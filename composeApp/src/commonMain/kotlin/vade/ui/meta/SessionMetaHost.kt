package vade.ui.meta

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import vade.ui.UiText
import vade.ui.destinations.EmptyStateCard
import vade.ui.icon.MetaIcons
import vade.ui.icon.VadeIcon
import vade.ui.icon.VadeIcons
import vade.ui.skin.LocalSkin

/**
 * w-c-meta 组合根：`SessionMetaState`（可测状态机）+ [FakeMetaActions]（内存实现）
 * 驱动的完整会话元数据编辑界面 —— 分组卡（新建/编辑/删除）、行上下文菜单
 * （重命名/置顶/归档/移动分组/删除）、全部编辑弹层。
 *
 * **本轮数据边界**：本组合根尚未被 `AppShell`/`SessionsScreen` 挂载（那是数据层
 * stick 的接线面）；UI 的真实数据源接上时，只需把实现了 [SessionMetaActions] 的
 * 数据层交给 [SessionMetaState]，再把本组合根替换 `SessionsScreen` 的调用点。
 * 排序/时间桶仍归数据层管 —— 本组合根只按 `groupId` 分面。
 */
@Composable
fun SessionMetaHost(
    state: SessionMetaState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onOpenSession: (MetaSession) -> Unit = {},
) {
    val skin = LocalSkin.current
    val scope = rememberCoroutineScope()
    val dispatch: (String, SessionMenuItem) -> Unit = { sessionId, item ->
        scope.launch { state.onMenuItem(sessionId, item) }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().background(skin.colors.canvas),
        contentPadding = contentPadding,
    ) {
        // 最近一次写动作失败：可展示、可关闭、按 VadeErrorCategory 分类（绝不静默）。
        state.actionError?.let { failure ->
            item(key = "meta-action-error", contentType = "meta-banner") {
                MetaErrorBanner(
                    text = failure.message,
                    onDismiss = state::dismissActionError,
                )
            }
        }

        // 顶栏动作：新建分组（矢量图标 + 44dp 触摸区，§3 图标即按钮）。
        item(key = "meta-header", contentType = "meta-header") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UiText(
                    text = "分组",
                    size = 11,
                    weight = FontWeight.SemiBold,
                    color = skin.colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(skin.shapes.xs))
                        .clickable { state.openNewGroupSheet() },
                    contentAlignment = Alignment.Center,
                ) {
                    VadeIcon(VadeIcons.FolderPlus, contentDescription = MetaCopy.MENU_NEW_GROUP, size = 20.dp, tint = skin.colors.onSurfaceVariant)
                }
            }
        }

        // 分组列表加载失败：**不是空表** —— 一张重试卡（唯一主行动「重试」），
        // 旧的权威快照（若有）继续渲染在下面，绝不因为一次读失败把组清空。
        val loadFailure = state.groupsLoadError
        if (loadFailure != null) {
            item(key = "meta-groups-failed", contentType = "meta-banner") {
                EmptyStateCard(
                    title = MetaCopy.GROUPS_LOAD_FAILED_TITLE,
                    body = MetaCopy.GROUPS_LOAD_FAILED_BODY + "（" + loadFailure.category.label + "）",
                    icon = VadeIcons.Empty,
                    action = {
                        skin.Button(MetaCopy.RETRY, { scope.launch { state.retryLoadGroups() } })
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        } else if (state.groupsLoad == MetaGroupsLoad.Loading && state.groups.isEmpty()) {
            item(key = "meta-groups-loading", contentType = "meta-banner") {
                UiText(
                    text = MetaCopy.GROUPS_LOADING,
                    size = 11,
                    color = skin.colors.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }

        if (state.hasNoGroups) {
            // 测试⑦的空态：分组列表为空 → 一张空态卡，唯一主行动是「新建分组」。
            item(key = "meta-groups-empty", contentType = "meta-banner") {
                EmptyStateCard(
                    title = MetaCopy.GROUPS_EMPTY_TITLE,
                    body = MetaCopy.GROUPS_EMPTY_BODY,
                    icon = VadeIcons.FolderPlus,
                    action = {
                        skin.Button(MetaCopy.MENU_NEW_GROUP, { state.openNewGroupSheet() })
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        } else {
            state.groups.forEach { group ->
                // H9：分组卡是一整类骨架（横幅 / 抬头 / 空态卡各自一类），显式声明 contentType。
                item(key = "group-${group.id}", contentType = "meta-group-card") {
                    val count = state.sessionsOf(group.id).size
                    vade.ui.destinations.GroupCardWithMeta(
                        name = group.name,
                        iconName = group.iconName,
                        colorHex = group.colorHex,
                        sessionCount = count,
                        onMore = { state.openGroupEditor(group.id) },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Column(Modifier.padding(bottom = 4.dp)) {
                            state.sessionsOf(group.id).forEach { session ->
                                MetaSessionRow(
                                    state = state,
                                    session = session,
                                    menuOpen = state.menuFor == session.id,
                                    onOpen = { onOpenSession(session) },
                                    onOpenMenu = { state.openSessionMenu(session.id) },
                                    onCloseMenu = { state.closeSessionMenu() },
                                    onDispatch = dispatch,
                                )
                            }
                        }
                    }
                }
            }
        }

        // 未分组（含空分组列表时的全部会话）——始终渲染，allow 用户把会话移出分组。
        item(key = "group-ungrouped", contentType = "meta-group-card") {
            vade.ui.destinations.GroupCardWithMeta(
                name = MetaCopy.UNGROUPED_LABEL,
                iconName = null,
                colorHex = null,
                sessionCount = state.ungroupedSessions.size,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Column(Modifier.padding(bottom = 4.dp)) {
                    state.ungroupedSessions.forEach { session ->
                        MetaSessionRow(
                            state = state,
                            session = session,
                            menuOpen = state.menuFor == session.id,
                            onOpen = { onOpenSession(session) },
                            onOpenMenu = { state.openSessionMenu(session.id) },
                            onCloseMenu = { state.closeSessionMenu() },
                            onDispatch = dispatch,
                        )
                    }
                }
            }
        }

        item(key = "meta-tail") { Spacer(Modifier.height(16.dp)) }
    }

    // --- 弹层（§9 悬浮卡；同一时刻至多一个，editor 互斥保证） ----------------------
    when (val editor = state.editor) {
        is MetaEditor.RenameSession -> RenameSheet(
            editor = editor,
            onCommit = { text -> scope.launch { state.commitRename(text) } },
            onClose = { state.closeEditor() },
        )
        is MetaEditor.ConfirmDeleteSession -> DeleteConfirmSheet(
            editor = editor,
            onConfirm = { scope.launch { state.confirmDelete() } },
            onCancel = { state.cancelDelete() },
        )
        is MetaEditor.MoveToGroup -> MoveToGroupSheet(
            editor = editor,
            groups = state.groups,
            onPick = { groupId -> scope.launch { state.moveToGroup(groupId) } },
            onEditGroup = { groupId ->
                if (groupId == null) state.openNewGroupSheet() else state.openGroupEditor(groupId)
            },
            onClose = { state.closeEditor() },
        )
        is MetaEditor.GroupSheet -> GroupEditorSheet(
            sheet = editor,
            onName = state::setGroupDraftName,
            onIcon = state::setGroupDraftIcon,
            onColor = state::setGroupDraftColor,
            onClearIcon = { state.clearGroupDraftIcon() },
            onClearColor = { state.clearGroupDraftColor() },
            canClear = state.canClearGroupIconColor,
            onCommit = { scope.launch { state.commitGroupSheet() } },
            onDelete = { scope.launch { state.deleteGroupFromEditor() } },
            onClose = { state.closeEditor() },
        )
        null -> Unit
    }
}

/** 会话行：≥64dp、`sm` 圆角、行尾更多按钮 + 长按菜单（§12 会话列表 + §3.4）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MetaSessionRow(
    state: SessionMetaState,
    session: MetaSession,
    menuOpen: Boolean,
    onOpen: () -> Unit,
    onOpenMenu: () -> Unit,
    onCloseMenu: () -> Unit,
    onDispatch: (String, SessionMenuItem) -> Unit,
) {
    val skin = LocalSkin.current
    val shape = RoundedCornerShape(skin.shapes.sm)
    // 工厂传入的焦点会话（`forReal(client, sessionId)`）：仅高亮，不改变任何动作语义。
    val focused = state.focusSessionId == session.id
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 3.dp)
                .heightIn(min = 64.dp)
                .clip(shape)
                .background(
                    if (focused) skin.colors.primary.copy(alpha = if (skin.colors.isDark) 0.14f else 0.08f)
                    else Color.Transparent,
                )
                .combinedClickable(onClick = onOpen, onLongClick = onOpenMenu)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (session.pinned) {
                        VadeIcon(VadeIcons.Pin, contentDescription = "已置顶", size = 13.dp, tint = skin.colors.primary)
                        Spacer(Modifier.width(4.dp))
                    }
                    if (session.archived) {
                        VadeIcon(VadeIcons.Archive, contentDescription = "已归档", size = 13.dp, tint = skin.colors.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                    }
                    UiText(
                        text = session.title,
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
            }
            Spacer(Modifier.width(8.dp))
            UiText(session.updatedLabel, size = 11, color = skin.colors.onSurfaceVariant)
            // 行尾更多按钮：纯矢量图标 + 44dp 触摸区（§3）。
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(skin.shapes.xs))
                    .clickable(onClick = onOpenMenu),
                contentAlignment = Alignment.Center,
            ) {
                VadeIcon(VadeIcons.More, contentDescription = "会话选项", size = 18.dp, tint = skin.colors.onSurfaceVariant)
            }
        }
        if (menuOpen) {
            SessionContextMenu(
                state = state,
                sessionId = session.id,
                onDispatch = { item -> onDispatch(session.id, item) },
            )
        }
    }
}

/**
 * 失败横条（g-meta-wire）：一行分类后的可展示错误 + 44dp 关闭按钮。
 * 只渲染 `SessionMetaState.actionError`（最近一条），不累积 —— 用户读完可关掉，
 * 关掉不等于问题已修（重试入口在各自弹层/失败卡里）。
 *
 * `internal`（B2 s-meta-host）：挂载面 `SessionMetaOverlayHost` 复用同一张横条，
 * 不另造一个样式分叉。
 */
@Composable
internal fun MetaErrorBanner(text: String, onDismiss: () -> Unit) {
    val skin = LocalSkin.current
    val shape = RoundedCornerShape(skin.shapes.sm)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(shape)
            .background(skin.colors.error.copy(alpha = if (skin.colors.isDark) 0.16f else 0.08f))
            .border(1.dp, skin.colors.error.copy(alpha = 0.35f), shape)
            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UiText(
            text = text,
            size = 12,
            color = skin.colors.error,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(skin.shapes.xs))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            VadeIcon(
                vector = VadeIcons.Close,
                contentDescription = MetaCopy.ACTION_FAILED_DISMISS,
                size = 16.dp,
                tint = skin.colors.error,
            )
        }
    }
}
