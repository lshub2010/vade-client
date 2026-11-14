package vade.ui.module

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import vade.ui.skin.BtnStyle

/**
 * 渲染中间层：把解析期 [PageNode] 树 + [PageEnv] 解析成 [ResolvedNode] —— props 全部
 * 落成具体字符串、动作就绪、`visible` 已裁剪、`item.*` 作用域已展开。渲染器
 * (`ModulePageRenderer`) 只做 ResolvedNode → Compose 的机械映射，不再做任何决策；
 * 这也让「绑定缺失不崩」「离线禁用动作」「未知枚举回落」可以在桌面测试里直接断言，
 * 不需要 Compose 测试装置。
 */
sealed interface ResolvedNode {
    data class Column(val gap: PageGap, val children: List<ResolvedNode>) : ResolvedNode
    data class Row(val gap: PageGap, val align: RowAlign, val wrap: Boolean, val children: List<ResolvedNode>) : ResolvedNode
    data class Tabs(val tabs: List<ResolvedTab>, val selectedIndex: Int) : ResolvedNode
    data class ListBlock(
        val rows: List<ResolvedNode>,
        val total: Int,
        val shown: Int,
        val emptyText: String,
    ) : ResolvedNode

    data class TextLeaf(
        val text: String,
        val style: PageTextStyle,
        val maxLines: Int,
        val format: PageTextFormat,
    ) : ResolvedNode

    data class Component(val kind: ComponentKind, val payload: ResolvedComponent) : ResolvedNode
}

data class ResolvedTab(val title: String, val child: ResolvedNode)

sealed interface ResolvedComponent {
    data class Button(val text: String, val style: BtnStyle, val enabled: Boolean, val action: PageAction?) : ResolvedComponent

    data class TextField(
        val label: String?,
        val placeholder: String?,
        val inputId: String?,
        val value: String,
        val multiline: Boolean,
        val maxLength: Int,
        val enabled: Boolean,
    ) : ResolvedComponent

    data class Card(
        val title: String?,
        val subtitle: String?,
        val elevated: Boolean,
        val children: List<ResolvedNode>,
        val action: PageAction?,
    ) : ResolvedComponent

    data class ListItem(
        val title: String,
        val subtitle: String?,
        val leadingIcon: PageIcon?,
        val trailingText: String?,
        val action: PageAction?,
    ) : ResolvedComponent

    data class Switch(
        val label: String?,
        val checked: Boolean,
        val enabled: Boolean,
        val action: PageAction?,
    ) : ResolvedComponent

    data class ToolCallCard(
        val tool: String,
        val state: ToolNodeState,
        val argsPreview: String,
        val resultSummary: String?,
        val action: PageAction?,
    ) : ResolvedComponent

    data class AttachmentChip(
        val fileId: String,
        val mime: String,
        val name: String,
        val state: ChipNodeState,
        val action: PageAction?,
    ) : ResolvedComponent
}

/**
 * 递归解析。`visible` 为假 → 返回 null（该节点整体消失，§5.2 只做真值判定）。
 * 绑定缺失 → 空串/默认值，**不崩**（验收 ⑧）；越界根 → 一次性提示。
 */
object ModulePageResolver {

    fun resolve(
        node: PageNode,
        env: PageEnv,
        report: PageResolveReport,
        scope: ListScope? = null,
    ): ResolvedNode? {
        if (!visibilityAllows(node.visible, env, report, scope)) return null
        return when (node) {
            is PageNode.Column -> ResolvedNode.Column(
                gap = node.gap,
                children = resolveChildren(node.children, env, report, scope),
            )
            is PageNode.Row -> ResolvedNode.Row(
                gap = node.gap,
                align = node.align,
                wrap = node.wrap,
                children = resolveChildren(node.children, env, report, scope),
            )
            is PageNode.Tabs -> {
                val tabs = node.tabs.mapNotNull { tab ->
                    resolve(tab.child, env, report, scope)?.let { ResolvedTab(tab.title, it) }
                }
                if (tabs.isEmpty()) return null
                ResolvedNode.Tabs(tabs, resolveSelected(node.selected, tabs.size, env, report, scope))
            }
            is PageNode.ListBlock -> resolveList(node, env, report, scope)
            is PageNode.TextLeaf -> ResolvedNode.TextLeaf(
                text = resolveTextContent(node.content, env, report, scope),
                style = node.style,
                maxLines = node.maxLines,
                format = node.format,
            )
            is PageNode.ComponentNode -> resolveComponent(node, env, report, scope)
        }
    }

