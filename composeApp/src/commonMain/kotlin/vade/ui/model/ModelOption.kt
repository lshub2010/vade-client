// Model picker — the data side (RFC-0005 / FR-8.46 / FR-8.47).
//
// Everything in this file is pure: protocol `ModelEntry` in, presentation models out. That is
// what makes the acceptance items testable without a window (same discipline as ChatState —
// the composables in `ModelPickerCard.kt` only *draw* what these functions decide).
//
// The one rule that shapes every branch below is RFC-0005 §3.2: a `null` capability field is
// UNKNOWN and is never interchangeable with `false` / `[]` — so every capability this picker
// shows is a THREE-valued fact (支持 / 不支持 / 未声明), and nothing may hard-code "全支持".

package vade.ui.model

import androidx.compose.ui.graphics.Color
import vade.ui.protocol.ModelEntry
import vade.ui.skin.SkinColors

/** RFC-0005 tri-state for one declared capability: 支持 / 不支持 / 未声明. */
enum class CapSupport { Supported, Unsupported, Unknown }

/** The three badges RFC-0005 §5 names for FR-8.47: 视觉 / 工具 / 长上下文. */
enum class CapabilityKind(val label: String) {
    Vision("vision"),
    Tools("tools"),
    Context("ctx"),
}

/**
 * One badge chip. [text] is the full visible copy — data-driven from the entry, never a
 * canned "支持" (the tri-state wording lives here so a wrong derivation shows up in tests).
 */
data class ModelBadge(val kind: CapabilityKind, val support: CapSupport, val text: String)

/** One labeled line of the expanded detail block; the 11-field model/list entry mapping. */
data class ModelDetailRow(val label: String, val value: String)

/**
 * A pickable model as the UI sees it. Built from [ModelEntry.of] — one field of the wire
 * type per property, so "the picker renders all 11 fields" is checkable field by field.
 */
data class ModelOption(
    /** Protocol `id` — the ONLY thing ever sent to `session/model.set` (never the name). */
    val id: String,
    val provider: String?,
    val displayName: String?,
    val vision: CapSupport,
    val tools: CapSupport,
    val context: CapSupport,
    val contextWindow: Long?,
    /** "≤4 张/轮 · ≤20.0 MB · ≤8.4M px" — the three image limits, null when none declared. */
    val imageLimitSummary: String?,
    val priority: Int?,
    val modalities: List<String>?,
    /** RFC-0005 tri-state of `available`: Unsupported = offline ⇒ not selectable. */
    val available: CapSupport,
    /** Offline entries stay in the list but refuse selection (server validates the same way). */
    val selectable: Boolean,
    /** All 11 fields as labeled rows, `null` ⇒ "未声明" (unknown is rendered, not dropped). */
    val detail: List<ModelDetailRow>,
) {
    /** Visible title: display_name when declared, else the raw id (RFC-0005 §2 slash form, e.g. "m6-echo/..."). */
    fun title(): String = displayName ?: id

    /** Secondary line: provider + priority, each honestly absent when undeclared. */
    fun metaLine(): String = buildString {
        append(provider ?: "provider 未声明")
        priority?.let { append(" · priority $it") }
    }

    /** The three FR-8.47 badges, in RFC order 视觉 → 工具 → 长上下文. */
    fun badges(): List<ModelBadge> = listOf(
        ModelBadge(CapabilityKind.Vision, vision, visionBadgeText(this)),
        ModelBadge(CapabilityKind.Tools, tools, toolsBadgeText(this)),
        ModelBadge(CapabilityKind.Context, context, contextBadgeText(this)),
    )

    companion object {
        fun of(entry: ModelEntry): ModelOption = ModelOption(
            id = entry.id,
            provider = entry.provider,
            displayName = entry.displayName,
            vision = visionSupport(entry),
            tools = toolsSupport(entry),
            context = contextSupport(entry),
            contextWindow = entry.contextWindow,
            imageLimitSummary = imageLimitSummary(entry),
            priority = entry.priority,
            modalities = entry.inputModalities,
            available = availability(entry),
            selectable = availability(entry) != CapSupport.Unsupported,
            detail = modelDetailRows(entry),
        )
    }
}

