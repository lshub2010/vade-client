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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import vade.ui.protocol.BrowserPairing
import vade.ui.protocol.FakeTransport
import vade.ui.protocol.KtorTransportFactory
import vade.ui.protocol.VadeClientConfig
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException
import vade.ui.protocol.VadeTransport
import vade.ui.protocol.VadeTransportFactory

/**
 * 缺陷⑩(b) · 换 core 的线上顺序与 best-effort 语义。
 *
 * 契约（P1-6 / P0-4）：**先拿到新 core 的 cookie，成功后才吊销旧会话**。旧顺序
 * （先吊销旧的、清本地、再去配新的）在新 core 拒绝时会把用户丢在未配对态 ——
 * 旧 cookie 已吊销、factory 已 dispose，没有任何回退。这一组用例钉住：
 *
 *  1. **顺序**：新 core 的 `POST /ui/session` 先于旧 core 的 `DELETE /ui/session`；吊销那一步
 *     在日志里排在配对成功之后（`MockEngine` 看得见真实 HTTP 请求的那一条）；
 *  2. **失败可回退**：新 core 拒绝配对 → 一次吊销都不发、旧 factory 不 dispose、桥仍是
 *     `Paired(old)` —— 用户还在原来的连接上；
 *  3. **best-effort**：新 core 到手之后，旧 core 回 500 / 超时都不阻塞切换，结果回传 UI；
 *  4. **取消不清配置**：取消发生在配对阶段 → 旧 factory / endpoint 一个字节都不动，也不发
 *     吊销；取消发生在吊销阶段 → 旧连接原样保留，刚拿到的新 factory 被放掉（不许泄漏）；
 *  5. **本地配置错误自己先挡**：端口越界不发任何请求、不碰旧配置；
 *  6. [PairingRuntime.unpair] 是「先吊销、再清」，未配对时是 no-op。
 */
class CoreSwitchTest {

