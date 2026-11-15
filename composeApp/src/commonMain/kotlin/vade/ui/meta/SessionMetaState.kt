package vade.ui.meta

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import vade.ui.data.AdapterProvider
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
    data class ConfirmDeleteSession(val session: MetaSession, val error: String? = null) : MetaEditor

    /** 移动到分组（含「未分组」）；[error] 是 `session/group.assign` 失败的可展示原因。 */
    data class MoveToGroup(val session: MetaSession, val error: String? = null) : MetaEditor

    /**
     * 分组编辑器（新建与编辑同一张表单）：图标 + 颜色草稿独立携带，
     * **可以同时设置并在一次提交里一起生效**（测试⑧）。
     *
     * [draftIcon] / [draftColor] 可空：null = 草稿就是「无图标 / 无强调色」。
     * 编辑态下 null 会作为**显式 JSON null**（清除）提交；新建态下服务端要求有值，
     * 表单不允许清空（见 [MetaCopy.GROUP_CLEAR_EDIT_ONLY]）。
     */
    data class GroupSheet(
        val group: MetaGroup?,
        val isNew: Boolean,
        val draftName: String,
        val draftIcon: String?,
        val draftColor: String?,
        val error: String? = null,
    ) : MetaEditor
}

/**
 * 会话元数据编辑的**可测状态机**（w-c-meta）。UI 绑定这些属性；测试直接驱动方法。
 *
 * 并发模型：本地列表乐观更新 + 同步回调 [actions]（Fake 即内存"服务端"）。
 *
 * **g-meta-wire 之后**：真实现 [vade.ui.data.MetaActionsReal] 会抛
 * [vade.ui.protocol.VadeException]（C-04 code 原样保留）。每个写方法都做三件事：
 *  1. 先乐观改本地列表；
 *  2. 失败时**回滚到调用前的快照**（`sessions` / `groups` 整体还原，不留半改行）；
 *  3. 把分类后的失败放进 [actionError]（[vade.ui.data.VadeErrorCategory] 桶 + 可读文案），
 *     并让弹层保持打开、`error` 字段有话说 —— 绝不静默吞掉、绝不假装成功。
 *
 * 分组列表读（[loadGroups]）失败落在 [MetaGroupsLoad.Failed]，**不是空表**：
 * UI 据 [groupsLoadError] 渲染重试入口（[retryLoadGroups]）。
 *
 * 挂起方法都要在协程里调（UI 侧 `rememberCoroutineScope`，测试侧 `runBlocking`）。
 */
