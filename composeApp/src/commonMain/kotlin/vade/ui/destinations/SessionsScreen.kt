package vade.ui.destinations

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
 * separate by spacing only. The list sits on `canvas`, edge-to-edge: [contentPadding]
 * (the shell's floating glass bar zones) is passed straight into the `LazyColumn`, so rows
 * really scroll behind the glass bars instead of stopping short of them.
 *
 * In scope here: grouping + ordering, the row's information shape, and opening a
 * session (the ✎ button lives in the shell top bar; `⌘K` search is U1-c). Out of scope:
 * message tree, streaming — nothing here talks to core, and the header says so.
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
 * [metaGroups] 的默认空表是刻意的最小侵入：`shell/AppShell.kt` 调本屏时不传，于是线上
 * 先退化成「显示 groupId」；把 `SessionMetaState.groups` 接进来只是给这个形参赋值的一行。
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
) {
    val skin = LocalSkin.current
    LazyColumn(
        modifier = modifier.fillMaxSize().background(skin.colors.canvas),
        contentPadding = contentPadding,
    ) {
        item(key = "u1a-note") {
            UiText(
                text = "假数据 · 未连接 core（U1-a 只做骨架，U1-b 接线）",
                size = 10,
                color = skin.colors.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            )
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
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 选中态的另一半：3dp accent 条，贴行左缘（随 sm 圆角裁切）。未选中时占位同宽，
        // 保证选中与否文字左缘对齐。
        Box(Modifier.width(3.dp).fillMaxHeight().background(if (selected) skin.colors.primary else androidx.compose.ui.graphics.Color.Transparent))
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(start = 13.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
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
