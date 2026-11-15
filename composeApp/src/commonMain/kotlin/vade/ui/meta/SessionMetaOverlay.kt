package vade.ui.meta

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import vade.ui.data.AdapterProvider
import vade.ui.data.SessionGroup
import vade.ui.data.SessionSummary
import vade.ui.data.bucketFor
import vade.ui.data.epochNowMs
import vade.ui.data.sessionGroups

/**
 * B2（s-meta-host）· 把 `SessionMetaState` 的菜单/弹层/失败横条**真正挂到壳层的会话列表上**。
 *
 * ## 为什么不是把 `SessionMetaHost` 整个换上去
 * `SessionMetaHost` 自带一套列表渲染（分组卡 + 行），换上去等于重画列表 —— 而
 * V3 拍板的行形状（标题 + 组色点、无预览、无分隔线）、D8 的选中左条都在
 * `SessionsScreen` 的行里，且 `tmp/g-route-transition` 正在改那个文件。所以本棒
 * **只挂菜单与弹层，不动列表本体**：行还是 `SessionsScreen` 画，长按通过一个
 * `onLongPress` 回调交到这里，动作走 [meta]（真适配器 = `adapters.meta(id)` 的
 * `MetaActionsReal`；假源 = `FakeMetaActions`）。
 *
 * ## 乐观更新为什么能「看得见」
 * 权威行（`UiState.groups()` 的 `SessionSummary`）与元数据状态机（`MetaSession`）
 * 是两份数据。挂接靠**投影**（[projectGroups]）：把状态机里的乐观值
 * （title / pinned / archived / groupId）覆盖到权威行上再交给 `SessionsScreen`，
 * 时间桶在 pinned 变化时按 `bucketFor` 重算。于是：
 *  - 动作先改状态机（乐观）→ 下一帧列表就变 —— 「点了没反应」消失；
 *  - RPC 失败 → 状态机整表回滚 → 投影跟着回原位，[meta] 的 `actionError`
 *    顶出可见横条 —— 不静默、不假成功；
 *  - 成功 → [onRefreshList]（壳层的 `retrySessions`）拉权威列表收敛。
 *
 * ## 权威同步只走「变更采纳」（不 clobber 乐观值）
 * [syncFrom] 对状态机里**已知**的行：只有当权威行与「上次同步快照」不同
 * （外部写入 / 我方动作成功后的确认回读）才采纳进状态机；权威没变的行一律不动
 * —— 事件刷新落在 RPC 在飞窗口里时权威还是旧值，采纳会把乐观值打回去（闪烁），
 * 按 last-synced 比较就不会。**未知**的行（新会话）直接补进状态机。
 * 乐观删除另有墓碑（[removedIds]）：同步不许把刚删的行加回来，权威刷新不再
 * 列出该行时墓碑清除。
 *
 * ## 构造与「什么时候不拉组表」（自 `shell/AppShell.kt` 的 `metaGroupsOf` 平移，D5）
 * 三段回退工厂 [SessionMetaState.forAdapters] + 拉表门槛（[allowGroupLoad]）：
 *  1. `adapters == null`（假源）：组表就是 fixture 自己的（`forAdapters(null)` 种子），
 *     **不**发 `session/group.list` —— 行与组名同源，屏上不出裸 `g-1`；
 *  2. `forAdapters` 退化（`wiringDegradation != null`）：跑在内存假实现上，
 *     **不**拉表 —— fixture 的「项目/灵感」绝不能冒到真会话上；
 *  3. 其余（真源 + 没退化）：`bootstrap()` 里 `loadGroups()`；读失败不是空表，
 *     行上退化成裸 id，重试入口在分组面板那条失败卡。
 */
