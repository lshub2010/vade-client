package vade.ui.e2e

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume
import vade.ui.chat.ApprovalOutcome
import vade.ui.chat.ChatItem
import vade.ui.chat.ChatPhase
import vade.ui.chat.ChatState
import vade.ui.chat.ChatThread
import vade.ui.chat.DraftStore
import vade.ui.chat.ToolRunState
import vade.ui.chat.isApprovalCard
import vade.ui.data.RealSessionSource
import vade.ui.data.RealSessionState
import vade.ui.protocol.BrowserPairing
import vade.ui.protocol.VadeClientConfig
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * T3 客户端半 · **真 core + 真 `com.example.model` + 真 `com.example.echo`**：模型在一条
 * assistant 消息里发起四个 `tool_calls`（echo / add / sleep / boom），客户端时间线必须出现
 * **四张工具卡**，且被 `[policy.tool_risk]` 抬到 `high` 的 `boom` 那张卡要拿到审批卡形态、
 * 并且审批请求（`request_id`）要落在**它**身上 —— 不是时间线尾部另起一张孤立卡。
 *
 * ## 链路里每一节都是真的
 *
 * 真 core（`vade.app.App` + uvicorn + 真会话库）→ 模块密钥带外进 whitelist → 真 model / echo
 * 两个子进程走 Noise IK 注册 → 浏览器 `POST /ui/session` 换 cookie → WS `ui/attach` →
 * `ui/submit` → 真模型模块（`[llm] provider = "module"`、`fallback_to_builtin = false`）→
 * 假 OpenAI 上游只回「四 tool_calls 的 assistant 消息」和「final 文本」→ core 逐个执行四个
 * echo 工具 → `turn.tool_call` / `turn.tool_result` / `turn.approval_required` 真事件。
 *
 * 组合根只走生产那一个口：`RealSessionSource` → `attach(null)` 铸会话 → `source.adapters!!`
 * → `adapters.turns(sessionId)` / `adapters.uploader()` 构造 `ChatState`（与
 * `SessionDetailScreen.resolveChatState` 同形），渲染的是生产 `ChatThread`。
 *
 * ## 为什么 CI 显式 skip 而不是失败
 *
 * 这条测试需要两个**不在公开仓**的模块仓库（`Vade-module-model` / `Vade-module-echo`）。
 * 缺 `VADE_MODEL_MODULE_SRC` / `VADE_ECHO_MODULE_SRC` 时 `Assume.assumeTrue` 让 JUnit 标记
 * **skipped**（绝不静默变绿、也绝不因为 CI 没有模块仓而红）。本地真跑要给的六个环境变量见
 * 文件末尾的 `companion object`。
 *
 * ## 已知缺陷（本条用例现在会红的那一组断言，2026-11-27 实测）
 *
 * core 在两个事件里用了**两套工具名**，而 `ChatState.bindableToolCardId` 只做字面相等：
 *
 * | 事件 | `payload.tool` 实测值 | 契约口径 |
 * | --- | --- | --- |
 * | `turn.tool_call` | `echo__boom` | C-06：LLM-side name（模块广告名） |
 * | `turn.approval_required` | `com.example.echo/boom` | core 发的是 `entry.tool_id`（canonical） |
 *
 * 于是 `call.name == p.tool` 永远不成立 → 绑定落空 → 时间线尾部多出一张降级卡
 * （`approval:<request_id>`、`argsPreview = null`），`boom` 自己停在 `Running`。这就是本用例
 * 「恰好四张卡」与「审批请求落在 boom 上」两条断言会红的原因。证据是 `shots/t3-model-tools`
 * 的 Python 真跑输出（`turn.tool_call tool=echo__boom` / `turn.approval_required
 * tool=com.example.echo/boom`）。
 *
 * **本批次只交新文件、不改生产代码**，所以这里保留按验收口径写的断言（红色即缺陷），
 * 修法在 `ChatState.bindableToolCardId` 比较前把两侧归一到同一命名空间（例如
 * `p.tool.replace("/", "__")` 与 `call.name` 比，或直接比 `tool_id` 的裸名后缀）。
 */