// --- the tri-state derivations ---------------------------------------------------

private fun availability(entry: ModelEntry): CapSupport = when (entry.available) {
    true -> CapSupport.Supported
    false -> CapSupport.Unsupported
    null -> CapSupport.Unknown
}

private fun visionSupport(entry: ModelEntry): CapSupport = when {
    entry.inputModalities == null -> CapSupport.Unknown
    entry.inputModalities.contains("image") -> CapSupport.Supported
    else -> CapSupport.Unsupported
}

private fun toolsSupport(entry: ModelEntry): CapSupport = when (entry.supportsTools) {
    true -> CapSupport.Supported
    false -> CapSupport.Unsupported
    null -> CapSupport.Unknown
}

/** Declared context window = the long-context badge lights up; absent = unknown (RFC §3.2). */
private fun contextSupport(entry: ModelEntry): CapSupport =
    if (entry.contextWindow != null && entry.contextWindow > 0) CapSupport.Supported
    else CapSupport.Unknown

// --- badge copy -------------------------------------------------------------------

private fun visionBadgeText(option: ModelOption): String = when (option.vision) {
    CapSupport.Supported -> "vision · 图片输入"
    CapSupport.Unsupported -> "vision · 仅文本"
    CapSupport.Unknown -> "vision · 未声明"
}

private fun toolsBadgeText(option: ModelOption): String = when (option.tools) {
    CapSupport.Supported -> "tools · 工具调用"
    CapSupport.Unsupported -> "tools · 无工具"
    CapSupport.Unknown -> "tools · 未声明"
}

private fun contextBadgeText(option: ModelOption): String = when (option.context) {
    CapSupport.Supported -> "ctx · " + formatContextWindow(option.contextWindow ?: 0)
    CapSupport.Unsupported -> "ctx · 未声明"
    CapSupport.Unknown -> "ctx · 未声明"
}

// --- the 11-field mapping (model/list entry → labeled rows, exhaustive) ------------

/**
 * Exactly eleven rows, in wire-field order — one per `model/list` entry field. A `null`
 * renders as "未声明" rather than disappearing: RFC-0005 §3.2 forbids reading `null` as
 * `false`/`[]`, and a dropped row would silently claim "no information needed".
 */
fun modelDetailRows(entry: ModelEntry): List<ModelDetailRow> = listOf(
    ModelDetailRow("模型 ID", entry.id),
    ModelDetailRow("Provider", entry.provider ?: "未声明"),
    ModelDetailRow("显示名", entry.displayName ?: "未声明"),
    ModelDetailRow("输入模态", entry.inputModalities?.joinToString(", ") ?: "未声明"),
    ModelDetailRow("单图上限", entry.maxImageBytes?.let(::formatModelBytes) ?: "未声明"),
    ModelDetailRow("单图像素", entry.maxImagePx?.let(::formatModelPixels) ?: "未声明"),
    ModelDetailRow("每轮图片", entry.maxImagesPerTurn?.let { "$it 张" } ?: "未声明"),
    ModelDetailRow("上下文", entry.contextWindow?.let { formatContextWindow(it) + " tokens" } ?: "未声明"),
    ModelDetailRow("工具调用", when (entry.supportsTools) {
        true -> "支持"
        false -> "不支持"
        null -> "未声明"
    }),
    ModelDetailRow("priority", entry.priority?.toString() ?: "未声明"),
    ModelDetailRow("可用性", when (entry.available) {
        true -> "在线"
        false -> "离线"
        null -> "未声明"
    }),
)

/**
 * The three image limits as one summary line under the vision badge — null only when the
 * entry declares none of them. Order: 张/轮 → bytes → px (RFC-0004 send-side order).
 */
