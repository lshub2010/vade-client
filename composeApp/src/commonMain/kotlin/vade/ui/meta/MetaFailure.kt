package vade.ui.meta

import vade.ui.data.VadeErrorCategory
import vade.ui.data.loadErrorCategoryOf
import vade.ui.data.loadErrorCodeOf

/**
 * 9 个 RPC 写动作 + 分组列表读的**动作身份**（失败文案的第一个词）。
 * 与 [SessionMetaActions] 的方法一一对应 —— 新增一个窄接口方法就必须在这里加一项，
 * 否则失败无法归类（本类刻意不提供「其他」兜底）。
 */
enum class MetaAction(val label: String) {
    Rename("重命名"),
    Pin("置顶"),
    Archive("归档"),
    DeleteSession("删除会话"),
    MoveToGroup("移动分组"),
    LoadGroups("加载分组"),
    CreateGroup("新建分组"),
    UpdateGroup("保存分组"),
    DeleteGroup("删除分组"),
}

/**
 * 一次动作失败的**可展示**记录（g-meta-wire）。
 *
 * 三个字段就是 UI 能做的三种决定：动作（重试哪一条）、
 * [category]（重试 / 重连 / 用户改输入 / 只能放弃）、[code]（C-04 原码，排查用）。
 * 分类永远走冻结的 [loadErrorCategoryOf] 表，不在 UI 里另写一套判断。
 *
 * 失败**绝不静默**：每个写方法都把最近一次失败放进 `SessionMetaState.actionError`，
 * UI 直接渲染 [message]；同一时刻只保留最近一条（更早的失败已被后续动作取代）。
 */
data class MetaActionError(
    val action: MetaAction,
    val category: VadeErrorCategory,
    /** C-04 code when the failure came through the protocol seam; null for transport-level throwables. */
    val code: String?,
    /** 可读原因（异常 message；没有就用兜底文案，绝不空串）。 */
    val detail: String,
) {

    /** 直接渲染的一行：`重命名失败：<原因>（需要重连）· NOT_FOUND`。 */
    val message: String
        get() = buildString {
            append(action.label)
            append("失败：")
            append(detail)
            append("（")
            append(category.label)
            append("）")
            if (code != null) {
                append(" · ")
                append(code)
            }
        }

    /** 只有 Retryable 才有「原样重试」的意义；其余分类要先重连或改输入。 */
    val retryable: Boolean get() = category == VadeErrorCategory.Retryable

    companion object {
        fun of(action: MetaAction, t: Throwable): MetaActionError = MetaActionError(
            action = action,
            category = loadErrorCategoryOf(t),
            code = loadErrorCodeOf(t),
            detail = t.message?.trim()?.takeIf { it.isNotEmpty() } ?: MetaCopy.UNKNOWN_FAILURE_DETAIL,
        )
    }
}

/**
 * 分组列表的加载状态（g-meta-wire）。**失败不是空表**：
 * [Failed] 时 `SessionMetaState.groups` 保持上一次的权威快照（可能为空，也可能是旧值），
 * UI 必须渲染重试卡（[MetaCopy.GROUPS_LOAD_FAILED_TITLE]）而不是空态卡 ——
 * 空态卡的语义是「服务端确实没有分组」，两者不能混。
 */
sealed interface MetaGroupsLoad {

    /** 还没拉过（工厂刚建好、或取消后回到可重试的初始态）。 */
    data object Idle : MetaGroupsLoad

    /** 一次 `session/group.list` 正在飞。 */
    data object Loading : MetaGroupsLoad

    /** 最近一次拉取成功；`groups` 即服务端权威列表。 */
    data object Loaded : MetaGroupsLoad

    /** 最近一次拉取失败：可展示错误 + 由 [MetaGroupsLoad] 的使用方给重试入口。 */
    data class Failed(val error: MetaActionError) : MetaGroupsLoad
}
