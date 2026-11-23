package vade.ui.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Headers
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import vade.ui.protocol.BrowserCookieUnavailableException
import vade.ui.protocol.BrowserPairing
import vade.ui.protocol.KtorTransportFactory
import vade.ui.protocol.VadeClientConfig
import vade.ui.protocol.VadeTransport
import vade.ui.protocol.VadeTransportFactory
import vade.ui.protocol.parseSessionToken

/**
 * T5/D8 · wasm 首次配对：浏览器读不到 `Set-Cookie` 时的两条通道 + 旧 core 的回退。
 *
 * The browser is simulated the only way a desktop test can: a `MockEngine` whose
 * `POST /ui/session` response exposes **no** `Set-Cookie` header (fetch forbids it
 * in a real browser) and either carries a body `session_token` (new core) or not
 * (old core). Everything under test — the opt-in header, precedence, memoisation,
 * revoke — is common code, so this pins the behaviour the wasm build runs.
 *
 * Fixtures never touch a socket and never leave the process.
 */
class BrowserTokenPairingTest {

    private class Wire {
        val posts: MutableList<HttpRequestData> = mutableListOf()
        val deletes: MutableList<HttpRequestData> = mutableListOf()
    }

    private fun config() = VadeClientConfig(
        host = "core.local",
        port = 8443,
        browserSession = BrowserPairing("CODE-T5"),
    )

    /**
     * One factory over a scripted engine. [postBody] / [postHeaders] describe the
     * mint response; [deleteStatus] the revoke answer.
     */
    private fun factory(
        wire: Wire,
        postBody: String,
        postHeaders: List<Pair<String, String>> = emptyList(),
        deleteStatus: HttpStatusCode = HttpStatusCode.NoContent,
    ): KtorTransportFactory {
        val engine = MockEngine { request ->
            when (request.method) {
                HttpMethod.Post -> {
                    wire.posts += request
                    respond(
                        content = postBody,
                        status = HttpStatusCode.OK,
                        headers = Headers.build {
                            append(HttpHeaders.ContentType, "application/json")
                            postHeaders.forEach { (name, value) -> append(name, value) }
                        },
                    )
                }
                HttpMethod.Delete -> {
                    wire.deletes += request
                    respond(content = "", status = deleteStatus)
                }
                else -> respond(content = "", status = HttpStatusCode.NotFound)
            }
        }
        return KtorTransportFactory(config = config(), client = HttpClient(engine))
    }

    @BeforeTest
    fun resetBridgeBefore() = PairingRuntime.clearForTests()

    @AfterTest
    fun resetBridgeAfter() = PairingRuntime.clearForTests()

    // ---------------------------------------------------------------- token 通道

    @Test
    fun aBodyTokenStandsInForTheUnreadableCookie() = runBlocking {
        val wire = Wire()
        val factory = factory(wire, """{"device_id":"ui-1","session_token":"tok-browser"}""")

        assertEquals(
            "__Host-ac_ui=tok-browser",
            factory.ensureSessionCookie(),
            "浏览器读不到 Set-Cookie 时，body 里的 session_token 就是这张会话",
        )
        assertEquals("tok-browser", factory.sessionCookie, "桥要能把它记进 Paired / 持久化")

        val post = wire.posts.single()
        assertEquals("1", post.headers["x-vade-session-token"], "必须显式 opt-in，原生端才走 cookie")
        assertEquals("/ui/session", post.url.encodedPath)
    }

    @Test
    fun aReadableCookieAlwaysWinsOverTheBodyCopy() = runBlocking {
        val wire = Wire()
        val factory = factory(
            wire,
            """{"device_id":"ui-1","session_token":"body-token"}""",
            postHeaders = listOf(HttpHeaders.SetCookie to "__Host-ac_ui=cookie-token"),
        )

        assertEquals(
            "__Host-ac_ui=cookie-token",
            factory.ensureSessionCookie(),
            "能读 Set-Cookie 的引擎不许改用 body 里的那个值",
        )
        assertEquals("cookie-token", factory.sessionCookie)
    }

