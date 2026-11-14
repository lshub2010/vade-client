package vade.ui.e2e

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import vade.ui.protocol.AttachParams
import vade.ui.protocol.BrowserPairing
import vade.ui.protocol.ContentPart
import vade.ui.protocol.DetachParams
import vade.ui.protocol.KtorTransportFactory
import vade.ui.protocol.SubmitParams
import vade.ui.protocol.TurnCompleted
import vade.ui.protocol.TurnDelta
import vade.ui.protocol.TurnQueued
import vade.ui.protocol.TurnStarted
import vade.ui.protocol.VadeClient
import vade.ui.protocol.VadeClientConfig
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeEvent
import vade.ui.protocol.VadeException
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 真链路端到端：本测试用 ProcessBuilder 启动 **真的 Python core**（uvicorn + 真会话库），
 * 用真的 `VadeClient`（Ktor WS + 真配对码 cookie 引导）连上去，跑完
 * `ui/attach → ui/submit → turn.* 事件流 → ui/detach`，并断言事件顺序与 usage 字段。
 *
 * 与 `VadeClientSmokeTest` 的区别：那个测试靠外部手动起 core（env 缺失时 Assume 跳过）；
 * 这个测试自己拉起 core —— 没有 env 时不跳过，core 起不来就是失败（不许 skip 变绿）。
 *
 * Provider：core 自己的测试注入方式 —— `vade.session._build_llm` 换成脚本化的
 * `MockLLMProvider`（core/tests/e2e/test_turn_lifecycle.py 的同类做法），绝不调外部 API。
 *
 * 环境变量（默认指向本机标准布局，可被脚本覆盖）：
 *   VADE_PYTHON            core 侧 venv 解释器
 *   VADE_CORE_PKG_DIR      含 vade 包的目录（core/）
 *   VADE_PROTOCOL_PY_DIR   含 vade_protocol 绑定的目录（protocol/core/python/）
 *
 * 数据目录：每次运行 `Files.createTempDirectory` 新建，用完删除 —— 绝不触碰真实会话库。
 */
class RealCoreE2ETest {

