package vade.ui.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import vade.ui.chat.ChatMessage
import vade.ui.protocol.ApprovalDecision
import vade.ui.protocol.ApprovalScope
import vade.ui.protocol.BrowserPairing
import vade.ui.protocol.KtorTransportFactory
import vade.ui.protocol.SessionCookie
import vade.ui.protocol.TlsTrustMode
import vade.ui.protocol.VadeClientConfig
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException
import vade.ui.protocol.VadeTransport
import vade.ui.protocol.VadeTransportFactory
import vade.ui.protocol.isCredentialHandshakeFailure

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
    /**
     * HTTPS switch (r5-tls). **Not** a platform channel: there is deliberately no
     * `VADE_TLS` / `?tls=` key — a self-signed core can only be trusted by a human, and
     * the only place that asks is the pairing form's switch + warning dialog
     * (`destinations/PairingCard.kt`). The flag therefore has to be one of the LAST
     * parameters (every positional construction of this data class in the repo passes
     * `pairCode` last) and defaults to `false` = today's plain-HTTP behaviour.
     *
     * `tls = true` means "dial `https`/`wss`"; whether the self-signed certificate is
     * accepted is a *separate* decision — see `VadeClientConfig.allowInsecureTls`, which
     * is only set from a confirmed dialog.
     */
    val tls: Boolean = false,
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
        /**
         * r5-tls · 拨 `https`/`wss` 而不是 `http`/`ws`（[RawSourceStrings.tls] 透传）。
         * 只决定**协议**；自签证书要不要接受由调用方另行决定（`allowInsecureTls`）。
         */
        val tls: Boolean = false,
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
     *
     * [tls] is *passed through* only (r5-tls): it never changes the mode word or the
     * port grammar, so a `tls = true` with `source = "fixtures"` still parses to
     * [SourceSpec.Fake] and the flag is simply ignored.
     */
    fun parse(
        source: String?,
        host: String?,
        port: String?,
        pairCode: String? = null,
        tls: Boolean = false,
    ): SourceSpec {
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
        return SourceSpec.Real(resolvedHost, resolvedPort, trimmedCode, tls)
    }

    /** Convenience entry taking the [RawSourceStrings] bundle (incl. `tls`). */
    fun parse(raw: RawSourceStrings): SourceSpec =
        parse(raw.source, raw.host, raw.port, raw.pairCode, raw.tls)
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
        // Boolean flag, not a nullable key: "either channel switched it on" wins. Neither
        // platform channel can set it today (see [RawSourceStrings.tls]) — it is carried so a
        // future channel cannot silently drop a `true` that the UI produced.
        tls = primary.tls || fallback.tls,
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
                // r5-tls · 桥里记着这次配对用的协议与**信任档位** —— 重建真源必须逐字复用它。
                //
                // P0-6 · 这里以前是 `allowInsecureTls = paired.tls`：只要用了 HTTPS（哪怕是
                // 正规 CA 签发的证书）重建出来的真源就一律 trust-all。协议与信任是两件事，
                // 现在只有 `trustMode == InsecureConfirmed`（用户对着自签警告点过「仍然连接」）
                // 才会打开 trust-all；SystemChain 永远走正常校验。
                realAutoConnecting(
                    VadeClientConfig(
                        host = paired.host,
                        port = paired.port,
                        tls = paired.tls,
                        allowInsecureTls = paired.trustMode == TlsTrustMode.InsecureConfirmed,
                    ),
                    paired.factory,
                )
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
            //
            // r5-tls · 协议照 `spec.tls` 透传，但 **`allowInsecureTls` 恒为 false**：
            // trust-all 只有在用户对着警告弹层点过「仍然连接」之后才允许（见
            // [firstRunPairingAttempt]），而这条分支来自平台原始通道（env / extras /
            // query），那里根本没有「人确认过」这回事。今天三个 actual 都不产生
            // `tls = true`，所以这条分支的实际行为逐字节不变。
            val base = VadeClientConfig(host = spec.host, port = spec.port, tls = spec.tls)
            val config = if (spec.pairCode != null && canBootstrapPairing()) {
                base.copy(browserSession = BrowserPairing(spec.pairCode))
            } else {
                base
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

    /**
     * P0-6 · TLS 握手阶段的**证书不被信任**（自签 / 主机名不匹配 / 链不完整）。
     *
     * 它是唯一会触发「信任这张证书？」二次确认的分类：先按 [TlsTrustMode.SystemChain]
     * 试一次，证书不被系统信任时**一个字节的请求都还没发出去**（TLS 在 HTTP 之前失败，
     * 一次性码没被烧），用户确认后再以 [TlsTrustMode.InsecureConfirmed] 重试一次。
     * 正规 CA 签发的证书永远走不到这里 —— 那才是「合法 CA 正常校验」。
     */
    CertificateUntrusted,
}

