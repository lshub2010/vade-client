package vade.ui.usage

import androidx.compose.ui.graphics.Color
import vade.ui.protocol.AuxUsage
import vade.ui.protocol.BudgetState
import vade.ui.protocol.BudgetUsage
import vade.ui.protocol.ChatUsage
import vade.ui.protocol.DayUsage
import vade.ui.protocol.ModelSource
import vade.ui.protocol.ModelUsage
import vade.ui.protocol.SessionUsageGetResult
import vade.ui.protocol.UsageBudgetPayload
import vade.ui.protocol.VadeUsage
import vade.ui.skin.SkinColors
import kotlin.math.roundToInt

/**
 * C-Activity · the pure projection layer behind the Activity page's 用量 panel.
 *
 * The panel is fed **real contract-shaped DTOs** from the outside
 * (`session/usage.get` → [SessionUsageGetResult], `turn.completed.usage` → [VadeUsage],
 * the `usage.budget` event → [UsageBudgetPayload]). Nothing in this file fabricates a
 * number: every value the contract did not deliver becomes [TokenCell.NotReported] and
 * the card renders it as「暂无数据」. This is the honesty contract of this slice —
 * wiring real data later is literally passing the DTO in, zero UI change.
 *
 * All decisions here are pure functions so the desktopTest suite can pin them without a
 * Compose test harness (this module's desktopTest has no ui-test artifact, and the build
 * file may not change in this stick).
 */

/**
 * One displayable value cell. [NotReported] means "the contract did not give us this
 * field" — it must NEVER be collapsed to a zero: a missing `cached_tokens` and a real
 * `cached_tokens: 0` are different facts (RFC-0005 source=none turns).
 */
sealed interface TokenCell {
    /** A real value delivered by the contract, already formatted for display. */
    data class Value(val text: String) : TokenCell

    /** No data — render as「暂无数据」, never as 0. */
    data object NotReported : TokenCell
}

/** `null`/absent → [TokenCell.NotReported]; a real number (including 0) → formatted [TokenCell.Value]. */
internal fun cell(n: Long?, format: (Long) -> String = ::formatTokens): TokenCell =
    if (n == null) TokenCell.NotReported else TokenCell.Value(format(n))

internal fun cell(n: Int?, format: (Int) -> String = Int::toString): TokenCell =
    if (n == null) TokenCell.NotReported else TokenCell.Value(format(n))

internal fun cell(raw: String?): TokenCell =
    if (raw.isNullOrBlank()) TokenCell.NotReported else TokenCell.Value(raw)

/** The one display text per cell —「暂无数据」is the mandated empty wording. */
fun TokenCell.display(): String = when (this) {
    is TokenCell.Value -> text
    TokenCell.NotReported -> "暂无数据"
}

// ----------------------------------------------------------------- formatting

/** 12 345 → "12,345" — thousands separators so token counts scan at a glance. */
fun formatTokens(n: Long): String = buildString {
    val s = n.toString()
    val first = s.length % 3
    if (first in 1..2) append(s.substring(0, first))
    for (i in first until s.length step 3) {
        if (isNotEmpty()) append(',')
        append(s.substring(i, i + 3))
    }
    if (isEmpty()) append('0')
}

/** 850 → "850ms"; 12_300 → "12.3s"; 125_000 → "2m05s"; 3_665_000 → "1h01m"; 0 → "0s". */
fun formatDuration(ms: Long): String = when {
    ms < 0L -> "0s"
    ms < 1_000L -> "${ms}ms"
    ms < 60_000L -> {
        val whole = ms / 1_000L
        val tenth = (ms % 1_000L) / 100L
        if (tenth == 0L) "${whole}s" else "$whole.${tenth}s"
    }
    ms < 3_600_000L -> "${ms / 60_000L}m${two((ms % 60_000L) / 1_000L)}s"
    else -> "${ms / 3_600_000L}h${two((ms % 3_600_000L) / 60_000L)}m"
}

