package vade.ui.data

import vade.ui.protocol.VadeErrorCodes

/**
 * The four buckets a UI can act on (c-data §4). One C-04 code maps to exactly one
 * bucket; the exhaustive table lives in [VadeErrorMapping.categories] and is pinned by
 * `VadeErrorMappingTest` — a code added to `schema/errors.json` without an entry here
 * turns that test red.
 */
enum class VadeErrorCategory(val label: String) {
    /** Transient (timeout / rate limit / draining): retrying the same op is the right move. */
    Retryable("可重试"),

    /**
     * Connection or session sync state is gone (transport died, stale view, re-pair
     * needed): reconnect + resync, automatically where possible.
     */
    NeedsReconnect("需要重连"),

    /** Input / permission / quota shape: the user must change something first. */
    NeedsUserAction("需要用户操作"),

    /** Version mismatch or broken security state: neither retry nor reconnect helps. */
    Fatal("致命"),
}

/**
 * C-04 error names → UI buckets. One file, one table, testable in isolation; the UI
 * (later batch) calls [categoryOf] and renders [VadeErrorCategory.label].
 *
 * Classification rationale (data-plane reading of `schema/errors.json`):
 * - Retryable: the request was well-formed and the refusal is about capacity/time.
 * - NeedsReconnect: the local view of the world is stale or the socket/session must be
 *   re-established; a resync (reconnect / re-attach / re-pair) is the fix.
 * - NeedsUserAction: the caller can succeed only with different input, permission,
 *   quota, or a user decision (approve / confirm / re-enter code / clear memory).
 * - Fatal: client/core version mismatch or a broken security state — no in-app
 *   recovery path exists.
 */
object VadeErrorMapping {

    /** Every declared C-04 code → its UI bucket. Keys must equal [VadeErrorCodes.ALL] (tested). */
    val categories: Map<String, VadeErrorCategory> = mapOf(
        // --- Retryable -------------------------------------------------------
        VadeErrorCodes.TIMEOUT to VadeErrorCategory.Retryable,
        VadeErrorCodes.CANCELLED to VadeErrorCategory.Retryable,
        VadeErrorCodes.OVERLOADED to VadeErrorCategory.Retryable,
        VadeErrorCodes.ADMIN_LOCKED to VadeErrorCategory.Retryable,
        VadeErrorCodes.MODULE_DRAINING to VadeErrorCategory.Retryable,

        // --- NeedsReconnect --------------------------------------------------
        // UNAVAILABLE is also the client-side "transport died before the reply"
        // code (VadeException.TRANSPORT_CLOSED_CODE) — reconnect fits it exactly.
        VadeErrorCodes.UNAVAILABLE to VadeErrorCategory.NeedsReconnect,
        VadeErrorCodes.AUTH_FAILED to VadeErrorCategory.NeedsReconnect,
        VadeErrorCodes.NOT_FOUND to VadeErrorCategory.NeedsReconnect,
        VadeErrorCodes.INTERNAL to VadeErrorCategory.NeedsReconnect,
        VadeErrorCodes.BIND_UNAUTHORIZED to VadeErrorCategory.NeedsReconnect,

        // --- NeedsUserAction -------------------------------------------------
        VadeErrorCodes.INVALID_ARGUMENT to VadeErrorCategory.NeedsUserAction,
        VadeErrorCodes.PERMISSION_DENIED to VadeErrorCategory.NeedsUserAction,
        VadeErrorCodes.MEMORY_FULL to VadeErrorCategory.NeedsUserAction,
        VadeErrorCodes.PAYLOAD_TOO_LARGE to VadeErrorCategory.NeedsUserAction,
        VadeErrorCodes.UNSUPPORTED to VadeErrorCategory.NeedsUserAction,
        VadeErrorCodes.TOOL_CONFLICT to VadeErrorCategory.NeedsUserAction,
        VadeErrorCodes.RISK_DOWNGRADE_FORBIDDEN to VadeErrorCategory.NeedsUserAction,
        VadeErrorCodes.APPROVER_INVALID to VadeErrorCategory.NeedsUserAction,
        VadeErrorCodes.READ_ONLY_SESSION to VadeErrorCategory.NeedsUserAction,
        VadeErrorCodes.BIND_DEVICE_LIMIT to VadeErrorCategory.NeedsUserAction,
        VadeErrorCodes.BIND_CLOCK_SKEW to VadeErrorCategory.NeedsUserAction,
        VadeErrorCodes.BIND_SETUP_LOCKED to VadeErrorCategory.NeedsUserAction,
        VadeErrorCodes.CONFIRM_REQUIRED to VadeErrorCategory.NeedsUserAction,
        VadeErrorCodes.UNKNOWN_CONFIG_KEY to VadeErrorCategory.NeedsUserAction,
        VadeErrorCodes.CODE_INVALID to VadeErrorCategory.NeedsUserAction,
        VadeErrorCodes.CODE_EXPIRED to VadeErrorCategory.NeedsUserAction,
        VadeErrorCodes.BUDGET_EXCEEDED to VadeErrorCategory.NeedsUserAction,

        // --- Fatal -----------------------------------------------------------
        VadeErrorCodes.PROTOCOL_MISMATCH to VadeErrorCategory.Fatal,
        VadeErrorCodes.MANIFEST_INVALID to VadeErrorCategory.Fatal,
        VadeErrorCodes.ORIGIN_MISMATCH to VadeErrorCategory.Fatal,
        VadeErrorCodes.SETUP_UNAUTHORIZED to VadeErrorCategory.Fatal,
        VadeErrorCodes.BIND_FINGERPRINT_MISMATCH to VadeErrorCategory.Fatal,
        VadeErrorCodes.BIND_HELLO_SIG_INVALID to VadeErrorCategory.Fatal,
        VadeErrorCodes.BIND_KEY_UNSUPPORTED to VadeErrorCategory.Fatal,
    )

    /**
     * Category for [code]. A name that is NOT among the frozen 34 (a future core
     * introduced one) falls back to [VadeErrorCategory.Retryable]: the gentlest UI
     * default — surface the message with a retry affordance, never crash.
     */
    fun categoryOf(code: String): VadeErrorCategory =
        categories[code] ?: VadeErrorCategory.Retryable
}
