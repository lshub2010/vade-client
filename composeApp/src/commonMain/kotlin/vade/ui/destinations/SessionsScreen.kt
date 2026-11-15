package vade.ui.destinations

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import vade.ui.UiText
import vade.ui.data.SessionGroup
import vade.ui.data.SessionSummary
import vade.ui.icon.VadeIcon
import vade.ui.icon.VadeIcons
import vade.ui.meta.MetaColors
import vade.ui.meta.MetaGroup
import vade.ui.skin.LocalSkin

/**
 * 会话 · 列表 (U1-a), v2 form (visual language §12「会话列表」row).
 *
 * Row spec: height ≥64dp, `sm` (16dp) corners, selected = `accentSoft` fill **plus a 3dp
 * accent bar on the left edge** (the bar is the unmistakable half of the pair; the 8% fill
 * alone reads as noise on canvas), timestamp in the muted token, and **no dividers** — rows
 * separate by spacing only. The bar is **drawn** by [SessionRow]'s `drawBehind`, not laid
 * out: a `Box(width = 3.dp).fillMaxHeight()` collapses to 3×0 inside a `LazyColumn` item
 * (unbounded height on the main axis ⇒ `fillMaxHeight` falls back to `minHeight = 0`), which
 * is exactly how D8 in `shots/g-visual-audit2` shipped a bar that nobody could see.
 * The list sits on `canvas`, edge-to-edge: [contentPadding]
 * (the shell's floating glass bar zones) is passed straight into the `LazyColumn`, so rows
 * really scroll behind the glass bars instead of stopping short of them.
 *
 * In scope here: grouping + ordering, the row's information shape, and opening a
 * session (the ✎ button lives in the shell top bar; `⌘K` search is U1-c). Out of scope:
 * message tree, streaming — nothing here talks to core.
 *
 * g-first-run: 顶部那行不再硬编码「假数据 · 未连接 core」。屏幕不知道 `SessionSource`，
 * 它只画调用方给的 [status]（判定在 `vade.ui.shell.listStatusOf`）：假源 → 「假数据」，
 * 真源已连上 → 什么都没有，真源失败 → 真实错误 + 自救命令。见 [ListStatus]。
 *
 * ## 分类 / 归档在行上可见（g-archive-ui）
 * 数据层早已把 `SessionSummary.archived` / `groupId` 投影出来（`SessionRowMapping`），
 * 但界面上一个都看不见 —— 分类等于不存在。这里只做**展示**：
 *  - 归档行：标题前一枚 13dp、`onSurfaceVariant` 的 [VadeIcons.Archive]（矢量图标，绝不用
 *    字符/汉字当图标）+ `contentDescription = "已归档"`。刻意用最小尺寸与 muted 色：
 *    明确可辨，但不跟标题抢注意力。
 *  - 有组的行：标题/预览下面一行「组色点 + 组名」。组名从 [metaGroups] 查（调用方传
 *    `MetaGroup` 列表）；查不到就把 `groupId` 原样显示 —— 显示一个裸 id 也比假装未分组
 *    诚实。组色同理：`colorHex` 缺失或非法（`MetaColors.isValid` 为 false）时点用
 *    muted 色，不编一个颜色出来。
 *  - 未归档且未分组：**一行都不多**（两个新块都在 `if` 里，不占位、不留空白）。
 *
 * [metaGroups] 的默认空表是刻意的最小侵入：测试与任何新调用点可以只画「裸 id」这一层
 * （`sessionGroupTag` 规则 3）。**生产调用点两个都必须显式传** —— `shell/AppShell.kt` 的
 * 宽屏两栏与窄屏单栏各一处，值来自 `metaGroupsOf(state)`（`session/group.list` 经适配器包）。
 * 只给其中一处赋值是本族缺陷最常见的复发形态（g-meta-groups 之前，行上一直显示裸 `g-1`）。
 *
 * [banner] is the shell's load-failure slot (g-error-retry): the session-list read failing
 * must be visible *here*, above the rows, together with its retry entry. It is a slot and
 * not a `LoadError?` parameter so this screen keeps knowing nothing about the loader.
 */
