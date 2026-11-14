package vade.ui.data

import kotlinx.serialization.json.JsonObject
import vade.ui.module.ModulePageEntryRules
import vade.ui.module.PageIcon
import vade.ui.protocol.PayloadDecodeException
import vade.ui.protocol.VadeJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `ui/modules` 回包 → [ModuleRegistry] 的**逐字段**解码。
 *
 * 形状取自 core 已合入的实现（`{revision, modules:[{module_id, instance_id, display_name,
 * version, online, pages:[{id,title,icon,entry,version,requires,refresh_s}]}]}`），**不是**
 * `contracts/vade/schema/rpc.json` —— 本棒开工时 `grep -c 'ui/modules'` 在那个文件里是 **0**
 * （submodule `320384a`）。所以这些是「按 core 实现形状的严格解码测试」，**不是**真帧回归；
 * 真帧回归要等契约落地后重抓 `RealCoreFrameCorpus`（它今天没有 `ui/modules` 帧）。
 *
 * 严格度的主张（与 `ModuleRegistry.declare` 同一条规则，因为实现确实复用了它）：
 *  - 缺必填键 / 类型不符 → [PayloadDecodeException]，**整份拒绝**，不跳过坏条；
 *  - `pages` 缺失 → 该模块没有入口，但模块本身在清单里（FR-8.79）；
 *  - 未知键（`entry` / `refresh_s` / 未来字段）→ 忽略；
 *  - 未知 `icon` → [PageIcon.None]。
 */
class ModuleCatalogDecodeTest {

    private fun json(text: String): JsonObject = VadeJson.default.parseToJsonElement(text) as JsonObject

    /** 一份典型的回包：两个模块，第一个 3 页（含隐藏页与 requires session），第二个没有 pages。 */
    private val fullReply = json(
        """
        {
          "revision": 7,
          "modules": [
            {
              "module_id": "com.example.download",
              "instance_id": "dl-1",
              "display_name": "下载管理",
              "version": "0.3.1",
              "online": true,
              "pages": [
                {"id": "queue", "title": "下载队列", "icon": "list", "entry": "ui/download/queue",
                 "version": 1, "refresh_s": 5},
                {"id": "settings", "title": "下载设置", "icon": "settings", "version": 1,
                 "requires": ["session"]},
                {"id": "beta", "title": "Beta 面板", "icon": "tool", "version": 2}
              ]
            },
            {
              "module_id": "com.example.echo",
              "instance_id": "main",
              "display_name": "回声模块",
              "version": "1.0.0",
              "online": false
            }
          ]
        }
        """.trimIndent(),
    )

    // ------------------------------------------------------------------ 逐字段

    @Test
    fun decodesEveryFieldVerbatim() {
        val result = fullReply
        assertEquals(7L, ModuleCatalogWire.revision(result))

        val nodes = ModuleCatalogWire.nodes(result)
        assertEquals(2, nodes.size)

        val download = nodes[0]
        assertEquals("com.example.download", download.moduleId)
        assertEquals("dl-1", download.instanceId)
        assertEquals("下载管理", download.displayName)
        assertEquals("0.3.1", download.version)
        assertTrue(download.online)
        assertTrue(download.isDeclared)
        assertEquals(3, download.pages.size)
        assertEquals("queue", download.pages[0].pageId)
        assertEquals("下载队列", download.pages[0].title)
        assertEquals(PageIcon.List, download.pages[0].icon)
        assertEquals(1, download.pages[0].version)
        assertFalse(download.pages[0].requiresSession)
        assertEquals(PageIcon.Settings, download.pages[1].icon)
        assertTrue(download.pages[1].requiresSession, "requires=[\"session\"] 必须解出来")
        assertEquals(2, download.pages[2].version)
        assertEquals(PageIcon.Tool, download.pages[2].icon)

        val echo = nodes[1]
        assertEquals("com.example.echo", echo.moduleId)
        assertEquals("main", echo.instanceId)
        assertEquals("回声模块", echo.displayName)
        assertEquals("1.0.0", echo.version)
        assertFalse(echo.online)
        assertEquals(emptyList(), echo.pages, "没有 pages 键 ⇒ 没有入口，但模块仍在清单里")
    }

    @Test
    fun registryDeclarationsFeedTheEntryRulesShape() {
        val registry = ModuleRegistry()
        registry.replaceAll(ModuleCatalogWire.nodes(fullReply))

        val declarations = registry.declarations()
        assertEquals(listOf("com.example.download", "com.example.echo"), declarations.map { it.moduleId })

        // 形状对齐的判据：入口规则能直接吃这份 declarations，且标题逐字来自 wire。
        val index = ModulePageEntryRules.build(declarations)
        assertEquals(1, index.groups.size, "echo 没有 pages ⇒ 不产生分组（不是空分组）")
        val group = index.groups.single()
        assertEquals("下载管理", group.moduleTitle)
        assertEquals(listOf("queue", "settings"), group.entries.map { it.pageId }, "version=2 的页不进入口")
        assertEquals("下载队列", group.entries[0].pageTitle)
        assertEquals(listOf("Beta 面板"), index.hidden.map { it.pageTitle }, "version 超集 → 一行隐藏说明")
    }

    // ------------------------------------------------------------------ 可选/未知

    @Test
    fun nullAndAbsentVersionBothDecodeToNull() {
        val nodes = ModuleCatalogWire.nodes(
            json(
                """
                {"revision": 1, "modules": [
                  {"module_id":"a","instance_id":"i","display_name":"A","version":null,"online":true},
                  {"module_id":"b","instance_id":"i","display_name":"B","online":true}
                ]}
                """.trimIndent(),
            ),
        )
        assertNull(nodes[0].version, "显式 null ⇒ null（不是空串）")
        assertNull(nodes[1].version, "缺键 ⇒ null")
    }

