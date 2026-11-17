package vade.ui.data

import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import vade.ui.protocol.BrowserPairing
import vade.ui.protocol.FakeTransportFactory
import vade.ui.protocol.VadeClientConfig
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException
import vade.ui.protocol.VadeTransport
import vade.ui.protocol.VadeTransportFactory
import vade.ui.shell.hostOf
import vade.ui.shell.portOf

/**
 * g-pair-first-run · 数据层：首次运行判据、把**输入的一次性码**交给现有连接链的一次尝试、
 * 运行期配对桥的消费点，以及「码不落盘 / 不进 URL / 不进日志 / 不进任何持久化字段」的
 * 扫描守卫（任务书口径：用扫描断言，不看实现）。
 */
class PairingFirstRunTest {

    private val realNoCode = RawSourceStrings("real", "127.0.0.1", "8443", null)
    private val realWithCode = RawSourceStrings("real", "127.0.0.1", "8443", "青松-ABC123")

    @BeforeTest
    fun resetBridgeBefore() {
        PairingRuntime.clearForTests()
    }

    @AfterTest
    fun resetBridgeAfter() {
        // 桥是全局单例：用例之间互不污染，跑完即清回未配对态。
        PairingRuntime.clearForTests()
    }

    // ------------------------------------------------------------- 判定
    //
    // H1 口径：引导卡是**启动**决定。`clearForTests()` 把桥留在 `NotBooted`（测试直构的
    // 组合就是这种状态），`needsFirstRunPairing` 对它一律 false —— 所以每个「该出卡」的
    // 用例都先走一次 `noteBooted()`，等价于入口的 `sessionSourceFromEnvironment()`。

    /** 等价于入口启动：桥进入「已启动、未配对」。 */
    private fun booted() = PairingRuntime.noteBooted()

    @Test
    fun notBootedNeverShowsTheCard() {
        // 没有入口声明过本次运行 → 不出卡（否则每个渲染列表的组合/测试都会凭空长一张）。
        assertFalse(needsFirstRunPairing(realNoCode), "NotBooted 状态下不出卡")
        assertFalse(needsFirstRunPairing(RawSourceStrings(null, null, null, null)))
    }

    @Test
    fun needsPairingRealWithoutCode() {
        booted()
        assertTrue(needsFirstRunPairing(realNoCode), "真源 + 缺码 = 引导卡该出现")
    }

    @Test
    fun noPairingWhenCodePresent() {
        booted()
        assertFalse(needsFirstRunPairing(realWithCode), "带了码（环境变量/命令行旧路径）= 不出现")
        // 只有空白也算缺 —— parse 会把空白码归一成 null。
        assertTrue(
            needsFirstRunPairing(RawSourceStrings("real", "127.0.0.1", "8443", "   ")),
            "真源 + 空白码 = 仍缺码，引导卡该出现",
        )
    }

    @Test
    fun defaultBootIsFirstRunAndExplicitFixturesIsNot() {
        booted()
        // H1：什么都不配的**默认启动**同样是首次运行 —— 新用户必须有一个填地址的地方。
        assertTrue(
            needsFirstRunPairing(RawSourceStrings(null, null, null, null)),
            "默认启动（无 source）= 首次运行，引导卡该出现",
        )
        assertTrue(needsFirstRunPairing(RawSourceStrings("", "", "", null)), "空字符串 = 没配")
        // 显式 `fixtures` / 其它非 real 值是开发者的逃生口：不出卡。
        assertFalse(
            needsFirstRunPairing(RawSourceStrings("fixtures", "127.0.0.1", "8443", null)),
            "显式 fixtures = 夹具路径，引导卡不出现",
        )
        assertFalse(needsFirstRunPairing(RawSourceStrings("fake", null, null, null)))
    }

    @Test
    fun pairedOrRejectedStatesFollowTheBridge() {
        booted()
        PairingRuntime.install(MarkerFactory(), "10.0.0.9", 19999)
        assertFalse(needsFirstRunPairing(realNoCode), "已配对 = 不出现")
        assertEquals("10.0.0.9:19999", PairingRuntime.pairedEndpoint)
        // 码被 core 拒过 → 仍要出卡，用户才能重试（H2：错码不会把入口关死）。
        PairingRuntime.clearForTests()
        booted()
        PairingRuntime.noteCodeRejected()
        assertTrue(needsFirstRunPairing(realNoCode), "码被拒过 = 引导卡仍出现（可重试）")
    }

    /** 只为了把桥填到已配对态的 factory（不会被连接）。 */
    private class MarkerFactory : VadeTransportFactory {
        override suspend fun connect(): VadeTransport = throw IllegalStateException("marker")
    }

    // ------------------------------------------------------------- 一次性尝试

