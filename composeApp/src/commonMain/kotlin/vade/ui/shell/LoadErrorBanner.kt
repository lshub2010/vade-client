package vade.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import vade.ui.UiText
import vade.ui.data.VadeErrorCategory
import vade.ui.icon.VadeIcon
import vade.ui.icon.VadeIcons
import vade.ui.skin.LocalSkin

/**
 * g-error-retry · the **live list read failure** gets a banner that can actually be acted
 * on. Before this, `UiState.sessionsError` was published by the loader and read by
 * nothing: with core not running (or the wrong port) the user was stuck with no way out
 * but restarting the app. `UiState.retrySessions()` already existed — this file only
 * gives it a surface.
 *
 * ## 消息线程没有第二条横幅
 *
 * 旧实现还有一条 `Messages` 横幅，来自 `SessionsLoader` 的 `messages(id)` 读。那条读
 * 与 `ChatState.loadHistory` 是**同一个** `ui/attach`，等于每次选会话 attach 两次；两条
 * 失败横幅也跟着重复。现在线程的 Loading / 空 / 失败三态由 `ChatThread` 从
 * `ChatState.phase` / `loadError` 一处渲染（重试 = `ChatState.retryHistory()`），
 * 这里只保留会话列表（与新会话）两个**动作**横幅。
 *
 * ## In-flight state (why the button cannot be mashed)
 * Every read publishes `error = null` **synchronously** when it starts (`SessionsLoader`
 * clears the error before launching the fetch) and re-publishes one only when it fails. So
 * inside a banner, `error == null` is exactly "an attempt I started is still in flight"
 * (success clears the banner entirely: `phase = Ready`, `error = null`). That gives the
 * button a real disabled state with zero new state and no timers:
 *  - failure → retry enabled;
 *  - click → error cleared → model says `retrying = true`, button disabled, label 重试中…;
 *  - fail again → new error → enabled with the new category/text;
 *  - success → banner gone (rows are on screen).
 * [loadBannerModel] is a pure function so all four transitions are testable without a
 * composition.
 *
 * ## Icons
 * `VadeIcons.Refresh` (retry) and `VadeIcons.Waiting` (in flight) are the existing semantic
 * exports — both are `ImageVector`s and `VadeIcons.kt` is untouched (no third vector needed,
 * no character glyph anywhere: docs/visual-language.md §3).
 */
internal enum class LoadBannerKind(
    /** Banner container tag; also what distinguishes the banners in tests. */
    val tag: String,
    /** Retry button tag. */
    val retryTag: String,
    /** What failed, lowercase, used inside the sentence copy. */
    val subject: String,
    /** 失败标题（A4-1：新建是「动作失败」，不是「加载失败」，逐类可改文案）。 */
    val failTitle: String,
    /** A11y description of the retry button (never empty, never a glyph). */
    val retryDescription: String,
) {
    Sessions(
        tag = "load-banner:sessions",
        retryTag = "load-banner:sessions:retry",
        subject = "会话列表",
        failTitle = "会话列表加载失败",
        retryDescription = "重试加载会话列表",
    ),
    NewSession(
        tag = "load-banner:new-session",
        retryTag = "load-banner:new-session:retry",
        subject = "新建会话",
        failTitle = "新建会话失败",
        retryDescription = "重试新建会话",
    ),
}

/** Everything the banner draws, derived (no Compose state inside). */
internal data class LoadBannerModel(
    val tag: String,
    val retryTag: String,
    /** 失败标题 / 重试中标题. */
    val title: String,
    /** 分类文案 + C-04 错误码（有码时），横幅最该被读到的一行. */
    val categoryLine: String,
    /** The transport's own words (kept short by the layout, never the only line). */
    val detail: String,
    /** What the user can do about this bucket. */
    val hint: String,
    /** An attempt this banner started is still in flight. */
    val retrying: Boolean,
    /** `false` while [retrying] — the button refuses clicks in that window. */
    val retryEnabled: Boolean,
    val retryLabel: String,
    val retryDescription: String,
)

/** Per-bucket guidance; the banner leads with the classification, this line is the action. */
internal fun loadErrorHint(category: VadeErrorCategory): String = when (category) {
    VadeErrorCategory.Retryable -> "这次读取是暂时失败，可以直接重试。"
    VadeErrorCategory.NeedsReconnect -> "core 可能没有启动，或地址 / 端口不对：确认后在下面重试。"
    VadeErrorCategory.NeedsUserAction -> "需要先处理权限 / 配置 / 配额，再重试。"
    VadeErrorCategory.Fatal -> "版本或安全状态不匹配，重试通常无效——需要换版本或重新配对。"
}

/**
 * The banner's state machine as a pure function.
 *
 * @param error the loader's classified failure right now (`null` = no failure surfaced).
 * @param retained the last error this banner displayed. Needed because a retry clears
 *   [error] while the fetch runs — without it the banner would vanish mid-retry instead of
 *   showing 重试中…, and a user would have no idea anything happened.
 *
 * Returns `null` when there is nothing to say: no failure and no retry in flight.
 */
