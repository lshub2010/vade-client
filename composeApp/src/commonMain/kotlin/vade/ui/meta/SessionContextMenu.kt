package vade.ui.meta

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
 * 键盘/无障碍（B2 s-meta-host）：focusable 的 Popup 抓住焦点，**Esc 关闭菜单**
 * （[onPreviewKeyEvent] 挂在卡片根上）；每行 [focusable]，Tab 可走、Enter/Space
 * 触发 —— 长按菜单不只有触屏一条路。系统返回键尚未接管（g-back-handler 未合入，
 * 待接一行：宿主 `BackHandler { state.closeSessionMenu() }`）。
 *
 * ## 锚点（alpha 审查 J3，2026-11-16）
 * 菜单曾经**固定**以列表窗格右上角为锚（`Popup(alignment = TopEnd, offset = 48dp)`），
 * 桌面按第 25 行时菜单弹在屏幕顶部 —— 用户看不出自己在改哪一条。现在由宿主
 * `SessionMetaOverlayHost` 在行下方放一个零尺寸锚点，[placement] 就是那个锚点的父坐标
 * 落点（[IntOffset.Zero]）；**头部再加一行会话标题**，让「改的是哪条」一眼可认。
 * [placement] 为 null 时退回旧行为，只有拿不到行矩形的老调用点会走。
 */
@Composable
internal fun SessionContextMenu(
    state: SessionMetaState,
    sessionId: String,
    onDispatch: (SessionMenuItem) -> Unit,
    /** 菜单左上角在父布局坐标里的落点（px）；null = 旧行为（父布局右上角、下移 48dp）。 */
    placement: IntOffset? = null,
) {
    val skin = LocalSkin.current
    val density = LocalDensity.current
    val session = state.sessions.firstOrNull { it.id == sessionId } ?: return
    // 键盘可达（B2）：菜单卡片自己抓焦点 —— Esc/Tab/Enter 都有确定的路，不依赖
    // 系统 Popup 的初始焦点落点。
    val menuFocus = remember { FocusRequester() }
    Popup(
        alignment = if (placement != null) Alignment.TopStart else Alignment.TopEnd,
        offset = placement ?: IntOffset(x = 0, y = with(density) { LegacyMenuTopGap.roundToPx() }),
        onDismissRequest = { state.closeSessionMenu() },
        properties = PopupProperties(focusable = true),
    ) {
        val shape = RoundedCornerShape(skin.shapes.lg)
        Column(
            Modifier
                .width(MenuWidth)
                .layeredShadow(VadeShadows.float, shape, dark = skin.colors.isDark)
                .clip(shape)
                .background(skin.colors.surface)
                .border(1.dp, skin.colors.hairline, shape)
                .focusRequester(menuFocus)
                .focusable()
                .onPreviewKeyEvent { event ->
                    // Esc 关闭：focusable Popup 抓住焦点后按键先到卡片根。
                    if (event.type == KeyEventType.KeyUp && event.key == Key.Escape) {
                        state.closeSessionMenu()
                        true
                    } else {
                        false
                    }
                }
                .padding(vertical = 6.dp),
        ) {
            // J3 · 头部：会话标题（单行省略）。长按菜单必须先回答「我改的是哪一条」——
            // 只列「重命名/置顶/归档…」而不说是哪个会话，用户只能凭记忆猜。
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UiText(
                    text = session.title,
                    size = 13,
                    weight = FontWeight.SemiBold,
                    color = skin.colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(skin.colors.hairline))
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
        LaunchedEffect(Unit) { runCatching { menuFocus.requestFocus() } }
    }
}

/**
 * 菜单卡宽度：**固定 220dp**。宿主（`SessionMetaOverlayHost`）靠它把菜单右缘对齐到
 * 行右缘 —— 宽度在这里只有一份，改宽度不会让锚点算错。
 */
internal val MenuWidth = 220.dp

/** 菜单贴在行下方时的垂直呼吸（行底 + 4dp）。 */
internal val MenuRowGap = 4.dp

/** 没有行锚点（旧调用点）时菜单下移的距离：父布局右上角 + 48dp，与本棒之前逐像素相同。 */
internal val LegacyMenuTopGap = 48.dp

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
            // 键盘可达（B2）：Tab 聚焦后 Enter/Space 触发，与点击同一条路。
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp &&
                    (event.key == Key.Enter || event.key == Key.Spacebar)
                ) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VadeIcon(icon, contentDescription = label, size = 20.dp, tint = tint)
        Spacer(Modifier.width(12.dp))
        UiText(text = label, size = 14, color = tint)
    }
}