    /** 记录构造参数与 connect 次数的替身 factory。 */
    private class RecordingFactory(
        private val onConnect: () -> VadeTransport,
    ) : VadeTransportFactory {
        val configs = mutableListOf<VadeClientConfig>()
        var connectCalls: Int = 0

        override suspend fun connect(): VadeTransport {
            connectCalls += 1
            return onConnect()
        }
    }

    /** 最小 transport 替身：只为了配对尝试能 close 一个探活会话。 */
    private class TestProbeTransport : VadeTransport {
        var closeCalls: Int = 0
        override val incoming = emptyFlow<String>()
        override suspend fun send(frame: String) = Unit
        override suspend fun close() {
            closeCalls += 1
        }
    }

    @Test
    fun attemptHandsEnteredCodeToTheExistingChain(): Unit = runBlocking {
        val probe = TestProbeTransport()
        val factory = RecordingFactory { probe }
        val result = firstRunPairingAttempt(realNoCode, "青松-XYZ789") { config ->
            factory.configs.add(config)
            factory
        }
        val connected = assertIs<PairingAttempt.Connected>(result)
        assertEquals(1, factory.connectCalls, "恰好一次连接尝试（POST /ui/session + WS 升级都在 factory.connect 里）")
        assertEquals(1, factory.configs.size)
        val config = factory.configs.single()
        assertEquals("127.0.0.1", config.host)
        assertEquals(8443, config.port)
        assertEquals("青松-XYZ789", config.browserSession?.pairCode, "连接尝试的参数 = 输入的码（原样，含汉字与连字符）")
        assertEquals(1, probe.closeCalls, "探活传输用完即关：cookie 已缓存在 factory，一次性码不被二次消费")
        assertTrue(connected.factory === factory, "Connected 交出的就是这次尝试用的 factory")
        // 实际连的 endpoint 随结果一起交回：桥要按**用户填的地址**建真源，
        // 而不是按启动时那条配置（默认启动时它甚至是空的）。
        assertEquals("127.0.0.1", connected.host)
        assertEquals(8443, connected.port)
    }

    @Test
    fun attemptFailureKindsAreClassified(): Unit = runBlocking {
        // 码错/过期：core 拒绝 POST /ui/session → CODE_INVALID / CODE_EXPIRED / AUTH_FAILED。
        val badCode = firstRunPairingAttempt(realNoCode, "错码") { _ ->
            throw VadeException(VadeErrorCodes.AUTH_FAILED, "POST /ui/session failed with HTTP 401: bad pair code")
        }
        assertEquals(PairingFailureKind.BadCode, assertIs<PairingAttempt.Rejected>(badCode).kind)
        val invalidCode = firstRunPairingAttempt(realNoCode, "错码") { _ ->
            throw VadeException(VadeErrorCodes.CODE_INVALID, "CODE_INVALID: no such pair code")
        }
        assertEquals(PairingFailureKind.BadCode, assertIs<PairingAttempt.Rejected>(invalidCode).kind)
        val expiredCode = firstRunPairingAttempt(realNoCode, "旧码") { _ ->
            throw VadeException(VadeErrorCodes.CODE_EXPIRED, "CODE_EXPIRED: pair code expired")
        }
        assertEquals(PairingFailureKind.BadCode, assertIs<PairingAttempt.Rejected>(expiredCode).kind)
        // 服务端没起：连接被拒绝。
        val serverDown = firstRunPairingAttempt(realNoCode, "青松-XYZ789") { _ ->
            throw ConnectException("Connection refused: /127.0.0.1:8443")
        }
        assertEquals(PairingFailureKind.ServerDown, assertIs<PairingAttempt.Rejected>(serverDown).kind)
        // 网络不通：DNS / 超时 / 中途断开。
        val network = firstRunPairingAttempt(realNoCode, "青松-XYZ789") { _ ->
            throw SocketTimeoutException("connect timed out")
        }
        assertEquals(PairingFailureKind.Network, assertIs<PairingAttempt.Rejected>(network).kind)
        // 地址/端口本身不合法：分类为 BadConfig，而且**一个 transport 都不许建**（不发请求）。
        val badConfig = firstRunPairingAttempt(
            RawSourceStrings("real", "127.0.0.1", "8443x", null),
            "青松-XYZ789",
        ) { _ -> throw AssertionError("配置非法时不得构造 transport（会真发一次请求）") }
        val rejected = assertIs<PairingAttempt.Rejected>(badConfig)
        assertEquals(PairingFailureKind.BadConfig, rejected.kind)
        assertIs<IllegalArgumentException>(rejected.cause, "原话要交回 UI（BadConfig 的详情行）")
    }

