package vade.ui.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import vade.ui.protocol.FakeTransport
import vade.ui.protocol.KtorTransportFactory
import vade.ui.protocol.TlsTrustMode
import vade.ui.protocol.VadeClientConfig
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException
import vade.ui.protocol.VadeTransport
import vade.ui.protocol.VadeTransportFactory

/**
 * P0-1 · 「进程重启之后用**磁盘上那张 cookie** 恢复」的真测试。
 *
 * 为什么必须有一条真测试：配对码一次性、core 也只在它自己启动时铸新码 —— 客户端一重启就
 * 要求用户回终端捞码 = 主路径不可用。但「直接信任磁盘」在 core 重启过（会话表清空）之后
 * 会变成僵尸重连，所以恢复路径只能是：**读磁盘 → 用那张 cookie 探一次活 → 成功才进
 * Paired**。
 *
 * 两条硬约束（对应两条最容易写假的断言）：
 *  1. **真文件**：走 [StringPairingStore]（生产实现：编码 / 严格解码只有 [PairingStoreCodec]
 *     一份）；「进程重启」= 内存里的桥与缓存全部丢掉、磁盘原样保留、store 用**新实例**
 *     重新读同一个路径。桌面 actual 的路径与 0600 另有一条直接断言。
 *  2. **不烧码**：恢复路径的配置里 `browserSession == null`（没有码可烧），且用真
 *     `KtorTransportFactory` + `MockEngine` 时**一个 HTTP 请求都不发** —— 恢复绝不能偷偷再
 *     `POST /ui/session`。
 */
class PairingRestoreTest {

    private lateinit var home: Path
    private var savedUserHome: String? = null

    /** 恢复路径里 `MockEngine` 看得见的请求（只用于证明「一个都没发」）。 */
    private class Wire {
        val requests: MutableList<String> = mutableListOf()
    }

    @BeforeTest
    fun resetBefore() {
        PairingRuntime.clearForTests()
        home = Files.createTempDirectory("vade-pairing-restore")
        savedUserHome = System.getProperty("user.home")
        System.setProperty("user.home", home.toString())
        PairingStores.storeOverride = fileStore()
    }

    @AfterTest
    fun resetAfter() {
        PairingRuntime.clearForTests()
        savedUserHome?.let { System.setProperty("user.home", it) }
        runCatching { deleteTree(home) }
    }

    /** 凭据文件路径（测试自己的临时文件；桌面 actual 的路径规则在最后一条用例里钉）。 */
    private fun pairingFile(): Path = home.resolve("pairing.json")

    /**
     * 生产 [StringPairingStore] + 临时文件。编码 / 严格解码 / 「坏文件 = 没有凭据」全部走
     * 生产代码；只有「文件放在哪、怎么写」是本用例的替身（桌面 actual 的 0600 + 原子替换
     * 由 `theDesktopStoreWritesAPrivateFileUnderTheConfigHome` 直接验）。
     */
    private fun fileStore(): PairingStore = object : StringPairingStore(
        read = { if (Files.isRegularFile(pairingFile())) Files.readString(pairingFile()) else null },
        write = { text -> Files.writeString(pairingFile(), text) },
        erase = { Files.deleteIfExists(pairingFile()) },
    ) {}

    /**
     * 「进程重启」：内存状态全丢（桥、缓存、注入的 store），磁盘一个字节都不动；新 store
     * 是**新实例** —— 读到的东西只能来自文件。
     */
    private fun restart() {
        PairingRuntime.clearForTests()
        assertNull(PairingStores.cached, "重启后内存缓存必须是空的，恢复只能看磁盘")
        PairingStores.storeOverride = fileStore()
    }

    /** `connect()` 成功、并对探针给出一帧 JSON-RPC 回包的替身（会话是活的）。 */
    private class AnsweringFactory : VadeTransportFactory {
        override suspend fun connect(): VadeTransport = FakeTransport().also { transport ->
            transport.push("""{"jsonrpc":"2.0","id":1,"result":{}}""")
        }
    }

