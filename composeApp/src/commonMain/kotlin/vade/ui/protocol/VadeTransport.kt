package vade.ui.protocol

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.delete
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

    /**
     * P0-4 · 这个 factory 还能不能用来建连接。
     *
     * 桥在「换 core / 断开」时把旧 factory [dispose] 掉（它的 `HttpClient` 已经关了），
     * 之后任何重连都不许把它复活：`VadeClient.runLoop` 见 `false` 直接终止，
     * `RealSessionSource` 见 `false` 直接失败 —— 而不是按退避无限重试成「一直在重连」。
     */
    val isUsable: Boolean get() = true

    /**
     * P0-1 · 本 factory 持有的 `__Host-ac_ui` 的**值**（不含头名）。`null` = 没有 UI 会话
     * （device 会话 / 测试替身 / 还没 bootstrap）。
     *
     * 持久化层在配对成功后读它落盘；重启后由 [VadeClientConfig.sessionCookie] 重新交回
     * factory，于是重连**不再**兑换一次性码（码已经被消费）。
     */
    val sessionCookie: String? get() = null

    /** Open a new transport. Throws on failure; the client treats any throw as "retry later". */
    suspend fun connect(): VadeTransport

    /**
     * 吊销本 factory 持有的 UI 会话（`DELETE /ui/session`，契约：204 no body）——「换 core /
     * 断开」时把旧 core 上这张 `__Host-ac_ui` 凭证作废，别让它一直挂到过期。
     *
     * 默认 no-op：device 会话、页面壳自带的 cookie、测试替身**没有**本进程 mint 的 UI
     * 会话可吊销，对它们「无事可做」就是成功。校验（超时 / 失败不许阻塞切换）在
     * `PairingRuntime` 一侧，这里只负责把这一次请求发出去。
     */
    suspend fun revokeSession() {}

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
 * T5/D8 · `POST /ui/session` 的 body token，给读不到 `Set-Cookie` 的浏览器。
 *
 * core 只在客户端显式带 `x-vade-session-token: 1` **且** Origin 通过闸门时才回这个字段
 * （`core/vade/transport/server.py`），值是 `__Host-ac_ui` 的同一份凭据。缺字段 / 类型不对
 * 一律返回 `null` —— 调用方退回「浏览器托管 cookie」模式，绝不把解析失败当成配对失败。
 *
 * 这个值**只许留在进程内存**：它是 HttpOnly cookie 的拷贝，写进 localStorage/磁盘等于
 * 把 30 天有效的 UI 凭据交给同源的任何脚本（XSS 直接变现）。
 */
