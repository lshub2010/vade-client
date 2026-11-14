package vade.ui.e2e

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 证明「180 s 不是慢慢等的借口，诊断不是装饰」——用**必定启动失败**的命令跑真
 * [CoreHandle] 的同一条失败路径，外加一条纯函数的判词分档。
 *
 * 四条注入 + 一条口径：
 *  1. 解释器路径不存在（`ProcessBuilder.start()` 自己就抛）→ 必须**立刻**报 CoreBootException，
 *     而不是 180 s 后超时，也不是裸 IOException；
 *  2. launcher 立刻 `sys.exit(7)` 并往 stderr 写一行 → 必须远小于 180 s 就失败，
 *     且消息里带 exit 码、stderr 尾部、stdout 尾部、命令行、已等时长；
 *  3. launcher 永不打印 READY，但调用方只给 5 s → 必须在自己的预算上失败，
 *     且提示语必须说明「这是调用方预算，不是生产上限」，不能把 5 s 说成机器负载。
 *  4. `verdict` 分档（合成耗时，秒级）：<12 s 判 `CORE-FAIL-EARLY`、>25 s 判 `LIKELY-LOAD` ——
 *     这条锁住「下次红一眼分清机器忙还是 core 崩」，也锁住 spawn 耗时不许并进等待。
 *
 * 每条都断言 elapsed 远小于 180 s：如果哪天有人把 180 s 当 sleep 用，这里先红。
 */
class CoreBootDiagnosticsTest {

    private var handle: CoreHandle? = null

    @AfterTest
    fun killLeftover() {
        handle?.kill()
        handle = null
    }

    private fun launcherWith(body: String): Pair<File, File> {
        val dir = Files.createTempDirectory("vade-e2e-diag-").toFile()
        val launcher = dir.resolve("diag_launcher.py")
        launcher.writeText(body.trimIndent())
        return dir to launcher
    }

    /** 诊断消息必须具备的全部字段（少一个就当失败——这就是本棒的交付物）。 */
    private fun assertHasForensics(msg: String, expectedCommandFragment: String) {
        assertTrue("elapsed   :" in msg, "message must carry the elapsed time:\n$msg")
        assertTrue("ms budget (deadlock guard" in msg, "message must carry the budget it compared against:\n$msg")
        assertTrue("spawn     :" in msg, "message must carry the spawn cost:\n$msg")
        assertTrue("command   :" in msg, "message must carry the full spawn command line:\n$msg")
        assertTrue(
            expectedCommandFragment in msg,
            "message must carry the actual command (missing '$expectedCommandFragment'):\n$msg",
        )
        assertTrue("data_dir  :" in msg, "message must carry the data dir:\n$msg")
        assertTrue("child     :" in msg, "message must carry the child state/exit:\n$msg")
        assertTrue("machine   :" in msg, "message must carry the machine/process snapshot:\n$msg")
        assertTrue("cpus=" in msg, "process snapshot must include cpu count:\n$msg")
        // 进程数：要么是真数字，要么是显式的「不可得 + 原因」——绝不允许静默缺字段或编 0。
        val javaCount = Regex("""javaProcs=(\d+|n/a)""").find(msg)
        val pyCount = Regex("""pythonProcs=(\d+|n/a)""").find(msg)
        assertTrue(javaCount != null, "process snapshot must carry javaProcs=<count|n/a>:\n$msg")
        assertTrue(pyCount != null, "process snapshot must carry pythonProcs=<count|n/a>:\n$msg")
        if ("n/a" in javaCount!!.value || "n/a" in pyCount!!.value) {
            assertTrue(
                "NOT collected" in msg,
                "an unavailable count must say why (never a silent 0):\n$msg",
            )
        }
        assertTrue("stdout tail" in msg, "message must carry a stdout tail section:\n$msg")
        assertTrue("stderr tail" in msg, "message must carry a stderr tail section:\n$msg")
        assertTrue("verdict   :" in msg, "message must carry a one-line conclusion verdict:\n$msg")
        // 「一眼能分清」的字面要求：定性必须在取证包**之前**，否则 80 行尾部输出会把结论埋掉。
        val verdictAt = msg.indexOf("verdict   :")
        val sectionAt = msg.indexOf("--- core boot diagnostics")
        assertTrue(verdictAt in 0 until sectionAt, "verdict must come before the dump, not after it:\n$msg")
    }

