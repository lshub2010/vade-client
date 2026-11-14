package vade.ui.module

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import vade.ui.skin.BtnStyle

/**
 * C-19 渲染期校验器（fail-closed，§7.2/§7.3）：把 `ui/page.get` 返回的原始节点树解析成
 * [PageNode]。**任何**结构性未知（脚本键 / 未知 type / 未知或宿主组件 / 未知动作 /
 * 结构越界 / 超限）→ 整页 [ParseResult.Rejected]，不渲染任何节点——不做截断后渲染，
 * 截断会把校验失败伪装成正常页面。
 *
 * 与 FR-8.78「未知节点降级为占位」的差异是 C-19 的显式收窄（RFC §7.2 引注）：未知骨架
 * 的占位符位置本身就是错的，「部分渲染」还会给模块留谈判路径。
 */
object ModulePageParser {

    // --- §7.3 限额（防渲染炸弹；恰好达标渲染，超一即拒） -------------------------

    const val MAX_DEPTH = 8
    const val MAX_NODES = 256
    const val MAX_CHILDREN = 32
    const val MAX_TEXT_CHARS = 4096
    const val MAX_ARGS_PREVIEW = 512
    const val MAX_TEXT_FIELD_LENGTH = 4096
    const val MAX_LIST_ITEMS = 512
    const val LIST_WINDOW = 50
    const val MAX_ACTIONS_PER_NODE = 4
    const val MAX_ACTIONS_PER_PAGE = 64

    /** 入口版本门控（§8.2）：页面 `version` 高于客户端支持集 → 入口不出现。 */
    const val SUPPORTED_PAGE_VERSION = 1

    /** C-19 修订（§9.2）：`x-` 前缀是页面级唯一逃生口，宿主完全忽略（不渲不求值不转发）。 */
    private const val ESCAPE_PREFIX = "x-"

    // --- 节点树里任何层级都不得出现的脚本键（§7.2，大小写不敏感） -----------------

    private val SCRIPT_KEYS = setOf(
        "script", "on_load", "onclick", "eval", "js", "html", "innerhtml", "expression", "lambda",
    )

    /** 4 个布局原语 + 1 个文本叶 + 组件引用。 */
    private val NODE_TYPES = setOf("column", "row", "tabs", "list", "text", "component")

    private val NODE_KEYS = setOf("type", "id", "visible", "data_ref", "props", "children", "on", "component")

    private val NODE_ID = Regex("^[a-z][a-z0-9_-]{0,31}$")

    private val SETTINGS_TABS = setOf("appearance", "models", "devices", "about")

    /** 每个组件允许的事件名（§4.5 事件列）。 */
    private val EVENTS_BY_KIND: Map<ComponentKind, Set<String>> = mapOf(
        ComponentKind.Button to setOf("click"),
        ComponentKind.TextField to setOf("change"),
        ComponentKind.Card to setOf("click"),
        ComponentKind.ListItem to setOf("click"),
        ComponentKind.Switch to setOf("change"),
        ComponentKind.ToolCallCard to setOf("click"),
        ComponentKind.AttachmentChip to setOf("click"),
    )

    // --- 入口 -------------------------------------------------------------------

    /**
     * 解析整页。[declaredPageIds] 是本模块 `ui.pages[]` 声明的页面 id 集合，
     * 用于校验 `navigate` 的 `module:<page_id>` 目标（§7.2：navigate 到未声明页 → 拒渲）。
     */
    fun parse(root: JsonElement, declaredPageIds: Set<String>): ParseResult {
        if (root !is JsonObject) return rejected("根节点不是对象")
        val ctx = ParseContext(declaredPageIds)
        val node = try {
            parseNode(root, depth = 1, ctx = ctx)
        } catch (_: PageRejectSignal) {
            null
        } ?: return rejected(ctx.reasons)
        if (ctx.nodeCount > MAX_NODES) {
            return rejected("节点数 ${ctx.nodeCount} 超过上限 $MAX_NODES")
        }
        if (ctx.actionCount > MAX_ACTIONS_PER_PAGE) {
            return rejected("动作数 ${ctx.actionCount} 超过每页上限 $MAX_ACTIONS_PER_PAGE")
        }
        return ParseResult.Ok(node, ctx.nodeCount, ctx.actionCount)
    }

    private fun rejected(reasons: List<String>) = ParseResult.Rejected(reasons.takeLast(12))
    private fun rejected(reason: String) = ParseResult.Rejected(listOf(reason))

    private class ParseContext(val declaredPageIds: Set<String>) {
        var nodeCount = 0
        var actionCount = 0
        val reasons = mutableListOf<String>()
        val nodeIds = mutableSetOf<String>()