@OptIn(ExperimentalTestApi::class)
class RealCoreToolCardsE2ETest {

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
    fun fourToolCallsBecomeFourCardsAndTheHighRiskApprovalBindsToBoom() {
        val modelSrc = System.getenv("VADE_MODEL_MODULE_SRC")?.takeIf { it.isNotBlank() }
        val echoSrc = System.getenv("VADE_ECHO_MODULE_SRC")?.takeIf { it.isNotBlank() }
        Assume.assumeTrue(
            "VADE_MODEL_MODULE_SRC / VADE_ECHO_MODULE_SRC 未设置：本测试需要 Vade-module-model " +
                "与 Vade-module-echo 两个仓库（CI 只有公开仓，显式 skip；本地真跑见类 KDoc）",
            modelSrc != null && echoSrc != null,
        )
        listOf(
            "VADE_MODEL_MODULE_SRC" to modelSrc!!,
            "VADE_ECHO_MODULE_SRC" to echoSrc!!,
        ).forEach { (name, path) ->
            assertTrue(
                File(path, "src").isDirectory || File(path).isDirectory,
                "$name 指向的目录不存在（要的是模块仓库的 src/ 或仓库根）：$path",
            )
        }

        val dir = Files.createTempDirectory("vade-t3-toolcards-").toFile()
        val launcher = launcherScript()
        val lines = Collections.synchronizedList(mutableListOf<String>())
        val stderr = StringBuilder()

        val process = ProcessBuilder(
            PYTHON,
            launcher.absolutePath,
            "--data-dir", File(dir, "data").absolutePath,
            "--core-dir", CORE_PKG_DIR,
            "--protocol-dir", PROTOCOL_PY_DIR,
            "--sdk-dir", SDK_PY_DIR,
            "--model-src", modelSrc,
            "--echo-src", echoSrc,
            "--approval-timeout", APPROVAL_TIMEOUT_S.toString(),
        )
            // 子进程 IO 强制 UTF-8：pair_code 是汉字，别赌 locale。
            .apply {
                environment()["PYTHONUTF8"] = "1"
                environment()["PYTHONIOENCODING"] = "utf-8"
            }
            .redirectErrorStream(false)
            .start()
        core = process
        thread(isDaemon = true, name = "t3-toolcards-core-out") {
            runCatching {
                BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).useLines { l ->
                    l.forEach { line ->
                        lines.add(line)
                        println("LAUNCHER> $line")
                    }
                }
            }
        }
        thread(isDaemon = true, name = "t3-toolcards-core-err") {
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
        assertEquals(
            APPROVAL_TIMEOUT_S,
            IntField(ready, "approval_timeout_s"),
            "--approval-timeout 必须原样落到 core 的 policy.approval_timeout_s：$ready",
        )

        // ① core 侧的事实：boom 的 risk 由 `[policy.tool_risk]` 抬到 high（两侧都要，见启动器 KDoc）。
        val policy = awaitStage(lines, "policy") { process.isAlive }
        assertEquals("high", StrField(policy, "engine"), "PolicyEngine 侧 risk 必须是 high：$policy")
        assertEquals("high", StrField(policy, "registry"), "工具绑定侧 risk 必须是 high：$policy")
        println("t3-toolcards · ready port=$port policy=$policy")

        val chat = runBlocking {
            val created = RealSessionSource(
                VadeClientConfig(
                    host = "127.0.0.1",
                    port = port,
                    browserSession = BrowserPairing(pairCode, clientName = "t3-toolcards"),
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
            // `attach(session_id = null)` = core 铸一个新会话（建会话与建事件订阅同一个动作）。
            val sessionId = created.attach(null).sessionId
            assertTrue(sessionId.isNotBlank(), "attach(null) 必须交回 core 铸的会话 id")
            val adapters = created.adapters
                ?: fail("连接后 adapters 必须非空（AdapterHost 的接线在 connect() 里）")
            ChatState(
                sessionId,
                uploader = adapters.uploader(),
                turns = adapters.turns(sessionId),
                drafts = DraftStore(),
            )
        }
        assertEquals(
            true,
            chat.isRealSource,
            "turns 来自真适配器，ChatState 必须是真源模式（否则渲染的是 fixtures）",
        )

        // ② 事件收集先起（ChatThread 的 LaunchedEffect 是同一顺序：先 collect，再 loadHistory）。
        val pumpScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var collecting: Job? = null
        var failure: Throwable? = null
        try {
            collecting = pumpScope.launch { chat.collectLiveEvents() }
            runBlocking {
                assertTrue(chat.loadHistory(), "真 source 的历史必须读得回来（loadHistory 返回 true）")
            }
            assertEquals(ChatPhase.Ready, chat.phase, "历史读完必须落 Ready：${chat.loadError}")

            chat.updateDraft(SUBMIT_TEXT)
            assertNotNull(chat.submit(), "submit 必须铸出 client_msg_id 并出网")

            // ③ 等四个 call 都到齐（approval:<request_id> 是「绑定失败」的降级卡，单独数）。
            val deadline = System.currentTimeMillis() + CoreBootBudget.WAIT_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline && toolCalls(chat).size < 4) {
                Thread.sleep(50)
            }
            assertTrue(
                toolCalls(chat).size >= 4,
                "90s 内没有等到四个工具卡（turn.tool_call / turn.tool_result 没投影上屏？）。" +
                    "core 侧事实=$policy\n时间线：\n${dump(chat)}",
            )

            val tools = chat.items.filterIsInstance<ChatItem.Tool>()
            val realCards = toolCalls(chat)
            assertEquals(
                4,
                tools.size,
                "时间线必须**恰好**四张工具卡：审批卡是 boom 那张卡的就地升级，不是尾部另起一张。" +
                    "多出来的卡通常是 `approval:<request_id>` 降级卡（绑定失败）。\n时间线：\n${dump(chat)}",
            )
            assertEquals(
                listOf("echo", "add", "sleep", "boom"),
                realCards.map { it.call.name.substringAfterLast("__") },
                "四张卡必须按事件到达序 echo / add / sleep / boom（名字取 core 广告的 LLM 名）：\n${dump(chat)}",
            )

            val (echo, add, sleep, boom) = realCards
            assertEquals(ToolRunState.Succeeded, echo.call.state, "echo 必须跑完：${echo.call.result}")
            assertEquals(ToolRunState.Succeeded, add.call.state, "add 必须跑完：${add.call.result}")
            assertEquals(ToolRunState.Succeeded, sleep.call.state, "sleep 必须跑完：${sleep.call.result}")
            assertEquals(true, echo.call.result?.ok, "echo 结果必须是 ok（readable）：${echo.call.result}")
            assertEquals(true, add.call.result?.ok, "add 结果必须是 ok：${add.call.result}")
            assertEquals(true, sleep.call.result?.ok, "sleep 结果必须是 ok：${sleep.call.result}")

            // ④ boom：high risk → 停在审批，审批请求落在这张卡上（不是尾部降级卡）。
            assertEquals(
                ToolRunState.PendingApproval,
                boom.call.state,
                "boom 必须原地升级成 PendingApproval（不是留在 Running 再另建一张）：\n${dump(chat)}",
            )
            assertEquals("high", boom.call.risk, "审批事件带来的 risk 必须是 high")
            assertEquals(
                ApprovalOutcome.Pending,
                chat.approvalOutcome(boom.call.id),
                "审批卡必须等人类（浏览器不是合法 approver，这里只验 Pending）：\n${dump(chat)}",
            )
            assertTrue(
                isApprovalCard(boom.call, chat.approvalOutcome(boom.call.id)),
                "boom 那张卡必须按审批卡形态渲染（isApprovalCard）",
            )
            assertNotNull(
                chat.approvalRequestId(boom.call.id),
                "turn.approval_required 的 request_id 必须绑到 boom 这张 call（approvalRequestId 单一真相）。" +
                    "绑定失败时 core 的 tool 是 `com.example.echo/boom` 而 tool_call 的 tool 是 " +
                    "`echo__boom`（两套命名空间，见类 KDoc 的已知缺陷）。\n时间线：\n${dump(chat)}",
            )
            listOf(echo, add, sleep).forEach { card ->
                assertNull(
                    chat.approvalRequestId(card.call.id),
                    "${card.call.name} 不该拿到任何审批请求（只有 boom 越线）",
                )
            }
            assertTrue(
                boom.call.argsPreview?.contains("message") == true,
                "审批卡的参数摘要必须来自 turn.tool_call 的 args_preview（high risk 是 typed 口径，" +
                    "字符串值被压成 <str:N>，但字段名仍在）：${boom.call.argsPreview}",
            )

            // ⑤ 生产 ChatThread 上屏：审批卡在屏上、boom 名字与参数摘要在卡上、四张卡有字。
            runComposeUiTest(testTimeout = 240.seconds) {
                setContent {
                    CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                        ChatThread(state = chat, animate = false)
                    }
                }
                try {
                    waitUntil(timeoutMillis = CoreBootBudget.WAIT_TIMEOUT_MS) {
                        onAllNodesWithText("需要批准", substring = true).fetchSemanticsNodes().isNotEmpty()
                    }
                } catch (t: Throwable) {
                    throw AssertionError(
                        "审批卡没有上屏（「需要批准」不存在）。时间线：\n${dump(chat)}\n语义树：\n" +
                            runCatching { onRoot(useUnmergedTree = true).printToString(maxDepth = 25) }
                                .getOrElse { "dump failed: $it" },
                        t,
                    )
                }
                onNodeWithText("需要批准").assertExists()
                assertTrue(
                    onAllNodesWithText("risk=high", substring = true).fetchSemanticsNodes().isNotEmpty(),
                    "审批卡必须带 high 风险徽标：\n" +
                        onRoot(useUnmergedTree = true).printToString(maxDepth = 25),
                )
                assertTrue(
                    onAllNodesWithText(boom.call.name, substring = true).fetchSemanticsNodes().isNotEmpty(),
                    "审批卡上必须有 boom 的工具名（${boom.call.name}）：\n" +
                        onRoot(useUnmergedTree = true).printToString(maxDepth = 25),
                )
                assertTrue(
                    onAllNodesWithText(boom.call.argsPreview!!, substring = true)
                        .fetchSemanticsNodes().isNotEmpty(),
                    "审批卡上必须有参数摘要（${boom.call.argsPreview}）：\n" +
                        onRoot(useUnmergedTree = true).printToString(maxDepth = 25),
                )
                assertTrue(
                    onAllNodesWithText("工具", substring = true).fetchSemanticsNodes().isNotEmpty(),
                    "至少一张普通工具卡必须在屏上：\n" +
                        onRoot(useUnmergedTree = true).printToString(maxDepth = 25),
                )
                listOf(echo, add, sleep).forEach { card ->
                    assertTrue(
                        onAllNodesWithText(card.call.name).fetchSemanticsNodes().isNotEmpty(),
                        "${card.call.name} 的工具卡必须在屏上：\n" +
                            onRoot(useUnmergedTree = true).printToString(maxDepth = 25),
                    )
                }
                println("--- 真 core 语义树（四工具 + 审批卡）--------------------------------")
                println(onRoot(useUnmergedTree = true).printToString(maxDepth = 25))
            }
        } catch (t: Throwable) {
            failure = t
            throw t
        } finally {
            collecting?.cancel()
            pumpScope.cancel()
            runCatching { runBlocking { source?.disconnect() } }
            source = null
            // 「无残留」：先 SIGTERM（启动器的 finally 才会杀两个模块子进程），再兜底 SIGKILL。
            val handle = core
            if (handle != null) {
                handle.destroy()
                if (!handle.waitFor(20, TimeUnit.SECONDS)) {
                    handle.destroyForcibly()
                    handle.waitFor(10, TimeUnit.SECONDS)
                }
                core = null
                val residue = AssertionError(
                    "launcher 必须已退出（否则每次运行都留一个 core + 两个模块子进程）。" +
                        "stdout 尾部：\n" + lines.takeLast(20).joinToString("\n"),
                )
                if (failure == null) {
                    assertFalse(handle.isAlive, residue.message)
                } else if (handle.isAlive) {
                    failure.addSuppressed(residue)
                }
            }
        }
    }

