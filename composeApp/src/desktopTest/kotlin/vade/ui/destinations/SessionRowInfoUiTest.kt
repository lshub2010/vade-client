package vade.ui.destinations

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import vade.ui.data.SessionSummary
import vade.ui.data.TimeBucket
import vade.ui.data.sessionGroups
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * J2（alpha 审查）：行内只剩标题 —— 未读、置顶、时间全不画，同名会话无法区分。
 *
 * 裁决后的行规格：**行高 56dp**，信息顺序 **未读/置顶 > 标题 > 时间**。这里逐项量：
 *  1. 行节点（`sessionRowTag`）的高度恰好 56dp（density 固定 1f ⇒ px == dp）；
 *  2. 置顶图标与未读圆点的**右缘**都不越过标题左缘，时间的**左缘**在标题之后；
 *  3. 时间贴行尾（右缘 = 块右缘 - 12dp 内容内边距）；
 *  4. 没有时间标签（真源缺 `updated_at`）时，行里**不多一个空文本节点**。
 *
 * 为什么量像素而不是数节点：Q2-B 那根被废除的 3dp 侧条就是「节点在、像素 0 高」的先例
 * （`SessionRowSelectedBlockTest` 的教训）。
 */
@OptIn(ExperimentalTestApi::class)
class SessionRowInfoUiTest {

    private fun ComposeUiTest.screen(session: SessionSummary) {
        setContent {
            CompositionLocalProvider(
                LocalSkin provides MaterialSkin(),
                // 1dp = 1px：断言里的数字与 dp 一一对应。
                LocalDensity provides Density(1f),
            ) {
                Box(Modifier.size(320.dp, 200.dp)) {
                    SessionsScreen(
                        groups = sessionGroups(listOf(session)),
                        selectedId = null,
                        twoPane = false,
                        onOpen = {},
                        status = ListStatus.Connected,
                    )
                }
            }
        }
        waitForIdle()
    }

    private fun rowBounds(id: String): Rect = onNodeWithTag(sessionRowTag(id)).fetchSemanticsNode().boundsInRoot

    // ------------------------------------------------------------- 1 · 行高 56dp

    @Test
    fun theRowIs56DpTall() = runComposeUiTest {
        screen(row())
        val bounds = rowBounds(ID)
        assertEquals(
            56f,
            bounds.height,
            "行高必须是 56dp（J2 裁决）。实测 ${bounds.height}dp 高的块",
        )
        assertEquals(56.dp, SESSION_ROW_HEIGHT, "常量也必须还是 56dp（改数字要同时改测试）")
    }

    // --------------------------------------------- 2 · 未读/置顶 > 标题 > 时间

    @Test
    fun pinnedAndUnreadSitBeforeTheTitleAndTimeSitsAfterIt() = runComposeUiTest {
        screen(row(pinned = true, unread = true))

        val row = rowBounds(ID)
        val title = onNodeWithText(TITLE, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val pinned = onNodeWithContentDescription(PINNED_BADGE_LABEL, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val unread = onNodeWithContentDescription(UNREAD_BADGE_LABEL, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val time = onNodeWithText(TIME_LABEL, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

        assertTrue(pinned.right <= title.left, "置顶图标必须在标题之前：置顶=${pinned} 标题=${title}")
        assertTrue(unread.left >= pinned.right, "未读点在置顶之后：未读=${unread} 置顶=${pinned}")
        assertTrue(unread.right <= title.left, "未读点必须在标题之前：未读=${unread} 标题=${title}")
        assertTrue(time.left >= title.left, "时间必须在标题之后：时间=${time} 标题=${title}")
        assertTrue(time.left > row.center.x, "时间要贴行尾（右半边），实测左缘 ${time.left} < 块中心 ${row.center.x}")
        assertTrue(
            abs(time.right - (row.right - 12f)) <= 1f,
            "时间右缘必须贴到块右缘 - 12dp 内边距（J2 行尾时间）：时间右缘 ${time.right}，期望 ${row.right - 12f}",
        )
    }

    @Test
    fun aPinnedRowCarriesThePinLabelAndAPlainRowDoesNot() = runComposeUiTest {
        screen(row(pinned = true))
        assertEquals(1, onAllNodesWithContentDescription(PINNED_BADGE_LABEL).fetchSemanticsNodes().size)
    }

    @Test
    fun anUnreadRowCarriesTheUnreadLabelAndAReadRowDoesNot() = runComposeUiTest {
        screen(row(unread = false))
        assertEquals(
            0,
            onAllNodesWithContentDescription(UNREAD_BADGE_LABEL).fetchSemanticsNodes().size,
            "已读行不许有未读点",
        )
    }

    // ------------------------------------- 3 · 没有时间就不画（不编一个空节点）

    @Test
    fun aRowWithoutATimeLabelAddsNoEmptyTextNode() = runComposeUiTest {
        screen(row(updatedLabel = ""))

        val texts = onNodeWithTag(sessionRowTag(ID)).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text)
            ?.map { it.text }
            .orEmpty()

        assertEquals(
            listOf(TITLE),
            texts,
            "缺 updated_at 的行只该有标题一段文字（空串会占一个文本节点，等于凭空多一段空白）",
        )
    }

    @Test
    fun theTimeLabelIsTheOneFromTheRowModel() = runComposeUiTest {
        screen(row(updatedLabel = "昨天 19:12"))
        assertTrue(
            onAllNodesWithText("昨天 19:12").fetchSemanticsNodes().isNotEmpty(),
            "行上的时间必须原样来自 SessionSummary.updatedLabel（不在这里重算）",
        )
    }

    // ------------------------------------------------------------------ 工具

    private fun row(
        pinned: Boolean = false,
        unread: Boolean = false,
        updatedLabel: String = TIME_LABEL,
    ): SessionSummary = SessionSummary(
        id = ID,
        title = TITLE,
        preview = "",
        bucket = TimeBucket.Today,
        updatedAt = 1_700_000_000_000L,
        updatedLabel = updatedLabel,
        pinned = pinned,
        unread = unread,
    )

    private companion object {
        const val ID = "s-row-info"
        const val TITLE = "J2 行内信息"
        const val TIME_LABEL = "3 天前"
    }
}
