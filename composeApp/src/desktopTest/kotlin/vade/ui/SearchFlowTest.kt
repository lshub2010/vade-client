package vade.ui

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import kotlinx.coroutines.runBlocking
import vade.ui.data.FakeSessions
import vade.ui.destinations.Destination
import vade.ui.search.LocalSessionIndex
import vade.ui.search.SEARCH_MAX_CHARS
import vade.ui.search.SEARCH_MIN_CHARS
import vade.ui.search.SearchPhase
import vade.ui.search.SearchScope
import vade.ui.search.SearchState
import vade.ui.search.matchRanges
import vade.ui.search.mergeRanges
import vade.ui.search.searchTerms
import vade.ui.shell.UiState
import vade.ui.shell.handleShellKeys
import vade.ui.skin.BottomBarStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * U1-c — the three things the task book §五 U1-c / FR-8.27 actually promise, each measurable
 * without a window:
 *
 * 1. the local index (`LocalSessionIndex`) — AND semantics, case folding, title-before-preview
 *    ranking, an honest "0 hits" answer, and no unbounded scan on absurd input;
 * 2. the state object — `open → type → close → reopen` keeps the query, the selection and the
 *    result-list scroll offset (the acceptance criterion: 从结果进会话再返回，搜索词与列表
 *    滚动位置保留);
 * 3. the *real* key router (`handleShellKeys`, made `internal` for this file) — ⌘K / Ctrl+K
 *    toggles, `Esc` closes, `↑ ↓` wrap, `Enter` opens the selected session.
 *
 * The corpus is `FakeSessions.all` (six rows, two of which mention AGP), so the expected
 * hits below are facts about that fixture, not about a mock.
 */
class SearchFlowTest {

    // --- index ------------------------------------------------------------------

    private val index = LocalSessionIndex(FakeSessions.all)

    private fun ids(query: String): List<String> =
        runBlocking { index.search(query) }.hits.map { it.session.id }

    @Test
    fun everyTermMustMatchSomewhere() {
        // "AGP" and "矩阵" co-occur only in s-matrix (title 矩阵 / preview AGP) and
        // s-agp (both in the title). Neither term alone would give this exact pair, so a
        // broken OR would show up as extra ids here.
        //
        // Order is s-matrix first: `titleMatched` means "at least one term hit the title"
        // (SearchIndex.kt:98), and *both* rows have a title hit via 矩阵 — so the next key,
        // pinned, decides and s-matrix is pinned. Ranking does not prefer a title that holds
        // *all* terms (noted as a limitation in RESULT.md, not changed here).
        assertEquals(listOf("s-matrix", "s-agp"), ids("AGP 矩阵"))

        // s-rfc0004 mentions 附件 but not AGP; every AGP row lacks 附件 => AND gives nothing.
        assertEquals(emptyList(), ids("AGP 附件"))

        // Whitespace is just a separator, including leading / trailing / doubled spaces.
        assertEquals(ids("AGP 矩阵"), ids("   AGP   矩阵  "))
    }

    @Test
    fun matchingIgnoresCase() {
        assertEquals(listOf("s-agp", "s-matrix"), ids("agp"))
        assertEquals(listOf("s-agp", "s-matrix"), ids("AgP"))
        assertEquals(listOf("s-wasm"), ids("WASM"))
        assertEquals(listOf("s-wasm"), ids("wasm"))
        // Chinese has no case to fold, but the mixed query still matches on both terms.
        assertEquals(listOf("s-agp"), ids("agp 矩阵验证"))
    }

    @Test
    fun titleHitsRankBeforePreviewHits() {
        val hits = runBlocking { index.search("AGP") }.hits
        assertEquals(listOf("s-agp", "s-matrix"), hits.map { it.session.id })
        // s-agp matches in the title; s-matrix only in the preview.
        assertTrue(hits[0].titleMatched, "AGP 9.5.1 矩阵验证 carries AGP in its title")
        assertEquals(listOf(0..2), hits[0].titleRanges)
        assertFalse(hits[1].titleMatched, "vade core 构建矩阵升级 has no AGP in the title")
        assertEquals(emptyList(), hits[1].titleRanges)
        // …and the title hit wins even though s-matrix is *pinned* and the ranking would
        // otherwise favour pinning: title match is compared first.
        assertTrue(FakeSessions.all.first { it.id == "s-matrix" }.pinned)
        assertFalse(FakeSessions.all.first { it.id == "s-agp" }.pinned)
    }