    // ------------------------------------------------------------------ 装置

    /** 工具卡（**不含** `approval:<request_id>` 降级卡 —— 那是绑定失败的另一条路）。 */
    private fun toolCalls(chat: ChatState): List<ChatItem.Tool> =
        chat.items.filterIsInstance<ChatItem.Tool>().filterNot { it.call.id.startsWith("approval:") }

    /** 失败判词里的一行行事实：id / 名字 / 状态 / risk / 审批请求 / 参数摘要。 */
    private fun dump(chat: ChatState): String = chat.items.joinToString("\n") { item ->
        when (item) {
            is ChatItem.Message ->
                "  Message(role=${item.message.role}, id=${item.message.id}, " +
                    "state=${item.message.state}, text=${item.message.bodyText.take(60)})"
            is ChatItem.Tool ->
                "  Tool(id=${item.call.id}, name=${item.call.name}, state=${item.call.state}, " +
                    "risk=${item.call.risk}, approval=${chat.approvalRequestId(item.call.id)}, " +
                    "preview=${item.call.argsPreview})"
        }
    }

    /** 启动器脚本：**仓库里那一份**（`shots/t3-model-tools/core_model_echo_launcher.py`），不另存副本。 */
    private fun launcherScript(): File {
        val candidates = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .take(6)
            .map { File(it, "shots/t3-model-tools/core_model_echo_launcher.py") }
            .filter { it.isFile }
            .toList()
        return candidates.firstOrNull()
            ?: fail(
                "找不到 shots/t3-model-tools/core_model_echo_launcher.py" +
                    "（从 ${System.getProperty("user.dir")} 向上找了 6 层）",
            )
    }