        /**
         * 记录一条定位并**抛出拒渲信号**（返回 Nothing）：杜绝「fail 之后表达式继续
         * 求值」的静默漏洞——fail 必须真的中止解析，而不是被当成一个 null 表达式。
         */
        fun fail(reason: String): Nothing {
            reasons += reason
            throw PageRejectSignal()
        }
    }

    /** 拒渲控制流信号（内部使用，绝不外泄）。 */
    private class PageRejectSignal : RuntimeException()

    // --- 节点 -------------------------------------------------------------------

    private fun parseNode(json: JsonObject, depth: Int, ctx: ParseContext): PageNode? {
        if (depth > MAX_DEPTH) {
            return ctx.fail("嵌套深度 $depth 超过上限 $MAX_DEPTH")
        }
        ctx.nodeCount += 1
        if (ctx.nodeCount > MAX_NODES) {
            return ctx.fail("节点数超过上限 $MAX_NODES")
        }
        // 脚本键扫描先于未知键检查：§7.2 第一条规则给出最精准的拒渲定位。
        if (!scanScriptKeys(json, "$", ctx)) return null
        // 未知键就是潜在脚本钩子（§9.2）：x-* 忽略，其余拒绝。
        for (key in json.keys) {
            if (key in NODE_KEYS || key.startsWith(ESCAPE_PREFIX)) continue
            return ctx.fail("节点未知键 \"$key\"（路径未知即攻击面）")
        }

        val type = (json["type"] as? JsonPrimitive)?.contentOrNull
            ?: return ctx.fail("节点缺 type")
        if (type !in NODE_TYPES) {
            return ctx.fail("未知节点类型 \"$type\"（fail-closed，不降级占位）")
        }
        // id / visible 都可缺省（§4.4）——缺失不是失败，只有「出现但非法」才拒渲。
        val idRaw = json["id"]
        val nodeId = when {
            idRaw == null || idRaw is JsonNull -> null
            else -> parseNodeId(idRaw, ctx) ?: return null
        }
        val visibleRaw = json["visible"]
        val visible = when {
            visibleRaw == null || visibleRaw is JsonNull -> null
            else -> parseVisibility(visibleRaw, ctx) ?: return null
        }

        return when (type) {
            "column" -> parseColumn(json, depth, ctx, nodeId, visible)
            "row" -> parseRow(json, depth, ctx, nodeId, visible)
            "tabs" -> parseTabs(json, depth, ctx, nodeId, visible)
            "list" -> parseList(json, depth, ctx, nodeId, visible)
            "text" -> parseText(json, ctx, nodeId, visible)
            "component" -> parseComponent(json, depth, ctx, nodeId, visible)
            else -> null // 上面已拒
        }
    }

    private fun parseNodeId(raw: JsonElement?, ctx: ParseContext): String? {
        if (raw == null || raw is JsonNull) return null
        val id = (raw as? JsonPrimitive)?.contentOrNull ?: return ctx.fail("id 必须是字符串")
        if (!NODE_ID.matches(id)) return ctx.fail("id \"$id\" 不匹配 ^[a-z][a-z0-9_-]{0,31}$")
        if (!ctx.nodeIds.add(id)) return ctx.fail("id \"$id\" 页内重复")
        return id
    }

    private fun parseVisibility(raw: JsonElement?, ctx: ParseContext): PageVisibility? {
        if (raw == null || raw is JsonNull) return null
        return when {
            raw is JsonPrimitive && raw.booleanOrNull != null -> PageVisibility.Literal(raw.booleanOrNull!!)
            raw is JsonObject && raw.keys == setOf("bind") -> {
                val path = (raw["bind"] as? JsonPrimitive)?.contentOrNull
                    ?: return ctx.fail("visible.bind 必须是字符串路径")
                val binding = PageBinding.parse(path)
                    ?: return ctx.fail("visible 绑定路径 \"$path\" 非法（≤4 段，每段 [a-z0-9_]{1,32}）")
                PageVisibility.Bound(binding)
            }
            else -> ctx.fail("visible 只允许布尔或 {\"bind\": 路径}")
        }
    }

    private fun parseChildrenArray(json: JsonObject, ctx: ParseContext): List<JsonElement>? {
        val children = json["children"] ?: return emptyList()
        if (children !is JsonArray) {
            ctx.fail("children 必须是数组")
            return null
        }
        if (children.size > MAX_CHILDREN) {
            ctx.fail("单节点 children ${children.size} 超过上限 $MAX_CHILDREN")
            return null
        }
        return children
    }