/** 一次首次运行配对尝试的结局。 */
sealed interface PairingAttempt {

    /**
     * 配对成功：[factory] 里已缓存 `__Host-ac_ui` cookie（码已被消费一次）。交给
     * [PairingRuntime] 后，[sessionSourceFrom] 用它建真源 —— 列表随即开始拉真数据。
     *
     * [host] / [port] 是这次尝试**实际连的**地址（用户可能在卡里改过），配对成功后
     * [PairingRuntime] 要按它建真源，而不是按启动时那条空配置。
     *
     * [tls] 是这次尝试**实际用的协议**（r5-tls）：桥按它重建真源（`https`/`wss`），
     * 不再退回 `http`。`true` 同时意味着用户在警告弹层里确认过自签证书。
     */
    data class Connected(
        val factory: VadeTransportFactory,
        val host: String = SourceConfig.DEFAULT_HOST,
        val port: Int = SourceConfig.DEFAULT_PORT,
        val tls: Boolean = false,
        /**
         * P0-6 · 这次尝试**实际用的信任档位**：SystemChain（默认，合法 CA 正常校验）或
         * InsecureConfirmed（用户对自签证书显式确认过）。桥与持久化都按它记 —— 重建真源时
         * 只有后者才允许 trust-all。
         */
        val trustMode: TlsTrustMode = TlsTrustMode.SystemChain,
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
        is PairingRuntimeState.Unpaired ->
            // 码被拒过 / 上次持久化的凭据被 core 拒了 / 凭据留着但 core 暂时不可达 ——
            // 三种都要出卡（第三种还要带「用已保存的配对重连」，见 PairingCard）。
            if (runtime.codeRejected || runtime.credentialRejected || runtime.storedCredentialKept) return true
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
    /**
     * r5-tls · 用户是否在警告弹层里确认过「信任这台主机的自签证书」。
     *
     * 默认 `false` —— 数据层不替用户做这个决定。为 `true` 时且 [RawSourceStrings.tls]
     * 也为真，才会把 `allowInsecureTls` 带进 [VadeClientConfig]（关掉证书链校验与主机名
     * 校验）。UI（`PairingForm`）只在**确认之后**才调用本函数，所以「未确认不得发任何
     * 请求」由调用点保证；这里再要求 `tls` 为真，是为了让「只确认了、但走明文」不会
     * 意外打开 trust-all。
     */
    allowInsecureTls: Boolean = false,
    // 必须保持在最后一个参数：现有调用点用 trailing lambda 传 factory，
    // 它一旦被挤到前面，lambda 就会绑定到 Boolean 上（编译器直接报错）。
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
    val spec = runCatching { SourceConfig.parse(raw.source, raw.host, raw.port, code, raw.tls) }
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
        // r5-tls · 协议与「信任档位」一起决定这次尝试：tls 决定 https/wss；
        // allowInsecureTls 只在用户确认过（且确实是 tls）时关掉证书校验 —— 这是
        // [TlsTrustMode.of] 唯一的推导，合法 CA 的 HTTPS 永远走 SystemChain（P0-6）。
        tls = spec.tls,
        allowInsecureTls = allowInsecureTls && spec.tls,
        browserSession = BrowserPairing(spec.pairCode),
    )
    val trustMode = TlsTrustMode.of(spec.tls, allowInsecureTls)
    return try {
        // factory 构造也在尝试之内：任何环节的失败都要被分类，而不是把原话裸抛给 UI。
        val factory = transportFactory(config)
        val probe = factory.connect()
        runCatching { probe.close() }
        PairingAttempt.Connected(factory, spec.host, spec.port, spec.tls, trustMode)
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
    // P0-6 · 证书不被信任（自签 / 名字不匹配）：唯一会要求「显式确认」的一类。它发生在
    // TLS 握手阶段 —— 请求还没发出去、一次性码没被烧，所以可以重试一次（InsecureConfirmed）。
    isCertificateTrustFailure(t) -> PairingFailureKind.CertificateUntrusted
    // A2 · Android 的明文策略拒绝这个地址（`CLEARTEXT communication to … not permitted`）：
    // 这是**地址不被允许**，不是网络不通 —— 分类到 BadConfig，卡片才会让用户改地址
    // （回环 / 模拟器别名 / `adb reverse`），而不是让他反复点「重试」。
    t.message?.contains("CLEARTEXT", ignoreCase = true) == true -> PairingFailureKind.BadConfig
    t.message?.let { m ->
        m.contains("refused", ignoreCase = true) || m.contains("ECONNREFUSED", ignoreCase = true)
    } == true -> PairingFailureKind.ServerDown
    else -> PairingFailureKind.Network
}

/**
 * P0-6 · 这次失败是不是「对端证书不被系统信任」。
 *
 * 三端的异常形状不同（JVM/OkHttp：`SSLHandshakeException` + `CertPathValidatorException`；
 * Android：`SSLHandshakeException: Trust anchor for certification path not found`；
 * 名字不匹配：`No subject alternative DNS name matching …` 或 `HostnameVerifier` 的
 * `CertificateException`），共同点是**类型名或原话里带证书语义**。因此同时看类型名、
 * 原话与 cause 链。
 *
 * 只用于「要不要弹自签确认」这个决定，所以宁可漏判成 Network（用户重试/看诊断），
 * 也不能把普通网络错误误判成证书问题（那会把「core 没起来」变成「你不信任证书」）。
 */
internal fun isCertificateTrustFailure(t: Throwable, depth: Int = 0): Boolean {
    if (depth >= 8) return false
    val name = t::class.simpleName.orEmpty()
    if (name.contains("SSLHandshake") ||
        name.contains("CertificateException") ||
        name.contains("CertPathValidator") ||
        name.contains("CertPathBuilder")
    ) {
        return true
    }
    val message = t.message.orEmpty()
    if (message.contains("PKIX", ignoreCase = true) ||
        message.contains("certification path", ignoreCase = true) ||
        message.contains("Trust anchor", ignoreCase = true) ||
        message.contains("SSLHandshake", ignoreCase = true) ||
        message.contains("unable to find valid certification path", ignoreCase = true) ||
        message.contains("subject alternative", ignoreCase = true)
    ) {
        return true
    }
    val cause = t.cause ?: return false
    return isCertificateTrustFailure(cause, depth + 1)
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
     * 入口已启动、尚未配对。
     *
     * [codeRejected] = 上一次码被 core 拒过（错码/过期码/启动参数里带的码失效）→ 引导卡
     * 必须仍然出现，用户才能重试（H2）。
     *
     * [credentialRejected] = **持久化下来的那张 cookie** 被 core 拒了（401 / 握手失败）。
     * 凭据已被删除，卡片要出现且要说清「上次的配对已失效」，而不是继续僵尸重连（P0-2）。
     *
     * [storedCredentialKept] = 凭据还在，但这次没能连上（core 没起来 / 网络不通 / 证书
     * 变了）。卡片除了输入新码，还要给「用已保存的配对重连」—— 否则一次 core 晚启动就
     * 逼用户去终端再捞一个码。
     */
    data class Unpaired(
        val codeRejected: Boolean = false,
        val credentialRejected: Boolean = false,
        val storedCredentialKept: Boolean = false,
    ) : PairingRuntimeState

    /**
     * 已配对：factory 里已有 `__Host-ac_ui` cookie，真源按 [host]:[port] 建。
     *
     * [tls] 是这次配对**实际用的协议**（r5-tls）：真源重建时照它拨 `https`/`wss`。
     *
     * [trustMode] 是证书信任档位（P0-6）：只有用户在自签警告上确认过的
     * [TlsTrustMode.InsecureConfirmed] 才会让重建出来的真源打开 trust-all；
     * [TlsTrustMode.SystemChain]（默认）永远走正常校验 —— 即使 `tls = true`。
     */
    data class Paired(
        val factory: VadeTransportFactory,
        val host: String,
        val port: Int,
        val tls: Boolean = false,
        val trustMode: TlsTrustMode = TlsTrustMode.SystemChain,
    ) : PairingRuntimeState
}

/**
 * 旧 core 的 `DELETE /ui/session` 预算。3 s：够一次局域网往返，又不至于让「换 core」在
 * 一台已经关机的旧 core 上卡住（超时后照样切换，只是 UI 提示吊销失败）。
 */
const val SESSION_REVOKE_TIMEOUT_MS: Long = 3_000L

/**
 * 旧 core 的 `DELETE /ui/session` 结局（换 core / 断开时，**best-effort**）。
 *
 * 三种值的分工是「用户要不要被告知」：
 *  - [Revoked]：旧 core 确认吊销，或旧 core 本来就没有本进程的 UI 会话（device 会话 /
 *    device 直连 / 未配对）——**不需要**提示；
 *  - [NotNeeded]：一个请求都没发（没有旧 core、目标配置填错了、本平台不能兑换码）；
 *  - [Failed]：发了但没成功（超时 / 网络 / 旧 core 拒绝）。切换 / 断开**照样**发生，
 *    UI 必须提示「旧凭证吊销失败」，否则用户以为旧 core 上那张 cookie 已经没了。
 */
sealed interface SessionRevocation {