    /** 一次测试里两条 wire 共用的观测点：请求顺序 + DELETE 带上的 cookie。 */
    private class Wire {
        val log: MutableList<String> = mutableListOf()
        val cookieOnDelete: MutableList<String?> = mutableListOf()
        /** 新 core 收到配对请求的那一刻，桥处于什么状态（钉住「clear 先于 POST」）。 */
        val statesAtConnect: MutableList<PairingRuntimeState> = mutableListOf()
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
            wire.statesAtConnect += PairingRuntime.state
            wire.log += "$label POST /ui/session"
            return FakeTransport()
        }
    }

    /** 新 core 拒绝配对：请求确实发出去了，但连接链抛错。 */
    private class RejectingFactory(private val wire: Wire) : VadeTransportFactory {
        override suspend fun connect(): VadeTransport {
            wire.statesAtConnect += PairingRuntime.state
            wire.log += "new POST /ui/session"
            throw VadeException(VadeErrorCodes.AUTH_FAILED, "code was rejected")
        }
    }

    /** 只为了把桥填到已配对态、且 `revokeSession` 由用例自己演的 factory。 */
    private class StubFactory(
        private val onRevoke: suspend () -> Unit,
    ) : VadeTransportFactory {
        override suspend fun connect(): VadeTransport = throw IllegalStateException("stub must not connect")
        override suspend fun revokeSession() = onRevoke()
    }

    /**
     * 新 core 的替身：`connect()` 成功（= 已经换到 cookie），并让用例看见它有没有被放掉。
     * 用来钉「取消在吊销阶段时，那份刚到手的新凭据不许泄漏」。
     */
    private class TrackedFactory : VadeTransportFactory {
        private var disposed = false
        override val isUsable: Boolean get() = !disposed
        override val sessionCookie: String? = "tok-new"
        override suspend fun connect(): VadeTransport = FakeTransport()
        override suspend fun dispose() {
            disposed = true
        }
    }

    private val newRaw = RawSourceStrings("real", "new.local", "9443", null)

    @BeforeTest
    fun resetBridgeBefore() = PairingRuntime.clearForTests()

    @AfterTest
    fun resetBridgeAfter() = PairingRuntime.clearForTests()

    // -------------------------------------------------------- 顺序：先配新 core，成功后才吊销旧会话

    @Test
    fun theNewCoreIsPairedBeforeTheOldSessionIsRevoked() = runBlocking {
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
            listOf("new POST /ui/session", "old DELETE /ui/session"),
            wire.log,
            "必须先拿到新 core 的 cookie，成功之后才吊销旧会话 —— 否则新 core 一失败就没有回退",
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
        // 新 core 收到 POST 的那一刻，桥里仍是旧 core —— 这就是「失败可回退」的前提。
        val atConnect = assertIs<PairingRuntimeState.Paired>(
            wire.statesAtConnect.single(),
            "新 core 配对期间旧凭据必须还在（失败才能原样退回旧连接）",
        )
        assertEquals("old.local", atConnect.host)
        assertSame(old, atConnect.factory)
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
    fun cancellingWhileTheNewCoreIsAskedToPairLeavesTheOldCoreAlone() = runBlocking {
        // 取消落在**第一步**（兑换一次性码）：一次吊销请求都不许发，旧连接原样保留。
        val old = StubFactory { delay(60_000) }
        PairingRuntime.install(old, "10.0.0.9", 19999)
        val wire = Wire()
        val hanging = object : VadeTransportFactory {
            override suspend fun connect(): VadeTransport {
                wire.statesAtConnect += PairingRuntime.state
                wire.log += "new POST /ui/session"
                awaitCancellation() // 配对在途：码正在兑换，cookie 还没到手
            }
        }

        val outcome = withTimeoutOrNull(200) {
            PairingRuntime.switchCore(newRaw, "青松-NEW123", revokeTimeoutMs = 60_000) { hanging }
        }

        assertNull(outcome, "取消里的切换不会返回结局")
        assertEquals(
            listOf("new POST /ui/session"),
            wire.log,
            "取消发生在配对阶段：旧 core 一次吊销请求都不许收到",
        )
        assertEquals("10.0.0.9:19999", PairingRuntime.pairedEndpoint, "取消后旧 endpoint 一个字节都不动")
        assertSame(old, PairingRuntime.pairedFactory, "取消后旧 factory 还在桥里")
    }

    @Test
    fun cancellingDuringTheOldRevocationKeepsTheOldCoreAndRetiresTheNewFactory() = runBlocking {
        // 取消落在**第三步**（新 cookie 已到手，正在吊销旧会话）：桥还没切，旧连接必须留着；
        // 而刚拿到的新 factory 既不进桥也没人再引用 —— 必须放掉，否则每取消一次漏一个 HttpClient。
        val revoking = CompletableDeferred<Unit>()
        val old = object : VadeTransportFactory {
            override suspend fun connect(): VadeTransport = throw IllegalStateException("old must not connect")
            override suspend fun revokeSession() {
                revoking.complete(Unit)
                delay(60_000)
            }
        }
        PairingRuntime.install(old, "10.0.0.9", 19999)
        val newFactory = TrackedFactory()

        val job = launch(Dispatchers.Default) {
            runCatching { PairingRuntime.switchCore(newRaw, "青松-NEW123", revokeTimeoutMs = 60_000) { newFactory } }
        }
        withTimeout(5_000) { revoking.await() }
        job.cancel()
        job.join()

        assertEquals("10.0.0.9:19999", PairingRuntime.pairedEndpoint, "取消后旧连接原样保留（桥还没切）")
        assertSame(old, PairingRuntime.pairedFactory)
        assertFalse(newFactory.isUsable, "被取消的那次切换必须放掉刚建好的新 factory（资源不许泄漏）")
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
    fun aRejectedNewCoreLeavesTheOldCorePairedAndUntouched() = runBlocking {
        // 新 core 拒绝 = 回退：旧会话不被吊销、旧 factory 不被 dispose、桥仍是 Paired(old)。
        val wire = Wire()
        val old = oldCoreFactory(wire)
        old.ensureSessionCookie()
        var disposed = false
        val counting = object : VadeTransportFactory {
            override suspend fun connect(): VadeTransport = old.connect()
            override suspend fun revokeSession() = old.revokeSession()
            override suspend fun dispose() {
                disposed = true
                old.dispose()
            }
        }
        PairingRuntime.install(counting, "old.local", 8443)
        wire.log.clear()

        val outcome = PairingRuntime.switchCore(newRaw, "青松-NEW123") { RejectingFactory(wire) }

        val rejected = assertIs<PairingAttempt.Rejected>(outcome.attempt, "新 core 拒绝配对，必须是可重试的 Rejected")
        assertEquals(PairingFailureKind.BadCode, rejected.kind)
        assertEquals(
            SessionRevocation.NotNeeded,
            outcome.revocation,
            "新 core 失败时一次吊销都不许发：旧连接还在服务，凭据不能先作废",
        )
        assertEquals(
            "old.local:8443",
            PairingRuntime.pairedEndpoint,
            "旧配置原样保留（用户还在原来的连接上，可以重试或继续用）",
        )
        assertSame(counting, PairingRuntime.pairedFactory, "旧 factory 不许离开桥")
        assertFalse(disposed, "旧 factory 的资源不许被 dispose —— 它还要继续服务这条连接")
        assertTrue(
            wire.log.none { it.startsWith("old DELETE") },
            "旧 core 一个吊销请求都不许收到：${wire.log}",
        )
        assertIs<PairingRuntimeState.Paired>(
            wire.statesAtConnect.single(),
            "新 core 收到 POST 时旧凭据必须还在（这就是失败可回退的原因）",
        )
        Unit
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
