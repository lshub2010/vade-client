package vade.ui.meta

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import vade.ui.icon.MetaIcons

/**
 * 上下文菜单的固定 6 项（测试③钉住这个集合，多一项少一项都算破坏）：
 * 重命名 / 置顶(取消置顶) / 归档(取消归档) / 移动到分组 / 新建分组 / 删除。
 * 「新建分组」从会话菜单一并给出：用户不必为了建分类先离开列表。
 */
enum class SessionMenuItem {
    Rename,
    TogglePin,
    ToggleArchive,
    MoveToGroup,
    NewGroup,
    Delete,
    ;

    /** 行内文案随会话当前态翻转（置顶↔取消置顶，归档↔取消归档）。 */
    fun label(session: MetaSession): String = when (this) {
        Rename -> MetaCopy.MENU_RENAME
        TogglePin -> if (session.pinned) MetaCopy.MENU_UNPIN else MetaCopy.MENU_PIN
        ToggleArchive -> if (session.archived) MetaCopy.MENU_UNARCHIVE else MetaCopy.MENU_ARCHIVE
        MoveToGroup -> MetaCopy.MENU_MOVE_TO_GROUP
        NewGroup -> MetaCopy.MENU_NEW_GROUP
        Delete -> MetaCopy.MENU_DELETE
    }

    /** 破坏性项用 error 色渲染，且必须经过 [MetaEditor.ConfirmDeleteSession]。 */
    val destructive: Boolean get() = this == Delete
}

/** 全部编辑入口收敛为这一个密封集：菜单项只负责把 [SessionMetaState.editor] 置成其一。 */
sealed interface MetaEditor {
    /** 重命名（内联小弹层，非全屏页）。 */
    data class RenameSession(val session: MetaSession, val error: String? = null) : MetaEditor

    /** 删除确认 —— 菜单里的「删除」**只到这里**，confirm 之前不产生任何动作回调。 */
    data class ConfirmDeleteSession(val session: MetaSession) : MetaEditor

    /** 移动到分组（含「未分组」）。 */
    data class MoveToGroup(val session: MetaSession) : MetaEditor

    /**
     * 分组编辑器（新建与编辑同一张表单）：图标 + 颜色草稿独立携带，
     * **可以同时设置并在一次提交里一起生效**（测试⑧）。
     */
    data class GroupSheet(
        val group: MetaGroup?,
        val isNew: Boolean,
        val draftName: String,
        val draftIcon: String,
        val draftColor: String,
        val error: String? = null,
    ) : MetaEditor
}

/**
 * 会话元数据编辑的**可测状态机**（w-c-meta）。UI 绑定这些属性；测试直接驱动方法。
 *
 * 并发模型：本地列表乐观更新 + 同步回调 [actions]（Fake 即内存"服务端"）。
 * 接真数据后数据层以权威列表回推，乐观值被覆盖 —— 本类不改结构。
 * 挂起方法都要在协程里调（UI 侧 `rememberCoroutineScope`，测试侧 `runBlocking`）。
 */
