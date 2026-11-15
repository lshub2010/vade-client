package vade.ui.e2e

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import vade.ui.data.ModuleCatalog
import vade.ui.data.ModuleCatalogState
import vade.ui.data.RealSessionSource
import vade.ui.data.RealSessionState
import vade.ui.data.VadeEvent
import vade.ui.destinations.CapabilitiesScreen
import vade.ui.module.ModulePageParser
import vade.ui.module.ParseResult
import vade.ui.module.RealModulePageSource
import vade.ui.protocol.BrowserPairing
import vade.ui.protocol.VadeClientConfig
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * g-modulepage-wire · **承重验收**：真 core + 真 echo 模块，跑
 * 「连上去 → 进能力页 → 看到「回声」入口 → 点开 → 三行中文原文逐行出现 →
 * 没有「模块离线」/「页面不可用」」。
 *
 * ## 与上一棒（`shots/g-module-page`）的差别：测试不再补任何一脚
 *
 * 上一棒这条链要测试自己 `catalog.retry()` + `pageSource.fetchPage(...)` 才跑得起来，
 * 因为生产代码里那两脚**没有人踩**（RESULT 缺口 1、2）。本棒把三处接缝接进 `commonMain`：
 *
 * | 缺口 | 本棒的接线 | 本测试怎么证明它真的在跑 |
 * |---|---|---|
 * | 1 清单没人拉 | 能力页 `LaunchedEffect` → `ModuleCatalog.ensureLoaded()` | 进屏幕**之前**断言 `Loading`；进屏幕后**不调任何手动动作**，入口自己出现 |
 * | 2 页面体没人拉 | `ModulePageHost(pageFetch = 真实页面源)` → `ui/page.get` | 点开后三行出现，且 `loadPage` 缓存里真有 core 交回的那棵树（4 节点 / 0 动作） |
 * | 3 恒报离线 | `RealModulePageSource.status()` ← `ModulesLoader.registry` | 页面顶部**没有**「模块离线」，而 `pages.status(...).online == true` |
 *
 * 链路每一节仍然都是真的：真 core（`vade.app.App` + uvicorn，临时数据目录）→
 * 模块密钥带外进 whitelist → 真 echo 模块子进程走 Noise IK 注册（`module/register`）→
 * 浏览器 `POST /ui/session` 拿 cookie → WS `ui/modules` → 真 `ui/page.get` → 组合的
 * `CapabilitiesScreen`。
 *
 * 组合根也只走生产那一个口：`RealSessionSource` → `adapters.modulePages()`（就是
 * `RealModulePageSource`，不是测试里 new 出来的另一个），清单通道是
 * `(source as ModuleCatalogProvider).moduleCatalog`。
 *
 * ## 失败时的取证
 *
 * 启动器（`shots/g-module-page/core_echo_launcher.py`）先把 core 侧的事实打出来：
 * `{"stage":"module", pages, page_ids, online, ui_warnings}`。断言失败消息里带这行原文，
 * 于是「模块没注册」「注册了但 0 页（页面被校验剔掉）」「注册了也有页但客户端没显示」
 * 三种病在日志里长得不一样。
 */
@OptIn(ExperimentalTestApi::class)
class RealCoreModulePageE2ETest {

    private var core: Process? = null
    private var source: RealSessionSource? = null

    @AfterTest
    fun stopEverything() {
        runCatching { runBlocking { source?.disconnect() } }
        source = null
        core?.destroyForcibly()
        core = null
    }

