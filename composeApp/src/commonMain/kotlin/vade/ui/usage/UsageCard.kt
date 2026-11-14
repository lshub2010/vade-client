package vade.ui.usage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
 * G: the read itself now exists — [UsageSection] renders Loading / Failed(+retry) /
 * Ready, and [UsageSectionLive] owns a [UsageLoader] over a [UsageSource]
 * ([UsageSourceReal] over `VadeClient.sessionUsageGet`). This function stays the pure
 * Ready renderer over the decoded DTO.
 */
@Composable
fun UsagePanel(
    session: SessionUsageGetResult?,
    lastTurn: VadeUsage?,
    budgetNotice: UsageBudgetPayload?,
    modifier: Modifier = Modifier,
    /**
     * G · 这份空态是不是「读数已经接通、服务端就是这样回」的。
     *
     * 接线前所有参数都是 `null`，空态说「接入后…」是对的；接线后 `session/usage.get`
     * 成功返回了一份什么都没有的回包，再说「未接线」就是假话 —— 那是**真没数据**，
     * 必须与「加载中」「加载失败」用不同文案区分开。默认 `false` 保持旧行为。
     */
    readWired: Boolean = false,
) {
    val projection = projectUsage(session, lastTurn, budgetNotice)
    if (!projection.hasAnyData) {
        if (readWired) {
            EmptyStateCard(
                icon = VadeIcons.Empty,
                title = "本会话暂无用量",
                body = "session/usage.get 已成功返回（已加载），但回包里没有任何可显示的 token 统计或预算字段：" +
                    "该会话还没有产生用量。这不是加载失败，也不是未接线。",
                modifier = modifier,
            )
        } else {
            EmptyStateCard(
                icon = VadeIcons.Empty,
                title = "暂无数据",
                body = "接入 session/usage.get 与 turn.completed.usage 后，这里显示真实的 token 统计与预算进度；" +
                    "未接线前不显示任何占位数字。",
                modifier = modifier,
            )
        }
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
            UsageRow("chat 合计 tokens", sessionRows.chatTotal)
            UsageRow("turns", sessionRows.turns)
            UsageRow("用量缺失 turns（source=none）", sessionRows.partialTurns)
            UsageRow("辅助调用 tokens（aux）", sessionRows.auxTotal)
            // 服务端合计原样显示：total_tokens = chat + aux 是契约算式，客户端不另算。
            UsageRow("总 tokens（chat + aux）", sessionRows.total)
            if (sessionRows.totalMatches == false) {
                MutedLine("注意：服务端 total_tokens 与 chat+aux 不一致（违反 rpc.json §session/usage.get 的 total_tokens = chat + aux），已原样显示服务端值，未在客户端改写。")
            }
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

/**
 * G · the wired 用量 section: the panel over a **live** read of `session/usage.get`.
 *
 * This is the drop-in the shell composes (`ActivityScreen`'s 用量 slot). Three states,
 * no fourth:
 *  - [UsageLoadState.Loading] → 「加载中」card (never a stale or fabricated number);
 *  - [UsageLoadState.Failed] → category label + C-04 code + hint + a **retry row**
 *    (refresh icon, click = [onRetry] → `session/usage.get` again);
 *  - [UsageLoadState.Ready] → the existing [UsagePanel] renderer over the decoded DTO.
 */
@Composable
fun UsageSection(
    state: UsageLoadState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    lastTurn: VadeUsage? = null,
    budgetNotice: UsageBudgetPayload? = null,
) {
    when (state) {
        UsageLoadState.Loading -> UsageLoadingCard(modifier)
        is UsageLoadState.Failed -> UsageFailedCard(state.error, onRetry, modifier)
        is UsageLoadState.Ready -> UsagePanel(
            session = state.usage,
            lastTurn = lastTurn,
            budgetNotice = budgetNotice,
            modifier = modifier,
            // 已接线读取：真空态用「已成功返回但没有可显示字段」的文案，
            // 不能再说「接入后…」（那是接线前的说法，见真没数据/加载中/加载失败三态）。
            readWired = true,
        )
    }
}

/**
 * Self-driving variant: owns a [UsageLoader] over [source] and (re)loads whenever
 * [sessionId] changes, so wiring the page is a two-argument call. A blank/null session id
 * renders the「尚未选择会话」empty state — no request, no fabricated numbers.
 */
@Composable
fun UsageSectionLive(
    source: UsageSource,
    sessionId: String?,
    modifier: Modifier = Modifier,
    lastTurn: VadeUsage? = null,
    budgetNotice: UsageBudgetPayload? = null,
) {
    val scope = rememberCoroutineScope()
    val loader = remember(source) { UsageLoader(source, scope) }
    DisposableEffect(loader) { onDispose { loader.dispose() } }
    LaunchedEffect(loader, sessionId) {
        if (!sessionId.isNullOrBlank()) loader.load(sessionId)
    }
    if (sessionId.isNullOrBlank()) {
        EmptyStateCard(
            icon = VadeIcons.Empty,
            title = "尚未选择会话",
            body = "打开一个会话后，这里通过 session/usage.get 拉取真实的 token 统计与预算进度。",
            modifier = modifier,
        )
        return
    }
    UsageSection(
        state = loader.state,
        onRetry = loader::retry,
        modifier = modifier,
        lastTurn = lastTurn,
        budgetNotice = budgetNotice,
    )
}

/** Loading: an honest placeholder —「加载中」, no numbers of any kind. */
@Composable
private fun UsageLoadingCard(modifier: Modifier = Modifier) {
    val colors = LocalSkin.current.colors
    PanelCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            VadeIcon(
                VadeIcons.Waiting,
                contentDescription = "加载中",
                size = 16.dp,
                tint = colors.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            UiText("加载中…", size = 13, weight = FontWeight.Medium, color = colors.onSurface)
        }
        MutedLine("正在请求 session/usage.get（${usageStateLabel(UsageLoadState.Loading)}），返回前不显示任何占位数字。")
    }
}

/** Failed: category-first banner + the exact C-04 code + a retry row that re-issues the read. */
@Composable
private fun UsageFailedCard(error: UsageLoadError, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalSkin.current.colors
    PanelCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            VadeIcon(
                VadeIcons.Failed,
                contentDescription = "加载失败",
                size = 16.dp,
                tint = colors.error,
            )
            Spacer(Modifier.width(8.dp))
            UiText("用量加载失败", size = 13, weight = FontWeight.SemiBold, color = colors.error)
        }
        UiText(error.label, size = 13, color = colors.onSurface)
        MutedLine(error.hint)
        // 可重试入口：同一 session_id 再发一次 session/usage.get（不是空数据、不是假成功）。
        Row(
            Modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(colors.surfaceVariant)
                .clickable(onClick = onRetry)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VadeIcon(VadeIcons.Refresh, contentDescription = "重试", size = 16.dp, tint = colors.onSurface)
            Spacer(Modifier.width(6.dp))
            UiText(error.retryLabel, size = 12, weight = FontWeight.Medium, color = colors.onSurface)
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
    // 单一文案来源（budgetCaption）：ratio=null →「未启用」，绝不出现 0%。
    UiText(
        budgetCaption(budget),
        size = 12,
        color = LocalSkin.current.colors.onSurfaceVariant,
    )
}
