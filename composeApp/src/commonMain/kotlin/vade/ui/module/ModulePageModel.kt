package vade.ui.module

import kotlinx.serialization.json.JsonObject
import vade.ui.skin.BtnStyle

/**
 * C-19 (RFC-0009) 模块页面的**类型化模型** — 服务端 `manifest.ui.pages[]` / `ui/page.get`
 * 返回的声明式节点树的客户端表示。本包只做三件事：解析校验（fail-closed）、只读绑定解析、
 * 原生渲染；没有脚本、没有表达式求值器、没有 WebView（FR-8.78 硬红线）。
 *
 * 节点 = 4 个布局原语（column / row / tabs / list）+ 1 个文本叶（text）+ C-09 白名单
 * 组件引用（7 个）。数据只读绑定三个根：`module.status.*` / `page.state.*` / `item.*`；
 * 动作只有 3 类：`navigate` / `tools/call` / `ui/submit`。
 */

/** C-19 节点树的页内锚点 id 与白名单图标枚举共享的取值域（§4.2 icon 枚举）。 */
enum class PageIcon { List, Chart, Settings, Tool, File, None }

/** 布局间距 token：`gap ∈ none|s|m|l`（§4.3）。dp 值对齐视觉语言 §四 间距刻度。 */
enum class PageGap(val dpSize: Int) { None(0), S(8), M(12), L(20) }

/** `row` 的 `align ∈ start|center|end`。 */
enum class RowAlign { Start, Center, End }

/** `text` 叶的 `style ∈ title|subtitle|body|caption|mono`。 */
enum class PageTextStyle { Title, Subtitle, Body, Caption, Mono }

/** `text` 叶的 `format`；未知 format 在解析期按 text 降级（§7.2 允许降级），不进本枚举。 */
enum class PageTextFormat { Text, Int, Decimal, Percent, Bytes, Datetime, Relative }

/** C-09 子集白名单（rev 1，§4.5）——只有这 7 个 `component` 名可用。 */
enum class ComponentKind(val wireName: String) {
    Button("Button"),
    TextField("TextField"),
    Card("Card"),
    ListItem("ListItem"),
    Switch("Switch"),
    ToolCallCard("ToolCallCard"),
    AttachmentChip("AttachmentChip"),
    ;

    companion object {
        fun fromWire(name: String): ComponentKind? = entries.firstOrNull { it.wireName == name }
    }
}

/**
 * 宿主组件（§4.5 明确不得出现）：`MessageBubble` / `InputBar` / `ApprovalDialog` /
 * `BottomBar`。一出现即**整页拒渲**（不是忽略）——模块页面能伪装会话记录、输入区、
 * 审批弹窗或一级导航，这是钓鱼面而非样式问题。
 */
enum class HostComponent(val wireName: String) {
    MessageBubble("MessageBubble"),
    InputBar("InputBar"),
    ApprovalDialog("ApprovalDialog"),
    BottomBar("BottomBar"),
    ;

    companion object {
        fun fromWire(name: String): HostComponent? = entries.firstOrNull { it.wireName == name }
    }
}

/**
 * `ToolCallCard` 的 `state ∈ running|ok|failed|waiting_approval`。未知字面量在解析期
 * 拒渲（props 类型不符，§7.2 结构性越界）。
 */
enum class ToolNodeState { Running, Ok, Failed, WaitingApproval }

/** `AttachmentChip` 的 `state`；与 C-09 `AttachmentState` 一一对应。 */
enum class ChipNodeState { Validating, Uploading, Uploaded, Failed }

/**
 * 只读绑定路径（§5.1/§5.2）：点号分隔 ≤4 段，每段 `[a-z0-9_]{1,32}`。没有表达式、
 * 没有运算——不求值就没有注入面。路径形式非法 = 结构性越界（解析期拒渲）；
 * 形式合法但根不在三个根内 = 渲染期空值 + 一次性提示（§5.4），不拒渲。
 */
data class PageBinding(val raw: String, val segments: List<String>) {
    val root: String get() = segments.first()

    companion object {
        val SEGMENT = Regex("^[a-z0-9_]{1,32}$")

        /** 形式非法返回 null（调用方按结构性越界拒渲）。 */
        fun parse(raw: String): PageBinding? {
            val segments = raw.split('.')
            if (segments.isEmpty() || segments.size > 4) return null
            if (segments.any { !SEGMENT.matches(it) }) return null
            return PageBinding(raw, segments)
        }
    }
}

/** `visible` 的两种形态（§4.4）：字面量布尔或只读绑定（只做真值判定）。 */
sealed interface PageVisibility {
    data class Literal(val value: Boolean) : PageVisibility
    data class Bound(val binding: PageBinding) : PageVisibility
}

/**
 * 组件 props / `text` 叶内容的三种取值形态（§4.4）：字面量、只读绑定、本地输入。
 * 绑定可带展示层 `format`（§4.6 示例：`{"bind":"item.progress","format":"percent"}`）；
 * `$input` 只允许出现在动作参数与 TextField.value（§5.3），不能出现在 data_ref 或
 * 其他 props 位置——解析期按位置分别校验。
 */
sealed interface PropValue {
    data class Literal(val value: String) : PropValue
    data class Bound(val binding: PageBinding, val format: PageTextFormat = PageTextFormat.Text) : PropValue
}

/** TextField 的 `value` 只接受 `$input` 引用（§5.3：值只写本地，不回写模块状态）。 */
data class InputRef(val inputId: String)