    private fun resolveChildren(
        children: List<PageNode>,
        env: PageEnv,
        report: PageResolveReport,
        scope: ListScope?,
    ): List<ResolvedNode> = children.mapNotNull { resolve(it, env, report, scope) }

    private fun visibilityAllows(
        visible: PageVisibility?,
        env: PageEnv,
        report: PageResolveReport,
        scope: ListScope?,
    ): Boolean = when (visible) {
        null -> true
        is PageVisibility.Literal -> visible.value
        is PageVisibility.Bound -> isTruthy(resolveBound(visible.binding, env, report, scope))
    }

    /** `list`：data_ref 数组逐项展开模板（≤512 项，窗口 ≤50 项/屏，Q3）。 */
    private fun resolveList(node: PageNode.ListBlock, env: PageEnv, report: PageResolveReport, scope: ListScope?): ResolvedNode.ListBlock {
        val value = resolveBound(node.dataRef, env, report, scope)
        val items = (value as? JsonArray)
        val capped = items?.take(ModulePageParser.MAX_LIST_ITEMS) ?: emptyList()
        if (items != null && items.size > ModulePageParser.MAX_LIST_ITEMS) {
            report.notice("列表数据 ${items.size} 项超过 ${ModulePageParser.MAX_LIST_ITEMS} 上限，已按上限处理")
        }
        val fields = node.itemFields?.toSet()
        val rows = capped
            .take(ModulePageParser.LIST_WINDOW)
            .mapNotNull { element ->
                val item = element as? JsonObject
                if (item == null) {
                    report.notice("列表项不是对象，已跳过")
                }
                item?.let { resolve(node.template, env, report, ListScope(it, fields)) }
            }
        return ResolvedNode.ListBlock(
            rows = rows,
            total = capped.size,
            shown = rows.size,
            emptyText = node.emptyText,
        )
    }

    private fun resolveSelected(
        selected: PropValue?,
        tabCount: Int,
        env: PageEnv,
        report: PageResolveReport,
        scope: ListScope?,
    ): Int {
        val raw = when (selected) {
            null -> return 0
            is PropValue.Literal -> selected.value
            is PropValue.Bound -> (resolveBound(selected.binding, env, report, scope) as? JsonPrimitive)?.contentOrNull
                ?: return 0
        }
        val index = raw.toIntOrNull() ?: return 0
        if (index !in 0 until tabCount) {
            report.notice("tabs.selected $index 越界，回落第一个分栏")
            return 0
        }
        return index
    }

    private fun resolveTextContent(
        content: PropValue?,
        env: PageEnv,
        report: PageResolveReport,
        scope: ListScope?,
    ): String = when (content) {
        null -> ""
        is PropValue.Literal -> content.value
        is PropValue.Bound -> {
            val value = resolveBound(content.binding, env, report, scope)
            PageFormatter.format(value, content.format)
        }
    }

    // --- 组件 -------------------------------------------------------------------

