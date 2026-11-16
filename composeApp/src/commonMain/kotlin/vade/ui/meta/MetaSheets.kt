package vade.ui.meta

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import vade.ui.UiText
import vade.ui.component.insets.bottomSafeClearance
import vade.ui.component.liquid.VadeShadows
import vade.ui.component.liquid.layeredShadow
import vade.ui.icon.MetaIcons
import vade.ui.icon.VadeIcon
import vade.ui.icon.VadeIcons
import vade.ui.skin.BtnStyle
import vade.ui.skin.LocalSkin

/**
 * w-c-meta 弹层：全部按视觉语言 §9「悬浮卡片」做 —— 12dp 四周边距、32dp 四角同圆、
 * 玻璃浮层阴影（[VadeShadows.float]）+ 1px 发丝线 + 顶部 grabber 白条；点遮罩关闭。
 * 不用 Material Dialog、不贴边、不满宽；图标一律矢量（§3）。
 *
 * 键盘/无障碍（B2 s-meta-host）：focusable Popup 抓住焦点，**Esc 关闭弹层**
 * （与点遮罩同一条 onClose 路径，绝不悄悄丢草稿之外的副作用）。
 *
 * c6-insets · L4/P1-2 —— Popup 是**独立 window**：根布局的 `statusBarsPadding()` /
 * `imePadding()` 抬不到它。所以底部不只是 §9 的 12dp，而是 `12dp + 导航条余量`
 * （余量在主窗口里读：Popup 自己的 window insets 在 Android 上不保证有值；键盘弹起时
 * 余量为 0，与 IME 不相加），再加上内容自身的 `.imePadding()` 处理键盘那一段；
 * 内容列可滚动，键盘把可视区压扁时不会把按钮挤出屏幕（重命名/分组编辑都够用）。
 */

/** §9 卡片骨架：grabber → 标题行（图标 + 标题 + 关闭）→ 内容。 */
@Composable
private fun MetaSheetScaffold(
    title: String,
    onClose: () -> Unit,
    icon: ImageVector? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val skin = LocalSkin.current
    val shape = RoundedCornerShape(skin.shapes.sheet) // 32dp：全局弹窗档
    val sheetFocus = remember { FocusRequester() }
    // 导航条余量在主窗口里读一次带进 Popup（见文件头 KDoc：Popup 的 window insets 不保证有值）。
    val bottomClearance = bottomSafeClearance()
    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onClose,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier
                // §9：四周 12dp 空隙，永不贴边；底部再加导航条余量 —— 三键条 / 手势胶囊
                // 压在操作按钮上曾是真机可见缺陷（L4）。
                .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp + bottomClearance)
                // 键盘：Popup 有自己的 window，根布局的 imePadding 抬不到它，只能自己让。
                .imePadding()
                .widthIn(max = 460.dp)
                .layeredShadow(VadeShadows.float, shape, dark = skin.colors.isDark)
                .clip(shape)
                .background(skin.colors.surface)
                .border(1.dp, skin.colors.hairline, shape)
                // 内容可滚：键盘把可视区压扁时，「保存/删除」不会被挤出屏幕外。
                .verticalScroll(rememberScrollState())
                .focusRequester(sheetFocus)
                .focusable()
                .onPreviewKeyEvent { event ->
                    // Esc 关闭弹层：与 onDismissRequest 同一条 onClose 路。
                    if (event.type == KeyEventType.KeyUp && event.key == Key.Escape) {
                        onClose()
                        true
                    } else {
                        false
                    }
                }
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            // grabber：36×4dp、距顶 8dp，唯一职责是"可下拉关闭"的暗示（§9）。
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 2.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(skin.shapes.xs))
                    .background(if (skin.colors.isDark) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.70f) else androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.24f)),
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    VadeIcon(icon, contentDescription = null, size = 20.dp, tint = skin.colors.primary)
                    Spacer(Modifier.width(8.dp))
                }
                UiText(text = title, size = 18, weight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.weight(1f))
                // 纯图标关闭按钮：44dp 触摸区 + contentDescription（§3.4）。
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(skin.shapes.xs))
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    VadeIcon(VadeIcons.Close, contentDescription = "关闭", size = 18.dp, tint = skin.colors.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
        // 弹层卡自己抓焦点：Esc 立即可用；点进输入框后焦点移走，Esc 走祖先预览链仍然生效。
        LaunchedEffect(Unit) { runCatching { sheetFocus.requestFocus() } }
    }
}