private fun imageLimitSummary(entry: ModelEntry): String? = listOfNotNull(
    entry.maxImagesPerTurn?.let { "≤$it 张/轮" },
    entry.maxImageBytes?.let { "≤" + formatModelBytes(it) },
    entry.maxImagePx?.let { "≤" + formatModelPixels(it) },
).takeIf { it.isNotEmpty() }?.joinToString(" · ")

// --- formatters (pure Kotlin — commonMain has no java.util.Locale) ------------------

/** One decimal, computed by hand so the value is identical on every target. */
private fun oneDecimal(value: Double): String {
    val scaled = kotlin.math.round(value * 10).toLong()
    val whole = scaled / 10
    val tenth = scaled % 10
    return "$whole.$tenth"
}

internal fun formatModelBytes(bytes: Long): String = when {
    bytes >= (1L shl 30) -> oneDecimal(bytes / (1024.0 * 1024 * 1024)) + " GB"
    bytes >= (1L shl 20) -> oneDecimal(bytes / (1024.0 * 1024)) + " MB"
    bytes >= (1L shl 10) -> oneDecimal(bytes / 1024.0) + " KB"
    else -> "$bytes B"
}

internal fun formatModelPixels(px: Long): String = when {
    px >= 1_000_000 -> oneDecimal(px / 1_000_000.0) + "M px"
    else -> "$px px"
}

internal fun formatContextWindow(tokens: Long): String = when {
    tokens >= 1_000_000 -> oneDecimal(tokens / 1_000_000.0) + "M"
    tokens >= 1_000 -> "${tokens / 1000}K"
    else -> "$tokens"
}

// --- the row visual decision (accent = selection; tested, not buried in a composable) --

/**
 * Which skin tokens a model row uses. [selected] is the accent case: `accentSoft` is the
 * visual-language §1 selected-row wash (primary @ 8% light / 14% dark — SkinColors has no
 * accentSoft token), a 1dp primary border and the check glyph. Unselected rows stay on
 * hairline; offline rows grey their title without claiming to be selected.
 */
data class ModelRowStyle(
    val background: Color?,
    val border: Color,
    val titleColor: Color,
    val metaColor: Color,
    val showCheck: Boolean,
)

fun modelRowStyle(colors: SkinColors, selected: Boolean, selectable: Boolean): ModelRowStyle = when {
    selected -> ModelRowStyle(
        background = colors.primary.copy(alpha = if (colors.isDark) 0.14f else 0.08f),
        border = colors.primary,
        titleColor = colors.onSurface,
        metaColor = colors.onSurfaceVariant,
        showCheck = true,
    )
    !selectable -> ModelRowStyle(
        background = null,
        border = colors.hairline,
        titleColor = colors.onSurfaceVariant,
        metaColor = colors.onSurfaceVariant,
        showCheck = false,
    )
    else -> ModelRowStyle(
        background = null,
        border = colors.hairline,
        titleColor = colors.onSurface,
        metaColor = colors.onSurfaceVariant,
        showCheck = false,
    )
}

// --- picker content: rows or the honest empty state ----------------------------------

/**
 * What the picker body draws. `NoModels` is a real state, not an empty list: RFC-0005 §7
 * bans presenting 空列表 as "没有模型" — the copy says who can fix it. `ReadOnly` is the
 * FR-8.46 degrade (-32601 / unknown method) and is deliberately a SEPARATE state: an old core
 * has no model surface at all, so showing 「无可用模型」 there would send the reader to a
 * provider setting that does not exist.
 *
 * [Loading] and [LoadFailed] are the same discipline applied to `models == null` on the REAL
 * path: not-asked-yet and the-ask-failed are also *not* "the core has none". Before them a
 * real picker flashed 「无可用模型」 on every entry, because `ModelActionsReal` starts at
 * `models = null` and the pull is asynchronous.
 */