    private fun parseColumn(json: JsonObject, depth: Int, ctx: ParseContext, nodeId: String?, visible: PageVisibility?): PageNode.Column? {
        val props = parsePropsObject(json, ctx) ?: return null
        if (!checkExtraPropKeys(props, setOf("gap"), "column", ctx)) return null
        val gap = parseGap(props["gap"], ctx) ?: return null
        val children = parseChildrenArray(json, ctx) ?: return null
        val parsed = parseChildNodes(children, depth, ctx) ?: return null
        return PageNode.Column(gap, parsed, nodeId, visible)
    }
    private fun parseRow(json: JsonObject, depth: Int, ctx: ParseContext, nodeId: String?, visible: PageVisibility?): PageNode.Row? {
        val props = parsePropsObject(json, ctx) ?: return null
        if (!checkExtraPropKeys(props, setOf("gap", "align", "wrap"), "row", ctx)) return null
        val gap = parseGap(props["gap"], ctx) ?: return null
        val align = when ((props["align"] as? JsonPrimitive)?.contentOrNull) {
            null, "start" -> RowAlign.Start
            "center" -> RowAlign.Center
            "end" -> RowAlign.End
            else -> return ctx.fail("row.align 只允许 start|center|end")
        }
        val wrap = parseBoolProp(props["wrap"], default = false) ?: return ctx.fail("row.wrap 必须是布尔")
        val children = parseChildrenArray(json, ctx) ?: return null
        val parsed = parseChildNodes(children, depth, ctx) ?: return null
        return PageNode.Row(gap, align, wrap, parsed, nodeId, visible)
    }

    private fun parseTabs(json: JsonObject, depth: Int, ctx: ParseContext, nodeId: String?, visible: PageVisibility?): PageNode.Tabs? {
        val props = parsePropsObject(json, ctx) ?: return null
        if (!checkExtraPropKeys(props, setOf("selected"), "tabs", ctx)) return null
        val selected = when (val raw = props["selected"]) {
            null -> null
            is JsonPrimitive ->
                if (raw.intOrNull != null) PropValue.Literal(raw.content)
                else return ctx.fail("tabs.selected 必须是 0-based 整数或 {\"bind\"} 绑定")
            else -> parsePropValue(raw, "tabs.selected", ctx) ?: return null
        }
        val children = parseChildrenArray(json, ctx) ?: return null
        if (children.isEmpty()) return ctx.fail("tabs 至少需要一个子页")
        val tabs = children.mapIndexed { index, element ->
            val child = element as? JsonObject ?: return ctx.fail("tabs 子节点必须是对象")
            val childProps = parsePropsObject(child, ctx) ?: return null
            val title = (childProps["tab_title"] as? JsonPrimitive)?.contentOrNull
                ?: return ctx.fail("tabs 第 $index 个子节点缺 props.tab_title")
            if (title.isEmpty()) return ctx.fail("tabs 第 $index 个子节点 tab_title 为空")
            // tab_title 是分栏容器的元数据，不是子节点自己的 props——剥掉再解析子节点；
            // 子节点其余 props 的封闭集校验由它自己的类型解析负责（column 校验 gap 等）。
            val strippedProps = JsonObject(childProps.toMap() - "tab_title")
            val strippedChild = JsonObject(child.toMutableMap().apply { put("props", strippedProps) })
            val parsedChild = parseNode(strippedChild, depth + 1, ctx) ?: return null
            TabPage(title, parsedChild)
        }
        return PageNode.Tabs(tabs, selected, nodeId, visible)
    }

    private fun parseList(json: JsonObject, depth: Int, ctx: ParseContext, nodeId: String?, visible: PageVisibility?): PageNode.ListBlock? {
        val props = parsePropsObject(json, ctx) ?: return null
        if (!checkExtraPropKeys(props, setOf("empty_text", "item_fields"), "list", ctx)) return null
        val dataRef = parseDataRef(json["data_ref"], ctx) ?: return null
        val emptyText = (props["empty_text"] as? JsonPrimitive)?.contentOrNull ?: "暂无数据"
        val itemFields = when (val raw = props["item_fields"]) {
            null -> null
            is JsonArray -> raw.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            else -> return ctx.fail("list.item_fields 必须是字符串数组")
        }
        val children = json["children"]
            ?: return ctx.fail("list 必须带恰好 1 个模板 child")
        if (children !is JsonArray || children.size != 1) {
            return ctx.fail("list 模板 child 必须 恰好 1 个（收到 ${(children as? JsonArray)?.size ?: "非数组"}）")
        }
        val template = parseNode(children[0] as? JsonObject ?: return ctx.fail("list 模板 child 必须是对象"), depth + 1, ctx)
            ?: return null
        return PageNode.ListBlock(dataRef, emptyText, itemFields, template, nodeId, visible)
    }