    companion object {
        val PYTHON: String =
            System.getenv("VADE_PYTHON") ?: "D:/HermesWorkspace/Vade/Vade/.venv/Scripts/python.exe"
        val CORE_PKG_DIR: String =
            System.getenv("VADE_CORE_PKG_DIR") ?: "D:/HermesWorkspace/Vade/Vade/core"
        val PROTOCOL_PY_DIR: String =
            System.getenv("VADE_PROTOCOL_PY_DIR") ?: "D:/HermesWorkspace/Vade/Vade/protocol/core/python"

        /** 与 LAUNCHER_TEMPLATE 里的脚本常量逐字对应 —— 断言依赖这种镜像关系。 */
        private const val ECHO_DELTA_1 = "E2E echo: scripted provider reply."
        private const val ECHO_DELTA_2 = " -- done."
        private const val USAGE_PROMPT = 9L
        private const val USAGE_COMPLETION = 12L
        private const val USAGE_TOTAL = 21L

        /**
         * 真 core 启动器。协议（stdout 行缓冲，一行一个 JSON 对象）：
         *   {"stage":"boot","data_dir":...}
         *   {"stage":"ready","port":N,"pair_code":"..."}   — uvicorn 已绑定、配对码已铸
         *   {"stage":"fatal","error":"..."}                — 任何启动失败
         * stderr 透传 uvicorn/traceback。进程被 e2e 测试在 finally 里 destroy。
         */
        private val LAUNCHER_TEMPLATE = """
            import argparse, asyncio, json, sys, threading, time, traceback
            from pathlib import Path

            CORE_DIR = r"@CORE_DIR@"
            PROTOCOL_DIR = r"@PROTOCOL_DIR@"
            sys.path.insert(0, PROTOCOL_DIR)
            sys.path.insert(0, CORE_DIR)

            def emit(obj):
                print(json.dumps(obj, ensure_ascii=False), flush=True)

            def main():
                ap = argparse.ArgumentParser()
                ap.add_argument("--data-dir", required=True)
                ap.add_argument("--echo-text", default="@ECHO_TEXT@")
                args = ap.parse_args()
                emit({"stage": "boot", "data_dir": args.data_dir})

                import vade.session as session_pkg
                from vade.llm.base import Delta
                from vade.llm.mock import MockLLMProvider

                usage = {"prompt_tokens": @USAGE_PROMPT@, "completion_tokens": @USAGE_COMPLETION@, "total_tokens": @USAGE_TOTAL@}

                def _build_llm(cfg, **kwargs):
                    # core 测试同款 provider 注入：脚本化 Mock，两段文本 delta，usage 挂最后一段。
                    return MockLLMProvider([[
                        Delta(text=args.echo_text),
                        Delta(text=" -- done.", usage=usage, finish_reason="stop"),
                    ]])

                session_pkg._build_llm = _build_llm

                import uvicorn
                from vade import __main__ as vade_main
                from vade.app import App, install_components, make_rpc_handler
                from vade.config import load_config
                from vade.identity.pairing import Purpose
                from vade.transport.server import CoreApp

                config = load_config(cli={
                    "core.data_dir": args.data_dir,
                    "core.listen": "127.0.0.1:0",
                    "dev.allow_all": True,
                })
                app = App(config)
                asyncio.run(app.startup())
                installed = install_components(app, strict=False)
                if "session" not in installed:
                    raise RuntimeError("session component did not install: " + str(sorted(installed)))
                pair_code = app.pairing.create(Purpose.BROWSER, ttl=600)

                core_app = CoreApp(
                    config=config,
                    identity=app.identity,
                    registry=app.registry,
                    pairing=app.pairing,
                    whitelist=app.whitelist,
                    make_crypto=vade_main.build_module_crypto(app),
                    on_rpc=make_rpc_handler(app),
                    policy_mode="allow_all",
                )
                server = uvicorn.Server(uvicorn.Config(
                    core_app, host="127.0.0.1", port=0, log_level="warning",
                    lifespan="off", proxy_headers=False,
                ))
                thread_ = threading.Thread(target=server.run, daemon=True)
                thread_.start()
                deadline = time.time() + 20
                while not server.started:
                    if time.time() > deadline or not thread_.is_alive():
                        raise RuntimeError("uvicorn did not start within 20s")
                    time.sleep(0.02)
                port = server.servers[0].sockets[0].getsockname()[1]
                emit({"stage": "ready", "port": port, "pair_code": pair_code})
                while thread_.is_alive():
                    time.sleep(0.2)
                return 0

            if __name__ == "__main__":
                try:
                    sys.exit(main())
                except SystemExit:
                    raise
                except BaseException:
                    emit({"stage": "fatal", "error": traceback.format_exc(limit=8)})
                    sys.exit(1)
        """.trimIndent()
    }

    /** 每个测试自己拉起的核心句柄；finally 里 kill()，断言进程真的死了（不留孤儿）。 */
    private var core: CoreHandle? = null

    @AfterTest
    fun killLeftoverCore() {
        core?.kill()
        core = null
    }

    // ------------------------------------------------------------------ 主链路