/** 一行主按钮 + 次按钮的标准行动区。 */
@Composable
private fun SheetActions(
    confirmLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val skin = LocalSkin.current
    Spacer(Modifier.height(16.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        skin.Button(MetaCopy.CANCEL, onCancel, style = BtnStyle.Secondary, enabled = true)
        skin.Button(confirmLabel, onConfirm, style = BtnStyle.Primary, enabled = true)
    }
}

/** 重命名：内联小弹层（§9 悬浮卡），不是全屏页。 */
@Composable
internal fun RenameSheet(
    editor: MetaEditor.RenameSession,
    onCommit: (String) -> Unit,
    onClose: () -> Unit,
) {
    val skin = LocalSkin.current
    var draft by remember(editor.session.id) { mutableStateOf(TextFieldValue(editor.session.title)) }
    MetaSheetScaffold(title = MetaCopy.MENU_RENAME, icon = VadeIcons.Compose, onClose = onClose) {
        skin.TextField(
            value = draft.text,
            onValueChange = { draft = draft.copy(text = it) },
            placeholder = null,
            error = editor.error,
        )
        if (editor.error != null) {
            Spacer(Modifier.height(6.dp))
            UiText(editor.error, size = 12, color = skin.colors.error)
        }
        SheetActions(confirmLabel = MetaCopy.GROUP_SAVE, onConfirm = { onCommit(draft.text) }, onCancel = onClose)
    }
}

/** 删除会话：二次确认（未确认绝不回调，测试④）；文案明确不可恢复。 */
@Composable
internal fun DeleteConfirmSheet(
    editor: MetaEditor.ConfirmDeleteSession,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val skin = LocalSkin.current
    MetaSheetScaffold(title = MetaCopy.DELETE_TITLE, icon = VadeIcons.Trash, onClose = onCancel) {
        UiText(
            text = MetaCopy.DELETE_BODY.replace("%s", editor.session.title),
            size = 14,
            color = skin.colors.onSurfaceVariant,
        )
        // 删除失败（例如 turn 还在跑 → INVALID_ARGUMENT）：会话仍在列表里，错误就摆在这里，
        // 确认层**不关**，用户可以看完原因再决定重试还是取消。
        if (editor.error != null) {
            Spacer(Modifier.height(6.dp))
            UiText(editor.error, size = 12, color = skin.colors.error)
        }
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            skin.Button(MetaCopy.CANCEL, onCancel, style = BtnStyle.Secondary)
            skin.Button(MetaCopy.DELETE_CONFIRM, onConfirm, style = BtnStyle.Danger)
        }
    }
}

/**
 * 移动到分组：未分组 + 全部分组，选中组带勾。
 *
 * 分组管理入口（B2 任务 2）：表单里每一组行尾有一个「编辑分组」按钮，
 * 表单末尾有一行「新建分组」—— 重命名/改图标/改颜色/删除（删组不删会话）
 * 都从 [GroupEditorSheet] 走，不必离开这张表。编辑会**替换**当前弹层
 * （editor 互斥），提交或取消后回到列表（再从菜单进「移动到分组」即可）。
 */
@Composable
internal fun MoveToGroupSheet(
    editor: MetaEditor.MoveToGroup,
    groups: List<MetaGroup>,
    onPick: (String?) -> Unit,
    onEditGroup: (String?) -> Unit,
    onClose: () -> Unit,
) {
    val skin = LocalSkin.current
    MetaSheetScaffold(title = MetaCopy.MENU_MOVE_TO_GROUP, icon = VadeIcons.Folder, onClose = onClose) {
        GroupOptionRow(
            label = MetaCopy.UNGROUPED_LABEL,
            iconName = null,
            colorHex = null,
            selected = editor.session.groupId == null,
            onClick = { onPick(null) },
        )
        groups.forEach { group ->
            GroupOptionRow(
                label = group.name,
                iconName = group.iconName,
                colorHex = group.colorHex,
                selected = editor.session.groupId == group.id,
                onClick = { onPick(group.id) },
                onEdit = { onEditGroup(group.id) },
            )
        }
        // 新建分组：分组管理的另一半入口（与菜单里的「新建分组」同一落点）。
        GroupOptionRow(
            label = MetaCopy.MENU_NEW_GROUP,
            iconName = null,
            colorHex = null,
            selected = false,
            onClick = { onEditGroup(null) },
            leadingOverride = VadeIcons.FolderPlus,
        )
        // 移动失败：会话留在原分组，表单不关，原因可见（分类文案来自 MetaActionError）。
        if (editor.error != null) {
            Spacer(Modifier.height(6.dp))
            UiText(editor.error, size = 12, color = skin.colors.error)
        }
    }
}