/**
 * 三类动作（§6）。`kind` 不在三类之内 → 整页拒渲；每节点 ≤4 个动作、每页 ≤64 个。
 */
sealed interface PageAction {
    /** `navigate`——目标在解析期定死，`module:<page_id>` 必须是本模块声明的页面。 */
    data class Navigate(val target: NavigateTarget) : PageAction

    /** `tools/call`——`tool` 必须是本模块声明的能力（归属校验在 core，§10.2）。 */
    data class ToolCall(val tool: String, val args: JsonObject, val confirmText: String?) : PageAction

    /** `ui/submit`——part 只允许 type:"text"（§6.3）。 */
    data class UiSubmit(val parts: List<SubmitPart>) : PageAction
}

sealed interface NavigateTarget {
    /** `module:<page_id>` —— 页内路由，不进深链（§8.3）。 */
    data class ModulePage(val pageId: String) : NavigateTarget

    /** `session:current` / `session:<id>`。 */
    data class Session(val sessionId: String?) : NavigateTarget

    /** `settings:<tab>`，枚举固定：appearance / models / devices / about。 */
    data class Settings(val tab: String) : NavigateTarget
}

/** `ui/submit` 的 part；text 可字面量 / bind / $input。 */
data class SubmitPart(val text: SubmitText)

sealed interface SubmitText {
    data class Literal(val value: String) : SubmitText
    data class Bound(val binding: PageBinding) : SubmitText
    data class InputRef(val inputId: String) : SubmitText
}

/**
 * 节点模型。容器 = Column / Row / Tabs / ListBlock / ComponentNode(Card)；
 * 叶 = TextLeaf / 其余组件。叶带 children → 拒渲（§4.4）。
 */
sealed interface PageNode {
    /** 页内唯一锚点 id（可缺省；出现时校验 pattern 与唯一性，§4.4）。 */
    val nodeId: String?

    /** `visible` 布尔或绑定；解析期只存形态，真值判定在解析器（§5.2）。 */
    val visible: PageVisibility?

    /** `column`：纵向排布，children ≤32。 */
    data class Column(
        val gap: PageGap,
        val children: List<PageNode>,
        override val nodeId: String? = null,
        override val visible: PageVisibility? = null,
    ) : PageNode

    /** `row`：横向排布。 */
    data class Row(
        val gap: PageGap,
        val align: RowAlign,
        val wrap: Boolean,
        val children: List<PageNode>,
        override val nodeId: String? = null,
        override val visible: PageVisibility? = null,
    ) : PageNode

    /** `tabs`：页内分栏（不是路由）；每个 child 带 `props.tab_title`。 */
    data class Tabs(
        val tabs: List<TabPage>,
        val selected: PropValue?,
        override val nodeId: String? = null,
        override val visible: PageVisibility? = null,
    ) : PageNode

    /** `list`：data_ref 指向数组（≤512 项），**恰好 1 个**模板 child，窗口 ≤50 项/屏（Q3）。 */
    data class ListBlock(
        val dataRef: PageBinding,
        val emptyText: String,
        val itemFields: List<String>?,
        val template: PageNode,
        override val nodeId: String? = null,
        override val visible: PageVisibility? = null,
    ) : PageNode

    /**
     * `text` 叶：内容 = data_ref（标量字符串化）或 props.text 字面量（二选一，都缺 → 拒渲）。
     */
    data class TextLeaf(
        val content: PropValue?,
        val style: PageTextStyle,
        val maxLines: Int,
        val format: PageTextFormat,
        override val nodeId: String? = null,
        override val visible: PageVisibility? = null,
    ) : PageNode

    /** `component`：C-09 白名单引用；只有 Card 允许 children。 */
    data class ComponentNode(
        val kind: ComponentKind,
        val props: ComponentProps,
        val events: Map<String, PageAction>,
        val children: List<PageNode>,
        override val nodeId: String? = null,
        override val visible: PageVisibility? = null,
        val dataRef: PageBinding? = null,
    ) : PageNode
}

data class TabPage(val title: String, val child: PageNode)

/** 7 个白名单组件的 props（封闭集，§4.5——未知键 → 拒渲）。 */
sealed interface ComponentProps {
    data class Button(
        val text: PropValue?,
        val style: BtnStyle,
        val enabled: PageVisibility?,
    ) : ComponentProps

    data class TextField(
        val label: PropValue?,
        val placeholder: PropValue?,
        val value: InputRef?,
        val multiline: Boolean,
        val maxLength: Int,
    ) : ComponentProps

    data class Card(
        val title: PropValue?,
        val subtitle: PropValue?,
        val elevated: Boolean,
    ) : ComponentProps

    data class ListItem(
        val title: PropValue?,
        val subtitle: PropValue?,
        val leadingIcon: PageIcon?,
        val trailingText: PropValue?,
    ) : ComponentProps

    data class Switch(
        val label: PropValue?,
        val enabled: PageVisibility?,
    ) : ComponentProps

    data class ToolCallCard(
        val tool: PropValue?,
        val state: ToolNodeState,
        val argsPreview: PropValue?,
        val resultSummary: PropValue?,
    ) : ComponentProps

    data class AttachmentChip(
        val fileId: PropValue?,
        val mime: PropValue?,
        val name: PropValue?,
        val state: ChipNodeState,
    ) : ComponentProps
}
