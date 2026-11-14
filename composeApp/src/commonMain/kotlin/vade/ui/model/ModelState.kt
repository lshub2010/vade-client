// Model picker state — the decision layer between the seam and the drawing.
//
// Same discipline as ChatState / SearchState: pure transitions, snapshot-state fields, no
// window and no clock, so `ModelPickerTest` can drive every acceptance item directly.
// The three rules the tests pin down:
//  1. selection is a single id — two rows can never both be "selected";
//  2. writes carry the protocol `model_id`, never the display name;
//  3. re-selecting the already-selected model is a no-op that sends NO request.

package vade.ui.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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
 */
class ModelState private constructor(
    private val actions: ModelActions,
    val scope: ModelScope,
) {
    /** The selected model id for this scope; null = session follows the global default. */
    var selectedId: String? by mutableStateOf(initialSelection())
        private set

    /** Outcome copy for the last successful change; null until something actually switched. */
    var lastNotice: String? by mutableStateOf(null)
        private set

    companion object {
        fun forSession(actions: ModelActions, sessionId: String): ModelState =
            ModelState(actions, ModelScope.Session(sessionId))

        fun forDefault(actions: ModelActions): ModelState =
            ModelState(actions, ModelScope.Default)
    }

    private fun initialSelection(): String? = when (val s = scope) {
        is ModelScope.Session -> actions.sessionOverride(s.sessionId)
        ModelScope.Default -> actions.defaultModelId
    }

    /** Every drawable row with its selection / clickability decision (server order kept). */
    fun rows(): List<ModelRow> = when (val content = content()) {
        is ModelPickerContent.Rows -> content.rows
        is ModelPickerContent.NoModels -> emptyList()
    }

    /** Rows vs the 「无可用模型」 empty state — computed from the seam, never hard-coded. */
    fun content(): ModelPickerContent =
        modelPickerContent(actions.models, actions.readOnly, selectedId)

    /**
     * Select [modelId]. Returns true only when a request actually went out; false is a
     * no-op: same id (no duplicate request), unknown id, offline entry, or read-only core.
     */
    fun select(modelId: String): Boolean {
        if (actions.readOnly) return false
        if (modelId == selectedId) return false
        val option = actions.models.orEmpty().map(ModelOption::of).firstOrNull { it.id == modelId }
            ?: return false
        if (!option.selectable) return false

        selectedId = modelId
        when (val s = scope) {
            is ModelScope.Session -> actions.setSessionModel(s.sessionId, modelId)
            ModelScope.Default -> actions.setDefaultModel(modelId)
        }
        lastNotice = when (scope) {
            is ModelScope.Session -> "模型已切换：${option.title()}（下一轮对话生效）"
            ModelScope.Default -> "默认模型已设为：${option.title()}"
        }
        return true
    }

    /**
     * Session scope only: clear the explicit override and fall back to 全局默认 / priority
     * (`session/model.set` with `model_id: null`). False when already cleared, on the
     * default scope, or read-only.
     */
    fun followGlobalDefault(): Boolean {
        val s = scope as? ModelScope.Session ?: return false
        if (actions.readOnly) return false
        if (selectedId == null) return false
        selectedId = null
        actions.setSessionModel(s.sessionId, null)
        lastNotice = "已清除会话覆盖，跟随全局默认"
        return true
    }

    /** Header line: what would answer the next turn (D101 resolution, shown not derived twice). */
    fun currentLabel(): String = when {
        scope is ModelScope.Session && selectedId == null -> "全局默认"
        scope is ModelScope.Default && selectedId == null -> "按 priority 兜底"
        else ->
            actions.models.orEmpty().map(ModelOption::of).firstOrNull { it.id == selectedId }?.title()
                ?: selectedId
                ?: "全局默认"
    }
}
