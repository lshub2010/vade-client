package vade.ui.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import vade.ui.protocol.VadeTransport
import vade.ui.protocol.VadeTransportFactory
import kotlin.concurrent.Volatile

/**
 * A [VadeTransport] that tees every inbound frame to synchronous listeners BEFORE the
 * protocol client's reader sees it, while still forwarding the frame downstream.
 *
 * Why this exists: the frozen protocol layer (untouchable for this batch) wraps 21 of
 * the 33 `ui_to_core` methods with typed calls — `session/list` has no wrapper and its
 * `rpc()`/`call()` are private, so [RealSessionSource] needs a raw request channel on
 * the SAME connection (a second connection would double-connect to core). Responses
 * are matched by id; raw ids are allocated from a 1e9 base so they can never collide
 * with the client's 0-based ids, and a response with an id the client does not
 * recognise is dropped silently by its `handleResponse` — tee-ing is therefore safe
 * for both directions.
 */
internal class TeeTransport(private val inner: VadeTransport) : VadeTransport {

    private val listenerMutex = Mutex()

    /** Copy-on-write snapshot list: the reader loop reads without suspending. */
    @Volatile
    private var listeners: List<(String) -> Unit> = emptyList()

    suspend fun addListener(listener: (String) -> Unit) {
        listenerMutex.withLock { listeners = listeners + listener }
    }

    suspend fun removeListener(listener: (String) -> Unit) {
        listenerMutex.withLock { listeners = listeners - listener }
    }

    override val incoming: Flow<String> = flow {
        // Exactly ONE collector owns inner.incoming (the protocol client's read loop).
        // Every frame is handed to the tee listeners synchronously, then forwarded —
        // no second collector ever competes for the underlying channel.
        inner.incoming.collect { frame ->
            listeners.forEach { listener -> runCatching { listener(frame) } }
            emit(frame)
        }
    }

    override suspend fun send(frame: String) = inner.send(frame)

    override suspend fun close() = inner.close()
}

/**
 * Factory wrapper handing every connection out as a [TeeTransport] and keeping the
 * CURRENT one reachable for raw calls ([RealSessionSource.rawRpc]). [reset] is used
 * between `disconnect()` and the next successful connect so a raw call cannot target
 * a dead transport.
 *
 * ## Ownership: the inner factory is never disposed here
 * [vade.ui.protocol.VadeClient.close] disposes the factory it was handed — that is
 * THIS tee, not the transport implementation. An injected factory
 * (`PairingRuntime.pairedFactory`) is **shared**: it caches the `__Host-ac_ui` cookie
 * (pair codes are single-use, so re-bootstrapping per drop burns one code each time)
 * and the very next `connect()` plus the pairing bridge keep using it. Disposing it
 * here closed the shared `HttpClient` for the whole process: after a `disconnect()`
 * every later connection failed at `connect()` and the client retried forever — the
 * permanent disconnect. Lifetime of the inner belongs to whoever built it:
 * [RealSessionSource] disposes only the factory IT built, in its own `disconnect()`.
 */
internal class TeeTransportFactory(private val inner: VadeTransportFactory) : VadeTransportFactory {

    @Volatile
    var current: TeeTransport? = null
        private set

    /** P0-4 · 透传「退休」状态：桥 dispose 掉内层 factory 之后，这一层也立刻不可用。 */
    override val isUsable: Boolean get() = inner.isUsable

    /** P0-1 · cookie 透传（持久化读的是真正持有 `__Host-ac_ui` 的那一层）。 */
    override val sessionCookie: String? get() = inner.sessionCookie

    override suspend fun connect(): VadeTransport =
        TeeTransport(inner.connect()).also { current = it }

    /** Only the tee is dropped; the inner factory is NOT disposed (see the class KDoc). */
    override suspend fun dispose() = reset()

    /** 摘下当前 tee 连接（原始帧通道）；下一次 `connect()` 会装一条新的。 */
    fun reset() {
        current = null
    }
}
