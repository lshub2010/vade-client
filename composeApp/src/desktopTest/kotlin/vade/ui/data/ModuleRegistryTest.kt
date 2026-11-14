package vade.ui.data

import kotlinx.serialization.json.JsonObject
import vade.ui.module.ModulePageEntryRules
import vade.ui.module.ModulePageSource
import vade.ui.module.ModuleStatus as ModuleOnlineStatus
import vade.ui.module.PageIcon
import vade.ui.protocol.ModuleStatus as WireModuleStatusEvent
import vade.ui.protocol.PayloadDecodeException
import vade.ui.protocol.VadeEvent as WireEvent
import vade.ui.protocol.VadeEventParser
import vade.ui.protocol.VadeJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `ModuleRegistry`：把「协议上真有的」模块数据（`module.status`）与「已冻结但还没有 wire 通道的」
 * 声明数据（C-03 manifest `display_name` + `ui.pages[]`）收成一份快照，并把快照喂进
 * `ModulePageEntryRules` 证明类型逐字段对齐。
 *
 * 这些测试**不假装**事件存在：events.json 只有 `module.status` 一个模块 topic，所以这里没有
 * 任何 `module.register` / `module.unregister` 的构造——那会是在测试一条 core 从不发出的报文。
 * 声明路径用 `declare(manifest)` 注入，喂的是 C-03 原文（RFC-0009 §9.2 的 `ui.pages[]` 形状），
 * 也就是 RFC 落地后 snapshot/RPC 会送到客户端的那份字节。
 */
class ModuleRegistryTest {

    // ------------------------------------------------------------------ 装置

    private fun json(text: String): JsonObject = VadeJson.default.parseToJsonElement(text) as JsonObject

    /** 走真 [VadeEventParser]（不直接 new payload）：解码层与注册表一起被钉住。 */
    private fun statusEvent(
        payload: String,
        seq: Long = 1,
        onMalformed: ((String?, Throwable) -> Unit)? = null,
    ): WireEvent? = VadeEventParser.parse(
        type = "module.status",
        seq = seq,
        coalesced = false,
        payload = json(payload),
        onMalformed = onMalformed,
    )

    private fun event(type: String, payload: String, seq: Long = 1): WireEvent? =
        VadeEventParser.parse(type, seq, false, json(payload))

    /** C-03 原文：`display_name` + `ui.pages[]`（3 页，含 1 页 version=2 与 1 页 requires=["session"]）。 */
    private val downloadManifest = json(
        """
        {
          "module_id": "com.example.download",
          "instance_id": "dl-1",
          "display_name": "下载管理",
          "version": "0.3.1",
          "protocol": "vade/v1",
          "role": "tool",
          "capabilities": {"tools": [{"name": "refresh", "risk": "low", "timeout_ms": 1000,
            "input_schema": {"type": "object", "properties": {}, "additionalProperties": false}}]},
          "ui": {"pages": [
            {"id": "queue", "title": "下载队列", "icon": "list", "version": 1,
             "schema": {"type": "column", "children": []}},
            {"id": "settings", "title": "下载设置", "icon": "settings", "version": 1,
             "requires": ["session"], "schema": {"ref": {"page_id": "settings", "sha256": "ab12"}}},
            {"id": "beta", "title": "Beta 面板", "icon": "tool", "version": 2,
             "schema": {"type": "column", "children": []}}
          ]}
        }
        """.trimIndent(),
    )

    /** 真 `com.example.echo` manifest：`display_name` 有，`ui` **整块没有** → 无入口。 */
    private val echoManifest = json(
        """
        {
          "module_id": "com.example.echo",
          "instance_id": "main",
          "display_name": "回声模块",
          "version": "1.0.0",
          "protocol": "vade/v1",
          "role": "tool",
          "concurrency": "pooled",
          "capabilities": {"tools": [{"name": "echo", "risk": "low", "timeout_ms": 5000,
            "input_schema": {"type": "object", "properties": {"text": {"type": "string"}},
              "required": ["text"], "additionalProperties": false}}]}
        }
        """.trimIndent(),
    )

    /** 只实现 declarations()/status() 的宿主适配（loadPage/loadState 与本测试无关）。 */
    private class RegistryBackedSource(private val registry: ModuleRegistry) : ModulePageSource {
        override fun declarations() = registry.declarations()
        override fun status(moduleId: String) = registry.status(moduleId)
        override fun loadPage(moduleId: String, pageId: String): JsonObject? = null
        override fun loadState(moduleId: String, instanceId: String, pageId: String): JsonObject? = null
    }

    // ------------------------------------------------------------------ module.status 解码

