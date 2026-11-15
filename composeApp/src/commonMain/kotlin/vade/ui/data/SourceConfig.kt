package vade.ui.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import vade.ui.chat.ChatMessage
import vade.ui.protocol.BrowserPairing
import vade.ui.protocol.KtorTransportFactory
import vade.ui.protocol.VadeClientConfig
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException
import vade.ui.protocol.VadeTransportFactory

/**
 * g-live · the three-target real-data switch, in ONE place.
 *
 * Every entry point (desktop `Main.kt`, Android `MainActivity`, wasm `main.kt`) builds
 * its [SessionSource] through [sessionSourceFromEnvironment]; only the raw strings
 * differ per platform (`expect fun rawSourceStrings`):
 *
 * - desktop: `VADE_SOURCE` / `VADE_HOST` / `VADE_PORT` (+ `VADE_PAIR_CODE`) env vars;
 * - Android: intent extras with the BARE keys `source` / `host` / `port` (+ `pair_code`)
 *   FIRST (the same table as wasm — [SourceKeys]), the same env vars as fallback
 *   (installed by `MainActivity` into [AndroidSourceEnv] before composition);
 * - wasm: `?source=real&host=...&port=...&pair_code=...` URL query.
 *
 * Everything else — the [SourceSpec] type, the grammar, the defaults, the
 * invalid-port rejection — is shared commonMain code and covered by one test set,
 * so the three targets cannot drift apart.
 */
data class RawSourceStrings(
    /** The mode value; only the exact word `real` (trimmed) switches the source. */
    val source: String?,
    /** Core endpoint host; blank/missing → [SourceConfig.DEFAULT_HOST]. */
    val host: String?,
    /** Core endpoint port; missing → default, present-but-invalid → hard error. */
    val port: String?,
    /**
     * One-time browser pair code (RFC-0004 §8.2). Optional but REQUIRED to actually
     * reach a real core: without it the WS upgrade is treated as a device peer and
     * the plain-JSON client cannot talk (core `server.py` `_ws` → Noise handshake).
     */
    val pairCode: String? = null,
)

/** The keys every platform uses for its own raw channel. Kept beside the parser. */
object SourceKeys {
    const val SOURCE = "source"
    const val HOST = "host"
    const val PORT = "port"
    const val PAIR_CODE = "pair_code"

    /**
     * g-rescue-text · 真相表：解析器接受的**全部**裸键，顺序与 [SourceConfig.parse] 的
     * 参数一致。Android 的 intent extras（Android actual 逐键读 [SourceKeys]）与 wasm 的
     * URL query（[rawStringsFromQuery] 用同一张表）共用它。
     *
     * 任何**写给用户看**的键名（自救卡的 adb 命令、Wasm query 示例、文档）都必须从这里
     * 派生，不许手写第二份——手写第二份正是 g-rescue-text 修掉的那类漂移：卡上教
     * `--es vade.source real`，解析器读的却是裸键 `source`，用户照抄 → extras 里没有
     * 解析器要找的键 → **静默回落假数据**，还以为连上了（两次复现）。
     * 守卫测试 `SelfRescueRemedyGuardTest` 钉住「文案里的键集合 == 这张表」。
     */
    val acceptedKeys: List<String> = listOf(SOURCE, HOST, PORT, PAIR_CODE)
}

/**
 * Platform hook: where the raw strings come from. The desktop actual reads env
 * vars, the Android actual reads intent extras with env fallback, the wasm actual
 * reads the URL query. NO PARSING HERE — parse lives in [SourceConfig.parse].
 */
expect fun rawSourceStrings(): RawSourceStrings

/**
 * The parsed result of the switch. [Fake] is the fixture path (byte-identical to the
 * pre-w-wire behaviour); [Real] wires the protocol-backed source.
 */
sealed interface SourceSpec {
    /** Fixtures (`FakeSessionSource`). The default whenever `source != "real"`. */
    data object Fake : SourceSpec

    /** `VADE_SOURCE=real` (+ extras): the live core at [host]:[port]. */
    data class Real(
        val host: String,
        val port: Int,
        /** Present when the platform raw strings carried a pair code; else `null`. */
        val pairCode: String? = null,
    ) : SourceSpec
}

/** The switch's shared grammar and defaults. Pure and testable on every target. */
object SourceConfig {

    /** Core's own schema default (`core.listen = 127.0.0.1:8443`). */
    const val DEFAULT_HOST = "127.0.0.1"
    const val DEFAULT_PORT = 8443

    /** Only this exact value (after trim/lowercase) switches to the real source. */
    const val REAL = "real"

