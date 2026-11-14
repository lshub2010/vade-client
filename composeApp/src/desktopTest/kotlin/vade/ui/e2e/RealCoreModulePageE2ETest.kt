package vade.ui.e2e

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import vade.ui.data.ModuleCatalogState
import vade.ui.data.RealSessionSource
import vade.ui.data.RealSessionState
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
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * g-module-page · B 段：**真 core + 真 echo 模块**，把 A 段那条链换成真传输再跑一遍。
 *
 * 链路每一节都是真的：
 * 真 core（`vade.app.App` + uvicorn，临时数据目录）→ 模块密钥带外进 whitelist →
 * 真 echo 模块子进程走 Noise IK 注册（`module/register`）→ 浏览器 `POST /ui/session`
 * 拿 cookie → WS `ui/modules` → `ModulesLoader` 解码出 `pages[0]` → 真 `ui/page.get`
 * 拿回页面体 → 组合的 `CapabilitiesScreen` 上出现「回声」入口 → 点开 → 三行文字。
 *
 * ## 为什么这条测试要单独存在（A 段已经证明了渲染器）
 *
 * A 段（`CapabilitiesModulePageOpenTest`）证的是「给定一帧回包 + 一份页面体，屏幕上会出现
 * 入口并渲染那三行」。它把**传输**换成了常量。本测试把常量换回真的 WS/HTTP，于是
 * 「core 真会给这几页」「`ui/page.get` 真会把这份 schema 交回来」「客户端解码链真的
 * 认这个形状」三件事同时被钉住 —— 三者任一不成立，入口或正文就消失。
 *
 * ## 这条测试自己补的两脚（生产路径目前缺，属于别的包的改动）
 *
 * 1. `ModuleCatalog` 的第一次拉取：`RealSessionSource.connect()` 只建 loader，`commonMain`
 *    里没有 `ModulesLoader.load()` 的调用点，所以这里显式 `retry()`（接口上的公开动作，
 *    「首次拉取前调用也合法」）。
 * 2. 页面体：`ModulePageHost` 是同步值语义，不会自己发 `ui/page.get`，而
 *    `RealModulePageSource.fetchPage(...)` 在 `commonMain` 里也没有调用点。这里显式拉一次。
 *
 * 两处都在报告里列为缺口（改它们要动 data 包 / 宿主生命周期，不在本棒红线内）。
 * 除此之外没有替身：没有假 core、没有假回包、没有跳过网络。
 *
 * ## 失败时的取证
 *
 * 启动器（`shots/g-module-page/core_echo_launcher.py`）会把 core 侧的事实先打出来：
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
        val dir = Files.createTempDirectory("vade-g-module-page-").toFile()
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
        thread(isDaemon = true, name = "g-module-page-core-out") {
            runCatching {
                BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).useLines { l ->
                    l.forEach { line ->
                        lines.add(line)
                        println("LAUNCHER> $line")
                    }
                }
            }
        }
        thread(isDaemon = true, name = "g-module-page-core-err") {
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
        println("g-module-page · core ready port=$port")

        // ① core 侧的事实：模块注册了、在线、声明了几页、页 id 是什么。
        val moduleStage = awaitStage(lines, "module") { process.isAlive }
        val corePages = IntField(moduleStage, "pages")
        val corePageIds = StrListField(moduleStage, "page_ids")
        println("g-module-page · module=$moduleStage")
        assertEquals(1, corePages, "真 echo 模块必须声明 1 页（core 侧事实，0 页 = 页面被校验剔掉或模块没 ui 块）：$moduleStage")
        assertEquals(listOf("echo"), corePageIds, "页 id 必须是 echo：$moduleStage")

        val record = runBlocking {
            val created = RealSessionSource(
                VadeClientConfig(
                    host = "127.0.0.1",
                    port = port,
                    browserSession = BrowserPairing(pairCode, clientName = "g-module-page"),
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

            // ② 真 WS `ui/modules`。生产路径目前没有「第一次拉取」的那一脚（见类 KDoc），
            //    这里用接口上的公开动作补上，并把补之前的状态如实打出来。
            val catalog = created.moduleCatalog
                ?: fail("连接后 moduleCatalog 必须非空（ModuleCatalogProvider 的接线在 connect() 里）")
            println("g-module-page · catalog state before kick = ${catalog.state}")
            catalog.retry()
            val deadline = System.currentTimeMillis() + CoreBootBudget.WAIT_TIMEOUT_MS
            while (catalog.state !is ModuleCatalogState.Ready && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            val readyState = assertIs<ModuleCatalogState.Ready>(
                catalog.state,
                "ui/modules 必须成功返回（不是 Loading/Failed）：${catalog.state}",
            )
            assertEquals(1, readyState.moduleCount, "清单里必须恰好一个模块")
            assertEquals(1, readyState.pageCount, "页面数必须 1 —— 恒为 0 的那条路正是本测试要拆开的")
            val declaration = readyState.declarations.single()
            assertEquals("com.example.echo", declaration.moduleId)
            assertEquals("回声模块", declaration.moduleTitle)
            assertEquals("回声", declaration.pages.single().title)
            assertEquals(true, declaration.online, "模块在线（registry health = ONLINE）")

            // ③ 真 `ui/page.get`：页面体经真 RPC 回来，并进 RealModulePageSource 的缓存。
            val client = created.client ?: fail("连接后 client 必须存在")
            val pageSource = RealModulePageSource(client)
            val pageId = declaration.pages.single().pageId
            val fetched = pageSource.fetchPage(
                moduleId = declaration.moduleId,
                instanceId = declaration.instanceId,
                pageId = pageId,
                declaredPageIds = setOf(pageId),
            )
            println("g-module-page · ui/page.get → nodes=${fetched.nodeCount} actions=${fetched.actionCount}")
            assertEquals(4, fetched.nodeCount, "column + 三个 text = 4 个节点")
            assertEquals(0, fetched.actionCount, "echo 页面没有任何动作")
            assertTrue(
                pageSource.loadPage(declaration.moduleId, pageId) != null,
                "fetchPage 成功后 loadPage 必须命中（否则宿主会画「页面不可用」）",
            )
            recordPage(pageSource, declaration.moduleId, pageId)
            PageBundle(catalog = catalog, pages = pageSource)
        }

        runComposeUiTest(testTimeout = 240.seconds) {
            setContent {
                CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                    CapabilitiesScreen(moduleCatalog = record.catalog, pageSource = record.pages)
                }
            }
            try {
                waitUntil(timeoutMillis = CoreBootBudget.WAIT_TIMEOUT_MS) {
                    onAllNodesWithText(ENTRY_TITLE).fetchSemanticsNodes().isNotEmpty()
                }
            } catch (t: Throwable) {
                throw AssertionError(
                    "能力页没有出现「$ENTRY_TITLE」入口。core 侧事实=$moduleStage，语义树：\n" +
                        runCatching { onRoot(useUnmergedTree = true).printToString(maxDepth = 25) }
                            .getOrElse { "dump failed: $it" },
                    t,
                )
            }
            onNodeWithText(ENTRY_TITLE).assertExists()
            // 点之前正文不在树上。
            ECHO_LINES.forEach { line -> onNodeWithText(line).assertDoesNotExist() }
            onNodeWithText(ENTRY_TITLE).performClick()
            waitForIdle()
            ECHO_LINES.forEach { line -> onNodeWithText(line).assertExists() }
            onNodeWithText("页面无法渲染").assertDoesNotExist()
            onNodeWithText("页面不可用").assertDoesNotExist()
            println("--- 真 core 语义树（点开之后）--------------------------------------")
            println(onRoot(useUnmergedTree = true).printToString(maxDepth = 25))
        }
    }

    /** 组合需要的一对：清单通道与真页面源（同一个连接）。 */
    private class PageBundle(
        val catalog: vade.ui.data.ModuleCatalog,
        val pages: RealModulePageSource,
    )

    /** 页面体自检：`loadPage` 回来的 schema 自己再解析一遍，确认它是 echo 的那棵树。 */
    private fun recordPage(source: RealModulePageSource, moduleId: String, pageId: String) {
        val body = source.loadPage(moduleId, pageId) ?: fail("loadPage 必须命中 $moduleId/$pageId")
        val parsed = ModulePageParser.parse(body, setOf(pageId))
        assertIs<ParseResult.Ok>(parsed, "core 交回的页面体必须过渲染期的 fail-closed 校验：$parsed")
    }

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

        const val ENTRY_TITLE = "回声"
        const val LINE_1 = "这个页面由模块的 manifest 声明（C-03 ui.pages），不是客户端硬编码的。"
        const val LINE_2 = "模块没有 ui 块时，ui/modules 里一个入口都不会出现（FR-8.79）。"
        const val LINE_3 = "工具：echo / add / sleep / boom"
        val ECHO_LINES = listOf(LINE_1, LINE_2, LINE_3)
    }
}
