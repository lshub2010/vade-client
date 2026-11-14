package vade.ui.meta

import vade.ui.data.MetaActionsReal
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
