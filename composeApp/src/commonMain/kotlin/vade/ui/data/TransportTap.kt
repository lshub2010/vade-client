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
 */
internal class TeeTransportFactory(private val inner: VadeTransportFactory) : VadeTransportFactory {

    @Volatile
    var current: TeeTransport? = null
        private set

    override suspend fun connect(): VadeTransport =
        TeeTransport(inner.connect()).also { current = it }

    override suspend fun dispose() = inner.dispose()

    fun reset() {
        current = null
    }
}