    @Test
    fun unknownKeysAndUnusedPageFieldsAreIgnored() {
        val nodes = ModuleCatalogWire.nodes(
            json(
                """
                {"revision": 2, "modules": [
                  {"module_id":"m","instance_id":"i","display_name":"M","online":true,
                   "future_top_level": {"x": 1},
                   "pages":[{"id":"p","title":"P","icon":"list","version":1,
                             "entry":"ui/m/p","refresh_s":30,"future_page_key":[1,2]}]}
                ], "next_cursor": "abc"}
                """.trimIndent(),
            ),
        )
        assertEquals(1, nodes.single().pages.size)
        assertEquals("p", nodes.single().pages.single().pageId)
    }

    @Test
    fun unknownIconDegradesToNone() {
        val nodes = ModuleCatalogWire.nodes(
            json(
                """
                {"revision": 1, "modules": [
                  {"module_id":"m","instance_id":"i","display_name":"M","online":true,
                   "pages":[{"id":"p","title":"P","icon":"sparkles","version":1}]}
                ]}
                """.trimIndent(),
            ),
        )
        assertEquals(PageIcon.None, nodes.single().pages.single().icon, "未知 icon 是装饰，降级不拒绝")
    }

    // ------------------------------------------------------------------ 严格拒绝

    @Test
    fun missingRevisionIsRejected() {
        val error = assertFailsWith<PayloadDecodeException> {
            ModuleCatalogWire.revision(json("""{"modules": []}"""))
        }
        assertTrue(error.message.orEmpty().contains("revision"), "错误必须点名缺的键：${error.message}")
    }

    @Test
    fun stringRevisionIsRejectedAsWrongShape() {
        // `lngStrict`：字符串 "3" 是错形状，不许当 3 用（那类「勉强能跑」的容忍最后都会
        // 变成一张对不上号的清单）。
        assertFailsWith<PayloadDecodeException> { ModuleCatalogWire.revision(json("""{"revision":"3"}""")) }
    }

    @Test
    fun missingModulesArrayIsRejected() {
        assertFailsWith<PayloadDecodeException> { ModuleCatalogWire.nodes(json("""{"revision":3}""")) }
        assertFailsWith<PayloadDecodeException> {
            ModuleCatalogWire.nodes(json("""{"revision":3,"modules":{"module_id":"x"}}"""))
        }
    }

    @Test
    fun missingRequiredModuleFieldRejectsTheWholeReply() {
        val error = assertFailsWith<PayloadDecodeException> {
            ModuleCatalogWire.nodes(
                json(
                    """
                    {"revision": 1, "modules": [
                      {"module_id":"ok","instance_id":"i","display_name":"OK","online":true},
                      {"instance_id":"i","display_name":"BAD","online":true}
                    ]}
                    """.trimIndent(),
                ),
            )
        }
        assertTrue(
            // 举的例子从 `display_name` 换成 `module_id`：契约里前者是 string|null（可选），
            // 拿它当「必填字段」的例子会把这个测试变成「钉住一个错误的契约」。
            error.message.orEmpty().contains("module_id"),
            "必须点名缺的键（不是笼统 malformed）：${error.message}",
        )
    }

    @Test
    fun missingOnlineRejectsTheWholeReply() {
        val error = assertFailsWith<PayloadDecodeException> {
            ModuleCatalogWire.nodes(
                json("""{"revision":1,"modules":[{"module_id":"m","instance_id":"i","display_name":"M"}]}"""),
            )
        }
        assertTrue(error.message.orEmpty().contains("online"), "在线态缺了不许默认成离线：${error.message}")
    }

    @Test
    fun stringOnlineIsRejectedAsWrongShape() {
        assertFailsWith<PayloadDecodeException> {
            ModuleCatalogWire.nodes(
                json(
                    """{"revision":1,"modules":[
                         {"module_id":"m","instance_id":"i","display_name":"M","online":"true"}]}""",
                ),
            )
        }
    }

    @Test
    fun malformedPageRejectsTheWholeReply() {
        // 坏页整份拒绝（与 `ModuleRegistry.declare` 同一条）：多一个坏页 = 入口少一个无解释。
        val error = assertFailsWith<PayloadDecodeException> {
            ModuleCatalogWire.nodes(
                json(
                    """
                    {"revision": 1, "modules": [
                      {"module_id":"m","instance_id":"i","display_name":"M","online":true,
                       "pages":[{"title":"没有 id","version":1}]}
                    ]}
                    """.trimIndent(),
                ),
            )
        }
        assertTrue(error.message.orEmpty().contains("id"), "必须点名缺的键：${error.message}")
    }

    @Test
    fun blankModuleIdIsRejected() {
        assertFailsWith<PayloadDecodeException> {
            ModuleCatalogWire.nodes(
                json("""{"revision":1,"modules":[{"module_id":"","instance_id":"i","display_name":"M","online":true}]}"""),
            )
        }
    }

    // ------------------------------------------------------------------ 空清单

    @Test
    fun emptyModulesIsASuccessfulEmptyList() {
        val result = json("""{"revision": 4, "modules": []}""")
        assertEquals(4L, ModuleCatalogWire.revision(result))
        assertEquals(emptyList(), ModuleCatalogWire.nodes(result), "空清单是成功结果，不是解码失败")
    }
}