internal fun loadBannerModel(
    kind: LoadBannerKind,
    phase: LoadPhase,
    error: LoadError?,
    retained: LoadError?,
): LoadBannerModel? {
    if (error != null) {
        val code = error.code
        return LoadBannerModel(
            tag = kind.tag,
            retryTag = kind.retryTag,
            title = kind.failTitle,
            categoryLine = if (code.isNullOrBlank()) error.category.label else "${error.category.label} · $code",
            detail = error.detail,
            hint = loadErrorHint(error.category),
            retrying = false,
            retryEnabled = true,
            retryLabel = "重试",
            retryDescription = kind.retryDescription,
        )
    }
    // error == null: either nothing ever failed (hidden) or an attempt is running. Only the
    // latter keeps a banner up — and only when there is a failure to explain it.
    val previous = retained ?: return null
    if (phase != LoadPhase.Loading && phase != LoadPhase.Failed) return null
    return LoadBannerModel(
        tag = kind.tag,
        retryTag = kind.retryTag,
        // A failed load with rows still on screen keeps `phase = Failed` (stale-while-
        // revalidate), an empty-list load goes `Loading` — both mean "attempt in flight".
        title = "正在重试${kind.subject}…",
        categoryLine = if (previous.code.isNullOrBlank()) previous.category.label else "${previous.category.label} · ${previous.code}",
        detail = previous.detail,
        hint = "上一次的失败信息保留在这里；结果回来前不再重复发起读取。",
        retrying = true,
        retryEnabled = false,
        retryLabel = "重试中…",
        retryDescription = kind.retryDescription,
    )
}

/**
 * Session-list failure banner (会话列表). Call sites render it as a list header; the retry
 * goes to `UiState.retrySessions()`.
 */
@Composable
fun SessionListLoadBanner(
    phase: LoadPhase,
    error: LoadError?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) = LoadBannerHost(LoadBannerKind.Sessions, phase, error, onRetry, modifier)

/**
 * A4-1 · 新建会话失败横幅（✎ 的动作失败，不是一次「读取」）。独立的 kind / 独立的重试
 * （重试入口 = `UiState.newSession()`，它会真的重发 `ui/attach(null)`，1 → 2）—— 与
 * 列表横幅互不合并、互不遮挡。
 */
@Composable
fun NewSessionLoadBanner(
    phase: LoadPhase,
    error: LoadError?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) = LoadBannerHost(LoadBannerKind.NewSession, phase, error, onRetry, modifier)

@Composable
private fun LoadBannerHost(
    kind: LoadBannerKind,
    phase: LoadPhase,
    error: LoadError?,
    onRetry: () -> Unit,
    modifier: Modifier,
) {
    // Retains the last failure for the duration of a retry (see `loadBannerModel`). Cleared
    // once a read succeeds, so a later *first* load (event refresh) never shows stale text.
    val retained = remember(kind) { mutableStateOf<LoadError?>(null) }
    LaunchedEffect(kind, phase, error) {
        when {
            error != null -> retained.value = error
            phase == LoadPhase.Ready -> retained.value = null
        }
    }
    val model = loadBannerModel(kind, phase, error, retained.value) ?: return
    LoadErrorBanner(model = model, onRetry = onRetry, modifier = modifier)
}

/**
 * The banner itself: classification first, then the transport's words, then a real button
 * (not a text link) that re-issues the same read.
 */
@Composable
internal fun LoadErrorBanner(
    model: LoadBannerModel,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val skin = LocalSkin.current
    val accent = skin.colors.error
    val shape = RoundedCornerShape(skin.shapes.sm)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(model.tag)
            .clip(shape)
            .background(accent.copy(alpha = if (skin.colors.isDark) 0.16f else 0.06f))
            .border(1.dp, accent.copy(alpha = 0.35f), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            VadeIcon(
                vector = if (model.retrying) VadeIcons.Waiting else VadeIcons.Failed,
                // Decorative here: the title next to it already says 加载失败.
                contentDescription = null,
                size = 16.dp,
                tint = accent,
            )
            Spacer(Modifier.width(8.dp))
            UiText(
                text = model.title,
                size = 13,
                weight = FontWeight.SemiBold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        UiText(model.categoryLine, size = 11, weight = FontWeight.Medium, color = skin.colors.onSurface)
        UiText(
            text = model.detail,
            size = 11,
            color = skin.colors.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        UiText(model.hint, size = 11, color = skin.colors.onSurfaceVariant, maxLines = 2)
        RetryEntry(model = model, onRetry = onRetry)
    }
}

/**
 * The retry entry: a bordered pill with a vector icon + label, `Role.Button` for
 * accessibility, keyboard-activatable through `Modifier.clickable` (Enter / Spacebar on
 * desktop — pinned by `LoadErrorBannerTest.retryEntryIsKeyboardActivatable`), and
 * **disabled** for the whole in-flight window so a double click cannot fire two reads.
 */
@Composable
private fun RetryEntry(model: LoadBannerModel, onRetry: () -> Unit) {
    val skin = LocalSkin.current
    val enabled = model.retryEnabled
    val accent = skin.colors.error
    val shape = RoundedCornerShape(percent = 50)
    val ink = if (enabled) skin.colors.onSurface else skin.colors.onSurfaceVariant
    Row(
        modifier = Modifier
            .testTag(model.retryTag)
            // 44dp: the visual language's minimum hit target, even inside a dense banner.
            .heightIn(min = 44.dp)
            .alpha(if (enabled) 1f else 0.55f)
            .clip(shape)
            .background(if (enabled) skin.colors.surface else Color.Transparent)
            .border(1.dp, if (enabled) accent.copy(alpha = 0.55f) else skin.colors.hairline, shape)
            .clickable(
                enabled = enabled,
                onClickLabel = model.retryDescription,
                role = Role.Button,
                onClick = onRetry,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VadeIcon(
            vector = if (model.retrying) VadeIcons.Waiting else VadeIcons.Refresh,
            // The button's only accessible name source besides the label text: kept non-null
            // on purpose (a11y requirement), and it rides into the button's merged semantics.
            contentDescription = model.retryDescription,
            size = 16.dp,
            tint = ink,
        )
        Spacer(Modifier.width(6.dp))
        UiText(model.retryLabel, size = 12, weight = FontWeight.Medium, color = ink)
    }
}
