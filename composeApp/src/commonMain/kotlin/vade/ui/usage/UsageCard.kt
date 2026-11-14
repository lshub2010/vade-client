package vade.ui.usage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vade.ui.UiText
import vade.ui.component.liquid.VadeShadows
import vade.ui.component.liquid.layeredShadow
import vade.ui.destinations.EmptyStateCard
import vade.ui.icon.VadeIcon
import vade.ui.icon.VadeIcons
import vade.ui.protocol.SessionUsageGetResult
import vade.ui.protocol.UsageBudgetPayload
import vade.ui.protocol.VadeUsage
import vade.ui.skin.LocalSkin

/**
 * C-Activity · 用量 panel — the renderer over [projectUsage].
 *
 * Data contract: everything comes in as **real contract-shaped DTOs** the shell passes
 * down (`session/usage.get` result, `turn.completed.usage`, the `usage.budget` event).
 * The composable invents nothing: wherever a field was not delivered, the projection
 * said [TokenCell.NotReported] and the row reads「暂无数据」in muted ink. No fake zeros.
 *
 * Reaching real data later = wiring the data layer to call `VadeClient.sessionUsageGet`
 * and to lift `usage` off `turn.completed` / `usage.budget` events, then passing the
 * DTOs into [UsagePanel]. Zero UI change.
 */
@Composable
fun UsagePanel(
    session: SessionUsageGetResult?,
    lastTurn: VadeUsage?,
    budgetNotice: UsageBudgetPayload?,
    modifier: Modifier = Modifier,
) {
    val projection = projectUsage(session, lastTurn, budgetNotice)
    if (!projection.hasAnyData) {
        EmptyStateCard(
            icon = VadeIcons.Empty,
            title = "暂无数据",
            body = "接入 session/usage.get 与 turn.completed.usage 后，这里显示真实的 token 统计与预算进度；" +
                "未接线前不显示任何占位数字。",
            modifier = modifier,
        )
        return
    }
    PanelCard(modifier) {
        // ---- 降级标识：source=none 时必须明确告知，不许静默或隐藏 ------------------
        val degraded = projection.turn?.degraded == true
        if (degraded) DegradedBanner()

        projection.notice?.let { notice ->
            NoticeRow(notice)
        }

        val turn = projection.turn
        SectionHeader("本轮（最近一个 turn）")
        if (turn == null || !turn.hasData) {
            MutedLine("暂无数据 — turn.completed.usage 尚未接入，不显示占位数字")
        } else {
            UsageRow("输入 tokens", turn.prompt)
            UsageRow("输出 tokens", turn.completion)
            UsageRow("合计 tokens", turn.total)
            UsageRow("命中缓存 tokens", turn.cached)
            UsageRow("推理 tokens", turn.reasoning)
            UsageRow("步数 steps", turn.steps)
            UsageRow("模型 model_id", turn.modelId)
            UsageRow("提供方 provider", turn.provider)
            UsageRow("来源 source", turn.source)
        }

        val sessionRows = projection.session
        SectionHeader("会话累计")
        if (sessionRows == null || !sessionRows.hasData) {
            MutedLine("暂无数据 — session/usage.get 尚未接入，不显示占位数字")
        } else {
            UsageRow("输入 tokens", sessionRows.chatPrompt)
            UsageRow("输出 tokens", sessionRows.chatCompletion)
            UsageRow("合计 tokens", sessionRows.chatTotal)
            UsageRow("turns", sessionRows.turns)
            UsageRow("用量缺失 turns（source=none）", sessionRows.partialTurns)
            UsageRow("辅助调用 tokens（aux）", sessionRows.auxTotal)
            for (model in sessionRows.models) {
                UsageRow("按模型 · ", model.name, model.total, model.turns)
            }
            sessionRows.day?.let { UsageRow("今日全 core", it) }
        }

        val budget = projection.budget
        SectionHeader("预算")
        if (budget == null || !budget.hasData) {
            MutedLine("暂无数据 — session/usage.get.budget 尚未接入，不显示占位数字")
        } else {
            BudgetHeader(budget)
            if (budget.fraction != null) {
                BudgetBar(budget.fraction, budgetColor(budget.level, LocalSkin.current.colors))
            }
            BudgetCaption(budget)
        }
    }
}

