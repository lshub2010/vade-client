package vade.ui.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import vade.ui.App
import vade.ui.data.RealSessionSource
import vade.ui.data.RealSessionState
import vade.ui.shell.UiState
import vade.ui.protocol.BrowserPairing
import vade.ui.protocol.VadeClientConfig
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * g-live · THE machine evidence for alpha A1/W4: a **real Python core** (uvicorn +
 * real SQLite session store) and the **composed app** running against it, end to end.
 *
 * Chain under test (every link real):
 *   launcher spawns core → `{"stage":"ready","port":N,"pair_code":"…"}` on stdout
 *   → `POST /ui/session` pair-code bootstrap → `__Host-ac_ui` cookie
 *   → `ws://…/ws` upgrade with the cookie → `session/list` RPC
 *   → [UiState]'s loader pulls rows → the COMPOSED `App` renders the core's real
 *   session titles and NOT a single fixture marker.
 *
 * The stale desktop comment claimed this flag "changes nothing visible" — this test
 * is the proof that it does (and that the wire works without any mock transport).
 *
 * The core is seeded with two demo sessions by the launcher (see
 * shots/g-live/core_launcher.py — the same script, standalone for humans); the pair
 * code printed on stdout stays inside this process and is NEVER logged by assertions.
 *
 * No env = no skip: the core failing to boot is a failure, never a green pass.
 */
@OptIn(ExperimentalTestApi::class)
class RealCoreUiE2ETest {

    private var handle: CoreHandle? = null

    @AfterTest
    fun killLeftoverCore() {
        handle?.kill()
        handle = null
    }

    /** Boot the real core; READY line parsed by [CoreHandle] (port + pair code). */
    private fun bootCore(): CoreHandle.Ready {
        val dir = Files.createTempDirectory("vade-g-live-ui-").toFile()
        val launcher = dir.resolve("g_live_ui_launcher.py")
        launcher.writeText(
            LAUNCHER
                .replace("@CORE_DIR@", RealCoreE2ETest.CORE_PKG_DIR)
                .replace("@PROTOCOL_DIR@", RealCoreE2ETest.PROTOCOL_PY_DIR),
        )
        val created = CoreHandle(
            python = RealCoreE2ETest.PYTHON,
            launcher = launcher,
            dataDir = dir.resolve("data"),
        )
        handle = created
        created.start()
        // 预算 = 死锁保险（180 s），不是性能断言：实测 12 路并发下 READY 中位 11.1–31.6 s、
        // 最长 37.7 s（shots/g-e2e-budget/）。旧的 30 s 正压在满负载中位数上。
        val ready = created.awaitReady(timeoutMs = CoreBootBudget.READY_TIMEOUT_MS)
        println("E2E-UI-CORE-READY spawnMs=${created.spawnMillis} waitMs=${created.waitedMillis} port=${ready.port}")
        return ready
    }

    /** RealSessionSource over the booted core, pair-code bootstrap, fast backoff. */
    private fun realSource(ready: CoreHandle.Ready): RealSessionSource =
        RealSessionSource(
            VadeClientConfig(
                host = "127.0.0.1",
                port = ready.port,
                browserSession = BrowserPairing(ready.pairCode, clientName = "g-live-w4"),
                requestTimeoutMs = 15_000,
                reconnectBaseMs = 200,
                reconnectCapMs = 1_000,
                // Keepalive ON (the 10s default): the core closes a browser socket
                // after 2×heartbeat of silence, and this test idles between the
                // wire sanity check and the composed UI — a killed socket here
                // would surface as a Failed banner and a flaky waitUntil.
            ),
        )

