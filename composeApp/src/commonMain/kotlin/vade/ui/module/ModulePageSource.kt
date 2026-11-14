package vade.ui.module

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * 模块页面的**窄数据源接口**（本轮的接缝）。宿主（`ModulePageHost`）与入口列表
 * （`ModulePageEntryList`）只认识这个接口——**接真数据只需实现该接口**：
 * `declarations()` 来自 `module.register` 快照里的 `manifest.ui.pages[]`（module.status
 * 事件维护 online），`loadPage` 对应 `ui/page.get` 的 `result.schema`，`loadState`
 * 对应 `ui/state.get` 的 `result.state`。协议层（w1-proto 的 `VadeClient`）已有
 * `page.get` / `state.get` 方法，由下一棒包一层此接口即可，本包不用改。
 *
 * 接口是同步值语义：真实实现若要走 ws 往返，在实现内部缓存/挂起即可（或加一层
 * suspend 包装），调用方不关心。离线时 `loadPage` 返回 null → 宿主显示卡片，
 * 不白屏（§7.4）。
 */
interface ModulePageSource {
    /** 模块与页面声明快照（manifest 顺序）；未声明 `ui.pages` 的模块 pages 为空。 */
    fun declarations(): List<ModuleDeclaration>

    /** 模块在线状态（module.status 事件）；宿主据此显示「模块离线」并禁用动作。 */
    fun status(moduleId: String): ModuleStatus

    /** 页面体节点树（`ui/page.get` → `result.schema`）；不可用（NOT_FOUND 等）→ null。 */
    fun loadPage(moduleId: String, pageId: String): JsonObject?

    /** 页面状态（`ui/state.get` → `result.state`）；取不到 → null（页顶 banner + 重试）。 */
    fun loadState(moduleId: String, instanceId: String, pageId: String): JsonObject?
}

data class ModuleStatus(val online: Boolean, val version: String?)

data class ModuleDeclaration(
    val moduleId: String,
    val instanceId: String,
    val moduleTitle: String,
    val online: Boolean,
    val moduleVersion: String?,
    val pages: List<PageDeclaration>,
)

data class PageDeclaration(
    val pageId: String,
    val title: String,
    val icon: PageIcon,
    val version: Int,
    val requiresSession: Boolean,
)

/** 一个入口 = 模块 + 页面（FR-8.79 / §8.1：能力页每项 = 模块显示名 › 页面标题）。 */
data class ModulePageEntry(
    val moduleId: String,
    val instanceId: String,
    val moduleTitle: String,
    val pageId: String,
    val pageTitle: String,
    val icon: PageIcon,
    val version: Int,
    val requiresSession: Boolean,
)

/** 版本高于客户端支持集的页面：入口不出现，模块侧一行说明（§8.2 / A13）。 */
data class HiddenPageNote(val moduleTitle: String, val pageTitle: String, val version: Int)

/**
 * 入口集合 = 有受支持页面的模块分组 + 版本门控隐藏说明。`groups` 为空且 `hidden`
 * 为空时，能力页**整个「模块页面」区块都不渲染**——不出现空 tab、空列表、灰掉的
 * 占位行（FR-8.79 判据，§8.2）。
 */
data class ModuleEntryIndex(
    val groups: List<ModuleEntryGroup>,
    val hidden: List<HiddenPageNote>,
) {
    val isEmpty: Boolean get() = groups.isEmpty() && hidden.isEmpty()
}

data class ModuleEntryGroup(
    val moduleId: String,
    val moduleTitle: String,
    val entries: List<ModulePageEntry>,
)

/**
 * 入口规则（§8.1/§8.2），从声明快照推出 UI 集合：
 *  - `ui.pages` 缺失或为空 → 该模块**没有**分组、没有行（不是占位）；
 *  - `version` 高于 [ModulePageParser.SUPPORTED_PAGE_VERSION] → 入口不出现 + 隐藏说明；
 *  - 其余按 manifest 顺序展开。
 */
