package vade.ui.meta

/**
 * 一个可选分组字段的**三态写意图**，与 `session/group.update` 的线格式一一对应：
 *
 * | 意图 | 线上的样子 | 语义 |
 * |---|---|---|
 * | [Keep] | 键不存在 | 保持不变 |
 * | [Clear] | `"icon": null` | 清除（契约 1.4.0 起 icon/color 是可选字段） |
 * | [Set] | `"icon": "rocket"` | 设置成新值 |
 *
 * [SessionMetaActions.updateGroup] 的两态签名把 `null` 解释成「保持」，所以它既表达不了
 * [Clear]、也无法区分「没改」与「清掉」—— 三态写走 [SessionMetaGroupEdit]。
 */
sealed interface MetaFieldEdit<out T> {

    /** 键缺省：服务端保持原值。 */
    data object Keep : MetaFieldEdit<Nothing>

    /** 显式 JSON null：服务端清空该字段。 */
    data object Clear : MetaFieldEdit<Nothing>

    /** 设置成 [value]（图标必须白名单名，颜色必须 `#RRGGBB`）。 */
    data class Set<T>(val value: T) : MetaFieldEdit<T>
}

/**
 * `session/group.update` 的**三态**窄能力（g-meta-wire）。
 *
 * 为什么单独一条接口：`SessionMetaActions` 是九个方法的两态签名（`null` = 保持），
 * 而线格式的 `icon` / `color` 从契约 1.4.0 起就是三态（`Optional<String>` →
 * 缺省 / null / 值）。清除因此需要一个能表达「显式 null」的入口。
 *
 * 实现方必须**真的**把 [MetaFieldEdit.Clear] 写成 JSON null（客户端
 * `SessionGroupUpdateParams` 的 `Optional.Null` 已经支持），不得退化成「省略键」。
 * 注入的实现若没有本能力，`SessionMetaState.canClearGroupIconColor` 为 false，
 * UI 不显示清除按钮，且状态机对清除请求明确报 [MetaCopy.GROUP_CLEAR_UNSUPPORTED]。
 */
interface SessionMetaGroupEdit {

    /**
     * `session/group.list`，**容忍** `icon` / `color` 为 null 的投影。
     *
     * 与 [SessionMetaActions.listGroups] 的区别只在投影：那边把缺失的 icon/color 当作
     * 协议违约（PROTOCOL_MISMATCH）；按契约它们是可选的，清除后服务端就存 null，
     * 所以三态实现这边把 null 原样投影成 [MetaGroup] 的空值。
     */
    suspend fun listGroups(): List<MetaGroup>

    /**
     * `session/group.update`：名字二态（null = 保持），图标/颜色三态。
     * 返回服务端回执投影（本地列表以它为准，不猜服务端存了什么）。
     */
    suspend fun editGroup(
        groupId: String,
        name: String? = null,
        icon: MetaFieldEdit<String> = MetaFieldEdit.Keep,
        color: MetaFieldEdit<String> = MetaFieldEdit.Keep,
    ): MetaGroup
}