    /** 第一次「进程」里那次配对成功的 factory：落盘的那张 cookie 就是它持有的。 */
    private class CookieFactory(private val cookie: String) : VadeTransportFactory {
        override val sessionCookie: String get() = cookie
        override suspend fun connect(): VadeTransport = FakeTransport()
    }

    /**
     * `connect()` 永不返回 —— 防火墙 DROP / VPN 断 / 错误网段 / NAT 黑洞的形状：
     * TCP 连不上也不报错，只有探针预算会先到。
     */
    private class HangingFactory : VadeTransportFactory {
        private var disposed = false
        override val isUsable: Boolean get() = !disposed
        override suspend fun connect(): VadeTransport = awaitCancellation()
        override suspend fun dispose() {
            disposed = true
        }
    }

    /**
     * 升级成功、但流在回任何帧之前**正常结束** —— 旧实现里 `probe.incoming.first{}` 会抛
     * `NoSuchElementException` 穿出探针（factory 泄漏 + 桥停在裸 `Unpaired()`）。
     */
    private class EmptyStreamFactory : VadeTransportFactory {
        private var disposed = false
        override val isUsable: Boolean get() = !disposed
        override suspend fun connect(): VadeTransport = object : VadeTransport {
            override val incoming: Flow<String> = emptyFlow()
            override suspend fun send(frame: String) = Unit
            override suspend fun close() = Unit
        }
        override suspend fun dispose() {
            disposed = true
        }
    }

    /** 真 [KtorTransportFactory] + [MockEngine]：每次 HTTP 请求都记在 [wire] 里。 */
    private fun mockFactory(wire: Wire, config: VadeClientConfig): VadeTransportFactory {
        val engine = MockEngine { request ->
            wire.requests += "${request.method.value} ${request.url}"
            respond(content = "", status = HttpStatusCode.NotFound)
        }
        return KtorTransportFactory(config, HttpClient(engine))
    }

    // ------------------------------------------------------------------ 成功恢复

    @Test
    fun aRestartRestoresTheStoredCookieInsteadOfAskingForANewCode(): Unit = runBlocking {
        // ① 第一个进程：配对成功 → install(persist = true) 把 {endpoint, tls, trust, cookie} 落盘。
        PairingRuntime.install(
            factory = CookieFactory("tok-restored"),
            host = "core.local",
            port = 9443,
            tls = true,
            trustMode = TlsTrustMode.InsecureConfirmed,
            persist = true,
        )
        assertTrue(Files.isRegularFile(pairingFile()), "配对成功后必须真的写下一个文件")
        val onDisk = assertIs<StoredPairing>(PairingStores.load())
        assertEquals("tok-restored", onDisk.cookie)
        assertEquals(TlsTrustMode.InsecureConfirmed, onDisk.trustMode, "信任档位必须原样记住（P0-6）")

        // ② 进程重启：内存全丢，磁盘还在。
        restart()

        // ③ 第二个进程：用磁盘上的 cookie 恢复（探活一次，成功才进桥）。
        val configs = mutableListOf<VadeClientConfig>()
        val outcome = PairingRuntime.restoreStoredCredential(
            raw = RawSourceStrings(null, null, null, null),
            transportFactory = { config ->
                configs += config
                AnsweringFactory()
            },
        )

        val restored = assertIs<PairingRestoreOutcome.Restored>(outcome, "好 cookie 必须恢复成功")
        assertEquals("core.local", restored.host)
        assertEquals(9443, restored.port)
        assertTrue(restored.tls, "协议要跟着恢复（https/wss），不许退回明文")
        assertEquals(TlsTrustMode.InsecureConfirmed, restored.trustMode)

        val config = configs.single()
        assertEquals("tok-restored", config.sessionCookie?.value, "用的是磁盘上那张 __Host-ac_ui")
        assertNull(config.browserSession, "恢复路径没有码可用 —— 也绝不许再 POST /ui/session")
        assertTrue(config.tls)
        assertTrue(config.allowInsecureTls, "记住的是「用户确认过的自签」，重建时照旧 trust-all")

        assertIs<PairingRuntimeState.Paired>(
            PairingRuntime.state,
            "恢复成功后桥必须进 Paired（App 据此重建真源）",
        )
        assertEquals("core.local:9443", PairingRuntime.pairedEndpoint)
        assertFalse(needsFirstRunPairing(RawSourceStrings(null, null, null, null)), "恢复成功 = 不再出配对卡")

        // ④ 落盘刷新（不是删除）：下次启动还要用它。
        assertEquals("tok-restored", PairingStores.load()?.cookie)
    }