    @Test
    fun noMatchIsAnAnswerNotAnEmptyScope() {
        val result = runBlocking { index.search("zzzz-not-a-session") }
        assertEquals(emptyList(), result.hits)
        // The degradation label needs this number: "本机已缓存 6 个会话".
        assertEquals(FakeSessions.all.size, result.indexed)
        assertEquals(SearchScope.Cached, result.scope)
        assertEquals("范围：已缓存", result.scope.label)
    }

    @Test
    fun blankQueryScansNothing() {
        assertEquals(emptyList(), searchTerms(""))
        assertEquals(emptyList(), searchTerms("   \t\n "))
        assertEquals(listOf("a", "b"), searchTerms("  a \t b "))
        val result = runBlocking { index.search("   ") }
        assertEquals(emptyList(), result.hits)
        assertEquals(FakeSessions.all.size, result.indexed)
    }

    @Test
    fun overlongQueryIsRefusedBeforeAnyScan() {
        // The index itself stays bounded (it just finds nothing), but the *state* refuses to
        // run above SEARCH_MAX_CHARS — that refusal is what the overlay renders.
        val atLimit = "x".repeat(SEARCH_MAX_CHARS)
        val pastLimit = "x".repeat(SEARCH_MAX_CHARS + 1)
        assertEquals(emptyList(), ids(atLimit))

        val state = SearchState()
        state.open()
        state.updateQuery(atLimit)
        assertEquals(SearchPhase.Ready, state.phase)
        assertFalse(state.tooLong)

        state.updateQuery(pastLimit)
        assertEquals(SearchPhase.TooLong, state.phase)
        assertTrue(state.tooLong)
        assertTrue(state.results.isEmpty())
        assertFalse(state.executed, "an over-long query must not claim a completed search")
        // Simulate the debounced runner's branch for a non-Ready phase.
        state.clearResults(FakeSessions.all.size)
        assertTrue(state.results.isEmpty())
        assertEquals(FakeSessions.all.size, state.indexedCount)
    }

    @Test
    fun highlightRangesPointAtTheOriginalText() {
        // Case-insensitive search must not shift the highlight: indices address the source
        // string as typed, which is what `UiText(AnnotatedString)` styles.
        val title = "AGP 9.5.1 矩阵验证"
        assertEquals(listOf(0..2), matchRanges(title, "agp"))
        assertEquals("AGP", title.substring(0, 3))
        // Second and third occurrences found, first skipped past correctly.
        assertEquals(listOf(0..1, 3..4, 9..10), matchRanges("UP up 升级 up", "up"))
        // Overlapping ranges are coalesced so two SpanStyles never fight.
        assertEquals(listOf(0..5), mergeRanges(listOf(0..2, 2..5)))
        assertEquals(listOf(0..1, 4..5), mergeRanges(listOf(4..5, 0..1)))
    }

    // --- state: open → type → close → reopen --------------------------------------

    @Test
    fun reopeningKeepsQuerySelectionAndScroll() {
        val search = SearchState()
        search.open()
        search.updateQuery("AGP")
        search.applyResults(runBlocking { index.search("AGP") })
        assertEquals(2, search.results.size)

        search.moveSelection(1)
        search.saveScroll(index = 1, offset = 42)
        val selected = search.selected

        search.close()
        assertFalse(search.open)
        // Nothing is cleared by closing — that is the whole acceptance criterion.
        assertEquals("AGP", search.query)
        assertEquals(2, search.results.size)
        assertEquals(selected, search.selected)
        assertEquals(1, search.scrollIndex)
        assertEquals(42, search.scrollOffset)

        search.open()
        assertTrue(search.open)
        assertEquals("AGP", search.query)
        assertEquals(2, search.results.size)
        assertEquals(selected, search.selected)
        assertEquals(1, search.scrollIndex)
        assertEquals(42, search.scrollOffset)
        assertEquals("s-matrix", search.currentHit()?.session?.id)
    }

