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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import vade.ui.UiText
import vade.ui.data.RawSourceStrings
import vade.ui.data.SessionGroup
import vade.ui.data.SessionSummary
import vade.ui.data.rawSourceStrings
import vade.ui.icon.VadeIcon
import vade.ui.icon.VadeIcons
import vade.ui.meta.MetaColors
import vade.ui.meta.MetaGroup
import vade.ui.skin.LocalSkin

/**
 * 会话 · 列表 (U1-a), Q2-B 单行形态（g-route-transition 口径变更，2026-11-15）。
 *
 * Row spec（Q2-B 覆盖 §12 旧行）：**单行条目 = 组色点 + 标题（单行省略）**——
 * 预览行、右侧时间戳/置顶/未读/模型列、行头像全部移除；分组色点保留（分类是刚接通的），
 * 组名移到色点的读屏语义里。行块左右内缩 8dp；选中态 = 带圆角的强调色块
 * （见 [SELECTED_BLOCK_CORNER] 一组常量），不再是「accentSoft + 左侧 3dp 条」。
 * 列表仍靠间距分层、没有分隔线。The list sits on `canvas`, edge-to-edge: [contentPadding]
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
    /**
     * g-pair-first-run：首次运行引导卡的原始配置通道。默认 = 平台真实通道（生产逐字节
     * 不变 —— 引导卡的可见性判定在卡内做，`AppShell` 的两处调用点一行未动）；测试注入。
     */
    pairingRaw: () -> RawSourceStrings = ::rawSourceStrings,
    /**
     * g-route-transition：挂到**选中行**上的焦点请求器——手机抽屉重新滑出时由宿主请求，
     * 让焦点回到原来那一行（无障碍口径）。null = 无人请求（旧调用点不需要动焦点）。
     */
    selectedRowFocus: FocusRequester? = null,
) {
    val skin = LocalSkin.current
    LazyColumn(
        modifier = modifier.fillMaxSize().background(skin.colors.canvas),
        contentPadding = contentPadding,
    ) {
        // g-pair-first-run：首次运行引导卡（真源且缺配对码时）。**只加这一块** —— 现有
        // 的 load-error-banner / list-status 两块横幅逻辑一行未动。卡不可见时一个节点都
        // 不组合，不占行高。
        item(key = "first-run-pairing") {
            FirstRunPairingCard(raw = pairingRaw)
        }
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
                val selected = session.id == selectedId
                SessionRow(
                    session = session,
                    selected = selected,
                    onClick = { onOpen(session) },
                    groupTag = sessionGroupTag(session.groupId, metaGroups),
                    // g-route-transition：焦点请求器只挂在选中行上（抽屉回开时焦点回到那一行）。
                    modifier = if (selected && selectedRowFocus != null) {
                        Modifier.focusRequester(selectedRowFocus)
                    } else {
                        Modifier
                    },
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
    modifier: Modifier = Modifier,
) {
    val skin = LocalSkin.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            // Q2-B（g-route-transition 口径变更）：行块**左右内缩 8dp**——选中态不是通栏
            // 满宽矩形，而是一块带圆角的强调色块。
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .heightIn(min = SELECTED_BLOCK_MIN_HEIGHT)
            .clip(RoundedCornerShape(if (selected) SELECTED_BLOCK_CORNER else skin.shapes.sm))
            // 选中态 = **带圆角的强调色块**（accent 淡染，亮 12% / 暗 16%，都在 10–16% 口径内）。
            // 旧 §12 的「左侧 3dp 条」随 Q2-B 一并废除（口径变更覆盖视觉语言那一行）。
            .background(
                if (selected) {
                    skin.colors.primary.copy(
                        alpha = if (skin.colors.isDark) SELECTED_BLOCK_ALPHA_DARK else SELECTED_BLOCK_ALPHA_LIGHT,
                    )
                } else {
                    Color.Transparent
                },
            )
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 组色点槽位：固定宽度让「有组/无组」行的标题起点对齐；点 8dp、查不到合法色时
        // 退化 muted（sessionGroupTag 的既有规则 3/4，不动）。
        Box(
            Modifier.width(24.dp).height(SELECTED_BLOCK_MIN_HEIGHT),
            contentAlignment = Alignment.Center,
        ) {
            if (groupTag != null) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(groupTag.color ?: skin.colors.onSurfaceVariant)
                        // Q2-B 只留色点不留组名文字（单行化）：组名移到读屏语义里，
                        // 同时是 UI 测试定位「分组色点仍在」的锚点。
                        .semantics { contentDescription = "分组：${groupTag.label}" },
                )
            }
        }
        if (session.archived) {
            // 「已归档」标记（g-archive-ui 原样保留）：矢量图标 13dp + muted 色。
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
            modifier = Modifier.weight(1f).padding(end = 12.dp),
            size = 15,
            weight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 「已归档」标记的无障碍文案 —— 同时是 [VadeIcons.Archive] 的 `contentDescription`
 * 与 UI 测试的定位点（一个字符串只写一次，避免标记和测试各说各话）。
 */
internal const val ARCHIVED_BADGE_LABEL = "已归档"

/**
 * Q2-B 选中块的**可断言几何/色值常量**（g-route-transition 口径变更）：选中行是
 * 「带圆角的强调色块」，不是通栏满宽矩形——
 *  - 圆角 [SELECTED_BLOCK_CORNER]，落在口径要求的 12–16dp 里；
 *  - accent 淡染透明度亮/暗两档（[SELECTED_BLOCK_ALPHA_LIGHT] / [SELECTED_BLOCK_ALPHA_DARK]），
 *    都落在口径要求的 10–16% 里，文字对比不受影响（底色只被染了这么一点）；
 *  - 行块左右各内缩 8dp（`SessionRow` 的 `padding(horizontal = 8.dp)`，测试量像素）；
 *  - 「已选中」语义（`selected = true` 的行）不再有 §12 的 3dp 左条——那根条随 Q2-B
 *    单行化一并废除，`SessionRowSelectedBlockTest` 用像素断言接住新形态。
 */
internal val SELECTED_BLOCK_CORNER = 14.dp
internal const val SELECTED_BLOCK_ALPHA_LIGHT = 0.12f
internal const val SELECTED_BLOCK_ALPHA_DARK = 0.16f
internal val SELECTED_BLOCK_MIN_HEIGHT = 48.dp

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
