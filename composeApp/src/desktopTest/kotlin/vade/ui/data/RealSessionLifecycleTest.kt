package vade.ui.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import vade.ui.protocol.FakeTransportFactory
import vade.ui.protocol.VadeTransport
import vade.ui.protocol.VadeTransportFactory
import vade.ui.protocol.eventFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lifecycle behaviour of [RealSessionSource] against an in-memory transport:
 * state sequence, reconnect backoff sequence, single-connection under concurrency,
 * disconnect semantics.
 */
class RealSessionLifecycleTest {

    @Test
    fun connectWalksDisconnectedConnectingConnected() {
        val factory = FakeTransportFactory()
        val source = RealSessionSource(dataTestConfig(), factory)
        runBlocking {
            factory.gateAttempt(1) // hold the first transport so Connecting is observable
            // The collector's view, as a channel: receive() wakes exactly when a state was
            // recorded (event-driven — no wall-clock polling of the flow or the list), so
            // the sequence assert below reads a deterministic observation.
            val seen = Channel<RealSessionState>(Channel.UNLIMITED)
            val collector = launch { source.state.collect { seen.trySend(it) } }
            yield() // let the collector subscribe (StateFlow emits the current value first)
            source.connect()
            val observed = mutableListOf<RealSessionState>()
            suspend fun awaitObserved(expected: RealSessionState) {
                while (true) {
                    val s = seen.receiveInsured("waiting for $expected; observed so far $observed")
                    observed.add(s)
                    if (s == expected) return
                }
            }

            awaitObserved(RealSessionState.Connecting)
            factory.gateAttempt(1).complete(Unit)
            awaitObserved(RealSessionState.Connected)
            val deduped = observed.fold(mutableListOf<RealSessionState>()) { acc, s ->
                if (acc.lastOrNull() != s) acc.add(s)
                acc
            }
            assertEquals(
                listOf(RealSessionState.Disconnected, RealSessionState.Connecting, RealSessionState.Connected),
                deduped,
            )
            source.disconnect()
            collector.cancel()
        }
    }

    @Test
    fun reconnectAfterConsecutiveFailuresFollowsTheExponentialBackoffSequence() {
        val factory = FakeTransportFactory(failFirstN = 5)
        val source = RealSessionSource(dataTestConfig(baseMs = 5, capMs = 40), factory)
        runBlocking {
            // The collector's view, as a channel: receive() wakes exactly when a state was
            // recorded, and every Reconnecting seen is kept — the final assert pins the
            // exact backoff sequence off what the collector ACTUALLY observed, in order,
            // deterministically even on a loaded machine (no wall-clock polling).
            val seen = Channel<RealSessionState>(Channel.UNLIMITED)
            val collector = launch { source.state.collect { seen.trySend(it) } }
            yield()
            // Gates 2..6 are registered UP FRONT so every failing connect attempt (2..5)
            // blocks after its backoff delay — each Reconnecting(attempt, delayMs) state
            // stays observable until the test releases it. Attempt 6 succeeds.
            val gates = (2..6).associateWith { factory.gateAttempt(it) }
            source.connect()
            val expected = listOf(1 to 5L, 2 to 10L, 3 to 20L, 4 to 40L, 5 to 40L)
            val reconnects = mutableListOf<RealSessionState.Reconnecting>()
            for ((attempt, delayMs) in expected) {
                while (true) {
                    val s = seen.receiveInsured("Reconnecting($attempt, $delayMs); observed so far $reconnects")
                    if (s is RealSessionState.Reconnecting) reconnects.add(s)
                    if (s == RealSessionState.Reconnecting(attempt, delayMs)) break
                    check(s != RealSessionState.Connected) {
                        "Connected arrived while still expecting Reconnecting($attempt, $delayMs); observed $reconnects"
                    }
                }
                gates[attempt + 1]?.complete(Unit)
            }
            gates[6]?.complete(Unit)
            while (true) {
                val s = seen.receiveInsured("Connected; observed so far $reconnects")
                if (s is RealSessionState.Reconnecting) reconnects.add(s)
                if (s == RealSessionState.Connected) break
            }
            // Six connect attempts were made, exactly one transport ever succeeded.
            assertEquals(6, factory.connectAttempts.size)
            assertEquals(1, factory.transports.size)
            assertEquals(expected, reconnects.map { it.attempt to it.delayMs })
            source.disconnect()
            collector.cancel()
        }
    }

