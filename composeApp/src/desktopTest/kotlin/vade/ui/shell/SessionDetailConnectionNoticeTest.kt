package vade.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import vade.ui.chat.ChatPreset
import vade.ui.data.RealSessionState
import vade.ui.data.SessionSummary
import vade.ui.data.TimeBucket
import vade.ui.destinations.SessionDetailScreen
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin

/**
 * T1 · 会话中段（不是设置页）也有连接态提示。
 *
 * 断线时 ChatThread 顶部的 [ConnectionNotice] 必须可见、带退避提示；恢复 Connected 后
 * 节点**完全退出组合**（不是隐藏文案），因此正常会话的布局/动画不变。
 */
@OptIn(ExperimentalTestApi::class)
class SessionDetailConnectionNoticeTest {

    private val session = SessionSummary(
        id = "s-conn",
        title = "连接态会话",
        preview = "正在重连",
        bucket = TimeBucket.Today,
        updatedAt = 1_000L,
        updatedLabel = "刚刚",
    )

    @Test
    fun reconnectingShowsAMidChatNoticeWithBackoffAndClearsWhenHealthy() = runComposeUiTest {
        var status by mutableStateOf(connectionStatusOf(RealSessionState.Reconnecting(attempt = 2, delayMs = 1_000)))
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                Box(Modifier.size(480.dp, 800.dp)) {
                    SessionDetailScreen(
                        session = session,
                        preset = ChatPreset.Interrupted,
                        animate = false,
                        showTitle = false,
                        connection = status,
                    )
                }
            }
        }

        onNodeWithTag(ConnectionNoticeTag).assertExists()
        onNodeWithText("重连中 1s").assertExists()
        onNodeWithText("第 2 次尝试失败，1s 后自动重试").assertExists()
        onNodeWithContentDescription("重连中", substring = true).assertExists()
        onAllNodesWithText("已连接").assertCountEquals(0)

        // 恢复正常：横幅不组合节点，屏上不再有连接态文案。
        runOnIdle { status = connectionStatusOf(RealSessionState.Connected) }
        waitForIdle()
        onAllNodesWithTag(ConnectionNoticeTag).assertCountEquals(0)
        onAllNodesWithText("重连中 1s").assertCountEquals(0)
    }

    @Test
    fun offlineShowsTheNoticeToo() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                Box(Modifier.size(480.dp, 800.dp)) {
                    SessionDetailScreen(
                        session = session,
                        preset = ChatPreset.Interrupted,
                        animate = false,
                        showTitle = false,
                        connection = connectionStatusOf(RealSessionState.Disconnected),
                    )
                }
            }
        }
        onNodeWithTag(ConnectionNoticeTag).assertExists()
        onNodeWithText("离线").assertExists()
        onNodeWithContentDescription("离线：未连接 core").assertExists()
    }
}