class SessionMetaState(
    initialSessions: List<MetaSession>,
    initialGroups: List<MetaGroup>,
    private val actions: SessionMetaActions,
    /**
     * 本面板聚焦的会话（`SessionMetaState.forReal` 传入）；只用于行高亮，
     * 不参与动作分派 —— 每个动作自己带 sessionId。
     */
    val focusSessionId: String? = null,
    /**
     * 三态分组写能力（清除图标/颜色）；默认取 [actions] 自己实现的那一份，
     * 拿不到就是「这个数据层只会两态」——`canClearGroupIconColor` 随之为 false。
     */
    private val groupEdit: SessionMetaGroupEdit? = actions as? SessionMetaGroupEdit,
    /**
     * 适配器查找失败的原因（非 null ⇒ 本状态机跑在内存假实现 [FakeMetaActions] 上）。
     *
     * 由 [Companion.forAdapters] 在 `adapters.meta(sessionId)` 抛异常时填入；面板可以把它
     * 渲染成一行可读说明。**绝不静默**：拿到非 null 时用户看到的是「已退回内存假数据」，
     * 而不是把 fixture 的改动当成 core 的改动。
     */
    val wiringDegradation: String? = null,
) {

    var sessions: List<MetaSession> by mutableStateOf(initialSessions)
        private set

    var groups: List<MetaGroup> by mutableStateOf(initialGroups)
        private set

    /**
     * 最近一次写动作的可展示失败；null = 最近一次是成功的。
     * 同一时刻只保留一条（新的失败覆盖旧的，成功后清空）。
     */
    var actionError: MetaActionError? by mutableStateOf(null)
        private set

    /** 分组列表加载状态：失败**不是空表**，见 [MetaGroupsLoad]。 */
    var groupsLoad: MetaGroupsLoad by mutableStateOf(MetaGroupsLoad.Idle)
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

    /** 置顶/取消置顶互斥：一个布尔字段，一条 `session/pin` 回调。失败整表回滚。 */
    suspend fun togglePin(sessionId: String) {
        val current = sessions.firstOrNull { it.id == sessionId } ?: return
        val before = sessions
        sessions = sessions.map { if (it.id == sessionId) it.copy(pinned = !current.pinned) else it }
        write(MetaAction.Pin, rollback = { sessions = before }) {
            actions.setSessionPinned(sessionId, !current.pinned)
        }
    }

    /** 归档/取消归档，与置顶同构（失败同样回滚）。 */
    suspend fun toggleArchive(sessionId: String) {
        val current = sessions.firstOrNull { it.id == sessionId } ?: return
        val before = sessions
        sessions = sessions.map { if (it.id == sessionId) it.copy(archived = !current.archived) else it }
        write(MetaAction.Archive, rollback = { sessions = before }) {
            actions.setSessionArchived(sessionId, !current.archived)
        }
    }

    /**
     * 提交重命名；空白名拒收并留在弹层（[MetaCopy.TITLE_EMPTY_ERROR]）。
     * RPC 失败：标题**回滚成旧值**、弹层留在原地、`error` 带上分类后的可展示原因。
     */
    suspend fun commitRename(title: String) {
        val current = editor as? MetaEditor.RenameSession ?: return
        val trimmed = title.trim()
        if (trimmed.isEmpty()) {
            editor = current.copy(error = MetaCopy.TITLE_EMPTY_ERROR)
            return
        }
        val before = sessions
        sessions = sessions.map { if (it.id == current.session.id) it.copy(title = trimmed) else it }
        val ok = write(MetaAction.Rename, rollback = { sessions = before }) {
            actions.renameSession(current.session.id, trimmed)
        }
        editor = if (ok) null else current.copy(error = failureText())
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

    /**
     * 二次确认步骤二：唯一会调 `session/delete` 的路径。
     * 失败：那一行**回到列表原位**、确认层不关、错误可展示（会话没被删掉，不会骗用户）。
     */
    suspend fun confirmDelete() {
        val current = editor as? MetaEditor.ConfirmDeleteSession ?: return
        val before = sessions
        sessions = sessions.filterNot { it.id == current.session.id }
        val ok = write(MetaAction.DeleteSession, rollback = { sessions = before }) {
            actions.deleteSession(current.session.id)
        }
        editor = if (ok) null else current.copy(error = failureText())
    }

    // --- 移动到分组 -------------------------------------------------------------------

    /**
     * [groupId] = null 即「未分组」（服务端 `session/group.assign` 的 null 语义）。
     * 失败：`groupId` 回滚，移动表单留在原地带错误。
     */
    suspend fun moveToGroup(groupId: String?) {
        val current = editor as? MetaEditor.MoveToGroup ?: return
        val before = sessions
        sessions = sessions.map { if (it.id == current.session.id) it.copy(groupId = groupId) else it }
        val ok = write(MetaAction.MoveToGroup, rollback = { sessions = before }) {
            actions.assignSessionToGroup(current.session.id, groupId)
        }
        editor = if (ok) null else current.copy(error = failureText())
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
     * 草稿「清除图标」：草稿置 null，提交时就是 `"icon": null`（线格式的显式 null）。
     * 新建态不允许（`group.create` 的图标是必填），此时返回 false 且不改草稿。
     */
    fun clearGroupDraftIcon(): Boolean {
        val sheet = editor as? MetaEditor.GroupSheet ?: return false
        if (sheet.isNew || !canClearGroupIconColor) return false
        editor = sheet.copy(draftIcon = null, error = null)
        return true
    }

    /** 草稿「清除颜色」，与 [clearGroupDraftIcon] 同构。 */
    fun clearGroupDraftColor(): Boolean {
        val sheet = editor as? MetaEditor.GroupSheet ?: return false
        if (sheet.isNew || !canClearGroupIconColor) return false
        editor = sheet.copy(draftColor = null, error = null)
        return true
    }

    /**
     * 提交表单：新建 → `session/group.create`；编辑 → 与原值 diff，**被改的字段合成一次
     * `session/group.update`**（图标+颜色同时改就是一条同时带两个键的调用，测试⑧）。
     *
     * 三态：字段没改 = 键缺省（保持）、草稿为 null 且原值非 null = **显式 JSON null（清除）**、
     * 其余 = 新值。走 [SessionMetaGroupEdit] 时三个状态都发得出去；注入的实现只有两态时
     * 清除会被明确拒收（[MetaCopy.GROUP_CLEAR_UNSUPPORTED]），绝不静默变成「保持不变」。
     *
     * 失败：分组列表不动（本地改动只在 RPC 成功后落盘），表单留在原地并带分类错误 ——
     * 新建失败时绝不先在本地塞一条假组。
     */
    suspend fun commitGroupSheet() {
        val sheet = editor as? MetaEditor.GroupSheet ?: return
        val name = sheet.draftName.trim()
        if (name.isEmpty()) {
            editor = sheet.copy(error = MetaCopy.TITLE_EMPTY_ERROR)
            return
        }
        val draftIcon = sheet.draftIcon
        val draftColor = sheet.draftColor
        if (draftIcon != null && MetaIcons[draftIcon] == null) {
            editor = sheet.copy(error = MetaCopy.GROUP_DRAFT_INVALID)
            return
        }
        if (draftColor != null && !MetaColors.isValid(draftColor)) {
            editor = sheet.copy(error = MetaCopy.GROUP_DRAFT_INVALID)
            return
        }
        if (sheet.isNew) {
            // 新建要求两字段都有值（窄接口 createGroup 的签名就是必填）；表单不会给出 null。
            val created = writeValue(MetaAction.CreateGroup) {
                actions.createGroup(
                    name = name,
                    iconName = draftIcon ?: DEFAULT_GROUP_ICON,
                    colorHex = draftColor ?: MetaColors.presets[DEFAULT_GROUP_COLOR_INDEX],
                )
            }
            if (created == null) {
                editor = sheet.copy(error = failureText())
                return
            }
            groups = groups + created
        } else {
            val group = sheet.group ?: return
            val nameEdit = if (name != group.name) name else null
            val iconEdit = editOf(draftIcon, group.iconName)
            val colorEdit = editOf(draftColor, group.colorHex)
            val edit = groupEdit
            if (edit != null) {
                val updated = writeValue(MetaAction.UpdateGroup) {
                    edit.editGroup(group.id, nameEdit, iconEdit, colorEdit)
                }
                if (updated == null) {
                    editor = sheet.copy(error = failureText())
                    return
                }
                // 服务端回执是权威投影（清除后 icon/color 就是 null）。
                groups = groups.map { if (it.id == group.id) updated else it }
            } else {
                // 两态兜底：Keep/Set 能表达，Clear 不能 —— 明说，不静默丢键。
                if (iconEdit == MetaFieldEdit.Clear || colorEdit == MetaFieldEdit.Clear) {
                    editor = sheet.copy(error = MetaCopy.GROUP_CLEAR_UNSUPPORTED)
                    return
                }
                val ok = write(MetaAction.UpdateGroup, rollback = {}) {
                    actions.updateGroup(
                        groupId = group.id,
                        name = nameEdit,
                        iconName = (iconEdit as? MetaFieldEdit.Set)?.value,
                        colorHex = (colorEdit as? MetaFieldEdit.Set)?.value,
                    )
                }
                if (!ok) {
                    editor = sheet.copy(error = failureText())
                    return
                }
                groups = groups.map {
                    if (it.id != group.id) it
                    else it.copy(name = name, iconName = draftIcon, colorHex = draftColor)
                }
            }
        }
        editor = null
    }

    /**
     * 删除分组（表单内危险区）：只删组 —— 组内会话 `groupId` 就地置 null，
     * 与服务端 `session/group.delete` 语义一致，会话一条不少。
     * 失败：组与组内会话的归属**一起回滚**，表单留在原地带错误。
     */
    suspend fun deleteGroupFromEditor() {
        val sheet = editor as? MetaEditor.GroupSheet ?: return
        val group = sheet.group ?: return
        val beforeGroups = groups
        val beforeSessions = sessions
        groups = groups.filterNot { it.id == group.id }
        sessions = sessions.map { if (it.groupId == group.id) it.copy(groupId = null) else it }
        val ok = write(MetaAction.DeleteGroup, rollback = {
            groups = beforeGroups
            sessions = beforeSessions
        }) {
            actions.deleteGroup(group.id)
        }
        editor = if (ok) null else sheet.copy(error = failureText())
    }

    // --- 分组列表读（失败 ≠ 空表） -----------------------------------------------------

    /**
     * `session/group.list`：成功 → 覆盖 [groups]、[groupsLoad] = [MetaGroupsLoad.Loaded]；
     * 失败 → [MetaGroupsLoad.Failed] + [actionError]，**`groups` 保持原值**（不清空）。
     * 返回是否成功，调用方据此决定要不要显示提示；重试走 [retryLoadGroups]。
     */
    suspend fun loadGroups(): Boolean {
        groupsLoad = MetaGroupsLoad.Loading
        return try {
            // 有三位态能力时用它：它的投影容忍 icon/color 为 null（清除后的服务端事实）。
            val loaded = groupEdit?.listGroups() ?: actions.listGroups()
            groups = loaded
            groupsLoad = MetaGroupsLoad.Loaded
            actionError = null
            true
        } catch (cancelled: CancellationException) {
            groupsLoad = MetaGroupsLoad.Idle
            throw cancelled
        } catch (t: Throwable) {
            val error = MetaActionError.of(MetaAction.LoadGroups, t)
            // 失败绝不当空表：groups 保留上一次的权威快照，由 UI 渲染重试卡。
            groupsLoad = MetaGroupsLoad.Failed(error)
            actionError = error
            false
        }
    }

    /** 重试入口（失败卡的按钮）：与 [loadGroups] 同一条路径，状态从 Failed → Loading。 */
    suspend fun retryLoadGroups(): Boolean = loadGroups()

    /** 分组列表加载失败的可展示错误；null = 没失败（或还没拉过）。 */
    val groupsLoadError: MetaActionError? get() = (groupsLoad as? MetaGroupsLoad.Failed)?.error

    /** UI 判断「给不给重试按钮」的唯一依据。 */
    val canRetryGroupLoad: Boolean get() = groupsLoad is MetaGroupsLoad.Failed

    /** 数据层把权威列表回推（`session/list` 投影）：覆盖本地乐观值，不触碰加载状态。 */
    fun applyProjection(newSessions: List<MetaSession>, newGroups: List<MetaGroup>? = null) {
        sessions = newSessions
        if (newGroups != null) groups = newGroups
    }

    /** 关掉失败横条（用户已读）；不清除 [MetaGroupsLoad.Failed] 的重试入口。 */
    fun dismissActionError() {
        actionError = null
    }

    fun closeEditor() {
        editor = null
    }

    // --- 视图辅助 ---------------------------------------------------------------------

    /**
     * 分组列表为空（UI 渲染空态卡的信号）。**加载失败或正在加载时不算空**：
     * 那时 emptiness 不是服务端事实，UI 必须渲染重试卡/加载条（见 [groupsLoad]）。
     */
    val hasNoGroups: Boolean
        get() = groups.isEmpty() &&
            groupsLoad != MetaGroupsLoad.Loading &&
            groupsLoad !is MetaGroupsLoad.Failed

    /** 显式 null 能力开关：注入的实现提供 [SessionMetaGroupEdit] 时，UI 才显示「清除」按钮。 */
    val canClearGroupIconColor: Boolean get() = groupEdit != null

    /** 新建分组不允许清空时的说明（见 [MetaCopy.GROUP_CLEAR_EDIT_ONLY]）。 */
    val groupClearHint: String get() = MetaCopy.GROUP_CLEAR_EDIT_ONLY

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

    // --- 失败态内部实现 ---------------------------------------------------------------

    /**
     * 执行一个写动作并按契约处理失败：成功清空 [actionError]，失败记录分类错误并调
     * [rollback]。`CancellationException` 先回滚再原样抛出 —— 协程取消不能把乐观值
     * 留在一个永远不会被服务端确认的状态里。
     */
    private suspend fun write(
        action: MetaAction,
        rollback: () -> Unit,
        block: suspend () -> Unit,
    ): Boolean = try {
        block()
        actionError = null
        true
    } catch (cancelled: CancellationException) {
        rollback()
        throw cancelled
    } catch (t: Throwable) {
        actionError = MetaActionError.of(action, t)
        rollback()
        false
    }

    /** [write] 的有返回值变体（创建分组）：null = 失败且已记录，调用方不得改本地列表。 */
    private suspend fun <T : Any> writeValue(action: MetaAction, block: suspend () -> T): T? = try {
        block().also { actionError = null }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (t: Throwable) {
        actionError = MetaActionError.of(action, t)
        null
    }

    /** 弹层 `error` 字段的文案：优先用分类后的可展示原因，绝不空串。 */
    private fun failureText(): String =
        actionError?.message ?: MetaCopy.UNKNOWN_FAILURE_DETAIL

    /** 草稿 vs 服务端旧值 → 三态写意图（相同 = 保持；草稿 null = 清除；其余 = 设置）。 */
    private fun editOf(draft: String?, current: String?): MetaFieldEdit<String> = when {
        draft == current -> MetaFieldEdit.Keep
        draft == null -> MetaFieldEdit.Clear
        else -> MetaFieldEdit.Set(draft)
    }

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

        /** 退化文案的前半句（与 `destinations` 的三个屏幕同一口径）。 */
        const val ADAPTER_FALLBACK_NOTE: String = "适配器不可用，已退回内存假数据"

        /**
         * ③ 三段回退接线（g-wire-screens）：**形参 [actions] > [adapters] > [FakeMetaActions]**。
         *
         * 与 [SessionMetaState.Companion.forReal] 的分工：那条要 `VadeClient`（真适配器直接
         * 由它构造），这条只认组合根交下来的 [AdapterProvider]，于是 UI 侧永远拿不到
         * `VadeClient` —— `RealAdapters` 是 `internal`，泄漏在编译期就不可能。
         *
         * [sessionId] 同时是**索引**（`adapters.meta(sessionId)`）与**聚焦会话**
         * （[focusSessionId]，行高亮用）。适配器把 `session_id` 当查找参数、不当校验参数，
         * 所以空白 id 不会在这里被拒（真正的校验在 RPC 边界，`INVALID_ARGUMENT`）。
         *
         * 适配器查找抛异常 ⇒ 退回 [FakeMetaActions] 并把原因放进 [wiringDegradation]
         * （不崩、不静默吞）。**不捕获 `Error`**。
         *
         * 已知边界：`AdapterProvider.meta()` 交出的是两态 [SessionMetaActions]，三态清除
         * （`icon`/`color` 显式 null）仍要 `groupEdit`；本工厂不注入它，于是
         * `canClearGroupIconColor == false` —— UI 会如实禁用「清除」，不会假装两态能清除。
         */
        fun forAdapters(
            adapters: AdapterProvider?,
            sessionId: String,
            sessions: List<MetaSession> = emptyList(),
            groups: List<MetaGroup> = emptyList(),
            actions: SessionMetaActions? = null,
        ): SessionMetaState {
            if (actions != null) {
                return SessionMetaState(sessions, groups, actions, focusSessionId = sessionId)
            }
            if (adapters == null) {
                // 假源（fixture）：屏上的会话行**本来就是**这份 fixture（`FakeSessions` 的
                // groupId 就是 `g-1` / `g-2`），它的分组权威也就是 fixture 自己的那张表 ——
                // 这里刚 new 出来的那个 `FakeMetaActions` 的种子。
                //
                // 构造期直接取，不额外发一次 `session/group.list`：假源没有 core，那条
                // "请求"只会在台账里多一条自己给自己的回声，而首帧还要先闪一下裸 `g-1`
                // 等协程回来（D5：界面上不该出现内部 id）。
                //
                // 分界线是 `adapters == null`（整屏都是假数据），**不是**「有没有 Fake」：
                // 一旦有适配器 —— 包括 `meta()` 抛异常、已经退化成 `FakeMetaActions` 的
                // 那一支 —— 组表必须保持调用方给的值（默认空表）。那种情形下行是真会话，
                // 拿 fixture 的「项目/灵感」去配它们就是撒一个比裸 id 更坏的谎，
                // 由 `shell/AppShell.kt` 的 `metaGroupsOf` 与两条回归测试钉住。
                val fake = FakeMetaActions()
                return SessionMetaState(
                    initialSessions = sessions,
                    initialGroups = groups.ifEmpty { fake.groups.value },
                    actions = fake,
                    focusSessionId = sessionId,
                )
            }
            return try {
                SessionMetaState(sessions, groups, adapters.meta(sessionId), focusSessionId = sessionId)
            } catch (error: Exception) {
                SessionMetaState(
                    initialSessions = sessions,
                    initialGroups = groups,
                    actions = FakeMetaActions(),
                    focusSessionId = sessionId,
                    wiringDegradation = "$ADAPTER_FALLBACK_NOTE（${error.message ?: "查找异常"}）",
                )
            }
        }
    }
}