class SessionMetaOverlay(
    val meta: SessionMetaState,
    /** 是否允许向 core 拉 `session/group.list`（见类 KDoc 的三条门槛）。 */
    private val allowGroupLoad: Boolean,
    /** 一个写动作成功后让壳层重拉权威列表（`UiState.retrySessions`；假源是 no-op）。 */
    private val onRefreshList: () -> Unit,
    /** 时间桶重算的时钟；测试注入固定值。 */
    private val nowMs: () -> Long = { epochNowMs() },
) {

    /**
     * 乐观删除的墓碑：状态机里已经没有、但权威列表还没刷新掉的会话 id。
     * 墓碑存在期间 [syncFrom] 不许把它加回状态机（否则已删的行会复活一帧）；
     * 权威列表不再列出时清除。
     */
    private var removedIds by mutableStateOf(emptySet<String>())

    /**
     * 每个会话**上次从权威源同步**到的投影。权威行与它不同 ⇒ 外部变更/确认回读
     * ⇒ 采纳；相同 ⇒ 什么都不做（保住乐观值，见类 KDoc「变更采纳」）。
     */
    private var lastSynced by mutableStateOf(emptyMap<String, MetaSession>())

    /**
     * 是否**至少同步过一次**权威行。没同步过时 [projectGroups] 原样返回 ——
     * 投影的唯一职责是「覆盖乐观值 / 藏掉乐观归档的行」，不是「过滤掉所有我不认识的行」。
     * 少了这道闸，没接适配器的壳（测试、或首帧还没回包）会投影出一份**空列表**，
     * 表现得像「一个会话都没有」—— 这是假空态，比显示裸 id 糟得多。
     */
    private var hasSynced by mutableStateOf(false)

    /** 挂载后拉一次组表（门槛见类 KDoc）。由 [SessionMetaOverlayHost] 的 LaunchedEffect 调。 */
    suspend fun bootstrap() {
        if (allowGroupLoad) meta.loadGroups()
    }

    /**
     * 权威行 → 状态机（只补缺失、只采纳变更，见类 KDoc）。由宿主在权威列表
     * 变化时调用；幂等。
     */
    fun syncFrom(rows: List<SessionSummary>) {
        if (rows.isNotEmpty()) hasSynced = true
        val known = meta.sessions.associateBy { it.id }
        val removed = removedIds
        val adopt = mutableListOf<MetaSession>()
        for (row in rows) {
            if (row.isDraft || row.id in removed) continue
            val projection = row.toMetaSession()
            val current = known[row.id]
            val last = lastSynced[row.id]
            when {
                current == null -> adopt += projection // 新会话：补进状态机
                last == null -> Unit // 来源不明的已知行（状态机自种）：不动
                projection != last -> adopt += projection // 权威变了：采纳（外部写/确认回读）
            }
        }
        if (adopt.isNotEmpty()) {
            meta.applyProjection(
                (known + adopt.associateBy { it.id }).values.toList(),
                newGroups = null,
            )
            lastSynced = lastSynced + adopt.associateBy { it.id }
        }
        // 权威列表不再列出的墓碑：刷新已经确认了删除，清掉。
        if (removed.isNotEmpty()) {
            val live = rows.mapTo(mutableSetOf()) { it.id }
            removedIds = removed.filterNot { it in live }.toSet()
        }
    }

    /**
     * 行长按 → 打开上下文菜单。草稿行没有服务端实体（不会真调适配器），
     * 直接忽略 —— 这是「不出现任何菜单入口」规则的一半，另一半（零会话）
     * 由「没有行就没有长按目标」保证。
     */
    fun openSessionMenu(summary: SessionSummary) {
        if (summary.isDraft) return
        if (summary.id !in removedIds && meta.sessions.none { it.id == summary.id }) {
            meta.applyProjection(meta.sessions + summary.toMetaSession(), newGroups = null)
            lastSynced = lastSynced + (summary.id to summary.toMetaSession())
        }
        meta.openSessionMenu(summary.id)
    }

    /**
     * 权威行 + 状态机乐观值 → 交给 `SessionsScreen` 的分组列表（纯投影，见类 KDoc）。
     * 规则：草稿原样；状态机不认识的行先不画（同步下一帧补上）；状态机里
     * `archived = true` 且权威没标归档的行 = 乐观归档 → 行立即消失（与真源
     * `session/list` 的归档过滤一致）；其余覆盖 title/pinned/groupId，pinned
     * 变化时时间桶按 [bucketFor] 重算（取消置顶不能留在「置顶」桶）。
     */
    fun projectGroups(groups: List<SessionGroup>): List<SessionGroup> {
        if (!hasSynced) return groups
        return sessionGroups(groups.flatMap { group -> group.sessions.mapNotNull { project(it) } })
    }

    private fun project(row: SessionSummary): SessionSummary? {
        if (row.isDraft) return row
        // 墓碑：乐观删除/已确认删除的行必须消失（这一条与「不认识」是两回事）。
        if (row.id in removedIds) return null
        // 同步过之后仍不认识 = 同步后才出现的新行：原样画，不许丢。
        val m = meta.sessions.firstOrNull { it.id == row.id } ?: return row
        if (m.archived && !row.archived) return null
        if (m.title == row.title && m.pinned == row.pinned && m.groupId == row.groupId) return row
        return row.copy(
            title = m.title,
            pinned = m.pinned,
            archived = row.archived,
            groupId = m.groupId,
            bucket = bucketFor(updatedAtMs = row.updatedAt, pinned = m.pinned, nowMs = nowMs()),
        )
    }

    // --- 动作分派（菜单与弹层都走这里：成功后拉权威列表收敛） ------------------------

    /** 菜单项分派（唯一入口，删除必经确认）。置顶/归档两项当场产生写回调。 */
    suspend fun dispatch(sessionId: String, item: SessionMenuItem) {
        meta.onMenuItem(sessionId, item)
        if (item == SessionMenuItem.TogglePin || item == SessionMenuItem.ToggleArchive) {
            afterWrite()
        }
    }

    /** 重命名提交（弹层「保存」）。失败：状态机回滚 + 弹层留在原地带原因。 */
    suspend fun commitRename(title: String) = afterWrite { meta.commitRename(title) }

    /**
     * 删除确认（弹层「删除」）。成功才立墓碑 —— 失败时状态机已经把行滚回列表，
     * 立墓碑会让它凭空消失（假成功）。
     */
    suspend fun confirmDelete(sessionId: String) {
        meta.confirmDelete()
        if (meta.actionError == null) {
            removedIds = removedIds + sessionId
            afterWrite()
        }
    }

    /** 移动到分组（含 null = 未分组）。失败：`groupId` 回滚。 */
    suspend fun moveToGroup(groupId: String?) = afterWrite { meta.moveToGroup(groupId) }

    /** 分组表单提交（新建 / 编辑）。失败：表单留在原地带原因，组表不动。 */
    suspend fun commitGroupSheet() = afterWrite { meta.commitGroupSheet() }

    /** 删组（删组不删会话，服务端把组内 `group_id` 置 null）。失败：组与归属一起回滚。 */
    suspend fun deleteGroupFromEditor() = afterWrite { meta.deleteGroupFromEditor() }

    /** 每个写动作的公共尾：没失败就拉权威列表收敛（假源 no-op，见 [onRefreshList]）。 */
    private suspend fun afterWrite(block: suspend () -> Unit = {}) {
        block()
        if (meta.actionError == null) onRefreshList()
    }
}