    // ---------------------------------------------------------------- 1. 解释器不存在

    @Test
    fun missingInterpreterFailsImmediatelyWithFullDiagnostics() {
        val (dir, launcher) = launcherWith("print('never runs')\n")
        val bogusPython = dir.resolve("python-that-does-not-exist.exe").absolutePath
        val created = CoreHandle(python = bogusPython, launcher = launcher, dataDir = dir.resolve("data"))
        handle = created
        val began = System.nanoTime()
        created.start()
        val err = assertFailsWith<CoreBootException> { created.awaitReady() } // 默认 180 s 预算
        val elapsedMs = (System.nanoTime() - began) / 1_000_000

        // 关键：spawn 失败必须在毫秒级被识别，绝不能被 180 s 掩盖。
        // （墙钟可能更长：后台进程采样器冷启一次 5–10 s，那是诊断成本，不是等待。）
        assertTrue(
            err.elapsedMs < 5_000,
            "spawn failure must be detected immediately, not after a budget wait (waited ${err.elapsedMs}ms)",
        )
        assertTrue(elapsedMs < 30_000, "spawn failure must not hang the test (took ${elapsedMs}ms)")
        assertTrue(
            "could not be spawned" in err.message!!,
            "message must name the spawn failure: ${err.message}",
        )
        assertHasForensics(err.message!!, "python-that-does-not-exist.exe")
        // 证据：把真实消息打进 stdout（JUnit XML 的 system-out），报告里引用的就是这一段。
        println("DIAG-SAMPLE spawn-failure >>>\n${err.message}\n<<< DIAG-SAMPLE spawn-failure")
        assertTrue(
            "not spawned" in err.message!!,
            "child section must say nothing was spawned: ${err.message}",
        )
        assertTrue(err.commandLine.contains("python-that-does-not-exist.exe"), "exception exposes commandLine")
        assertTrue(!created.isAlive, "nothing left running after a spawn failure")
        created.kill() // 幂等：未 spawn 时不得抛
    }

    // ---------------------------------------------------------------- 2. 立刻 sys.exit(7)

    @Test
    fun launcherExitingImmediatelyFailsFastWithTailEvidence() {
        val (dir, launcher) = launcherWith(
            """
            import json, sys
            print(json.dumps({"stage": "boot", "data_dir": "injected"}), flush=True)
            sys.stderr.write("BOOM: injected missing dependency (vade.llm.nothere)\n")
            sys.stderr.flush()
            sys.exit(7)
            """,
        )
        val created = CoreHandle(
            python = RealCoreE2ETest.PYTHON,
            launcher = launcher,
            dataDir = dir.resolve("data"),
        )
        handle = created
        val began = System.nanoTime()
        created.start()
        val err = assertFailsWith<CoreBootException> { created.awaitReady() }
        val elapsedMs = (System.nanoTime() - began) / 1_000_000

        val msg = err.message!!
        // 不是「等 180 s 才失败」：注入的故障在秒级被识别
        assertTrue(err.elapsedMs < 10_000, "an instantly dying launcher must be noticed at once (waited ${err.elapsedMs}ms)")
        assertTrue(elapsedMs < 40_000, "an instantly dying launcher must not hang the test (took ${elapsedMs}ms)")
        assertTrue("exited before READY" in msg, "headline must name the early exit:\n$msg")
        assertTrue("exit=7" in msg, "headline AND child section must carry the exit code:\n$msg")
        assertTrue(
            "BOOM: injected missing dependency" in msg,
            "stderr tail must carry the child's actual complaint:\n$msg",
        )
        assertTrue(
            "\"stage\": \"boot\"" in msg,
            "stdout tail must carry the protocol line the child did print:\n$msg",
        )
        assertHasForensics(msg, "diag_launcher.py")
        // 证据：真实注入故障（python 立刻 exit 7）的完整消息。
        println("DIAG-SAMPLE exit-7 >>>\n$msg\n<<< DIAG-SAMPLE exit-7")
        // 结论提示必须数据驱动：秒级失败 → 明确排除机器负载
        assertTrue("NOT machine load" in msg, "verdict must rule out load for a fast failure:\n$msg")
        assertTrue(err.elapsedMs < 10_000, "exception field elapsedMs reports the short wait: ${err.elapsedMs}ms")
        assertTrue(err.stderrTail.contains("BOOM"), "exception exposes stderrTail")
        assertTrue(!created.isAlive, "injected crash must leave no process behind")
    }