/** Zero-pads to two digits without `String.format` (common code, wasm-safe). */
private fun two(n: Long): String = if (n < 10L) "0$n" else n.toString()

// ----------------------------------------------------------------- budget

/**
 * The four fill levels of the budget bar (plus [NONE] = no bar at all).
 *
 * Threshold table (task-fixed UI thresholds; the contract's `budget.state` wins when
 * the server has spoken):
 *  - ratio < 80%                          → [NORMAL]  (accent fill)
 *  - 80% ≤ ratio < 100%                   → [WARNING] (warn fill)
 *  - used == limit (exactly at the cap)   → [CRITICAL] (danger fill)
 *  - used > limit  (over the cap)         → [EXCEEDED] (danger fill +「已超限」label)
 *
 * [CRITICAL] and [EXCEEDED] share the danger colour on purpose: the visual language
 * fixes the status palette (§二) and bans a fourth invented hue; the tier is told apart
 * by the level and the「已超限」caption, not by a new colour.
 */
enum class BudgetLevel { NONE, NORMAL, WARNING, CRITICAL, EXCEEDED }

/**
 * Pure budget-bar decision. Precedence: the authoritative contract state
 * (`session/usage.get.budget.state` — the `usage.budget` event is only a notification)
 * first, then the exact integer `used` vs `limit`, then the ratio fallback.
 */
fun budgetLevel(state: BudgetState?, used: Long, limit: Long, ratio: Double?): BudgetLevel = when {
    limit <= 0L -> BudgetLevel.NONE
    state == BudgetState.EXCEEDED -> BudgetLevel.EXCEEDED
    state == BudgetState.WARN -> BudgetLevel.WARNING
    used > limit -> BudgetLevel.EXCEEDED
    used == limit -> BudgetLevel.CRITICAL
    else -> {
        val r = ratio ?: (used.toDouble() / limit.toDouble())
        if (r >= 0.80) BudgetLevel.WARNING else BudgetLevel.NORMAL
    }
}

/**
 * Fill colour per level, resolved from public skin tokens. [BudgetLevel.NONE] maps to
 * the muted ink because nothing is drawn with it (track-only).
 */
fun budgetColor(level: BudgetLevel, colors: SkinColors): Color = when (level) {
    BudgetLevel.NONE -> colors.onSurfaceVariant
    BudgetLevel.NORMAL -> colors.primary
    BudgetLevel.WARNING -> colors.warning
    BudgetLevel.CRITICAL, BudgetLevel.EXCEEDED -> colors.error
}

/** 0..1 progress fraction for the bar; `null` input (state=off) draws no bar. */
fun budgetFraction(used: Long, limit: Long, ratio: Double?): Float? {
    if (limit <= 0L) return null
    val r = ratio ?: (used.toDouble() / limit.toDouble())
    return r.toFloat().coerceIn(0f, 1f)
}

/**
 * The `usage.budget` event banner. The event is a **notification, not the truth**
 * (events.json §usage.budget notes) — so the text says so. `degraded=true` has exactly
 * one contract meaning: enforce=block *would* have refused the turn but the ledger is
 * untrustworthy (last measured turn had source=none), so the refusal was downgraded to
 * a warning — the UI must tell the user that, not hide it.
 */
data class BudgetNotice(val text: String, /** exceeded banners take the danger colour. */ val danger: Boolean)

