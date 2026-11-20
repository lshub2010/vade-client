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
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import vade.ui.protocol.BrowserPairing
import vade.ui.protocol.FakeTransport
import vade.ui.protocol.KtorTransportFactory
import vade.ui.protocol.VadeClientConfig
import vade.ui.protocol.VadeTransport
import vade.ui.protocol.VadeTransportFactory

/**
 * r5-tls · HTTPS 选项的**数据层**契约（UI 侧的闸门在 `destinations/PairingTlsUiTest`）。
 *
 *  1. `tls = true` 时拨的是 `https` / `wss`（`MockEngine` 看见的那一次 POST + 两个 URL
 *     构造函数）；`tls = false` 时仍然是 `http` / `ws` —— 默认路径逐字节不变；
 *  2. `allowInsecureTls` 只有在「`tls = true` **且**调用方声明用户确认过」时才进
 *     [VadeClientConfig]；只确认不走 TLS，或走 TLS 但没确认，都不会打开 trust-all；
 *  3. 桥（[PairingRuntimeState.Paired]）记住协议，换 core 后新 core 也按同一个协议建
 *     真源（首配 / 换 core 同一条口径）；
 *  4. `unpair` 的语义没变：退回未配对、什么都不留。
 *
 * 全部用 [MockEngine]，不起 socket、不依赖 core。
 */
class TlsPairingTest {

    /** 一次用例的观测点：请求全序 + `POST /ui/session` 的那几次。 */
    private class Wire {
        val requests: MutableList<String> = mutableListOf()
        val posts: MutableList<String> = mutableListOf()
    }

