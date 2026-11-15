package vade.ui.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import vade.ui.protocol.FakeTransportFactory
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
}