    /**
     * ONE grammar for all three targets:
     * - `source` blank/missing/anything-but-`real` → [SourceSpec.Fake];
     * - host blank/missing → [DEFAULT_HOST], port missing → [DEFAULT_PORT];
     * - a NON-EMPTY port that is not an integer in 1..65535 is a hard error —
     *   never a silent fallback to the default (a typo'd `VADE_PORT=8443 ` with a
     *   stray letter must fail loudly, not quietly point at 8443).
     */
    fun parse(source: String?, host: String?, port: String?, pairCode: String? = null): SourceSpec {
        if (source?.trim()?.lowercase() != REAL) return SourceSpec.Fake
        val trimmedHost = host?.trim()
        val resolvedHost = trimmedHost?.takeIf { it.isNotEmpty() } ?: DEFAULT_HOST
        val trimmedPort = port?.trim()
        val resolvedPort = when {
            trimmedPort.isNullOrEmpty() -> DEFAULT_PORT
            else -> trimmedPort.toIntOrNull()
                ?.takeIf { it in 1..65535 }
                ?: throw IllegalArgumentException(
                    "source.port: '$trimmedPort' is not an integer in 1..65535 — refusing to fall back to the default",
                )
        }
        val trimmedCode = pairCode?.trim()?.takeIf { it.isNotEmpty() }
        return SourceSpec.Real(resolvedHost, resolvedPort, trimmedCode)
    }

    /** Convenience entry taking the [RawSourceStrings] bundle. */
    fun parse(raw: RawSourceStrings): SourceSpec =
        parse(raw.source, raw.host, raw.port, raw.pairCode)
}

/**
 * URL-query → raw strings, shared so the wasm actual stays a one-liner and desktop
 * tests can pin the grammar (`?source=real&host=...&port=...&pair_code=...`).
 * A bare key (`?source`) parses as the empty string; empty is never `real`.
 */
fun rawStringsFromQuery(query: String): RawSourceStrings {
    val params = query.removePrefix("?").split('&').filter { it.isNotEmpty() }
    fun value(key: String): String? = params
        .firstOrNull { it == key || it.startsWith("$key=") }
        ?.let { if (it == key) "" else it.substringAfter('=') }
    return RawSourceStrings(
        value(SourceKeys.SOURCE),
        value(SourceKeys.HOST),
        value(SourceKeys.PORT),
        value(SourceKeys.PAIR_CODE),
    )
}

/** Per-key merge: the primary channel wins when it carried a value, else the fallback. */
fun mergeRawStrings(primary: RawSourceStrings?, fallback: RawSourceStrings): RawSourceStrings =
    if (primary == null) fallback
    else RawSourceStrings(
        source = primary.source ?: fallback.source,
        host = primary.host ?: fallback.host,
        port = primary.port ?: fallback.port,
        pairCode = primary.pairCode ?: fallback.pairCode,
    )

/** Parse from the current platform's raw strings. */
fun sourceSpecFromEnvironment(): SourceSpec = SourceConfig.parse(rawSourceStrings())

/**
 * The [SessionSource] this run should use — the ONE construction point the three
 * entry points share. `Fake` keeps today's fixture behaviour byte-for-byte; `Real`
 * returns the protocol-backed source wrapped in [AutoConnectingSessionSource].
 */
fun sessionSourceFromEnvironment(): SessionSource =
    sessionSourceFrom(rawSourceStrings())

/** Testable core of [sessionSourceFromEnvironment]: explicit raw strings in. */
fun sessionSourceFrom(raw: RawSourceStrings): SessionSource =
    when (val spec = SourceConfig.parse(raw)) {
        is SourceSpec.Fake -> FakeSessionSource()
        is SourceSpec.Real -> {
            val config = if (spec.pairCode != null) {
                VadeClientConfig(
                    host = spec.host,
                    port = spec.port,
                    browserSession = BrowserPairing(spec.pairCode),
                )
            } else {
                VadeClientConfig(host = spec.host, port = spec.port)
            }
            // g-pair-first-run · 运行期配对桥的**消费点**：首次运行引导卡成功后把「已换到
            // cookie 的 transport factory」装进 [PairingRuntime]，这里用它建
            // [RealSessionSource] —— 同一条链、同一次配对（一次性码不会被第二次兑换）。
            // `null`（默认）= 今天的逐字节行为。
            AutoConnectingSessionSource(
                RealSessionSource(config, PairingRuntime.pairedFactory),
                spec.host,
                spec.port,
            )
        }
    }

/** Budget for the runtime auto-connect wait (the loader's Failed banner shows past it). */
internal const val AUTO_CONNECT_TIMEOUT_MS = 15_000L

