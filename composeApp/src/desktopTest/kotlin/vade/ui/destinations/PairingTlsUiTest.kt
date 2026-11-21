package vade.ui.destinations

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import vade.ui.data.CoreSwitchOutcome
import vade.ui.data.PairingAttempt
import vade.ui.data.PairingFailureKind
import vade.ui.data.PairingRuntime
import vade.ui.data.RawSourceStrings
import vade.ui.data.SessionRevocation
import vade.ui.data.SourceConfig
import vade.ui.data.firstRunPairingAttempt
import vade.ui.protocol.KtorTransportFactory
import vade.ui.protocol.TlsTrustMode
import vade.ui.protocol.VadeClientConfig
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException
import vade.ui.protocol.VadeTransport
import vade.ui.protocol.VadeTransportFactory
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin

/**
 * r5-tls · HTTPS 开关 + 自签证书警告的**真组合**用例。
 *
 * 六条契约里的 UI 那几条都钉在这里（URL 与 config 的断言在 `data/TlsPairingTest`）：
 *  ① 开了 HTTPS 的普通提交**先按系统信任链**试一次（`allowInsecureTls = false`）—— 合法 CA
 *    直接成功，没有「只要 https 就 trust-all」这条路；
 *  ② 只有**证书不被信任**时才挂确认层；从这次失败到确认之前，`MockEngine` 零请求（TLS 在
 *    HTTP 之前失败，一次性码没被烧）；
 *  ③ 「取消」：零请求、零重试，且码还在输入框里；
 *  ④ 「仍然连接」：**恰好一次** `POST /ui/session`，URL 是 `https`，且 trust-all 只在这一条
 *    重试上打开（`InsecureConfirmed`）；
 *  ⑤ 开关关着：不弹层、照旧提交（`tls = false`）；
 *  ⑥ 「更换 core」的面板：同一份开关、同一道闸门（第一次切换走系统链，确认后才是 trust-all）。
 *
 * 表单在两条路里是**同一个** [PairingForm]，所以这里直接组合表单与面板本体（不经环境变量、
 * 不依赖 core）。提交跑在测试自己的 [Dispatchers.Default] 作用域上（表单的 `submitScope`
 * 注入缝）：一次真 HTTP 往返不挂在测试时钟上，断言只看「请求有没有发」与「发了几次」。
 */
@OptIn(ExperimentalTestApi::class)
class PairingTlsUiTest {

    /** 一次用例的 wire：请求全序（方法 + 完整 URL）+ 其中 POST 的那几次。 */
    private class Wire {
        // 跨线程读写（提交在 Dispatchers.Default 上跑，断言在测试线程）：用并发容器，
        // 不靠「循环读总会看见」这种运气。
        val requests: MutableList<String> = CopyOnWriteArrayList()
        val posts: MutableList<String> = CopyOnWriteArrayList()