    @Test
    fun moduleStatusDecodesEveryFieldVerbatimAndEntersTheRegistry() {
        val event = requireNotNull(
            statusEvent(
                """{"module_id":"com.example.echo","instance_id":"main","online":true,"version":"1.0.0"}""",
                seq = 7,
            ),
        )
        val payload = (event as WireModuleStatusEvent).payload
        assertEquals("com.example.echo", payload.moduleId)
        assertEquals("main", payload.instanceId)
        assertEquals(true, payload.online)
        assertEquals("1.0.0", payload.version)
        assertEquals(7L, event.seq)
        assertFalse(event.coalesced)

        val registry = ModuleRegistry()
        assertTrue(registry.onEvent(event), "module.status is the one module event the registry consumes")
        assertEquals(
            ModuleRegistry.Node(
                moduleId = "com.example.echo",
                instanceId = "main",
                online = true,
                version = "1.0.0",
                displayName = null,
                pages = emptyList(),
            ),
            registry.node("com.example.echo", "main"),
            "identity/online/version come from the event; no title or page is invented",
        )
    }

    @Test
    fun unknownFieldsOnModuleStatusAreIgnored() {
        val event = requireNotNull(
            statusEvent(
                """{"module_id":"m","instance_id":"i","online":false,"title":"猜的名字",
                   "pages":[{"id":"x"}],"x-future":{"deep":[1,2]}}""",
            ),
        )
        val registry = ModuleRegistry()
        assertTrue(registry.onEvent(event))
        val node = requireNotNull(registry.node("m", "i"))
        assertEquals(false, node.online)
        assertNull(node.displayName, "a status payload cannot smuggle a display name into the registry")
        assertEquals(emptyList(), node.pages, "nor pages")
    }

    @Test
    fun moduleStatusWithoutOnlineIsMalformedAndLeavesTheRegistryUntouched() {
        var malformed: Pair<String?, Throwable>? = null
        val registry = ModuleRegistry()
        val event = statusEvent(
            """{"module_id":"m","instance_id":"i","version":"1.0.0"}""",
            onMalformed = { type, error -> malformed = type to error },
        )
        assertNull(event, "required key `online` missing → the event is dropped, not defaulted to false")
        val failure = requireNotNull(malformed) { "the parser must report the malformed payload" }
        assertEquals("module.status", failure.first)
        assertTrue(failure.second is PayloadDecodeException)
        assertEquals(emptyList(), registry.nodes(), "the parser dropped it, so nothing reached the registry")
    }

    @Test
    fun moduleStatusWithNumericVersionIsMalformedNotSilentlyUnversioned() {
        var malformed: Pair<String?, Throwable>? = null
        val event = statusEvent(
            """{"module_id":"m","instance_id":"i","online":true,"version":3}""",
            onMalformed = { type, error -> malformed = type to error },
        )
        assertNull(event, "events.json spells version as string|null — the number 3 is a core bug, not 'no version'")
        val failure = requireNotNull(malformed) { "the parser must report the malformed payload" }
        assertTrue(failure.second is PayloadDecodeException)
        assertTrue(
            failure.second.message.orEmpty().contains("version"),
            "the decode failure names the offending key: ${failure.second.message}",
        )
    }

    // ------------------------------------------------------------------ 累积 / 幂等 / 状态变化

    @Test
    fun twoModulesAccumulateInArrivalOrder() {
        val registry = ModuleRegistry()
        registry.onEvent(requireNotNull(statusEvent("""{"module_id":"a","instance_id":"1","online":true}""", 1)))
        registry.onEvent(requireNotNull(statusEvent("""{"module_id":"b","instance_id":"1","online":false}""", 2)))
        assertEquals(listOf("a", "b"), registry.nodes().map { it.moduleId })
        assertEquals(true, registry.status("a").online)
        assertEquals(false, registry.status("b").online)
    }

    @Test
    fun repeatedStatusForTheSameInstanceIsIdempotent() {
        val registry = ModuleRegistry()
        val payload = """{"module_id":"a","instance_id":"1","online":true,"version":"1.0.0"}"""
        repeat(3) { registry.onEvent(requireNotNull(statusEvent(payload, seq = it + 1L))) }
        assertEquals(1, registry.nodes().size, "one (module_id, instance_id) = one node, no matter how many events")
        assertEquals("1.0.0", registry.node("a", "1")?.version)
    }

    @Test
    fun statusFlipUpdatesOnlineAndVersionInPlace() {
        val registry = ModuleRegistry()
        registry.onEvent(requireNotNull(statusEvent("""{"module_id":"a","instance_id":"1","online":true,"version":"1.0.0"}""", 1)))
        registry.onEvent(requireNotNull(statusEvent("""{"module_id":"a","instance_id":"1","online":false,"version":"1.0.1"}""", 2)))
        val node = requireNotNull(registry.node("a", "1"))
        assertEquals(false, node.online)
        assertEquals("1.0.1", node.version)
        assertEquals(listOf("a"), registry.nodes().map { it.moduleId }, "an update never reorders the table")
        assertEquals(false, registry.status("a").online)
    }