sealed interface ModelPickerContent {
    /** [interactive] = writes allowed (false on the FR-8.46 read-only degrade). */
    data class Rows(val rows: List<ModelRow>, val interactive: Boolean) : ModelPickerContent

    /** 「此 core 不支持模型选择」 — models stayed `null`, so there is nothing to draw but the badge. */
    data class ReadOnly(val title: String, val body: String) : ModelPickerContent

    /** 「正在加载模型列表」 — the first `model/list` answer has not arrived yet. */
    data class Loading(val title: String, val body: String) : ModelPickerContent

    /** 「模型列表加载失败」 — a pull failed and there is no previously pulled list to show. */
    data class LoadFailed(val title: String, val body: String) : ModelPickerContent

    /** 「无可用模型」 — [body] differs between "no provider" and "old core, read-only". */
    data class NoModels(val title: String, val body: String) : ModelPickerContent
}

/** One drawable row: the option plus its selection / clickability decision. */
data class ModelRow(
    val option: ModelOption,
    val selected: Boolean,
    /** Expanded detail block (all 11 fields) — shown on the selected row. */
    val detail: List<ModelDetailRow>,
    /** selectable option && !readOnly — the composable passes this to `clickable(enabled=)`. */
    val clickable: Boolean,
)

fun modelPickerContent(
    models: List<ModelEntry>?,
    readOnly: Boolean,
    selectedId: String?,
    /**
     * Whether this state has a write method at all. False on the FR-8.46 degrade and on the
     * global-default scope of a real core (contract 1.5.0 has no default write) — rows render
     * but refuse clicks, which is the honest form of "you cannot change this here".
     */
    writesEnabled: Boolean = !readOnly,
    /**
     * The classified failure of the last pull, when there is one. Only consulted while
     * [models] is `null` (nothing to draw): with a previously pulled list on screen the rows
     * stay and the failure rides the card's error line instead of blanking the list.
     */
    loadFailure: ModelFailure? = null,
    /**
     * `models == null` because a real `model/list` pull has not answered yet — as opposed to
     * the seam's "this core has no model surface", which stays [NoModels].
     */
    awaitingFirstPull: Boolean = false,
): ModelPickerContent {
    if (models == null && readOnly) {
        return ModelPickerContent.ReadOnly(
            title = "此 core 不支持模型选择",
            body = "旧版 core 没有 model/list（-32601 / unknown method）——客户端降级为只读。" +
                "这里显示「列表未知」而不是「没有模型」：core 升级后列表会自动出现（FR-8.46）。",
        )
    }
    if (models == null && awaitingFirstPull) {
        return ModelPickerContent.Loading(
            title = "正在加载模型列表",
            body = "已向 core 请求 model/list，结果还没回来。这段时间不显示「无可用模型」：" +
                "还没拉到 ≠ 没有模型。",
        )
    }
    if (models == null && loadFailure != null) {
        return ModelPickerContent.LoadFailed(
            title = "模型列表加载失败",
            body = "这次 model/list 没有成功，列表内容未知——失败不等于「没有可用模型」（${loadFailure.category.label}）。" +
                "卡片上的错误行是 core 的原话；重试后再判断。",
        )
    }
    if (models.isNullOrEmpty()) {
        return ModelPickerContent.NoModels(
            title = "无可用模型",
            body = if (readOnly) {
                "此 core 未提供模型列表（旧版本或未配置）——由管理员在 core 侧配置，客户端降级为只读（FR-8.46）。"
            } else {
                "当前没有可路由的模型 provider；列表由管理员在 core 侧配置（model/list）。"
            },
        )
    }
    val interactive = !readOnly && writesEnabled
    val options = models.map(ModelOption::of)
    return ModelPickerContent.Rows(
        rows = options.map { option ->
            ModelRow(
                option = option,
                selected = option.id == selectedId,
                detail = option.detail,
                clickable = option.selectable && interactive,
            )
        },
        interactive = interactive,
    )
}
