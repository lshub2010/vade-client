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
// used by the fixture and by every state test. [forRealSession] / [forRealDefault] build the
// real adapter (`ModelActionsReal`) from a connected `VadeClient`, so a screen's wiring is one
// line. The differences are deliberate and visible below:
//  - the real path MIRRORS the adapter's pulled values into snapshot state (`models`,
//    `readOnly`, `defaultModelId`) so the badge/list actually recompose after a pull — the
//    adapter's plain vals are backed by StateFlows and would not trigger recomposition;
//  - the real path can FAIL: writes are optimistic, ride [RealWiring.writeScope], and a
//    failure rolls the selection back and lands in [writeFailure];
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
import kotlinx.coroutines.launch
import vade.ui.data.ModelActionsReal
import vade.ui.protocol.ModelEntry
import vade.ui.protocol.VadeClient

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
    /** Real wiring; `null` = the in-memory seam (synchronous writes that cannot fail). */
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

    companion object {
        /** Wires the picker to an arbitrary seam object (fixture, preview, test double). */
        fun forSession(actions: ModelActions, sessionId: String): ModelState =
            ModelState(actions, ModelScope.Session(sessionId), real = null)

        /** Wires the default-scope picker to an arbitrary seam object. */
        fun forDefault(actions: ModelActions): ModelState =
            ModelState(actions, ModelScope.Default, real = null)

        /**
         * One-line wiring for a real session picker: builds `ModelActionsReal` over the shared
         * [client] and pulls `model/list` + `session/model.get` once in [scope] (which also
         * carries the writes). Pass `autoRefresh = false` when the caller wants to drive
         * [refresh] itself (tests do).
         *
         * The client must already be `connect()`ed — the same instance the session screen uses.
         */
        fun forRealSession(
            client: VadeClient,
            sessionId: String,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            autoRefresh: Boolean = true,
        ): ModelState {
            val adapter = ModelActionsReal(client, scope)
            val state = ModelState(
                actions = adapter,
                scope = ModelScope.Session(sessionId),
                real = RealWiring(adapter, scope),
            )
            if (autoRefresh) scope.launch { state.refresh() }
            return state
        }

        /**
         * One-line wiring for the 设置页 default picker. Refreshes `model/list` (which also
         * carries `default.model_id`). Writes are refused with UNSUPPORTED — contract 1.5.0
         * has no global-default write method — so the card renders the rows as read-only.
         */
        fun forRealDefault(
            client: VadeClient,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            autoRefresh: Boolean = true,
        ): ModelState {
            val adapter = ModelActionsReal(client, scope)
            val state = ModelState(
                actions = adapter,
                scope = ModelScope.Default,
                real = RealWiring(adapter, scope),
            )
            if (autoRefresh) scope.launch { state.refresh() }
            return state
        }
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
     */
    suspend fun refresh() {
        val wiring = real ?: return
        loadFailure = null
        val sessionId = (scope as? ModelScope.Session)?.sessionId
        try {
            wiring.adapter.refreshModels()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            loadFailure = modelLoadFailureOf(t)
        }
        models = wiring.adapter.models
        readOnly = wiring.adapter.readOnly
        defaultModelId = wiring.adapter.defaultModelId
        // A degraded core has no model surface at all — do not chase session/model.get.
        if (readOnly) return

        if (sessionId != null) {
            try {
                wiring.adapter.refreshSessionOverride(sessionId)
                // Only adopt the pulled override into an empty selection: a pick made while
                // the pull was in flight must not be clobbered by a stale answer.
                if (selectedId == null) selectedId = wiring.adapter.sessionOverride(sessionId)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                if (loadFailure == null) loadFailure = modelLoadFailureOf(t)
            }
        } else if (selectedId == null) {
            selectedId = wiring.adapter.defaultModelId
        }
    }

    /** Every drawable row with its selection / clickability decision (server order kept). */
    fun rows(): List<ModelRow> = when (val content = content()) {
        is ModelPickerContent.Rows -> content.rows
        // The read-only degrade and the empty state have no rows BY CONSTRUCTION — returning
        // an empty row list here is not the same as claiming "no models configured".
        is ModelPickerContent.ReadOnly -> emptyList()
        is ModelPickerContent.NoModels -> emptyList()
    }

    /** Rows / read-only degrade / 「无可用模型」 — computed from the seam, never hard-coded. */
    fun content(): ModelPickerContent = modelPickerContent(
        models = models,
        readOnly = readOnly,
        selectedId = selectedId,
        writesEnabled = writesEnabled,
    )

    // --- writes -----------------------------------------------------------------------

    /**
     * Select [modelId]. Returns true only when a request actually went out; false is a
     * no-op: same id (no duplicate request), unknown id, offline entry, or read-only core.
     *
     * Real wiring: the selection flips immediately and the write rides [RealWiring.writeScope];
     * a failure reverts it and lands in [writeFailure] (that is the fire-and-forget path).
     */
    fun select(modelId: String): Boolean {
        val option = selectable(modelId) ?: return false
        val wiring = real
        if (wiring == null) {
            applySeamSelection(option)
            return true
        }
        if (scope is ModelScope.Default) return selectDefaultReal(option, rethrow = false)

        val previous = optimistic(option)
        wiring.writeScope.launch {
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
        wiring.writeScope.launch {
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
            writeFailure = null
        } catch (c: CancellationException) {
            selectedId = previous
            lastNotice = null
            throw c
        } catch (t: Throwable) {
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
    val writeScope: CoroutineScope,
)
