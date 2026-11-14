package vade.ui.meta

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import vade.ui.UiText
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
    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onClose,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier
                .padding(12.dp) // §9：四周 12dp 空隙，永不贴边
                .widthIn(max = 460.dp)
                .layeredShadow(VadeShadows.float, shape, dark = skin.colors.isDark)
                .clip(shape)
                .background(skin.colors.surface)
                .border(1.dp, skin.colors.hairline, shape)
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

/** 移动到分组：未分组 + 全部分组，选中组带勾。 */
@Composable
internal fun MoveToGroupSheet(
    editor: MetaEditor.MoveToGroup,
    groups: List<MetaGroup>,
    onPick: (String?) -> Unit,
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
            )
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
                vector = iconName?.let { MetaIcons[it] } ?: VadeIcons.Empty,
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
    }
}

/**
 * 分组编辑器（新建/编辑共用）：名称 + 39 白名单图标 + 10 预设色；
 * 编辑态下方是危险区 —— 说明文案（[MetaCopy.GROUP_DELETE_NOTE]）+ 删除分组按钮。
 */
@Composable
internal fun GroupEditorSheet(
    sheet: MetaEditor.GroupSheet,
    onName: (String) -> Unit,
    onIcon: (String) -> Unit,
    onColor: (String) -> Unit,
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