    @Test
    fun realCoreAttachSubmitStreamsOrderedTurnEvents() {
        val handle = bootCore(echoText = ECHO_DELTA_1)
        runBlocking {
            val client = VadeClient(
                VadeClientConfig(
                    host = "127.0.0.1",
                    port = handle.readyInfo.port,
                    browserSession = BrowserPairing(handle.readyInfo.pairCode, clientName = "w-e2e"),
                    requestTimeoutMs = 15_000,
                    keepaliveIntervalMs = null,
                ),
            )
            val received = Channel<VadeEvent>(Channel.UNLIMITED)
            val subscribed = CompletableDeferred<Unit>()
            val collector = CoroutineScope(Dispatchers.Default).launch {
                client.events
                    .onSubscription { subscribed.complete(Unit) }
                    .collect { received.trySend(it) }
            }
            try {
                subscribed.await()
                client.connect()
                assertTrue(client.awaitConnected(10_000), "real WS upgrade with cookie accepted")

                val attach = client.attach(AttachParams(sessionId = null, lastSeq = null))
                println("E2E-ATTACH session_id=${attach.sessionId} from_seq=${attach.fromSeq} snapshot=${attach.snapshot != null}")
                assertTrue(attach.sessionId.isNotBlank(), "core minted a session id")
                assertTrue(attach.snapshot != null, "fresh-session attach carries a snapshot")

                val clientMsgId = "e2e-${System.currentTimeMillis()}"
                val submit = client.uiSubmit(
                    SubmitParams(
                        sessionId = attach.sessionId,
                        clientMsgId = clientMsgId,
                        content = listOf(ContentPart.TextPart("端到端：你好，Vade。")),
                    ),
                )
                println("E2E-SUBMIT turn_id=${submit.turnId} queue_pos=${submit.queuePos}")

                val events = collectUntilTerminal(received, submit.turnId, deadlineMs = 30_000)
                println("E2E-EVENTS collected=${events.size}")
                events.forEachIndexed { i, e -> println("E2E-EVENT idx=$i seq=${e.seq} type=${e::class.simpleName} $e") }

                // ---- 断言事件顺序：turn.queued → turn.started → turn.delta(≥1) → turn.completed ----
                val queued = events.filterIsInstance<TurnQueued>()
                val started = events.filterIsInstance<TurnStarted>()
                val deltas = events.filterIsInstance<TurnDelta>()
                val completed = events.filterIsInstance<TurnCompleted>()
                assertTrue(queued.isNotEmpty(), "turn.queued arrived (got: ${types(events)})")
                assertTrue(started.isNotEmpty(), "turn.started arrived (got: ${types(events)})")
                assertTrue(deltas.isNotEmpty(), "turn.delta arrived (got: ${types(events)})")
                assertTrue(completed.isNotEmpty(), "turn.completed arrived (got: ${types(events)})")

                val queuedIdx = events.indexOf(queued.first())
                val startedIdx = events.indexOf(started.first())
                val firstDeltaIdx = events.indexOf(deltas.first())
                val completedIdx = events.indexOf(completed.first())
                assertTrue(
                    queuedIdx < startedIdx && startedIdx < firstDeltaIdx && firstDeltaIdx < completedIdx,
                    "order must be queued($queuedIdx) < started($startedIdx) < delta($firstDeltaIdx) < completed($completedIdx)",
                )
                // seq 是会话级严格 +1：turn.* 子序列的 seq 也必须严格递增（wire 证据）
                assertTrue(
                    queued.first().seq < started.first().seq &&
                        started.first().seq < deltas.first().seq &&
                        deltas.last().seq < completed.first().seq,
                    "seq strictly increasing across the turn.* subsequence",
                )

                // ---- turn_id 归属 ----
                assertEquals(submit.turnId, queued.first().payload.turnId, "queued turn matches submit reply")
                assertEquals(submit.turnId, completed.first().payload.turnId, "completed turn matches submit reply")
                assertTrue(deltas.all { it.payload.turnId == submit.turnId }, "all deltas belong to this turn")

                // ---- delta 内容（脚本化 provider 的确定文本）----
                val joined = deltas.joinToString("") { it.payload.text }
                assertEquals(
                    "$ECHO_DELTA_1$ECHO_DELTA_2",
                    joined,
                    "deltas concatenate to the scripted provider text",
                )
                assertTrue(deltas.first().payload.partIndex >= 0, "part_index parses")
                assertEquals("text", deltas.first().payload.kind, "delta kind parses as text")

                // ---- turn.completed.usage：存在且字段可解（c-data 曾在此静默兜底全零）----
                val usage = completed.first().payload.usage
                assertEquals("completed", completed.first().payload.state, "terminal state is exactly 'completed'")
                assertEquals("provider", usage.source, "usage.source must say the provider reported (never silent zeros)")
                assertEquals(USAGE_PROMPT, usage.promptTokens, "prompt_tokens round-trips through the real wire")
                assertEquals(USAGE_COMPLETION, usage.completionTokens, "completion_tokens round-trips")
                assertEquals(USAGE_TOTAL, usage.totalTokens, "total_tokens round-trips")
                assertEquals(USAGE_PROMPT + USAGE_COMPLETION, usage.totalTokens, "total == prompt + completion")
                assertEquals(1, usage.steps, "one LLM step for a single scripted call")

                // ---- ui/detach 收尾 ----
                val detached = client.uiDetach(DetachParams(sessionId = attach.sessionId))
                assertEquals(true, detached.ok, "ui/detach ok on the real core")
            } finally {
                runCatching { collector.cancel() }
                runCatching { client.close() }
            }
        }
        // 客户端已关：核心进程必须还能被干净杀掉（不留孤儿 python）
        handle.kill()
        assertTrue(!handle.isAlive, "core process terminated after the test")
    }

    // ------------------------------------------------------------------ 失败路径