    private fun resolveComponent(node: PageNode.ComponentNode, env: PageEnv, report: PageResolveReport, scope: ListScope?): ResolvedNode.Component {
        val p = node.props
        val payload: ResolvedComponent = when (p) {
            is ComponentProps.Button -> ResolvedComponent.Button(
                text = strProp(p.text, env, report, scope),
                style = p.style,
                // 离线 → 动作禁用（§5.4）；fold 进 resolved 模型，渲染层不再决策。
                enabled = boolVisibility(p.enabled, env, report, scope) && env.moduleOnline,
                action = node.events["click"],
            )
            is ComponentProps.TextField -> ResolvedComponent.TextField(
                label = strPropOrNull(p.label, env, report, scope),
                placeholder = strPropOrNull(p.placeholder, env, report, scope),
                inputId = p.value?.inputId,
                value = p.value?.let { env.inputs[it.inputId] } ?: "",
                multiline = p.multiline,
                maxLength = p.maxLength,
                enabled = true, // 输入不是动作：不受离线禁用（§5.4 只禁动作）
            )
            is ComponentProps.Card -> ResolvedComponent.Card(
                title = strPropOrNull(p.title, env, report, scope),
                subtitle = strPropOrNull(p.subtitle, env, report, scope),
                elevated = p.elevated,
                children = resolveChildren(node.children, env, report, scope),
                action = node.events["click"],
            )
            is ComponentProps.ListItem -> ResolvedComponent.ListItem(
                title = strProp(p.title, env, report, scope),
                subtitle = strPropOrNull(p.subtitle, env, report, scope),
                leadingIcon = p.leadingIcon,
                trailingText = strPropOrNull(p.trailingText, env, report, scope),
                action = node.events["click"],
            )
            is ComponentProps.Switch -> ResolvedComponent.Switch(
                label = strPropOrNull(p.label, env, report, scope),
                // 宿主不本地翻转：checked 只来自绑定，翻转只发动作（§4.5 硬约束）。
                checked = node.dataRef?.let { isTruthy(resolveBound(it, env, report, scope)) } ?: false,
                enabled = boolVisibility(p.enabled, env, report, scope) && env.moduleOnline,
                action = node.events["change"],
            )
            is ComponentProps.ToolCallCard -> ResolvedComponent.ToolCallCard(
                tool = strProp(p.tool, env, report, scope),
                state = p.state,
                argsPreview = strProp(p.argsPreview, env, report, scope),
                resultSummary = strPropOrNull(p.resultSummary, env, report, scope),
                action = node.events["click"],
            )
            is ComponentProps.AttachmentChip -> ResolvedComponent.AttachmentChip(
                fileId = strProp(p.fileId, env, report, scope),
                mime = strProp(p.mime, env, report, scope),
                name = strProp(p.name, env, report, scope),
                state = p.state,
                action = node.events["click"],
            )
        }
        return ResolvedNode.Component(node.kind, payload)
    }

    /** 必有主值/文案的 props：缺失落空串，不崩。 */
    private fun strProp(prop: PropValue?, env: PageEnv, report: PageResolveReport, scope: ListScope?): String =
        strPropOrNull(prop, env, report, scope) ?: ""

    private fun strPropOrNull(prop: PropValue?, env: PageEnv, report: PageResolveReport, scope: ListScope?): String? = when (prop) {
        null -> null
        is PropValue.Literal -> prop.value
        is PropValue.Bound -> PageFormatter.format(resolveBound(prop.binding, env, report, scope), prop.format)
            .ifEmpty { null }
    }

    private fun boolVisibility(v: PageVisibility?, env: PageEnv, report: PageResolveReport, scope: ListScope?): Boolean =
        when (v) {
            null -> true
            is PageVisibility.Literal -> v.value
            is PageVisibility.Bound -> isTruthy(resolveBound(v.binding, env, report, scope))
        }

    /**
     * 绑定取值的统一入口。`item.*` 白名单（`item_fields`）越界 → 按缺失处理 + 提示
     * （§4.6 / §5.4）。
     */
    private fun resolveBound(
        binding: PageBinding,
        env: PageEnv,
        report: PageResolveReport,
        scope: ListScope?,
    ): JsonElement? {
        if (scope != null && binding.rootKind() == BindRoot.Item) {
            val field = binding.segments.getOrNull(1)
            if (field != null && scope.fields != null && field !in scope.fields) {
                report.notice("列表项字段 \"$field\" 不在 item_fields 白名单内，按缺失处理")
                return null
            }
        }
        return resolveBinding(binding, env, scope?.item, report)
    }

    // --- 动作参数（§6.4） --------------------------------------------------------

