package vade.ui.protocol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.longOrNull

/**
 * In-memory [VadeTransport]: frames the test pushes go to the client, frames the client
 * sends are recorded for assertions. `incoming` is a channel flow so [close] COMPLETES
 * the flow — that is exactly how a real websocket EOF ends the reader loop and starts
 * reconnect.
 */
class FakeTransport : VadeTransport {

    private val incomingChannel = Channel<String>(Channel.UNLIMITED)
    private val sentChannel = Channel<String>(Channel.UNLIMITED)

    val sentFlow = sentChannel.receiveAsFlow()

    /** Every frame the client sent, kept for the whole test (drain-safe counting). */
    val record: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf<String>())

    override val incoming = incomingChannel.receiveAsFlow()

    @Volatile
    var closedCount: Int = 0
        private set

    /** Core → client. */
    fun push(frame: String) {
        incomingChannel.trySend(frame)
    }

    /** Client → core: await the next frame the client sent (with a test deadline). */
    suspend fun awaitSent(timeoutMs: Long = 5000): String = withTimeout(timeoutMs) { sentChannel.receive() }

    /** Non-suspending read for drain-style assertions ([record] keeps everything anyway). */
    fun drainSent(): List<String> {
        val out = mutableListOf<String>()
        while (true) {
            out.add(sentChannel.tryReceive().getOrNull() ?: break)
        }
        return out
    }

    override suspend fun send(frame: String) {
        if (closedCount > 0) throw IOException("transport is closed")
        record.add(frame)
        sentChannel.trySend(frame)
    }

    override suspend fun close() {
        closedCount += 1
        incomingChannel.close()
    }
}

/**
 * [VadeTransportFactory] that hands out [FakeTransport]s and can fail the first N
 * connect attempts (reconnect tests).
 */
class FakeTransportFactory(
    private val failFirstN: Int = 0,
) : VadeTransportFactory {

    val transports = mutableListOf<FakeTransport>()
    val connectAttempts = mutableListOf<Int>()

    /** Gate a specific attempt until the test releases it (connect attempt N = 1-based). */
    val attemptGates = mutableMapOf<Int, CompletableDeferred<Unit>>()

    fun gateAttempt(n: Int): CompletableDeferred<Unit> =
        attemptGates.getOrPut(n) { CompletableDeferred() }

    private var created = 0

    override suspend fun connect(): VadeTransport {
        created += 1
        connectAttempts.add(created)
        attemptGates[created]?.await()
        if (created <= failFirstN) throw IOException("simulated connect failure #$created")
        val transport = FakeTransport()
        transports.add(transport)
        return transport
    }
}

/** Polls until [cond] holds or the deadline passes — timing robustness without sleeps-of-faith. */
suspend fun waitUntil(timeoutMs: Long = 5000, cond: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!cond()) {
        if (System.currentTimeMillis() > deadline) {
            error("condition not met within ${timeoutMs}ms")
        }
        delay(10)
    }
}

/** Event-frame helper mirroring core's `ConnectionSink.send`: method "event", params envelope. */
fun eventFrame(
    type: String,
    seq: Long,
    payload: String = "{}",
    coalesce: Boolean = false,
): String = buildString {
    append("""{"jsonrpc":"2.0","method":"event","params":{"type":"$type","seq":$seq,"payload":$payload""")
    if (coalesce) append(""","x-vade":{"coalesce":true}""")
    append("}}")
}

/** Response frame helper for a recorded request id. */
fun responseFrame(id: Long, result: String): String =
    """{"jsonrpc":"2.0","id":$id,"result":$result}"""

/** Error frame helper following rpc.json §envelope.error. */
fun errorFrame(id: Long, code: String, message: String, data: String? = null): String =
    """{"jsonrpc":"2.0","id":$id,"error":{"code":"$code","message":"$message","data":$data}}"""

/** Extracts the request method + params object text of the first captured client frame. */
fun requestOf(frame: String): Pair<String, String> {
    val obj = VadeJson.default.parseToJsonElement(frame) as kotlinx.serialization.json.JsonObject
    val method = (obj["method"] as kotlinx.serialization.json.JsonPrimitive).content
    return method to (obj["params"]?.toString() ?: "{}")
}

/** The `id` the client allocated on a captured request frame. */
fun idOf(frame: String): Long {
    val obj = VadeJson.default.parseToJsonElement(frame) as kotlinx.serialization.json.JsonObject
    return (obj["id"] as kotlinx.serialization.json.JsonPrimitive).longOrNull ?: -1L
}

/** Minimal client for behavior tests: tiny timeouts, keepalive off unless asked. */
fun testClient(factory: FakeTransportFactory, mutate: (VadeClientConfig) -> VadeClientConfig = { it }): VadeClient {
    val config = VadeClientConfig(
        host = "127.0.0.1",
        port = 1,
        browserSession = null,
        requestTimeoutMs = 2000,
        reconnectBaseMs = 5,
        reconnectCapMs = 40,
        keepaliveIntervalMs = null,
        json = VadeJson.default,
    )
    return VadeClient(mutate(config), factory)
}
