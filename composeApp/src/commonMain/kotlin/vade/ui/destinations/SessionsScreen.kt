package vade.ui.destinations

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.dp
import vade.ui.UiText
import vade.ui.data.SessionGroup
import vade.ui.data.SessionSummary
import vade.ui.skin.LocalSkin

/**
 * 会话 · 列表 (U1-a).
 *
 * In scope here: grouping + ordering, the row's information shape, and opening a
 * session (the ✎ button lives in the shell top bar; `⌘N` is U1-g).
 * Out of scope: search overlay (`⌘K`, U1-c), message tree, streaming — nothing here
 * talks to core, and the header says so.
 */
@Composable
fun SessionsScreen(
    groups: List<SessionGroup>,
    selectedId: String?,
    twoPane: Boolean,
    onOpen: (SessionSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val skin = LocalSkin.current
    LazyColumn(modifier = modifier.fillMaxSize().background(skin.colors.surface)) {
        item(key = "u1a-note") {
            UiText(
                text = "假数据 · 未连接 core（U1-a 只做骨架，U1-b 接线）",
                size = 10,
                color = skin.colors.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        groups.forEach { group ->
            item(key = "hdr-${group.bucket.name}") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    UiText(
                        text = group.bucket.label,
                        size = 10,
                        weight = FontWeight.SemiBold,
                        color = skin.colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier.height(1.dp).weight(1f).background(skin.colors.outline.copy(alpha = 0.25f)),
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
    skin.ListItem(
        title = session.title,
        leading = {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(skin.shapes.small))
                    .background(skin.colors.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                UiText(
                    text = session.title.take(1),
                    size = 13,
                    weight = FontWeight.SemiBold,
                    color = skin.colors.primary,
                )
            }
        },
        subtitle = session.preview,
        trailing = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                UiText(session.updatedLabel, size = 10, color = skin.colors.onSurfaceVariant)
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
        },
        onClick = onClick,
        selected = selected,
    )
}
