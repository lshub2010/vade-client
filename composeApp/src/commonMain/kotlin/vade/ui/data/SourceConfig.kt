package vade.ui.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import vade.ui.chat.ChatMessage
import vade.ui.protocol.BrowserPairing
import vade.ui.protocol.VadeClientConfig
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException

/**
 * g-live · the three-target real-data switch, in ONE place.
 *
 * Every entry point (desktop `Main.kt`, Android `MainActivity`, wasm `main.kt`) builds
 * its [SessionSource] through [sessionSourceFromEnvironment]; only the raw strings
 * differ per platform (`expect fun rawSourceStrings`):
 *
 * - desktop: `VADE_SOURCE` / `VADE_HOST` / `VADE_PORT` (+ `VADE_PAIR_CODE`) env vars;
 * - Android: `vade.source` / `vade.host` / `vade.port` (+ `vade.pair_code`) intent
 *   extras FIRST, the same env vars as fallback (installed by `MainActivity` into
 *   [AndroidSourceEnv] before composition);
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
            AutoConnectingSessionSource(RealSessionSource(config), spec.host, spec.port)
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