object ModulePageEntryRules {
    fun build(declarations: List<ModuleDeclaration>): ModuleEntryIndex {
        val groups = mutableListOf<ModuleEntryGroup>()
        val hidden = mutableListOf<HiddenPageNote>()
        for (module in declarations) {
            val supported = module.pages.filter { it.version <= ModulePageParser.SUPPORTED_PAGE_VERSION }
            hidden += module.pages.filter { it.version > ModulePageParser.SUPPORTED_PAGE_VERSION }
                .map { HiddenPageNote(module.moduleTitle, it.title, it.version) }
            if (supported.isEmpty()) continue // FR-8.79：无声明 → 无入口，不是空列表
            groups += ModuleEntryGroup(
                moduleId = module.moduleId,
                moduleTitle = module.moduleTitle,
                entries = supported.map {
                    ModulePageEntry(
                        moduleId = module.moduleId,
                        instanceId = module.instanceId,
                        moduleTitle = module.moduleTitle,
                        pageId = it.pageId,
                        pageTitle = it.title,
                        icon = it.icon,
                        version = it.version,
                        requiresSession = it.requiresSession,
                    )
                },
            )
        }
        return ModuleEntryIndex(groups, hidden)
    }
}

/**
 * 内存示例树驱动（本轮）。三端展示与测试共用同一份夹具：
 *  - `download` 下载管理：2 页（队列 = column/list/text/button + 绑定；设置 = Card/Switch/TextField + ui/submit，requires session）；
 *  - `charts` 图表：2 页（总览 = tabs/ToolCallCard + navigate 到详情；详情 = AttachmentChip/ListItem）；
 *  - `notes` 笔记：**未声明 ui.pages** → 无入口（FR-8.79 测试夹具）；
 *  - `legacy` 旧版组件：页面 version=2 → 入口不出现 + 一行说明（A13 测试夹具）。
 */
object ModulePageSources {
    private val JSON = Json

    /** 给测试与 UI 复用的声明快照（demo 树与 declarations 永远成对）。 */
    fun demoDeclarations(): List<ModuleDeclaration> = listOf(
        ModuleDeclaration(
            moduleId = "download", instanceId = "dl-1", moduleTitle = "下载管理",
            online = true, moduleVersion = "0.3.1",
            pages = listOf(
                PageDeclaration("queue", "下载队列", PageIcon.List, version = 1, requiresSession = false),
                PageDeclaration("settings", "下载设置", PageIcon.Settings, version = 1, requiresSession = true),
            ),
        ),
        ModuleDeclaration(
            moduleId = "charts", instanceId = "ch-1", moduleTitle = "图表",
            online = true, moduleVersion = "0.2.0",
            pages = listOf(
                PageDeclaration("overview", "图表总览", PageIcon.Chart, version = 1, requiresSession = false),
                PageDeclaration("detail", "图片明细", PageIcon.Tool, version = 1, requiresSession = false),
            ),
        ),
        ModuleDeclaration(
            moduleId = "notes", instanceId = "no-1", moduleTitle = "笔记",
            online = true, moduleVersion = "0.1.0",
            pages = emptyList(), // 未声明 ui.pages：FR-8.79 → 不显示入口
        ),
        ModuleDeclaration(
            moduleId = "legacy", instanceId = "lg-1", moduleTitle = "旧版组件",
            online = true, moduleVersion = "0.9.0",
            pages = listOf(
                PageDeclaration("beta", "Beta 面板", PageIcon.File, version = 2, requiresSession = false),
            ),
        ),
    )

    fun demo(): ModulePageSource = DemoSource

    private object DemoSource : ModulePageSource {
        private val declarations = demoDeclarations()
        private val byId = declarations.associateBy { it.moduleId }

        override fun declarations(): List<ModuleDeclaration> = declarations

        override fun status(moduleId: String): ModuleStatus {
            val module = byId[moduleId] ?: return ModuleStatus(online = false, version = null)
            return ModuleStatus(module.online, module.moduleVersion)
        }

        override fun loadPage(moduleId: String, pageId: String): JsonObject? = pages["$moduleId/$pageId"]

        override fun loadState(moduleId: String, instanceId: String, pageId: String): JsonObject? =
            states["$moduleId/$pageId"]