    /** 等某一行 `{"stage":"..."}`；超时/子进程先死/启动器 fatal 都抛带取证的消息。 */
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
        fail("等 $stage 超过 ${timeoutMs}ms（死锁保险）：\n--- stdout ---\n${lines.joinToString("\n")}")
    }

    private fun IntField(line: String, key: String): Int =
        Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(line)?.groupValues?.get(1)?.toInt()
            ?: fail("启动器输出里没有 $key：$line")

    private fun StrField(line: String, key: String): String =
        Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(line)?.groupValues?.get(1)
            ?: fail("启动器输出里没有 $key：$line")

    private companion object {
        /** 与 `RealCoreE2ETest` / `RealCoreModulePageE2ETest` 同一套默认值（可用环境变量覆盖）。 */
        val PYTHON: String = System.getenv("VADE_PYTHON") ?: "D:/HermesWorkspace/Vade/Vade/.venv/Scripts/python.exe"
        val CORE_PKG_DIR: String = System.getenv("VADE_CORE_PKG_DIR") ?: "D:/HermesWorkspace/Vade/Vade/core"
        val PROTOCOL_PY_DIR: String =
            System.getenv("VADE_PROTOCOL_PY_DIR") ?: "D:/HermesWorkspace/Vade/Vade/protocol/core/python"
        val SDK_PY_DIR: String =
            System.getenv("VADE_SDK_PY_DIR") ?: "D:/HermesWorkspace/Vade/Vade/sdk/python"

        /**
         * 审批窗口。**故意远大于默认 15 s**：这条用例断言的是「卡还停在待批准」。
         * 15 s 窗口里 `ChatState.approvalOutcome` 会按 `expires_at` 读成 `TimedOut`（fail-closed，
         * 读时投影），一张本该 Pending 的卡会在渲染等待期间自己变灰 —— 那是时钟抖，不是缺陷。
         * 300 s 给「连、发、四卡、渲染」留足余量；回合停在 waiting_approval 是**预期**的
         * （浏览器不是合法 approver，本用例从不点批准）。
         */
        const val APPROVAL_TIMEOUT_S = 300

        const val SUBMIT_TEXT = "Run echo, add, sleep and boom, then summarise."
    }
}
