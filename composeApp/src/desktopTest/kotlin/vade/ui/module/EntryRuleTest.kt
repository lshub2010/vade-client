package vade.ui.module

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 入口规则（FR-8.79 / §8.2 / A5 / A13）：声明了 ui.pages 的模块才有入口；未声明的模块
 * **无入口**（不是空行不是占位）；version 高于客户端支持集 → 入口不出现 + 一行说明；
 * demo 夹具自身的行为也钉住（能力页区块就是它驱动的）。
 */
class EntryRuleTest {

    private fun declaration(
        moduleId: String,
        title: String = "模块",
        vararg pages: PageDeclaration,
    ) = ModuleDeclaration(
        moduleId = moduleId,
        instanceId = "$moduleId-1",
        moduleTitle = title,
        online = true,
        moduleVersion = "1.0",
        pages = pages.toList(),
    )

    private fun page(id: String, version: Int = 1) =
        PageDeclaration(pageId = id, title = "页面$id", icon = PageIcon.List, version = version, requiresSession = false)

    @Test
    fun moduleWithPagesGetsEntryRows() {
        val index = ModulePageEntryRules.build(listOf(declaration("download", "下载管理", page("queue"), page("settings"))))
        assertEquals(1, index.groups.size)
        val group = index.groups[0]
        assertEquals("下载管理", group.moduleTitle)
        assertEquals(listOf("queue", "settings"), group.entries.map { it.pageId })
        assertEquals("页面queue", group.entries[0].pageTitle)
    }

    @Test
    fun moduleWithoutPagesHasNoEntry() {
        val index = ModulePageEntryRules.build(
            listOf(
                declaration("notes", "笔记"),
                declaration("download", "下载管理", page("queue")),
            ),
        )
        // FR-8.79：未声明 ui.pages 的模块不出现——没有分组、没有行、没有占位。
        assertTrue(index.groups.none { it.moduleId == "notes" })
        assertTrue(index.groups.map { it.moduleId } == listOf("download"))
    }

    @Test
    fun versionAboveSupportIsHiddenWithNote() {
        val index = ModulePageEntryRules.build(
            listOf(declaration("legacy", "旧版组件", page("beta", version = 2))),
        )
        assertTrue(index.groups.isEmpty(), "version=2 > 支持 {1} → 入口不出现")
        assertEquals(1, index.hidden.size)
        assertEquals("旧版组件", index.hidden[0].moduleTitle)
        assertEquals("页面beta", index.hidden[0].pageTitle)
        assertEquals(2, index.hidden[0].version)
    }

    @Test
    fun versionOneBoundaryIsSupported() {
        val index = ModulePageEntryRules.build(listOf(declaration("m", "模块", page("p1", version = 1))))
        assertEquals(1, index.groups.size, "恰好 1 是支持集边界（≤1）")
    }

    @Test
    fun mixedSupportedAndHiddenPagesKeepModuleVisible() {
        val index = ModulePageEntryRules.build(
            listOf(declaration("m", "模块", page("v1", version = 1), page("v2", version = 2))),
        )
        assertEquals(1, index.groups.size)
        assertEquals(listOf("v1"), index.groups[0].entries.map { it.pageId })
        assertEquals(1, index.hidden.size)
    }

    @Test
    fun emptyIndexSignalsCapabilitiesSectionShouldVanish() {
        val index = ModulePageEntryRules.build(emptyList())
        assertTrue(index.isEmpty, "整库无页面 → 能力页整个「模块页面」区块不渲染")
    }

    // --- demo 夹具（能力页本轮的真实数据源） ------------------------------------------

    @Test
    fun demoSourceHasEntryForDeclaredModulesOnly() {
        val index = ModulePageEntryRules.build(ModulePageSources.demoDeclarations())
        val moduleIds = index.groups.map { it.moduleId }
        assertTrue("download" in moduleIds && "charts" in moduleIds)
        // 未声明 ui.pages 的 notes 模块：无入口（FR-8.79 夹具）。
        assertTrue("notes" !in moduleIds)
        // legacy 只有 version=2 页面：分组缺席、隐藏说明在列。
        assertTrue("legacy" !in moduleIds)
        assertTrue(index.hidden.any { it.moduleTitle == "旧版组件" })
    }

    @Test
    fun demoSourceLoadsPagesAndStateForItsOwnDeclarations() {
        val source = ModulePageSources.demo()
        for (module in source.declarations()) {
            for (page in module.pages.filter { it.version <= ModulePageParser.SUPPORTED_PAGE_VERSION }) {
                val tree = source.loadPage(module.moduleId, page.pageId)
                assertTrue(tree != null, "demo 树必须给 ${module.moduleId}/${page.pageId} 提供页面体")
                val parseResult = ModulePageParser.parse(
                    requireNotNull(tree),
                    setOfNotNull(*module.pages.map { it.pageId }.toTypedArray()),
                )
                assertTrue(
                    parseResult is ParseResult.Ok,
                    "demo 页 ${module.moduleId}/${page.pageId} 必须能过校验: $parseResult",
                )
            }
        }
    }

    @Test
    fun demoSourceReportsOnlineStatusAndUnknownModuleOffline() {
        val source = ModulePageSources.demo()
        assertEquals(true, source.status("download").online)
        assertEquals(false, source.status("nope").online, "未知模块按离线处理（fail-safe 展示）")
    }
}
