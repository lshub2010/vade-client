package vade.ui.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import vade.ui.chat.ChatMessage
import vade.ui.protocol.ApprovalDecision
import vade.ui.protocol.ApprovalScope
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
 * H4 · 本平台能否真的兑换一次性配对码。
 *
 * `POST /ui/session` 的成功凭据是 `Set-Cookie: __Host-ac_ui`，而浏览器运行时**读不到**
 * 它 —— 在 Wasm 上发起这次 POST 只会把用户的一次性码烧掉，然后提示「码不对，再取一个」
 * （死循环）。所以能力是 `expect` 的：JVM/Android = true，浏览器 = false，调用方据此
 * 根本不发请求、直接给「请用桌面/手机配对」的文案。
 */
expect fun canBootstrapPairing(): Boolean

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
 *
 * H1 · 入口还要声明「本次启动没有任何可用的配对凭据」（[PairingRuntime.noteBooted]）：
 * 引导卡的可见性以这次启动为准，而不是以「谁组合了会话列表」为准 —— 否则每个渲染列表的
 * 组合/测试都会凭空长出一张配对卡。
 */
fun sessionSourceFromEnvironment(): SessionSource {
    PairingRuntime.noteBooted()
    return sessionSourceFrom(rawSourceStrings())
}

/**
 * Testable core of [sessionSourceFromEnvironment]: explicit raw strings in.
 *
 * [SourceSpec.Fake] + 已配对（[PairingRuntime] 里装着 factory）= **默认启动配对成功之后**
 * 的重建点：真源要按用户输入的 host/port 建，而不是按空配置。`Fake` 且未配对仍是夹具。
 */