/** Card container with the GroupCard recipe (§8 row-1 layered shadow + hairline). */
@Composable
private fun PanelCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val skin = LocalSkin.current
    val shape = RoundedCornerShape(skin.shapes.md)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .layeredShadow(VadeShadows.card, shape, dark = skin.colors.isDark)
            .clip(shape)
            .background(skin.colors.surface)
            .border(1.dp, skin.colors.hairline, shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

@Composable
private fun SectionHeader(text: String) {
    Spacer(Modifier.height(2.dp))
    UiText(text, size = 11, weight = FontWeight.SemiBold, color = LocalSkin.current.colors.onSurfaceVariant)
}

@Composable
private fun MutedLine(text: String) {
    UiText(text, size = 13, color = LocalSkin.current.colors.onSurfaceVariant)
}

/** One label/cell line; a missing cell renders「暂无数据」in muted ink — never a zero. */
@Composable
private fun UsageRow(label: String, cell: TokenCell) {
    val colors = LocalSkin.current.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        UiText(label, size = 13, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
        UiText(
            cell.display(),
            size = 13,
            weight = if (cell is TokenCell.Value) FontWeight.Medium else FontWeight.Normal,
            color = if (cell is TokenCell.Value) colors.onSurface else colors.onSurfaceVariant,
        )
    }
}

/** `by_model` line: 名称 + tokens + turns. */
@Composable
private fun UsageRow(prefix: String, name: TokenCell, total: TokenCell, turns: TokenCell) {
    val colors = LocalSkin.current.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        UiText(
            prefix + name.display(),
            size = 13,
            color = colors.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        val tail = "${total.display()} · turns ${turns.display()}"
        UiText(
            tail,
            size = 13,
            weight = FontWeight.Medium,
            color = colors.onSurface,
        )
    }
}

/** source=none 降级标识 — visible, explicit, warn-coloured; never hidden. */
@Composable
private fun DegradedBanner() {
    val colors = LocalSkin.current.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(percent = 50))
            .background(colors.warning.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VadeIcon(
            VadeIcons.Waiting,
            contentDescription = "降级运行：本轮用量未上报",
            size = 16.dp,
            tint = colors.warning,
        )
        Spacer(Modifier.width(8.dp))
        UiText(
            "降级运行：本轮 usage.source=none，token 统计缺失，以下数字可能为 0 而非真实值",
            size = 12,
            color = colors.warning,
            modifier = Modifier.weight(1f),
        )
    }
}

/** usage.budget 事件横幅 — a notification, not the truth; the text itself says so. */
@Composable
private fun NoticeRow(notice: BudgetNotice) {
    val colors = LocalSkin.current.colors
    val tint = if (notice.danger) colors.error else colors.warning
    UiText(notice.text, size = 12, color = tint)
}

/** 预算标题行 + 档位标签（≥80% 警告、达上限/超限 危险）。 */
@Composable
private fun BudgetHeader(budget: BudgetRows) {
    val colors = LocalSkin.current.colors
    val tier: Pair<String, Color>? = when (budget.level) {
        BudgetLevel.WARNING -> "警告线" to colors.warning
        BudgetLevel.CRITICAL -> "已达上限" to colors.error
        BudgetLevel.EXCEEDED -> "已超限" to colors.error
        else -> null
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        UiText("本会话预算", size = 13, weight = FontWeight.Medium, color = colors.onSurface)
        if (tier != null) {
            val (label, tint) = tier
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(tint.copy(alpha = 0.12f))
                    .padding(horizontal = 10.dp, vertical = 2.dp),
            ) {
                UiText(label, size = 11, weight = FontWeight.SemiBold, color = tint)
            }
        }
    }
}

/** 6dp 全圆进度条：fill 颜色由 [budgetColor] 决定（NORMAL 强调 / WARNING 警告 / 危险档 danger）。 */
@Composable
private fun BudgetBar(fraction: Float, color: Color) {
    val skin = LocalSkin.current
    val pill = RoundedCornerShape(percent = 50)
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(pill)
            .background(skin.colors.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(6.dp)
                .clip(pill)
                .background(color),
        )
    }
}

@Composable
private fun BudgetCaption(budget: BudgetRows) {
    val colors = LocalSkin.current.colors
    UiText(
        "已用 ${budget.used.display()} / 上限 ${budget.limit.display()}（${budget.percent.display()}）" +
            " · 状态 ${budget.state.display()} · ${budget.enforced.display()}",
        size = 12,
        color = colors.onSurfaceVariant,
    )
}