    @Test
    fun theMintHappensOnceSoAOneTimeCodeIsNeverBurnedTwice() = runBlocking {
        val wire = Wire()
        val factory = factory(wire, """{"device_id":"ui-1","session_token":"tok-browser"}""")

        repeat(3) { assertEquals("__Host-ac_ui=tok-browser", factory.ensureSessionCookie()) }
        assertEquals(1, wire.posts.size, "每次重连都重新 POST 会烧掉用户的一次性码")
    }

    // ------------------------------------------------------- 旧 core / 回退 cookie

    @Test
    fun anOldCoreFallsBackToTheBrowsersOwnCookieInsteadOfThrowing() = runBlocking {
        val wire = Wire()
        // 旧 core: ignores the opt-in header, answers only device_id. No Set-Cookie is
        // visible to a browser either — the cookie is in the browser's jar.
        val factory = factory(wire, """{"device_id":"ui-1"}""")

        assertNull(
            factory.ensureSessionCookie(),
            "拿不到可读凭据时不能立刻判死：/ws 升级才是真正的证明",
        )
        assertNull(factory.sessionCookie)
        // The consumed code is not sent again on the next reconnect attempt.
        assertNull(factory.ensureSessionCookie())
        assertEquals(1, wire.posts.size, "回退模式下也必须只兑换一次")
    }

    @Test
    fun theBrowserManagedModeRevokesThroughTheBrowsersCookieJar() = runBlocking {
        val wire = Wire()
        val factory = factory(wire, """{"device_id":"ui-1"}""")
        assertNull(factory.ensureSessionCookie())

        factory.revokeSession()

        val delete = wire.deletes.single()
        assertEquals("/ui/session", delete.url.encodedPath)
        assertNull(
            delete.headers[HttpHeaders.Cookie],
            "本进程没有 cookie 值可发；同源浏览器会自己把 __Host-ac_ui 带在这次 DELETE 上",
        )
    }

    @Test
    fun aFactoryThatNeverBootstrappedStillSendsNoRevokeRequest() = runBlocking {
        val wire = Wire()
        val factory = factory(wire, """{"device_id":"ui-1"}""")

        factory.revokeSession()

        assertTrue(wire.deletes.isEmpty(), "没配对过就没有本进程的会话可吊销")
    }

    // ------------------------------------------------------------- 失败分类 / 文案

    @Test
    fun aBrowserThatCouldNotKeepTheSessionIsNotReportedAsABadCode() {
        assertEquals(
            PairingFailureKind.BrowserCookieMissing,
            pairingFailureKindOf(
                BrowserCookieUnavailableException("code accepted, socket rejected"),
            ),
            "码已经被 core 用掉了，不能归到 BadCode（用户会拿同一个码重试）",
        )
    }

    @Test
    fun aRejectedAttemptMapsToTheBrowserCookieMissingKind() = runBlocking {
        val failing = object : VadeTransportFactory {
            override suspend fun connect(): VadeTransport =
                throw BrowserCookieUnavailableException("the browser did not keep __Host-ac_ui")
        }

        val attempt = firstRunPairingAttempt(
            raw = RawSourceStrings(source = "real", host = "core.local", port = "8443", pairCode = null),
            enteredCode = "CODE-T5",
            transportFactory = { failing },
        )

        val rejected = assertIs<PairingAttempt.Rejected>(attempt)
        assertEquals(PairingFailureKind.BrowserCookieMissing, rejected.kind)
        assertTrue(rejected.cause.message.orEmpty().contains("__Host-ac_ui"))
    }

    // ------------------------------------------------------------------- 解码边界

    @Test
    fun theBodyTokenParserRejectsEverythingButARealString() {
        assertEquals("abc", parseSessionToken("""{"session_token":"abc"}"""))
        assertNull(parseSessionToken("""{"device_id":"ui-1"}"""), "旧 core 没有这个字段")
        assertNull(parseSessionToken("""{"session_token":123}"""), "类型不对 → 当作没有，不抛")
        assertNull(parseSessionToken("""{"session_token":"   "}"""))
        assertNull(parseSessionToken("not json"), "解析失败不能变成配对失败")
        assertNull(parseSessionToken(""))
    }
}