    /** 旧 core 确认吊销（2xx），或它本来就没持有 UI 会话 —— 无事可做就是成功。 */
    data object Revoked : SessionRevocation

    /** 没有旧 core 可吊销：未配对、目标配置不合法、本平台不能配对 —— 一次请求都不发。 */
    data object NotNeeded : SessionRevocation

    /** best-effort 失败：[cause] 是传输原话 / [SessionRevokeTimeoutException]。 */
    data class Failed(val cause: Throwable) : SessionRevocation
}

/**
 * 旧 core 在预算内没答复 `DELETE /ui/session`。单独一个类型是为了让 UI 能说清
 * 「超时」而不是把 Ktor 的取消细节原样抛给用户（取消本身仍然原样向上传播）。
 */
class SessionRevokeTimeoutException(timeoutMs: Long) :
    Exception("DELETE /ui/session did not answer within ${timeoutMs}ms")

/** 「换 core」的完整结局：旧凭证吊销结果 + 新 core 的配对结果（两者都要给 UI 看）。 */
data class CoreSwitchOutcome(
    val revocation: SessionRevocation,
    val attempt: PairingAttempt,
)

/**
 * P0-1 · 启动恢复的探测预算：一次 WS 升级 + 一帧请求。
 *
 * 5 s 够一次局域网往返，又不会让「core 没起来」把冷启动拖成一张白屏（UNAVAILABLE 之后
 * 卡片立刻出现，凭据还留着，用户可以点「用已保存的配对重连」）。
 */
const val PAIRING_PROBE_TIMEOUT_MS: Long = 5_000L

/** 活性探针：一帧带 id 的普通请求；任何 JSON-RPC 回包（result 或 error）都证明会话是活的。 */
private const val PAIRING_PROBE_FRAME: String =
    """{"jsonrpc":"2.0","id":1,"method":"ui/state.get","params":{}}"""

/** [PairingRuntime.restoreStoredCredential] 的结局。 */
sealed interface PairingRestoreOutcome {

