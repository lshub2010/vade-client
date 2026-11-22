package vade.ui.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import vade.ui.data.SessionAttachedEvent
import vade.ui.data.SessionListChangedEvent
import vade.ui.data.SessionSource
import vade.ui.data.SessionSummary
import vade.ui.data.TurnCompletedEvent
import vade.ui.data.TurnDeltaEvent
import vade.ui.data.TurnFailedEvent
import vade.ui.data.TurnQueuedEvent
import vade.ui.data.TurnStartedEvent
import vade.ui.data.VadeErrorCategory
import vade.ui.data.VadeEvent
import vade.ui.data.loadErrorCategoryOf
import vade.ui.data.loadErrorCodeOf

/**
 * Three-state phase of one async read from a [SessionSource] (w-wire). `Loading` = a
 * fetch is in flight; `Ready` = the latest finished attempt succeeded — possibly with
 * zero rows, because an empty list is a real answer and must never be confused with a
 * failure; `Failed` = the latest finished attempt threw and the classified error rides
 * in [SessionsLoader.sessionsError] (the message thread's three states live on
 * `ChatState.phase` / `loadError`). A failure is
 * NEVER reported as a silent empty list.
 */
enum class LoadPhase { Loading, Ready, Failed }

/** One failed source read, classified for the UI (c-data §4 buckets). */
data class LoadError(
    /** The UI bucket (可重试 / 需要重连 / 需要用户操作 / 致命) — what the banner leads with. */
    val category: VadeErrorCategory,
    /** C-04 code when the failure came through the protocol seam, else `null`. */
    val code: String?,
    /** The transport's own words (exception message / class name) shown after the label. */
    val detail: String,
) {
    /** Single-line banner text: 分类文案 + 详情. */
    val label: String get() = "${category.label}：$detail"
}

/**
 * A4-1 · `internal`（原 file-private）：`UiState.newSession()` 的真源失败也要走**同一条**
 * 分类路径 —— 分类逻辑全仓只有这一份，不许 UiState 再抄一份漂移。
 */
internal fun loadErrorOf(t: Throwable): LoadError =
    LoadError(
        category = loadErrorCategoryOf(t),
        code = loadErrorCodeOf(t),
        detail = t.message ?: t::class.simpleName ?: "unknown error",
    )

/**
 * w-wire · the last pipe segment: everything `UiState` needs to actually READ its
 * [SessionSource] — the initial `sessions()` pull, the event-driven list refresh, and
 * the three-state Loading / Ready / Failed bookkeeping with classified errors and a
 * retry entry.
 *
 * ## 为什么这里**不读消息**
 *
 * `SessionSource.messages(id)` 在真源上是 `ui/attach` —— **一次 attach 就是一次订阅**
 * （core 为同一会话再注册一个 sink ⇒ 同连接双 sink、事件双投递）。选会话时读线程的
 * 职责因此只有一个主人：[ChatState.loadHistory]（由 `ChatThread` 的
 * `LaunchedEffect(state)` 驱动），它的读通道与事件流是同一次 attach 的两面。
 *
 * 旧实现让 loader 也拉一遍 `messages(id)`，于是每选一次会话 attach 两次，并多出一条
 * 「该会话的消息加载失败」横幅 —— 与 `ChatState.phase` 的失败态重复。那条读和那条横幅
 * 都已删除；消息的加载中 / 空 / 失败三态只由 ChatState 一处驱动。
 *
 * ## Three states
 * The list read: start (Loading unless data from a previous success is already on
 * screen — stale-while-revalidate for event refreshes), fetch, then either Ready (rows
 * swapped atomically) or Failed (previous rows KEPT, error classified via
 * `loadErrorCategoryOf`, retry entry = [refreshSessions]). `Failed` is a distinct
 * observable state; it is never laundered into an empty `Ready`.
 *
 * ## Events
 * `observeListEvents()` is collected once: it is the UNFILTERED list-level seam, so
 * handled events from any session (including while no chat session is visible) trigger
 * a coalesced list refresh (a trailing-edge flag collapses an event storm arriving
 * mid-flight into ONE extra fetch). Unhandled / unknown event subtypes are explicitly
 * ignored — never a crash. `observeEvents()` is deliberately NOT used here: it is
 * gated to the visible chat session (P1-3).
 *
 * ## Disposal
 * [dispose] stops accepting work and cancels every launched job; a result that arrives
 * after dispose writes nothing (the disposed flag is checked before each write, and the
 * cancelled job surfaces the fetch as a `CancellationException` which is never mapped
 * onto a Failed state).
 */
