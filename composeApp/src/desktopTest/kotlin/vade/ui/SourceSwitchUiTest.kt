package vade.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.flow.emptyFlow
import vade.ui.data.SessionSource
import vade.ui.data.SessionSummary
import vade.ui.data.TimeBucket
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * g-live · the UI-level refutation of the stale `Main.kt` comment.
 *
 * The old comment ("UI pages do not READ `source` yet, so this flag changes nothing
 * visible") died with w-wire: `UiState` runs a non-fake source through
 * [vade.ui.shell.SessionsLoader] and the composed tree renders its rows. These tests
 * pin BOTH sides inside a real composition — the default `App()` still serves
 * fixtures, and an injected non-fake source replaces what is on screen. The full
 * real-core chain is `RealCoreUiE2ETest`; this file isolates the UI read.
 */
@OptIn(ExperimentalTestApi::class)
class SourceSwitchUiTest {

    /** A non-fake [SessionSource]: exactly one row with a title no fixture carries. */
    private class OneRowSource : SessionSource {
        override suspend fun sessions(includeArchived: Boolean): List<SessionSummary> = listOf(
            SessionSummary(
                id = "ui-assert-real",
                title = UI_ASSERT_TITLE,
                preview = "g-live · 注入源的一行",
                bucket = TimeBucket.Today,
                updatedAt = System.currentTimeMillis(),
                updatedLabel = "刚刚",
            ),
        )

        override suspend fun messages(sessionId: String): List<vade.ui.chat.ChatMessage> = emptyList()

        override fun observeEvents(): kotlinx.coroutines.flow.Flow<vade.ui.data.VadeEvent> = emptyFlow()
    }

    @Test
    fun appWithoutAnExplicitSourceStillServesTheFixtures() = runComposeUiTest {
        // The default parameter chain (App → UiState → FakeSessionSource) is the
        // regression contract: `App()` with NO source argument shows fixtures.
        setContent { App() }
        onNodeWithText("RFC-0004 附件与多模态评审").assertExists()
    }

    @Test
    fun appReadsTheInjectedSourceSoTheOldNothingVisibleCommentIsStale() = runComposeUiTest {
        setContent { App(source = OneRowSource()) }
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText(UI_ASSERT_TITLE).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText(UI_ASSERT_TITLE).assertExists()
        // The fixtures did not ride along: the loader path replaces the whole list.
        assertTrue(
            onAllNodesWithText("RFC-0004 附件与多模态评审").fetchSemanticsNodes().isEmpty(),
            "fixture row must be gone once a real source drives the list",
        )
    }

    private companion object {
        const val UI_ASSERT_TITLE = "W4-UI 断言·注入源的一行"
    }
}
