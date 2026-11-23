package vade.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlin.test.Test
import vade.ui.chat.ChatMessage
import vade.ui.chat.ChatPreset
import vade.ui.data.ConnectionStateHost
import vade.ui.data.RealSessionState
import vade.ui.data.SessionSource
import vade.ui.data.SessionSummary
import vade.ui.data.TimeBucket
import vade.ui.data.VadeEvent
import vade.ui.destinations.Destination
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin

/**
 * T1 · **装配**证据：`AppShell` 从 `UiState.source` 的 [ConnectionStateHost] 取连接态，
 * 顶栏与会话中段两条路径都绑到同一份 [RealSessionState] 流。
 *
 * 只在 `TopBar` 单测里画徽标不够 —— 那样即使 AppShell 永远不传 `connection`，测试也会绿
 * （本仓已出现多次「函数写了但没人调用」）。这里用真实 `AppShell` + 900dp 双栏组合，
 * 断言生产入口创建的对象真的把状态传到了两个可见位置。
 */
@OptIn(ExperimentalTestApi::class)
class AppShellConnectionStatusTest {

    private class HostSource : SessionSource, ConnectionStateHost {
        override val connectionState = MutableStateFlow<RealSessionState>(RealSessionState.Connected)

        val row = SessionSummary(
            id = "s-conn-shell",
            title = "装配用会话",
            preview = "连接态",
            bucket = TimeBucket.Today,
            updatedAt = 1_000L,
            updatedLabel = "刚刚",
        )

        override suspend fun sessions(includeArchived: Boolean): List<SessionSummary> = listOf(row)

        override suspend fun messages(sessionId: String): List<ChatMessage> = emptyList()

        override fun observeEvents(): Flow<VadeEvent> = emptyFlow()
    }

    @Test
    fun shellBindsTopBarAndMidChatNoticeToTheSourcesOwnLifecycleFlow() {
        val dispatcher = StandardTestDispatcher()
        val src = HostSource()
        val state = UiState(
            initialBar = BottomBarStyle.Floating,
            initialSkinId = "material",
            initialDest = Destination.Sessions,
            chatPreset = ChatPreset.Interrupted,
            source = src,
            loaderScope = CoroutineScope(dispatcher + SupervisorJob()),
        )
        try {
            runComposeUiTest {
                setContent {
                    CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                    // 900dp = rail + 双栏（列表 + 详情），两种顶栏/详情路径都真实组合。
                        Box(Modifier.size(900.dp, 800.dp)) {
                            AppShell(state)
                        }
                    }
                }
                dispatcher.scheduler.advanceUntilIdle()
                waitForIdle()

                // 真源在线：顶栏有徽标；健康态没有中段横幅（布局零变化）。
                onNodeWithTag(TopBarConnectionTag).assertExists()
                onNodeWithContentDescription("已连接 core").assertExists()
                onAllNodesWithTag(ConnectionNoticeTag).assertCountEquals(0)

                // 打开会话 → 详情屏组合；把同一个源推到重连态。徽标与中段横幅共用同一句
                // 语义描述 ⇒ 精确 2 个节点，正好证明两条可见路径都绑上了。
                runOnIdle {
                    state.open(src.row.id)
                    src.connectionState.value = RealSessionState.Reconnecting(attempt = 3, delayMs = 2_000)
                }
                dispatcher.scheduler.advanceUntilIdle()
                waitForIdle()

                onNodeWithTag(TopBarConnectionTag).assertExists()
                onNodeWithTag(ConnectionNoticeTag).assertExists()
                onAllNodesWithContentDescription("重连中：第 3 次尝试失败，2s 后自动重试")
                    .assertCountEquals(2)
                onAllNodesWithText("第 3 次尝试失败，2s 后自动重试").assertCountEquals(1)
                onAllNodesWithText("已连接").assertCountEquals(0)

                // 掉到离线：两处都改口，仍不许出现「已连接」。
                runOnIdle { src.connectionState.value = RealSessionState.Disconnected }
                dispatcher.scheduler.advanceUntilIdle()
                waitForIdle()
                onNodeWithTag(ConnectionNoticeTag).assertExists()
                onAllNodesWithContentDescription("离线：未连接 core").assertCountEquals(2)
                onAllNodesWithText("已连接").assertCountEquals(0)
            }
        } finally {
            state.dispose()
        }
    }
}