    @Test
    fun realCoreEchoPageOpensAndRendersTheThreeLines() {
        val dir = Files.createTempDirectory("vade-g-modulepage-wire-").toFile()
        val launcher = launcherScript(dir)
        val lines = Collections.synchronizedList(mutableListOf<String>())
        val stderr = StringBuilder()

        val process = ProcessBuilder(
            PYTHON,
            launcher.absolutePath,
            "--data-dir", File(dir, "data").absolutePath,
            "--core-dir", CORE_PKG_DIR,
            "--protocol-dir", PROTOCOL_PY_DIR,
            "--sdk-dir", SDK_PY_DIR,
            "--module-src", ECHO_MODULE_SRC,
            "--module-wait-s", "90",
        )
            // 子进程 IO 强制 UTF-8：pair_code 是汉字，别赌 locale。
            .apply {
                environment()["PYTHONUTF8"] = "1"
                environment()["PYTHONIOENCODING"] = "utf-8"
            }
            .redirectErrorStream(false)
            .start()
        core = process
        thread(isDaemon = true, name = "g-modulepage-wire-core-out") {
            runCatching {
                BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).useLines { l ->
                    l.forEach { line ->
                        lines.add(line)
                        println("LAUNCHER> $line")
                    }
                }
            }
        }
        thread(isDaemon = true, name = "g-modulepage-wire-core-err") {
            runCatching {
                BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8)).useLines { l ->
                    l.forEach { line ->
                        if (stderr.length < 20_000) stderr.append(line).append('\n')
                    }
                }
            }
        }

        val ready = awaitStage(lines, "ready") { process.isAlive }
        val port = IntField(ready, "port")
        val pairCode = StrField(ready, "pair_code")
        println("g-modulepage-wire · core ready port=$port")

        // ① core 侧的事实：模块注册了、在线、声明了几页、页 id 是什么。
        val moduleStage = awaitStage(lines, "module") { process.isAlive }
        val corePages = IntField(moduleStage, "pages")
        val corePageIds = StrListField(moduleStage, "page_ids")
        println("g-modulepage-wire · module=$moduleStage")
        assertEquals(1, corePages, "真 echo 模块必须声明 1 页（core 侧事实，0 页 = 页面被校验剔掉或模块没 ui 块）：$moduleStage")
        assertEquals(listOf("echo"), corePageIds, "页 id 必须是 echo：$moduleStage")

        val record = runBlocking {
            val created = RealSessionSource(
                VadeClientConfig(
                    host = "127.0.0.1",
                    port = port,
                    browserSession = BrowserPairing(pairCode, clientName = "g-modulepage-wire"),
                    requestTimeoutMs = 20_000,
                    reconnectBaseMs = 200,
                    reconnectCapMs = 1_000,
                ),
            )
            source = created
            created.connect()
            withTimeout(CoreBootBudget.WAIT_TIMEOUT_MS) {
                created.state.first { it is RealSessionState.Connected }
            }

            // ② 生产组合根的三样东西都必须在：清单通道、适配器包、页面源就是那个真类。
            val catalog = created.moduleCatalog
                ?: fail("连接后 moduleCatalog 必须非空（ModuleCatalogProvider 的接线在 connect() 里）")
            val adapters = created.adapters
                ?: fail("连接后 adapters 必须非空（AdapterHost 的接线在 connect() 里）")
            val pageSource = adapters.modulePages()
            val pages = pageSource as? RealModulePageSource
                ?: fail("modulePages() 必须交回 RealModulePageSource，实际是 ${pageSource::class.simpleName}")

            // ③ **本测试不 kick**：进屏幕之前一帧都还没发——这正是缺口的形状。
            println("g-modulepage-wire · catalog state before the screen = ${catalog.state}")
            assertEquals(
                ModuleCatalogState.Loading,
                catalog.state,
                "进屏幕之前不该有人拉过清单（谁先拉是缺口 1 的判据）",
            )
            assertNull(
                pages.loadPage("com.example.echo", "echo"),
                "页面体在点开之前不该在缓存里（缺口 2 的判据）",
            )

            Bundle(catalog = catalog, pages = pages, events = created.observeEvents())
        }

        runComposeUiTest(testTimeout = 240.seconds) {
            setContent {
                CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                    CapabilitiesScreen(
                        moduleCatalog = record.catalog,
                        pageSource = record.pages,
                        events = record.events,
                    )
                }
            }
            try {
                // ④ 入口出现 = 屏幕真的发起了那次唯一拉取（测试没有 retry/load）。
                waitUntil(timeoutMillis = CoreBootBudget.WAIT_TIMEOUT_MS) {
                    onAllNodesWithText(ENTRY_TITLE).fetchSemanticsNodes().isNotEmpty()
                }
            } catch (t: Throwable) {
                throw AssertionError(
                    "能力页没有出现「$ENTRY_TITLE」入口（说明第一次 ui/modules 没被发起或没解开）。" +
                        "core 侧事实=$moduleStage，catalog.state=${record.catalog.state}，语义树：\n" +
                        runCatching { onRoot(useUnmergedTree = true).printToString(maxDepth = 25) }
                            .getOrElse { "dump failed: $it" },
                    t,
                )
            }

            // ⑤ 清单是屏幕自己拉到的：1 个模块、1 页、在线（全部来自真 core 回包）。
            val readyState = assertIs<ModuleCatalogState.Ready>(
                record.catalog.state,
                "ui/modules 必须成功返回（不是 Loading/Failed）：${record.catalog.state}",
            )
            assertEquals(1, readyState.moduleCount, "清单里必须恰好一个模块")
            assertEquals(1, readyState.pageCount, "页面数必须 1 —— 恒为 0 的那条路正是本测试要拆开的")
            val declaration = readyState.declarations.single()
            assertEquals("com.example.echo", declaration.moduleId)
            assertEquals("回声模块", declaration.moduleTitle)
            assertEquals("回声", declaration.pages.single().title)
            assertEquals(true, declaration.online, "模块在线（registry health = ONLINE）")

            // ⑥ 缺口 3：页面源读的是同一份快照，不是恒 false 的常量。
            assertEquals(
                true,
                record.pages.status(declaration.moduleId).online,
                "RealModulePageSource.status 必须反映 registry 的在线态（否则页面顶部恒画「模块离线」）",
            )
            assertEquals(
                listOf("echo"),
                record.pages.declarations().single().pages.map { it.pageId },
                "声明也必须来自同一份快照（页内 navigate 目标校验要用）",
            )

            onNodeWithText(ENTRY_TITLE).assertExists()
            // 点之前正文不在树上。
            ECHO_LINES.forEach { line -> onNodeWithText(line).assertDoesNotExist() }

            // ⑦ 点它：宿主自己去发 ui/page.get（测试不碰 fetchPage）。
            onNodeWithText(ENTRY_TITLE).performClick()
            try {
                waitUntil(timeoutMillis = CoreBootBudget.WAIT_TIMEOUT_MS) {
                    onAllNodesWithText(LINE_1).fetchSemanticsNodes().isNotEmpty()
                }
            } catch (t: Throwable) {
                throw AssertionError(
                    "点开之后正文没有出现（ui/page.get 没被宿主发起，或回包没解开）。语义树：\n" +
                        runCatching { onRoot(useUnmergedTree = true).printToString(maxDepth = 25) }
                            .getOrElse { "dump failed: $it" },
                    t,
                )
            }
            waitForIdle()

            onNodeWithText("$MODULE_TITLE › $ENTRY_TITLE").assertExists()
            ECHO_LINES.forEach { line -> onNodeWithText(line).assertExists() }

            // ⑧ 三条「医生说没病」的正面判据：不画离线横幅、不画两张错误卡。
            assertTrue(
                onAllNodesWithText("模块离线", substring = true).fetchSemanticsNodes().isEmpty(),
                "模块在线时页面不得出现「模块离线」：\n" +
                    onRoot(useUnmergedTree = true).printToString(maxDepth = 25),
            )
            onNodeWithText("页面不可用").assertDoesNotExist()
            onNodeWithText("页面无法渲染").assertDoesNotExist()
            onNodeWithText("正在取回页面").assertDoesNotExist()

            // ⑨ 页面体现在真的在缓存里 = 那一帧 `ui/page.get` 真发出去了、真的是 core 交回的树。
            val cached = record.pages.loadPage(declaration.moduleId, "echo")
                ?: fail("点开之后 loadPage 必须命中（否则宿主画的就是「页面不可用」）")
            val parsed = ModulePageParser.parse(cached, setOf("echo"))
            val ok = assertIs<ParseResult.Ok>(parsed, "core 交回的页面体必须过渲染期的 fail-closed 校验：$parsed")
            println("g-modulepage-wire · ui/page.get → nodes=${ok.nodeCount} actions=${ok.actionCount}")
            assertEquals(4, ok.nodeCount, "column + 三个 text = 4 个节点")
            assertEquals(0, ok.actionCount, "echo 页面没有任何动作")

            println("--- 真 core 语义树（点开之后）--------------------------------------")
            println(onRoot(useUnmergedTree = true).printToString(maxDepth = 25))
        }
    }

    /** 组合需要的一对：清单通道与真页面源（同一个连接、同一个 registry）。 */
    private class Bundle(
        val catalog: ModuleCatalog,
        val pages: RealModulePageSource,
        val events: Flow<VadeEvent>,
    )

    // ------------------------------------------------------------------ 装置

    /** 启动器脚本：**仓库里那一份**（`shots/g-module-page/core_echo_launcher.py`），不另存副本。 */
    private fun launcherScript(work: File): File {
        val candidates = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .take(6)
            .map { File(it, "shots/g-module-page/core_echo_launcher.py") }
            .filter { it.isFile }
            .toList()
        val found = candidates.firstOrNull()
            ?: fail(
                "找不到 shots/g-module-page/core_echo_launcher.py（从 ${System.getProperty("user.dir")} 向上找了 6 层）",
            )
        // 复制到临时目录再跑：脚本不需要写自己旁边，但临时目录能保证「这次跑的是一份快照」。
        return File(work, "core_echo_launcher.py").also { it.writeText(found.readText()) }
    }

    /** 等某一行 `{"stage":"..."}`；超时/子进程先死都抛带取证的消息。 */
    private fun awaitStage(
        lines: List<String>,
        stage: String,
        timeoutMs: Long = CoreBootBudget.READY_TIMEOUT_MS,
        alive: () -> Boolean,
    ): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            synchronized(lines) {
                lines.firstOrNull { it.contains("\"stage\": \"$stage\"") || it.contains("\"stage\":\"$stage\"") }
            }?.let { return it }
            lines.toList().firstOrNull { it.contains("\"stage\": \"fatal\"") || it.contains("\"stage\":\"fatal\"") }
                ?.let { fail("启动器 fatal（等 $stage 时）：$it\n--- stdout ---\n${lines.joinToString("\n")}") }
            if (!alive()) {
                fail("core 子进程在 $stage 之前退出：\n--- stdout ---\n${lines.joinToString("\n")}")
            }
            Thread.sleep(20)
        }
        fail(
            "等 ${stage} 超过 ${timeoutMs}ms（死锁保险）：\n--- stdout ---\n${lines.joinToString("\n")}",
        )
    }

    private fun IntField(line: String, key: String): Int =
        Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(line)?.groupValues?.get(1)?.toInt()
            ?: fail("启动器输出里没有 $key：$line")

    private fun StrField(line: String, key: String): String =
        Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(line)?.groupValues?.get(1)
            ?: fail("启动器输出里没有 $key：$line")

    private fun StrListField(line: String, key: String): List<String> =
        Regex("\"$key\"\\s*:\\s*\\[([^\\]]*)]").find(line)?.groupValues?.get(1)
            ?.split(',')
            ?.map { it.trim().trim('"') }
            ?.filter { it.isNotEmpty() }
            ?: fail("启动器输出里没有 $key：$line")

    private companion object {
        /** 与 `RealCoreE2ETest` 同一套默认值（可用环境变量覆盖）。 */
        val PYTHON: String = System.getenv("VADE_PYTHON") ?: "D:/HermesWorkspace/Vade/Vade/.venv/Scripts/python.exe"
        val CORE_PKG_DIR: String = System.getenv("VADE_CORE_PKG_DIR") ?: "D:/HermesWorkspace/Vade/Vade/core"
        val PROTOCOL_PY_DIR: String =
            System.getenv("VADE_PROTOCOL_PY_DIR") ?: "D:/HermesWorkspace/Vade/Vade/protocol/core/python"
        val SDK_PY_DIR: String = System.getenv("VADE_SDK_PY_DIR") ?: "D:/HermesWorkspace/Vade/Vade/sdk/python"
        val ECHO_MODULE_SRC: String =
            System.getenv("VADE_ECHO_MODULE_SRC") ?: "D:/HermesWorkspace/Vade/Vade-module-echo/src"

        const val MODULE_TITLE = "回声模块"
        const val ENTRY_TITLE = "回声"
        const val LINE_1 = "这个页面由模块的 manifest 声明（C-03 ui.pages），不是客户端硬编码的。"
        const val LINE_2 = "模块没有 ui 块时，ui/modules 里一个入口都不会出现（FR-8.79）。"
        const val LINE_3 = "工具：echo / add / sleep / boom"
        val ECHO_LINES = listOf(LINE_1, LINE_2, LINE_3)
    }
}