    /** 真 [KtorTransportFactory] + [MockEngine]：POST 真的走 Ktor 的请求构造（URL 可断言）。 */
    private fun wireFactory(
        wire: Wire,
        tls: Boolean,
        allowInsecureTls: Boolean = false,
        host: String = "core.local",
    ): KtorTransportFactory {
        val engine = MockEngine { request ->
            wire.requests += "${request.method.value} ${request.url}"
            if (request.method == HttpMethod.Post && request.url.encodedPath == "/ui/session") {
                wire.posts += request.url.toString()
                respond(
                    content = """{"device_id":"dev-tls"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.SetCookie, "__Host-ac_ui=tok-tls"),
                )
            } else {
                respond(content = "", status = HttpStatusCode.NotFound)
            }
        }
        return KtorTransportFactory(
            config = VadeClientConfig(
                host = host,
                port = 8443,
                tls = tls,
                allowInsecureTls = allowInsecureTls,
                browserSession = BrowserPairing("CODE-TLS"),
            ),
            client = HttpClient(engine),
        )
    }

    /** `connect()` 立刻成功、不发明任何网络请求的替身（只要 `firstRunPairingAttempt` 的结局）。 */
    private class ConnectedFactory : VadeTransportFactory {
        override suspend fun connect(): VadeTransport = FakeTransport()
    }

    @BeforeTest
    fun resetBridgeBefore() = PairingRuntime.clearForTests()

    @AfterTest
    fun resetBridgeAfter() = PairingRuntime.clearForTests()

    // ------------------------------------------------------------- ① URL：https / wss

    @Test
    fun tlsTrueDialsHttpsAndWss() = runBlocking {
        val wire = Wire()
        val factory = wireFactory(wire, tls = true, allowInsecureTls = true)

        assertNotNull(factory.ensureSessionCookie(), "POST /ui/session 要真的发出去（MockEngine 回 200 + cookie）")

        assertEquals(
            listOf("POST https://core.local:8443/ui/session"),
            wire.requests,
            "tls = true 时配对请求必须是 https",
        )
        assertEquals("https://core.local:8443/ui/session", factory.httpUrl(), "吊销 / 配对都走同一个 scheme")
        assertEquals("wss://core.local:8443/ws", factory.websocketUrl(), "WS 升级必须跟着变 wss")
    }

    // ------------------------------------------------------------- ⑤ 默认：仍是 http / ws

    @Test
    fun tlsFalseStaysPlainHttp() = runBlocking {
        val wire = Wire()
        val factory = wireFactory(wire, tls = false)

        assertNotNull(factory.ensureSessionCookie())

        assertEquals(listOf("POST http://core.local:8443/ui/session"), wire.requests)
        assertEquals("http://core.local:8443/ui/session", factory.httpUrl())
        assertEquals("ws://core.local:8443/ws", factory.websocketUrl())
    }

    // ------------------------------------------------- ③④ allowInsecureTls 只在确认后进来

    @Test
    fun confirmedSelfSignedPairingCarriesTlsAndTrustAll() = runBlocking {
        val seen = mutableListOf<VadeClientConfig>()
        val raw = RawSourceStrings(source = "real", host = "core.local", port = "8443", pairCode = null, tls = true)

        val attempt = firstRunPairingAttempt(
            raw = raw,
            enteredCode = "CODE-TLS",
            transportFactory = { config -> seen += config; ConnectedFactory() },
            allowInsecureTls = true,
        )

        val config = seen.single()
        assertTrue(config.tls, "确认过的 HTTPS 配对必须拨 https")
        assertTrue(config.allowInsecureTls, "确认过之后才允许 trust-all（自签证书）")
        val connected = assertIs<PairingAttempt.Connected>(attempt)
        assertTrue(connected.tls, "结局里也要带协议，桥据此重建真源")
    }

    @Test
    fun withoutConfirmationTrustAllStaysOff() = runBlocking {
        val seen = mutableListOf<VadeClientConfig>()
        val raw = RawSourceStrings(source = "real", host = "core.local", port = "8443", pairCode = null, tls = true)

        // 默认 allowInsecureTls = false：数据层不替用户做「信任自签证书」这个决定。
        firstRunPairingAttempt(
            raw = raw,
            enteredCode = "CODE-TLS",
            transportFactory = { config -> seen += config; ConnectedFactory() },
        )

        assertTrue(seen.single().tls, "协议照 tls 走")
        assertFalse(seen.single().allowInsecureTls, "没确认过就只走系统信任链")
    }

    @Test
    fun trustAllIsNeverOpenedForAPlainHttpPairing() = runBlocking {
        val seen = mutableListOf<VadeClientConfig>()
        val raw = RawSourceStrings(source = "real", host = "core.local", port = "8443", pairCode = null, tls = false)

        firstRunPairingAttempt(
            raw = raw,
            enteredCode = "CODE-TLS",
            transportFactory = { config -> seen += config; ConnectedFactory() },
            // 就算调用方多传了确认，tls = false 也不许打开 trust-all（它是明文，没有证书可验）。
            allowInsecureTls = true,
        )

        assertFalse(seen.single().tls)
        assertFalse(seen.single().allowInsecureTls, "allowInsecureTls 只在 tls = true 时才可能为真")
    }

    // ------------------------------------------------------------- ⑥ 换 core 同样遵守

    @Test
    fun switchCoreKeepsTheProtocolAndTheConsentForTheNewCore() = runBlocking {
        val seen = mutableListOf<VadeClientConfig>()

        val outcome = PairingRuntime.switchCore(
            raw = RawSourceStrings(source = "real", host = "core.local", port = "8443", pairCode = null, tls = true),
            enteredCode = "CODE-TLS",
            transportFactory = { config -> seen += config; ConnectedFactory() },
            allowInsecureTls = true,
        )

        assertIs<PairingAttempt.Connected>(outcome.attempt)
        assertTrue(seen.single().tls, "换 core 的新尝试也必须拨 https")
        assertTrue(seen.single().allowInsecureTls, "确认过自签证书，换 core 也要带过去")

        val paired = assertIs<PairingRuntimeState.Paired>(PairingRuntime.state, "成功后桥里是新 core")
        assertTrue(paired.tls, "桥必须记住协议 —— 否则重建真源会退回 http")

        // 重建真源（配对成功后 App 就是这么做的）：协议与 trust-all 都要跟着桥走。
        val rebuilt = sessionSourceFrom(RawSourceStrings(null, null, null, null))
        val shim = assertIs<AutoConnectingSessionSource>(rebuilt)
        assertTrue(shim.delegate.config.tls, "重建的真源必须是 https")
        assertTrue(shim.delegate.config.allowInsecureTls, "重建的真源必须继续信任那张自签证书")
    }

    @Test
    fun aPlainSwitchStaysPlainInTheBridge() = runBlocking {
        PairingRuntime.switchCore(
            raw = RawSourceStrings(source = "real", host = "core.local", port = "8443", pairCode = null),
            enteredCode = "CODE-TLS",
            transportFactory = { ConnectedFactory() },
        )

        val paired = assertIs<PairingRuntimeState.Paired>(PairingRuntime.state)
        assertFalse(paired.tls, "没有开 HTTPS 的切换不许在桥里留下 tls = true")
    }

    // ------------------------------------------------------------- 语义未变：unpair

    @Test
    fun unpairStillGoesBackToUnpairedWithNoProtocolLeftBehind() = runBlocking {
        PairingRuntime.install(ConnectedFactory(), "core.local", 8443, tls = true)

        val revocation = PairingRuntime.unpair()

        assertTrue(
            revocation is SessionRevocation.Revoked || revocation is SessionRevocation.NotNeeded,
            "没有 UI 会话可吊销时「无事可做」也是成功：$revocation",
        )
        assertEquals(PairingRuntimeState.Unpaired(), PairingRuntime.state, "unpair 的语义没变：退回未配对")
    }
}