    @Test
    fun aChangedQueryStartsFromTheTopButTheSameQueryDoesNot() {
        val search = SearchState()
        search.open()
        search.updateQuery("AGP")
        search.applyResults(runBlocking { index.search("AGP") })
        search.moveSelection(1)
        search.saveScroll(1, 42)

        // Same text: no reset (this is what a recomposition / re-emit looks like).
        search.updateQuery("AGP")
        assertEquals(1, search.selected)
        assertEquals(1, search.scrollIndex)

        // Same *trimmed* query, extra trailing space: still the same result set.
        search.updateQuery("AGP ")
        assertEquals(1, search.selected)

        // A genuinely different query resets selection and scroll.
        search.updateQuery("wasm")
        assertEquals(0, search.selected)
        assertEquals(0, search.scrollIndex)
        assertEquals(0, search.scrollOffset)
    }

    @Test
    fun phasesFollowTheThreeCharacterTrigger() {
        val search = SearchState()
        assertEquals(SearchPhase.Empty, search.phase)
        assertEquals(SEARCH_MIN_CHARS, search.missingChars)

        search.updateQuery("a")
        assertEquals(SearchPhase.TooShort, search.phase)
        assertEquals(SEARCH_MIN_CHARS - 1, search.missingChars)

        search.updateQuery("agp")
        assertEquals(SearchPhase.Ready, search.phase)
        assertEquals(0, search.missingChars)

        search.updateQuery("   ")
        assertEquals(SearchPhase.Empty, search.phase, "a whitespace-only query is empty, not searchable")
    }

    @Test
    fun selectionStaysInsideTheListWhenResultsShrink() {
        val search = SearchState()
        search.open()
        search.updateQuery("AGP")
        search.applyResults(runBlocking { index.search("AGP") })
        search.moveSelection(1)
        assertEquals(1, search.selected)

        // Narrower query → one hit; the old selection must be clamped, not orphaned.
        search.updateQuery("AGP 矩阵验证")
        search.applyResults(runBlocking { index.search("AGP 矩阵验证") })
        assertEquals(1, search.results.size)
        assertEquals(0, search.selected)

        search.clearResults(0)
        assertNull(search.currentHit())
        assertEquals(0, search.selected)
    }

    // --- key routing through the real handler -------------------------------------

    private fun uiState() = UiState(BottomBarStyle.Fixed, "material", Destination.Sessions)

    /**
     * Real `KeyEvent`s, built with the only constructor Compose desktop offers.
     *
     * `KeyEvent(key = …)` is annotated `@InternalComposeUiApi` ("unstable API for use only
     * between compose-ui modules") and the AWT bridge `toComposeEvent()` is `internal` to the
     * compose-ui module, so a test that wants the *real* handler has to opt in here. The
     * handler under test touches none of it — it only reads `type`, `key`, `isCtrlPressed`
     * and `isMetaPressed`, all public.
     */
    @OptIn(InternalComposeUiApi::class)
    private fun keyDown(key: Key, ctrl: Boolean = false, meta: Boolean = false): KeyEvent = KeyEvent(
        key = key,
        type = KeyEventType.KeyDown,
        isCtrlPressed = ctrl,
        isMetaPressed = meta,
    )

    @OptIn(InternalComposeUiApi::class)
    private fun keyUp(key: Key): KeyEvent = KeyEvent(key = key, type = KeyEventType.KeyUp)