    @Test
    fun sameModuleTwoInstancesAreSeparateNodesAndStatusAggregates() {
        val registry = ModuleRegistry()
        registry.onEvent(requireNotNull(statusEvent("""{"module_id":"a","instance_id":"1","online":false,"version":"1.0.0"}""", 1)))
        registry.onEvent(requireNotNull(statusEvent("""{"module_id":"a","instance_id":"2","online":true,"version":"1.0.0"}""", 2)))
        assertEquals(2, registry.nodes().size, "core's identity is module_id#instance_id, so two instances are two nodes")
        assertTrue(registry.status("a").online, "module-level status: any instance online = online")
        assertEquals("1.0.0", registry.status("a").version)
    }

    @Test
    fun statusOfAnUnregisteredModuleIsOfflineAndQueriesReturnNull() {
        val registry = ModuleRegistry()
        registry.onEvent(requireNotNull(statusEvent("""{"module_id":"a","instance_id":"1","online":true}""")))
        assertEquals(ModuleOnlineStatus(online = false, version = null), registry.status("ghost"))
        assertNull(registry.node("ghost"))
        assertNull(registry.node("ghost", "1"))
        assertEquals(emptyList(), registry.declarations(), "unknown module never becomes a declaration")
        // 空白 id 不建节点、不抛：契约要求 string，空白串是 core 的 bug，客户端不替它编身份。
        assertFalse(registry.applyStatus("", "1", online = true, version = null))
        assertFalse(registry.applyStatus("a", "  ", online = true, version = null))
        assertEquals(1, registry.nodes().size)
    }

    @Test
    fun onEventIgnoresEveryNonModuleEvent() {
        val registry = ModuleRegistry()
        val turn = requireNotNull(event("turn.started", """{"turn_id":"t1","state":"running"}"""))
        assertFalse(registry.onEvent(turn), "only module.status is consumed; the caller may skip a re-render")
        assertEquals(emptyList(), registry.nodes())
    }

    // ------------------------------------------------------------------ 声明（manifest 注入）

    @Test
    fun manifestDeclarationKeepsTitleAndPagesVerbatimInManifestOrder() {
        val registry = ModuleRegistry()
        registry.declare(downloadManifest)
        val declarations = registry.declarations()
        assertEquals(1, declarations.size)
        val module = declarations.single()
        assertEquals("com.example.download", module.moduleId)
        assertEquals("dl-1", module.instanceId)
        assertEquals("下载管理", module.moduleTitle)
        assertEquals("0.3.1", module.moduleVersion)
        assertFalse(module.online, "no module.status yet = offline, never a hopeful true")
        assertEquals(listOf("queue", "settings", "beta"), module.pages.map { it.pageId }, "manifest order, untouched")
        assertEquals(listOf("下载队列", "下载设置", "Beta 面板"), module.pages.map { it.title })
        assertEquals(listOf(PageIcon.List, PageIcon.Settings, PageIcon.Tool), module.pages.map { it.icon })
        assertEquals(listOf(1, 1, 2), module.pages.map { it.version })
        assertEquals(listOf(false, true, false), module.pages.map { it.requiresSession })
    }

    @Test
    fun manifestWithoutUiDeclaresTheModuleButProducesNoPages() {
        val registry = ModuleRegistry()
        registry.declare(echoManifest)
        val declaration = registry.declarations().single()
        assertEquals("回声模块", declaration.moduleTitle)
        assertEquals(emptyList(), declaration.pages, "echo declares no ui.pages: no pages, no entry (FR-8.79)")
        assertEquals(true, registry.node("com.example.echo", "main")?.isDeclared)
    }

    @Test
    fun statusOnlyModuleNeverAppearsInDeclarations() {
        val registry = ModuleRegistry()
        registry.onEvent(requireNotNull(statusEvent("""{"module_id":"com.example.echo","instance_id":"main","online":true,"version":"1.0.0"}""")))
        assertTrue(registry.status("com.example.echo").online, "status is known …")
        assertEquals(emptyList(), registry.declarations(), "… but no title/pages are known, so nothing is declared")
        assertTrue(
            ModulePageEntryRules.build(registry.declarations()).isEmpty,
            "能力页据此 fail-closed：无声明 → 整个「模块页面」区块不渲染（FR-8.79）",
        )
    }