    private fun parseText(json: JsonObject, ctx: ParseContext, nodeId: String?, visible: PageVisibility?): PageNode.TextLeaf? {
        if (json.containsKey("children")) return ctx.fail("text 是叶节点，不允许 children（§4.4）")
        if (json.containsKey("on")) return ctx.fail("text 叶不允许 on 事件")
        val props = parsePropsObject(json, ctx) ?: return null
        if (!checkExtraPropKeys(props, setOf("style", "max_lines", "format", "text"), "text", ctx)) return null
        val style = when ((props["style"] as? JsonPrimitive)?.contentOrNull) {
            null -> PageTextStyle.Body
            "title" -> PageTextStyle.Title
            "subtitle" -> PageTextStyle.Subtitle
            "body" -> PageTextStyle.Body
            "caption" -> PageTextStyle.Caption
            "mono" -> PageTextStyle.Mono
            else -> return ctx.fail("text.style 只允许 title|subtitle|body|caption|mono")
        }
        val maxLines = when (val raw = props["max_lines"]) {
            null -> 1
            is JsonPrimitive -> raw.intOrNull?.takeIf { it in 1..8 }
                ?: return ctx.fail("text.max_lines 必须在 1..8")
            else -> return ctx.fail("text.max_lines 必须是整数")
        }
        val format = parseTextFormat((props["format"] as? JsonPrimitive)?.contentOrNull)
        val content = when {
            json.containsKey("data_ref") -> PropValue.Bound(parseDataRef(json["data_ref"], ctx) ?: return null)
            props.containsKey("text") -> parsePropValue(requireNotNull(props["text"]), "text.props.text", ctx) ?: return null
            else -> return ctx.fail("text 节点缺内容（data_ref 或 props.text 二选一）")
        }
        if (content is PropValue.Literal && content.value.length > MAX_TEXT_CHARS) {
            return ctx.fail("text 长度 ${content.value.length} 超过上限 $MAX_TEXT_CHARS")
        }
        return PageNode.TextLeaf(content, style, maxLines, format, nodeId, visible)
    }

    private fun parseComponent(json: JsonObject, depth: Int, ctx: ParseContext, nodeId: String?, visible: PageVisibility?): PageNode.ComponentNode? {
        val name = (json["component"] as? JsonPrimitive)?.contentOrNull
            ?: return ctx.fail("component 节点缺 component 名")

        // 宿主组件：整页拒渲并点名，不是忽略（§4.5——伪装成会话/输入区/审批/导航是钓鱼面）。
        HostComponent.fromWire(name)?.let {
            return ctx.fail("宿主组件 $name 出现在模块页面（MessageBubble/InputBar/ApprovalDialog/BottomBar 是宿主集合，出现即整页拒渲）")
        }
        val kind = ComponentKind.fromWire(name)
            ?: return ctx.fail("未知组件 \"$name\"（白名单外，fail-closed）")

        val props = parsePropsObject(json, ctx) ?: return null
        val dataRef = if (json.containsKey("data_ref")) parseDataRef(json["data_ref"], ctx) ?: return null else null
        val parsedProps = parseComponentProps(kind, props, ctx) ?: return null

        val events = parseEvents(kind, json["on"], ctx) ?: return null

        val children = parseChildrenArray(json, ctx) ?: return null
        if (children.isNotEmpty() && kind != ComponentKind.Card) {
            return ctx.fail("组件 $name 不允许 children（只有 Card 可以，§4.5）")
        }
        val parsedChildren = if (kind == ComponentKind.Card) {
            parseChildNodes(children, depth, ctx) ?: return null
        } else {
            emptyList()
        }
        return PageNode.ComponentNode(kind, parsedProps, events, parsedChildren, nodeId, visible, dataRef)
    }

    private fun parseChildNodes(children: List<JsonElement>, depth: Int, ctx: ParseContext): List<PageNode>? {
        val parsed = mutableListOf<PageNode>()
        for (element in children) {
            val child = element as? JsonObject ?: return ctx.fail("children 元素必须是对象")
            val node = parseNode(child, depth + 1, ctx) ?: return null
            parsed += node
        }
        return parsed
    }

    // --- props / 事件 / 动作 -----------------------------------------------------

    private fun parsePropsObject(json: JsonObject, ctx: ParseContext): JsonObject? {
        val props = json["props"] ?: return JsonObject(emptyMap())
        if (props !is JsonObject) {
            ctx.fail("props 必须是对象")
            return null
        }
        if (!scanScriptKeys(props, "props", ctx)) return null
        return props
    }

    /** 白名单键之外的 props 键 → 拒渲（§4.4 封闭集；x-* 忽略）。false = 已拒渲。 */
    private fun checkExtraPropKeys(props: JsonObject, allowed: Set<String>, what: String, ctx: ParseContext): Boolean {
        for (key in props.keys) {
            if (key in allowed || key.startsWith(ESCAPE_PREFIX)) continue
            ctx.fail("$what 的未知 props 键 \"$key\"")
            return false
        }
        return true
    }