/**
 * Runtime shim between the switch and [RealSessionSource]'s contract: the real
 * source answers only after [RealSessionSource.connect], and the composition tree
 * (UiState/SessionsLoader) has no connect hook of its own. This wrapper connects
 * lazily on the first read — idempotent and concurrency-safe underneath — and
 * bounds the wait; a core that never comes up surfaces as a classified
 * [VadeException] (the loader's Failed banner), never a hang, never fixtures.
 */
internal class AutoConnectingSessionSource(
    private val real: RealSessionSource,
    private val host: String,
    private val port: Int,
    private val connectTimeoutMs: Long = AUTO_CONNECT_TIMEOUT_MS,
) : SessionSource, ModuleCatalogProvider {

    /**
     * 模块清单的能力透传：组合根只有这个 shim 与 [RealSessionSource]，两者都要能回答
     * `as? ModuleCatalogProvider`，否则 `AppShell` 的能力页接线在生产形态下会永远拿到
     * `null` —— 那是「接线看着在、实际不生效」的那类假接线。今天 [real] 也返回 null
     * （契约缺 `ui/modules`，见 `RealSessionSource.moduleCatalog` 的 KDoc），但透传本身
     * 是现在就要对的形状。
     */
    override val moduleCatalog: ModuleCatalog? get() = real.moduleCatalog

    override suspend fun sessions(includeArchived: Boolean): List<SessionSummary> {
        awaitConnected()
        return real.sessions(includeArchived)
    }

    override suspend fun messages(sessionId: String): List<ChatMessage> {
        awaitConnected()
        return real.messages(sessionId)
    }

    override fun observeEvents(): Flow<VadeEvent> = real.observeEvents()

    /** Test/diagnostic access: the wrapped real source (g-pair-first-run wiring tests). */
    internal val delegate: RealSessionSource get() = real

    private suspend fun awaitConnected() {
        real.connect() // idempotent; the protocol client owns the reconnect loop
        val reached = withTimeoutOrNull(connectTimeoutMs) {
            real.state.first { it is RealSessionState.Connected }
        }
        if (reached == null) {
            throw VadeException(
                VadeErrorCodes.UNAVAILABLE,
                "core at $host:$port not reachable within ${connectTimeoutMs}ms (auto-connect)",
            )
        }
    }
}

// =============================================================================
// g-pair-first-run · 首次运行配对的透传层（三端共用的判定 + 一次性尝试 + 运行期桥）
//
//  - 判定 [needsFirstRunPairing]：真源且缺配对码 = 首次运行，引导卡该出现。
//  - 尝试 [firstRunPairingAttempt]：把**用户输入的一次性码**交给已有的连接链 —— 语法仍是
//    [SourceConfig.parse]，构造仍是 [VadeClientConfig] + [BrowserPairing]（与
//    [sessionSourceFrom] 的 Real 分支同一形状），传输仍是 [KtorTransportFactory]（runner
//    每次重连的同一入口）。一次调用 = **恰好一次** `POST /ui/session` + 一次 WS 升级。
//  - 桥 [PairingRuntime]：成功后把已换到 cookie 的 factory 交给本进程的运行期状态，
//    [sessionSourceFrom] 用它建真源 —— 码只走函数参数与进程内存，**不落盘、不拼进 URL、
//    不进任何持久化字段**（守卫测试扫描持久化/URL sink，不看实现）。
//
//  桌面 / Android / Wasm 的环境变量与 intent extras / URL query 旧路径一字未动。
// =============================================================================

/** 首次运行引导卡的三种失败 —— 文案一一对应，互不可替换（见 `destinations/PairingCard.kt`）。 */
enum class PairingFailureKind {
    /** 码错 / 过期 / 已被用掉：需要用户重新取码再输一次。 */
    BadCode,

    /** 服务端没起来（连接被拒绝）：需要用户先启动 core，文案带启动命令。 */
    ServerDown,

    /** 网络不通（DNS / 超时 / 中途断开）：检查地址端口后可重试。 */
    Network,
}

/** 一次首次运行配对尝试的结局。 */
sealed interface PairingAttempt {

    /**
     * 配对成功：[factory] 里已缓存 `__Host-ac_ui` cookie（码已被消费一次）。交给
     * [PairingRuntime] 后，[sessionSourceFrom] 用它建真源 —— 列表随即开始拉真数据。
     */
    data class Connected(val factory: VadeTransportFactory) : PairingAttempt

    /** 配对失败：[kind] 选文案，[cause] 是传输原话（引导卡原样展示、不二次加工）。 */
    data class Rejected(val kind: PairingFailureKind, val cause: Throwable) : PairingAttempt
}