    @Test
    fun registryDeclarationsFeedEntryRulesWithTitlesIconsAndTheVersionGate() {
        val registry = ModuleRegistry()
        registry.declare(downloadManifest)
        registry.onEvent(requireNotNull(statusEvent("""{"module_id":"com.example.download","instance_id":"dl-1","online":true,"version":"0.3.1"}""")))

        val index = ModulePageEntryRules.build(RegistryBackedSource(registry).declarations())
        val group = index.groups.single()
        assertEquals("com.example.download", group.moduleId)
        assertEquals("下载管理", group.moduleTitle)
        assertEquals(listOf("queue", "settings"), group.entries.map { it.pageId }, "version=2 page is not an entry")
        assertEquals(listOf("下载队列", "下载设置"), group.entries.map { it.pageTitle })
        assertEquals(listOf(PageIcon.List, PageIcon.Settings), group.entries.map { it.icon })
        assertEquals(listOf(false, true), group.entries.map { it.requiresSession })
        assertEquals("dl-1", group.entries.first().instanceId, "ui/page.get needs the instance id, so the entry carries it")
        assertEquals(1, index.hidden.size, "the v2 page is reported as hidden, not silently dropped")
        assertEquals("Beta 面板", index.hidden.single().pageTitle)
        assertEquals(2, index.hidden.single().version)
    }

    @Test
    fun redeclaringTheSameManifestKeepsOnlineStatusAndPosition() {
        val registry = ModuleRegistry()
        registry.declare(downloadManifest)
        registry.onEvent(requireNotNull(statusEvent("""{"module_id":"com.example.download","instance_id":"dl-1","online":true,"version":"0.3.1"}""")))
        registry.declare(downloadManifest)
        assertEquals(1, registry.declarations().size, "same (module, instance) = one declaration")
        assertEquals(listOf("com.example.download"), registry.nodes().map { it.moduleId })
        assertTrue(registry.status("com.example.download").online, "a re-declare must not reset the live online state")
        assertEquals("下载管理", registry.declarations().single().moduleTitle)
    }

    @Test
    fun unknownIconNameFallsBackToNoneAndUnknownPageKeysAreIgnored() {
        val registry = ModuleRegistry()
        registry.declare(
            json(
                """
                {"module_id":"m","instance_id":"i","display_name":"模块","version":"1.0.0",
                 "ui":{"pages":[{"id":"p","title":"页","icon":"sparkles","version":1,
                   "refresh_s":30,"entry":"panel","x-future":true,
                   "schema":{"type":"column","children":[]}}]}}
                """.trimIndent(),
            ),
        )
        val page = registry.declarations().single().pages.single()
        assertEquals(PageIcon.None, page.icon, "schema default is `none`; an unknown icon is cosmetic, not a reason to drop the page")
        assertEquals("p", page.pageId)
    }

    @Test
    fun manifestMissingModuleIdThrowsInsteadOfInventingIdentity() {
        val registry = ModuleRegistry()
        val error = assertFailsWith<PayloadDecodeException> {
            registry.declare(json("""{"instance_id":"i","display_name":"模块","version":"1.0.0"}"""))
        }
        assertTrue(error.message.orEmpty().contains("module_id"), error.message.orEmpty())
        assertEquals(emptyList(), registry.declarations())
    }

    @Test
    fun pageWithoutIdFailsTheWholeManifestInsteadOfVanishing() {
        val registry = ModuleRegistry()
        assertFailsWith<PayloadDecodeException> {
            registry.declare(
                json(
                    """
                    {"module_id":"m","instance_id":"i","display_name":"模块","version":"1.0.0",
                     "ui":{"pages":[{"title":"没有 id","version":1,"schema":{"type":"column","children":[]}}]}}
                    """.trimIndent(),
                ),
            )
        }
        assertEquals(emptyList(), registry.declarations(), "a broken projection is refused as a whole, not half-imported")
    }

    @Test
    fun wrongShapedUiOrPagesIsRefusedNotTreatedAsNoPages() {
        val registry = ModuleRegistry()
        assertFailsWith<PayloadDecodeException> {
            registry.declare(json("""{"module_id":"m","instance_id":"i","display_name":"模块","ui":{"pages":{}}}"""))
        }
        assertFailsWith<PayloadDecodeException> {
            registry.declare(json("""{"module_id":"m","instance_id":"i","display_name":"模块","ui":[]}"""))
        }
        assertFailsWith<PayloadDecodeException> {
            registry.declare(
                json("""{"module_id":"m","instance_id":"i","display_name":"模块","ui":{"pages":[{"id":"p","title":5,"version":1}]}}"""),
            )
        }
    }

    @Test
    fun clearDropsEverythingSoAReconnectStartsFromTheWireNotFromMemory() {
        val registry = ModuleRegistry()
        registry.declare(downloadManifest)
        registry.onEvent(requireNotNull(statusEvent("""{"module_id":"com.example.download","instance_id":"dl-1","online":true}""")))
        registry.clear()
        assertEquals(emptyList(), registry.nodes())
        assertEquals(emptyList(), registry.declarations())
        assertEquals(ModuleOnlineStatus(online = false, version = null), registry.status("com.example.download"))
    }
}