    @Test
    fun realCoreSessionTitlesRenderInAppAndFixturesStayAway() {
        val ready = bootCore()
        runBlocking {
            val source = realSource(ready)
            try {
                source.connect()
                withTimeout(CoreBootBudget.WAIT_TIMEOUT_MS) {
                    source.state.first { it is RealSessionState.Connected }
                }
                // The loader path (the same one the composition drives) first,
                // deterministically and WITH diagnostics on failure.
                val uiRows = uiStateRows(source)
                assertTrue(
                    SEEDED_TITLES.all { it in uiRows.map { row -> row.title } },
                    "the core must serve the seeded sessions through the loader (got: ${uiRows.map { it.title }})",
                )

                runComposeUiTest(testTimeout = 240.seconds) {
                    setContent { App(source = source) }
                    // 180 s = 死锁保险，不是「渲染预算」：真渲染通常 < 2 s，但在 12 路抢 CPU 时
                    // Compose 首次组合 + 真 WS 首帧曾被 45 s 吃掉（ComposeTimeoutException）。
                    // 条件驱动，节点一出现立刻返回。
                    //
                    // 外层 `timeout = 240s` 是 runComposeUiTest 自己的 runTest 看门狗（默认 60 s）：
                    // 必须**大于**下面的 180 s，否则框架会先抛 UncompletedCoroutinesError，
                    // 把一条带诊断的 ComposeTimeoutException 盖成「test body did not run to completion」。
                    try {
                        waitUntil(timeoutMillis = CoreBootBudget.WAIT_TIMEOUT_MS) {
                            onAllNodesWithText(SEEDED_TITLES.first()).fetchSemanticsNodes().isNotEmpty()
                        }
                    } catch (t: Throwable) {
                        // 取证：屏幕到底渲染了什么（语义树）+ 测试帧钟走过多少 —— 这两样能直接
                        // 区分「组合根本没跑」「跑了但显示错误/空列表」「渲染对了但文案不对」。
                        val dump = runCatching {
                            onRoot(useUnmergedTree = true).printToString(maxDepth = 25)
                        }.getOrElse { "semantics dump failed: ${it::class.simpleName}: ${it.message}" }
                        throw AssertionError(
                            composeFailureDiagnostics(source, t, mainClock.currentTime, dump),
                            t,
                        )
                    }
                    SEEDED_TITLES.forEach { title ->
                        onNodeWithText(title).assertExists()
                    }
                    // Not a single fixture marker may ride along on the loader path.
                    FIXTURE_MARKERS.forEach { marker ->
                        assertTrue(
                            onAllNodesWithText(marker).fetchSemanticsNodes().isEmpty(),
                            "fixture marker must be absent in real mode: $marker",
                        )
                    }
                }
                // The ids the UI pulled are the core-minted ones — none is a fixture id.
                uiRows.forEach { row ->
                    assertNotEquals("", row.id)
                    assertTrue(FIXTURE_IDS.none { it == row.id }, "no fixture id may appear: ${row.id}")
                }
            } finally {
                runCatching { source.disconnect() }
            }
        }
        handle?.kill()
        assertTrue(handle?.isAlive == false, "core terminated after the test")
    }

    /**
     * 组合 UI 超时的取证包：光有 `ComposeTimeoutException: Condition still not satisfied`
     * 分不清是「App 没拿到数据」还是「拿到数据但没渲染」。
     *
     * 这里补三件事，外加一句**可 grep 的定性**（`verdict   : RENDER-STALL` / `DATA-PATH-FAIL`）：
     *  1. `source.state` —— 共享的真源当时连没连上；
     *  2. 一个**独立探针** [uiStateProbe]：用同一个 source 新建一个 UiState（异步读路径），
     *     15 s 内能不能到 Ready、拿到几行 —— 它 Ready 而 App 没渲染，就是 UI 层的事；
     *  3. core 进程还活着没 + 完整命令行。
     */
    private fun composeFailureDiagnostics(
        source: RealSessionSource,
        cause: Throwable,
        mainClockTimeMs: Long,
        semanticsDump: String,
    ): String {
        val state = runCatching { source.state.value.toString() }.getOrElse { "unreadable: $it" }
        val probe = runCatching { uiStateProbe(source) }.getOrElse { "probe failed: $it" }
        val dataPathOk = probe.startsWith("phase=Ready")
        val verdict = if (dataPathOk) {
            "RENDER-STALL — the data path works (a fresh UiState on the SAME source reached Ready " +
                "within 15s with rows, core alive), so this is the composition/rendering segment, " +
                "NOT the READY budget and NOT a core crash. Read the semantics tree: an empty list " +
                "means rows never reached the composition; an error banner means the app's own loader " +
                "failed."
        } else {
            "DATA-PATH-FAIL — a fresh UiState on the same source could not reach Ready either, so the " +
                "shared wire is not delivering. This is a real failure (core or transport), not a " +
                "rendering/load timeout: read source state + the core's command line below."
        }
        return buildString {
            append("composed App never rendered the seeded title '").append(SEEDED_TITLES.first()).append("': ")
            append(cause::class.simpleName).append(": ").append(cause.message)
            append("\nverdict      : ").append(verdict)
            append("\n--- compose e2e diagnostics ----------------------------------------------")
            append("\nmainClock    : ").append(mainClockTimeMs).append("ms advanced")
            append("\nsource state : ").append(state)
            append("\nloader probe : ").append(probe)
            append("\ncore         : alive=").append(handle?.isAlive ?: false)
                .append(" command=").append(handle?.commandLine ?: "n/a")
            append("\nsemantics tree:")
            semanticsDump.lines().forEach { append("\n  | ").append(it) }
        }
    }

    /**
     * P1-3 · 探针 UiState 只**借**共享真源读一次。
     *
     * `UiState.dispose()` 现在会经 `DisconnectableSource` 真的断开它持有的源；把
     * [RealSessionSource] 本体传进探针，探针一结束就会把后面组合 UI 要用的连接一起关掉
     * （`uiStateRows` 就在 `App(source = source)` 之前）。包一层只转发 [SessionSource]
     * 的口：探针读的仍是同一个连接，但「可关停」被摘掉了 —— 连接的生命周期归测试管。
     */
    private class BorrowedSource(delegate: RealSessionSource) : vade.ui.data.SessionSource by delegate

