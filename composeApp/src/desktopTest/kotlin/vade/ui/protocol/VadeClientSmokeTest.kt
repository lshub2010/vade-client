package vade.ui.protocol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.onSubscription
import org.junit.Assume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Smoke against a real core** — the evidence test for `shots/w1-proto/smoke.md`.
 *
 * Gated by environment variables so the ordinary `desktopTest` run stays hermetic:
 * without `VADE_SMOKE_PORT` the assumption fails and JUnit marks this test *skipped*
 * (never a silent pass). The smoke run sets:
 *
 * ```
 * VADE_SMOKE_HOST=127.0.0.1
 * VADE_SMOKE_PORT=<port from smoke_core.py banner>
 * VADE_SMOKE_PAIR_CODE=<pair_code from banner>
 * ```
 *
 * Flow: real `POST /ui/session` (pair code → cookie) → real WS upgrade → `ui/attach`
 * (null session: core creates one) → `ui/submit` → collect live turn events →
 * `session/usage.get`. Everything asserted here is what a real core answered.
 */
class VadeClientSmokeTest {

    private val host: String? = System.getenv("VADE_SMOKE_HOST") ?: "127.0.0.1"
    private val port: String? = System.getenv("VADE_SMOKE_PORT")
    private val pairCode: String? = System.getenv("VADE_SMOKE_PAIR_CODE")

    @Test
    fun attachSubmitAndLiveEventsAgainstARealCore() {
        Assume.assumeTrue("VADE_SMOKE_PORT set", port != null)
        Assume.assumeTrue("VADE_SMOKE_PAIR_CODE set", pairCode != null)

        runBlocking {
            val client = VadeClient(
                VadeClientConfig(
                    host = host!!,
                    port = port!!.toInt(),
                    browserSession = BrowserPairing(pairCode!!, clientName = "w1-proto-smoke"),
                    requestTimeoutMs = 15_000,
                    reconnectBaseMs = 200,
                    keepaliveIntervalMs = 5_000,
                ),
            )

            val events = mutableListOf<VadeEvent>()
            // Subscribe handshake FIRST: the flow is a hot SharedFlow with no replay, so
            // connecting before the collector is registered can silently drop the
            // earliest (replay/attach) events.
            val subscribed = CompletableDeferred<Unit>()
            val collectJob = CoroutineScope(Dispatchers.Default).launch {
                client.events
                    .onSubscription { subscribed.complete(Unit) }
                    .collect { synchronized(events) { events.add(it) } }
            }
            subscribed.await()

            try {
                client.connect()
                assertTrue(client.awaitConnected(15_000), "connected over a real WS upgrade")

                // ---- ui/attach (fresh session: neither session_id nor last_seq) ----
                val attach = client.attach(AttachParams(null, null))
                println("SMOKE ui/attach -> $attach")
                // core mints session ids as uuid4().hex (core/vade/session/models.py new_id):
                assertTrue(attach.sessionId.length == 32 && attach.sessionId.all { it.isDigit() || it in 'a'..'f' }, "session id echo (32-hex, core's uuid4().hex)")
                assertTrue(attach.snapshot != null, "a fresh session attach must answer with a snapshot")
                assertTrue(attach.snapshot!!.fromSeq >= 0)
                println("SMOKE snapshot.from_seq=${attach.snapshot.fromSeq} hasTasks=${attach.snapshot.hasTasks}")

                // ---- ui/submit ----
                val clientMsgId = "smoke-${System.currentTimeMillis()}"
                val submit = client.uiSubmit(SubmitParams(attach.sessionId, clientMsgId, listOf(ContentPart.TextPart("冒烟：你好，Vade。"))))
                println("SMOKE ui/submit -> $submit")

                // ---- live event stream ----
                waitUntil(timeoutMs = 30_000) {
                    synchronized(events) { events.any { it is TurnCompleted || it is TurnFailed || it is TurnInterrupted } }
                }
                synchronized(events) {
                    events.forEach { println("SMOKE event seq=${it.seq} ${it::class.simpleName} ${summarize(it)}") }
                }
                assertTrue(events.any { it is TurnQueued }, "turn.queued observed")
                assertTrue(events.any { it is TurnDelta }, "turn.delta observed")
                val attachedEvent = events.filterIsInstance<SessionAttached>().firstOrNull()
                assertTrue(attachedEvent != null, "session.attached observed")
                assertEquals(true, attachedEvent!!.payload.snapshot, "fresh attach answers snapshot=true")

                // ---- session/usage.get ----
                val usage = client.sessionUsageGet(SessionUsageGetParams(attach.sessionId))
                println("SMOKE session/usage.get -> $usage")

                // ---- last_seq bookkeeping over the real stream ----
                assertTrue(client.lastSeq >= attach.snapshot!!.fromSeq, "client.lastSeq advanced past the snapshot point")

                collectJob.cancel()
            } finally {
                runCatching { collectJob.cancel() }
                runCatching { client.close() }
            }
        }
    }

    private fun summarize(event: VadeEvent): String = when (event) {
        is TurnDelta -> "turn=${event.payload.turnId} part=${event.payload.partIndex} text=${event.payload.text.take(60)}"
        is TurnQueued -> "turn=${event.payload.turnId}"
        is TurnCompleted -> "turn=${event.payload.turnId} tokens=${event.payload.usage.totalTokens}"
        is TurnFailed -> "turn=${event.payload.turnId} code=${event.payload.code}"
        is SessionAttached -> "session=${event.payload.sessionId} snapshot=${event.payload.snapshot}"
        else -> ""
    }
}
