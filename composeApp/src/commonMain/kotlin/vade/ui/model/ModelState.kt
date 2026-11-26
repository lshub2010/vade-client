// Model picker state — the decision layer between the seam and the drawing.
//
// Same discipline as ChatState / SearchState: pure transitions, snapshot-state fields, no
// window and no clock, so `ModelStateTest` / `ModelRealWiringTest` can drive every acceptance
// item directly.
// The rules the tests pin down:
//  1. selection is a single id — two rows can never both be "selected";
//  2. writes carry the protocol `model_id`, never the display name;
//  3. re-selecting the already-selected model is a no-op that sends NO request;
//  4. a failed write REVERTS the selection and becomes a displayable [ModelFailure];
//  5. the FR-8.46 read-only degrade is visible (`readOnly` + [readOnlyReason]) and `models`
//     stays `null` — never an empty list, which would render as the wrong 「无可用模型」 copy.
//
// ## Two wirings, one state
// [forSession] / [forDefault] take a [ModelActions] seam object — synchronous, cannot fail,
// used by the fixture and by every state test — and RECOGNIZE a real `ModelActionsReal`
// handed in by an adapter bundle (`AdapterProvider.model/modelDefault`), taking the real path
// for it instead of the seam path. [forRealSession] / [forRealDefault] build that adapter from
// a connected `VadeClient`, so a screen's wiring is one line. All four go through one
// [ModelState.Companion.wired] so there is exactly one place where "real" is decided.
// The differences are deliberate and visible below:
//  - the real path MIRRORS the adapter's pulled values into snapshot state (`models`,
//    `readOnly`, `defaultModelId`) so the badge/list actually recompose after a pull — the
//    adapter's plain vals are backed by StateFlows and would not trigger recomposition;
//  - the real path can FAIL: writes are optimistic, ride [RealWiring.scope], and a
//    failure rolls the selection back and lands in [writeFailure];
//  - the real path starts with `models == null` that means 「还没问过」 — [loadAttempted]
//    separates that from the seam's 「这个 seam 没有模型面」, so the picker never claims
//    「无可用模型」 before the first `model/list` answer or after a failed one;
//  - clearing a session override is a REAL write: since `p-nullkey` the DTO puts an explicit
//    `"model_id": null` on the wire (key present, value null), which is what core's
//    missing-key gate requires — an omitted key would be INVALID_ARGUMENT.

package vade.ui.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import vade.ui.data.ModelActionsReal
import vade.ui.protocol.ModelEntry
import vade.ui.protocol.VadeClient
import vade.ui.protocol.VadeException
import vade.ui.protocol.isMethodMissing

/** Which D101 layer the picker is editing: one session's override, or the global default. */
sealed interface ModelScope {
    /** `session/model.set` on [sessionId]; clearing falls back to 全局默认 → priority. */
    data class Session(val sessionId: String) : ModelScope

    /** The global default write (设置页). */
    data object Default : ModelScope
}

/**
 * Selection state for one picker instance. The state owns dedupe + selectability; the
 * [ModelActions] implementation owns where the write actually goes. [lastNotice] carries
 * the honest outcome copy — session switches say 「下一轮对话生效」 because that is what
 * `session/model.set` returns (`effective_at: next_turn`), not an invented instant swap.
 *
 * [writeFailure] / [loadFailure] are the error half of the same honesty: a failure is a
 * visible, classified value, never a dropped request and never a silent empty list.
 */
