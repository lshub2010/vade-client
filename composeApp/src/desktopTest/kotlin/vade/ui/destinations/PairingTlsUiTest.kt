package vade.ui.destinations

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import vade.ui.data.PairingRuntime
import vade.ui.data.RawSourceStrings
import vade.ui.data.SessionRevocation
import vade.ui.data.SourceConfig
import vade.ui.data.firstRunPairingAttempt
import vade.ui.protocol.KtorTransportFactory
import vade.ui.protocol.VadeClientConfig
import vade.ui.protocol.VadeTransport
import vade.ui.protocol.VadeTransportFactory
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin

/**
 * r5-tls · HTTPS 开关 + 自签证书警告的**真组合**用例。
 *
 * 六条契约里的 UI 那几条都钉在这里（URL 与 config 的断言在 `data/TlsPairingTest`）：
 *  ② 弹层出现**之前/挂着时**：`MockEngine` 零请求（不是「请求被拒绝」，是一个都没发）；
 *  ③ 「取消」：零请求，且码还在输入框里（一次性码没被烧）；
 *  ④ 「仍然连接」：**恰好一次** `POST /ui/session`，且 URL 是 `https`；
 *  ⑤ 开关关着：不弹层、照旧提交（`tls = false`）；
 *  ⑥ 「更换 core」的面板：同一份开关、同一道闸门。
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

    // -------------------------------------------------------- ②③④ 闸门：挂在表单上

    @Test
    fun tlsOnShowsTheWarningAndSendsNothingUntilTheUserConfirms() = runComposeUiTest {
        val wire = Wire()
        val seen: MutableList<VadeClientConfig> = CopyOnWriteArrayList()
        val attempts = AtomicInteger(0)

        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                PairingForm(
                    initialHost = "core.local",
                    initialPort = "8443",
                    submitLabel = PAIRING_BUTTON_LABEL,
                    submittingLabel = PAIRING_SUBMITTING,
                    submitScope = submitScope,
                    onSubmit = { host, port, code, tls ->
                        attempts.incrementAndGet()
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
                            transportFactory = { config ->
                                seen += config
                                KtorTransportFactory(config, wire.client())
                            },
                            allowInsecureTls = tls,
                        )
                    },
                )
            }
        }
        waitForIdle()

        // 开关默认关：label 在，弹层不在。
        onNodeWithText(PAIRING_TLS_LABEL).assertExists()
        assertEquals(0, dialogs(), "默认关，不该有弹层")

        onNodeWithTag(PAIRING_TLS_SWITCH_TAG).performClick()
        waitForIdle()
        onNodeWithTag(PAIRING_TLS_SWITCH_TAG).assertIsOn()
        onNode(codeField()).performTextInput("青松-TLS123")
        onNodeWithText(PAIRING_BUTTON_LABEL).performClick()
        waitUntil(timeoutMillis = 5_000) { dialogs() == 1 }
        waitForIdle()

        // ② 弹层挂着：一个请求都没有发（连接链根本没被调用）。
        onNodeWithText(PAIRING_TLS_WARNING_TITLE).assertExists()
        onNodeWithText(PAIRING_TLS_CONFIRM_LABEL).assertExists()
        assertEquals(0, attempts.get(), "点提交只挂弹层，不调用连接链")
        assertTrue(wire.requests.isEmpty(), "未确认不得发任何请求：${wire.requests}")

        // ③ 取消：仍然零请求，码原样留着（一次性码没被烧）。
        onNodeWithText(PAIRING_TLS_CANCEL_LABEL).performClick()
        waitUntil(timeoutMillis = 5_000) { dialogs() == 0 }
        waitForIdle()

        assertEquals(0, attempts.get(), "取消 = 不提交")
        assertTrue(wire.requests.isEmpty(), "取消不发任何请求：${wire.requests}")
        onNode(codeField()).assertTextContains("青松-TLS123") // 取消不烧配对码：输入原样保留
        onNodeWithText(PAIRING_BUTTON_LABEL).assertIsEnabled()

        // ④ 再点一次 → 确认 → 恰好一次 POST，且走 https。
        onNodeWithText(PAIRING_BUTTON_LABEL).performClick()
        waitUntil(timeoutMillis = 5_000) { dialogs() == 1 }
        onNodeWithText(PAIRING_TLS_CONFIRM_LABEL).performClick()
        waitUntil(timeoutMillis = 10_000) { wire.posts.isNotEmpty() }
        waitForIdle()

        assertEquals(1, attempts.get(), "确认之后才提交，而且只提交一次")
        assertEquals(1, wire.posts.size, "恰好一次 POST /ui/session：${wire.requests}")
        assertEquals("https://core.local:8443/ui/session", wire.posts.single())
        val config = seen.single()
        assertTrue(config.tls, "确认后的配对必须拨 https")
        assertTrue(config.allowInsecureTls, "确认后的配对才允许 trust-all")
        assertEquals("青松-TLS123", config.browserSession?.pairCode, "码原样进配置（只在这一次尝试里活）")
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
                    onSubmit = { host, _, _, tls ->
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
        val switched: MutableList<RawSourceStrings> = CopyOnWriteArrayList()

        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                CoreSwitchPanel(
                    onBack = {},
                    onSwitch = { raw, _ ->
                        switched += raw
                        CoreSwitchOutcome(
                            revocation = SessionRevocation.NotNeeded,
                            attempt = PairingAttempt.Connected(MarkerFactory(), raw.host ?: "127.0.0.1", 8443, raw.tls),
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

        assertEquals(0, switched.size, "换 core 也要先确认：弹层挂着时不许调用 onSwitch")

        onNodeWithText(PAIRING_TLS_CONFIRM_LABEL).performClick()
        waitUntil(timeoutMillis = 10_000) { switched.isNotEmpty() }
        waitForIdle()

        assertEquals(1, switched.size, "确认后恰好一次切换")
        assertTrue(switched.single().tls, "面板里的开关要跟着 RawSourceStrings 走")
    }
}