@Composable
private fun GroupOptionRow(
    label: String,
    iconName: String?,
    colorHex: String?,
    selected: Boolean,
    onClick: () -> Unit,
    /** 行尾「编辑分组」入口；null = 不显示（未分组行与新建行没有编辑对象）。 */
    onEdit: (() -> Unit)? = null,
    /** 行首图标的覆写（「新建分组」行不是查表图标，直接给 FolderPlus）。 */
    leadingOverride: ImageVector? = null,
) {
    val skin = LocalSkin.current
    val accent = colorHex?.let { MetaColors.parse(it) } ?: skin.colors.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(skin.shapes.sm))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(skin.shapes.xs))
                .background(accent.copy(alpha = if (skin.colors.isDark) 0.18f else 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            VadeIcon(
                vector = leadingOverride ?: (iconName?.let { MetaIcons[it] } ?: VadeIcons.Empty),
                contentDescription = null,
                size = 16.dp,
                tint = accent,
            )
        }
        Spacer(Modifier.width(10.dp))
        UiText(text = label, size = 14, modifier = Modifier.weight(1f))
        if (selected) {
            VadeIcon(VadeIcons.Check, contentDescription = "当前分组", size = 16.dp, tint = skin.colors.primary)
        }
        // 分组管理入口：纯矢量图标 + 44dp 触摸区（§3.4）。
        if (onEdit != null) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(skin.shapes.xs))
                    .focusable()
                    .clickable(onClick = onEdit),
                contentAlignment = Alignment.Center,
            ) {
                VadeIcon(VadeIcons.Compose, contentDescription = "编辑分组「$label」", size = 16.dp, tint = skin.colors.onSurfaceVariant)
            }
        }
    }
}

/**
 * 分组编辑器（新建/编辑共用）：名称 + 39 白名单图标 + 10 预设色；
 * 编辑态下方是危险区 —— 说明文案（[MetaCopy.GROUP_DELETE_NOTE]）+ 删除分组按钮。
 *
 * 编辑态另有两个「清除」按钮：图标/颜色在线上是三态（缺省 / null / 值），清除就是
 * 显式 JSON null；新建态不允许（`group.create` 要求有值），显示一条说明。
 */
@Composable
internal fun GroupEditorSheet(
    sheet: MetaEditor.GroupSheet,
    onName: (String) -> Unit,
    onIcon: (String) -> Unit,
    onColor: (String) -> Unit,
    onClearIcon: () -> Unit,
    onClearColor: () -> Unit,
    canClear: Boolean,
    onCommit: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    val skin = LocalSkin.current
    MetaSheetScaffold(
        title = if (sheet.isNew) MetaCopy.GROUP_EDITOR_NEW_TITLE else MetaCopy.GROUP_EDITOR_EDIT_TITLE,
        icon = VadeIcons.FolderPlus,
        onClose = onClose,
    ) {
        skin.TextField(
            value = sheet.draftName,
            onValueChange = onName,
            placeholder = MetaCopy.GROUP_NAME_PLACEHOLDER,
            error = sheet.error,
        )
        Spacer(Modifier.height(12.dp))
        SectionLabel(MetaCopy.GROUP_ICON_SECTION, VadeIcons.Palette)
        Spacer(Modifier.height(6.dp))
        IconPickerGrid(selected = sheet.draftIcon, onSelect = onIcon)
        Spacer(Modifier.height(12.dp))
        SectionLabel(MetaCopy.GROUP_COLOR_SECTION, VadeIcons.Palette)
        Spacer(Modifier.height(6.dp))
        ColorPickerRow(selected = sheet.draftColor, onSelect = onColor)
        Spacer(Modifier.height(8.dp))
        // 三态写：键缺省 = 保持不变，JSON null = 清除。编辑态可以清；新建态必须选值。
        if (sheet.isNew) {
            UiText(MetaCopy.GROUP_CLEAR_EDIT_ONLY, size = 11, color = skin.colors.onSurfaceVariant)
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                skin.Button(
                    text = MetaCopy.GROUP_CLEAR_ICON,
                    onClick = onClearIcon,
                    style = BtnStyle.Secondary,
                    enabled = canClear && sheet.draftIcon != null,
                )
                skin.Button(
                    text = MetaCopy.GROUP_CLEAR_COLOR,
                    onClick = onClearColor,
                    style = BtnStyle.Secondary,
                    enabled = canClear && sheet.draftColor != null,
                )
            }
        }
        if (sheet.error != null) {
            Spacer(Modifier.height(6.dp))
            UiText(sheet.error, size = 12, color = skin.colors.error)
        }
        SheetActions(
            confirmLabel = if (sheet.isNew) MetaCopy.GROUP_CREATE else MetaCopy.GROUP_SAVE,
            onConfirm = onCommit,
            onCancel = onClose,
        )
        if (!sheet.isNew) {
            Spacer(Modifier.height(12.dp))
            // 危险区：说明 + 删除分组（测试⑥钉住「不会删除会话」文案）。
            UiText(
                text = MetaCopy.GROUP_DELETE_NOTE,
                size = 12,
                color = skin.colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            skin.Button(MetaCopy.GROUP_DELETE_CONFIRM, onDelete, style = BtnStyle.Danger)
        }
    }
}

@Composable
private fun SectionLabel(text: String, icon: ImageVector) {
    val skin = LocalSkin.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        VadeIcon(icon, contentDescription = null, size = 14.dp, tint = skin.colors.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        UiText(text, size = 11, color = skin.colors.onSurfaceVariant)
    }
}