    /** 用同一个真源新建一个 UiState，最多等 15 s 到 Ready，返回可读结论（不抛异常）。 */
    private fun uiStateProbe(source: RealSessionSource): String {
        val probe = UiState(
            initialBar = vade.ui.skin.BottomBarStyle.Fixed,
            initialSkinId = "material",
            initialDest = vade.ui.destinations.Destination.Sessions,
            source = BorrowedSource(source),
            loaderScope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
            ),
        )
        return try {
            val deadline = System.currentTimeMillis() + 15_000
            while (probe.sessionsPhase != vade.ui.shell.LoadPhase.Ready &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(50)
            }
            "phase=${probe.sessionsPhase} rows=${probe.sessionRows.map { it.title }} " +
                "error=${probe.sessionsError}"
        } finally {
            runCatching { probe.dispose() }
        }
    }

    /**
     * Drive [UiState] directly (the shell's async read path) and wait until its
     * loader reports Ready — the non-UI half of the same evidence.
     */
    private fun uiStateRows(source: RealSessionSource) =
        UiState(
            initialBar = vade.ui.skin.BottomBarStyle.Fixed,
            initialSkinId = "material",
            initialDest = vade.ui.destinations.Destination.Sessions,
            source = BorrowedSource(source),
            loaderScope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
            ),
        ).let { state ->
            try {
                val deadline = System.currentTimeMillis() + CoreBootBudget.WAIT_TIMEOUT_MS
                while (state.sessionsPhase != vade.ui.shell.LoadPhase.Ready) {
                    if (System.currentTimeMillis() > deadline) {
                        fail(
                            "UiState loader never reached Ready within ${CoreBootBudget.WAIT_TIMEOUT_MS}ms " +
                                "(phase=${state.sessionsPhase}, error=${state.sessionsError}, " +
                                "core=${handle?.commandLine ?: "n/a"})",
                        )
                    }
                    Thread.sleep(50)
                }
                state.sessionRows
            } finally {
                state.dispose()
            }
        }

    // ------------------------------------------------------------------ harness

    private companion object {
        /** Titles the launcher seeds into the real core (identical to core_launcher.py). */
        val SEEDED_TITLES = listOf("W4 实跑·真核心会话甲", "W4 实跑·真核心会话乙")

        /** Fixture-only strings that must NEVER appear once a real source drives the UI. */
        val FIXTURE_MARKERS = listOf(
            "RFC-0004 附件与多模态评审",
            "vade core 构建矩阵升级",
            "wasm 体积回归",
            "AGP 9.5.1 矩阵验证",
        )
        val FIXTURE_IDS = listOf("s-rfc0004", "s-matrix", "s-u1a", "s-skin11", "s-wasm", "s-agp")

        /**
         * Same launcher as `shots/g-live/core_launcher.py` (keep the two in sync):
         * real core, scripted MockLLM provider (never an external API), two seeded
         * sessions, one-time browser pair code minted and printed on the READY line.
         */
        private val LAUNCHER = """
            import argparse, asyncio, json, sys, threading, time, traceback
            from pathlib import Path

            CORE_DIR = r"@CORE_DIR@"
            PROTOCOL_DIR = r"@PROTOCOL_DIR@"
            sys.path.insert(0, PROTOCOL_DIR)
            sys.path.insert(0, CORE_DIR)

            SEED_TITLES = ["W4 实跑·真核心会话甲", "W4 实跑·真核心会话乙"]

            def emit(obj):
                print(json.dumps(obj, ensure_ascii=False), flush=True)

            def main():
                ap = argparse.ArgumentParser()
                ap.add_argument("--data-dir", required=True)
                ap.add_argument("--port", type=int, default=0)
                ap.add_argument("--no-seed", action="store_true")
                args = ap.parse_args()
                emit({"stage": "boot", "data_dir": args.data_dir})

                import vade.session as session_pkg
                from vade.llm.base import Delta
                from vade.llm.mock import MockLLMProvider

                def _build_llm(cfg, **kwargs):
                    return MockLLMProvider([[
                        Delta(text="W4 实跑：脚本化 provider 回复。"),
                        Delta(text=" -- done.", usage={"prompt_tokens": 9, "completion_tokens": 12, "total_tokens": 21}, finish_reason="stop"),
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
                    "core.listen": "127.0.0.1:" + str(args.port),
                    "dev.allow_all": True,
                })
                app = App(config)
                asyncio.run(app.startup())
                installed = install_components(app, strict=False)
                if "session" not in installed:
                    raise RuntimeError("session component did not install: " + str(sorted(installed)))
                if not args.no_seed:
                    for title in SEED_TITLES:
                        app.session.create_session(title=title)
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
                    core_app, host="127.0.0.1", port=args.port, log_level="warning",
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
}
