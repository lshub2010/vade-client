package vade.ui.destinations

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import vade.ui.data.FakeSessions
import vade.ui.data.SessionGroup
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * J1（alpha 审查）：0 个会话时**整页空白**，同包早就有 `EmptyStateCard`，只是没接。
 *
 * 这里钉三层：
 *  1. 读完了且真的没有会话（Connected）→ 空态卡 + 「新建会话」按钮，按钮真的回调；
 *  2. 首读还在飞（Connecting）→ **不画**这张卡（那时候说「还没有会话」是假的）；
 *  3. 有行 → 卡不存在（空态不是常驻横幅）。
 *
 * 断言的是用户看得见的文案与点击效果，不量像素 —— 卡的长相归 `GroupCards` 管。
 */
@OptIn(ExperimentalTestApi::class)
class SessionListEmptyStateTest {

    private fun ComposeUiTest.screen(
        groups: List<SessionGroup> = emptyList(),
        status: ListStatus = ListStatus.Connected,
        onNewSession: () -> Unit = {},
    ) {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                Box(Modifier.size(360.dp, 640.dp)) {
                    SessionsScreen(
                        groups = groups,
                        selectedId = null,
                        twoPane = false,
                        onOpen = {},
                        status = status,
                        onNewSession = onNewSession,
                    )
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.emptyCardCount(): Int =
        onAllNodesWithText(SESSIONS_EMPTY_TITLE).fetchSemanticsNodes().size

    // ------------------------------------------------------------------ 1 · 空态真的画

    @Test
    fun anEmptyListThatFinishedLoadingShowsTheEmptyCard() = runComposeUiTest {
        screen(status = ListStatus.Connected)

        onNodeWithText(SESSIONS_EMPTY_TITLE).assertExists()
        onNodeWithText(SESSIONS_EMPTY_BODY).assertExists()
        onNodeWithText(SESSIONS_EMPTY_ACTION).assertExists()
    }

    @Test
    fun theEmptyCardsCreateButtonCallsTheAction() = runComposeUiTest {
        var created = 0
        screen(status = ListStatus.Connected, onNewSession = { created += 1 })

        onNodeWithText(SESSIONS_EMPTY_ACTION).performClick()
        waitForIdle()

        assertEquals(1, created, "空态卡的「新建会话」必须真的接到动作上（不许是死按钮）")
    }

    @Test
    fun theEmptyCardIsNotDrawnWhileTheFirstReadIsStillInFlight() = runComposeUiTest {
        screen(status = ListStatus.Connecting("127.0.0.1:18755"))

        assertEquals(0, emptyCardCount(), "首读在飞时说「还没有会话」是假的 —— 加载中不画空态")
        // 加载中仍有可见文字（不是靠整屏空白通过断言）。
        assertTrue(
            onAllNodesWithText("正在连接 core", substring = true).fetchSemanticsNodes().isNotEmpty(),
            "加载中必须有「正在连接 core …」",
        )
    }

    // ------------------------------------------------------------------ 2 · 不该出现的地方

    @Test
    fun aNonEmptyListHasNoEmptyCard() = runComposeUiTest {
        screen(groups = FakeSessions.groups(), status = ListStatus.Connected)

        assertEquals(0, emptyCardCount(), "有行的列表不许再画空态卡")
        onNodeWithText("RFC-0004 附件与多模态评审").assertExists()
    }

    @Test
    fun theFixtureSourceWithZeroRowsStillExplainsItself() = runComposeUiTest {
        // 默认 status（Fixtures）也要有空态：假源演示列表被清空时，用户同样需要「新建」入口。
        screen(groups = emptyList(), status = ListStatus.Fixtures)

        onNodeWithText(SESSIONS_EMPTY_TITLE).assertExists()
        onNodeWithText(FIXTURE_NOTE).assertExists()
    }

    @Test
    fun aFailedReadKeepsTheCreateEntryReachable() = runComposeUiTest {
        // 读失败时上面的横幅说真话（为什么没读到），这张卡只负责给出用户此刻唯一能做的事。
        screen(
            groups = emptyList(),
            status = ListStatus.Failed(
                headline = "无法连接 core",
                detail = "需要重连（UNAVAILABLE）",
                remedy = "VADE_SOURCE=real VADE_PAIR_CODE=<配对码> gradle :composeApp:run",
            ),
        )

        onNodeWithText("无法连接 core").assertExists()
        onNodeWithText(SESSIONS_EMPTY_ACTION).assertExists()
    }
}