    /** 没有存过凭据（或显式通道接管了本次启动）—— 什么都不做，走首次运行流程。 */
    data object NoCredential : PairingRestoreOutcome

    /** 探测成功：桥已进 Paired，凭据已刷新落盘（App 据此重建真源）。 */
    data class Restored(
        val host: String,
        val port: Int,
        val tls: Boolean,
        val trustMode: TlsTrustMode,
    ) : PairingRestoreOutcome

    /** 凭据被拒（401 / 握手失败 / 升级后无应答）：已删除，桥退回 Unpaired → 引导卡出现。 */
    data class Rejected(val cause: Throwable) : PairingRestoreOutcome

    /** 凭据还在，但这次连不上（core 没起 / 网络 / 证书）：桥退回 Unpaired(kept) → 卡片可重试。 */
    data class Unreachable(val cause: Throwable) : PairingRestoreOutcome
}

object PairingRuntime {

    /** 桥的唯一可变字段（守卫测试钉住「码不进任何字段」：类型里没有 String 码）。 */
    var state: PairingRuntimeState by mutableStateOf<PairingRuntimeState>(PairingRuntimeState.NotBooted)
        private set

    /** 已配对时的 factory；`null` = 未配对/未启动。 */
    val pairedFactory: VadeTransportFactory? get() = (state as? PairingRuntimeState.Paired)?.factory

    /** 已配对时的 `host:port`（文案用）；`null` = 未配对。 */
    val pairedEndpoint: String? get() = (state as? PairingRuntimeState.Paired)?.let { "${it.host}:${it.port}" }

    /** 启动恢复 / 「用已保存的配对重连」互斥：同一时刻只允许一次探测。 */
    private val restoreMutex = Mutex()

    /** 入口（[sessionSourceFromEnvironment]）调用：本次启动没有任何可用凭据 → 允许出卡。 */
    fun noteBooted() {
        if (state is PairingRuntimeState.NotBooted) state = PairingRuntimeState.Unpaired()
    }