    @Test
    fun twoConcurrentConnectsProduceExactlyOneConnection() {
        val factory = FakeTransportFactory()
        val source = RealSessionSource(dataTestConfig(), factory)
        runBlocking {
            val a = launch(Dispatchers.Default) { source.connect() }
            val b = launch(Dispatchers.Default) { source.connect() }
            a.join()
            b.join()
            source.state.awaitState("the source to become Connected") { it == RealSessionState.Connected }
            assertEquals(1, factory.connectAttempts.size)
            assertEquals(1, factory.transports.size)
            source.disconnect()
        }
    }

    @Test
    fun disconnectClosesTheTransportAndStopsEventDispatch() {
        val factory = FakeTransportFactory()
        val source = RealSessionSource(dataTestConfig(), factory)
        runBlocking {
            source.connect()
            source.state.awaitState("the source to become Connected") { it == RealSessionState.Connected }
            val received = Channel<VadeEvent>(Channel.UNLIMITED)
            val collector = launch {
                source.observeEvents().collect { received.trySend(it) }
            }
            yield()
            source.disconnect()
            assertEquals(RealSessionState.Disconnected, source.state.value)
            // close() closes the transport explicitly AND the runner closes it again on
            // cleanup — so "at least one close" is the contract (the protocol client
            // double-close is its own idempotent business).
            assertTrue(factory.transports[0].closedCount >= 1)
            // The client's reader is dead after close(): a frame pushed now must not
            // surface through the UI seam.
            factory.transports[0].push(
                eventFrame("turn.delta", 1, """{"turn_id":"t1","text":"x","part_index":0,"kind":"text"}"""),
            )
            val late = withTimeoutOrNull(400) { received.receive() }
            assertNull(late)
            collector.cancel()
        }
    }

    @Test
    fun connectAfterDisconnectBuildsAFreshClientAndConnectsAgain() {
        val factory = FakeTransportFactory()
        val source = RealSessionSource(dataTestConfig(), factory)
        runBlocking {
            source.connect()
            source.state.awaitState("the source to become Connected") { it == RealSessionState.Connected }
            source.disconnect()
            assertEquals(RealSessionState.Disconnected, source.state.value)
            source.connect()
            source.state.awaitState("the source to become Connected") { it == RealSessionState.Connected }
            assertEquals(2, factory.transports.size)
            source.disconnect()
        }
    }

    @Test
    fun disconnectIsIdempotentAndSafeWhenNeverConnected() {
        val factory = FakeTransportFactory()
        val source = RealSessionSource(dataTestConfig(), factory)
        runBlocking {
            source.disconnect() // never connected: must not throw
            assertEquals(RealSessionState.Disconnected, source.state.value)
            source.connect()
            source.state.awaitState("the source to become Connected") { it == RealSessionState.Connected }
            source.disconnect()
            source.disconnect() // twice in a row: still fine, transport was closed
            assertEquals(RealSessionState.Disconnected, source.state.value)
            assertTrue(factory.transports[0].closedCount >= 1)
            assertTrue(source.client == null)
        }
    }

    // ---------------------------------------------------------------- factory ownership (P1)

    /**
     * A factory whose [dispose] REALLY closes the door: every later [connect] throws.
     * That is exactly [vade.ui.protocol.KtorTransportFactory], whose `dispose()` closes
     * its own `HttpClient` and cannot be revived.
     *
     * The shared [FakeTransportFactory] does NOT override `dispose()` (the interface
     * default is a no-op), which is why the "disconnect killed the shared factory"
     * regression stayed invisible: with this fake the same call throws instead.
     */
    private class ClosingTransportFactory : VadeTransportFactory {
        private val inner = FakeTransportFactory()

        @Volatile
        var disposeCount: Int = 0
            private set

