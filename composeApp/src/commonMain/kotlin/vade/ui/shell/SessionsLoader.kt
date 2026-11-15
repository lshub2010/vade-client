package vade.ui.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import vade.ui.chat.ChatMessage
import kotlin.concurrent.Volatile
import vade.ui.data.SessionAttachedEvent
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
 * in [SessionsLoader.sessionsError] / [SessionsLoader.messagesError]. A failure is
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
 * [SessionSource] — the initial `sessions()` pull, the per-selection `messages(id)`
 * pull with stale-request invalidation, the event-driven refresh, and the three-state
 * Loading / Ready / Failed bookkeeping with classified errors and retry entries.
 *
 * ## Three states
 * Both loads run the same loop: start (Loading unless data from a previous success is
 * already on screen — stale-while-revalidate for event refreshes), fetch, then either
 * Ready (rows swapped atomically) or Failed (previous rows KEPT, error classified via
 * `loadErrorCategoryOf`, retry entry = [refreshSessions] / [retryMessages]). `Failed`
 * is a distinct observable state; it is never laundered into an empty `Ready`.
 *
 * ## Stale request invalidation (messages)
 * Every `selectSession` bumps an [AtomicLong] generation and captures it; a response
 * (or failure) from an older generation is dropped before any state write, so switching
 * sessions fast can never let the OLD session's late answer overwrite the NEW one.
 * Re-selecting the session that is already loaded/loading is a no-op (no duplicate
 * fetch); re-selecting a Failed one refetches (that is the retry path).
 *
 * ## Events
 * `observeEvents()` is collected once; handled events trigger a coalesced list refresh
 * (a trailing-edge flag collapses an event storm arriving mid-flight into ONE extra
 * fetch). Unhandled / unknown event subtypes are explicitly ignored — never a crash.
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

    /**
     * Phase of the latest `messages(sessionId)` attempt for [messagesSessionId]. With
     * no session selected yet this is vacuously Ready with an empty thread — nothing
     * was requested, so there is nothing loading and nothing failed.
     */
    var messagesPhase: LoadPhase by mutableStateOf(LoadPhase.Ready)
        private set

    /** Classified failure of the latest `messages()` attempt; `null` while Ready. */
    var messagesError: LoadError? by mutableStateOf<LoadError?>(null)
        private set

    /** Which session the [messages] list belongs to (set BEFORE the fetch starts). */
    var messagesSessionId: String? by mutableStateOf<String?>(null)
        private set

    /** The selected session's thread; swapped atomically on success, KEPT on failure. */
    var messages: List<ChatMessage> by mutableStateOf<List<ChatMessage>>(emptyList())
        private set

    @Volatile
    private var disposed = false

    /**
     * Generation counter for stale-request invalidation. Bumped and captured in the
     * same thread that issues the fetch (the UI thread / the sequential test thread),
     * read back on the loader thread only for the drop check — a plain `@Volatile`
     * Long is sufficient; there is no cross-thread `++` (wasm has no JVM AtomicLong,
     * and adding one would be ceremony around a single-threaded mutation).
     */
    @Volatile
    private var messagesGen = 0L

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
            source.observeEvents().collect { onEvent(it) }
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

    // ------------------------------------------------------------------ messages

    /**
     * Selects [sessionId] and loads its thread. `isDraft` rows have no server thread —
     * nothing to fetch, the phase is vacuously Ready with an empty thread. Already
     * loaded / loading sessions are not refetched; a Failed one is (retry). Any result
     * from an older generation is dropped before it can touch the state.
     */
    fun selectSession(sessionId: String, isDraft: Boolean = false) {
        if (disposed) return
        if (isDraft) {
            messagesGen += 1 // invalidate anything still in flight
            messagesSessionId = sessionId
            messages = emptyList()
            messagesPhase = LoadPhase.Ready
            messagesError = null
            return
        }
        if (sessionId == messagesSessionId && messagesPhase != LoadPhase.Failed) return
        messagesGen += 1
        val gen = messagesGen
        messagesSessionId = sessionId
        messagesPhase = LoadPhase.Loading
        messagesError = null
        launchJob {
            try {
                val fetched = source.messages(sessionId)
                if (disposed || gen != messagesGen) return@launchJob // stale: drop silently
                messages = fetched
                messagesPhase = LoadPhase.Ready
            } catch (e: CancellationException) {
                return@launchJob
            } catch (t: Throwable) {
                if (disposed || gen != messagesGen) return@launchJob
                messagesPhase = LoadPhase.Failed
                messagesError = loadErrorOf(t)
            }
        }
    }

    /** Retry entry for a Failed message thread load (no-op when nothing failed). */
    fun retryMessages() {
        val id = messagesSessionId ?: return
        selectSession(id)
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
