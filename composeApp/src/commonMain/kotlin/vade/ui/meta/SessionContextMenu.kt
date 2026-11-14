package vade.ui.meta

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import vade.ui.UiText
import vade.ui.component.liquid.VadeShadows
import vade.ui.component.liquid.layeredShadow
import vade.ui.icon.VadeIcon
import vade.ui.icon.VadeIcons
import vade.ui.skin.LocalSkin

/**
 * 会话行上下文菜单（w-c-meta）：固定 6 项（[SessionMetaState.SESSION_MENU_ITEMS]）。
 *
 * 呈现规则（docs/visual-language.md §3/§8/§10）：
 *  - 每项 = 矢量图标 + 文字，图标随当前态翻转（置顶↔取消置顶等）；
 *  - 破坏性项（删除）整行用 error 色，且点击**只打开确认弹层** —— 真正的删除
 *    回调发生在 [SessionMetaState.confirmDelete]，未确认绝不触发（测试④）；
 *  - 卡片：`lg`(24dp) 圆角 + surface + 发丝线 + [VadeShadows.float]；
 *  - 行高 ≥44dp（§3.4 触摸区）。
 *
 * 本组合项渲染在**会话行内部**（[Popup] 以行右上角为锚），由 [SessionMetaHost]
 * 在 `state.menuFor == session.id` 时挂载。
 */
@Composable
internal fun SessionContextMenu(
    state: SessionMetaState,
    sessionId: String,
    onDispatch: (SessionMenuItem) -> Unit,
) {
    val skin = LocalSkin.current
    val density = LocalDensity.current
    val session = state.sessions.firstOrNull { it.id == sessionId } ?: return
    Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(x = with(density) { 0.dp.roundToPx() }, y = with(density) { 48.dp.roundToPx() }),
        onDismissRequest = { state.closeSessionMenu() },
        properties = PopupProperties(focusable = true),
    ) {
        val shape = RoundedCornerShape(skin.shapes.lg)
        Column(
            Modifier
                .width(220.dp)
                .layeredShadow(VadeShadows.float, shape, dark = skin.colors.isDark)
                .clip(shape)
                .background(skin.colors.surface)
                .border(1.dp, skin.colors.hairline, shape)
                .padding(vertical = 6.dp),
        ) {
            state.menuItems().forEach { item ->
                MenuRow(
                    label = item.label(session),
                    icon = item.icon(session),
                    destructive = item.destructive,
                    onClick = {
                        state.closeSessionMenu()
                        onDispatch(item)
                    },
                )
            }
        }
    }
}

/** 菜单行图标随会话当前态翻转：置顶→[VadeIcons.PinOff]，归档→[VadeIcons.ArchiveOff]。 */
private fun SessionMenuItem.icon(session: MetaSession): ImageVector = when (this) {
    SessionMenuItem.Rename -> VadeIcons.Compose
    SessionMenuItem.TogglePin -> if (session.pinned) VadeIcons.PinOff else VadeIcons.Pin
    SessionMenuItem.ToggleArchive -> if (session.archived) VadeIcons.ArchiveOff else VadeIcons.Archive
    SessionMenuItem.MoveToGroup -> VadeIcons.Folder
    SessionMenuItem.NewGroup -> VadeIcons.FolderPlus
    SessionMenuItem.Delete -> VadeIcons.Trash
}

@Composable
private fun MenuRow(
    label: String,
    icon: ImageVector,
    destructive: Boolean,
    onClick: () -> Unit,
) {
    val skin = LocalSkin.current
    val tint = if (destructive) skin.colors.error else skin.colors.onSurface
    Row(
        Modifier
            .heightIn(min = 44.dp) // §3.4：触摸区 ≥44dp
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VadeIcon(icon, contentDescription = label, size = 20.dp, tint = tint)
        Spacer(Modifier.width(12.dp))
        UiText(text = label, size = 14, color = tint)
    }
}