    // ---------------------------------------------------------------- 3. 小预算要如实说

    @Test
    fun neverPrintingLauncherFailsAtItsOwnBudgetNotAtTheCeiling() {
        val (dir, launcher) = launcherWith(
            """
            import time
            time.sleep(300)
            """,
        )
        val created = CoreHandle(
            python = RealCoreE2ETest.PYTHON,
            launcher = launcher,
            dataDir = dir.resolve("data"),
        )
        handle = created
        val began = System.nanoTime()
        created.start()
        val err = assertFailsWith<CoreBootException> { created.awaitReady(timeoutMs = 5_000) }
        val elapsedMs = (System.nanoTime() - began) / 1_000_000

        val msg = err.message!!
        assertTrue(
            err.elapsedMs in 4_500..8_000,
            "5s budget must fire at ~5s, not at 180s (waited ${err.elapsedMs}ms)",
        )
        assertTrue(elapsedMs < 40_000, "the bounded wait must not hang the test (took ${elapsedMs}ms)")
        assertTrue("did not print READY within 5000ms" in msg, "headline must name the budget:\n$msg")
        assertHasForensics(msg, "diag_launcher.py")
        assertTrue(
            "CALLER-SUPPLIED budget of 5000ms" in msg,
            "verdict must say a 5s run was cut short by the test, NOT blame machine load:\n$msg",
        )
        assertTrue("alive=true" in msg, "child section must show the hung child is still alive:\n$msg")
        assertTrue(err.timeoutMs == 5_000L, "exception exposes the budget used")
    }

    // ---------------------------------------------------------------- 4. 判词分档（纯函数，秒级）