    @Test
    fun coreCrashBeforeReadySurfacesExitCodeAndLeavesNoOrphan() {
        val dir = newTempDir()
        val launcher = dir.resolve("crash_launcher.py")
        // 一个会自己退出的假 core：启动成功路径走不到 READY
        launcher.writeText(
            """
            import sys, time
            sys.stderr.write("simulated: core crashed during boot\n")
            time.sleep(0.1)
            sys.exit(3)
            """.trimIndent(),
        )
        val handle = CoreHandle(python = PYTHON, launcher = launcher, dataDir = dir.resolve("data"))
        core = handle
        handle.start()
        val err = assertFailsWith<CoreBootException> { handle.awaitReady(timeoutMs = 15_000) }
        assertTrue(
            "exited before READY" in err.message!!,
            "error names the early exit: ${err.message}",
        )
        assertTrue(err.message!!.contains("exit=3"), "error carries the child exit code: ${err.message}")
        handle.kill()
        assertTrue(!handle.isAlive, "nothing left running after a boot crash")
    }

    @Test
    fun coreBootTimeoutKillsTheProcessInsteadOfHanging() {
        val dir = newTempDir()
        val launcher = dir.resolve("sleep_launcher.py")
        // 永远不打印 READY 的假 core：等待必须有上限而不是挂死
        launcher.writeText("import time\ntime.sleep(300)\n")
        val handle = CoreHandle(python = PYTHON, launcher = launcher, dataDir = dir.resolve("data"))
        core = handle
        handle.start()
        val began = System.nanoTime()
        val err = assertFailsWith<CoreBootException> { handle.awaitReady(timeoutMs = 4_000) }
        val elapsedMs = (System.nanoTime() - began) / 1_000_000
        assertTrue(elapsedMs < 10_000, "boot wait is bounded (${elapsedMs}ms), not a hang")
        assertTrue(
            err.message!!.contains("did not print READY within 4000ms"),
            "error names the timeout: ${err.message}",
        )
        handle.kill()
        assertTrue(!handle.isAlive, "the timed-out core was killed (no orphan python)")
    }

    @Test
    fun wrongPortFailsFastWithClearErrorAndNoPythonAtAll() {
        // 占住一个端口但那不是 core：连接必须失败且报错可读
        val decoy = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        try {
            runBlocking {
                val client = VadeClient(
                    VadeClientConfig(
                        host = "127.0.0.1",
                        port = decoy.localPort,
                        browserSession = BrowserPairing("ZZZZ-ZZZZZZ", clientName = "w-e2e-wrong-port"),
                        requestTimeoutMs = 3_000,
                        reconnectBaseMs = 200,
                        reconnectCapMs = 500,
                        keepaliveIntervalMs = null,
                    ),
                )
                try {
                    client.connect()
                    val connected = client.awaitConnected(2_500)
                    assertEquals(false, connected, "a non-WS listener must never report Connected")
                    val err = assertFailsWith<VadeException> {
                        client.attach(AttachParams(sessionId = null, lastSeq = null))
                    }
                    assertTrue(
                        "not connected" in err.message!!,
                        "rpc against a dead transport explains itself: ${err.message}",
                    )
                } finally {
                    runCatching { client.close() }
                }
            }
        } finally {
            runCatching { decoy.close() }
        }
    }

    @Test
    fun wrongPairCodeSurfacesAuthFailedFromUiSession() {
        val handle = bootCore(echoText = "unused")
        runBlocking {
            // 直接打引导层：client.connect() 会把错误吞进重试循环，这里要求错误原地浮出
            val factory = KtorTransportFactory(
                VadeClientConfig(
                    host = "127.0.0.1",
                    port = handle.readyInfo.port,
                    browserSession = BrowserPairing("ZZZZ-ZZZZZZ", clientName = "w-e2e-bad-code"),
                    requestTimeoutMs = 5_000,
                ),
            )
            try {
                val err = assertFailsWith<VadeException> { factory.connect() }
                assertEquals(VadeErrorCodes.AUTH_FAILED, err.code, "pair-code rejection maps to AUTH_FAILED")
                assertTrue("HTTP" in err.message!!, "error carries the HTTP status: ${err.message}")
            } finally {
                runCatching { factory.dispose() }
            }
        }
        handle.kill()
        assertTrue(!handle.isAlive, "core killed after the auth failure path")
    }