    @Test
    fun failedAttemptNeverRetries(): Unit = runBlocking {
        // runner 会把异常吞掉换成无限重试；引导卡的尝试必须**一次**就回来（三种失败才可区分）。
        val factory = RecordingFactory { throw ConnectException("Connection refused") }
        val result = firstRunPairingAttempt(realNoCode, "青松-XYZ789") { _ -> factory }
        assertIs<PairingAttempt.Rejected>(result)
        assertEquals(1, factory.connectCalls, "失败也必须恰好一次尝试，不许在尝试函数里重试")
    }

    // ------------------------------------------------------------- 运行期桥的消费点

    /**
     * P0-1 · 启动参数里的码必须真的落到 [VadeClientConfig.browserSession]。
     *
     * 之前 `sessionSourceFrom` 的 Real 分支算出了带 [BrowserPairing] 的 config，却在构造
     * 真源时只传 host/port —— 于是 `VADE_PAIR_CODE` / Android `--es pair_code` /
     * wasm `?pair_code=` **完全不生效**：WS 升级被当成「设备 peer」，core 走 Noise 握手，
     * 明文 JSON 客户端永远接不上（用户看到的是「core 不可达」，码白烧）。
     */
    @Test
    fun realSourceCarriesTheRawPairCodeIntoItsConfig() {
        val auto = assertIs<AutoConnectingSessionSource>(
            sessionSourceFrom(realWithCode),
            "真源唯一的装配形状是 shim（AutoConnectingSessionSource）",
        )
        val config = auto.delegate.config
        assertEquals("127.0.0.1", config.host)
        assertEquals(8443, config.port)
        val pairing = assertIs<BrowserPairing>(
            config.browserSession,
            "码没进配置 = VADE_PAIR_CODE / pair_code 完全不生效（WS 会按设备 peer 升级）",
        )
        assertEquals("青松-ABC123", pairing.pairCode)
    }

    /** 没码时不许凭空造一个浏览器会话：那是「假装已配对」的另一种假接线。 */
    @Test
    fun realSourceWithoutACodeStaysADeviceSession() {
        val auto = assertIs<AutoConnectingSessionSource>(sessionSourceFrom(realNoCode))
        assertNull(auto.delegate.config.browserSession, "无码 = 设备会话（不做 POST /ui/session）")
    }