    /**
     * 引导卡 / 换 core / 启动恢复成功后调用；compose state —— 读取它的组合（入口源构造、
     * 引导卡可见性）会跟着刷新。
     *
     * [trustMode] 是这次配对**实际用的信任档位**（P0-6）：调用方必须把
     * [PairingAttempt.Connected.trustMode] 原样传进来，不许再从 `tls` 推 —— 「走 https」
     * 与「接受任意证书」是两件事。
     *
     * [persist] · **生产调用点必须为 true**（配对成功 / 换 core / 启动恢复）：把
     * `{host, port, tls, trustMode}` + 这张 `__Host-ac_ui` 写进平台 KV，下次启动先用它探测。
     * 默认 `false` 是为了测试与「只装进内存」的旧路径不碰磁盘。
     */
    fun install(
        factory: VadeTransportFactory,
        host: String = SourceConfig.DEFAULT_HOST,
        port: Int = SourceConfig.DEFAULT_PORT,
        /** r5-tls · 这次配对实际用的协议（`https`/`wss` 时置真；默认 `false` = 明文）。 */
        tls: Boolean = false,
        /** P0-6 · 信任档位；`tls = false` 时恒按 SystemChain 记（明文没有证书可验）。 */
        trustMode: TlsTrustMode = TlsTrustMode.SystemChain,
        persist: Boolean = false,
    ) {
        val resolvedTrust = if (tls) trustMode else TlsTrustMode.SystemChain
        state = PairingRuntimeState.Paired(factory, host, port, tls, resolvedTrust)
        if (persist) {
            factory.sessionCookie?.let { cookie ->
                PairingStores.save(StoredPairing(host, port, tls, resolvedTrust, cookie))
            }
        }
    }

    /**
     * 一次配对码被 core 拒绝（错码/过期码，或启动参数里带的码失效）：桥回到「未配对 +
     * 码被拒过」，引导卡因此重新出现 —— 而不是把用户关在「一直重连」的黑箱里（H2/H3）。
     */
    fun noteCodeRejected() {
        if (state !is PairingRuntimeState.Paired) state = PairingRuntimeState.Unpaired(codeRejected = true)
    }

    /**
     * P0-2 · 持久化下来的那张 `__Host-ac_ui` 已经不被 core 认了（401 / 握手失败 / core
     * 重启后会话表清空）。
     *
     * 三件事必须同时发生，否则就是本报告里最严重的那条「僵尸重连」：
     *  1. **删掉磁盘上的凭据**（留着只会下次启动再演一遍）；
     *  2. 桥退回「未配对 + 凭据被拒」→ 引导卡重新出现（`needsFirstRunPairing` 为真）；
     *  3. 调用方（`RealSessionSource`）随桥状态重建源 —— 旧 client 的无限退避随之结束。
     *
     * 与 [noteCodeRejected] 分开：那个是「用户输的码不对」，这个是「上次的凭据失效」，
     * 卡片的解释与下一步不同。
     */
    fun noteCredentialRejected() {
        // 凭据已死：删磁盘 + 桥退回「已拒绝」。运行期（core 重启）也会走到这里：factory
        // 已经把本地 cookie 清掉了，桥再留着 Paired 只会让 UI 永远显示「重连中」。
        PairingStores.clear()
        state = PairingRuntimeState.Unpaired(credentialRejected = true)
    }

    /**
     * P0-1 · **启动恢复**：读回上次的凭据，`{host, port, tls, trustMode}` + cookie，
     * **先用这张 cookie 探测**（一次 WS 升级 + 一帧请求），成功了才进 [Paired]。
     *
     * 为什么不直接信任磁盘：cookie 可能已经失效（core 重启过、30 天到期、被别的端吊销）。
     * 直接进 Paired 会回到「一直重连、卡片永不出现」的老路；所以探测是入口唯一合法路径。
     *
     * 三种结局（[PairingRestoreOutcome]）：
     *  - [PairingRestoreOutcome.Restored]：桥进 Paired，App 据此重建真源；
     *  - [PairingRestoreOutcome.Rejected]：401 / 握手失败 → **删凭据** + 桥退回
     *    `Unpaired(credentialRejected)`，引导卡出现（不再僵尸重连）；
     *  - [PairingRestoreOutcome.Unreachable]：core 没起来 / 网络不通 / 证书变了 → 凭据
     *    **原样留着**（一张好 cookie 不该因为 core 晚启动而被删），桥退回
     *    `Unpaired(storedCredentialKept)`，卡片出现并带「用已保存的配对重连」。
     *
     * [raw] 的优先级：显式通道（`source=fixtures`，或带了 `pair_code` 的 real）优先，
     * 恢复不插手 —— 那是开发者/旧路径明确选择的连接方式。
     */
    suspend fun restoreStoredCredential(
        raw: RawSourceStrings = rawSourceStrings(),
        probeTimeoutMs: Long = PAIRING_PROBE_TIMEOUT_MS,
        transportFactory: (VadeClientConfig) -> VadeTransportFactory = { config -> KtorTransportFactory(config) },
    ): PairingRestoreOutcome = restoreMutex.withLock {
        val already = state as? PairingRuntimeState.Paired
        if (already != null) {
            return@withLock PairingRestoreOutcome.Restored(
                already.host,
                already.port,
                already.tls,
                already.trustMode,
            )
        }
        val explicit = runCatching { SourceConfig.parse(raw) }.getOrNull()
        if (explicit is SourceSpec.Real && explicit.pairCode != null) {
            return@withLock PairingRestoreOutcome.NoCredential
        }
        if (explicit is SourceSpec.Fake && !raw.source.isNullOrBlank()) {
            return@withLock PairingRestoreOutcome.NoCredential
        }
        probeStoredLocked(probeTimeoutMs, transportFactory)
    }