/**
 * 组合根：构造 [SessionMetaOverlay]（状态机 + 门槛 + 刷新回调），`adapters` 变化才重建。
 * 原 `shell/AppShell.kt` 的 `metaGroupsOf` 由此替代 —— 一个状态机同时供
 * 「行上的组名」（`overlay.meta.groups`）与长按菜单/分组管理（本文件宿主），
 * 不再有两份组表各自漂移的口子。
 */
@Composable
fun rememberSessionMetaOverlay(
    adapters: AdapterProvider?,
    sessionId: String,
    onRefreshList: () -> Unit,
): SessionMetaOverlay = remember(adapters) {
    val meta = SessionMetaState.forAdapters(adapters, sessionId)
    SessionMetaOverlay(
        meta = meta,
        allowGroupLoad = adapters != null && meta.wiringDegradation == null,
        onRefreshList = onRefreshList,
    )
}

/**
 * 元数据宿主的**悬浮面**：菜单（长按触发）、编辑弹层、失败横条。渲染在
 * 会话列表 pane 的上层（调用方把列表与宿主放进同一个 `Box`），自身不消费
 * 点击 —— 没有菜单/弹层/横条时对列表零干扰。
 *
 * 横条位置取「玻璃顶栏区（72dp）+ 8dp 呼吸」：与 `SessionsScreen` 的 contentPadding
 * 顶区对齐，横条永远压在列表内容上方而不是钻进玻璃条里。
 */