    @Test
    fun pairedFactoryReachesTheRealChain() {
        val fake = FakeTransportFactory()
        PairingRuntime.install(fake, "127.0.0.1", 8443)
        val source = sessionSourceFrom(realNoCode)
        val auto = assertIs<AutoConnectingSessionSource>(source)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            runBlocking {
                scope.launch { runCatching { source.sessions(false) } }
                // 桥里的 factory 被**真实链路**调用（而不是默认的 Ktor —— 那条路连不上端口 1）。
                withTimeout(15_000) {
                    while (fake.connectAttempts.isEmpty()) delay(25)
                }
            }
            assertTrue(fake.connectAttempts.size >= 1, "sessionSourceFrom 必须用桥里的 factory 建真源")
        } finally {
            scope.cancel()
            runBlocking { auto.delegate.disconnect() }
        }
    }

    @Test
    fun bridgeHoldsFactoryNotTheCode() {
        // 「码不进 SourceConfig 的任何字段」由**类型系统**承重：桥的状态里唯一与凭据有关的
        // 字段是 `Paired.factory: VadeTransportFactory`，`String` 根本存不进去
        // （`is String` 在编译期即恒 false）。状态的声明由
        // enteredPairCodeHasNoPersistenceSink 的扫描断言钉住（SourceConfig.kt 唯一的 var
        // 必须是 `mutableStateOf<PairingRuntimeState…`，而 PairingRuntimeState 的字段是
        // factory/host/port/Boolean —— 没有一个能装下输入的码）。
        // 这里钉桥的持有语义 + 新装的 endpoint 一并生效。
        val factory = RecordingFactory { TestProbeTransport() }
        PairingRuntime.install(factory, "10.0.0.9", 19999)
        assertTrue(PairingRuntime.pairedFactory === factory)
        assertEquals("10.0.0.9:19999", PairingRuntime.pairedEndpoint, "install 的 host/port 就是真源要连的地址")
        PairingRuntime.clearForTests()
        assertTrue(PairingRuntime.pairedFactory == null, "clearForTests 把桥清回未配对态")
        assertEquals(null, PairingRuntime.pairedEndpoint)
    }

    @Test
    fun defaultStartupAfterPairingBuildsARealSourceAtThePairedEndpoint() {
        // 默认启动（raw 里什么都没有）配好对之后，重建点必须是**真源** —— 而不是因为
        // `source` 为空就退回夹具（H1 的落地路径：卡消失、列表开始拉真数据）。
        val fake = FakeTransportFactory()
        PairingRuntime.install(fake, "10.0.0.9", 19999)
        val source = sessionSourceFrom(RawSourceStrings(null, null, null, null))
        assertTrue(source is AutoConnectingSessionSource, "已配对 + 默认启动 = 真源，不是 FakeSessionSource")
        assertEquals("10.0.0.9:19999", PairingRuntime.pairedEndpoint)
    }

    // ------------------------------------------------------------- endpoint 解析对照

    @Test
    fun pairingEndpointMatchesShellResolve() {
        // 与 shell 的 hostOf/portOf 同一条规则 —— 两处实现互相对照，漂移即红。
        for (raw in listOf(
            realNoCode,
            RawSourceStrings("real", " 192.168.1.7 ", "18755", null),
            RawSourceStrings("real", "", "", null),
            RawSourceStrings("real", null, null, null),
        )) {
            assertEquals(
                "${hostOf(raw)}:${portOf(raw)}",
                pairingEndpointOf(raw),
                "pairingEndpointOf 与 shell 的解析规则必须一致（raw=$raw）",
            )
        }
    }

    // ------------------------------------------------------------- 扫描守卫

    /** 剥掉 /* */ 块注释与 // 行注释后的源码文本（守卫别把自己的 KDoc 当 offender）。 */
    private fun stripped(path: String): String {
        val text = File(path).readText(Charsets.UTF_8)
        val noBlock = Regex("""(?s)/\*.*?\*/""").replace(text, "")
        return noBlock.lines().joinToString("\n") { line ->
            // 含字符串字面量的行不动（避免把行内的 "://" 吃掉造成漏检）；
            // 配对相关文件的代码里没有带 // 的字符串。
            if (line.contains('"')) line else line.substringBefore("//")
        }
    }

    @Test
    fun enteredPairCodeHasNoPersistenceSink() {
        val files = listOf(
            "src/commonMain/kotlin/vade/ui/data/SourceConfig.kt",
            "src/commonMain/kotlin/vade/ui/destinations/PairingCard.kt",
        )
        // 持久化 sink：DataStore / SharedPreferences / 文件写 / wasm 存储 —— 码不落盘。
        val persistenceSinks = listOf(
            "DataStore", "SharedPreferences", "writeText", "writeBytes", "appendText",
            "putString", "putInt", "openFileOutput", "FileOutputStream", "FileWriter",
            "localStorage", "sessionStorage",
        )
        // 日志 sink：码不进日志。
        val logSinks = listOf("println", "Log.", "System.out", "System.err", "logger")
        // URL sink：码不拼进 URL（连接参数走 POST body，由既有 transport 组装）。
        val urlSinks = listOf("ws://", "wss://", "http://", "https://", "encodeURIComponent")

        for (path in files) {
            val text = stripped(path)
            for (sink in persistenceSinks) {
                assertFalse(text.contains(sink), "$path 不得出现持久化 sink `$sink`（码不落盘）")
            }
            for (sink in logSinks) {
                assertFalse(text.contains(sink), "$path 不得出现日志 sink `$sink`（码不进日志）")
            }
            for (sink in urlSinks) {
                assertFalse(text.contains(sink), "$path 不得出现 URL 构建 `$sink`（码不拼进 URL）")
            }
        }
        // SourceConfig 的**全部**可变字段只有一个：运行期桥的状态 ——
        // 「输入值不进 SourceConfig 的任何持久化字段」的扫描正身。
        val bridgeVar = Regex("""\bvar\s+state\s*:\s*PairingRuntimeState\b""")
        val mutableLines = stripped(files[0]).lines().filter { Regex("""\bvar\s""").containsMatchIn(it) }
        val bridgeLines = mutableLines.filter { bridgeVar.containsMatchIn(it) }
        assertEquals(1, bridgeLines.size, "SourceConfig.kt 里唯一的 var 就是运行期桥（PairingRuntimeState）")
        assertEquals(0, mutableLines.size - bridgeLines.size, "SourceConfig.kt 不得新增任何其它可变字段")
        // 桥的状态形状：凭据只能以 factory 的形式存在。`Paired` 的字段就是
        // factory/host/port —— 加一个 `code`/`pairCode` 字段会让上面那条类型论证失效，
        // 所以这里直接钉住它的成员表。
        val stateLines = stripped(files[0]).lines()
        val pairedAt = stateLines.indexOfFirst { "data class Paired(" in it }
        assertTrue(pairedAt >= 0, "PairingRuntimeState.Paired 必须存在（运行期桥的唯一凭据形态）")
        val pairedBlock = stateLines
            .drop(pairedAt)
            .takeWhile { !it.trim().startsWith(") : PairingRuntimeState") }
            .joinToString("\n")
        assertTrue("VadeTransportFactory" in pairedBlock, "Paired 必须持有 factory")
        assertFalse(
            "pairCode" in pairedBlock || Regex("""\bcode\s*:""").containsMatchIn(pairedBlock),
            "Paired 不得出现码字段（码只在 firstRunPairingAttempt 的参数里活一次）",
        )
    }
}