    @Test
    fun requestBudgetExpiresWithTimeoutCodeWhenCoreNeverReplies() {
        // 一个只完成 WS 升级、之后永不回帧的假 core：客户端预算必然耗尽，
        // 且耗尽必须以 TIMEOUT 原地浮出（这是客户端自己超时机制的真实验证）。
        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val accepter = thread(isDaemon = true, name = "e2e-quiet-ws") {
            val socket = runCatching { server.accept() }.getOrNull() ?: return@thread
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
                var key: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    Regex("(?i)sec-websocket-key:\\s*(.+)").find(line)?.let { key = it.groupValues[1].trim() }
                }
                if (key != null) {
                    val accept = java.util.Base64.getEncoder().encodeToString(
                        java.security.MessageDigest.getInstance("SHA-1")
                            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)),
                    )
                    val response = "HTTP/1.1 101 Switching Protocols\r\n" +
                        "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: " + accept + "\r\n\r\n"
                    socket.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
                    socket.getOutputStream().flush()
                }
                // 沉默 30s：任何合理的客户端预算都会先耗尽
                Thread.sleep(30_000)
            } finally {
                runCatching { socket.close() }
            }
        }
        runBlocking {
            val client = VadeClient(
                VadeClientConfig(
                    host = "127.0.0.1",
                    port = server.localPort,
                    browserSession = null, // 静默套接字不做 /ui/session 引导，直接走升级
                    requestTimeoutMs = 500,
                    reconnectBaseMs = 200,
                    keepaliveIntervalMs = null,
                ),
            )
            try {
                client.connect()
                assertTrue(client.awaitConnected(5_000), "the quiet server did complete the WS upgrade")
                val err = assertFailsWith<VadeException> {
                    client.attach(AttachParams(sessionId = null, lastSeq = null))
                }
                assertEquals(VadeErrorCodes.TIMEOUT, err.code, "budget exhaustion maps to TIMEOUT")
                assertTrue("no reply within 500ms" in err.message!!, "error names the budget: ${err.message}")
            } finally {
                runCatching { client.close() }
            }
        }
        server.close()
        assertTrue(server.isClosed, "decoy socket closed")
        accepter.join(1_000)
    }

    // ------------------------------------------------------------------ 支撑

    /** 写 launcher（含镜像常量）→ 起 core → 等 READY（deadline 内轮询）。 */
    private fun bootCore(echoText: String): CoreHandle {
        val dir = newTempDir()
        val launcher = dir.resolve("e2e_core_launcher.py")
        launcher.writeText(
            LAUNCHER_TEMPLATE
                .replace("@CORE_DIR@", CORE_PKG_DIR)
                .replace("@PROTOCOL_DIR@", PROTOCOL_PY_DIR)
                .replace("@ECHO_TEXT@", echoText)
                .replace("@USAGE_PROMPT@", USAGE_PROMPT.toString())
                .replace("@USAGE_COMPLETION@", USAGE_COMPLETION.toString())
                .replace("@USAGE_TOTAL@", USAGE_TOTAL.toString()),
        )
        val handle = CoreHandle(python = PYTHON, launcher = launcher, dataDir = dir.resolve("data"))
        core = handle
        handle.start()
        handle.awaitReady(timeoutMs = 30_000)
        println("E2E-CORE port=${handle.readyInfo.port} pair_code=${handle.readyInfo.pairCode} data_dir=${handle.dataDir.absolutePath}")
        return handle
    }

    /** 收集事件直到 terminal（completed/failed/interrupted for turnId）或超时 —— 每次接收都带 deadline。 */
    private suspend fun collectUntilTerminal(
        channel: Channel<VadeEvent>,
        turnId: String,
        deadlineMs: Long,
    ): List<VadeEvent> {
        val out = mutableListOf<VadeEvent>()
        val end = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < end) {
            val next = withTimeoutOrNull((end - System.currentTimeMillis()).coerceAtLeast(1)) {
                channel.receive()
            } ?: break
            out += next
            val terminal = next is TurnCompleted && next.payload.turnId == turnId
            if (terminal) return out
        }
        fail("no terminal event for turn $turnId within ${deadlineMs}ms; got ${types(out)}")
    }

    private fun types(events: List<VadeEvent>): String = events.joinToString(",") { it::class.simpleName ?: "?" }

    private fun newTempDir(): File = Files.createTempDirectory("vade-e2e-core-").toFile()
}

/** core 启动失败 / 超时 —— message 必须可读，stderr 尾巴带上供诊断。 */
class CoreBootException(message: String, val stderrTail: String) : RuntimeException(message)