@Composable
fun SessionMetaOverlayHost(
    overlay: SessionMetaOverlay,
    rows: List<SessionSummary>,
    modifier: Modifier = Modifier,
) {
    val meta = overlay.meta
    val scope = rememberCoroutineScope()
    LaunchedEffect(overlay) { overlay.bootstrap() }
    LaunchedEffect(rows) { overlay.syncFrom(rows) }

    Box(modifier.fillMaxSize()) {
        // 行上下文菜单：Popup 锚在本 Box（= 列表 pane）右上角。
        meta.menuFor?.let { sessionId ->
            SessionContextMenu(
                state = meta,
                sessionId = sessionId,
                onDispatch = { item -> scope.launch { overlay.dispatch(sessionId, item) } },
            )
        }

        // 编辑弹层（同一时刻至多一个，editor 互斥保证）。
        when (val editor = meta.editor) {
            is MetaEditor.RenameSession -> RenameSheet(
                editor = editor,
                onCommit = { text -> scope.launch { overlay.commitRename(text) } },
                onClose = { meta.closeEditor() },
            )
            is MetaEditor.ConfirmDeleteSession -> DeleteConfirmSheet(
                editor = editor,
                onConfirm = { scope.launch { overlay.confirmDelete(editor.session.id) } },
                onCancel = { meta.cancelDelete() },
            )
            is MetaEditor.MoveToGroup -> MoveToGroupSheet(
                editor = editor,
                groups = meta.groups,
                onPick = { groupId -> scope.launch { overlay.moveToGroup(groupId) } },
                // 分组管理入口（任务 2）：表单里直接进编辑器；null = 新建。
                onEditGroup = { groupId ->
                    if (groupId == null) meta.openNewGroupSheet() else meta.openGroupEditor(groupId)
                },
                onClose = { meta.closeEditor() },
            )
            is MetaEditor.GroupSheet -> GroupEditorSheet(
                sheet = editor,
                onName = meta::setGroupDraftName,
                onIcon = meta::setGroupDraftIcon,
                onColor = meta::setGroupDraftColor,
                onClearIcon = { meta.clearGroupDraftIcon() },
                onClearColor = { meta.clearGroupDraftColor() },
                canClear = meta.canClearGroupIconColor,
                onCommit = { scope.launch { overlay.commitGroupSheet() } },
                onDelete = { scope.launch { overlay.deleteGroupFromEditor() } },
                onClose = { meta.closeEditor() },
            )
            null -> Unit
        }

        // 写动作失败横条（最近一条，可关闭）—— 分类文案来自 MetaActionError。
        meta.actionError?.let { failure ->
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = ActionBannerTop)
                    .padding(horizontal = 12.dp),
            ) {
                MetaErrorBanner(
                    text = failure.message,
                    onDismiss = meta::dismissActionError,
                )
            }
        }
    }
}

/** 失败横条的顶距 = 玻璃顶栏区（shell 的 `TOP_BAR_ZONE` 72dp）+ 8dp 呼吸。 */
private val ActionBannerTop = 80.dp