    /**
     * 「用已保存的配对重连」（引导卡上的按钮）：与启动恢复**同一条**探测路径，只是不做
     * 显式通道的判定 —— 用户刚刚亲手点了它。
     */
    suspend fun retryStoredCredential(
        probeTimeoutMs: Long = PAIRING_PROBE_TIMEOUT_MS,
        transportFactory: (VadeClientConfig) -> VadeTransportFactory = { config -> KtorTransportFactory(config) },
    ): PairingRestoreOutcome = restoreMutex.withLock {
        val already = state as? PairingRuntimeState.Paired
        if (already != null) {
            return@withLock PairingRestoreOutcome.Restored(
                already.host,
                already.port,
                already.tls,
                already.trustMode,
            )
        }
        probeStoredLocked(probeTimeoutMs, transportFactory)
    }

    private suspend fun probeStoredLocked(
        probeTimeoutMs: Long,
        transportFactory: (VadeClientConfig) -> VadeTransportFactory,
    ): PairingRestoreOutcome {
        val stored = PairingStores.load() ?: return PairingRestoreOutcome.NoCredential
        val config = VadeClientConfig(
            host = stored.host,
            port = stored.port,
            tls = stored.tls,
            // P0-6 · 信任档位是**记住的那一个**：持久化时只有「用户确认过的自签」才是
            // InsecureConfirmed；合法的 CA 证书恢复后照旧走 SystemChain。
            allowInsecureTls = stored.trustMode == TlsTrustMode.InsecureConfirmed,
            sessionCookie = SessionCookie(stored.cookie),
        )
        val factory = try {
            transportFactory(config)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return restoreFailure(t)
        }
        val probe = try {
            withTimeoutOrNull(probeTimeoutMs) { factory.connect() }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            runCatching { factory.dispose() }
            return restoreFailure(t)
        }
        if (probe == null) {
            runCatching { factory.dispose() }
            return credentialRejected("websocket upgrade did not answer within ${probeTimeoutMs}ms")
        }
        // 活性探针：core 若把这张 cookie 当「设备 peer」并在等 Noise 握手，升级会成功但
        // 一个字节都不回 —— 与 401 同义（凭据已死），而且正是僵尸重连的起点（P0-2）。
        val answered = withTimeoutOrNull(probeTimeoutMs) {
            runCatching { probe.send(PAIRING_PROBE_FRAME) }
            probe.incoming.first { frame -> frame.contains("\"jsonrpc\"") }
        }
        runCatching { probe.close() }
        if (answered == null) {
            runCatching { factory.dispose() }
            return credentialRejected("core accepted the socket but never answered within ${probeTimeoutMs}ms")
        }
        install(factory, stored.host, stored.port, stored.tls, stored.trustMode, persist = true)
        return PairingRestoreOutcome.Restored(stored.host, stored.port, stored.tls, stored.trustMode)
    }

    /** 非「凭据已死」的失败：凭据留着，桥退到「凭据还在但连不上」。 */
    private fun restoreFailure(t: Throwable): PairingRestoreOutcome {
        val dead = (t as? VadeException)?.code == VadeErrorCodes.AUTH_FAILED || isCredentialHandshakeFailure(t)
        if (dead) return credentialRejected(t.message ?: "the stored session cookie was rejected")
        state = PairingRuntimeState.Unpaired(storedCredentialKept = true)
        return PairingRestoreOutcome.Unreachable(t)
    }