class SessionMetaState(
    initialSessions: List<MetaSession>,
    initialGroups: List<MetaGroup>,
    private val actions: SessionMetaActions,
) {

    var sessions: List<MetaSession> by mutableStateOf(initialSessions)
        private set

    var groups: List<MetaGroup> by mutableStateOf(initialGroups)
        private set

    /** 长按/右键/更多按钮打开的行菜单所属会话 id；null = 菜单关着。 */
    var menuFor: String? by mutableStateOf(null)
        private set

    /** 当前打开的编辑弹层（重命名 / 删除确认 / 移动分组 / 分组表单）。 */
    var editor: MetaEditor? by mutableStateOf(null)
        private set

    // --- 菜单 ---------------------------------------------------------------------

    fun openSessionMenu(sessionId: String) {
        menuFor = sessionId
    }

    fun closeSessionMenu() {
        menuFor = null
    }

    /** 固定 6 项的有序集合；顺序即渲染顺序。 */
    fun menuItems(): List<SessionMenuItem> = SESSION_MENU_ITEMS

    fun menuLabel(item: SessionMenuItem, sessionId: String): String {
        val session = sessions.first { it.id == sessionId }
        return item.label(session)
    }

    // --- 菜单分派（唯一入口，保证「删除必经确认」无法绕过） -------------------------

    /** 菜单点击 → 关菜单 → 路由。破坏性项只开确认弹层，不直接删。 */
    suspend fun onMenuItem(sessionId: String, item: SessionMenuItem) {
        menuFor = null
        val session = sessions.firstOrNull { it.id == sessionId } ?: return
        when (item) {
            SessionMenuItem.Rename -> editor = MetaEditor.RenameSession(session)
            SessionMenuItem.TogglePin -> togglePin(sessionId)
            SessionMenuItem.ToggleArchive -> toggleArchive(sessionId)
            SessionMenuItem.MoveToGroup -> editor = MetaEditor.MoveToGroup(session)
            SessionMenuItem.NewGroup -> openNewGroupSheet()
            SessionMenuItem.Delete -> editor = MetaEditor.ConfirmDeleteSession(session)
        }
    }

    // --- 会话动作 -------------------------------------------------------------------

    /** 置顶/取消置顶互斥：一个布尔字段，一条 `session/pin` 回调。 */
    suspend fun togglePin(sessionId: String) {
        val current = sessions.first { it.id == sessionId }
        val next = !current.pinned
        sessions = sessions.map { if (it.id == sessionId) it.copy(pinned = next) else it }
        actions.setSessionPinned(sessionId, next)
    }

    /** 归档/取消归档，与置顶同构。 */
    suspend fun toggleArchive(sessionId: String) {
        val current = sessions.first { it.id == sessionId }
        val next = !current.archived
        sessions = sessions.map { if (it.id == sessionId) it.copy(archived = next) else it }
        actions.setSessionArchived(sessionId, next)
    }

    /** 提交重命名；空白名拒收并留在弹层（error 文案进 [MetaEditor.RenameSession.error]）。 */
    suspend fun commitRename(title: String) {
        val current = editor as? MetaEditor.RenameSession ?: return
        val trimmed = title.trim()
        if (trimmed.isEmpty()) {
            editor = current.copy(error = MetaCopy.TITLE_EMPTY_ERROR)
            return
        }
        sessions = sessions.map { if (it.id == current.session.id) it.copy(title = trimmed) else it }
        actions.renameSession(current.session.id, trimmed)
        editor = null
    }

    /** 二次确认步骤一：菜单/按钮只允许走到这里 —— 不产生删除回调。 */
    fun requestDelete(sessionId: String) {
        val session = sessions.firstOrNull { it.id == sessionId } ?: return
        editor = MetaEditor.ConfirmDeleteSession(session)
    }

    /** 用户反悔：关掉确认层，什么也不发生。 */
    fun cancelDelete() {
        editor = null
    }

    /** 二次确认步骤二：唯一会调 `session/delete` 的路径。 */
    suspend fun confirmDelete() {
        val current = editor as? MetaEditor.ConfirmDeleteSession ?: return
        sessions = sessions.filterNot { it.id == current.session.id }
        actions.deleteSession(current.session.id)
        editor = null
    }

    // --- 移动到分组 -------------------------------------------------------------------

    /** [groupId] = null 即「未分组」（服务端 `session/group.assign` 的 null 语义）。 */
    suspend fun moveToGroup(groupId: String?) {
        val current = editor as? MetaEditor.MoveToGroup ?: return
        sessions = sessions.map { if (it.id == current.session.id) it.copy(groupId = groupId) else it }
        actions.assignSessionToGroup(current.session.id, groupId)
        editor = null
    }

    // --- 分组表单（新建 / 编辑共用，图标与颜色可一次提交） -----------------------------

    fun openNewGroupSheet() {
        editor = MetaEditor.GroupSheet(
            group = null,
            isNew = true,
            draftName = "",
            draftIcon = DEFAULT_GROUP_ICON,
            draftColor = MetaColors.presets[DEFAULT_GROUP_COLOR_INDEX],
        )
    }

    fun openGroupEditor(groupId: String) {
        val group = groups.firstOrNull { it.id == groupId } ?: return
        editor = MetaEditor.GroupSheet(
            group = group,
            isNew = false,
            draftName = group.name,
            draftIcon = group.iconName,
            draftColor = group.colorHex,
        )
    }

    fun setGroupDraftName(name: String) {
        val sheet = editor as? MetaEditor.GroupSheet ?: return
        editor = sheet.copy(draftName = name, error = null)
    }

    /** 草稿换图标；只接受白名单名（选择器本就只出白名单，这里再挡一层）。 */
    fun setGroupDraftIcon(iconName: String) {
        val sheet = editor as? MetaEditor.GroupSheet ?: return
        if (MetaIcons[iconName] == null) return
        editor = sheet.copy(draftIcon = iconName)
    }

    /** 草稿换颜色；统一走 [MetaColors.normalize] 归一成大写 `#RRGGBB`。 */
    fun setGroupDraftColor(colorHex: String) {
        val sheet = editor as? MetaEditor.GroupSheet ?: return
        val normalized = MetaColors.normalize(colorHex) ?: return
        editor = sheet.copy(draftColor = normalized)
    }

    /**
     * 提交表单：新建 → `session/group.create`；编辑 → 与原值 diff，**被改的字段合成一次
     * `session/group.update`**（图标+颜色同时改就是一条同时带两个键的调用，测试⑧）。
     */
    suspend fun commitGroupSheet() {
        val sheet = editor as? MetaEditor.GroupSheet ?: return
        val name = sheet.draftName.trim()
        if (name.isEmpty()) {
            editor = sheet.copy(error = MetaCopy.TITLE_EMPTY_ERROR)
            return
        }
        if (MetaIcons[sheet.draftIcon] == null || !MetaColors.isValid(sheet.draftColor)) {
            editor = sheet.copy(error = MetaCopy.GROUP_DRAFT_INVALID)
            return
        }
        if (sheet.isNew) {
            val created = actions.createGroup(name, sheet.draftIcon, sheet.draftColor)
            groups = groups + created
        } else {
            val group = sheet.group ?: return
            actions.updateGroup(
                groupId = group.id,
                name = if (name != group.name) name else null,
                iconName = if (sheet.draftIcon != group.iconName) sheet.draftIcon else null,
                colorHex = if (sheet.draftColor != group.colorHex) sheet.draftColor else null,
            )
            groups = groups.map {
                if (it.id != group.id) it
                else it.copy(name = name, iconName = sheet.draftIcon, colorHex = sheet.draftColor)
            }
        }
        editor = null
    }

    /**
     * 删除分组（表单内危险区）：只删组 —— 组内会话 `groupId` 就地置 null，
     * 与服务端 `session/group.delete` 语义一致，会话一条不少。
     */
    suspend fun deleteGroupFromEditor() {
        val sheet = editor as? MetaEditor.GroupSheet ?: return
        val group = sheet.group ?: return
        groups = groups.filterNot { it.id == group.id }
        sessions = sessions.map { if (it.groupId == group.id) it.copy(groupId = null) else it }
        actions.deleteGroup(group.id)
        editor = null
    }

    fun closeEditor() {
        editor = null
    }

    // --- 视图辅助 ---------------------------------------------------------------------

    /** 测试⑦：分组列表为空（UI 渲染空态卡的信号）。 */
    val hasNoGroups: Boolean get() = groups.isEmpty()

    /**
     * 某个分组（null = 未分组）下的会话，保持输入顺序。
     * 悬空引用按未分组算：`groupId` 指向的组不在列表里（例如数据层给了一致性未定
     * 的快照）时，会话落在未分组面 —— 空分组列表（测试⑦）时全部会话都在未分组。
     */
    fun sessionsOf(groupId: String?): List<MetaSession> = when (groupId) {
        null -> sessions.filter { it.groupId == null || groups.none { g -> g.id == it.groupId } }
        else -> sessions.filter { it.groupId == groupId }
    }

    val ungroupedSessions: List<MetaSession> get() = sessionsOf(null)

    companion object {
        /** 固定菜单集合 —— 顺序也是契约的一部分。 */
        val SESSION_MENU_ITEMS: List<SessionMenuItem> = listOf(
            SessionMenuItem.Rename,
            SessionMenuItem.TogglePin,
            SessionMenuItem.ToggleArchive,
            SessionMenuItem.MoveToGroup,
            SessionMenuItem.NewGroup,
            SessionMenuItem.Delete,
        )

        /** 新建表单的默认图标/颜色（folder + 蓝）。 */
        const val DEFAULT_GROUP_ICON = "folder"
        const val DEFAULT_GROUP_COLOR_INDEX = 5
    }
}
