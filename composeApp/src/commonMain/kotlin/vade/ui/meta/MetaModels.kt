package vade.ui.meta

/**
 * w-c-meta · 会话元数据（RFC-0010 / C-05 1.3.0）的 **UI 侧模型**。
 *
 * 刻意与 `vade.ui.data` 包解耦：数据层由另一根平行 stick 接线，本轮 UI 只认这里的
 * 两个不可变类型；接真数据时由数据层把协议模型投影成 [MetaSession] / [MetaGroup]。
 *
 * 字段与服务端契约一一对应：
 *  - 图标 = `ICON_NAMES` 白名单里的一个名字（见 `vade.ui.icon.MetaIcons`，39 个）；
 *  - 颜色 = `#RRGGBB`（服务端 `COLOR_PATTERN = ^#[0-9a-fA-F]{6}$`，客户端统一归一成大写）。
 */

/** 一条会话的元数据投影（列表行 + 编辑菜单所需的最小集合）。 */
data class MetaSession(
    val id: String,
    val title: String,
    val preview: String = "",
    val updatedLabel: String = "",
    /** 服务端 `session/pin` 的落点。 */
    val pinned: Boolean = false,
    /** 服务端 `session/archive` 的落点。 */
    val archived: Boolean = false,
    /** 所属分组；null = 未分组（服务端 `session/group.assign` 传 null 的语义）。 */
    val groupId: String? = null,
)

/** 一个用户自建分组（服务端 `session/group.*` 五方法的落点）。 */
data class MetaGroup(
    val id: String,
    val name: String,
    /** `ICON_NAMES` 白名单名；非法名由服务端拒收，UI 侧构造时走 [MetaIcons]。 */
    val iconName: String,
    /** 大写 `#RRGGBB`。 */
    val colorHex: String,
)

/** 所有面向用户的文案常量集中在这一处，测试（GROUP_DELETE_NOTE 等）直接钉住。 */
object MetaCopy {
    /** 上下文菜单 6 项（固定集合，`SessionMenuItem` 之外的入口不得混进来）。 */
    const val MENU_RENAME = "重命名"
    const val MENU_PIN = "置顶"
    const val MENU_UNPIN = "取消置顶"
    const val MENU_ARCHIVE = "归档"
    const val MENU_UNARCHIVE = "取消归档"
    const val MENU_MOVE_TO_GROUP = "移动到分组"
    const val MENU_NEW_GROUP = "新建分组"
    const val MENU_DELETE = "删除"

    /** 删除会话的二次确认：必须说明不可恢复。 */
    const val DELETE_TITLE = "删除会话"
    const val DELETE_BODY = "「%s」将被永久删除，消息记录不可恢复。此操作无法撤销。"
    const val DELETE_CONFIRM = "删除"
    const val CANCEL = "取消"

    /** 分组编辑器：名称 / 图标 / 颜色 / 危险区。 */
    const val GROUP_EDITOR_NEW_TITLE = "新建分组"
    const val GROUP_EDITOR_EDIT_TITLE = "编辑分组"
    const val GROUP_NAME_PLACEHOLDER = "分组名称"
    const val GROUP_ICON_SECTION = "图标"
    const val GROUP_COLOR_SECTION = "颜色"
    const val GROUP_SAVE = "保存"
    const val GROUP_CREATE = "创建"

    /**
     * 删除分组的说明（测试⑥钉住「不会删除会话」）：服务端 `session/group.delete`
     * 只删组并把这些会话的 `group_id` 置 NULL，会话本体不受影响。
     */
    const val GROUP_DELETE_NOTE = "删除分组不会删除会话：组内会话会变为「未分组」，可随时再次分配。"
    const val GROUP_DELETE_CONFIRM = "删除分组"

    /** 「未分组」虚拟分组（菜单与移动表单里的落点，不是真实组）。 */
    const val UNGROUPED_LABEL = "未分组"

    /** 表单校验（重命名与分组名称共用一条）。 */
    const val TITLE_EMPTY_ERROR = "名称不能为空"
    const val GROUP_DRAFT_INVALID = "图标或颜色不在允许范围内"


    /** 分组列表为空时的空态（测试⑦）。 */
    const val GROUPS_EMPTY_TITLE = "还没有分组"
    const val GROUPS_EMPTY_BODY = "用「新建分组」把会话按项目分类，支持自定义图标与颜色。"
}
