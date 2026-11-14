package vade.ui.skin.miuix

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text
import vade.ui.skin.ApprovalRequest
import vade.ui.skin.AttachmentState
import vade.ui.skin.AttachmentView
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.BtnStyle
import vade.ui.skin.BubbleRole
import vade.ui.skin.Decision
import vade.ui.skin.InputMode
import vade.ui.skin.MessageAction
import vade.ui.skin.MessageView
import vade.ui.skin.NavItem
import vade.ui.skin.Scope
import vade.ui.skin.ToolCallView
import vade.ui.skin.ToolCardState

/**
 * Screenshot / review surface for the miuix skin.
 *
 * WHY THIS EXISTS: U1-b (the chat page) is not built yet, so the shipped app has no screen
 * that composes `MessageBubble` / `ToolCallCard` / `InputBar`. The task book §T4 requires
 * every acceptance shot to show "聊天页（有消息 + 工具卡片）、底栏、输入框", so the skin
 * ships its own page that composes **the real C-09 components** — nothing here is a drawing,
 * every pixel comes from the same `MiuixSkin` methods the future chat page will call.
 *
 * Reached with `--showcase` on the desktop entry point; it is not wired into `App()` and is
 * not a business page (no `destinations/` file, no state machine).
 */
@Composable
fun MiuixShowcasePage(
    skin: MiuixSkin,
    bar: BottomBarStyle = BottomBarStyle.Fixed,
    showApproval: Boolean = false,
) {
    val c = skin.colors
    var selected by remember { mutableStateOf(0) }
    var draft by remember { mutableStateOf("把 AGP 版本一起升到 9.5.1，然后重跑三端") }
    var toolExpanded by remember { mutableStateOf(true) }
    // Desktop hands focus to the first focusable node (the composer) the moment the window
    // activates, so the at-rest 1 dp hairline was never what a screenshot saw — every capture
    // showed the focused 2 dp accent ring and read as "the ring is always on". Dropping the
    // initial focus once makes the resting state the state that gets photographed.
    val focus = LocalFocusManager.current
    LaunchedEffect(Unit) {
        // Two passes: the first runs before the window is activated (desktop assigns focus on
        // activation), the second lands after it, so the resting state is what gets captured.
        delay(600)
        focus.clearFocus()
    }

    val nav = listOf(
        // w-g-icons：底栏图标由 skin.BottomBar 按 id 映射 VadeIcons 矢量；NavItem.glyph 不再喂。
        NavItem("sessions", "会话"),
        NavItem("activity", "活动", badge = "2"),
        NavItem("capabilities", "能力"),
        NavItem("me", "我的"),
    )

    Box(Modifier.fillMaxSize().background(c.surfaceVariant)) {
        Column(Modifier.fillMaxSize()) {
            // Header assembled from Card + Button (C-09 has no TopBar, see Skin.kt).
            skin.Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("构建矩阵核对", fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = c.onSurface)
                        Text("miuix · chat showcase", fontSize = MiuixTokens.tinySize, color = c.onSurfaceVariant)
                    }
                    // w-g-icons：顶栏按钮用真实文案。`skin.Button` 是冻结的 String 签名、
                    // 无法携带 ImageVector，而字符图标（"⋯"/"↑"）被 v2 §3 明令禁止——
                    // showcase 演示的是 Button 的样式本身，真文案不损其目的。
                    skin.Button("更多", onClick = {}, style = BtnStyle.Text)
                    Spacer(Modifier.width(6.dp))
                    skin.Button("发送", onClick = {}, style = BtnStyle.Primary)
                }
            }

            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                skin.MessageBubble(
                    message = MessageView("m0", "AGP 9.5.1 + Gradle 9.6.1 + compileSdk 37 这条组合我已经重跑过，Android 能出 APK。"),
                    role = BubbleRole.Assistant,
                    maxWidth = 760.dp,
                    actions = listOf(MessageAction("copy", "复制"), MessageAction("retry", "重试")),
                    onAction = {},
                )
                skin.MessageBubble(
                    message = MessageView("m1", "那把两个 deprecated 开关也一起记录，AGP 10 会移除它们。"),
                    role = BubbleRole.User,
                    maxWidth = 760.dp,
                )
                skin.MessageBubble(
                    message = MessageView("m2", "上下文已截断：只保留最近 20 条", badges = listOf("已压缩") ),
                    role = BubbleRole.System,
                    maxWidth = 760.dp,
                )

                // Tool card, expanded (the shot the task book asks for).
                Box(Modifier.widthIn(max = 760.dp)) {
                    skin.ToolCallCard(
                        call = ToolCallView(
                            id = "t1",
                            name = "fs.write",
                            args = """{"path": "composeApp/build.gradle.kts", "mode": "patch"}""",
                            result = "ok · 6 lines changed, 0 warnings",
                            risk = "medium",
                        ),
                        state = if (toolExpanded) ToolCardState.Expanded else ToolCardState.Collapsed,
                        onToggle = { toolExpanded = !toolExpanded },
                    )
                }
                Box(Modifier.widthIn(max = 760.dp)) {
                    skin.ToolCallCard(
                        call = ToolCallView(
                            id = "t2",
                            name = "shell.exec",
                            args = """{"cmd": "gradle :composeApp:assembleDebug"}""",
                            result = "exit 1 · checkDebugAarMetadata: requires compileSdk 37",
                            risk = "high",
                            isError = true,
                        ),
                        state = ToolCardState.PendingApproval,
                        onToggle = {},
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    skin.AttachmentChip(
                        attachment = AttachmentView(
                            fileId = "f1",
                            fileName = "screenshot-480x900.png",
                            mime = "image/png",
                            state = AttachmentState.Uploaded,
                        ),
                        onRemove = {},
                    )
                    skin.AttachmentChip(
                        attachment = AttachmentView(
                            fileId = "f2",
                            fileName = "build.log",
                            mime = "text/plain",
                            state = AttachmentState.Uploading,
                            progress = 0.42f,
                        ),
                        onRemove = {},
                    )
                }

                // Component matrix strip: the three input states + switch + list rows.
                skin.Card(Modifier.fillMaxWidth().widthIn(max = 760.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("组件矩阵", fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = c.onSurfaceVariant)
                        skin.TextField(value = "", onValueChange = {}, placeholder = "默认态")
                        skin.TextField(value = "not-a-sdk-path", onValueChange = {}, placeholder = "错误态", error = "compileSdk 必须是数字")
                        skin.TextField(value = "disabled", onValueChange = {}, placeholder = "禁用态", enabled = false)
                        skin.ListItem(
                            title = "启用 floating 底栏",
                            subtitle = "外观设置 · 立即生效",
                            trailing = { skin.Switch(checked = true, onCheckedChange = {}) },
                            onClick = {},
                        )
                        // w-g-icons：五种 BtnStyle 用真实文案演示。字符图标（"↑"/"＋"/"✕"/"⋯"）
                        // 被 v2 §3 禁止，而 Button 的 String 签名装不下矢量——真文案演示按钮
                        // 样式不损目的，11sp 说明文字只是标签（不是按钮）。
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            listOf(
                                "发送" to BtnStyle.Primary,
                                "添加" to BtnStyle.Secondary,
                                "删除" to BtnStyle.Danger,
                                "更多" to BtnStyle.Text,
                            ).forEach { (label, style) ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    skin.Button(label, {}, style)
                                    Text(
                                        when (style) {
                                            BtnStyle.Primary -> "主"
                                            BtnStyle.Secondary -> "次"
                                            BtnStyle.Danger -> "危"
                                            BtnStyle.Text -> "文"
                                        },
                                        fontSize = MiuixTokens.tinySize,
                                        color = c.onSurfaceVariant,
                                    )
                                }
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                skin.Button("添加", {}, BtnStyle.Secondary, enabled = false)
                                Text("禁", fontSize = MiuixTokens.tinySize, color = c.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            // D88 composer + the bar under test. RFC-0011: the send/stop ball is the
            // separate `SendOrb` (C-09 component 12) composed beside the field — this row
            // is exactly what a business page writes, minus the page-side ＋ button.
            // InputBar has no modifier param (frozen signature), so the weight sits on a Box.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    skin.InputBar(
                        value = draft,
                        onValueChange = { draft = it },
                        mode = if (draft.isEmpty()) InputMode.Idle else InputMode.Composing,
                        attachments = null,
                        enabled = true,
                        voiceAvailable = false,
                    )
                }
                Spacer(Modifier.width(12.dp))
                skin.SendOrb(
                    mode = if (draft.isEmpty()) InputMode.Idle else InputMode.Composing,
                    onClick = { draft = "" },
                )
            }
            skin.BottomBar(items = nav, selected = selected, onSelect = { selected = it }, style = bar)
        }

        if (showApproval) {
            skin.ApprovalDialog(
                request = ApprovalRequest(
                    id = "a1",
                    toolName = "shell.exec",
                    arguments = """{
  "cmd": "gradle :composeApp:assembleDebug",
  "cwd": "D:/HermesWorkspace/Vade-client-wt/u2-miuix",
  "timeout_s": 900
}""",
                    risk = "high",
                ),
                remainingSec = 43,
                onDecide = { _: Decision, _: Scope -> },
            )
        }
    }
}
