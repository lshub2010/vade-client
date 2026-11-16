package vade.ui.protocol

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * One duplex string-frame channel to the core. Frames are UTF-8 JSON documents —
 * either a JSON-RPC request/response/error or an `event` notification (see
 * [VadeClient]). Production implementation is [KtorVadeTransport]; tests drive the
 * client through an in-memory implementation of this interface.
 */
interface VadeTransport {
    /** Frames from the core. Completes when the transport closes (then [VadeClient] reconnects). */
    val incoming: Flow<String>

    /** Send one frame to the core. Throws when the transport is closed. */
    suspend fun send(frame: String)

    /** Close the transport; idempotent. */
    suspend fun close()
}

/**
 * Connects one [VadeTransport] per (re)connection attempt. Kept as a factory — not a
 * single connection — because reconnect (exponential backoff) is the client's job.
 */
interface VadeTransportFactory {
    /** Open a new transport. Throws on failure; the client treats any throw as "retry later". */
    suspend fun connect(): VadeTransport

    /** Release factory-owned resources (the shared [HttpClient]); called from [VadeClient.close]. */
    suspend fun dispose() {}
}

/**
 * `/ui/session` 的拒绝体 → `(code, message)`。
 *
 * core 的配对端点回的是**扁平**体 `{"code": "CODE_INVALID", "message": "..."}`
 * （`core/vade/transport/server.py`），不是 rpc 信封；这里也容忍 `{"error": {...}}`
 * 多包一层。解析不出来返回 `null` —— 调用方退回「HTTP 状态 + 原文」，绝不编造码。
 */
internal fun parsePairingErrorBody(body: String): Pair<String, String>? {
    val obj = runCatching { VadeJson.default.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject }
        .getOrNull() ?: return null
    val err = (obj["error"] as? kotlinx.serialization.json.JsonObject) ?: obj
    val code = err.strStrict("code")?.takeIf { it.isNotBlank() } ?: return null
    return code to (err.strStrict("message")?.takeIf { it.isNotBlank() } ?: code)
}

/**
 * Browser-session transport factory (RFC-0004 §8.2, the "pairing" path).
 *
 * 1. `POST /ui/session` with `{pair_code, client_name}` — mints the `__Host-ac_ui`
 *    cookie (HttpOnly, one-time pair code) and returns `device_id`. The cookie is
 *    captured ONCE per factory and reused for every reconnect — pair codes are
 *    single-use, so re-bootstrapping per attempt would burn one code per drop.
 * 2. `GET ws(s)://host:port/ws` upgrade with `Cookie: __Host-ac_ui=…`.
 *
 * On browser (wasm/JS) targets the fetch API cannot read `Set-Cookie`, and the browser
 * carries the cookie itself for same-origin sockets: pair through the page shell there
 * and leave [VadeClientConfig.browserSession] `null` — this factory is the JVM/Android path.
 */
class KtorTransportFactory(
    private val config: VadeClientConfig,
    private val client: HttpClient = HttpClient { install(WebSockets) },
) : VadeTransportFactory {

    @Volatile
    private var cookieHeader: String? = null

    private val bootstrapMutex = Mutex()

    override suspend fun connect(): VadeTransport {
        val cookie = config.browserSession?.let { ensureCookie() }
        val session = client.webSocketSession {
            url(websocketUrl())
            if (cookie != null) {
                // The JS engine may refuse custom headers on the upgrade — a browser
                // carries cookies itself, so a refusal there is harmless; on JVM the
                // header is the whole authentication.
                runCatching { header(HttpHeaders.Cookie, cookie) }
            }
        }
        return KtorVadeTransport(session)
    }

    override suspend fun dispose() {
        client.close()
    }

    private suspend fun ensureCookie(): String = bootstrapMutex.withLock {
        cookieHeader ?: run {
            val response = client.post {
                url(httpUrl() + "/ui/session")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"pair_code":${jsonString(config.browserSession!!.pairCode)},""" +
                        """"client_name":${jsonString(config.clientName)}}""",
                )
            }
            if (response.status != HttpStatusCode.OK) {
                // H3 · core 的拒绝体是 `{"code": "CODE_INVALID", "message": "..."}`
                // （`core/vade/transport/server.py` 的 `_json(400, {...})`）。以前把整段
                // body 塞进一个 AUTH_FAILED —— 于是「码过期」和「码抄错」与「没配对」
                // 在 UI 上不可区分。这里原码透传，码缺失时才退回 AUTH_FAILED。
                val body = response.bodyAsText()
                val coreError = parsePairingErrorBody(body)
                throw VadeException(
                    code = coreError?.first ?: VadeErrorCodes.AUTH_FAILED,
                    message = "POST /ui/session failed with HTTP ${response.status.value}: " +
                        (coreError?.second ?: body.take(200)),
                )
            }
            val token = response.headers.getAll(HttpHeaders.SetCookie).orEmpty()
                .firstNotNullOfOrNull { Regex("__Host-ac_ui=([^;]+)").find(it)?.groupValues?.get(1) }
                ?: throw VadeException(
                    code = VadeErrorCodes.AUTH_FAILED,
                    message = "/ui/session response carried no __Host-ac_ui cookie " +
                        "(browser targets cannot read Set-Cookie: pair through the page shell instead)",
                )
            val header = "__Host-ac_ui=$token"
            cookieHeader = header
            header
        }
    }

    private fun httpUrl(): String = "${if (config.tls) "https" else "http"}://${config.host}:${config.port}"

    private fun websocketUrl(): String = "${if (config.tls) "wss" else "ws"}://${config.host}:${config.port}/ws"

    private companion object {
        /** Minimal JSON string escaping for the two literals we send ourselves. */
        fun jsonString(raw: String): String = buildString {
            append('"')
            raw.forEach { ch ->
                when (ch) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
            append('"')
        }
    }
}

/**
 * [VadeTransport] over a Ktor WebSocket session. Core sends BINARY frames (UTF-8 JSON
 * bytes — `Connection.send_json` uses `websocket.send(bytes)`), so both [Frame.Binary]
 * and [Frame.Text] decode into strings here.
 */
class KtorVadeTransport(
    private val session: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession,
) : VadeTransport {

    override val incoming: Flow<String> = flow {
        val channel: ReceiveChannel<Frame> = session.incoming
        for (frame in channel) {
            when (frame) {
                is Frame.Text -> emit(frame.readText())
                is Frame.Binary -> emit(frame.data.decodeToString())
                is Frame.Close -> break
                else -> {} // ping/pong never reach the app layer on the ASGI side
            }
        }
    }

    override suspend fun send(frame: String) {
        session.send(Frame.Text(frame))
    }

    override suspend fun close() {
        runCatching { session.close(CloseReason(CloseReason.Codes.NORMAL, "client closing")) }
        runCatching { session.incoming.cancel() }
    }
}
