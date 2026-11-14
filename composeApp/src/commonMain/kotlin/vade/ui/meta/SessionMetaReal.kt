package vade.ui.meta

import vade.ui.data.MetaActionsReal
import vade.ui.data.SessionSummary
import vade.ui.protocol.GroupRow
import vade.ui.protocol.Optional
import vade.ui.protocol.SessionGroupListParams
import vade.ui.protocol.SessionGroupUpdateParams
import vade.ui.protocol.VadeClient
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException

/**
 * **一行接线**（g-meta-wire）：把真 RPC 装进 [SessionMetaState]。
 *
 * 之前唯一缺的就是这一层：状态机早就只依赖窄接口 [SessionMetaActions]，
 * 但传进去的永远是内存假实现 [FakeMetaActions] —— 九个按钮改的是内存，不是 core。
 * 现在接线只剩一行：
 *
 * ```
 * val meta = remember { SessionMetaState.forReal(client, state.selectedSessionId.orEmpty()) }
 * LaunchedEffect(Unit) { meta.loadGroups() }   // 失败的读不进空表，见 MetaGroupsLoad
 * ```
 *
 * ## 参数
 * - [client]：**已 `connect()`** 的共享实例（和 `RealSessionSource` 用同一条连接；
 *   适配器不持连接、不管重连）。没连上时第一个 RPC 会抛，状态机会按
 *   [vade.ui.data.VadeErrorCategory.NeedsReconnect] 展示出来，不会静默。
 * - [sessionId]：本面板聚焦的会话（行高亮用，见 [SessionMetaState.focusSessionId]）。
 * - [sessions] / [groups]：列表投影。`session/list` 是读路径，窄接口有意不含它，
 *   所以会话投影由数据层（`RealSessionSource`）投影后传入；后续快照变化走
 *   [SessionMetaState.applyProjection]。分组则可以直接用 [SessionMetaState.loadGroups]
 *   从 core 拉（`session/group.list`）。
 *
 * ## 两个适配器，一个状态机
 * 八个会话/分组动作仍走 `data/MetaActionsReal`；`session/group.update` 的
 * `icon`/`color` 是三态（键缺省 = 保持 / JSON null = 清除 / 值 = 设置），两态签名
 * 表达不了「清除」，所以此外再注入一份 [MetaGroupEditReal]（见 [SessionMetaGroupEdit]）。
 * 状态机优先用三态那一份：它的 `group.list` 投影也容忍 null（清除后服务端存的就是 null）。
 *
 * ## 失败语义（与状态机一致）
 * 写动作抛 [VadeException]（C-04 `code` 原样）→ 状态机回滚乐观值并把分类后的可展示错误
 * 放进 [SessionMetaState.actionError]；不会有任何一次失败被吞掉。
 */
fun SessionMetaState.Companion.forReal(
    client: VadeClient,
    sessionId: String,
    sessions: List<MetaSession> = emptyList(),
    groups: List<MetaGroup> = emptyList(),
): SessionMetaState = SessionMetaState(
    initialSessions = sessions,
    initialGroups = groups,
    actions = MetaActionsReal(client),
    focusSessionId = sessionId,
    groupEdit = MetaGroupEditReal(client),
)

/**
 * `data.SessionSummary` → [MetaSession]（g-archive-ui）：本文件 KDoc 承诺的那层投影。
 *
 * 为什么需要它：`SessionMetaState` 只认 [MetaSession]，而 `session/list` 的读路径由数据层
 * 投影成 `SessionSummary`（`RealSessionSource` 的 `sessions()`）。没有这一步，真数据永远
 * 进不了元数据状态机 —— 行上的 `archived` / `groupId` 也就到不了编辑菜单与分组筛选。
 *
 * ## 字段映射（逐字对齐 [MetaSession] 的声明，7/7 全覆盖，不增不减）
 *
 * | MetaSession 字段 | 类型 | 默认 | ← SessionSummary | 说明 |
 * |---|---|---|---|---|
 * | `id` | `String` | — | `id` | 线上 `session_id`，主键 |
 * | `title` | `String` | — | `title` | 线上 `title`（缺省已被数据层兜成「（无标题）」） |
 * | `preview` | `String` | `""` | `preview` | UI 自有；`session/list` 行不带内容，通常为空 |
 * | `updatedLabel` | `String` | `""` | `updatedLabel` | 派生标签（`relativeLabel`），原样搬 |
 * | `pinned` | `Boolean` | `false` | `pinned` | 线上 `pinned_at != null` |
 * | `archived` | `Boolean` | `false` | `archived` | 线上 `archived_at != null` |
 * | `groupId` | `String?` | `null` | `groupId` | 线上 `group_id` 逐字（null = 未分组） |
 *
 * **SessionSummary 有、MetaSession 没有的 5 个字段**（`bucket` / `updatedAt` / `unread` /
 * `model` / `isDraft`）**在这里被有意丢弃**，不是漏了：MetaSession 的定位是「列表行 +
 * 编辑菜单所需的最小集合」（见其声明处 KDoc），这 5 个都是列表展示态（分桶、排序键、未读点、
 * D101 徽标、草稿标记），元数据面板不消费。**不给 MetaSession 加槽位** —— 契约里没有对应
 * 写方法（`session/list` 只读；`model` 走 `session/model.set` 的另一套模型），加槽位就是
 * 在编一个不存在的读路径。
 *
 * 反向也成立：MetaSession 没有任何字段在 SessionSummary 里找不到来源，所以映射里没有
 * 「保持默认值凑数」的分支 —— 7 个字段全部逐字搬运。
 */
