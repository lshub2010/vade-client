package vade.ui.destinations

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vade.ui.UiText
import vade.ui.data.SessionSummary
import vade.ui.skin.BtnStyle
import vade.ui.skin.LocalSkin

/**
 * 会话 · 详情 — **placeholder only** (U1-a boundary).
 *
 * It exists to prove the navigation contract, not the chat:
 *  - on a phone it is pushed full screen and the bottom bar is hidden (D88) — the
 *    shell does that by not composing the bar when this screen is open;
 *  - on ≥ 720 dp it renders in the right pane of a list+detail pair (D89).
 *
 * The message flow, streaming, tool cards and the input area are U1-b. Nothing below
 * fakes a reply: the placeholders say what will land there and which component owns it.
 */
@Composable
fun SessionDetailScreen(
    session: SessionSummary?,
    modifier: Modifier = Modifier,
) {
    val skin = LocalSkin.current
    Column(modifier = modifier.fillMaxSize().background(skin.colors.surface)) {
        if (session == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                UiText("在左侧选择一个会话", size = 12, color = skin.colors.onSurfaceVariant)
            }
            return
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(skin.shapes.small))
                        .background(skin.colors.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) { UiText(session.title.take(1), size = 14, weight = FontWeight.SemiBold, color = skin.colors.primary) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    UiText(session.title, size = 15, weight = FontWeight.SemiBold)
                    UiText(
                        text = "最后更新 ${session.updatedLabel} · 模型：${session.model ?: "全局默认"}",
                        size = 10,
                        color = skin.colors.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            // --- what U1-b fills in, stated plainly instead of mock chat ---
            skin.Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    UiText("消息流占位", size = 12, weight = FontWeight.SemiBold)
                    UiText(
                        text = "U1-b 在此接 MessageBubble（助手全宽 ≤760dp / 用户右对齐）、" +
                            "Markdown、流式 delta 与 ToolCallCard。",
                        size = 11,
                        color = skin.colors.onSurfaceVariant,
                    )
                    PlaceholderLine(widthFraction = 1f)
                    PlaceholderLine(widthFraction = 0.86f)
                    PlaceholderLine(widthFraction = 0.62f)
                }
            }

            Spacer(Modifier.height(12.dp))
            skin.Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    UiText("输入区占位", size = 12, weight = FontWeight.SemiBold)
                    UiText(
                        text = "U1-b 在此接 InputBar（多行高度 / 发送-停止 / 附件条插槽）；" +
                            "IME 与浮动胶囊不叠压是 D88 的验收项。",
                        size = 11,
                        color = skin.colors.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                skin.Button("← 返回列表", onClick = {}, style = BtnStyle.Text, enabled = false)
                Spacer(Modifier.width(8.dp))
                UiText(
                    text = "返回按钮由顶栏提供（本页仅示意；Esc / 手势属 U1-g）",
                    size = 10,
                    color = skin.colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PlaceholderLine(widthFraction: Float) {
    val skin = LocalSkin.current
    Box(
        Modifier
            .fillMaxWidth(widthFraction)
            .height(10.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(skin.colors.surfaceVariant),
    )
}
