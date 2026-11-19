package vade.ui.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import vade.ui.protocol.BrowserPairing
import vade.ui.protocol.FakeTransport
import vade.ui.protocol.KtorTransportFactory
import vade.ui.protocol.VadeClientConfig
import vade.ui.protocol.VadeTransport
import vade.ui.protocol.VadeTransportFactory

/**
 * 缺陷⑩(b) · 换 core 的线上顺序与 best-effort 语义。
 *
 * 契约（任务棒给定的，core 一侧已实现）：`DELETE /ui/session`（带 `__Host-ac_ui` cookie）
 * → 204，吊销当前 UI 会话。这一组用例钉住：
 *
 *  1. **顺序**：旧 core 的 `DELETE /ui/session` 先于新 core 的配对（MockEngine 看得见的那一次
 *     真实 HTTP 请求，日志里排在新 core 的 `POST /ui/session` 之前）；
 *  2. **best-effort**：旧 core 回 500 / 超时都不阻塞切换 —— 新配置照样装上，吊销结果回传；
 *  3. **取消不清配置**：切换协程被取消时，旧 factory / endpoint 一个字节都不动，也不去配新
 *     core（一次性码没被烧）；
 *  4. **本地配置错误自己先挡**：端口越界不发任何请求、不碰旧配置；
 *  5. [PairingRuntime.unpair] 也是「先吊销、再清」，未配对时是 no-op。
 */
class CoreSwitchTest {

    /** 一次测试里两条 wire 共用的观测点：请求顺序 + DELETE 带上的 cookie。 */
    private class Wire {
        val log: MutableList<String> = mutableListOf()
        val cookieOnDelete: MutableList<String?> = mutableListOf()
    }