/** 真 core 子进程的句柄：READY 行解析、deadline 轮询、两段式 kill、孤儿检查。 */
class CoreHandle(private val python: String, private val launcher: File, val dataDir: File) {

    data class Ready(val port: Int, val pairCode: String)

    private var process: Process? = null
    private val stdoutLines = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val stderrText = StringBuilder()

    @Volatile
    var ready: Ready? = null
        private set

    /** awaitReady 成功后的非空视图。 */
    val readyInfo: Ready
        get() = ready ?: error("core is not ready (awaitReady not called or it failed)")

    val isAlive: Boolean
        get() = process?.isAlive == true

    fun start() {
        val pb = ProcessBuilder(python, launcher.absolutePath, "--data-dir", dataDir.absolutePath)
        // pair_code 含汉字 + json.dumps(ensure_ascii=False)：子进程 IO 强制 UTF-8，别赌 locale
        pb.environment()["PYTHONUTF8"] = "1"
        pb.environment()["PYTHONIOENCODING"] = "utf-8"
        pb.redirectErrorStream(false)
        process = pb.start()
        val p = process!!
        thread(isDaemon = true, name = "e2e-core-stdout") {
            try {
                BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8)).useLines { lines ->
                    lines.forEach { line ->
                        stdoutLines.add(line)
                        parseStage(line)
                    }
                }
            } catch (_: Exception) {
                // stream closed by kill(): 正常收尾
            }
        }
        thread(isDaemon = true, name = "e2e-core-stderr") {
            try {
                BufferedReader(InputStreamReader(p.errorStream, Charsets.UTF_8)).useLines { lines ->
                    lines.forEach { line ->
                        synchronized(stderrText) {
                            stderrText.append(line).append('\n')
                            if (stderrText.length > 8000) stderrText.delete(0, stderrText.length - 8000)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun parseStage(line: String) {
        if ("\"stage\"" !in line) return
        when {
            "\"ready\"" in line -> {
                val port = Regex("\"port\"\\s*:\\s*(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
                val code = Regex("\"pair_code\"\\s*:\\s*\"([^\"]*)\"").find(line)?.groupValues?.get(1)
                if (port != null && code != null) ready = Ready(port, code)
            }
        }
    }

    /** 轮询 + 硬 deadline：READY / 进程退出 / fatal 行 / 超时，四种出口都有明确报错。 */
    fun awaitReady(timeoutMs: Long): Ready {
        val p = process ?: fail("core was never started")
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < end) {
            ready?.let { return it }
            if (!p.isAlive) {
                throw CoreBootException(
                    "core exited before READY (exit=${p.exitValue()}); " + firstFatalOrEmpty(),
                    stderrTail(),
                )
            }
            firstFatalOrEmpty().takeIf { it.isNotEmpty() }?.let {
                throw CoreBootException("core reported fatal while booting; $it", stderrTail())
            }
            Thread.sleep(50) // 轮询间隔，有 deadline 兜底
        }
        throw CoreBootException(
            "core did not print READY within ${timeoutMs}ms (data_dir=${dataDir.absolutePath}); " + firstFatalOrEmpty(),
            stderrTail(),
        )
    }

    private fun firstFatalOrEmpty(): String =
        synchronized(stdoutLines) {
            stdoutLines.firstOrNull { "\"fatal\"" in it }?.take(500).orEmpty()
        }

    private fun stderrTail(): String = synchronized(stderrText) {
        stderrText.toString().takeLast(1200)
    }

    /** destroy → 等待 → destroyForcibly → 断言死透 → 清临时目录。幂等。 */
    fun kill() {
        val p = process ?: return
        if (!p.isAlive) {
            cleanupTempDir()
            return
        }
        p.destroy()
        if (!p.waitFor(3, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            p.waitFor(2, TimeUnit.SECONDS)
        }
        if (p.isAlive) {
            fail("python core process refused to die (pid=${p.pid()}) — orphan risk")
        }
        cleanupTempDir()
    }

    /** Windows 上 sqlite WAL 句柄在进程死后可能短暂滞留：删除重试三次。 */
    private fun cleanupTempDir() {
        val root = dataDir.parentFile ?: return
        repeat(3) {
            if (root.deleteRecursively()) return
            Thread.sleep(250)
        }
        // best effort：清不掉就留给 OS 的 temp 清理，不影响判定
        println("E2E-WARN temp dir not removed: ${root.absolutePath}")
    }
}
