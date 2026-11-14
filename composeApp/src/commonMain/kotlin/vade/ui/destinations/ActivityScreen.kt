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
import vade.ui.icon.VadeIcons
import vade.ui.protocol.SessionUsageGetResult
import vade.ui.protocol.UsageBudgetPayload
import vade.ui.protocol.VadeUsage
import vade.ui.skin.LocalSkin
import vade.ui.subagent.SubagentPanel
import vade.ui.subagent.SubagentRunView
import vade.ui.usage.UsagePanel

/**
 * 活动 (C-Activity), D87 blocks + the two real data panels.
 *
 * The three U1-a placeholder blocks (运行中 / 待审批 / 最近失败) stay empty until U1-d
 * wires turn events — nothing here invents an event stream. The fourth block is now
 **real**: RFC-0012 landed server-side, so 后台任务·子代理 renders [SubagentPanel], and
 * the new 用量 section renders [UsagePanel] from the `session/usage.get` /
 * `turn.completed.usage` / `usage.budget` DTO snapshots.
 *
 * **Injection seam**: the four parameters are the DTO snapshots the shell passes in.
 * Defaults are `null`, meaning "not wired yet" — the panels then render their mandated
 *「暂无数据」empty states and never fabricate numbers. Reaching real data = the data
 * layer calling `VadeClient.sessionUsageGet` / `subagent/list` and passing the decoded
 * DTOs here; zero UI change.
 *
 * [contentPadding] is the shell's floating glass bar zones: passed into the scroll so the
 * page content scrolls behind the glass instead of stopping short of it.
 */
@Composable
fun ActivityScreen(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    /** `session/usage.get` result snapshot; null = 未接线 → 空态. */
    usage: SessionUsageGetResult? = null,
    /** The `usage` object of the most recent `turn.completed` (or failed/interrupted). */
    lastTurnUsage: VadeUsage? = null,
    /** The last `usage.budget` event payload (notification, not the authoritative truth). */
    budgetNotice: UsageBudgetPayload? = null,
    /** Sub-agent runs (`subagent/get`-shaped snapshots); null = 未接线, empty = 真实为空. */
    subagentRuns: List<SubagentRunView>? = null,
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
            icon = VadeIcons.Pending,
            title = "暂无待审批",
            body = "审批关闭只认 turn.approval_resolved（FR-8.53）；U1-d 接线前不伪造倒计时，也不插入假的审批卡。",
        )

        SectionLabel("最近失败")
        EmptyStateCard(
            icon = VadeIcons.Failed,
            title = "暂无失败记录",
            body = "失败 turn 与失败工具卡会在这里汇总（U1-d）。",
        )

        SectionLabel("用量")
        UsagePanel(
            session = usage,
            lastTurn = lastTurnUsage,
            budgetNotice = budgetNotice,
        )

        SectionLabel("后台任务 · 子代理")
        SubagentPanel(runs = subagentRuns)
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
