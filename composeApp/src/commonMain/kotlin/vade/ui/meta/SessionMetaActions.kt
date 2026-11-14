package vade.ui.meta

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * w-c-meta 的**窄接口**：把 RFC-0010 十方法里属于「编辑」的九个动作收进一个界面，
 * UI（`SessionMetaState` / `SessionMetaHost`）只依赖它。
 *
 * 有意不包含 `session/list`：列表是读路径，由数据层（`vade.ui.data` 包，另一根 stick）
 * 以 `List<MetaSession>` / `List<MetaGroup>` 形态喂给 UI；本接口只描述**写动作**。
 *
 * **接真数据只需要做一件事**：让数据层实现本接口（每个方法 → 一个 RPC 调用：
 * `session/rename|pin|archive|delete`、`session/group.assign`、
 * `session/group.list|create|update|delete`），然后把实现交给 [SessionMetaState]。
 * UI、菜单、选择器、测试全部不动 —— 本轮的 [FakeMetaActions] 就是参照实现。
 */
interface SessionMetaActions {

    // --- session 类（会话侧的五个写方法） ---------------------------------------------

    /** `session/rename`。 */
    suspend fun renameSession(sessionId: String, title: String)

    /** `session/pin`（幂等布尔，置顶与取消置顶共用一个方法）。 */
    suspend fun setSessionPinned(sessionId: String, pinned: Boolean)

    /** `session/archive`（同上，归档/取消归档共用）。 */
    suspend fun setSessionArchived(sessionId: String, archived: Boolean)

    /** `session/delete` — UI 必须先走二次确认（`SessionMetaState.requestDelete`）。 */
    suspend fun deleteSession(sessionId: String)

    /** `session/group.assign`；[groupId] = null 表示移出分组（未分组）。 */
    suspend fun assignSessionToGroup(sessionId: String, groupId: String?)

    // --- session/group.* ----------------------------------------------------------

    /** `session/group.list`（编辑器打开时拉全量；不做增量订阅）。 */
    suspend fun listGroups(): List<MetaGroup>

    /** `session/group.create`；返回带服务端 id 的组。 */
    suspend fun createGroup(name: String, iconName: String, colorHex: String): MetaGroup

    /** `session/group.update` —— 三个字段都可空，只提交要改的；图标与颜色可同一次提交。 */
    suspend fun updateGroup(groupId: String, name: String? = null, iconName: String? = null, colorHex: String? = null)

    /**
     * `session/group.delete` — 只删组：服务端把组内会话的 `group_id` 置 NULL，
     * **会话本体不受影响**（UI 文案 [MetaCopy.GROUP_DELETE_NOTE] 与此对齐）。
     */
    suspend fun deleteGroup(groupId: String)
}

/** 一次动作调用的记录（op + 语义化参数串），[FakeMetaActions.calls] 供测试断言。 */
data class MetaCall(val op: String, val detail: String)

/**
 * 内存实现：本轮驱动 UI 与全部测试；也是数据层实现真接口时的行为参照
 * （尤其 [deleteGroup] 的「会话不解散、`groupId` 置 null」语义）。
 */
class FakeMetaActions(
    seedSessions: List<MetaSession> = defaultSessions,
    seedGroups: List<MetaGroup> = defaultGroups,
) : SessionMetaActions {

    val sessions = MutableStateFlow(seedSessions)
    val groups = MutableStateFlow(seedGroups)

    /** 按调用顺序追加；测试用它证明「未确认时绝不回调删除」。 */
    val calls = mutableListOf<MetaCall>()

    private fun record(op: String, detail: String = "") {
        calls += MetaCall(op, detail)
    }

    override suspend fun renameSession(sessionId: String, title: String) {
        record("session/rename", "$sessionId:$title")
        sessions.update { list -> list.map { if (it.id == sessionId) it.copy(title = title) else it } }
    }

    override suspend fun setSessionPinned(sessionId: String, pinned: Boolean) {
        record("session/pin", "$sessionId:$pinned")
        sessions.update { list -> list.map { if (it.id == sessionId) it.copy(pinned = pinned) else it } }
    }

    override suspend fun setSessionArchived(sessionId: String, archived: Boolean) {
        record("session/archive", "$sessionId:$archived")
        sessions.update { list -> list.map { if (it.id == sessionId) it.copy(archived = archived) else it } }
    }

    override suspend fun deleteSession(sessionId: String) {
        record("session/delete", sessionId)
        sessions.update { list -> list.filterNot { it.id == sessionId } }
    }

    override suspend fun assignSessionToGroup(sessionId: String, groupId: String?) {
        record("session/group.assign", "$sessionId:${groupId ?: "null"}")
        sessions.update { list -> list.map { if (it.id == sessionId) it.copy(groupId = groupId) else it } }
    }

    override suspend fun listGroups(): List<MetaGroup> {
        record("session/group.list")
        return groups.value
    }

    override suspend fun createGroup(name: String, iconName: String, colorHex: String): MetaGroup {
        record("session/group.create", "$name|$iconName|$colorHex")
        val group = MetaGroup(id = "g-${groups.value.size + 1}", name = name, iconName = iconName, colorHex = colorHex)
        groups.update { it + group }
        return group
    }

    override suspend fun updateGroup(groupId: String, name: String?, iconName: String?, colorHex: String?) {
        // detail 只含提交了的键 —— 测试⑧用它证明图标与颜色能同一次提交且互不覆盖。
        val parts = buildList {
            if (name != null) add("name=$name")
            if (iconName != null) add("icon=$iconName")
            if (colorHex != null) add("color=$colorHex")
        }
        record("session/group.update", "$groupId:${parts.joinToString(",")}")
        groups.update { list ->
            list.map {
                if (it.id != groupId) it
                else it.copy(
                    name = name ?: it.name,
                    iconName = iconName ?: it.iconName,
                    colorHex = colorHex ?: it.colorHex,
                )
            }
        }
    }

    override suspend fun deleteGroup(groupId: String) {
        record("session/group.delete", groupId)
        groups.update { list -> list.filterNot { it.id == groupId } }
        // 服务端语义：删组不删会话，组内会话变未分组。
        sessions.update { list -> list.map { if (it.groupId == groupId) it.copy(groupId = null) else it } }
    }

    companion object {
        /** 种子：两 组 + 四会话（含置顶/未分组各一），Fake 驱动 UI 演示用。 */
        val defaultGroups = listOf(
            MetaGroup("g-1", "项目", "briefcase", "#3B82F6"),
            MetaGroup("g-2", "灵感", "bulb", "#8B5CF6"),
        )
        val defaultSessions = listOf(
            MetaSession("s-1", "RFC-0010 元数据评审", preview = "图标白名单 39 个", updatedLabel = "2 小时前", pinned = true, groupId = "g-1"),
            MetaSession("s-2", "客户端 UI 拆棒", preview = "GroupCards 最小侵入", updatedLabel = "昨天", groupId = "g-1"),
            MetaSession("s-3", "调色盘配色草稿", preview = "10 个预设色", updatedLabel = "3 天前", groupId = "g-2"),
            MetaSession("s-4", "随手记", preview = "", updatedLabel = "上周", groupId = null),
        )
    }
}