fun budgetNotice(payload: UsageBudgetPayload): BudgetNotice? = when (payload.stateEnum) {
    BudgetState.WARN ->
        BudgetNotice(
            "预算通知：已达警告线（已用 ${formatTokens(payload.used)} / 上限 ${formatTokens(payload.limit)}）；" +
                "权威状态以 session/usage.get 为准",
            danger = false,
        )
    BudgetState.EXCEEDED ->
        if (payload.degraded) {
            BudgetNotice(
                "预算通知：已超限且账本不可信（本轮 usage.source=none，enforce=block 的拒绝已降级为警告）；" +
                    "权威状态以 session/usage.get 为准",
                danger = true,
            )
        } else {
            BudgetNotice(
                "预算通知：已超限（已用 ${formatTokens(payload.used)} / 上限 ${formatTokens(payload.limit)}）；" +
                    "enforce=block 下后续 turn 将被拒绝（BUDGET_EXCEEDED）",
                danger = true,
            )
        }
    // ok/off never notify (contract); a raw value we do not know is shown verbatim, not invented around.
    BudgetState.OFF, BudgetState.OK -> null
    BudgetState.UNKNOWN ->
        BudgetNotice("预算通知：未知状态 ${payload.state}；权威状态以 session/usage.get 为准", danger = false)
}

// ----------------------------------------------------------------- projections

/** The「本轮」rows projected from one `turn.completed.usage` object. */
data class TurnUsageRows(
    val prompt: TokenCell,
    val completion: TokenCell,
    val total: TokenCell,
    val cached: TokenCell,
    val reasoning: TokenCell,
    val steps: TokenCell,
    val modelId: TokenCell,
    val provider: TokenCell,
    val source: TokenCell,
    /** true ONLY when `usage.source == "none"` — the card MUST show the degraded marker then. */
    val degraded: Boolean,
) {
    val hasData: Boolean
        get() = listOf(prompt, completion, total, cached, reasoning, steps, modelId, provider, source)
            .any { it is TokenCell.Value }
}

/** The「会话累计」rows projected from a `session/usage.get` result. */
data class SessionUsageRows(
    val chatPrompt: TokenCell,
    val chatCompletion: TokenCell,
    val chatTotal: TokenCell,
    val turns: TokenCell,
    /** Turns whose usage.source=none — surfaced, not hidden. */
    val partialTurns: TokenCell,
    val auxTotal: TokenCell,
    val models: List<ModelRow>,
    val day: TokenCell?,
) {
    data class ModelRow(val name: TokenCell, val total: TokenCell, val turns: TokenCell)

    val hasData: Boolean
        get() = listOf(chatPrompt, chatCompletion, chatTotal, turns, partialTurns, auxTotal)
            .any { it is TokenCell.Value } || models.isNotEmpty() || day is TokenCell.Value
}

/** Everything the budget block draws, already decided — no policy in the composable. */
data class BudgetRows(
    val level: BudgetLevel,
    /** 0..1 or null (= draw no bar: state off / limit 0). */
    val fraction: Float?,
    val used: TokenCell,
    val limit: TokenCell,
    val percent: TokenCell,
    /** The contract's raw state string, verbatim (off/ok/warn/exceeded). */
    val state: TokenCell,
    /** enforce=block surfaced: true →「超出即阻断」, false →「仅警告不阻断」, null → 暂无数据. */
    val enforced: TokenCell,
) {
    val hasData: Boolean get() = level != BudgetLevel.NONE || state is TokenCell.Value
}

data class UsageProjection(
    val turn: TurnUsageRows?,
    val session: SessionUsageRows?,
    val budget: BudgetRows?,
    /** `usage.budget` event banner; null when there is nothing to tell. */
    val notice: BudgetNotice?,
) {
    /** Whole-panel empty state when nothing at all was delivered. */
    val hasAnyData: Boolean
        get() = (turn?.hasData == true) || (session?.hasData == true) || (budget?.hasData == true) || notice != null
}

/** Project one `turn.completed.usage` ([VadeUsage]); null input → null rows (空态, not zeros). */
fun projectTurnUsage(usage: VadeUsage?): TurnUsageRows? {
    if (usage == null) return null
    return TurnUsageRows(
        prompt = cell(usage.promptTokens),
        completion = cell(usage.completionTokens),
        total = cell(usage.totalTokens),
        // Missing (absent key) → NotReported「暂无数据」; a real 0 → "0". Never conflated.
        cached = cell(usage.cachedTokens),
        reasoning = cell(usage.reasoningTokens),
        steps = cell(usage.steps),
        modelId = cell(usage.modelId),
        provider = cell(usage.provider),
        source = cell(usage.source),
        degraded = usage.sourceEnum == ModelSource.NONE,
    )
}

