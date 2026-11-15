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

    @Test
    fun needsPairingRealWithoutCode() {
        assertTrue(needsFirstRunPairing(realNoCode), "真源 + 缺码 = 引导卡该出现")
    }

    @Test
    fun noPairingWhenCodePresent() {
        assertFalse(needsFirstRunPairing(realWithCode), "带了码（环境变量/命令行旧路径）= 不出现")
        // 只有空白也算缺 —— parse 会把空白码归一成 null。
        assertTrue(
            needsFirstRunPairing(RawSourceStrings("real", "127.0.0.1", "8443", "   ")),
            "真源 + 空白码 = 仍缺码，引导卡该出现",
        )
    }

    @Test
    fun noPairingOnFakeSource() {
        assertFalse(needsFirstRunPairing(RawSourceStrings(null, null, null, null)), "假源 = fixtures 路径，引导卡绝不出现")
        assertFalse(
            needsFirstRunPairing(RawSourceStrings("fixtures", "127.0.0.1", "8443", null)),
            "非 real 的任何值都是假源",
        )
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
    }

    @Test
    fun attemptFailureKindsAreClassified(): Unit = runBlocking {
        // 码错/过期：core 拒绝 POST /ui/session → C-04 AUTH_FAILED。
        val badCode = firstRunPairingAttempt(realNoCode, "错码") { _ ->
            throw VadeException(VadeErrorCodes.AUTH_FAILED, "POST /ui/session failed with HTTP 401: bad pair code")
        }
        assertEquals(PairingFailureKind.BadCode, assertIs<PairingAttempt.Rejected>(badCode).kind)
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

    @Test
    fun pairedFactoryReachesTheRealChain() {
        val fake = FakeTransportFactory()
        PairingRuntime.install(fake)
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
        // 「码不进 SourceConfig 的任何字段」由**类型系统**承重：pairedFactory 的声明类型是
        // `VadeTransportFactory?`，String 根本存不进去（`is String` 在编译期即恒 false，
        // 上一版那两条断言因此被编译器打回）。声明类型本身由 enteredPairCodeHasNoPersistenceSink
        // 的扫描断言钉住（SourceConfig.kt 唯一的 var 必须是 `mutableStateOf<VadeTransportFactory…`）。
        // 这里只钉桥的持有语义：装进什么，拿出来就是什么。
        val factory = RecordingFactory { TestProbeTransport() }
        PairingRuntime.install(factory)
        assertTrue(PairingRuntime.pairedFactory === factory)
        PairingRuntime.clearForTests()
        assertTrue(PairingRuntime.pairedFactory == null, "clearForTests 把桥清回未配对态")
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
        // SourceConfig 的**全部**可变字段只有一个：运行期桥，且类型是 factory 不是码 ——
        // 「输入值不进 SourceConfig 的任何持久化字段」的扫描正身。
        val mutableLines = stripped(files[0]).lines().filter { Regex("""\bvar\s""").containsMatchIn(it) }
        val bridgeLines = mutableLines.filter { it.contains("mutableStateOf<VadeTransportFactory") }
        assertEquals(1, bridgeLines.size, "SourceConfig.kt 里唯一的 var 就是运行期桥（factory 类型）")
        assertEquals(0, mutableLines.size - bridgeLines.size, "SourceConfig.kt 不得新增任何其它可变字段")
    }
}