    private fun parseBoolProp(raw: JsonElement?, default: Boolean): Boolean? = when (raw) {
        null -> default
        is JsonPrimitive -> raw.booleanOrNull
        else -> null
    }

    private fun parseGap(raw: JsonElement?, ctx: ParseContext): PageGap? = when ((raw as? JsonPrimitive)?.contentOrNull) {
        null, "none" -> PageGap.None
        "s" -> PageGap.S
        "m" -> PageGap.M
        "l" -> PageGap.L
        else -> ctx.fail("gap 只允许 none|s|m|l")
    }

    private fun parseDataRef(raw: JsonElement?, ctx: ParseContext): PageBinding? {
        val path = (raw as? JsonPrimitive)?.contentOrNull
            ?: return ctx.fail("data_ref 必须是字符串路径")
        val binding = PageBinding.parse(path)
            ?: return ctx.fail("data_ref \"$path\" 越界（≤4 段，每段 [a-z0-9_]{1,32}）")
        return binding
    }

    /** 字符串类 props 的取值：字面量字符串或 {"bind": 路径}。 */
    private fun parsePropValue(raw: JsonElement, what: String, ctx: ParseContext): PropValue? = when {
        raw is JsonNull -> null
        raw is JsonPrimitive && raw.isString -> PropValue.Literal(raw.content)
        // {"bind": 路径} 或 {"bind": 路径, "format": …}（§4.6 示例形态）。
        raw is JsonObject && raw.keys.minus("format") == setOf("bind") -> {
            val path = (raw["bind"] as? JsonPrimitive)?.contentOrNull
                ?: return ctx.fail("$what.bind 必须是字符串路径")
            val binding = PageBinding.parse(path)
                ?: return ctx.fail("$what 绑定路径 \"$path\" 非法")
            val formatRaw = (raw["format"] as? JsonPrimitive)?.contentOrNull
            PropValue.Bound(binding, parseTextFormat(formatRaw))
        }
        else -> ctx.fail("$what 只允许字符串字面量或 {\"bind\": 路径}")
    }

    /** `format` 只影响展示：未知值按 text 输出原值（§7.2 装饰性降级，不拒渲）。 */
    fun parseTextFormat(raw: String?): PageTextFormat = when (raw) {
        null, "text" -> PageTextFormat.Text
        "int" -> PageTextFormat.Int
        "decimal" -> PageTextFormat.Decimal
        "percent" -> PageTextFormat.Percent
        "bytes" -> PageTextFormat.Bytes
        "datetime" -> PageTextFormat.Datetime
        "relative" -> PageTextFormat.Relative
        else -> PageTextFormat.Text
    }