/** Project one `session/usage.get` result; null input → null rows. */
fun projectSessionUsage(result: SessionUsageGetResult?): SessionUsageRows? {
    if (result == null) return null

    fun chatRow(c: ChatUsage?): Triple<TokenCell, TokenCell, TokenCell> =
        Triple(
            c?.promptTokens?.let { cell(it) } ?: TokenCell.NotReported,
            c?.completionTokens?.let { cell(it) } ?: TokenCell.NotReported,
            c?.totalTokens?.let { cell(it) } ?: TokenCell.NotReported,
        )

    val chat = chatRow(result.chat)
    val aux: AuxUsage? = result.aux
    val models = result.byModel.map { m: ModelUsage ->
        SessionUsageRows.ModelRow(
            // model_id is `string|null` in the contract — an absent id is honest「未知模型」.
            name = m.modelId?.let { TokenCell.Value(it) } ?: TokenCell.Value("未知模型"),
            total = cell(m.totalTokens),
            turns = cell(m.turns),
        )
    }
    val day: DayUsage? = result.day
    val dayCell: TokenCell? = day?.let {
        // limit=0 = no day budget configured — say so instead of printing "x / 0".
        if (it.coreLimit <= 0L) {
            TokenCell.Value("${formatTokens(it.coreTotalTokens)}（未设上限）")
        } else {
            val state = it.state?.let { s -> "，$s" } ?: ""
            TokenCell.Value("${formatTokens(it.coreTotalTokens)} / ${formatTokens(it.coreLimit)}$state")
        }
    }
    return SessionUsageRows(
        chatPrompt = chat.first,
        chatCompletion = chat.second,
        chatTotal = chat.third,
        turns = result.chat?.turns?.let { cell(it) } ?: TokenCell.NotReported,
        partialTurns = result.chat?.partialTurns?.let { cell(it) } ?: TokenCell.NotReported,
        auxTotal = aux?.totalTokens?.let { cell(it) } ?: TokenCell.NotReported,
        models = models,
        day = dayCell,
    )
}

/** Project the authoritative `budget` object of `session/usage.get`; null input → null rows. */
fun projectBudget(budget: BudgetUsage?): BudgetRows? {
    if (budget == null) return null
    val level = budgetLevel(budget.stateEnum, budget.used, budget.limit, budget.ratio)
    val fraction = budgetFraction(budget.used, budget.limit, budget.ratio)
    val percent: TokenCell = if (fraction == null) {
        TokenCell.NotReported
    } else {
        val raw = budget.ratio ?: (budget.used.toDouble() / budget.limit.toDouble())
        TokenCell.Value("${(raw * 100).roundToInt()}%")
    }
    return BudgetRows(
        level = level,
        fraction = fraction,
        used = cell(budget.used),
        limit = cell(budget.limit),
        percent = percent,
        state = cell(budget.state),
        enforced = when (budget.enforced) {
            null -> TokenCell.NotReported
            true -> TokenCell.Value("超出即阻断（enforce=block）")
            false -> TokenCell.Value("仅警告，不阻断")
        },
    )
}

/** Project the whole panel from the three real DTO snapshots the shell will pass in. */
fun projectUsage(
    session: SessionUsageGetResult?,
    lastTurn: VadeUsage?,
    notice: UsageBudgetPayload?,
): UsageProjection = UsageProjection(
    turn = projectTurnUsage(lastTurn),
    session = projectSessionUsage(session),
    budget = projectBudget(session?.budget),
    notice = notice?.let(::budgetNotice),
)