    // ------------------------------------------------------------------ 不许再 POST

    @Test
    fun restoringNeverPostsAPairCodeEvenWhenTheSocketCannotBeOpened(): Unit = runBlocking {
        // 用**真** KtorTransportFactory + MockEngine：如果恢复路径偷偷走 POST，这里会看见。
        PairingRuntime.install(CookieFactory("tok-no-post"), "core.local", 8443, persist = true)
        restart()

        val wire = Wire()
        val outcome = PairingRuntime.restoreStoredCredential(
            raw = RawSourceStrings(null, null, null, null),
            transportFactory = { config -> mockFactory(wire, config) },
        )

        // MockEngine 的 client 没有 WS 插件：升级会在「不发任何 HTTP 请求」之后失败。
        val unreachable = assertIs<PairingRestoreOutcome.Unreachable>(outcome)
        assertTrue(
            wire.requests.isEmpty(),
            "恢复路径一张码都没有，绝不许再 POST /ui/session（那会白烧一个码）：${wire.requests}",
        )
        val unpaired = assertIs<PairingRuntimeState.Unpaired>(PairingRuntime.state)
        assertTrue(
            unpaired.storedCredentialKept,
            "core 暂时连不上 ≠ 凭据失效：文件必须留着（卡片给「用已保存的配对重连」）",
        )
        assertTrue(Files.isRegularFile(pairingFile()), "凭据不许因为一次连不上就被删")
        assertEquals("tok-no-post", PairingStores.load()?.cookie)
        assertTrue(unreachable.cause.message?.isNotBlank() == true, "失败原因由传输原话给出，不编造")
    }

    // ------------------------------------------------------------------ 凭据已死

    @Test
    fun aRejectedStoredCookieIsDeletedAndThePairingCardComesBack(): Unit = runBlocking {
        PairingRuntime.install(CookieFactory("tok-dead"), "core.local", 8443, persist = true)
        restart()

        val outcome = PairingRuntime.restoreStoredCredential(
            raw = RawSourceStrings(null, null, null, null),
            transportFactory = {
                object : VadeTransportFactory {
                    override suspend fun connect(): VadeTransport = throw VadeException(
                        VadeErrorCodes.AUTH_FAILED,
                        "websocket upgrade rejected the stored __Host-ac_ui cookie: 401 Unauthorized",
                    )
                }
            },
        )

        assertIs<PairingRestoreOutcome.Rejected>(outcome, "401 = 凭据已死，不是网络问题")
        assertFalse(Files.exists(pairingFile()), "死凭据必须从磁盘删掉，否则下次启动再演一遍")
        val unpaired = assertIs<PairingRuntimeState.Unpaired>(PairingRuntime.state)
        assertTrue(unpaired.credentialRejected, "桥要说明「上次的配对已失效」")
        assertTrue(
            needsFirstRunPairing(RawSourceStrings(null, null, null, null)),
            "凭据被拒 → 引导卡必须重新出现（用户要能重新配）",
        )
    }

    // ------------------------------------------------- 探针：超时 / 流结束 / 误判 401

