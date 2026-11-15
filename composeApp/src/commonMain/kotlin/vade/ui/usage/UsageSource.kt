package vade.ui.usage

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import vade.ui.data.VadeErrorCategory
import vade.ui.data.loadErrorCategoryOf
import vade.ui.data.loadErrorCodeOf
import vade.ui.protocol.SessionUsageGetParams
import vade.ui.protocol.SessionUsageGetResult
import vade.ui.protocol.VadeClient
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException
import kotlin.concurrent.Volatile

/**
 * G · the narrow seam between the 用量 page and `session/usage.get` (RFC-0005).
 *
 * Before this file the panel was a pure renderer: `ActivityScreen` handed it a
 * `SessionUsageGetResult?` that nobody ever fetched, so the 用量 section could only ever
 * show「暂无数据」. This interface is the one missing hop — one suspend read that returns
 * either the decoded contract DTO or a [VadeException] with its C-04 code intact.
 *
 * The seam returns the **whole DTO**, never a flattened row list: the projection in
 * `UsageModel.kt` is the single place that turns contract fields into display cells, so
 * `total_tokens = chat + aux` stays the server's number and is never re-derived here.
 */
interface UsageSource {
    /**
     * `session/usage.get` for one session. Throws on every failure — an empty/zero-filled
     * [SessionUsageGetResult] is NEVER returned as a stand-in for a failed read.
     */
    suspend fun load(sessionId: String): SessionUsageGetResult
}

/**
 * The real [UsageSource]: one typed RPC against [VadeClient] (the same client
 * `RealSessionSource` drives). Pure adapter — owns no connection and no state; the
 * caller injects the shared client.
 *
 * ## Frame discipline
 * A blank `session_id` is refused BEFORE any frame is built (zero-frame tests), and the
 * three required-field guarantees of the contract are re-checked on the reply:
 *  1. `session_id` must echo the requested id (it is a required result field);
 *  2. `total_tokens` must be present (required field);
 *  3. `total_tokens` must equal `chat.total_tokens + aux.total_tokens` — the contract's
 *     own arithmetic (`total_tokens` = chat + aux, schema/rpc.json notes). A reply that
 *     contradicts it is a broken ledger, so it fails loudly as PROTOCOL_MISMATCH instead
 *     of painting wrong numbers on the page.
 *
 * Every other failure propagates as the [VadeException] the protocol seam raised; the
 * UI classifies it with [loadErrorCategoryOf] (C-04 buckets) — no swallowing here.
 */
class UsageSourceReal(private val client: VadeClient) : UsageSource {

    override suspend fun load(sessionId: String): SessionUsageGetResult {
        if (sessionId.isBlank()) {
            throw VadeException(
                VadeErrorCodes.INVALID_ARGUMENT,
                "params.session_id is required and must be non-blank",
            )
        }
        val result = client.sessionUsageGet(SessionUsageGetParams(sessionId))

        if (result.sessionId != sessionId) {
            throw VadeException(
                VadeErrorCodes.PROTOCOL_MISMATCH,
                "session/usage.get echoed session_id=\"${result.sessionId}\" for requested \"$sessionId\"",
            )
        }
        val total = result.totalTokens
            ?: throw VadeException(
                VadeErrorCodes.PROTOCOL_MISMATCH,
                "session/usage.get result is missing required field total_tokens",
            )
        val sum = (result.chat?.totalTokens ?: 0L) + (result.aux?.totalTokens ?: 0L)
        if (total != sum) {
            throw VadeException(
                VadeErrorCodes.PROTOCOL_MISMATCH,
                "session/usage.get total_tokens=$total contradicts chat+aux=$sum " +
                    "(chat=${result.chat?.totalTokens ?: 0}, aux=${result.aux?.totalTokens ?: 0})",
            )
        }
        return result
    }
}

// ----------------------------------------------------------------- load state

/** The mandated three states of the 用量 read. There is no fourth, silent "empty" state. */
sealed interface UsageLoadState {
    /** A `session/usage.get` is in flight (also the pre-first-request phase of the panel). */
    data object Loading : UsageLoadState

    /** The contract reply is on screen. */
    data class Ready(val usage: SessionUsageGetResult) : UsageLoadState

    /** The read failed; [error] carries the C-04 bucket and the retry affordance text. */
    data class Failed(val error: UsageLoadError) : UsageLoadState
}