@Composable
fun SessionsScreen(
    groups: List<SessionGroup>,
    selectedId: String?,
    twoPane: Boolean,
    onOpen: (SessionSummary) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    metaGroups: List<MetaGroup> = emptyList(),
    /** Shell-owned failure banner (see `shell/LoadErrorBanner.kt`); null = nothing to say. */
    banner: (@Composable () -> Unit)? = null,
    /**
     * 列表头状态横幅要画什么。默认值 [ListStatus.Fixtures] 是**本棒之前**的行为（永远画
     * 那行假数据提示），保留它是为了让任何没跟上接线的调用点行为逐字节不变；生产调用点
     * （`AppShell`）必须显式传真实状态。
     */
    status: ListStatus = ListStatus.Fixtures,
) {
    val skin = LocalSkin.current
    LazyColumn(
        modifier = modifier.fillMaxSize().background(skin.colors.canvas),
        contentPadding = contentPadding,
    ) {
        if (banner != null) {
            item(key = "load-error-banner") { banner() }
        }
        // 两种「说明数据现状」的出口，合并裁定（g-first-run × g-error-retry）：
        //  - 已连接 → 整个 item 不存在（空文本仍占 10+20dp 行高，顶部会凭空多一段空白）；
        //  - 假数据 + 有失败横幅 → 不画：两者互相打脸（沿用 g-error-retry 的理由）；
        //  - 失败 → **两条都画**：shell 那条带重试按钮（唯一可操作出口），
        //    [ListStatus.Failed] 带**可照抄的自救命令**（`VADE_SOURCE=real` /
        //    `VADE_PAIR_CODE=` / 码从哪取）—— 后者是「core 根本没起」时唯一有用的东西，
        //    不能被去重掉。代价是失败时两张卡说同一件事的一部分，
        //    记为**已知视觉欠账**：正确形态是把命令并进带重试的那张卡，等一次专门的
        //    `LoadErrorBanner` 改动；在那之前宁可信息重复，也不让用户看不到怎么把 core 起来。
        val drawStatus = status != ListStatus.Connected &&
            !(status is ListStatus.Fixtures && banner != null)
        if (drawStatus) {
            item(key = "list-status") {
                ListStatusBanner(status, compact = banner != null)
            }
        }

        groups.forEach { group ->
            item(key = "hdr-${group.bucket.name}") {
                // 分组头：只有文字标签 + 数量，没有 1px 规则线——§12 要求列表靠间距分层。
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    UiText(
                        text = group.bucket.label,
                        size = 11,
                        weight = FontWeight.SemiBold,
                        color = skin.colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    UiText(
                        text = "${group.sessions.size}",
                        size = 11,
                        color = skin.colors.onSurfaceVariant,
                    )
                }
            }
            items(group.sessions, key = { it.id }) { session ->
                SessionRow(
                    session = session,
                    selected = session.id == selectedId && twoPane,
                    onClick = { onOpen(session) },
                    groupTag = sessionGroupTag(session.groupId, metaGroups),
                )
            }
        }
        item(key = "tail") { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun SessionRow(
    session: SessionSummary,
    selected: Boolean,
    onClick: () -> Unit,
    groupTag: SessionGroupTag?,
) {
    val skin = LocalSkin.current
    val accentSoft = skin.colors.primary.copy(alpha = if (skin.colors.isDark) 0.14f else 0.08f)
    val shape = RoundedCornerShape(skin.shapes.sm)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .heightIn(min = 64.dp)
            .clip(shape)
            .background(if (selected) accentSoft else androidx.compose.ui.graphics.Color.Transparent)
            // 选中态的另一半（§12「`accentSoft` + 左侧 3dp 条」）：**画**，不**排**。
            // 这里原先是一个 `Box(Modifier.width(3.dp).fillMaxHeight())`，而 `LazyColumn` 的
            // item 在主轴上给的是**无界**高度（`maxHeight = Infinity`），`fillMaxHeight()`
            // 对这种约束直接退化成 `minHeight`（= 0）—— 那根条被量成 3×0，静默消失。
            // 观感上就是「选中行只有填充、没有左条」（shots/g-visual-audit2 的 D8）。
            // `drawBehind` 用的是行**量完之后**的真实高度（≥64dp、随内容长），并且画在
            // `.clip(shape)` 之内，两端随 `sm` 圆角自然收进去；它不占布局宽度，所以
            // 选中/未选中切换时文字左缘不会跳。
            .drawBehind {
                if (selected) {
                    drawRect(size = Size(ACCENT_BAR_WIDTH.toPx(), size.height), color = skin.colors.primary)
                }
            }
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左条由上面的 drawBehind 画在行自己的高度里，不占布局宽度（所以这里直接 16dp：
        // 3dp 条 + 13dp 内容间距，条落在 padding 里，不压 32dp 头像）。
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(skin.shapes.sm))
                    .background(skin.colors.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                UiText(
                    text = session.title.take(1),
                    size = 13,
                    weight = FontWeight.SemiBold,
                    color = skin.colors.primary,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (session.archived) {
                        // 「已归档」标记：矢量图标（ImageVector，禁字符/汉字当图标），13dp +
                        // muted 色 —— 明确可辨但不刺眼；contentDescription 同时喂给读屏与 UI 测试。
                        VadeIcon(
                            vector = VadeIcons.Archive,
                            contentDescription = ARCHIVED_BADGE_LABEL,
                            size = 13.dp,
                            tint = skin.colors.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    UiText(
                        text = session.title,
                        modifier = Modifier.weight(1f, fill = false),
                        size = 15,
                        weight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (session.preview.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    UiText(
                        text = session.preview,
                        size = 12,
                        color = skin.colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (groupTag != null) {
                    // 分类：组色点 + 组名。只有真的属于某个分组才出现 —— 未分组行一行不多。
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(groupTag.color ?: skin.colors.onSurfaceVariant),
                        )
                        Spacer(Modifier.width(4.dp))
                        UiText(
                            text = groupTag.label,
                            modifier = Modifier.weight(1f, fill = false),
                            size = 10,
                            color = skin.colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
                // 时间戳 = muted token（onSurfaceVariant，即视觉语言的 textMuted）。
                UiText(session.updatedLabel, size = 11, color = skin.colors.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (session.pinned) {
                        UiText("置顶", size = 9, color = skin.colors.primary)
                        Spacer(Modifier.width(4.dp))
                    }
                    if (session.unread) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(skin.colors.error))
                        Spacer(Modifier.width(4.dp))
                    }
                    if (session.model != null) {
                        UiText(session.model, size = 9, color = skin.colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/**
 * 「已归档」标记的无障碍文案 —— 同时是 [VadeIcons.Archive] 的 `contentDescription`
 * 与 UI 测试的定位点（一个字符串只写一次，避免标记和测试各说各话）。
 */
internal const val ARCHIVED_BADGE_LABEL = "已归档"

/**
 * 选中行左侧强调条的宽度 —— `docs/visual-language.md` §12「会话列表」那一行：
 * 「选中态 `accentSoft` + **左侧 3dp 条**」。条由 [SessionRow] 的 `drawBehind` 画，
 * 不参与布局（见那一段注释：`fillMaxHeight()` 在 `LazyColumn` 的 item 里会塌成 0）。
 */
internal val ACCENT_BAR_WIDTH = 3.dp

/**
 * 会话行上的「分类」展示值：组名（or 裸 id）+ 组色。
 *
 * 单独抽成一个小值类型而不是在 Composable 里现查，是为了让「查不到组怎么办」这条规则
 * 可以被纯函数测试钉住（Compose 里再测一遍字符串拼装纯属浪费）。
 *
 * [color] 可空：`MetaGroup.colorHex` 本身可空（契约 1.4.0 起 `color` 可被清除），且还有
 * 非法 `#RRGGBB` 的可能 —— 两种情况都**不编颜色**，由调用方退化成 muted 色。
 */
internal data class SessionGroupTag(val label: String, val color: Color?)

/**
 * `groupId` + 已知分组表 → 行上要显示的 [SessionGroupTag]；`null` = 不该显示任何东西。
 *
 * 规则（每条都有测试）：
 *  1. `groupId` 为 null / 空白 ⇒ **null**（未分组：连空行都不留，否则每行都多一段空白）；
 *  2. 在 [groups] 里查到同名分组 ⇒ 用它的 `name`；`name` 是空白串的畸形组同样退化到 id；
 *  3. 查不到（分组表没加载 / 传了空表 / id 拼错）⇒ **原样显示 `groupId`**。裸 id 不好看，
 *     但它比「悄悄显示成未分组」诚实，也比空着更能提示「组名没接上」。
 *  4. 颜色只认合法的 `#RRGGBB`（[MetaColors.isValid]）；缺省/非法 ⇒ null ⇒ muted 点。
 */
internal fun sessionGroupTag(groupId: String?, groups: List<MetaGroup>): SessionGroupTag? {
    val id = groupId?.trim().orEmpty()
    if (id.isEmpty()) return null
    val group = groups.firstOrNull { it.id == id }
    val label = group?.name?.trim()?.takeIf { it.isNotEmpty() } ?: id
    val color = group?.colorHex?.takeIf { MetaColors.isValid(it) }?.let { MetaColors.parse(it) }
    return SessionGroupTag(label = label, color = color)
}