    /**
     * 「下次红一眼能分清机器忙还是 core 崩」的**可执行版本**。
     *
     * 直接对 [CoreDiagnostics.formatBootFailure] 喂合成耗时（真起 180 s 的进程不现实），
     * 断言 `verdict   :` 的标签在每档都对 —— 尤其 `LIKELY-LOAD`（机器忙）与
     * `CORE-FAIL-EARLY`（真崩）不能互换，否则这条诊断等于装饰。
     */
    @Test
    fun verdictSeparatesLoadFromCrashWithoutReadingTheTails() {
        val dir = Files.createTempDirectory("vade-e2e-verdict-").toFile()
        try {
            val launcher = dir.resolve("diag_launcher.py").apply { writeText("pass\n") }
            val dataDir = dir.resolve("data")
            fun msg(
                elapsedMs: Long,
                timeoutMs: Long,
                hitCeiling: Boolean,
                spawnMs: Long = 0L,
                spawnFailed: Boolean = false,
            ): String = CoreDiagnostics.formatBootFailure(
                headline = "synthetic failure for classification only",
                elapsedMs = elapsedMs,
                timeoutMs = timeoutMs,
                command = CoreDiagnostics.commandLineOf(RealCoreE2ETest.PYTHON, launcher, dataDir),
                dataDir = dataDir,
                childState = "alive=true exit=n/a pid=1",
                stdoutTail = "",
                stdoutLines = 0,
                stderrTail = "Traceback (most recent call last):",
                stderrLines = 1,
                processSnapshot = "cpus=16 loadAvg=n/a(Windows) javaProcs=3 pythonProcs=4 unreadable=0 of 100",
                hitCeiling = hitCeiling,
                spawnMs = spawnMs,
                spawnFailed = spawnFailed,
            )

            // ① < 12 s：真崩（读 stderr），并且必须明确排除负载
            val early = msg(3_000, 180_000, hitCeiling = false)
            assertTrue("verdict   : CORE-FAIL-EARLY — " in early, "fast failure must be classified as a crash:\n$early")
            assertTrue("NOT machine load" in early, "fast failure must rule out load:\n$early")

            // ② > 25 s：机器忙 —— 这就是「一眼分清」的那一半
            val slow = msg(26_000, 180_000, hitCeiling = false)
            assertTrue("verdict   : LIKELY-LOAD — " in slow, "a >25s failure must be classified as load:\n$slow")
            assertTrue("MACHINE LOAD" in slow, "load verdict must say so in words:\n$slow")
            assertHasForensics(slow, "diag_launcher.py") // 合成消息也要带全套取证字段

            // ③ 12–25 s 之间不许冒充结论
            val mid = msg(18_000, 180_000, hitCeiling = false)
            assertTrue("verdict   : INCONCLUSIVE — " in mid, "the 12-25s band must stay inconclusive:\n$mid")

            // ④ 等满 180 s 上限：死锁保险被打穿
            val ceiling = msg(180_000, 180_000, hitCeiling = true)
            assertTrue("verdict   : CEILING-HIT — " in ceiling, "a spent ceiling must say so:\n$ceiling")

            // ⑤ 调用方自设 5 s 预算：必须说成「测试自己砍短的」，不许赖负载
            val caller = msg(5_000, 5_000, hitCeiling = true)
            assertTrue("verdict   : CALLER-BUDGET — " in caller, "a caller budget must not read as load:\n$caller")
            assertTrue("CALLER-SUPPLIED budget of 5000ms" in caller, "caller budget must be named:\n$caller")

            // ⑥ ProcessBuilder.start() 自己抛：既不是 core 崩也不是负载
            val spawn = msg(12, 180_000, hitCeiling = false, spawnFailed = true)
            assertTrue("verdict   : SPAWN-FAIL — " in spawn, "a spawn failure has its own verdict:\n$spawn")
            assertTrue("NOT machine load" in spawn, "spawn failure must rule out load:\n$spawn")
            assertTrue("NOT the core" in spawn, "spawn failure must rule out the core:\n$spawn")

            // ⑦ spawn 耗时是**独立**证据：>10 s 单独成句，且不并进「等了多久」
            val slowSpawn = msg(26_000, 180_000, hitCeiling = false, spawnMs = 12_000)
            assertTrue("[spawn alone took 12000ms" in slowSpawn, "a slow spawn must be called out separately:\n$slowSpawn")
            assertTrue(
                "waited 26000ms of 180000ms budget" in slowSpawn,
                "the wait figure must NOT absorb the spawn cost:\n$slowSpawn",
            )
            // 正常 spawn 不刷这句噪音
            assertTrue(
                "spawn alone took" !in msg(3_000, 180_000, hitCeiling = false, spawnMs = 8),
                "a normal spawn cost must not add a load clause",
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------- 5. 预算口径本身

    @Test
    fun budgetIsDeadlockInsuranceNotAPerformanceTarget() {
        // 180 s 必须显著高于实测最长（37.7 s）；若有人把它缩回 30 s 这一条先红。
        assertEquals(180_000L, CoreBootBudget.READY_TIMEOUT_MS, "READY ceiling is 180s of deadlock insurance")
        assertTrue(
            CoreBootBudget.READY_TIMEOUT_MS >= 4 * 37_700L,
            "ceiling must stay >=4x the worst measured boot (37700ms), else it is a performance assertion again",
        )
        assertTrue(
            CoreBootBudget.READY_TIMEOUT_MS > CoreBootBudget.SLOW_MS,
            "ceiling must sit above the contention band it guards",
        )
        assertTrue(
            CoreBootBudget.TAIL_LINES in 10..100,
            "tail line budget must stay readable: ${CoreBootBudget.TAIL_LINES}",
        )
        assertTrue(
            CoreBootBudget.WAIT_TIMEOUT_MS >= 120_000L,
            "post-READY waits must use the same deadlock-guard posture, not a 20/30/45s performance budget",
        )
    }
}