class SessionsLoader(
    private val source: SessionSource,
    private val scope: CoroutineScope,
) {
    /** Phase of the latest `sessions()` attempt (see [LoadPhase] for the exact meaning). */
    var sessionsPhase: LoadPhase by mutableStateOf(LoadPhase.Ready)
        private set

    /** Classified failure of the latest `sessions()` attempt; `null` while Ready. */
    var sessionsError: LoadError? by mutableStateOf<LoadError?>(null)
        private set

    /** Rows the source answered with; swapped atomically on success, KEPT on failure. */
    var rows: List<SessionSummary> by mutableStateOf<List<SessionSummary>>(emptyList())
        private set

    @Volatile
    private var disposed = false

    @Volatile
    private var sessionsFlight = false

    @Volatile
    private var sessionsDirty = false

    private val jobs = mutableListOf<Job>()

    /**
     * Starts the initial sessions pull and the event subscription. Called once from
     * `UiState`'s init; the fixture path never calls this (no coroutines exist there).
     */
    fun start() {
        refreshSessions()
        launchJob {
            // P1-3 · 列表刷新与聊天投影是两个可见性规则不同的消费者：这里收**全量**
            // 事件（列表根 attachedSessionId==null 时 session.* 也要到），不能用
            // observeEvents()（它只交付当前可见会话）。
            source.observeListEvents().collect { onEvent(it) }
        }
    }

    /**
     * Stops the loader: no further fetches start, in-flight results are dropped, no
     * further state writes happen. Idempotent.
     */
    fun dispose() {
        disposed = true
        // Cancel a snapshot — the disposed flag is the authoritative guard before any
        // state write, so a job slipping past this list cannot corrupt anything.
        jobs.toList().forEach { it.cancel() }
        jobs.clear()
    }

    // ------------------------------------------------------------------ sessions

    /**
     * (Re)pulls the session list — the initial load, the retry entry after a failure,
     * and the response to a handled event all funnel through here. Concurrent requests
     * coalesce: one fetch in flight + N events arriving = exactly one trailing fetch.
     */
    fun refreshSessions() {
        if (disposed) return
        if (sessionsFlight) {
            sessionsDirty = true
            return
        }
        sessionsFlight = true
        // First load / reload of an empty list shows Loading honestly; a refresh with
        // rows already on screen keeps them visible (stale-while-revalidate) — either
        // way the OUTCOME is Ready or Failed, never a silent empty list.
        if (rows.isEmpty()) sessionsPhase = LoadPhase.Loading
        sessionsError = null
        launchJob {
            try {
                val fetched = source.sessions()
                if (disposed) return@launchJob
                rows = fetched
                sessionsPhase = LoadPhase.Ready
            } catch (e: CancellationException) {
                return@launchJob // disposed/cancelled — no state write, never a fake failure
            } catch (t: Throwable) {
                if (disposed) return@launchJob
                sessionsPhase = LoadPhase.Failed
                sessionsError = loadErrorOf(t)
            } finally {
                sessionsFlight = false
                if (!disposed && sessionsDirty) {
                    sessionsDirty = false
                    refreshSessions() // trailing-edge: the coalesced event refresh
                }
            }
        }
    }

    // ------------------------------------------------------------------ events

    /**
     * The data layer projects six core events (`RealSessionEvents.kt`); there is no
     * `session.updated` / `session.list_changed` subtype yet. Attach + the turn
     * lifecycle events mean session-level truth moved (updated_at / unread), so they
     * trigger the coalesced list refresh; `turn.delta` is per-character streaming and
     * must NOT re-pull the list per delta; anything else (future subtypes) is
     * explicitly ignored — never a crash.
     */
    private fun onEvent(event: VadeEvent) {
        when (event) {
            is SessionAttachedEvent -> refreshSessions()
            is TurnQueuedEvent -> refreshSessions()
            is TurnStartedEvent -> refreshSessions()
            is TurnCompletedEvent -> refreshSessions()
            is TurnFailedEvent -> refreshSessions()
            // P1-3 · 契约指定的列表失效边：别的会话被建/被删（另一台设备、ui/attach
            // 自动建会话）时也要重拉，不能只靠 turn.* 的副作用。
            is SessionListChangedEvent -> refreshSessions()
            is TurnDeltaEvent -> {
                // Streaming text increments change no list row — explicit ignore.
            }
            else -> {
                // Unknown / unhandled event subtype — explicit ignore, never a crash.
            }
        }
    }

    // ------------------------------------------------------------------ internals

    private fun launchJob(block: suspend CoroutineScope.() -> Unit) {
        if (disposed) return
        val job = scope.launch(block = block)
        jobs += job
    }
}