internal fun parseSessionToken(body: String): String? {
    if (body.isBlank()) return null
    val obj = runCatching { VadeJson.default.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject }
        .getOrNull() ?: return null
    return runCatching { obj.strStrict("session_token") }.getOrNull()
        ?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * T5 · 码是有效的（`POST /ui/session` 已 200），但浏览器没把会话带上来：本运行时读不到
 * `Set-Cookie`，响应里也没有 body token，`/ws` 升级仍被拒。
 *
 * 单独一个类型是为了让配对卡说「浏览器没保存会话」而不是「码错」—— 后者会让用户拿着
 * 一个**已经被消费**的码反复重试。
 */
class BrowserCookieUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Browser-session transport factory (RFC-0004 §8.2, the "pairing" path).
 *
 * 1. `POST /ui/session` with `{pair_code, client_name}` — mints the `__Host-ac_ui`
 *    cookie (HttpOnly, one-time pair code) and returns `device_id`. The cookie is
 *    captured ONCE per factory and reused for every reconnect — pair codes are
 *    single-use, so re-bootstrapping per attempt would burn one code per drop.
 * 2. `GET ws(s)://host:port/ws` upgrade with `Cookie: __Host-ac_ui=…`.
 *
 * ## Two credential channels, one session (T5/D8)
 *
 * A JVM/Android engine reads `Set-Cookie` and uses it directly. A browser (wasm)
 * cannot: `Set-Cookie` is a forbidden response header. So the request opts in with
 * `x-vade-session-token: 1` and the core — only for an allowlisted browser Origin —
 * mirrors the same token in the JSON body. The value is the *same* session row, so
 * `DELETE /ui/session` revokes it exactly like the cookie.
 *
 * If neither channel yields a value (an older core, or a response the browser was
 * allowed to receive but could not read), this factory does **not** throw: the
 * browser has already stored the cookie on its own (same-origin page), and the
 * `/ws` upgrade is the real proof. That mode is memoised ([browserManagedCookie])
 * so a reconnect never re-sends the consumed pair code. When the upgrade is then
 * rejected the factory raises [BrowserCookieUnavailableException] instead of a
 * generic `AUTH_FAILED`, because "your code was accepted but the browser did not
 * keep the cookie" is a different next step from "that code is wrong".
 */
class KtorTransportFactory(
    private val config: VadeClientConfig,
    /**
     * r5-tls · 默认 client 从 [platformHttpClient] 来，把 `allowInsecureTls`（= 用户在警告
     * 弹层里确认过）带到引擎的 TLS 配置上。测试仍可注入 `HttpClient(MockEngine)` ——
     * 注入的 client 不经过平台 TLS 配置，本用例集因此不依赖 socket。
     */
    private val client: HttpClient = platformHttpClient(config.allowInsecureTls),
) : VadeTransportFactory {

    /**
     * P0-1 · 初始 cookie：优先用配置里持久化下来的那张（[VadeClientConfig.sessionCookie]，
     * 重启恢复路径），否则 `null`（等 `POST /ui/session` 换一张）。
     */
    @Volatile
    private var cookieHeader: String? = config.sessionCookie?.let { "$COOKIE_NAME=${it.value}" }

    /**
     * T5 · 本 factory 已经把一次性码兑换成了一次会话，但这次会话的凭据**不在**
     * `cookieHeader` 里：core 没回可读的 `Set-Cookie`，也没回 `session_token`
     * （旧 core，或响应被浏览器/CORS 过滤）。凭据在浏览器的 cookie jar 里，由浏览器
     * 自己带上 —— 这个 flag 只负责「别再 POST 一次，码已经被消费了」。
     */
    @Volatile
    private var browserManagedCookie = false

    /** P0-4 · 被桥退休之后不再可用；`connect()` 直接抛而不是让调用方无限重试。 */
    @Volatile
    private var disposed = false

    private val bootstrapMutex = Mutex()

    override val isUsable: Boolean get() = !disposed

    override val sessionCookie: String?
        get() = cookieHeader?.substringAfter("=", "")?.takeIf { it.isNotEmpty() }

    override suspend fun connect(): VadeTransport {
        if (disposed) {
            throw VadeException(
                VadeException.TRANSPORT_CLOSED_CODE,
                "this transport factory was retired (core switch / unpair); a retired factory must not reconnect",
            )
        }
        val cookie = ensureSessionCookie()
        val session = try {
            client.webSocketSession {
                url(websocketUrl())
                if (cookie != null) {
                    // The JS engine may refuse custom headers on the upgrade — a browser
                    // carries cookies itself, so a refusal there is harmless; on JVM the
                    // header is the whole authentication.
                    runCatching { header(HttpHeaders.Cookie, cookie) }
                }
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // P0-2 · 升级被拒 = 这张 cookie 已经没人认了（core 重启过 / 过期 / 被吊销）。
            // 先本地作废再抛出：否则下一次重连还会带着同一张死 cookie，成了僵尸重连。
            if (isCredentialHandshakeFailure(t)) {
                cookieHeader = null
                if (browserManagedCookie) {
                    // T5 · 码是有效的（POST 已 200），但浏览器没把会话带上来。这不是
                    // 「码错」，也不能重试同一个码（已消费）—— 给可执行的下一步。
                    throw BrowserCookieUnavailableException(
                        "POST /ui/session accepted the pair code, but the /ws upgrade was rejected: " +
                            "this runtime cannot read Set-Cookie and the response carried no session_token, " +
                            "so the session cookie never reached this page. Serve the page from the core's " +
                            "own origin (same scheme/host/port, e.g. a reverse proxy) or pair from the " +
                            "desktop app / Android; the one-time code has been consumed — take a new one.",
                        t,
                    )
                }
                throw VadeException(
                    VadeErrorCodes.AUTH_FAILED,
                    "websocket upgrade rejected the stored __Host-ac_ui cookie: ${t.message}",
                )
            }
            throw t
        }
        return KtorVadeTransport(session)
    }

    override suspend fun dispose() {
        disposed = true
        client.close()
    }

    /**
     * `POST /ui/session` 那一步（换 `__Host-ac_ui` cookie），单独成函数以便 [connect] 在 WS
     * 升级前调用、[revokeSession] 的用例也能只走 HTTP。
     *
     * - 有一次性码（[VadeClientConfig.browserSession]）→ `POST /ui/session` 换一张；
     * - 没有码但有持久化的 cookie（重启恢复）→ 直接用它，**一次请求都不发**（码早已消费，
     *   再 POST 只会 401）；
     * - 两者都没有（device 会话）→ `null`。
     */
    internal suspend fun ensureSessionCookie(): String? =
        config.browserSession?.let { ensureCookie() } ?: cookieHeader

    /**
     * `DELETE /ui/session`（契约：204）—— 吊销本 factory 名下的 UI 会话。
     *
     * 只在**已经**拿到 cookie 时发：没 bootstrap 过就没有本进程的 UI 会话可吊销，再拿一次性
     * 配对码去 POST 只会白烧一个码（[ensureCookie] 的 memo 里没有码可用）。
     *
     * T5 · 浏览器托管模式（[browserManagedCookie]）例外：本进程读不到 cookie 的值，但
     * **浏览器**会把这张 cookie 带在同源请求上，所以照样发 DELETE（不带 `Cookie` 头），
     * 让用户断开时旧 core 上的会话真的作废。那次请求若不是同源，core 回 401，由调用方
     * 按 best-effort 处理。
     *
     * P1-3 · **只有 core 确认（2xx）之后才忘掉本地 cookie**。旧实现用 `finally` 无条件清：
     * `switchCore` 在吊销这一步被取消（或旧 core 超时 / 拒绝）时，桥仍然是 `Paired(old)`、
     * factory 也还是那一个，但本地 cookie 已经没了 —— 下一次重连不带 cookie，core 按 device
     * peer 走 Noise，升级可能"成功"却一个字节都不回，界面显示已连接而所有 RPC 超时。
     * 用户点了取消却把旧连接悄悄弄坏，比干净失败更难诊断。
     *
     * 失败原样抛给 `PairingRuntime` 的 best-effort 包装（超时 / 失败不阻塞切换，只记录 +
     * 提示）。撤销成功后本地不再持有这张凭证；撤销失败时它可能仍然有效，留着才是对的。
     */
    override suspend fun revokeSession() {
        val cookie = cookieHeader
        if (cookie == null) {
            if (!browserManagedCookie) return
            val response = client.delete { url(httpUrl() + "/ui/session") }
            if (response.status.value !in 200..299) {
                throw VadeException(
                    code = VadeErrorCodes.AUTH_FAILED,
                    message = "DELETE /ui/session failed with HTTP ${response.status.value}: " +
                        response.bodyAsText().take(200),
                )
            }
            return
        }
        val response = client.delete {
            url(httpUrl() + "/ui/session")
            header(HttpHeaders.Cookie, cookie)
        }
        if (response.status.value !in 200..299) {
            throw VadeException(
                code = VadeErrorCodes.AUTH_FAILED,
                message = "DELETE /ui/session failed with HTTP ${response.status.value}: " +
                    response.bodyAsText().take(200),
            )
        }
        // 到这里 core 已经确认吊销：本机再留着这张 cookie 只会冒充登录态。
        cookieHeader = null
    }

    private suspend fun ensureCookie(): String? = bootstrapMutex.withLock {
        cookieHeader ?: if (browserManagedCookie) {
            // 码已经被消费过一次；凭据在浏览器 jar 里，别再 POST。
            null
        } else run {
            val response = client.post {
                url(httpUrl() + "/ui/session")
                contentType(ContentType.Application.Json)
                // T5 · 显式声明「本运行时读不到 Set-Cookie」。core 只在 Origin 也通过
                // 闸门时才把同一个 token 放进 body；原生客户端不带 Origin，永远走 cookie。
                header(SESSION_TOKEN_HEADER, "1")
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
            val body = response.bodyAsText()
            // Cookie first: when the engine can read it, that is the credential the
            // server itself set, and the response body is not consulted at all.
            val token = response.headers.getAll(HttpHeaders.SetCookie).orEmpty()
                .firstNotNullOfOrNull { Regex("__Host-ac_ui=([^;]+)").find(it)?.groupValues?.get(1) }
                ?: parseSessionToken(body)
            if (token == null) {
                // Neither channel: an older core (it ignores the opt-in header) or a
                // response the browser was allowed to receive but not to read. The
                // browser has already stored the cookie; `/ws` is the proof. Memoise
                // it so the consumed pair code is never re-sent.
                browserManagedCookie = true
                return@run null
            }
            val header = "$COOKIE_NAME=$token"
            cookieHeader = header
            header
        }
    }

    /**
     * r5-tls · `https`/`wss` vs `http`/`ws` 只由 [VadeClientConfig.tls] 决定。
     * `internal`（不是 private）是为了让 `TlsPairingTest` 直接钉住两个 URL —— 一个
     * `MockEngine` 只能看见 HTTP 的那一次请求，看不见 WS 升级的 URL。
     */
    internal fun httpUrl(): String = "${if (config.tls) "https" else "http"}://${config.host}:${config.port}"

    internal fun websocketUrl(): String = "${if (config.tls) "wss" else "ws"}://${config.host}:${config.port}/ws"

    private companion object {
        /** `Set-Cookie` 里的头名（RFC-0004 §8.2）。 */
        const val COOKIE_NAME = "__Host-ac_ui"

        /** T5 · 显式 opt-in：本运行时读不到 `Set-Cookie`，请把同一个 token 放进 body。 */
        const val SESSION_TOKEN_HEADER = "x-vade-session-token"

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
 * P0-2 · 这次失败是不是「本机那张 `__Host-ac_ui` 已经不被 core 认了」。
 *
 * `/ws` 的正常拒绝形态是握手阶段的状态码（401 / 403）；Ktor 会把它包成
 * `ResponseException`（`expectSuccess` 打开时）或 `WebSocketException("Handshake exception,
 * expected status code 101 but was 401")`。两种都认，另外再看一眼 cause 链 —— 引擎经常把
 * 真正的状态码埋在 `cause` 里。
 *
 * 只用于**清除本地凭据**这个决定，因此宁可保守：普通网络错误（拒绝连接 / 超时 / DNS）
 * 一个都不命中，否则一次 core 没起来就会把用户的有效 cookie 删掉。
 */
fun isCredentialHandshakeFailure(t: Throwable): Boolean {
    var current: Throwable? = t
    var depth = 0
    while (current != null && depth < 8) {
        val status = (current as? io.ktor.client.plugins.ResponseException)?.response?.status?.value
        if (status == 401 || status == 403) return true
        val message = current.message.orEmpty()
        // P1-1 · 状态码按**词边界**匹配：裸子串会把端口 `4013` / `14013` 或正文里的一段
        // 数字当成 401 —— 那条路上紧接着就是「删掉磁盘上的凭据」，一次误判 = 用户回终端捞码。
        if (HTTP_AUTH_STATUS.containsMatchIn(message) ||
            message.contains("Unauthorized", ignoreCase = true) ||
            message.contains("Forbidden", ignoreCase = true)
        ) {
            return true
        }
        current = current.cause
        depth += 1
    }
    return false
}

/**
 * 握手拒绝的状态码，**带词边界**：`\b401\b` 命中 `HTTP 401` / `code 401 but was`，
 * 不命中端口 `:4013` 或 `4013`。
 */
private val HTTP_AUTH_STATUS = Regex("""\b(?:401|403)\b""")


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
