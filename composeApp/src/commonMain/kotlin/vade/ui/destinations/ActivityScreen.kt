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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vade.ui.UiText
import vade.ui.skin.LocalSkin

/**
 * 活动 (U1-a skeleton).
 *
 * D87 gives this destination four blocks: running turns, pending approvals (with a
 * badge), recent failures, background tasks / sub-agents. RFC-0007 (sub-agents) and
 * RFC-0008 (`waiting_input`) are still proposals, so per task book §九 nothing here may
 * invent an event stream: the sections render their empty states and say which RFC gates
 * the data (U1-d fills them in).
 */
@Composable
fun ActivityScreen(modifier: Modifier = Modifier) {
    val skin = LocalSkin.current
    Column(modifier.fillMaxSize().background(skin.colors.surface).verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            UiText("运行中", size = 11, weight = FontWeight.SemiBold, color = skin.colors.onSurfaceVariant)
            EmptyCard(
                title = "暂无运行中的 turn",
                body = "U1-d 接入 turn 事件后，这里显示进度、已运行时长与停止入口。",
            )

            UiText("待审批", size = 11, weight = FontWeight.SemiBold, color = skin.colors.onSurfaceVariant)
            skin.Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        UiText("审批队列为静态骨架", size = 12, weight = FontWeight.SemiBold)
                        UiText(
                            text = "弹窗关闭只认 turn.approval_resolved（FR-8.53）；U1-d 接线前不伪造倒计时。",
                            size = 11,
                            color = skin.colors.onSurfaceVariant,
                        )
                    }
                    Box(
                        Modifier.size(18.dp).clip(CircleShape).background(skin.colors.error),
                        contentAlignment = Alignment.Center,
                    ) { UiText("0", size = 9, color = skin.colors.onError) }
                }
            }

            UiText("最近失败", size = 11, weight = FontWeight.SemiBold, color = skin.colors.onSurfaceVariant)
            EmptyCard(title = "暂无失败记录", body = "失败 turn 与失败工具卡会在这里汇总（U1-d）。")

            UiText("后台任务 · 子代理", size = 11, weight = FontWeight.SemiBold, color = skin.colors.onSurfaceVariant)
            EmptyCard(
                title = "等待 RFC-0007",
                body = "子代理 / 后台任务协议未批准，本切片只留容器与空态，不接假事件流。",
            )
        }
    }
}

@Composable
private fun EmptyCard(title: String, body: String) {
    val skin = LocalSkin.current
    skin.Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            UiText(title, size = 12, weight = FontWeight.SemiBold)
            UiText(body, size = 11, color = skin.colors.onSurfaceVariant)
        }
    }
}

/** Placeholder kept so the file compiles before U1-d grows real sections. */
@Composable
internal fun ActivitySpacer() {
    Spacer(Modifier.height(0.dp))
}