    private fun parseComponentProps(kind: ComponentKind, props: JsonObject, ctx: ParseContext): ComponentProps? {
        fun str(key: String): PropValue? = props[key]?.let { parsePropValue(it, "${kind.wireName}.$key", ctx) }
        fun bool(key: String): Boolean? = when (val raw = props[key]) {
            null -> null
            is JsonPrimitive -> raw.booleanOrNull ?: run {
                ctx.fail("${kind.wireName}.$key 必须是布尔")
                null
            }
            else -> {
                ctx.fail("${kind.wireName}.$key 必须是布尔")
                null
            }
        }
        fun enabled(): PageVisibility? = when (val raw = props["enabled"]) {
            null -> null
            is JsonPrimitive -> PageVisibility.Literal(raw.booleanOrNull ?: run {
                ctx.fail("${kind.wireName}.enabled 必须是布尔")
                return null
            })
            is JsonObject -> parseVisibility(raw, ctx) ?: return null
            else -> {
                ctx.fail("${kind.wireName}.enabled 必须是布尔")
                null
            }
        }

        return when (kind) {
            ComponentKind.Button -> {
                if (!checkExtraPropKeys(props, setOf("text", "style", "enabled"), "Button", ctx)) return null
                val style = when ((props["style"] as? JsonPrimitive)?.contentOrNull) {
                    null, "primary" -> BtnStyle.Primary
                    "secondary" -> BtnStyle.Secondary
                    "danger" -> BtnStyle.Danger
                    "text" -> BtnStyle.Text
                    else -> return ctx.fail("Button.style 只允许 primary|secondary|danger|text")
                }
                ComponentProps.Button(str("text"), style, enabled())
            }
            ComponentKind.TextField -> {
                if (!checkExtraPropKeys(
                    props,
                    setOf("label", "placeholder", "value", "multiline", "max_length"),
                    "TextField", ctx,
                )) return null
                val value = when (val raw = props["value"]) {
                    null -> null
                    is JsonObject -> {
                        val inputId = (raw["input"] as? JsonPrimitive)?.contentOrNull
                            ?: return ctx.fail("TextField.value 只接受 {\"input\": id}（§5.3，不做双向绑定）")
                        if (raw.keys != setOf("input")) {
                            return ctx.fail("TextField.value 只接受 {\"input\": id}（§5.3，不做双向绑定）")
                        }
                        InputRef(inputId)
                    }
                    else -> return ctx.fail("TextField.value 只接受 {\"input\": id}（§5.3，不做双向绑定）")
                }
                val maxLength = when (val raw = props["max_length"]) {
                    null -> 0 // 0 = 不限制
                    is JsonPrimitive -> raw.intOrNull?.takeIf { it in 1..MAX_TEXT_FIELD_LENGTH }
                        ?: return ctx.fail("TextField.max_length 必须 ≤$MAX_TEXT_FIELD_LENGTH")
                    else -> return ctx.fail("TextField.max_length 必须是整数")
                }
                ComponentProps.TextField(str("label"), str("placeholder"), value, bool("multiline") ?: false, maxLength)
            }
            ComponentKind.Card -> {
                if (!checkExtraPropKeys(props, setOf("title", "subtitle", "elevated"), "Card", ctx)) return null
                ComponentProps.Card(str("title"), str("subtitle"), bool("elevated") ?: false)
            }
            ComponentKind.ListItem -> {
                if (!checkExtraPropKeys(props, setOf("title", "subtitle", "leading_icon", "trailing_text"), "ListItem", ctx)) return null
                val icon = when (val raw = props["leading_icon"]) {
                    null -> null
                    is JsonPrimitive -> parsePageIcon(raw.contentOrNull) // 未知图标 → 默认回落（§7.2 装饰性降级）
                    else -> return ctx.fail("ListItem.leading_icon 必须是字符串")
                }
                ComponentProps.ListItem(str("title"), str("subtitle"), icon, str("trailing_text"))
            }
            ComponentKind.Switch -> {
                if (!checkExtraPropKeys(props, setOf("label", "enabled"), "Switch", ctx)) return null
                ComponentProps.Switch(str("label"), enabled())
            }
            ComponentKind.ToolCallCard -> {
                if (!checkExtraPropKeys(props, setOf("tool", "state", "args_preview", "result_summary"), "ToolCallCard", ctx)) return null
                val state = when ((props["state"] as? JsonPrimitive)?.contentOrNull) {
                    "running" -> ToolNodeState.Running
                    "ok" -> ToolNodeState.Ok
                    "failed" -> ToolNodeState.Failed
                    "waiting_approval" -> ToolNodeState.WaitingApproval
                    else -> return ctx.fail("ToolCallCard.state 只允许 running|ok|failed|waiting_approval")
                }
                val preview = str("args_preview")
                if (preview is PropValue.Literal && preview.value.length > MAX_ARGS_PREVIEW) {
                    return ctx.fail("args_preview 长度超过上限 $MAX_ARGS_PREVIEW")
                }
                ComponentProps.ToolCallCard(str("tool"), state, preview, str("result_summary"))
            }
            ComponentKind.AttachmentChip -> {
                if (!checkExtraPropKeys(props, setOf("file_id", "mime", "name", "state"), "AttachmentChip", ctx)) return null
                val state = when ((props["state"] as? JsonPrimitive)?.contentOrNull) {
                    "validating" -> ChipNodeState.Validating
                    "uploading" -> ChipNodeState.Uploading
                    "uploaded" -> ChipNodeState.Uploaded
                    "failed" -> ChipNodeState.Failed
                    else -> return ctx.fail("AttachmentChip.state 只允许 validating|uploading|uploaded|failed")
                }
                ComponentProps.AttachmentChip(str("file_id"), str("mime"), str("name"), state)
            }
        }
    }

    /** C-19 页面/行图标的字符串枚举；`none` → 无图标，未知值 → File 默认回落（§7.2）。 */
    fun parsePageIcon(raw: String?): PageIcon = when (raw) {
        null -> PageIcon.File // 缺省回落默认图标
        "list" -> PageIcon.List
        "chart" -> PageIcon.Chart
        "settings" -> PageIcon.Settings
        "tool" -> PageIcon.Tool
        "file" -> PageIcon.File
        "none" -> PageIcon.None
        else -> PageIcon.File // 未知值回落默认图标 + 一条告警（装饰性，不拒渲）
    }