    /**
     * P1-1 · 升级超时**不是**「凭据已死」。
     *
     * 防火墙 DROP / VPN 断 / 错误网段 / NAT 黑洞时 TCP 连不上也不报错，5 s 预算先到。
     * 旧实现走 `credentialRejected`（删磁盘凭据 + 桥 credentialRejected）—— 一次网络
     * 黑洞就销毁唯一凭据，用户必须回 core 终端捞新码。现在只能判 `Unreachable`：凭据留着，
     * 卡片给「用已保存的配对重连」。
     */
    @Test
    fun aHangingUpgradeKeepsTheStoredCredentialAndOnlyReportsUnreachable(): Unit = runBlocking {
        PairingRuntime.install(CookieFactory("tok-hang"), "core.local", 8443, persist = true)
        restart()

        val factory = HangingFactory()
        val outcome = PairingRuntime.restoreStoredCredential(
            raw = RawSourceStrings(null, null, null, null),
            probeTimeoutMs = 50,
            transportFactory = { factory },
        )

        val unreachable = assertIs<PairingRestoreOutcome.Unreachable>(
            outcome,
            "升级超时 = 核心暂时不可达，不是凭据被拒（P1-1）",
        )
        assertEquals(VadeErrorCodes.TIMEOUT, (unreachable.cause as? VadeException)?.code, "原因是超时，不是 401")
        assertTrue(Files.isRegularFile(pairingFile()), "一次网络黑洞不许删掉唯一凭据")
        assertEquals("tok-hang", PairingStores.load()?.cookie, "磁盘上的 cookie 必须原样还在")
        val unpaired = assertIs<PairingRuntimeState.Unpaired>(PairingRuntime.state)
        assertTrue(unpaired.storedCredentialKept, "卡片要给「用已保存的配对重连」")
        assertFalse(unpaired.credentialRejected, "不许说「上次的配对已失效」—— 它没有失效")
        assertFalse(factory.isUsable, "没进桥的 factory 必须被 dispose（连接池不许泄漏）")
    }

    /**
     * P1-1 的误判面：错误原文里出现「401」不一定是 401。
     *
     * core 端口 `4013` 的连接失败原文形如 `Connect to core.local:4013 failed: ...` ——
     * 旧实现用 `message.contains("401")` 判「凭据已死」并删盘。现在按词边界匹配，只能
     * 走 `Unreachable`。
     */
    @Test
    fun aConnectionFailureWhoseMessageMentionsPort4013IsNotACredentialRejection(): Unit = runBlocking {
        PairingRuntime.install(CookieFactory("tok-4013"), "core.local", 4013, persist = true)
        restart()

        val outcome = PairingRuntime.restoreStoredCredential(
            raw = RawSourceStrings(null, null, null, null),
            transportFactory = {
                object : VadeTransportFactory {
                    override suspend fun connect(): VadeTransport =
                        throw IOException("Connect to core.local:4013 failed: Connection timed out")
                }
            },
        )

        assertIs<PairingRestoreOutcome.Unreachable>(outcome, "端口数字巧合不许判成 401")
        assertTrue(Files.isRegularFile(pairingFile()), "连接失败不许删凭据")
        assertEquals("tok-4013", PairingStores.load()?.cookie)
    }

    /**
     * 升级成功、core 一个字节都不回（把 cookie 当 device peer 在等 Noise 握手）：这才是
     * **真的**凭据已死 —— 保留 P0-2 的删除语义，否则就是僵尸重连。
     */
    @Test
    fun aCoreThatAcceptsTheSocketAndNeverAnswersIsTreatedAsADeadCredential(): Unit = runBlocking {
        PairingRuntime.install(CookieFactory("tok-silent"), "core.local", 8443, persist = true)
        restart()

        val outcome = PairingRuntime.restoreStoredCredential(
            raw = RawSourceStrings(null, null, null, null),
            probeTimeoutMs = 50,
            transportFactory = {
                object : VadeTransportFactory {
                    override suspend fun connect(): VadeTransport = FakeTransport()
                }
            },
        )

        assertIs<PairingRestoreOutcome.Rejected>(outcome, "升级成功但永不应答 = 凭据已死（僵尸重连的起点）")
        assertFalse(Files.exists(pairingFile()), "死凭据必须删掉，否则下次启动再演一遍")
        val unpaired = assertIs<PairingRuntimeState.Unpaired>(PairingRuntime.state)
        assertTrue(unpaired.credentialRejected, "桥要说「上次的配对已失效」")
    }

