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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import vade.ui.UiText
import vade.ui.data.SessionGroup
import vade.ui.data.SessionSummary
import vade.ui.skin.LocalSkin

/**
 * 会话 · 列表 (U1-a), v2 form (visual language §12「会话列表」row).
 *
 * Row spec: height ≥64dp, `sm` (16dp) corners, selected = `accentSoft` fill **plus a 3dp
 * accent bar on the left edge** (the bar is the unmistakable half of the pair; the 8% fill
 * alone reads as noise on canvas), timestamp in the muted token, and **no dividers** —
 * rows separate by spacing only. The list sits on `canvas`, edge-to-edge: [contentPadding]
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
 */
@Composable
fun SessionsScreen(
    groups: List<SessionGroup>,
    selectedId: String?,
    twoPane: Boolean,
    onOpen: (SessionSummary) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
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
        // 已连接时整个 item 都不存在（不是渲染空文本）：空文本仍占 10+20 dp 的行高，
        // 列表顶部会凭空多一段空白。
        if (status != ListStatus.Connected) {
            item(key = "list-status") {
                ListStatusBanner(status)
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
                )
            }
        }
        item(key = "tail") { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun SessionRow(session: SessionSummary, selected: Boolean, onClick: () -> Unit) {
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
                UiText(
                    text = session.title,
                    size = 15,
                    weight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