    /** 凭据已死：删磁盘 + 桥退回「已拒绝」，引导卡出现。 */
    private fun credentialRejected(reason: String): PairingRestoreOutcome {
        PairingStores.clear()
        state = PairingRuntimeState.Unpaired(credentialRejected = true)
        return PairingRestoreOutcome.Rejected(VadeException(VadeErrorCodes.AUTH_FAILED, reason))
    }

    /**
     * 断开当前 core / 取消配对：先 **best-effort** 吊销旧 core 上这张 `__Host-ac_ui`
     * （`DELETE /ui/session`），再把桥清回「未配对」。
     *
     * 「best-effort」是契约的一部分：旧 core 已经下线、超时、拒绝都**不阻塞**断开 ——
     * 本地凭证该清就清。返回值让调用方决定要不要提示「旧凭证吊销失败」。
     *
     * 取消（[CancellationException]）不是失败：它原样向上抛，且**不清**任何本地配置 ——
     * 「点了取消结果 core 被清掉了」是不允许的状态。
     */
    suspend fun unpair(revokeTimeoutMs: Long = SESSION_REVOKE_TIMEOUT_MS): SessionRevocation {
        val previous = state as? PairingRuntimeState.Paired ?: return SessionRevocation.NotNeeded
        val revocation = revokeBestEffort(previous.factory, revokeTimeoutMs)
        clearPaired(previous)
        return revocation
    }

    /**
     * 换 core：**先拿到新 core 的 cookie，成功后才吊销旧会话并切换**。
     *
     * P1-6 / P0-4 的顺序（旧实现是「先吊销旧的、清本地，再去配新的」，新 core 一旦失败
     * 用户就停在未配对 —— 旧 cookie 已吊销、factory 已 dispose，没有任何回退）：
     *  1. 目标配置先解析：端口越界这类**本地**错误原地拒绝，一个请求都不发，旧配置原样保留；
     *  2. 用用户填的新地址 + 码走**同一条**连接链（[firstRunPairingAttempt]，恰好一次
     *     `POST /ui/session`）。**这一步失败 → 什么都不动**：旧 core 的会话没被吊销、
     *     旧 factory 没被 dispose、桥仍是 `Paired(old)` —— 用户还在原来的连接上；
     *  3. 只有新凭据到手之后，才向旧 core 发 `DELETE /ui/session`（best-effort，超时/失败
     *     不阻塞，结果回传 UI）；
     *  4. 一次性切换桥到新 core 并落盘；旧 factory [VadeTransportFactory.dispose]（
     *     `isUsable` 从此为 false —— 任何迟到的重连都不会把它复活，P0-4）。
     *
     * 取消（[CancellationException]）永远是「旧连接不动」：第 2 步取消 → 桥与本地配置一个
     * 字节都不变；第 3 步取消 → 桥仍是旧 core，但刚到手的新 factory 会被
     * [VadeTransportFactory.dispose]（既不进桥就没人会用它，留着只是泄漏）。一次性码在
     * 第 2 步就已被 core 消费，取消不会「退回」它 —— 本地配置错误仍然一个请求都不发。
     */
    suspend fun switchCore(
        raw: RawSourceStrings,
        enteredCode: String,
        revokeTimeoutMs: Long = SESSION_REVOKE_TIMEOUT_MS,
        /**
         * P0-6 · 用户在**自签证书警告**上确认过（`raw.tls` 为真时才生效）。
         * 默认 `false`：合法 CA 的正常 HTTPS 只走系统信任链，换 core 与首配同一条口径。
         *
         * 放在 `transportFactory` **之前**：后者必须保持最后一个参数，否则现有的
         * trailing-lambda 调用点会绑定到这个 Boolean 上（编译器会直接报类型不匹配）。
         */
        allowInsecureTls: Boolean = false,
        transportFactory: (VadeClientConfig) -> VadeTransportFactory = { config ->
            KtorTransportFactory(config)
        },
    ): CoreSwitchOutcome {
        // 1) 目标配置：本地就能判死的错误不许先动旧 core（也不许烧掉用户的一次性码）。
        runCatching { SourceConfig.parse(raw.source, raw.host, raw.port, enteredCode, raw.tls) }
            .getOrElse {
                return CoreSwitchOutcome(
                    SessionRevocation.NotNeeded,
                    PairingAttempt.Rejected(PairingFailureKind.BadConfig, it),
                )
            }
        // H4 · 本平台不能兑换码（浏览器读不到 Set-Cookie）：同样原地拒绝 —— 不许先把旧
        // core 的会话吊销掉再告诉用户「这里不能配对」。[firstRunPairingAttempt] 自己不发明
        // 网络请求，复用它同一条文案，不在这里抄第二份。
        if (!canBootstrapPairing()) {
            return CoreSwitchOutcome(
                SessionRevocation.NotNeeded,
                firstRunPairingAttempt(
                    raw, enteredCode,
                    allowInsecureTls = allowInsecureTls,
                    transportFactory = transportFactory,
                ),
            )
        }
        // 2) 先拿新 core 的 cookie。失败 = 回退：旧连接一个字节都不动。
        val attempt = firstRunPairingAttempt(
            raw, enteredCode,
            allowInsecureTls = allowInsecureTls,
            transportFactory = transportFactory,
        )
        if (attempt !is PairingAttempt.Connected) {
            return CoreSwitchOutcome(SessionRevocation.NotNeeded, attempt)
        }
        // 3) 新凭据到手，才吊销旧 core 的 UI 会话（best-effort：超时 / 失败不阻塞切换）。
        val previous = state as? PairingRuntimeState.Paired
        val revocation = try {
            revokeBestEffort(previous?.factory, revokeTimeoutMs)
        } catch (ce: CancellationException) {
            // 取消发生在这一步：桥还没切，旧连接原样保留（正确），但刚换到的新 factory
            // 既不进桥、也没有任何调用方持有 —— 必须放掉它的 HttpClient / cookie，
            // 否则每取消一次就漏一个连接池 + 一份仍然有效的服务端会话。
            if (previous?.factory !== attempt.factory) {
                runCatching { attempt.factory.dispose() }
            }
            throw ce
        }
        // 4) 一次性切换 + 新凭据落盘；旧 factory 退休。`dispose()` 之后
        //    `isUsable == false`，runner 见它直接终止 —— 旧 core 不会被重连复活。
        install(
            factory = attempt.factory,
            host = attempt.host,
            port = attempt.port,
            tls = attempt.tls,
            trustMode = attempt.trustMode,
            persist = true,
        )
        if (previous != null && previous.factory !== attempt.factory) {
            runCatching { previous.factory.dispose() }
        }
        return CoreSwitchOutcome(revocation, attempt)
    }