        private val pages: Map<String, JsonObject> = mapOf(
            "download/queue" to JSON.parseToJsonElement("""{
                "type": "column",
                "props": {"gap": "m"},
                "children": [
                    {"type": "text", "data_ref": "page.state.queue_len",
                     "props": {"style": "title", "format": "int"}},
                    {"type": "text", "data_ref": "page.state.summary", "props": {"style": "caption"}},
                    {"type": "text", "props": {"text": "任务明细"}, "visible": {"bind": "module.status.online"}},
                    {"type": "list", "data_ref": "page.state.items",
                     "props": {"empty_text": "队列为空"},
                     "children": [
                         {"type": "component", "component": "ListItem",
                          "props": {"title": {"bind": "item.name"},
                                    "subtitle": {"bind": "item.progress", "format": "percent"},
                                    "trailing_text": {"bind": "item.size", "format": "bytes"}}}
                     ]},
                    {"type": "component", "component": "Button",
                     "props": {"text": "刷新", "style": "primary"},
                     "on": {"click": {"kind": "tools/call", "tool": "download.refresh", "args": {}}}}
                ]
            }""") as JsonObject,
            "download/settings" to JSON.parseToJsonElement("""{
                "type": "column",
                "props": {"gap": "m"},
                "children": [
                    {"type": "component", "component": "Card",
                     "props": {"title": "并发", "subtitle": "同时下载的任务数"},
                     "children": [
                         {"type": "component", "component": "Switch",
                          "data_ref": "page.state.auto_start",
                          "props": {"label": "新增任务自动开始"},
                          "on": {"change": {"kind": "tools/call", "tool": "download.set_auto_start",
                                            "args": {"value": true}}}},
                         {"type": "component", "component": "TextField",
                          "props": {"label": "同时下载上限", "value": {"input": "limit"}, "max_length": 2}},
                         {"type": "component", "component": "Button",
                          "props": {"text": "保存", "style": "primary"},
                          "on": {"click": {"kind": "ui/submit",
                                           "content": [{"type": "text", "text": "（来自模块页面：下载设置）"},
                                                       {"type": "text", "text": {"input": "limit"}}]}}}
                     ]},
                    {"type": "row", "props": {"gap": "s", "align": "center"},
                     "children": [
                         {"type": "text", "props": {"text": "限速"}},
                         {"type": "text", "data_ref": "page.state.speed", "props": {"style": "body"}}
                     ]}
                ]
            }""") as JsonObject,
            "charts/overview" to JSON.parseToJsonElement("""{
                "type": "tabs",
                "props": {"selected": 0},
                "children": [
                    {"type": "column", "props": {"gap": "m", "tab_title": "趋势"},
                     "children": [
                         {"type": "text", "data_ref": "page.state.total", "props": {"style": "title", "format": "int"}},
                         {"type": "component", "component": "ToolCallCard",
                          "props": {"tool": "charts.query", "state": "ok",
                                    "args_preview": "range=7d", "result_summary": "返回 168 个点"}},
                         {"type": "row", "props": {"gap": "s"},
                          "children": [
                              {"type": "component", "component": "Button",
                               "props": {"text": "看明细", "style": "secondary"},
                               "on": {"click": {"kind": "navigate", "to": "module:detail"}}}
                          ]}
                     ]},
                    {"type": "list", "props": {"tab_title": "明细", "empty_text": "暂无明细"},
                     "data_ref": "page.state.rows",
                     "children": [
                         {"type": "component", "component": "ListItem",
                          "props": {"title": {"bind": "item.day"},
                                    "subtitle": {"bind": "item.count", "format": "int"}}}
                     ]}
                ]
            }""") as JsonObject,
            "charts/detail" to JSON.parseToJsonElement("""{
                "type": "column",
                "props": {"gap": "m"},
                "children": [
                    {"type": "component", "component": "AttachmentChip",
                     "props": {"file_id": "blb_demo_chart", "mime": "image/png",
                               "name": "导出图.png", "state": "uploaded"},
                     "on": {"click": {"kind": "navigate", "to": "module:overview"}}},
                    {"type": "component", "component": "ListItem",
                     "props": {"title": "返回总览", "leading_icon": "chart"},
                     "on": {"click": {"kind": "navigate", "to": "module:overview"}}}
                ]
            }""") as JsonObject,
        )

        private val states: Map<String, JsonObject> = mapOf(
            "download/queue" to JSON.parseToJsonElement("""{
                "queue_len": 3,
                "summary": "2 个进行中，1 个排队",
                "items": [
                    {"name": "模型 A 权重", "progress": 0.42, "size": 734003200},
                    {"name": "数据集 winter", "progress": 0.85, "size": 10485760},
                    {"name": "文档包", "progress": 0.05, "size": 20480}
                ]
            }""") as JsonObject,
            "download/settings" to JSON.parseToJsonElement("""{
                "auto_start": true,
                "speed": "8.2 MB/s"
            }""") as JsonObject,
            "charts/overview" to JSON.parseToJsonElement("""{
                "total": 168,
                "rows": [
                    {"day": "周一", "count": 31},
                    {"day": "周二", "count": 27},
                    {"day": "周三", "count": 24}
                ]
            }""") as JsonObject,
            "charts/detail" to JSON.parseToJsonElement("{}") as JsonObject,
        )
    }
}