/** 首次运行判据：真源且没带配对码 = 引导卡该出现。纯函数，码与假源都不触发。 */
fun needsFirstRunPairing(raw: RawSourceStrings): Boolean {
    // 端口写错时 parse 抛 IllegalArgumentException —— 引导卡不因配置错误出现，
    // 那条错误由启动通道自己的报错负责。
    val spec = runCatching { SourceConfig.parse(raw) }.getOrNull() ?: return false
    return spec is SourceSpec.Real && spec.pairCode == null
}

/** 与 `shell/hostOf` / `shell/portOf` 同一条解析规则（同一默认值常量），两处由测试互相对照。 */
internal fun pairingEndpointOf(raw: RawSourceStrings): String {
    val host = raw.host?.trim()?.takeIf { it.isNotEmpty() } ?: SourceConfig.DEFAULT_HOST
    val port = raw.port?.trim()?.takeIf { it.isNotEmpty() } ?: SourceConfig.DEFAULT_PORT.toString()
    return "$host:$port"
}

/**
 * 把**用户输入的一次性码**交给已有连接链的一次性尝试。
 *
 * 恰好一次连接尝试：`factory.connect()` 内部 = `POST /ui/session`（body 里带码，换
 * `__Host-ac_ui` cookie）+ 一次 WS 升级 —— 与 runner 每次重连带的是同一个入口。探活的
 * 传输随即关闭：cookie 已缓存在 factory 里，链路重连时复用，一次性码不会被二次兑换。
 *
 * 失败在**这里**直接分类（不走 runner —— runner 把一切异常吞掉换成无限重试，三种失败
 * 在那条路上不可区分）：`AUTH_FAILED` = 码错/过期；"refused" = 服务端没起；其余 = 网络。
 * 码只走函数参数与进程内存 —— 不写盘、不拼进 URL、不进日志。
 */
suspend fun firstRunPairingAttempt(
    raw: RawSourceStrings,
    enteredCode: String,
    transportFactory: (VadeClientConfig) -> VadeTransportFactory = { config -> KtorTransportFactory(config) },
): PairingAttempt {
    val code = enteredCode.trim()
    val spec = runCatching { SourceConfig.parse(raw.source, raw.host, raw.port, code) }.getOrNull()
    if (spec !is SourceSpec.Real || spec.pairCode == null) {
        // 引导卡只在「真源且缺码」时出现；走到这里说明运行形态变了（如运行期切了假源）。
        return PairingAttempt.Rejected(
            PairingFailureKind.BadCode,
            VadeException(
                VadeErrorCodes.AUTH_FAILED,
                "pairing attempt rejected before the wire: source is not 'real' " +
                    "(parse produced ${if (spec is SourceSpec.Real) "Real" else "Fake"})",
            ),
        )
    }
    val config = VadeClientConfig(
        host = spec.host,
        port = spec.port,
        browserSession = BrowserPairing(spec.pairCode),
    )
    return try {
        // factory 构造也在尝试之内：任何环节的失败都要被分类成三种之一，
        // 而不是把原话裸抛给 UI。
        val factory = transportFactory(config)
        val probe = factory.connect()
        runCatching { probe.close() }
        PairingAttempt.Connected(factory)
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        PairingAttempt.Rejected(pairingFailureKindOf(t), t)
    }
}

/** 三种失败的分类：C-04 `AUTH_FAILED` = 码错；"refused" = 服务端没起；其余 = 网络。 */
internal fun pairingFailureKindOf(t: Throwable): PairingFailureKind = when {
    t is VadeException && t.code == VadeErrorCodes.AUTH_FAILED -> PairingFailureKind.BadCode
    t.message?.let { m ->
        m.contains("refused", ignoreCase = true) || m.contains("ECONNREFUSED", ignoreCase = true)
    } == true -> PairingFailureKind.ServerDown
    else -> PairingFailureKind.Network
}

/**
 * 运行期配对桥：引导卡成功后交出的 factory，[sessionSourceFrom] 的 Real 分支读它。
 *
 * 进程内存 state（`mutableStateOf`）—— 三端都没有可写的持久层入口，且桥里装的是
 * **factory 而不是码**：码在配对成功的那一刻已被消费，进程里此后不该再留它。
 */
object PairingRuntime {

    /** `null`（默认）= 未配对/非引导路径 —— [sessionSourceFrom] 保持今天的逐字节行为。 */
    var pairedFactory by mutableStateOf<VadeTransportFactory?>(null)
        private set

    /** 引导卡成功后调用；compose state —— 读取它的组合（入口源构造、引导卡可见性）会跟着刷新。 */
    fun install(factory: VadeTransportFactory) {
        pairedFactory = factory
    }

    /** 测试专用：把桥清回未配对态（桥是全局单例，用例之间必须互不污染）。 */
    internal fun clearForTests() {
        pairedFactory = null
    }
}