class ModelState private constructor(
    private val actions: ModelActions,
    val scope: ModelScope,
    /**
     * Real wiring; `null` = the in-memory seam (synchronous writes that cannot fail).
     *
     * Non-null is what turns on the *pull* half of this state as well: [refresh] is a no-op
     * without it, so a state built over a real adapter must not leave this `null` — see
     * [forSession]'s KDoc for the exact failure that produces.
     */
    private val real: RealWiring?,
) {
    /** The selected model id for this scope; null = session follows the global default. */
    var selectedId: String? by mutableStateOf(initialSelection())
        private set

    /** Outcome copy for the last successful change; null until something actually switched. */
    var lastNotice: String? by mutableStateOf(null)
        private set

    /**
     * `model/list` entries as of the last pull. `null` = this core has no model surface
     * (degraded / not pulled yet) — the picker must NOT turn that into an empty list.
     */
    var models: List<ModelEntry>? by mutableStateOf(actions.models)
        private set

    /**
     * Whether a pull has been **attempted** for this state.
     *
     * Starts `true` on the in-memory seam (there is nothing to pull — its vals already ARE
     * the truth) and `false` on the real path, where `models` is `null` until the first
     * `model/list` answer arrives. That difference is the whole point: 「无可用模型」 is a
     * claim about the CORE, and before the first answer we cannot make it — the picker owes
     * the reader 「正在加载」 instead. Without this, wiring the real adapter would flash a
     * false 「无可用模型」 on every screen entry (the adapter starts at `models = null`).
     */
    var loadAttempted: Boolean by mutableStateOf(real == null)
        private set

    /**
     * A pull is between "request sent" and "answer handled".
     *
     * Separate from [loadAttempted] because the picker must keep saying 「正在加载」 for the
     * whole RPC window: `models` is still `null` there, and for those milliseconds the naive
     * `models == null ⇒ 无可用模型` branch would put the false empty state on screen after a
     * real `model/list` had already gone out.
     */
    var loadInFlight: Boolean by mutableStateOf(false)

    /** True while `model/discover` is in flight; the refresh button is disabled then. */
    var discovering: Boolean by mutableStateOf(false)
        private set

    /** Old core answered `unknown method` to `model/discover`; hide the refresh control. */
    var discoverUnsupported: Boolean by mutableStateOf(false)
        private set

    /** Last terminal discovery failure was a retryable upstream error (not a dead control). */
    var discoveryFailed: Boolean by mutableStateOf(false)
        private set

    /** True only when the real adapter can attempt discovery (not the fixture seam). */
    val canDiscover: Boolean get() =
        real != null && !readOnly && !discoverUnsupported && !real.adapter.discoveryBlocked

    /**
     * Monotonic pull id. A second `refresh()` supersedes the first; the older
     * answer must not write over the newer catalog when it resumes.
     */
    @Volatile
    private var loadGeneration: Int = 0

    /** FR-8.46 degrade switch — true after a `-32601` / `unknown method` answer. */
    var readOnly: Boolean by mutableStateOf(actions.readOnly)
        private set

    /** `model/list.default.model_id` as of the last pull; null = 按 priority 兜底. */
    var defaultModelId: String? by mutableStateOf(actions.defaultModelId)
        private set

    /** A real write is in flight — the card refuses to start a second one. */
    var writePending: Boolean by mutableStateOf(false)
        private set

    /** The last failed write, classified; cleared by the next write that lands. */
    var writeFailure: ModelFailure? by mutableStateOf(null)
        private set

    /** The last failed pull, classified. A failure is never reported as "no models". */
    var loadFailure: ModelFailure? by mutableStateOf(null)
        private set

    /**
     * Set once by [dispose]; keeps the cancel idempotent and turns every public entry point
     * into a no-op. **Volatile** because `dispose()` runs on the UI thread while an
     * in-flight [refresh] resumes on a coroutine dispatcher.
     */
    @Volatile
    private var disposed = false

    companion object {
        /**
         * Wires the picker to a seam object (fixture, preview, test double) — or to the
         * **real** adapter, which is recognized by type.
         *
         * ## Why the type test is not optional
         * `ModelActionsReal` starts at `models = null` and only fills it from its suspend
         * `refreshModels()`; on the seam path [refresh] is a deliberate no-op ("the seam
         * already IS the truth"). Handing a real adapter to a state that stays on the seam
         * path therefore breaks both halves of the picker:
         *  - the list stays `null` forever ⇒ the card renders 「无可用模型」, a claim about the
         *    core that nobody ever asked the core about (the pull never happened);
         *  - writes go through the synchronous seam call, whose fire-and-forget adapter
         *    launch swallows the failure into `ModelActionsReal.lastWriteError` — [writeFailure]
         *    (and therefore the card's error line) never sees it, and the optimistic selection
         *    is never reverted.
         * Recognizing the adapter gives the real path its pull + optimistic write + revert,
         * exactly like [forRealSession].
         *
         * Every OTHER [ModelActions] keeps the pre-existing behaviour byte for byte: no scope
         * is created, no coroutine is launched, [refresh] stays a no-op and the seam write
         * stays synchronous.
         *
         * @param scope carries the pull and the writes; only touched on the real path.
         * @param autoRefresh `false` when the caller drives [refresh] itself (tests do).
         */
        fun forSession(
            actions: ModelActions,
            sessionId: String,
            scope: CoroutineScope? = null,
            autoRefresh: Boolean = true,
        ): ModelState = wired(actions, ModelScope.Session(sessionId), scope, autoRefresh)

        /** Default-scope twin of [forSession]; same real-adapter recognition. */
        fun forDefault(
            actions: ModelActions,
            scope: CoroutineScope? = null,
            autoRefresh: Boolean = true,
        ): ModelState = wired(actions, ModelScope.Default, scope, autoRefresh)

        /**
         * The one place that decides seam-vs-real for a handed-in [actions]: a
         * `ModelActionsReal` gets the [RealWiring] (and, unless suppressed, its first pull),
         * anything else gets `null` — i.e. exactly the pre-wiring state object.
         *
         * [ownsScope] says the scope was created by this factory (not handed in by a caller):
         * only then does [dispose] cancel it. `coroutineScope == null` always means "create and
         * own one here".
         */
        private fun wired(
            actions: ModelActions,
            scope: ModelScope,
            coroutineScope: CoroutineScope?,
            autoRefresh: Boolean,
            ownsScope: Boolean = false,
        ): ModelState {
            val wiring = (actions as? ModelActionsReal)?.let { adapter ->
                RealWiring(
                    adapter = adapter,
                    scope = coroutineScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default),
                    ownsScope = ownsScope || coroutineScope == null,
                )
            }
            val state = ModelState(actions, scope, wiring)
            if (wiring != null && autoRefresh) {
                // Set the flag synchronously: the launched coroutine may not run before
                // the first composition, and a click in that window must not fall into
                // `discoverUpstream`'s `loadInFlight` early-return without feedback.
                state.loadInFlight = true
                wiring.scope.launch { state.refresh() }
            }
            return state
        }

        /**
         * One-line wiring for a real session picker: builds `ModelActionsReal` over the shared
         * [client] and pulls `model/list` + `session/model.get` once in [scope] (which also
         * carries the writes). Pass `autoRefresh = false` when the caller wants to drive
         * [refresh] itself (tests do).
         *
         * [scope] `null` = this state creates a scope and **owns** it: it is cancelled by
         * [dispose], which is what a screen's `DisposableEffect` calls. A caller-supplied scope
         * is never cancelled here — the caller owns its lifetime.
         *
         * The client must already be `connect()`ed — the same instance the session screen uses.
         */
        fun forRealSession(
            client: VadeClient,
            sessionId: String,
            scope: CoroutineScope? = null,
            autoRefresh: Boolean = true,
        ): ModelState {
            val resolved = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
            return wired(
                actions = ModelActionsReal(client, resolved),
                scope = ModelScope.Session(sessionId),
                coroutineScope = resolved,
                autoRefresh = autoRefresh,
                ownsScope = scope == null,
            )
        }

        /**
         * One-line wiring for the 设置页 default picker. Refreshes `model/list` (which also
         * carries `default.model_id`). Writes are refused with UNSUPPORTED — contract 1.5.0
         * has no global-default write method — so the card renders the rows as read-only.
         *
         * Scope ownership is the same rule as [forRealSession]: `null` = created here and
         * cancelled by [dispose]; a caller-supplied scope is left alone.
         */
        fun forRealDefault(
            client: VadeClient,
            scope: CoroutineScope? = null,
            autoRefresh: Boolean = true,
        ): ModelState {
            val resolved = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
            return wired(
                actions = ModelActionsReal(client, resolved),
                scope = ModelScope.Default,
                coroutineScope = resolved,
                autoRefresh = autoRefresh,
                ownsScope = scope == null,
            )
        }
    }

    /**
     * 页面离开组合时调用：取消**这个状态自己创建**的拉取/写入作用域。
     *
     * 为什么需要（f9-audit P1-1）：real 路径的 `forSession` / `forDefault` 会自建一个
     * `CoroutineScope(SupervisorJob() + Dispatchers.Default)` 并立刻 `launch { refresh() }`，
     * 而这个作用域没有任何外部句柄。状态对象被 `remember` 丢掉之后，在途的 `model/list` /
     * `session/model.get`（以及回包对 `models` / `selectedId` 的写入）还能活到 RPC 自己超时：
     * 频繁进出会话 = 反复发同一对请求，旧状态的对象图也不可回收。本仓的约定是
     * `DisposableEffect(loader) { onDispose { loader.dispose() } }`（`UsageCard` /
     * `ActivityScreen` / `SubagentCard` / `App`），`ChatState.dispose()` 同一口径 ——
     * `SessionDetailScreen` / `ProfileScreen` 已按这个形状接线。
     *
     * **只取消自己建的那个作用域**：调用方传进来的 scope 归调用方（测试要自己控制生命周期，
     * `rememberCoroutineScope` 也由组合负责取消），这里不许越权取消。幂等；内存 seam
     * （`real == null`）与调用方自持 scope 的两种情况下都已经没有在途任务。
     *
     * **置死的是状态，不只是作用域**（f18 复审 P1-1）：取消 `ownsScope` 只拦住本类自己
     * launch 的协程，挡不住调用方在**自己**的 scope 上跑的 [refresh]（`ProfileScreen` 的
     * `retryScope` 正是这种）。所以这里同时把 [disposed] 置真、把两个在途标志复位：
     * [refresh] 的入口与每个 suspension 之后都查这个门禁，迟到回包既不改写 `models` /
     * `selectedId`，也不再追发 `session/model.get`；写路径（[select] 等）同样拒绝开工。
     */
    fun dispose() {
        if (disposed) return
        disposed = true
        // 立刻从「正在加载 / 正在写入」落下来：这个状态已经不在任何屏上，没有人会再观察它，
        // 但把它留在 true 会让「dispose 之后…」的断言读到一个假象。
        loadInFlight = false
        writePending = false
        real?.takeIf { it.ownsScope }?.scope?.cancel()
    }

    // --- degrade / refusal copy -------------------------------------------------------

    /**
     * Long copy for the read-only badge; null when the core can take model writes. Shown next
     * to the 「只读」 chip so the degrade is visible WITHOUT expanding the card.
     */
    val readOnlyReason: String?
        get() = if (readOnly) READ_ONLY_BODY else null

    /**
     * Why writes cannot even be attempted here; `null` = writes are allowed. Read-only covers
     * the old core; the default scope has no write method at all (contract 1.5.0).
     */
    val writeBlockedReason: String?
        get() = when {
            readOnly -> READ_ONLY_BODY
            scope is ModelScope.Default && real != null -> DEFAULT_WRITE_BODY
            else -> null
        }

    /** True when this state can honestly attempt a write. */
    val writesEnabled: Boolean get() = writeBlockedReason == null

    /**
     * True only when 「恢复默认模型」 can honestly go on the wire: session scope, writable core,
     * and an override actually set. The clearing frame carries an explicit `"model_id": null`
     * (required-with-null since `p-nullkey`), which is what core's `if "model_id" not in params`
     * gate needs — an omitted key would be INVALID_ARGUMENT.
     */
    val canClearOverride: Boolean get() = scope is ModelScope.Session && !readOnly

    // --- reads ------------------------------------------------------------------------

    private fun initialSelection(): String? = when (val s = scope) {
        is ModelScope.Session -> actions.sessionOverride(s.sessionId)
        ModelScope.Default -> actions.defaultModelId
    }

    /**
     * Pull the model surface. Real wiring only — the in-memory seam already IS the truth.
     *
     * `model/list` first (it also carries the global default), then the session override.
     * Failures are recorded in [loadFailure] instead of thrown so the one-line `autoRefresh`
     * launch cannot crash the scope; the degrade case (`-32601`) is a RETURN VALUE of the
     * adapter (`readOnly = true`, `models = null`) and is mirrored, not treated as an error.
     *
     * Cancellation is split into two, because a timeout IS a `CancellationException` subclass
     * and rethrowing it used to swallow the failure whole (`g-model-refresh`): a request that
     * outlives its deadline surfaces as [CancellationException] (a `TimeoutCancellationException`
     * from an inner `withTimeout`, Ktor's request-timeout shape, or a dead WebSocket session
     * surfacing as a plain one). The two are told apart by the CURRENT job's liveness:
     *  - our own scope is gone ([ensureActive] throws) → real cancellation: rethrow, record
     *    nothing — the state object belongs to a dead scope and nobody observes it;
     *  - the job is still alive → the cancellation came from the REQUEST, not from us: it is
     *    a retryable failure and MUST land in [loadFailure]. Rethrowing it is what collapsed
     *    a weak-network timeout into the 「无可用模型」 false empty state (`models` still null,
     *    `loadFailure` null, `loadInFlight` reset by the finally).
     *
     * ## Dead state (f18 复审 P1-1)
     * [refresh] is **public and suspend**, so any caller can run it on a scope this state does
     * not own (`ProfileScreen`'s retry button uses `rememberCoroutineScope`). Cancelling
     * `ownsScope` therefore cannot stop those pulls. The [disposed] gate is checked at entry
     * and **after every suspension**: a late `model/list` answer must not mirror into
     * [models] and must not chase `session/model.get`; a late override answer must not adopt
     * into [selectedId]. What was already pulled before `dispose()` stays as it was — the
     * gate is positional, not a silent wipe.
     */
    suspend fun refresh() {
        if (disposed) return
        val wiring = real ?: return
        // From here on the picker is 「正在加载」, not 「无可用模型」: the request is out and no
        // answer has been handled yet (`models` is still null on a first pull).
        loadAttempted = true
        loadInFlight = true
        loadFailure = null
        val generation = ++loadGeneration
        try {
            val sessionId = (scope as? ModelScope.Session)?.sessionId
            try {
                wiring.adapter.refreshModels()
            } catch (c: CancellationException) {
                currentCoroutineContext().ensureActive() // our own scope died → real cancel
                loadFailure = modelLoadFailureOf(c) // timeout-shaped: a retryable failure
            } catch (t: Throwable) {
                loadFailure = modelLoadFailureOf(t)
            }
            // Late answer for a dead state or for a superseded pull: do not mirror,
            // do not chase the override.
            if (disposed || generation != loadGeneration) return
            models = wiring.adapter.models
            readOnly = wiring.adapter.readOnly
            defaultModelId = wiring.adapter.defaultModelId
            // A degraded core has no model surface at all — do not chase session/model.get.
            if (readOnly) return

            if (sessionId != null) {
                try {
                    wiring.adapter.refreshSessionOverride(sessionId)
                    // Same gate, one suspension later: a `session/model.get` answer that lands
                    // after the page is gone (or after a newer pull started) must not
                    // become this state's selection.
                    if (disposed || generation != loadGeneration) return
                    // Only adopt the pulled override into an empty selection: a pick made while
                    // the pull was in flight must not be clobbered by a stale answer.
                    if (selectedId == null) selectedId = wiring.adapter.sessionOverride(sessionId)
                } catch (c: CancellationException) {
                    // Same split as the list pull above: a timeout-shaped cancellation of the
                    // override pull is a failure (the first one already recorded keeps priority
                    // — the card shows one classified failure, not two), a real cancel rethrows.
                    currentCoroutineContext().ensureActive()
                    if (loadFailure == null) loadFailure = modelLoadFailureOf(c)
                } catch (t: Throwable) {
                    if (loadFailure == null) loadFailure = modelLoadFailureOf(t)
                }
            } else if (selectedId == null) {
                selectedId = wiring.adapter.defaultModelId
            }
        } finally {
            // Only the newest pull owns the flag; an older answer resuming late must
            // not make the picker think the newer request has finished.
            if (generation == loadGeneration) loadInFlight = false
        }
    }

    /**
     * Refresh the upstream `/models` catalog, then re-pull `model/list`.
     *
     * Failure policy: a failed discovery NEVER clears the current rows. The card
     * shows the classified failure and keeps the last good list selectable; the
     * same applies to an old core (method missing) — it is a visible failure, not
     * a silent empty picker. The 2 s cooldown is a success (`cached=true`), so it
     * simply re-reads the catalog.
     */
    suspend fun discoverUpstream() {
        if (disposed || discovering || loadInFlight) return
        val wiring = real ?: return
        discovering = true
        loadFailure = null
        discoveryFailed = false
        try {
            wiring.adapter.discoverModels()
            if (disposed) return
            discoverUnsupported = false
            discoveryFailed = false
            refresh()
        } catch (c: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (!disposed) {
                discoveryFailed = true
                loadFailure = modelLoadFailureOf(c)
            }
        } catch (t: Throwable) {
            if (disposed) return
            if (t is VadeException && (t.isMethodMissing() || t.reason == "not_discoverable")) {
                // Old core, or a core whose provider owns its own list (module/mock):
                // one answer is enough. Keep the flag on the shared adapter so the
                // next page does not render the same dead control.
                discoverUnsupported = true
                wiring.adapter.discoveryBlocked = true
            } else {
                discoveryFailed = true
            }
            loadFailure = modelLoadFailureOf(t)
        } finally {
            discovering = false
        }
    }

    /**
     * The one retry entry the card uses. A discovery failure must re-run
     * `model/discover`; re-pulling `model/list` cannot repair an upstream
     * probe and would leave the user clicking twice. Terminal verdicts are
     * hidden by the UI instead.
     */
    suspend fun retryLoad() {
        if (discoveryFailed && canDiscover) discoverUpstream() else refresh()
    }
    }

    /** Every drawable row with its selection / clickability decision (server order kept). */
    fun rows(): List<ModelRow> = when (val content = content()) {
        is ModelPickerContent.Rows -> content.rows
        // The read-only degrade, the in-flight pull, a failed pull and the empty state have
        // no rows BY CONSTRUCTION — returning an empty row list here is not the same as
        // claiming "no models configured".
        is ModelPickerContent.ReadOnly -> emptyList()
        is ModelPickerContent.Loading -> emptyList()
        is ModelPickerContent.LoadFailed -> emptyList()
        is ModelPickerContent.NoModels -> emptyList()
    }

    /**
     * Rows / read-only degrade / first-pull-in-flight / failed pull / 「无可用模型」 —
     * computed from the seam, never hard-coded.
     *
     * The two middle states are what keep a *real* adapter honest before its first answer:
     * `models == null` there means "not asked yet" or "the ask failed", never "the core has
     * none" (that is [ModelPickerContent.NoModels], which the seam path still reaches
     * exactly as before because [loadAttempted] starts `true` there).
     */
    fun content(): ModelPickerContent = modelPickerContent(
        models = models,
        readOnly = readOnly,
        selectedId = selectedId,
        writesEnabled = writesEnabled,
        loadFailure = loadFailure,
        awaitingFirstPull = models == null && (loadInFlight || !loadAttempted),
    )

    // --- writes -----------------------------------------------------------------------

    /**
     * Select [modelId]. Returns true only when a request actually went out; false is a
     * no-op: same id (no duplicate request), unknown id, offline entry, or read-only core.
     *
     * Real wiring: the selection flips immediately and the write rides [RealWiring.scope];
     * a failure reverts it and lands in [writeFailure] (that is the fire-and-forget path).
     */
    fun select(modelId: String): Boolean {
        if (disposed) return false
        val option = selectable(modelId) ?: return false
        val wiring = real
        if (wiring == null) {
            applySeamSelection(option)
            return true
        }
        if (scope is ModelScope.Default) return selectDefaultReal(option, rethrow = false)

        val previous = optimistic(option)
        wiring.scope.launch {
            try {
                writeSessionReal(wiring, (scope as ModelScope.Session).sessionId, option.id, previous)
            } catch (c: CancellationException) {
                throw c
            } catch (_: Throwable) {
                // writeSessionReal already reverted the selection and filled writeFailure.
            }
        }
        return true
    }

    /**
     * Suspend variant of [select] for the real wiring: same optimistic flip, same revert +
     * [writeFailure], then RETHROWS the failure (use it from coroutines that want it directly).
     */
    suspend fun selectAwaiting(modelId: String): Boolean {
        if (disposed) return false
        val option = selectable(modelId) ?: return false
        val wiring = real
        if (wiring == null) {
            applySeamSelection(option)
            return true
        }
        if (scope is ModelScope.Default) return selectDefaultReal(option, rethrow = true)
        val previous = optimistic(option)
        writeSessionReal(wiring, (scope as ModelScope.Session).sessionId, option.id, previous)
        return true
    }

    /**
     * Session scope only: clear the explicit override and fall back to 全局默认 / priority
     * (`session/model.set` with `model_id: null`). False when already cleared, on the
     * default scope, or read-only.
     *
     * Real wiring: the frame now carries an explicit `"model_id": null` (the `p-nullkey`
     * fix), so this is a real, usable write — optimistic like [select], with the same revert +
     * [writeFailure] on failure.
     */
    fun followGlobalDefault(): Boolean {
        if (disposed) return false
        val s = scope as? ModelScope.Session ?: return false
        if (!canClearOverride) return false
        val previous = selectedId ?: return false
        val wiring = real
        if (wiring == null) {
            selectedId = null
            actions.setSessionModel(s.sessionId, null)
            lastNotice = CLEAR_NOTICE
            return true
        }
        selectedId = null
        lastNotice = CLEAR_NOTICE
        wiring.scope.launch {
            try {
                writeSessionReal(wiring, s.sessionId, modelId = null, previous = previous)
            } catch (c: CancellationException) {
                throw c
            } catch (_: Throwable) {
                // writeSessionReal already restored the override and filled writeFailure.
            }
        }
        return true
    }

    /** Suspend variant of [followGlobalDefault]: revert + record, then RETHROW the failure. */
    suspend fun followGlobalDefaultAwaiting(): Boolean {
        if (disposed) return false
        val s = scope as? ModelScope.Session ?: return false
        if (!canClearOverride) return false
        val previous = selectedId ?: return false
        val wiring = real ?: run {
            selectedId = null
            actions.setSessionModel(s.sessionId, null)
            lastNotice = CLEAR_NOTICE
            return true
        }
        selectedId = null
        lastNotice = CLEAR_NOTICE
        writeSessionReal(wiring, s.sessionId, modelId = null, previous = previous)
        return true
    }

    /** Header line: what would answer the next turn (D101 resolution, shown not derived twice). */
    fun currentLabel(): String = when {
        scope is ModelScope.Session && selectedId == null -> "全局默认"
        scope is ModelScope.Default && selectedId == null -> "按 priority 兜底"
        else ->
            models.orEmpty().map(ModelOption::of).firstOrNull { it.id == selectedId }?.title()
                ?: selectedId
                ?: "全局默认"
    }

    // --- internals --------------------------------------------------------------------

    /** The picked option, or null when the click must be a no-op (the three rules above). */
    private fun selectable(modelId: String): ModelOption? {
        if (readOnly) return null
        if (modelId == selectedId) return null
        val option = models.orEmpty().map(ModelOption::of).firstOrNull { it.id == modelId } ?: return null
        return option.takeIf { it.selectable }
    }

    /** In-memory seam: synchronous write, no failure branch exists. */
    private fun applySeamSelection(option: ModelOption) {
        selectedId = option.id
        when (val s = scope) {
            is ModelScope.Session -> actions.setSessionModel(s.sessionId, option.id)
            ModelScope.Default -> actions.setDefaultModel(option.id)
        }
        lastNotice = noticeOf(option)
    }

    /** Flip the selection + notice before the RPC; returns the value to revert to. */
    private fun optimistic(option: ModelOption): String? {
        val previous = selectedId
        selectedId = option.id
        lastNotice = noticeOf(option)
        return previous
    }

    private suspend fun writeSessionReal(
        wiring: RealWiring,
        sessionId: String,
        modelId: String?,
        previous: String?,
    ) {
        writePending = true
        try {
            wiring.adapter.setSessionModelAwaiting(sessionId, modelId)
            // A dead state must not report the outcome of a write that outlived its page.
            if (disposed) return
            writeFailure = null
        } catch (c: CancellationException) {
            // Undo the optimistic flip for BOTH shapes (a dead scope and a timed-out write
            // must both leave the selection where it was), then split them: real cancellation
            // rethrows with no failure recorded; a timeout-shaped cancellation is a REAL
            // failure — record it before rethrowing so the fire-and-forget launch (which
            // swallows the rethrow) still shows the card's error line.
            if (!disposed) {
                selectedId = previous
                lastNotice = null
            }
            currentCoroutineContext().ensureActive()
            if (disposed) throw c
            writeFailure = modelWriteFailureOf(c)
            throw c
        } catch (t: Throwable) {
            if (disposed) throw t
            selectedId = previous
            lastNotice = null
            writeFailure = modelWriteFailureOf(t)
            throw t
        } finally {
            writePending = false
        }
    }

    /**
     * Default scope on the real path. Contract 1.5.0 has no global-default write, so the
     * adapter raises UNSUPPORTED without sending a frame — record it as a visible failure.
     * [rethrow] = the awaiting variant's contract.
     */
    private fun selectDefaultReal(option: ModelOption, rethrow: Boolean): Boolean = try {
        actions.setDefaultModel(option.id)
        selectedId = option.id
        lastNotice = noticeOf(option)
        writeFailure = null
        true
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        writeFailure = modelWriteFailureOf(t)
        if (rethrow) throw t
        false
    }

    private fun noticeOf(option: ModelOption): String = when (scope) {
        is ModelScope.Session -> "模型已切换：${option.title()}（下一轮对话生效）"
        ModelScope.Default -> "默认模型已设为：${option.title()}"
    }

}

// Copy that the state and the card share. File-private: these are this layer's words, not an
// API. READ_ONLY_BODY is FR-8.46's degrade explanation; DEFAULT_WRITE_BODY names the missing
// contract method so the 设置页 does not read like a bug.
private const val READ_ONLY_BODY =
    "此 core 不支持模型选择：旧版 core 没有 model/list（-32601 / unknown method），" +
        "客户端降级为只读；列表保持「未知」而不是「没有模型」（FR-8.46）。"

private const val DEFAULT_WRITE_BODY =
    "core 契约 1.5.0 没有全局默认模型的写方法（UNSUPPORTED）——" +
        "默认模型跟随 core 配置，此处只读。"

private const val CLEAR_NOTICE = "已清除会话覆盖，跟随全局默认"

/**
 * The real adapter plus the scope its suspend work rides. Held as one object so the in-memory
 * seam (synchronous, cannot fail) and the real path stay visibly distinct: every failure
 * branch in [ModelState] exists only when this is non-null.
 */
private class RealWiring(
    val adapter: ModelActionsReal,
    val scope: CoroutineScope,
    /** True when [scope] was created by this factory — the only case [ModelState.dispose] cancels. */
    val ownsScope: Boolean = false,
)
