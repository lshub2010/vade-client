package vade.ui.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The one exception type the Vade protocol layer throws (rpc.json §envelope).
 *
 * `code` is always a C-04 error **name** (34 declared values in `schema/errors.json`),
 * never a number and never a custom string — the transport carries names verbatim, and
 * [VadeErrorCodes] pins all 34 so a typo surfaces at compile time. `message` is core's
 * human-readable text (<=300 chars). `data` is the structured error payload (e.g. a
 * `field` pointer at the offending part) — never a stack trace.
 */
class VadeException(
    val code: String,
    message: String,
    val data: JsonObject? = null,
) : Exception(message.ifEmpty { code }) {

    /** `error.data.field` pointer, when core sent one (e.g. `content[0].text`). */
    val field: String?
        get() = data?.get("field")?.jsonPrimitive?.content

    /** `error.data.reason`, the common RFC-0005+ sub-reason key (e.g. `model_unavailable`). */
    val reason: String?
        get() = data?.get("reason")?.jsonPrimitive?.content

    /** `error.data.retry_after`, when core rate-limits with a hint (e.g. ADMIN_LOCKED). */
    val retryAfter: Double?
        get() = data?.get("retry_after")?.let {
            runCatching { it.jsonPrimitive.doubleOrNull ?: it.jsonPrimitive.longOrNull?.toDouble() }.getOrNull()
        }

    override fun toString(): String =
        "VadeException(code=$code, message=$message" +
            (data?.let { ", data=$it" } ?: "") +
            ")"

    companion object {
        /** The client-side request timeout, mapped onto C-04's own TIMEOUT name. */
        const val CLIENT_TIMEOUT_CODE: String = VadeErrorCodes.TIMEOUT

        /** Raised when the transport died before the reply arrived — mirrors core's wording. */
        const val TRANSPORT_CLOSED_CODE: String = VadeErrorCodes.UNAVAILABLE
    }
}

/**
 * Every C-04 error name (`schema/errors.json`, frozen, count pinned at 34 by
 * [vade.ui.protocol.VadeContractFramesTest]). Use these constants when *matching* an
 * error; when *raising* one that does not originate on the wire (client-side timeout),
 * [VadeException.CLIENT_TIMEOUT_CODE] / [VadeException.TRANSPORT_CLOSED_CODE] reuse
 * declared names instead of inventing one.
 */
object VadeErrorCodes {
    const val INVALID_ARGUMENT = "INVALID_ARGUMENT"
    const val NOT_FOUND = "NOT_FOUND"
    const val PERMISSION_DENIED = "PERMISSION_DENIED"
    const val TIMEOUT = "TIMEOUT"
    const val CANCELLED = "CANCELLED"
    const val UNAVAILABLE = "UNAVAILABLE"
    const val OVERLOADED = "OVERLOADED"
    const val MEMORY_FULL = "MEMORY_FULL"
    const val PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE"
    const val UNSUPPORTED = "UNSUPPORTED"
    const val PROTOCOL_MISMATCH = "PROTOCOL_MISMATCH"
    const val MANIFEST_INVALID = "MANIFEST_INVALID"
    const val TOOL_CONFLICT = "TOOL_CONFLICT"
    const val RISK_DOWNGRADE_FORBIDDEN = "RISK_DOWNGRADE_FORBIDDEN"
    const val APPROVER_INVALID = "APPROVER_INVALID"
    const val ORIGIN_MISMATCH = "ORIGIN_MISMATCH"
    const val READ_ONLY_SESSION = "READ_ONLY_SESSION"
    const val BIND_UNAUTHORIZED = "BIND_UNAUTHORIZED"
    const val BIND_FINGERPRINT_MISMATCH = "BIND_FINGERPRINT_MISMATCH"
    const val BIND_HELLO_SIG_INVALID = "BIND_HELLO_SIG_INVALID"
    const val BIND_DEVICE_LIMIT = "BIND_DEVICE_LIMIT"
    const val BIND_CLOCK_SKEW = "BIND_CLOCK_SKEW"
    const val BIND_KEY_UNSUPPORTED = "BIND_KEY_UNSUPPORTED"
    const val BIND_SETUP_LOCKED = "BIND_SETUP_LOCKED"
    const val AUTH_FAILED = "AUTH_FAILED"
    const val ADMIN_LOCKED = "ADMIN_LOCKED"
    const val SETUP_UNAUTHORIZED = "SETUP_UNAUTHORIZED"
    const val MODULE_DRAINING = "MODULE_DRAINING"
    const val INTERNAL = "INTERNAL"
    const val CONFIRM_REQUIRED = "CONFIRM_REQUIRED"
    const val UNKNOWN_CONFIG_KEY = "UNKNOWN_CONFIG_KEY"
    const val CODE_INVALID = "CODE_INVALID"
    const val CODE_EXPIRED = "CODE_EXPIRED"
    const val BUDGET_EXCEEDED = "BUDGET_EXCEEDED"

    /** All 34 declared names, in errors.json order — the count assertion walks this list. */
    val ALL: List<String> = listOf(
        INVALID_ARGUMENT, NOT_FOUND, PERMISSION_DENIED, TIMEOUT, CANCELLED, UNAVAILABLE,
        OVERLOADED, MEMORY_FULL, PAYLOAD_TOO_LARGE, UNSUPPORTED, PROTOCOL_MISMATCH,
        MANIFEST_INVALID, TOOL_CONFLICT, RISK_DOWNGRADE_FORBIDDEN, APPROVER_INVALID,
        ORIGIN_MISMATCH, READ_ONLY_SESSION, BIND_UNAUTHORIZED, BIND_FINGERPRINT_MISMATCH,
        BIND_HELLO_SIG_INVALID, BIND_DEVICE_LIMIT, BIND_CLOCK_SKEW, BIND_KEY_UNSUPPORTED,
        BIND_SETUP_LOCKED, AUTH_FAILED, ADMIN_LOCKED, SETUP_UNAUTHORIZED, MODULE_DRAINING,
        INTERNAL, CONFIRM_REQUIRED, UNKNOWN_CONFIG_KEY, CODE_INVALID, CODE_EXPIRED,
        BUDGET_EXCEEDED,
    )
}