    /** 旧 core 的 factory：真 [KtorTransportFactory] + [MockEngine]（DELETE 真的走 HTTP 层）。 */
    private fun oldCoreFactory(
        wire: Wire,
        host: String = "old.local",
        deleteStatus: HttpStatusCode = HttpStatusCode.NoContent,
        deleteBody: String = "",
    ): KtorTransportFactory {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            wire.log += "old ${request.method.value} $path"
            when {
                request.method == HttpMethod.Post && path == "/ui/session" -> respond(
                    content = """{"device_id":"dev-old"}""",
                    status = HttpStatusCode.OK,
                    // 生产里这一次 POST 换回 `__Host-ac_ui`（core: 200 + Set-Cookie）。
                    headers = headersOf(HttpHeaders.SetCookie, "__Host-ac_ui=tok-old"),
                )
                request.method == HttpMethod.Delete && path == "/ui/session" -> {
                    wire.cookieOnDelete += request.headers[HttpHeaders.Cookie]
                    respond(content = deleteBody, status = deleteStatus)
                }
                else -> respond(content = "", status = HttpStatusCode.NotFound)
            }
        }
        return KtorTransportFactory(
            config = VadeClientConfig(
                host = host,
                port = 8443,
                browserSession = BrowserPairing("code-old"),
            ),
            client = HttpClient(engine),
        )
    }

    /**
     * 新 core 的 factory 替身：`connect()` 就是生产里那一次「`POST /ui/session` + WS 升级」，
     * 这里只在同一个 [Wire.log] 里落一条标记，让「先 DELETE 旧、再 POST 新」成为可断言的全序。
     */
    private class RecordingFactory(
        private val label: String,
        private val wire: Wire,
    ) : VadeTransportFactory {
        override suspend fun connect(): VadeTransport {
            wire.log += "$label POST /ui/session"
            return FakeTransport()
        }
    }

    /** 只为了把桥填到已配对态、且 `revokeSession` 由用例自己演的 factory。 */
    private class StubFactory(
        private val onRevoke: suspend () -> Unit,
    ) : VadeTransportFactory {
        override suspend fun connect(): VadeTransport = throw IllegalStateException("stub must not connect")
        override suspend fun revokeSession() = onRevoke()
    }

    private val newRaw = RawSourceStrings("real", "new.local", "9443", null)

    @BeforeTest
    fun resetBridgeBefore() = PairingRuntime.clearForTests()

    @AfterTest
    fun resetBridgeAfter() = PairingRuntime.clearForTests()

    // -------------------------------------------------------- 顺序：DELETE 先于新 core 配对

    @Test
    fun oldCoreIsRevokedBeforeTheNewCoreIsAskedToPair() = runBlocking {
        val wire = Wire()
        val old = oldCoreFactory(wire)
        // 旧 core 上真的有一张 __Host-ac_ui（= 生产里它配对成功的那一刻）。
        assertNotNull(old.ensureSessionCookie(), "先 bootstrap 一次，旧 core 才有本进程的 UI 会话")
        PairingRuntime.install(old, "old.local", 8443)
        wire.log.clear()

        val received = mutableListOf<VadeClientConfig>()
        val newFactory = RecordingFactory("new", wire)
        val outcome = PairingRuntime.switchCore(newRaw, "青松-NEW123") { config ->
            received += config
            newFactory
        }

        assertEquals(
            listOf("old DELETE /ui/session", "new POST /ui/session"),
            wire.log,
            "旧 core 的 DELETE /ui/session 必须先于新 core 的配对请求",
        )
        assertEquals(
            listOf<String?>("__Host-ac_ui=tok-old"),
            wire.cookieOnDelete,
            "吊销必须带上本机那张 __Host-ac_ui —— 不带 cookie 的 DELETE 吊销不了任何会话",
        )
        assertEquals(
            "青松-NEW123",
            received.single().browserSession?.pairCode,
            "新 core 的配对码原样进配置（码只在这一次尝试里活）",
        )
        assertEquals(SessionRevocation.Revoked, outcome.revocation)
        val connected = assertIs<PairingAttempt.Connected>(outcome.attempt)
        assertSame(newFactory, connected.factory)
        assertEquals("new.local:9443", PairingRuntime.pairedEndpoint, "成功后桥里是新 core")
    }

    // -------------------------------------------------------- best-effort：失败 / 超时不阻塞

    @Test
    fun revocationFailureDoesNotBlockTheSwitch() = runBlocking {
        val wire = Wire()
        val old = oldCoreFactory(
            wire,
            deleteStatus = HttpStatusCode.InternalServerError,
            deleteBody = """{"code":"INTERNAL","message":"revoke boom"}""",
        )
        old.ensureSessionCookie()
        PairingRuntime.install(old, "old.local", 8443)

        val outcome = PairingRuntime.switchCore(newRaw, "青松-NEW123") { RecordingFactory("new", wire) }

        val failed = assertIs<SessionRevocation.Failed>(outcome.revocation, "旧 core 回 500 = 吊销失败")
        assertTrue(
            failed.cause.message?.contains("500") == true,
            "失败原因要带 HTTP 状态（UI 提示写的是传输原话）：${failed.cause.message}",
        )
        assertIs<PairingAttempt.Connected>(outcome.attempt, "吊销失败不许阻塞切换")
        assertEquals("new.local:9443", PairingRuntime.pairedEndpoint, "新配置照样装上了")
        assertTrue(
            "old DELETE /ui/session" in wire.log && "new POST /ui/session" in wire.log,
            "两条请求都发过：${wire.log}",
        )
    }

    @Test
    fun revocationTimeoutDoesNotBlockTheSwitch() = runBlocking {
        val old = StubFactory { delay(10_000) }
        PairingRuntime.install(old, "old.local", 8443)

        val started = System.currentTimeMillis()
        val outcome = PairingRuntime.switchCore(
            raw = newRaw,
            enteredCode = "青松-NEW123",
            revokeTimeoutMs = 50,
        ) { RecordingFactory("new", Wire()) }
        val elapsed = System.currentTimeMillis() - started

        val failed = assertIs<SessionRevocation.Failed>(outcome.revocation)
        assertIs<SessionRevokeTimeoutException>(failed.cause, "超时是单独一类，UI 才能说清")
        assertIs<PairingAttempt.Connected>(outcome.attempt)
        assertTrue(elapsed < 5_000, "切换不许被一台关机的旧 core 拖住（实际 ${elapsed}ms）")
    }

    @Test
    fun deleteFailureStillClearsTheOldCookieLocally() = runBlocking {
        // 旧 core 拒绝吊销，但本地也不许继续攥着那张 cookie（换 core 之后它只会冒充登录态）。
        val wire = Wire()
        val old = oldCoreFactory(wire, deleteStatus = HttpStatusCode.Unauthorized)
        assertNotNull(old.ensureSessionCookie())
        PairingRuntime.install(old, "old.local", 8443)

        assertIs<SessionRevocation.Failed>(PairingRuntime.unpair(), "401 = 吊销失败")
        wire.log.clear()
        // cookie 已清：再吊销一次连请求都不发（没有可吊销的会话了）。
        old.revokeSession()
        assertTrue(wire.log.isEmpty(), "cookie 清掉之后 revokeSession 是 no-op：${wire.log}")
    }

    // -------------------------------------------------------- 取消 / 本地错误：不动旧配置

    @Test
    fun cancellingTheSwitchLeavesTheOldConfigInstalled() = runBlocking {
        val old = StubFactory { delay(60_000) }
        PairingRuntime.install(old, "10.0.0.9", 19999)
        val newFactory = RecordingFactory("new", Wire())

        var cancellation: Throwable? = null
        val job = launch(Dispatchers.Default) {
            try {
                PairingRuntime.switchCore(newRaw, "青松-NEW123", revokeTimeoutMs = 60_000) { newFactory }
            } catch (ce: CancellationException) {
                cancellation = ce
            }
        }
        delay(150)
        job.cancel()
        job.join()

        assertNotNull(cancellation, "取消必须原样抛给调用方，不能被吞成「吊销失败然后继续切」")
        assertEquals("10.0.0.9:19999", PairingRuntime.pairedEndpoint, "取消后旧 endpoint 一个字节都不动")
        assertSame(old, PairingRuntime.pairedFactory, "取消后旧 factory 还在桥里")
    }

    @Test
    fun theSwitchCancelledBeforeItFinishesNeverPairsTheNewCore() = runBlocking {
        val old = StubFactory { delay(60_000) }
        PairingRuntime.install(old, "10.0.0.9", 19999)
        val wire = Wire()
        val newFactory = RecordingFactory("new", wire)

        val outcome = withTimeoutOrNull(200) {
            PairingRuntime.switchCore(newRaw, "青松-NEW123", revokeTimeoutMs = 60_000) { newFactory }
        }

        assertNull(outcome, "取消里的切换不会返回结局")
        assertTrue(wire.log.isEmpty(), "取消之后不许去碰新 core（一次性码没被烧）：${wire.log}")
        assertEquals("10.0.0.9:19999", PairingRuntime.pairedEndpoint)
    }

    @Test
    fun badTargetConfigIsRejectedBeforeAnythingIsTouched() = runBlocking {
        val wire = Wire()
        val old = oldCoreFactory(wire)
        old.ensureSessionCookie()
        PairingRuntime.install(old, "old.local", 8443)
        wire.log.clear()

        val outcome = PairingRuntime.switchCore(
            RawSourceStrings("real", "new.local", "99999", null),
            "青松-NEW123",
        ) { RecordingFactory("new", wire) }

        val rejected = assertIs<PairingAttempt.Rejected>(outcome.attempt)
        assertEquals(PairingFailureKind.BadConfig, rejected.kind)
        assertEquals(SessionRevocation.NotNeeded, outcome.revocation, "本地就能判死的错误不该先吊销旧 core")
        assertTrue(wire.log.isEmpty(), "不许发任何请求：${wire.log}")
        assertEquals("old.local:8443", PairingRuntime.pairedEndpoint, "旧配置原样保留（填错不是断线）")
    }

    @Test
    fun anUnpairedRuntimeHasNothingToRevoke() = runBlocking {
        assertEquals(SessionRevocation.NotNeeded, PairingRuntime.unpair())
        assertEquals(PairingRuntimeState.NotBooted, PairingRuntime.state, "未配对时 unpair 不改桥")
    }

    // -------------------------------------------------------- 断开

    @Test
    fun unpairRevokesTheCurrentSessionThenClearsTheBridge() = runBlocking {
        val wire = Wire()
        val old = oldCoreFactory(wire)
        old.ensureSessionCookie()
        PairingRuntime.install(old, "old.local", 8443)
        wire.log.clear()

        assertEquals(SessionRevocation.Revoked, PairingRuntime.unpair())

        assertEquals(listOf("old DELETE /ui/session"), wire.log, "断开只吊销，不去配任何新 core")
        assertNull(PairingRuntime.pairedFactory, "断开后桥里没有 factory")
        assertEquals(PairingRuntimeState.Unpaired(), PairingRuntime.state, "桥回到未配对（引导卡可重新出现）")
    }
}