fun SessionSummary.toMetaSession(): MetaSession = MetaSession(
    id = id,
    title = title,
    preview = preview,
    updatedLabel = updatedLabel,
    pinned = pinned,
    archived = archived,
    groupId = groupId,
)

/**
 * `session/group.update` / `session/group.list` 的**三态**真适配器。
 *
 * 为什么不是 `MetaActionsReal` 的一部分：那是 `data` 包的窄接口实现，签名两态
 * （`null` = 保持不变），并把缺失的 `icon`/`color` 当作协议违约。契约 1.4.0 起这两个
 * 字段是可选字段，清除后服务端存的就是 null —— 这里按线格式如实投影，并让
 * [MetaFieldEdit.Clear] 真的写成 `"icon": null`（`SessionGroupUpdateParams` 的
 * `Optional.Null`；`putOpt` 对 `Optional.Null` 写 `JsonNull`，不是省略键）。
 *
 * 两个方法各一条 RPC，没有本地状态、没有连接：调用方注入已连接的共享 [VadeClient]。
 */
class MetaGroupEditReal(private val client: VadeClient) : SessionMetaGroupEdit {

    /** `session/group.list`：无参数；顺序原样保留（sort_order, created_at）。 */
    override suspend fun listGroups(): List<MetaGroup> =
        client.sessionGroupList(SessionGroupListParams()).groups.map { entry -> entry.group.toMetaGroup() }

    /** `session/group.update`：只发有意图的键；[MetaFieldEdit.Clear] → 显式 JSON null。 */
    override suspend fun editGroup(
        groupId: String,
        name: String?,
        icon: MetaFieldEdit<String>,
        color: MetaFieldEdit<String>,
    ): MetaGroup {
        if (groupId.isBlank()) throw invalidArgument("params.group_id is required and must be non-blank")
        val trimmedName = name?.trim()
        if (trimmedName != null && trimmedName.isEmpty()) {
            throw invalidArgument("params.name cannot be blank (a name cannot be cleared)")
        }
        if (trimmedName == null && icon == MetaFieldEdit.Keep && color == MetaFieldEdit.Keep) {
            throw invalidArgument("session/group.update requires at least one of name/icon/color")
        }
        val result = client.sessionGroupUpdate(
            SessionGroupUpdateParams(
                groupId = groupId,
                name = trimmedName,
                icon = icon.toOptional(),
                color = color.toOptional(),
            ),
        )
        return result.group.toMetaGroup()
    }

    /**
     * 契约 group row → UI [MetaGroup]。与 `MetaActionsReal` 唯一的区别：
     * `icon` / `color` 缺失是**合法状态**（被清除过），投影成 null 而不是抛协议违约。
     */
    private fun GroupRow.toMetaGroup(): MetaGroup = MetaGroup(
        id = groupId,
        name = name,
        iconName = icon,
        colorHex = color,
    )

    private fun MetaFieldEdit<String>.toOptional(): Optional<String> = when (this) {
        is MetaFieldEdit.Keep -> Optional.Absent
        is MetaFieldEdit.Clear -> Optional.Null
        is MetaFieldEdit.Set -> Optional.Value(value)
    }

    private fun invalidArgument(message: String): VadeException =
        VadeException(VadeErrorCodes.INVALID_ARGUMENT, message)
}