        /**
         * 只有 HTTP 的 client —— 故意不装 `WebSockets`：`connect()` 在 `POST /ui/session`
         * 之后立刻抛（插件缺失），于是「恰好一次 POST」是一次真实的、可数的请求，而不会去
         * 握手一个假 socket、也不会多出任何一次请求。
         */
        fun client(): HttpClient = HttpClient(
            MockEngine { request ->
                requests += "${request.method.value} ${request.url}"
                if (request.method == HttpMethod.Post && request.url.encodedPath == "/ui/session") {
                    posts += request.url.toString()
                    respond(
                        content = """{"device_id":"dev-tls"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.SetCookie, "__Host-ac_ui=tok-tls"),
                    )
                } else {
                    respond(content = "", status = HttpStatusCode.NotFound)
                }
            },
        )
    }

    private class MarkerFactory : VadeTransportFactory {
        override suspend fun connect(): VadeTransport = throw IllegalStateException("marker must not connect")
    }

    /** 提交作用域（表单的注入缝）：不挂在组合上，也不挂在测试时钟上。 */
    private val submitScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @BeforeTest
    fun resetBridgeBefore() = PairingRuntime.clearForTests()

    @AfterTest
    fun resetBridgeAfter() {
        submitScope.cancel()
        PairingRuntime.clearForTests()
        CoreSwitchNotice.text = null
        CoreSwitchNotice.submitting.value = false
    }

    /**
     * 码输入框的匹配器（与 `PairingCardUiTest` 同一条）：表单里有三个可输入框，语义树上
     * 都只有「可输入文本」，只能靠 tag 定位码框。
     */
    private fun codeField() = hasSetTextAction() and hasAnyAncestor(hasTestTag(PAIRING_CODE_FIELD_TAG))

    /** 弹层的语义节点数（0 = 没弹）。 */
    private fun ComposeUiTest.dialogs(): Int =
        onAllNodesWithTag(PAIRING_TLS_DIALOG_TAG).fetchSemanticsNodes().size

    // ------------------------------------------ ②③④ 闸门：先系统信任链，证书失败才确认

    @Test
    fun tlsOnTriesTheSystemChainFirstAndSendsNoRequestBeforeTheUserConfirms() = runComposeUiTest {
        val wire = Wire()
        val seen: MutableList<VadeClientConfig> = CopyOnWriteArrayList()
        // 正常提交收到的 (tls, insecure) 快照 —— 断言放在组合之外，避免异常被
        // submitScope 吞掉之后只剩一个「弹层没出现」的超时。
        val systemChainSeen: MutableList<Pair<Boolean, Boolean>> = CopyOnWriteArrayList()
        val selfSignedAttempts = AtomicInteger(0)

        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                PairingForm(
                    initialHost = "core.local",
                    initialPort = "8443",
                    submitLabel = PAIRING_BUTTON_LABEL,
                    submittingLabel = PAIRING_SUBMITTING,
                    submitScope = submitScope,
                    initialTls = true,
                    onSubmit = { _, _, _, tls, insecure ->
                        // P0-6 · 普通提交 = SystemChain：`tls` 只是协议，「接受任意证书」是另一件事。
                        systemChainSeen += tls to insecure
                        // 自签证书：TLS 握手就失败 —— 请求根本没发出去（一次性码没被烧）。
                        PairingAttempt.Rejected(
                            PairingFailureKind.CertificateUntrusted,
                            VadeException(
                                VadeErrorCodes.AUTH_FAILED,
                                "SSLHandshakeException: PKIX path building failed",
                            ),
                        )
                    },
                    onSubmitSelfSigned = { host, port, code, tls ->
                        selfSignedAttempts.incrementAndGet()
                        // 真连接链（MockEngine），只为把「一次 POST」变成可数的事实。
                        firstRunPairingAttempt(
                            raw = RawSourceStrings(
                                source = SourceConfig.REAL,
                                host = host,
                                port = port,
                                pairCode = null,
                                tls = tls,
                            ),
                            enteredCode = code,
                            allowInsecureTls = true,
                            transportFactory = { config ->
                                seen += config
                                KtorTransportFactory(config, wire.client())
                            },
                        )
                    },
                )
            }
        }
        waitForIdle()

        // 开关默认关：label 在，弹层不在。
        onNodeWithText(PAIRING_TLS_LABEL).assertExists()
        assertEquals(0, dialogs(), "默认关，不该有弹层")

        onNode(codeField()).performTextInput("青松-TLS123")
        onNodeWithText(PAIRING_BUTTON_LABEL).performClick()
        waitUntil(timeoutMillis = 5_000) { dialogs() == 1 }
        waitForIdle()

        // ② 证书不被信任 → 挂确认层。这一次**先**按系统信任链试过，且一个 HTTP 请求都没发。
        onNodeWithText(PAIRING_TLS_WARNING_TITLE).assertExists()
        onNodeWithText(PAIRING_TLS_CONFIRM_LABEL).assertExists()
        assertEquals(listOf(true to false), systemChainSeen, "先按系统信任链试一次（不是先弹窗）")
        assertEquals(0, selfSignedAttempts.get(), "未确认不许走 trust-all")
        assertTrue(wire.requests.isEmpty(), "证书失败发生在 HTTP 之前：一个请求都没发 ${wire.requests}")

        // ③ 取消：仍然零请求，码原样留着（一次性码没被烧）。
        onNodeWithText(PAIRING_TLS_CANCEL_LABEL).performClick()
        waitUntil(timeoutMillis = 5_000) { dialogs() == 0 }
        waitForIdle()

        assertEquals(listOf(true to false), systemChainSeen, "取消 = 不重试（没有第二次提交）")
        assertEquals(0, selfSignedAttempts.get(), "取消 = 不开 trust-all")
        assertTrue(wire.requests.isEmpty(), "取消不发任何请求：${wire.requests}")
        onNode(codeField()).assertTextContains("青松-TLS123") // 取消不烧配对码：输入原样保留
        onNodeWithText(PAIRING_BUTTON_LABEL).assertIsEnabled()

        // ④ 再点一次 → 确认「仍然连接」→ 恰好一次 POST，且走 https + InsecureConfirmed。
        onNodeWithText(PAIRING_BUTTON_LABEL).performClick()
        waitUntil(timeoutMillis = 5_000) { dialogs() == 1 }
        onNodeWithText(PAIRING_TLS_CONFIRM_LABEL).performClick()
        waitUntil(timeoutMillis = 10_000) { wire.posts.isNotEmpty() }
        waitForIdle()

        assertEquals(1, selfSignedAttempts.get(), "确认之后才走 trust-all，而且只走一次")
        assertEquals(1, wire.posts.size, "恰好一次 POST /ui/session：${wire.requests}")
        assertEquals("https://core.local:8443/ui/session", wire.posts.single())
        val config = seen.single()
        assertTrue(config.tls, "确认后的配对必须拨 https")
        assertTrue(config.allowInsecureTls, "确认后的配对才允许 trust-all")
        assertEquals("青松-TLS123", config.browserSession?.pairCode, "码原样进配置（只在这一次尝试里活）")
    }

    // ------------------------------------------------ P1-4 确认绑定 endpoint / 表单变更作废

    /** 地址输入框（同 `codeField()` 的做法：三个可输入框只能靠 tag 区分）。 */
    private fun hostField() = hasSetTextAction() and hasAnyAncestor(hasTestTag(PAIRING_HOST_FIELD_TAG))

    /**
     * P1-4 · 「仍然连接」确认的是**失败那一刻的快照目标**。
     *
     * 旧实现点确认时才去读实时表单：对 A 的证书失败 → 把地址改成 B → 点确认，trust-all
     * 就打到 B（对任意地址的空白授权）。现在：
     *  ① 弹层必须写出它要信任的 `host:port`；
     *  ② 改地址 = 警告立刻作废（不许确认旧目标）；
     *  ③ 再提交得到的是新目标自己的警告，确认重试的也是新目标。
     */
    @Test
    fun theSelfSignedConfirmationIsBoundToTheEndpointThatFailed() = runComposeUiTest {
        val systemChainHosts: MutableList<String> = CopyOnWriteArrayList()
        val selfSignedHosts: MutableList<String> = CopyOnWriteArrayList()

        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                PairingForm(
                    initialHost = "a.local",
                    initialPort = "8443",
                    submitLabel = PAIRING_BUTTON_LABEL,
                    submittingLabel = PAIRING_SUBMITTING,
                    submitScope = submitScope,
                    initialTls = true,
                    onSubmit = { host, _, _, _, _ ->
                        systemChainHosts += host
                        PairingAttempt.Rejected(
                            PairingFailureKind.CertificateUntrusted,
                            VadeException(
                                VadeErrorCodes.AUTH_FAILED,
                                "SSLHandshakeException: PKIX path building failed",
                            ),
                        )
                    },
                    onSubmitSelfSigned = { host, _, _, _ ->
                        selfSignedHosts += host
                        PairingAttempt.Rejected(
                            PairingFailureKind.Network,
                            VadeException(VadeErrorCodes.UNAVAILABLE, "自签重试在用例里不建连接"),
                        )
                    },
                )
            }
        }
        waitForIdle()

        onNode(codeField()).performTextInput("青松-BIND01")
        onNodeWithText(PAIRING_BUTTON_LABEL).performClick()
        waitUntil(timeoutMillis = 5_000) { dialogs() == 1 }
        waitForIdle()

        // ① 确认对象是写出来的那一台，不是一张空白授权。
        onNodeWithTag(PAIRING_TLS_TARGET_TAG)
            .assertTextContains(tlsWarningTargetLine("a.local:8443"), substring = true)

        // ② 改地址 → 警告作废，且不许偷偷按旧目标重试。
        onNode(hostField()).performTextClearance()
        onNode(hostField()).performTextInput("b.local")
        waitUntil(timeoutMillis = 5_000) { dialogs() == 0 }
        waitForIdle()
        assertEquals(0, selfSignedHosts.size, "地址改了：旧目标的确认必须作废，不许发 trust-all 重试")

        // ③ 新目标重新走系统信任链，拿到自己的警告；确认重试的必须是 b.local。
        onNodeWithText(PAIRING_BUTTON_LABEL).performClick()
        waitUntil(timeoutMillis = 5_000) { dialogs() == 1 }
        waitForIdle()
        onNodeWithTag(PAIRING_TLS_TARGET_TAG)
            .assertTextContains(tlsWarningTargetLine("b.local:8443"), substring = true)
        onNodeWithText(PAIRING_TLS_CONFIRM_LABEL).performClick()
        waitUntil(timeoutMillis = 5_000) { selfSignedHosts.isNotEmpty() }
        waitForIdle()

        assertEquals(listOf("b.local"), selfSignedHosts, "确认的是 b.local 自己的证书")
        assertEquals(listOf("a.local", "b.local"), systemChainHosts, "每次提交各走一次系统信任链")
    }

    /**
     * P1-4 的另一个变体：警告显示后关掉 HTTPS 开关。
     *
     * 旧实现确认时 `allowInsecureTls && submittedTls` 为假 → 静默退回**明文**提交，一次性码
     * 走明文。现在协议一改警告就作废：既没有确认按钮，也没有任何提交。
     */
    @Test
    fun turningTlsOffInvalidatesTheSelfSignedWarningInsteadOfFallingBackToPlaintext() = runComposeUiTest {
        val plainAttempts = AtomicInteger(0)
        val selfSignedAttempts = AtomicInteger(0)

        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                PairingForm(
                    initialHost = "a.local",
                    initialPort = "8443",
                    submitLabel = PAIRING_BUTTON_LABEL,
                    submittingLabel = PAIRING_SUBMITTING,
                    submitScope = submitScope,
                    initialTls = true,
                    onSubmit = { _, _, _, _, insecure ->
                        if (insecure) selfSignedAttempts.incrementAndGet() else plainAttempts.incrementAndGet()
                        PairingAttempt.Rejected(
                            PairingFailureKind.CertificateUntrusted,
                            VadeException(VadeErrorCodes.AUTH_FAILED, "SSLHandshakeException: PKIX path building failed"),
                        )
                    },
                    onSubmitSelfSigned = { _, _, _, _ ->
                        selfSignedAttempts.incrementAndGet()
                        PairingAttempt.Rejected(
                            PairingFailureKind.Network,
                            VadeException(VadeErrorCodes.UNAVAILABLE, "自签重试在用例里不建连接"),
                        )
                    },
                )
            }
        }
        waitForIdle()

        onNode(codeField()).performTextInput("青松-BIND02")
        onNodeWithText(PAIRING_BUTTON_LABEL).performClick()
        waitUntil(timeoutMillis = 5_000) { dialogs() == 1 }
        waitForIdle()

        // 关掉 HTTPS：警告针对的协议已经不存在。
        onNodeWithTag(PAIRING_TLS_SWITCH_TAG).performClick()
        waitUntil(timeoutMillis = 5_000) { dialogs() == 0 }
        waitForIdle()

        onNodeWithText(PAIRING_TLS_CONFIRM_LABEL).assertDoesNotExist()
        assertEquals(0, selfSignedAttempts.get(), "关掉 HTTPS 之后不许再走 trust-all")
        assertEquals(1, plainAttempts.get(), "只许有最初那一次系统信任链尝试：不作废就等于静默明文提交")
    }

    // -------------------------------------------------------- ⑤ 默认：不弹层、走明文

    @Test
    fun tlsOffSubmitsImmediatelyWithNoDialogAndNoTls() = runComposeUiTest {
        val calls: MutableList<Boolean> = CopyOnWriteArrayList()

        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                PairingForm(
                    initialHost = "core.local",
                    initialPort = "8443",
                    submitLabel = PAIRING_BUTTON_LABEL,
                    submittingLabel = PAIRING_SUBMITTING,
                    submitScope = submitScope,
                    onSubmit = { host, _, _, tls, _ ->
                        calls += tls
                        PairingAttempt.Connected(MarkerFactory(), host, 8443)
                    },
                )
            }
        }
        waitForIdle()

        onNode(codeField()).performTextInput("青松-PLAIN1")
        onNodeWithText(PAIRING_BUTTON_LABEL).performClick()
        waitUntil(timeoutMillis = 10_000) { calls.isNotEmpty() }
        waitForIdle()

        assertEquals(1, calls.size, "开关关着 = 旧行为：直接提交")
        assertFalse(calls.single(), "没有打开 HTTPS 就不许带 tls = true")
        assertEquals(0, dialogs(), "开关关着不弹自签证书警告")
    }

    // -------------------------------------------------------- ⑥ 换 core：同一份闸门

    @Test
    fun theCoreSwitchPanelHasTheSameSwitchAndTheSameGate() = runComposeUiTest {
        PairingRuntime.noteBooted()
        // 面板的第一段必须走 SystemChain；只有确认之后才允许 trust-all —— 与首配卡同一道闸门。
        val systemChain: MutableList<RawSourceStrings> = CopyOnWriteArrayList()
        val selfSigned: MutableList<RawSourceStrings> = CopyOnWriteArrayList()

        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                CoreSwitchPanel(
                    onBack = {},
                    onSwitch = { raw, _ ->
                        systemChain += raw
                        CoreSwitchOutcome(
                            revocation = SessionRevocation.NotNeeded,
                            attempt = PairingAttempt.Rejected(
                                PairingFailureKind.CertificateUntrusted,
                                VadeException(
                                    VadeErrorCodes.AUTH_FAILED,
                                    "SSLHandshakeException: PKIX path building failed",
                                ),
                            ),
                        )
                    },
                    onSwitchSelfSigned = { raw, _ ->
                        selfSigned += raw
                        CoreSwitchOutcome(
                            revocation = SessionRevocation.NotNeeded,
                            attempt = PairingAttempt.Connected(
                                MarkerFactory(),
                                raw.host ?: "127.0.0.1",
                                8443,
                                raw.tls,
                                TlsTrustMode.InsecureConfirmed,
                            ),
                        )
                    },
                )
            }
        }
        waitForIdle()

        // 面板里也是那一个开关（不是第二套）。
        onNodeWithTag(PAIRING_TLS_SWITCH_TAG).assertExists()

        onNodeWithTag(PAIRING_TLS_SWITCH_TAG).performClick()
        onNode(codeField()).performTextInput("青松-PANEL1")
        onNodeWithText(SWITCH_BUTTON_LABEL).performClick()
        waitUntil(timeoutMillis = 5_000) { dialogs() == 1 }
        waitForIdle()

        assertEquals(1, systemChain.size, "面板的第一次提交走系统信任链")
        assertEquals(0, selfSigned.size, "确认之前不许走 trust-all")
        assertTrue(systemChain.single().tls, "面板里的开关要跟着 RawSourceStrings 走")

        // 面板是滚动列：确认按钮可能在视口下半（滚动祖先把它带进来再点）。
        onNodeWithText(PAIRING_TLS_CONFIRM_LABEL).performScrollTo().performClick()
        waitUntil(timeoutMillis = 10_000) { selfSigned.isNotEmpty() }
        waitForIdle()

        assertEquals(1, selfSigned.size, "确认后恰好一次 trust-all 切换")
        assertTrue(selfSigned.single().tls, "确认后的切换仍然带 tls = true")
        assertEquals(1, systemChain.size, "确认之后不许再走一次系统信任链")
    }
}