        val connectAttempts: Int get() = inner.connectAttempts.size

        override suspend fun connect(): VadeTransport {
            check(disposeCount == 0) {
                "connect() on a disposed factory — a closed HttpClient cannot be revived " +
                    "(this is the permanent-disconnect shape)"
            }
            return inner.connect()
        }

        override suspend fun dispose() {
            disposeCount += 1
        }
    }

    /**
     * P1 · 注入的 factory 是**共享**的（`PairingRuntime.pairedFactory`：cookie 缓存、
     * 配对桥、下一次 connect 都用它），`disconnect()` 只摘 tee，不 dispose 它。
     * 旧实现在这里让第二次 `connect()` 永远失败 —— 即「断开一次 = 永久断连」。
     */
    @Test
    fun injectedFactorySurvivesDisconnectAndServesTheNextConnect() {
        val factory = ClosingTransportFactory()
        val source = RealSessionSource(dataTestConfig(), factory)
        runBlocking {
            source.connect()
            source.state.awaitState("the first connection") { it == RealSessionState.Connected }
            source.disconnect()
            assertEquals(
                0,
                factory.disposeCount,
                "注入的 factory 不归源所有 —— disconnect 只摘 tee，dispose 会关掉持有者的 HttpClient",
            )
            source.connect()
            source.state.awaitState("the second connection through the SAME shared factory") {
                it == RealSessionState.Connected
            }
            assertEquals(2, factory.connectAttempts, "第二次 connect 必须真的经同一个共享 factory 建连")
            assertEquals(0, factory.disposeCount)
            source.disconnect()
        }
    }

    /**
     * P1 · 换源的现场（引导卡配对成功后 `App()` 重建 UiState）：旧源被 disconnect、
     * 新源拿到**同一个** `PairingRuntime.pairedFactory`。旧源若 dispose 了它，新源从
     * 第一次 connect 起就必然失败，而 UI 只显示「一直连不上」。
     */
    @Test
    fun sharedFactoryStillServesTheSourceThatReplacesTheDisconnectedOne() {
        val factory = ClosingTransportFactory()
        val first = RealSessionSource(dataTestConfig(), factory)
        val second = RealSessionSource(dataTestConfig(), factory)
        runBlocking {
            first.connect()
            first.state.awaitState("the old source to connect") { it == RealSessionState.Connected }
            first.disconnect()
            second.connect()
            second.state.awaitState("the replacement source (same shared factory)") {
                it == RealSessionState.Connected
            }
            assertEquals(2, factory.connectAttempts)
            assertEquals(0, factory.disposeCount, "换源时旧源不得把关着的门带走")
            second.disconnect()
        }
    }

    /**
     * P1 · 没注入 factory 时工厂是**自建**的：它必须由源 dispose（否则 HttpClient 泄漏），
     * 且下一次 `connect()` 必须**重建**一个 —— 复用 dispose 过的那个就是永久断连
     * （旧 `innerFactory by lazy` 的形态）。
     */
    @Test
    fun selfBuiltFactoryIsDisposedOnDisconnectAndRebuiltOnTheNextConnect() {
        val built = mutableListOf<ClosingTransportFactory>()
        val source = RealSessionSource(
            config = dataTestConfig(),
            ownedFactoryProvider = { ClosingTransportFactory().also(built::add) },
        )
        runBlocking {
            source.connect()
            source.state.awaitState("the first connection") { it == RealSessionState.Connected }
            assertEquals(1, built.size, "第一次 connect 建一个自建工厂")
            source.disconnect()
            assertEquals(1, built[0].disposeCount, "自建的工厂必须由源 dispose —— 没人会替它关 HttpClient")
            source.connect()
            source.state.awaitState("the second connection (a rebuilt factory, not the disposed one)") {
                it == RealSessionState.Connected
            }
            assertEquals(2, built.size, "dispose 过的工厂不可复用 —— 第二次 connect 必须重新建")
            assertEquals(0, built[1].disposeCount, "新工厂在断开之前不得被关")
            source.disconnect()
            assertEquals(1, built[1].disposeCount)
        }
    }
}