    /**
     * best-effort 吊销：**任何**失败（超时 / 网络 / 旧 core 拒绝）都收敛成
     * [SessionRevocation.Failed]；只有调用方的取消会穿出去（并且此时配置一个字节都还没动）。
     */
    private suspend fun revokeBestEffort(
        factory: VadeTransportFactory?,
        timeoutMs: Long,
    ): SessionRevocation {
        if (factory == null) return SessionRevocation.NotNeeded
        // `null` 只可能是**本函数自己的**超时：外层取消会以 CancellationException 抛出
        // （[revokeOnce] 原样穿透），不会被吞成一次「失败」。
        val attempt: SessionRevocation? = withTimeoutOrNull(timeoutMs) { revokeOnce(factory) }
        return attempt ?: SessionRevocation.Failed(SessionRevokeTimeoutException(timeoutMs))
    }

    /** 一次 `DELETE /ui/session`：成功 / 失败都收敛成 [SessionRevocation]，取消照样穿透。 */
    private suspend fun revokeOnce(factory: VadeTransportFactory): SessionRevocation =
        try {
            factory.revokeSession()
            SessionRevocation.Revoked
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            SessionRevocation.Failed(t)
        }

    /**
     * 清本地凭据：桥退回「未配对」，旧 factory 的资源顺手放掉，**磁盘上的凭据也删掉**
     * （P0-1：断开 = 用户不再使用这台 core，下次启动不该自己连回去）。
     *
     * 旧 factory 归桥所有（`RealSessionSource` 对**注入**的 factory 从不 dispose，见其
     * KDoc），换 core 之后不会再有引用 —— 这里关掉它的 `HttpClient`，不然每换一次 core
     * 就漏一个连接池。`dispose()` 同时把 `isUsable` 置假：任何迟到的重连都不会复活它。
     * dispose 失败不影响清配置。
     */
    private suspend fun clearPaired(previous: PairingRuntimeState.Paired?) {
        state = PairingRuntimeState.Unpaired()
        PairingStores.clear()
        previous?.let { runCatching { it.factory.dispose() } }
    }

    /**
     * 测试专用：把桥清回「未启动」（桥是全局单例，用例之间必须互不污染）。
     *
     * 同时把凭据存储换成**内存实现**：任何用例都不会读写开发者本机的真文件，也不会
     * 因为上一次用例写下的凭据而意外「恢复成功」。
     */
    internal fun clearForTests() {
        state = PairingRuntimeState.NotBooted
        PairingStores.resetForTests()
    }
}