    /**
     * P1-2 · 探针读失败（流在回包前正常结束 → `NoSuchElementException`）不许穿出
     * `restoreStoredCredential`。
     *
     * 旧实现只把 `probe.send` 包进 `runCatching`：`first{}` 抛出的异常直接穿出去 →
     * `factory.dispose()` 不执行（HttpClient/连接池泄漏）、桥停在裸 `Unpaired()`（既没有
     * `credentialRejected` 也没有 `storedCredentialKept`，卡片上连重连按钮都没有），而磁盘上
     * 的凭据明明还在。现在整段 `runCatching` + `finally dispose` + 按类型映射。
     */
    @Test
    fun aProbeWhoseStreamEndsWithoutAFrameKeepsTheCredentialAndDisposesTheFactory(): Unit = runBlocking {
        PairingRuntime.install(CookieFactory("tok-eof"), "core.local", 8443, persist = true)
        restart()

        val factory = EmptyStreamFactory()
        // 这一行本身不许抛：异常若穿出探针，测试会红在这里（而不是红在断言上）。
        val outcome = PairingRuntime.restoreStoredCredential(
            raw = RawSourceStrings(null, null, null, null),
            probeTimeoutMs = 1_000,
            transportFactory = { factory },
        )

        assertIs<PairingRestoreOutcome.Unreachable>(outcome, "流提前结束不是「凭据已死」的证据")
        assertTrue(Files.isRegularFile(pairingFile()), "凭据必须留着")
        assertEquals("tok-eof", PairingStores.load()?.cookie)
        val unpaired = assertIs<PairingRuntimeState.Unpaired>(PairingRuntime.state)
        assertTrue(
            unpaired.storedCredentialKept,
            "桥必须是「凭据还在但连不上」，不许是裸 Unpaired（那样卡片没有重连入口）",
        )
        assertFalse(unpaired.credentialRejected)
        assertFalse(factory.isUsable, "探针异常路径也必须 dispose factory（旧实现泄漏 HttpClient）")
    }

    @Test
    fun aFreshProcessWithoutACredentialHasNothingToRestore(): Unit = runBlocking {
        val outcome = PairingRuntime.restoreStoredCredential(
            raw = RawSourceStrings(null, null, null, null),
            transportFactory = { error("没有凭据时不许构造 transport（那会真发请求）") },
        )
        assertEquals(PairingRestoreOutcome.NoCredential, outcome)
        assertEquals(PairingRuntimeState.NotBooted, PairingRuntime.state, "没有凭据 = 不动桥")
    }

    @Test
    fun theDesktopStoreWritesAPrivateFileUnderTheConfigHome() {
        val path = desktopPairingPath()
        if (!path.startsWith(home)) {
            // 环境变量（VADE_HOME / XDG_CONFIG_HOME）把路径挪走了：不去碰开发者本机的真文件，
            // 这条断言在本环境跳过（恢复流程本身已由上面四条覆盖）。
            return
        }
        val store = platformPairingStore()
        store.save(StoredPairing("10.0.0.9", 19999, false, TlsTrustMode.SystemChain, "tok-file"))
        assertTrue(Files.isRegularFile(path), "桌面 actual 必须落到 $path")
        if ("posix" in java.nio.file.FileSystems.getDefault().supportedFileAttributeViews()) {
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(path),
                "文件里是完整 UI 权限的 __Host-ac_ui：必须 0600",
            )
        }
        assertEquals("tok-file", store.load()?.cookie)
        store.clear()
        assertFalse(Files.exists(path), "clear 之后文件必须消失")
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        val stream = Files.walk(root)
        try {
            stream.sorted(Comparator.reverseOrder<Path>()).forEach { Files.deleteIfExists(it) }
        } finally {
            stream.close()
        }
    }
}
