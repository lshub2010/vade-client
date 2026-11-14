package vade.ui.destinations

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vade.ui.UiText
import vade.ui.skin.LocalSkin

/**
 * 活动 (U1-a skeleton), v2 form.
 *
 * D87 gives this destination four blocks: running turns, pending approvals (with a
 * badge), recent failures, background tasks / sub-agents. RFC-0007 (sub-agents) and
 * RFC-0008 (`waiting_input`) are still proposals, so per task book §九 nothing here may
 * invent an event stream: every block renders its v2 empty state (centered, 48dp icon,
 * 20sp title, 14sp muted body — [EmptyStateCard]) and says which RFC gates the data
 * (U1-d fills them in). Group cards are `md` + hairline + soft shadow ([GroupCard]).
 *
 * [contentPadding] is the shell's floating glass bar zones: passed into the scroll so the
 * page content scrolls behind the glass instead of stopping short of it.
 */
@Composable
fun ActivityScreen(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val skin = LocalSkin.current
    Column(
        modifier
            .fillMaxSize()
            .background(skin.colors.canvas)
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionLabel("运行中")
        EmptyStateCard(
            title = "暂无运行中的 turn",
            body = "U1-d 接入 turn 事件后，这里显示进度、已运行时长与停止入口。",
        )

        SectionLabel("待审批")
        EmptyStateCard(
            glyph = "!",
            title = "暂无待审批",
            body = "审批关闭只认 turn.approval_resolved（FR-8.53）；U1-d 接线前不伪造倒计时，也不插入假的审批卡。",
        )

        SectionLabel("最近失败")
        EmptyStateCard(
            glyph = "×",
            title = "暂无失败记录",
            body = "失败 turn 与失败工具卡会在这里汇总（U1-d）。",
        )

        SectionLabel("后台任务 · 子代理")
        EmptyStateCard(
            glyph = "⧗",
            title = "等待 RFC-0007",
            body = "子代理 / 后台任务协议未批准，本切片只留容器与空态，不接假事件流。",
        )
        Spacer(Modifier.padding(bottom = 8.dp))
    }
}

/** 11sp muted section label above each group card — the only chrome between cards. */
@Composable
internal fun SectionLabel(text: String) {
    UiText(text, size = 11, weight = FontWeight.SemiBold, color = LocalSkin.current.colors.onSurfaceVariant)
}

/** Placeholder kept so the file compiles before U1-d grows real sections. */
@Composable
internal fun ActivitySpacer() {
    Spacer(Modifier.padding(0.dp))
}