    private fun parseEvents(kind: ComponentKind, raw: JsonElement?, ctx: ParseContext): Map<String, PageAction>? {
        if (raw == null || raw is JsonNull) return emptyMap()
        if (raw !is JsonObject) return ctx.fail("on 必须是对象")
        val allowed = EVENTS_BY_KIND.getValue(kind)
        val out = mutableMapOf<String, PageAction>()
        for ((event, actionJson) in raw) {
            if (event.startsWith(ESCAPE_PREFIX)) continue
            if (event !in allowed) return ctx.fail("组件 ${kind.wireName} 不允许事件 \"$event\"")
            if (out.size >= MAX_ACTIONS_PER_NODE) return ctx.fail("单节点动作超过上限 $MAX_ACTIONS_PER_NODE")
            val actionJson = actionJson as? JsonObject ?: return ctx.fail("on.$event 必须是动作对象")
            val action = parseAction(actionJson, ctx) ?: return null
            // Q4：AttachmentChip 只读，click 只能 navigate（不能上传、不能发 blob 请求）。
            if (kind == ComponentKind.AttachmentChip && action !is PageAction.Navigate) {
                return ctx.fail("AttachmentChip 的 click 只允许 navigate 动作（Q4 只读形态）")
            }
            ctx.actionCount += 1
            out[event] = action
        }
        return out
    }

    private fun parseAction(json: JsonObject, ctx: ParseContext): PageAction? {
        if (!scanScriptKeys(json, "action", ctx)) return null
        val kind = (json["kind"] as? JsonPrimitive)?.contentOrNull
            ?: return ctx.fail("动作缺 kind")
        return when (kind) {
            "navigate" -> {
                for (key in json.keys) {
                    if (key != "kind" && key != "to" && !key.startsWith(ESCAPE_PREFIX)) {
                        return ctx.fail("navigate 动作未知键 \"$key\"")
                    }
                }
                val to = (json["to"] as? JsonPrimitive)?.contentOrNull
                    ?: return ctx.fail("navigate 缺 to")
                val target = when {
                    to.startsWith("module:") -> {
                        val pageId = to.removePrefix("module:")
                        if (pageId !in ctx.declaredPageIds) {
                            return ctx.fail("navigate 到未声明页面 \"$pageId\"")
                        }
                        NavigateTarget.ModulePage(pageId)
                    }
                    to == "session:current" -> NavigateTarget.Session(null)
                    to.startsWith("session:") -> NavigateTarget.Session(to.removePrefix("session:"))
                    to.startsWith("settings:") -> {
                        val tab = to.removePrefix("settings:")
                        if (tab !in SETTINGS_TABS) {
                            return ctx.fail("navigate settings 目标 \"$tab\" 不在固定枚举内")
                        }
                        NavigateTarget.Settings(tab)
                    }
                    else -> return ctx.fail("未知 navigate 目标 \"$to\"")
                }
                PageAction.Navigate(target)
            }
            "tools/call" -> {
                for (key in json.keys) {
                    if (key !in setOf("kind", "tool", "args", "confirm_text") && !key.startsWith(ESCAPE_PREFIX)) {
                        return ctx.fail("tools/call 动作未知键 \"$key\"")
                    }
                }
                val tool = (json["tool"] as? JsonPrimitive)?.contentOrNull
                    ?: return ctx.fail("tools/call 缺 tool")
                val args = (json["args"] as? JsonObject) ?: JsonObject(emptyMap())
                if (!scanScriptKeys(args, "args", ctx)) return null
                for ((key, value) in args) {
                    if (!key.startsWith(ESCAPE_PREFIX) && !parseArgSource(value, "$key", ctx)) return null
                }
                val confirm = (json["confirm_text"] as? JsonPrimitive)?.contentOrNull
                PageAction.ToolCall(tool, args, confirm)
            }
            "ui/submit" -> {
                for (key in json.keys) {
                    if (key != "kind" && key != "content" && !key.startsWith(ESCAPE_PREFIX)) {
                        return ctx.fail("ui/submit 动作未知键 \"$key\"")
                    }
                }
                val content = (json["content"] as? JsonArray) ?: return ctx.fail("ui/submit 缺 content 数组")
                val parts = content.map { element ->
                    val part = element as? JsonObject ?: return ctx.fail("ui/submit part 必须是对象")
                    val type = (part["type"] as? JsonPrimitive)?.contentOrNull
                        ?: return ctx.fail("ui/submit part 缺 type")
                    if (type != "text") return ctx.fail("ui/submit part 只允许 type:\"text\"")
                    val textJson = part["text"] ?: return ctx.fail("ui/submit part 缺 text")
                    val text = when {
                        textJson is JsonPrimitive && textJson.isString -> SubmitText.Literal(textJson.content)
                        textJson is JsonObject && textJson.keys == setOf("bind") -> {
                            val path = (textJson["bind"] as? JsonPrimitive)?.contentOrNull
                                ?: return ctx.fail("ui/submit text.bind 必须是字符串路径")
                            SubmitText.Bound(PageBinding.parse(path) ?: return ctx.fail("ui/submit text 绑定路径非法"))
                        }
                        textJson is JsonObject && textJson.keys == setOf("input") -> {
                            SubmitText.InputRef((textJson["input"] as? JsonPrimitive)?.contentOrNull
                                ?: return ctx.fail("ui/submit text.input 必须是字符串"))
                        }
                        textJson is JsonObject && textJson.keys.contains("input") && textJson.keys.contains("as") &&
                            textJson.keys == setOf("input", "as") -> {
                            // 带 as 的 input 只用于数字参数（§5.3）；submit part 一律字符串。
                            val asType = (textJson["as"] as? JsonPrimitive)?.contentOrNull
                            if (asType != "string") return ctx.fail("ui/submit text input 只允许字符串（as:string）")
                            SubmitText.InputRef((textJson["input"] as? JsonPrimitive)?.contentOrNull
                                ?: return ctx.fail("ui/submit text.input 必须是字符串"))
                        }
                        else -> return ctx.fail("ui/submit text 只允许字面量 / bind / input")
                    }
                    SubmitPart(text)
                }
                PageAction.UiSubmit(parts)
            }
            else -> ctx.fail("未知动作 kind \"$kind\"（http.request / shell / state.set 一律拒渲）")
        }
    }

