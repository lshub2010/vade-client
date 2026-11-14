package vade.ui.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The state machine behind both picker entries (任务书 ④–⑦): writes carry the protocol
 * `model_id` (never the display name), re-selecting sends nothing, offline entries refuse
 * selection, an absent list renders the 「无可用模型」 empty state instead of an empty
 * list, and same-name-different-provider entries never blur into each other.
 *
 * Driven through a recording [ModelActions] — no composition, no window.
 */
class ModelStateTest {

    /** Recording seam: every write lands here verbatim, keyed by scope. */
    private class RecordingModelActions(
        override val models: List<vade.ui.protocol.ModelEntry>? = ModelFixtures.catalog,
        override val readOnly: Boolean = false,
        override var defaultModelId: String? = null,
    ) : ModelActions {

        data class Call(val kind: String, val sessionId: String?, val modelId: String?)

        private val sessionOverrides = mutableMapOf<String, String?>()
        val calls = mutableListOf<Call>()

        fun seed(sessionId: String, modelId: String?) {
            sessionOverrides[sessionId] = modelId
        }

        override fun sessionOverride(sessionId: String): String? = sessionOverrides[sessionId]

        override fun setSessionModel(sessionId: String, modelId: String?) {
            calls += Call("session", sessionId, modelId)
            sessionOverrides[sessionId] = modelId
        }

        override fun setDefaultModel(modelId: String?) {
            calls += Call("default", null, modelId)
            defaultModelId = modelId
        }
    }

    // --- ④ the write carries model_id, not the display name ----------------------

    @Test
    fun selectSendsTheModelIdNeverTheDisplayName() {
        val actions = RecordingModelActions()
        val state = ModelState.forSession(actions, "s-rfc0004")

        assertTrue(state.select("claude-sonnet"))
        assertEquals(
            listOf(RecordingModelActions.Call("session", "s-rfc0004", "claude-sonnet")),
            actions.calls,
        )
        // The entry's display name is "Claude Sonnet" — if any call carried it, the picker
        // confused the wire id with the label.
        assertTrue(actions.calls.none { it.modelId == "Claude Sonnet" })
        assertEquals("claude-sonnet", state.selectedId)
        assertEquals("claude-sonnet", actions.sessionOverride("s-rfc0004"))
    }

    // --- ⑥ re-selecting the same model sends no second request --------------------

    @Test
    fun reselectingTheSameModelSendsNoRequest() {
        val actions = RecordingModelActions()
        val state = ModelState.forSession(actions, "s-1")

        assertTrue(state.select("local-qwen"))
        assertFalse(state.select("local-qwen"), "already selected — the second tap is a no-op")
        assertEquals(1, actions.calls.size, "exactly one set went out")
        assertEquals("local-qwen", state.selectedId)
    }

    // --- ③ selection is unique and accent-marked -----------------------------------

    @Test
    fun exactlyOneRowIsSelectedAndItIsTheAccentRow() {
        val actions = RecordingModelActions()
        val state = ModelState.forSession(actions, "s-1")
        assertTrue(state.select("claude-sonnet"))

        val rows = state.rows()
        val selected = rows.filter { it.selected }
        assertEquals(1, selected.size, "a single id wins: ${rows.filter { it.selected }.map { it.option.id }}")
        assertEquals("claude-sonnet", selected.single().option.id)
        assertTrue(selected.single().clickable)
        // The unselected rows are unselected even though some share badges/priority.
        assertEquals(rows.size - 1, rows.count { !it.selected })
    }

    // --- ⑦ same display name, different providers stay distinct --------------------

    @Test
    fun sameDisplayNameDifferentProvidersNeverBlur() {
        val twinOpenai = vade.ui.protocol.ModelEntry(
            id = "twin-openai",
            provider = "openai",
            displayName = "Chat Twin",
            inputModalities = listOf("text"),
            maxImageBytes = null,
            maxImagePx = null,
            maxImagesPerTurn = null,
            contextWindow = 16_000L,
            supportsTools = true,
            priority = 10,
            available = true,
        )
        val twinAnthropic = twinOpenai.copy(id = "twin-anthropic", provider = "anthropic", priority = 20)
        val actions = RecordingModelActions(models = listOf(twinOpenai, twinAnthropic))
        val state = ModelState.forSession(actions, "s-1")

        val rows = state.rows()
        assertEquals(2, rows.size, "both entries render — nothing merged by display name")
        assertEquals(setOf("twin-openai", "twin-anthropic"), rows.map { it.option.id }.toSet())
        assertEquals("Chat Twin", rows[0].option.title(), "identical labels on purpose")
        assertEquals("Chat Twin", rows[1].option.title())

        assertTrue(state.select("twin-anthropic"))
        assertEquals("twin-anthropic", state.selectedId)
        assertEquals(
            listOf(RecordingModelActions.Call("session", "s-1", "twin-anthropic")),
            actions.calls,
            "the id of the picked provider goes out — never the shared name",
        )
    }

    // --- offline entries stay listed but refuse selection ---------------------------

    @Test
    fun offlineModelIsListedGreyedAndNotSelectable() {
        val actions = RecordingModelActions()
        val state = ModelState.forSession(actions, "s-1")

        val offlineRow = state.rows().single { it.option.id == "gpt-4o" }
        assertFalse(offlineRow.option.selectable, "available=false ⇒ not selectable")
        assertFalse(offlineRow.clickable)
        assertEquals(CapSupport.Unsupported, offlineRow.option.available)

        assertFalse(state.select("gpt-4o"), "the picker refuses the click; the server would too")
        assertEquals(0, actions.calls.size, "no request for a dead instance")
        assertNull(state.selectedId)
        assertNull(state.lastNotice, "a refusal is not a successful switch")
    }

