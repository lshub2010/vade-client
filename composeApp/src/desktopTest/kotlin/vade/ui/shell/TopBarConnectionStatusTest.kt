package vade.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue
import vade.ui.data.RealSessionState
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin

/**
 * T1 · 顶栏连接徽标的**可见性与语义**（真实组合，非纯函数）：
 *
 *  1. 三态（已连接 / 重连中带退避 / 离线）各自在顶栏出现，读屏拿得到完整 contentDescription；
 *  2. **重连态不误报「已连接」** —— 语义树里既没有「已连接」文本，描述也不是它；
 *  3. 窄屏只画圆点：徽标宽度 ≤ 24dp，标题仍保有 ≥150dp 的横向空间（不被长文案挤压）。
 *
 * 密度固定 1f，dp 与语义树 px 一一对应。
 */
@OptIn(ExperimentalTestApi::class)
class TopBarConnectionStatusTest {

    private val LONG_TITLE = "一个足够长的会话标题用来验证窄屏不会被状态文案挤压"

    @Composable
    private fun host(width: Dp, status: ConnectionStatus?) {
        CompositionLocalProvider(
            LocalSkin provides MaterialSkin(),
            LocalDensity provides Density(1f),
        ) {
            Box(
                Modifier
                    .width(width)
                    .height(90.dp)
                    .background(Color(0xFF141414)),
            ) {
                TopBar(
                    title = LONG_TITLE,
                    animate = false,
                    connection = status,
                )
            }
        }
    }

    @Test
    fun reconnectingAndOfflineAreVisibleAndNeverSayConnected() = runComposeUiTest {
        var status by mutableStateOf(connectionStatusOf(RealSessionState.Connected))
        setContent { host(700.dp, status) }

        // 健康态：绿点 + 短文案，读屏一句话说清。
        onNodeWithTag(TopBarConnectionTag).assertExists()
        onNodeWithText("已连接").assertExists()
        onNodeWithContentDescription("已连接 core").assertExists()

        // 重连态：带次数与退避；任何地方都不许再出现「已连接」。
        runOnIdle { status = connectionStatusOf(RealSessionState.Reconnecting(attempt = 2, delayMs = 1_500)) }
        waitForIdle()
        onNodeWithText("重连中 1.5s").assertExists()
        // 顶栏只放短文案：第二行 detail 属于会话中段横幅，不在 56dp 胶囊里挤成两行。
        onAllNodesWithText("第 2 次尝试失败，1.5s 后自动重试").assertCountEquals(0)
        onNodeWithContentDescription("重连中", substring = true).assertExists()
        onAllNodesWithText("已连接").assertCountEquals(0)

        // 离线：红色离线，描述同样不得包含「已连接」。
        runOnIdle { status = connectionStatusOf(RealSessionState.Disconnected) }
        waitForIdle()
        onNodeWithText("离线").assertExists()
        onNodeWithContentDescription("离线：未连接 core").assertExists()
        onAllNodesWithText("已连接").assertCountEquals(0)
    }

    /**
     * 顶栏只放短文案 —— 第二行 detail 属于会话中段横幅，不应在 56dp 胶囊里挤成两行。
     * 用「不存在」钉住这条分工（防止有人把 detail 直接塞进徽标）。
     */
    @Test
    fun narrowTopBarKeepsTheTitleInsteadOfSqueezingItForTheLabel() = runComposeUiTest {
        setContent { host(320.dp, connectionStatusOf(RealSessionState.Connected)) }

        val titleWidth = onNodeWithText(LONG_TITLE).fetchSemanticsNode().boundsInRoot.width
        val badgeWidth = onNodeWithTag(TopBarConnectionTag).fetchSemanticsNode().boundsInRoot.width
        assertTrue(titleWidth >= 150f, "窄屏标题被状态徽标挤压：titleWidth=$titleWidth")
        assertTrue(badgeWidth <= 24f, "窄屏徽标应只剩圆点（≤24dp），实际 badgedWidth=$badgeWidth")

        // 视觉压缩不影响读屏：圆点仍带完整描述。
        onNodeWithContentDescription("已连接 core").assertExists()
        onAllNodesWithText("已连接").assertCountEquals(0)
    }

    @Test
    fun wideTopBarShowsTheShortLabelInAdditionToTheDot() = runComposeUiTest {
        setContent { host(700.dp, connectionStatusOf(RealSessionState.Connected)) }
        onNodeWithText("已连接").assertExists()
        onNodeWithContentDescription("已连接 core").assertExists()
    }
}