    /**
     * 动作参数来源（§6.4）三种之一：字面量（string/number/bool/null 及其数组）、
     * {"bind": 路径}、{"input": id, "as"?: int|string}。其余来源键（{"eval":…}、
     * {"bind": "$env"}、嵌套对象）→ 拒渲。false = 形态非法、已记入拒渲理由。
     */
    private fun parseArgSource(raw: JsonElement, what: String, ctx: ParseContext): Boolean {
        return when {
            raw is JsonNull -> true
            raw is JsonPrimitive -> {
                val ok = raw.isString || raw.booleanOrNull != null || raw.contentOrNull?.toDoubleOrNull() != null
                if (!ok) ctx.fail("动作参数 $what 类型不支持")
                ok
            }
            raw is JsonArray -> {
                for (element in raw) {
                    if (!parseArgSource(element, "$what[]", ctx)) return false
                }
                true
            }
            raw is JsonObject && raw.keys == setOf("bind") -> {
                val path = (raw["bind"] as? JsonPrimitive)?.contentOrNull
                if (path == null) {
                    ctx.fail("动作参数 $what.bind 必须是字符串路径")
                    return false
                }
                if (PageBinding.parse(path) == null) {
                    ctx.fail("动作参数 $what 绑定路径 \"$path\" 非法")
                    return false
                }
                true
            }
            raw is JsonObject && (raw.keys == setOf("input") || raw.keys == setOf("input", "as")) -> {
                val inputId = (raw["input"] as? JsonPrimitive)?.contentOrNull
                if (inputId == null || inputId.isEmpty()) {
                    ctx.fail("动作参数 $what.input 必须是非空字符串")
                    return false
                }
                val asType = (raw["as"] as? JsonPrimitive)?.contentOrNull ?: "string"
                if (asType !in setOf("string", "int")) {
                    ctx.fail("动作参数 $what.as 只允许 string|int")
                    return false
                }
                true
            }
            else -> {
                ctx.fail("动作参数 $what 不是合法来源（只允许字面量 / bind / input，§6.4）")
                false
            }
        }
    }

    // --- 脚本键扫描（任意层级，大小写不敏感） -------------------------------------

    /** 任意层级脚本键扫描（§7.2，大小写不敏感）。false = 已拒渲（整页）。 */
    private fun scanScriptKeys(json: JsonObject, path: String, ctx: ParseContext): Boolean {
        for ((key, value) in json) {
            if (key.startsWith(ESCAPE_PREFIX)) continue
            val lower = key.lowercase()
            if (lower in SCRIPT_KEYS) {
                ctx.fail("脚本键 \"$key\" 出现于 $path（任意层级命中即整页拒渲，无代码执行）")
                return false
            }
            if (lower == "template" && value is JsonPrimitive && value.contentOrNull?.contains("${'$'}{") == true) {
                ctx.fail("template 含 ${'$'}{} 模板插值于 $path（模板引擎 = 注入面）")
                return false
            }
        }
        return true
    }
}

/** 解析结果：Ok 携带类型化树与统计；Rejected 携带定位（错误卡最多展示 3 条）。 */
sealed interface ParseResult {
    data class Ok(val root: PageNode, val nodeCount: Int, val actionCount: Int) : ParseResult
    data class Rejected(val reasons: List<String>) : ParseResult
}