    /** Opens the palette through the handler and fills it the way the input field would. */
    private fun openedPalette(query: String = "AGP"): UiState {
        val state = uiState()
        assertTrue(handleShellKeys(keyDown(Key.K, meta = true), state), "⌘K must be consumed")
        assertTrue(state.search.open)
        state.updateSearchQuery(query)
        state.search.applyResults(runBlocking { index.search(query) })
        return state
    }

    @Test
    fun metaKOpensAndTogglesThePalette() {
        val state = uiState()
        assertFalse(state.search.open)

        assertTrue(handleShellKeys(keyDown(Key.K, meta = true), state))
        assertTrue(state.search.open)

        // Second press closes instead of restacking (FR-8.74).
        assertTrue(handleShellKeys(keyDown(Key.K, meta = true), state))
        assertFalse(state.search.open)
    }

    @Test
    fun ctrlKIsTheSameShortcutOnNonAppleKeyboards() {
        val state = uiState()
        assertTrue(handleShellKeys(keyDown(Key.K, ctrl = true), state))
        assertTrue(state.search.open)

        // A bare K is ordinary typing and must reach the focused field.
        assertFalse(handleShellKeys(keyDown(Key.K), state))
        assertTrue(state.search.open, "an unmodified K must not toggle the palette")
    }

    @Test
    fun escapeClosesThePalette() {
        val state = openedPalette()
        assertTrue(handleShellKeys(keyDown(Key.Escape), state))
        assertFalse(state.search.open)
        // Reopening lands on the same query (close ≠ reset).
        assertEquals("AGP", state.search.query)
        assertEquals(2, state.search.results.size)
    }

    @Test
    fun arrowsMoveTheSelectionAndWrap() {
        val state = openedPalette()
        assertEquals(0, state.search.selected)

        assertTrue(handleShellKeys(keyDown(Key.DirectionDown), state))
        assertEquals(1, state.search.selected)

        assertTrue(handleShellKeys(keyDown(Key.DirectionDown), state))
        assertEquals(0, state.search.selected, "↓ past the last row wraps to the first")

        assertTrue(handleShellKeys(keyDown(Key.DirectionUp), state))
        assertEquals(1, state.search.selected, "↑ past the first row wraps to the last")
    }

    @Test
    fun enterOpensTheSelectedSession() {
        val state = openedPalette()
        handleShellKeys(keyDown(Key.DirectionDown), state)
        val expected = state.search.results[1].session.id

        assertTrue(handleShellKeys(keyDown(Key.Enter), state))
        assertEquals(expected, state.selectedSessionId)
        assertFalse(state.search.open, "opening a hit closes the overlay")
        assertEquals(Destination.Sessions, state.dest)
    }

    @Test
    fun enterWithNothingSelectedPassesThrough() {
        val state = uiState()
        state.openSearch()
        state.updateSearchQuery("a") // TooShort → no rows
        assertTrue(state.search.results.isEmpty())

        assertFalse(handleShellKeys(keyDown(Key.Enter), state), "no hit to open ⇒ not consumed")
        assertNull(state.selectedSessionId)
        assertTrue(state.search.open, "the palette stays up")
    }

    @Test
    fun escapeClosesAnOpenSessionOnlyWhenThePaletteIsClosed() {
        val state = uiState()
        state.open("s-u1a")
        // Palette closed + a session open: Esc is the back affordance (FR-8.72 layering).
        assertTrue(handleShellKeys(keyDown(Key.Escape), state))
        assertNull(state.selectedSessionId)

        // Nothing open at all: Esc is not ours.
        assertFalse(handleShellKeys(keyDown(Key.Escape), state))
    }

    @Test
    fun keyUpAndUnrelatedKeysAreNotConsumed() {
        val state = uiState()
        assertFalse(handleShellKeys(keyUp(Key.K), state), "only KeyDown drives shortcuts")
        assertFalse(state.search.open)

        assertFalse(handleShellKeys(keyDown(Key.A), state))
        assertFalse(handleShellKeys(keyDown(Key.DirectionDown), state), "arrows outside search are not ours")
        assertFalse(handleShellKeys(keyUp(Key.Escape), state), "Esc on key-up is not a second close")
    }
}