    @Test
    fun unknownAvailabilityIsSelectableAndLabelledUnknown() {
        // A dedicated entry: `available` genuinely absent (the undeclared fixture is online,
        // because availability is runtime state, not a declared capability).
        val mystery = ModelFixtures.undeclared.copy(available = null)
        val actions = RecordingModelActions(models = listOf(mystery))
        val state = ModelState.forSession(actions, "s-1")

        val row = state.rows().single()
        assertEquals(CapSupport.Unknown, row.option.available)
        assertTrue(row.clickable, "the server is the validator; unknown ≠ offline")

        assertTrue(state.select(mystery.id))
        assertEquals(mystery.id, actions.calls.single().modelId)
    }

    // --- ⑤ the empty state, not an empty list ---------------------------------------

    @Test
    fun noModelsShowsTheEmptyStateNotAnEmptyList() {
        val nullSurface = RecordingModelActions(models = null)
        val noneConfigured = RecordingModelActions(models = emptyList())

        for (actions in listOf(nullSurface, noneConfigured)) {
            val state = ModelState.forSession(actions, "s-1")
            val content = state.content()
            assertTrue(content is ModelPickerContent.NoModels, "got $content")
            val empty = content as ModelPickerContent.NoModels
            assertEquals("无可用模型", empty.title)
            assertTrue(empty.body.contains("管理员"), "the copy says who can fix it: ${empty.body}")
            assertTrue(state.rows().isEmpty())
        }
    }

    @Test
    fun readOnlyCoreDegradesToTheFR846CopyAndRefusesWrites() {
        val actions = RecordingModelActions(models = ModelFixtures.catalog, readOnly = true)
        val state = ModelState.forSession(actions, "s-1")

        val content = state.content()
        assertTrue(content is ModelPickerContent.Rows, "a read-only core with a list still shows rows: $content")
        val rows = content as ModelPickerContent.Rows
        assertFalse(rows.interactive)
        assertTrue(rows.rows.none { it.clickable })

        assertFalse(state.select("claude-sonnet"), "read-only ⇒ every select is a no-op")
        assertEquals(0, actions.calls.size)
    }

    @Test
    fun aPopulatedListIsInteractiveRows() {
        val state = ModelState.forDefault(RecordingModelActions())
        val content = state.content()
        assertTrue(content is ModelPickerContent.Rows, "got $content")
        val rows = content as ModelPickerContent.Rows
        assertTrue(rows.interactive)
        assertEquals(ModelFixtures.catalog.size, rows.rows.size, "server order kept, nothing dropped")
    }

    // --- clearing the session override ------------------------------------------------

    @Test
    fun followGlobalDefaultClearsTheOverrideExactlyOnce() {
        val actions = RecordingModelActions()
        actions.seed("s-1", "claude-sonnet")
        val state = ModelState.forSession(actions, "s-1")
        assertEquals("claude-sonnet", state.selectedId, "initial selection comes from the seam")

        assertTrue(state.followGlobalDefault())
        assertNull(state.selectedId)
        assertEquals(
            listOf(RecordingModelActions.Call("session", "s-1", null)),
            actions.calls,
            "clearing is session/model.set with model_id=null",
        )
        assertFalse(state.followGlobalDefault(), "already cleared — no second write")
        assertEquals(1, actions.calls.size)
        assertEquals("全局默认", state.currentLabel())
    }

    @Test
    fun followGlobalDefaultIsSessionScopeOnly() {
        val actions = RecordingModelActions()
        val state = ModelState.forDefault(actions)
        assertFalse(state.followGlobalDefault(), "the default layer has no override to clear")
        assertEquals(0, actions.calls.size)
    }

    // --- default scope ------------------------------------------------------------------

    @Test
    fun defaultScopeWritesTheDefaultModelId() {
        val actions = RecordingModelActions()
        val state = ModelState.forDefault(actions)

        assertTrue(state.select("local-qwen"))
        assertEquals(
            listOf(RecordingModelActions.Call("default", null, "local-qwen")),
            actions.calls,
        )
        assertEquals("local-qwen", actions.defaultModelId)
        assertEquals("Qwen2.5 32B", state.currentLabel(), "the label is the display name of the pick")
    }

    @Test
    fun defaultScopeStartsFromTheSeededDefaultAndPrioritiesTheLabel() {
        val actions = RecordingModelActions(defaultModelId = "claude-sonnet")
        val state = ModelState.forDefault(actions)
        assertEquals("claude-sonnet", state.selectedId)
        assertEquals("Claude Sonnet", state.currentLabel())

        val empty = ModelState.forDefault(RecordingModelActions())
        assertEquals("按 priority 兜底", empty.currentLabel(), "no default set — say so, do not fake one")
    }

    // --- honest outcome copy --------------------------------------------------------------

    @Test
    fun sessionSwitchNoticeSaysNextTurnNotInstant() {
        val actions = RecordingModelActions()
        val state = ModelState.forSession(actions, "s-1")

        assertTrue(state.select("local-qwen"))
        assertTrue(
            state.lastNotice!!.contains("下一轮"),
            "`session/model.set` is effective_at=next_turn — the copy must not claim more: ${state.lastNotice}",
        )
        assertTrue(state.lastNotice!!.contains("Qwen2.5 32B"))
    }

    @Test
    fun unknownModelIdIsANoOp() {
        val actions = RecordingModelActions()
        val state = ModelState.forSession(actions, "s-1")
        assertFalse(state.select("not-in-the-list"))
        assertEquals(0, actions.calls.size)
    }
}