fun sessionSourceFrom(raw: RawSourceStrings): SessionSource =
    when (val spec = SourceConfig.parse(raw)) {
        is SourceSpec.Fake -> {
            val paired = PairingRuntime.state as? PairingRuntimeState.Paired
            if (paired != null) {
                realAutoConnecting(VadeClientConfig(host = paired.host, port = paired.port), paired.factory)
            } else {
                FakeSessionSource()
            }
        }
        is SourceSpec.Real -> {
            // P0-1 · 码必须真的进到 [VadeClientConfig] 里。之前这里算出了 config 却在
            // 构造 [RealSessionSource] 时只传 host/port —— `VADE_PAIR_CODE` / `pair_code`
            // extra / `?pair_code=` 因此**完全不生效**：WS 升级被当成「设备 peer」，core
            // 走 Noise 握手，明文 JSON 客户端永远接不上（用户看到的是「core 不可达」）。
            //
            // 浏览器（[canBootstrapPairing] = false）不注入：它读不到 `POST /ui/session`
            // 的 `Set-Cookie`，发这次请求只会把用户的一次性码烧掉（H4）。
            val config = if (spec.pairCode != null && canBootstrapPairing()) {
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
            realAutoConnecting(config, PairingRuntime.pairedFactory)
        }
    }

/** Budget for the runtime auto-connect wait (the loader's Failed banner shows past it). */
internal const val AUTO_CONNECT_TIMEOUT_MS = 15_000L

/** 真源装配的唯一形状：shim 包住真源，完整 [VadeClientConfig]（含 `browserSession`）一路带进去。 */
private fun realAutoConnecting(
    config: VadeClientConfig,
    factory: VadeTransportFactory?,
): SessionSource = AutoConnectingSessionSource(
    RealSessionSource(config, factory),
    config.host,
    config.port,
)

/**
 * Runtime shim between the switch and [RealSessionSource]'s contract: the real
 * source answers only after [RealSessionSource.connect], and the composition tree
 * (UiState/SessionsLoader) has no connect hook of its own. This wrapper connects
 * lazily on the first read — idempotent and concurrency-safe underneath — and
 * bounds the wait; a core that never comes up surfaces as a classified
 * [VadeException] (the loader's Failed banner), never a hang, never fixtures.
 *
 * ## A1 · 它必须实现 [AdapterHost]（和 [ActivitySeamHost]）
 * 组合根建出来的**唯一**生产形态就是本类；`AppShell.adaptersOf` 用
 * `(state.source as? AdapterHost)?.adapters` 取适配器包。只要本类不实现它，真源下
 * 聊天/元数据/模型/上传/能力页会全部静默退回 fixtures —— 那是「真模式是假的」，
 * 三份审查独立命中的 A1。透传而不是缓存：适配器包随连接存在（断开即 null），
 * 由 [RealSessionSource] 自己管生命周期。
 */
internal class AutoConnectingSessionSource(
    private val real: RealSessionSource,
    private val host: String,
    private val port: Int,
    private val connectTimeoutMs: Long = AUTO_CONNECT_TIMEOUT_MS,
) : SessionSource, AdapterHost, ActivitySeamHost, ModuleCatalogProvider, DisconnectableSource {

    /** A1 · 适配器包透传（未连接 = null，与 `RealSessionSource` 的契约逐字一致；snapshot state 读，P1-1）。 */
    override val adapters: AdapterProvider? get() = real.adapters

    /**
     * A3 · 活动页读取窄口透传：**恒非空**（晚绑定 client）。缺了它，顶栏在真源（本 shim）
     * 下会走 `activitySourcesOf` 的"未接线"分支并显示**编造的 fixture token 数**。
     */
    override val activitySeams: ActivitySeams get() = real.activitySeams

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

    /**
     * B6 · `approval/decide` 透传：先等连接（与 sessions/messages 同一口径），再交
     * [real] 的实现 —— 参数逐字、错误原码穿透。
     */
    override suspend fun decideApproval(requestId: String, decision: ApprovalDecision, scope: ApprovalScope) {
        awaitConnected()
        real.decideApproval(requestId = requestId, decision = decision, scope = scope)
    }

    /**
     * A4-1 · 「新建会话」透传：先等连接（与 sessions/messages 同一口径），再交 [real] 的
     * `ui/attach(null)` 实现 —— 帧逐字、错误原码穿透。缺了它，真实模式（`SourceConfig`
     * 组装出来的就是本 shim）的「新建」会落到接口的响亮默认失败上。
     */
    override suspend fun createSession(): String {
        awaitConnected()
        return real.createSession()
    }

    /** Test/diagnostic access: the wrapped real source (g-pair-first-run wiring tests). */
    internal val delegate: RealSessionSource get() = real

    /**
     * P1-3 · 关停透传：组合根（`UiState.dispose()`）拿到的是本 shim，断开必须落到真源
     * 的连接上。只关 client/收集器，桥里的 transport factory 原样保留。
     */
    override suspend fun disconnect() = real.disconnect()

    /**
     * 等到连上为止，并把**真实原因**带回去（H3）。
     *
     * 以前这里只会等 15 秒然后统一报「core 没起/端口不对」—— 码错、码过期、401 全被
     * 协议 runner 的无限重连吞掉，用户看到的是错误诊断。现在：
     *  - 连接尝试抛出**不可重试**的错误（`CODE_INVALID` / `CODE_EXPIRED` / `AUTH_FAILED` /
     *    绑定类）→ **立刻**把原异常抛给上层（列表横幅显示分类 + 原码），并把「码被拒过」
     *    记进 [PairingRuntime]，让引导卡重新出现可重试；
     *  - 超时（core 真没起来 / 网络不通）→ 抛 `UNAVAILABLE`，但把 runner 记下的最后一条
     *    原话拼进 message，横幅不再撒谎。
     */
    private suspend fun awaitConnected() {
        real.connect() // idempotent; the protocol client owns the reconnect loop
        val outcome = withTimeoutOrNull(connectTimeoutMs) {
            real.state.first { state ->
                state is RealSessionState.Connected ||
                    (state is RealSessionState.Reconnecting && isPermanentConnectFailure(real.lastConnectFailure))
            }
        }
        if (outcome is RealSessionState.Connected) return

        val cause = real.lastConnectFailure
        if (outcome != null && cause != null) {
            // 不可重试：带着原码/原话立刻失败，不再空转。
            if (isPairCodeRejection(cause)) PairingRuntime.noteCodeRejected()
            throw cause
        }
        val lastWords = cause?.message?.takeIf { it.isNotBlank() }?.let { "；最后一次失败：$it" } ?: ""
        throw VadeException(
            VadeErrorCodes.UNAVAILABLE,
            "core at $host:$port not reachable within ${connectTimeoutMs}ms (auto-connect)$lastWords",
        )
    }
}

/**
 * 重试**不可能**修好的连接失败（码错/过期、认证、身份或协议不匹配）：立即失败。
 * 传输层错误（拒绝连接、超时、DNS）不在此列 —— core 可能只是还没起来，继续等。
 */
internal fun isPermanentConnectFailure(t: Throwable?): Boolean {
    val code = (t as? VadeException)?.code ?: return false
    return code in PERMANENT_CONNECT_FAILURES
}

private val PERMANENT_CONNECT_FAILURES: Set<String> = setOf(
    VadeErrorCodes.CODE_INVALID,
    VadeErrorCodes.CODE_EXPIRED,
    VadeErrorCodes.AUTH_FAILED,
    VadeErrorCodes.ORIGIN_MISMATCH,
    VadeErrorCodes.PROTOCOL_MISMATCH,
    VadeErrorCodes.MANIFEST_INVALID,
    VadeErrorCodes.SETUP_UNAUTHORIZED,
    VadeErrorCodes.BIND_UNAUTHORIZED,
    VadeErrorCodes.BIND_FINGERPRINT_MISMATCH,
    VadeErrorCodes.BIND_HELLO_SIG_INVALID,
    VadeErrorCodes.BIND_KEY_UNSUPPORTED,
    VadeErrorCodes.BIND_DEVICE_LIMIT,
    VadeErrorCodes.BIND_CLOCK_SKEW,
    VadeErrorCodes.BIND_SETUP_LOCKED,
)

/** 这次失败是不是「配对码被 core 拒了」（引导卡要重新出现让用户重试）。 */
internal fun isPairCodeRejection(t: Throwable): Boolean {
    val ex = t as? VadeException ?: return false
    return ex.code == VadeErrorCodes.CODE_INVALID ||
        ex.code == VadeErrorCodes.CODE_EXPIRED ||
        (ex.code == VadeErrorCodes.AUTH_FAILED && ex.message?.contains("/ui/session") == true)
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

/**
 * 首次运行引导卡的失败分类 —— 文案一一对应，互不可替换（见 `destinations/PairingCard.kt`）。
 *
 * 与 `data/VadeErrorCategory` 的分工：那张表是**协议码 → UI 桶**（横幅用），这里是
 * **引导卡的下一步**（卡片用，带可照抄的动作）。[BadCode] 现在同时覆盖
 * `CODE_INVALID` / `CODE_EXPIRED` / `AUTH_FAILED` —— core 的原码会原样出现在详情行里。
 */
enum class PairingFailureKind {
    /** 码错 / 过期 / 已被用掉：需要用户重新取码再输一次。 */
    BadCode,

    /** 服务端没起来（连接被拒绝）：需要用户先启动 core，文案带启动命令。 */
    ServerDown,

    /** 网络不通（DNS / 超时 / 中途断开）：检查地址端口后可重试。 */
    Network,

    /** 地址/端口本身不合法（端口越界、空 host）：卡片内直接改，不发一次网络请求。 */
    BadConfig,

    /**
     * H4 · 本平台**不能**兑换一次性码（浏览器/Wasm 读不到 `Set-Cookie`）：不发
     * `POST /ui/session`，直接给「请用桌面/手机配对」的可行动文案。
     */
    NotAvailableHere,
}

/** 一次首次运行配对尝试的结局。 */
sealed interface PairingAttempt {

    /**
     * 配对成功：[factory] 里已缓存 `__Host-ac_ui` cookie（码已被消费一次）。交给
     * [PairingRuntime] 后，[sessionSourceFrom] 用它建真源 —— 列表随即开始拉真数据。
     *
     * [host] / [port] 是这次尝试**实际连的**地址（用户可能在卡里改过），配对成功后
     * [PairingRuntime] 要按它建真源，而不是按启动时那条空配置。
     */
    data class Connected(
        val factory: VadeTransportFactory,
        val host: String = SourceConfig.DEFAULT_HOST,
        val port: Int = SourceConfig.DEFAULT_PORT,
    ) : PairingAttempt

    /** 配对失败：[kind] 选文案，[cause] 是传输原话（引导卡原样展示、不二次加工）。 */
    data class Rejected(val kind: PairingFailureKind, val cause: Throwable) : PairingAttempt
}

/**
 * 首次运行判据（H1/H2 的口径）：**未配对**就该出卡，而不再是「真源且缺码」。
 *
 *  - [PairingRuntimeState.NotBooted]（测试直构的组合 / 非入口路径）→ 不出现：引导卡是
 *    **启动**决定，不是「谁组合了会话列表」决定；
 *  - [PairingRuntimeState.Paired] → 不出现；
 *  - 上一次尝试的码被 core 拒了（[PairingRuntimeState.Unpaired.codeRejected]）→ 仍出现，
 *    可重试（错码/过期码不会把入口关死）；
 *  - 否则：真源缺码 → 出现；**什么都没配**（默认启动）→ 出现；显式 `source=fixtures` /
 *    `source=fake` → 不出现（夹具是开发者显式选择的逃生口）。
 *
 * 端口写错时 [SourceConfig.parse] 抛 `IllegalArgumentException` —— 引导卡不因配置错误
 * 出现，那条错误由入口的可读错误屏负责（P1-4）。
 */
fun needsFirstRunPairing(raw: RawSourceStrings): Boolean {
    when (val runtime = PairingRuntime.state) {
        is PairingRuntimeState.Paired -> return false
        is PairingRuntimeState.NotBooted -> return false
        is PairingRuntimeState.Unpaired -> if (runtime.codeRejected) return true
    }
    val spec = runCatching { SourceConfig.parse(raw) }.getOrNull() ?: return false
    return when (spec) {
        is SourceSpec.Real -> spec.pairCode == null
        is SourceSpec.Fake -> raw.source.isNullOrBlank()
    }
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
 * 失败在**这里**直接分类（不走 runner —— runner 把一切异常吞掉换成无限重试，几种失败
 * 在那条路上不可区分）：
 *  - `CODE_INVALID` / `CODE_EXPIRED` / `AUTH_FAILED` = 码错/过期（core 的原码透传到这里）；
 *  - 端口/地址不合法 = [PairingFailureKind.BadConfig]，**一次网络请求都不发**；
 *  - "refused" = 服务端没起；其余 = 网络。
 *
 * H4 · 本平台不能兑换码（[canBootstrapPairing] = false，浏览器/Wasm）时直接返回
 * [PairingFailureKind.NotAvailableHere] —— 不烧掉用户的一次性码。
 *
 * 码只走函数参数与进程内存 —— 不写盘、不拼进 URL、不进日志。
 */
suspend fun firstRunPairingAttempt(
    raw: RawSourceStrings,
    enteredCode: String,
    transportFactory: (VadeClientConfig) -> VadeTransportFactory = { config -> KtorTransportFactory(config) },
): PairingAttempt {
    if (!canBootstrapPairing()) {
        return PairingAttempt.Rejected(
            PairingFailureKind.NotAvailableHere,
            VadeException(
                VadeErrorCodes.UNSUPPORTED,
                "this build cannot bootstrap a browser session: the browser runtime cannot read the " +
                    "Set-Cookie header from POST /ui/session, so no request was sent (the one-time " +
                    "code was NOT consumed) — pair on desktop or mobile instead",
            ),
        )
    }
    val code = enteredCode.trim()
    val spec = runCatching { SourceConfig.parse(raw.source, raw.host, raw.port, code) }
        .getOrElse { return PairingAttempt.Rejected(PairingFailureKind.BadConfig, it) }
    if (spec !is SourceSpec.Real || spec.pairCode == null) {
        // 引导卡只在「未配对」时出现；走到这里说明运行形态变了（如运行期切了假源）。
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
        // factory 构造也在尝试之内：任何环节的失败都要被分类，而不是把原话裸抛给 UI。
        val factory = transportFactory(config)
        val probe = factory.connect()
        runCatching { probe.close() }
        PairingAttempt.Connected(factory, spec.host, spec.port)
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        PairingAttempt.Rejected(pairingFailureKindOf(t), t)
    }
}

/**
 * 失败的分类。码类错误优先看 **core 回的原码**（`CODE_INVALID` / `CODE_EXPIRED`），
 * 不再把所有 `AUTH_FAILED` 笼统说成「配对码不对」；配置解析错误单独一类，不发网络请求。
 */
internal fun pairingFailureKindOf(t: Throwable): PairingFailureKind = when {
    t is VadeException && t.code in PAIR_CODE_ERRORS -> PairingFailureKind.BadCode
    t is IllegalArgumentException -> PairingFailureKind.BadConfig
    // A2 · Android 的明文策略拒绝这个地址（`CLEARTEXT communication to … not permitted`）：
    // 这是**地址不被允许**，不是网络不通 —— 分类到 BadConfig，卡片才会让用户改地址
    // （回环 / 模拟器别名 / `adb reverse`），而不是让他反复点「重试」。
    t.message?.contains("CLEARTEXT", ignoreCase = true) == true -> PairingFailureKind.BadConfig
    t.message?.let { m ->
        m.contains("refused", ignoreCase = true) || m.contains("ECONNREFUSED", ignoreCase = true)
    } == true -> PairingFailureKind.ServerDown
    else -> PairingFailureKind.Network
}

private val PAIR_CODE_ERRORS: Set<String> = setOf(
    VadeErrorCodes.CODE_INVALID,
    VadeErrorCodes.CODE_EXPIRED,
    VadeErrorCodes.AUTH_FAILED,
)

/**
 * 运行期配对桥：已经换到 cookie 的 factory + 用户实际连的 endpoint，
 * [sessionSourceFrom] 读它建真源。
 *
 * 进程内存 state（`mutableStateOf`）—— 三端都没有可写的持久层入口，且桥里装的是
 * **factory 而不是码**：码在配对成功的那一刻已被消费，进程里此后不该再留它。
 *
 * 三个状态的意义：
 *  - [PairingRuntimeState.NotBooted]：还没有任何入口声明过本次运行（测试直构、纯数据
 *    用例）→ 引导卡不出现；
 *  - [PairingRuntimeState.Unpaired]：入口已启动但没有可用凭据 → 引导卡出现；
 *  - [PairingRuntimeState.Paired]：已配对，真源按 [PairingRuntimeState.Paired.host]/[port]
 *    建，界面随 state 刷新（compose state）。
 */
sealed interface PairingRuntimeState {

    /** 默认：入口还没声明本次运行（夹具/测试路径），也不是已配对。 */
    data object NotBooted : PairingRuntimeState

    /**
     * 入口已启动、尚未配对。[codeRejected] = 上一次码被 core 拒过（错码/过期码/启动参数里
     * 带的码失效）→ 引导卡必须仍然出现，用户才能重试（H2）。
     */
    data class Unpaired(val codeRejected: Boolean = false) : PairingRuntimeState

    /** 已配对：factory 里已有 `__Host-ac_ui` cookie，真源按 [host]:[port] 建。 */
    data class Paired(
        val factory: VadeTransportFactory,
        val host: String,
        val port: Int,
    ) : PairingRuntimeState
}

object PairingRuntime {

    /** 桥的唯一可变字段（守卫测试钉住「码不进任何字段」：类型里没有 String 码）。 */
    var state: PairingRuntimeState by mutableStateOf<PairingRuntimeState>(PairingRuntimeState.NotBooted)
        private set

    /** 已配对时的 factory；`null` = 未配对/未启动。 */
    val pairedFactory: VadeTransportFactory? get() = (state as? PairingRuntimeState.Paired)?.factory

    /** 已配对时的 `host:port`（文案用）；`null` = 未配对。 */
    val pairedEndpoint: String? get() = (state as? PairingRuntimeState.Paired)?.let { "${it.host}:${it.port}" }

    /** 入口（[sessionSourceFromEnvironment]）调用：本次启动没有任何可用凭据 → 允许出卡。 */
    fun noteBooted() {
        if (state is PairingRuntimeState.NotBooted) state = PairingRuntimeState.Unpaired()
    }

    /** 引导卡成功后调用；compose state —— 读取它的组合（入口源构造、引导卡可见性）会跟着刷新。 */
    fun install(
        factory: VadeTransportFactory,
        host: String = SourceConfig.DEFAULT_HOST,
        port: Int = SourceConfig.DEFAULT_PORT,
    ) {
        state = PairingRuntimeState.Paired(factory, host, port)
    }

    /**
     * 一次配对码被 core 拒绝（错码/过期码，或启动参数里带的码失效）：桥回到「未配对 +
     * 码被拒过」，引导卡因此重新出现 —— 而不是把用户关在「一直重连」的黑箱里（H2/H3）。
     */
    fun noteCodeRejected() {
        if (state !is PairingRuntimeState.Paired) state = PairingRuntimeState.Unpaired(codeRejected = true)
    }

    /** 测试专用：把桥清回「未启动」（桥是全局单例，用例之间必须互不污染）。 */
    internal fun clearForTests() {
        state = PairingRuntimeState.NotBooted
    }
}