    /**
     * 解析动作 args 为要发送的字面量 JSON：字面量原样；bind → 解析（缺失 → 空串，
     * core 按 input_schema 报 INVALID_ARGUMENT）；input → 字符串或严格整数（失败返回
     * null = 动作不发出 + 就地提示）。嵌套对象在解析期已拒，这里只见到合法形态。
     */
    fun resolveArgs(args: JsonObject, env: PageEnv, report: PageResolveReport): JsonObject? {
        val out = buildJsonObject {
            for ((key, raw) in args) {
                when {
                    raw is JsonObject && raw.keys.contains("input") -> {
                        val ref = InputRef((raw["input"] as? JsonPrimitive)?.contentOrNull ?: return@buildJsonObject)
                        val asInt = (raw["as"] as? JsonPrimitive)?.contentOrNull == "int"
                        val value = resolveInput(ref, asInt, env, report) ?: return null
                        put(key, value)
                    }
                    raw is JsonObject && raw.keys.contains("bind") -> {
                        val path = (raw["bind"] as? JsonPrimitive)?.contentOrNull ?: ""
                        val binding = PageBinding.parse(path)
                        val value = binding?.let { resolveBinding(it, env, null, report) }
                        // 解析不到 → 空串（§6.4），core 复校。
                        put(key, JsonPrimitive(PageFormatter.format(value, PageTextFormat.Text)))
                    }
                    raw is JsonArray -> put(key, resolveArgArray(raw, env, report) ?: return null)
                    else -> put(key, raw)
                }
            }
        }
        return out
    }

    private fun resolveArgArray(array: JsonArray, env: PageEnv, report: PageResolveReport): JsonArray? {
        val out = mutableListOf<JsonElement>()
        for (raw in array) {
            when {
                raw is JsonObject && raw.keys.contains("input") -> {
                    val ref = InputRef((raw["input"] as? JsonPrimitive)?.contentOrNull ?: return null)
                    val asInt = (raw["as"] as? JsonPrimitive)?.contentOrNull == "int"
                    out += resolveInput(ref, asInt, env, report) ?: return null
                }
                raw is JsonObject && raw.keys.contains("bind") -> {
                    val path = (raw["bind"] as? JsonPrimitive)?.contentOrNull ?: return null
                    val binding = PageBinding.parse(path) ?: return null
                    val value = resolveBinding(binding, env, null, report)
                    out += JsonPrimitive(PageFormatter.format(value, PageTextFormat.Text))
                }
                else -> out += raw
            }
        }
        return JsonArray(out)
    }

    /** `ui/submit` 的 text parts → 具体字符串列表（字面量 / bind / $input）。 */
    fun resolveSubmitTexts(parts: List<SubmitPart>, env: PageEnv, report: PageResolveReport): List<String> =
        parts.map { part ->
            when (val text = part.text) {
                is SubmitText.Literal -> text.value
                is SubmitText.Bound -> PageFormatter.format(
                    resolveBinding(text.binding, env, null, report),
                    PageTextFormat.Text,
                )
                is SubmitText.InputRef -> env.inputs[text.inputId] ?: ""
            }
        }

    /**
     * 动作是否可发（§5.4 / §6.3）：模块离线 → 全部禁用；`ui/submit` 还要求活跃会话
     * （无会话 → 动作禁用并提示，不静默丢弃）。
     */
    fun actionEnabled(action: PageAction, env: PageEnv): Boolean {
        if (!env.moduleOnline) return false
        return when (action) {
            is PageAction.UiSubmit -> env.hasSession
            else -> true
        }
    }
}

/** `item.*` 作用域：列表模板解析期间的当前项与字段白名单。 */
data class ListScope(val item: JsonObject, val fields: Set<String>?)

/** 帮助测试与宿主把选中页重新声明集拿出来（navigate 校验用）。 */
fun declaredPageIds(source: ModulePageSource, moduleId: String): Set<String> =
    source.declarations()
        .filter { it.moduleId == moduleId }
        .flatMap { module -> module.pages.map { page -> page.pageId } }
        .toSet()