/** One failed usage read, classified for the UI. Never converted into an empty Ready. */
data class UsageLoadError(
    /** The C-04 UI bucket (可重试 / 需要重连 / 需要用户操作 / 致命) — the banner leads with it. */
    val category: VadeErrorCategory,
    /** The C-04 code when the failure came through the protocol seam, else `null`. */
    val code: String?,
    /** The transport's own words, shown after the category label. */
    val detail: String,
) {
    /** 分类文案 + code + 详情 — one line, always carries the category word. */
    val label: String
        get() = if (code.isNullOrBlank()) "${category.label}：$detail" else "${category.label}（$code）：$detail"

    /** What the retry button promises for this bucket. */
    val retryLabel: String
        get() = when (category) {
            VadeErrorCategory.Retryable -> "重试"
            VadeErrorCategory.NeedsReconnect -> "重连后重试"
            VadeErrorCategory.NeedsUserAction -> "修正后重试"
            VadeErrorCategory.Fatal -> "重试（通常无效）"
        }

    /** Why the failure happened and what the user can do — never a bare stack trace. */
    val hint: String
        get() = when (category) {
            VadeErrorCategory.Retryable ->
                "请求超时或服务繁忙；重试会重新拉取 session/usage.get，已显示的数字不会残留。"
            VadeErrorCategory.NeedsReconnect ->
                "连接已断开或会话视图已失效；请恢复连接（重连 / 重新 attach）后再重试。"
            VadeErrorCategory.NeedsUserAction ->
                "参数或权限被拒绝；请按提示修正后再试（同一请求原样重试通常无效）。"
            VadeErrorCategory.Fatal ->
                "客户端与服务端版本或安全状态不一致；更新客户端或重新配对后才能恢复。"
        }
}

internal fun usageLoadErrorOf(t: Throwable): UsageLoadError = UsageLoadError(
    category = loadErrorCategoryOf(t),
    code = loadErrorCodeOf(t),
    detail = t.message ?: t::class.simpleName ?: "unknown error",
)

/** Short state word for the panel header / tests: 加载中 / 已加载 / 加载失败. */
fun usageStateLabel(state: UsageLoadState): String = when (state) {
    UsageLoadState.Loading -> "加载中"
    is UsageLoadState.Ready -> "已加载"
    is UsageLoadState.Failed -> "加载失败"
}

// ----------------------------------------------------------------- loader

/**
 * Drives one `session/usage.get` at a time with the same discipline as `SessionsLoader`:
 *  - **three states**, an outcome is always Loading → Ready or Loading → Failed;
 *  - **Failed is observable**, never laundered into an empty Ready;
 *  - **generation guard**: [load] bumps a counter and captures it; a reply (or failure)
 *    from an older generation is dropped before any state write, so switching sessions
 *    fast can never let the OLD session's late usage overwrite the NEW one;
 *  - [retry] re-issues the last `session_id` — the retry entry the Failed card renders;
 *  - [dispose] stops all writes; a late reply after dispose changes nothing.
 *
 * In-flight requests are NOT cancelled when a new one starts: the stale reply is dropped
 * by generation instead (cancelling would hide the late-arrival race the guard exists for).
 */
class UsageLoader(
    private val source: UsageSource,
    private val scope: CoroutineScope,
) {
    /** The latest attempt's state. Starts [UsageLoadState.Loading]: nothing has answered yet. */
    var state: UsageLoadState by mutableStateOf<UsageLoadState>(UsageLoadState.Loading)
        private set

    /** The session the current state belongs to (`null` before the first [load]). */
    var sessionId: String? by mutableStateOf<String?>(null)
        private set

    /**
     * Generation of the newest attempt. Bumped on the caller thread, read back on the
     * loader thread only for the drop check — a plain `@Volatile` Long is enough (no
     * cross-thread `++`, and wasm has no JVM AtomicLong).
     */
    @Volatile
    private var generation = 0L

    @Volatile
    private var disposed = false

    /** (Re)loads [sessionId]. A blank id fails WITHOUT any frame ever being sent. */
    fun load(sessionId: String) {
        if (disposed) return
        generation += 1
        val gen = generation
        this.sessionId = sessionId
        if (sessionId.isBlank()) {
            state = UsageLoadState.Failed(
                UsageLoadError(
                    category = VadeErrorCategory.NeedsUserAction,
                    code = VadeErrorCodes.INVALID_ARGUMENT,
                    detail = "session_id 为空，未发起 session/usage.get",
                ),
            )
            return
        }
        state = UsageLoadState.Loading
        scope.launch {
            val next = try {
                UsageLoadState.Ready(source.load(sessionId))
            } catch (e: CancellationException) {
                // Same split as ModelState.refresh: a timeout-shaped cancellation (the request
                // died, our scope did not) is a retryable failure — rethrowing it used to leave
                // the card in Loading forever; our own scope dying writes nothing.
                currentCoroutineContext().ensureActive()
                UsageLoadState.Failed(usageLoadErrorOf(e))
            } catch (t: Throwable) {
                UsageLoadState.Failed(usageLoadErrorOf(t))
            }
            if (disposed || gen != generation) return@launch // stale: drop silently
            state = next
        }
    }

    /** The Failed card's retry entry: re-issue the last session_id (no-op before any load). */
    fun retry() {
        val id = sessionId ?: return
        load(id)
    }

    /** Stops all further writes; idempotent. */
    fun dispose() {
        disposed = true
    }
}
