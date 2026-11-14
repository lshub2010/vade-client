package vade.ui.e2e

import java.io.File
import java.lang.management.ManagementFactory

/**
 * 两个真 core e2e（[RealCoreE2ETest] / [RealCoreUiE2ETest]）共用的「READY 预算 + 失败取证」。
 *
 * ## 为什么预算是 180 s（而不是 30 s）
 *
 * 30 s 曾把「真 core 从 `ProcessBuilder.start()` 到打印 `{"stage":"ready"}`」的**墙钟**当成
 * 正确性参数。实测（探针见 `shots/g-e2e-budget/`，同一台 16 核 Windows，两个探针互证）：
 *
 * | 场景 | 真实 launcher 到 READY | boot-only（不含 uvicorn 绑定） |
 * |---|---|---|
 * | 空载 | 2.3 – 7.3 s | 8.3 – 9.8 s |
 * | 12 路并发 | 中位 19.6 / 24.7 s，最长 26.1 s | 中位 19.6 / 20.1 s，最长 24.1 s |
 * | 更忙的机器（上一棒，12 路） | 中位 25.8 / 31.6 s，最长 **37.7 s** | 同上 |
 *
 * boot 的内部构成说明这不是「import 慢」：`import vade.session + uvicorn` 只占
 * 0.15 – 0.23 s，其余全在 `App.startup()` + `install_components()` + SQLite 初始化；
 * 12 路并发把整段 boot 放大 ~2.2–3×。
 *
 * 于是 30 s 死线**落在满负载的中位数上**——一半以上的运行必然红（历史现象正是
 * 「满负载必现、隔离跑全绿」）。180 s ≈ 4.5× 实测最长值，它的定位是：
 *
 * > **死锁保险**：正常 boot 仍然是**条件驱动**（读到 READY 行立刻返回，不等满），
 * > 180 s 只在真挂死时兜底。**它不是预期耗时**，也不是「慢慢等」的许可。
 *
 * 若某天 boot 真花到 180 s，那不是预算问题，是 core 起了 100 倍——届时失败消息里的
 * 进程数与尾部输出足以分清「机器被烧满」还是「core 崩了」。
 *
 * ## 失败消息必须自带取证包
 *
 * 见 [CoreDiagnostics.formatBootFailure]：已等时长、完整命令行、stdout/stderr 尾部、
 * 失败当时本机 java/python 进程数，以及一句按**实测耗时分档**的结论提示。
 */
internal object CoreBootBudget {

    /**
     * READY 等待的硬上限 —— **死锁保险，不是预期耗时**。
     *
     * 实测最长 37.7 s（12 路并发、更忙的机器）；180 s = 4.5×，给负载留足余量。
     */
    const val READY_TIMEOUT_MS = 180_000L

    /**
     * READY 之后那些「条件驱动、有界等待」的上限（WS Connected、loader Ready、
     * 组合 UI 首次渲染、turn 事件回收）。同一台机器被 12 路抢时它们和 boot 同源被放大，
     * 所以用同一个「死锁保险」口径，而不是各自 20 s / 30 s / 45 s 的性能断言。
     */
    const val WAIT_TIMEOUT_MS = 180_000L

    /**
     * 失败消息里 stdout / stderr 各保留的最后行数。
     *
     * 理由：launcher 的 stdout 只有 2–3 行协议行（boot / ready / fatal），stderr 是
     * uvicorn 噪音 + 最多 `traceback.format_exc(limit=8)` 的 Python traceback（≈10 行）。
     * 40 行足以同时装下「协议行 + fatal 行 + 完整 traceback + 触发它的 uvicorn 报错」，
     * 再多只是把 JUnit XML 和控制台刷屏，反而让人不看。
     */
    const val TAIL_LINES = 40

    /** 单侧尾部的字符上限：防一条超长行（例如整段 JSON traceback）把消息撑爆。 */
    const val TAIL_CHARS = 4_000

    /** < 12 s 就失败 → 不可能是负载（空载 boot 的实测下限是 2.3 s，上限 9.8 s）。 */
    const val FAST_FAIL_MS = 12_000L

    /** > 25 s → 已经进了实测的并发区间（中位 19.6–31.6 s），优先怀疑机器负载。 */
    const val SLOW_MS = 25_000L

    /** 实测数据（写进失败提示，让人不必去翻 shot 目录）。 */
    const val MEASURED = "idle 2.3-9.8s, 12-way median 19.6-31.6s, worst 37.7s"
}

/** [vade.ui.e2e.CoreHandle] 在 boot 失败时抛出的异常；message 自带完整取证包。 */
class CoreBootException(
    message: String,
    val stderrTail: String,
    val stdoutTail: String = "",
    val elapsedMs: Long = 0L,
    val timeoutMs: Long = 0L,
    val commandLine: String = "",
    val processSnapshot: String = "",
) : RuntimeException(message)

/**
 * 失败取证包的拼装（纯函数，可被测试直接断言 —— 不依赖真的起一个 core）。
 *
 * 输出形如：
 * ```
 * core did not print READY within 180000ms; no fatal line on stdout
 * --- core boot diagnostics -------------------------------------------------
 * elapsed   : 180042ms (budget 180000ms — deadlock guard, NOT expected cost)
 * command   : "D:/.../python.exe" "C:/.../e2e_core_launcher.py" "--data-dir" "C:/.../data"
 * env       : PYTHONUTF8=1 PYTHONIOENCODING=utf-8
 * data_dir  : C:\...\data
 * child     : alive=true exit=n/a
 * machine   : cpus=16 javaProcs=12 pythonProcs=9 (machine-wide; unreadable=0)
 * stdout tail (last 40 lines, 3 kept):
 *   | {"stage": "boot", "data_dir": "C:\\...\\data"}
 * stderr tail (last 40 lines, 11 kept):
 *   | INFO:     Started server process [1234]
 * hint      : waited 180.0s > 25s — ...
 * ```
 */
internal object CoreDiagnostics {

    /** 完整 spawn 命令行（quoted，可直接粘进 shell 复现）。 */
    fun commandLineOf(python: String, launcher: File, dataDir: File): List<String> =
        listOf(python, launcher.absolutePath, "--data-dir", dataDir.absolutePath)

    fun quote(command: List<String>): String = command.joinToString(" ") { arg ->
        if (arg.isEmpty() || arg.any { it.isWhitespace() || it == '"' }) {
            "\"" + arg.replace("\"", "\\\"") + "\""
        } else {
            arg
        }
    }

    /**
     * 失败当时本机的 java / python 进程数。
     *
     * 用 [ProcessHandle.allProcesses] 枚举全机进程（Windows 上无需提权即可读同用户进程）。
     * `info().command()` 对部分系统进程返回空 —— 那就退回 `commandLine()`；两者都读不到时
     * 计入 `unreadable`，**不会**假装是 0 个。
     *
     * **代价**（本机实测，508 个进程）：冷启一次 5–10 s，热态 120–180 ms。所以它不适合在
     * 失败路径上同步调用 —— [ProcessSampler] 在 spawn 时就把采样线程拉起来，失败时读最近一次
     * 样本（毫秒级、且带 age）。
     */
    fun processSnapshot(): String {
        val facts = machineFacts()
        val all = runCatching { ProcessHandle.allProcesses().toList() }.getOrNull()
            ?: return "$facts javaProcs=n/a pythonProcs=n/a (ProcessHandle.allProcesses() refused)"
        var java = 0
        var python = 0
        var unreadable = 0
        all.forEach { ph ->
            val info = runCatching { ph.info() }.getOrNull()
            val command = runCatching { info?.command()?.orElse("") ?: "" }.getOrDefault("")
            val line = runCatching { info?.commandLine()?.orElse("") ?: "" }.getOrDefault("")
            // 只看可执行文件名，避免把「命令行里恰好出现 java 字样」的进程算进来。
            val exe = command.ifBlank { line.take(260) }
            if (exe.isBlank()) {
                unreadable++
            } else {
                val base = exe.substringAfterLast('\\').substringAfterLast('/').lowercase()
                val head = base.substringBefore(' ')
                when {
                    head.startsWith("java") || head.startsWith("javaw") -> java++
                    head.startsWith("python") || head == "py.exe" -> python++
                }
            }
        }
        return "$facts javaProcs=$java pythonProcs=$python unreadable=$unreadable of ${all.size}"
    }

    /** 永远拿得到的部分（CPU 数 + load average）——进程枚举失败时也要带上，别整段丢。 */
    fun machineFacts(): String {
        val cpus = runCatching { Runtime.getRuntime().availableProcessors() }.getOrDefault(-1)
        // 标准 OS MXBean 的 getSystemLoadAverage()：Windows 上恒返回 -1，如实标 n/a。
        val load = runCatching { ManagementFactory.getOperatingSystemMXBean().systemLoadAverage }
            .getOrDefault(-1.0)
        return "cpus=$cpus loadAvg=${if (load < 0) "n/a(Windows)" else String.format("%.2f", load)}"
    }

    /**
     * 后台进程采样器：`CoreHandle.start()` 时拉起，每 [periodMs] 刷新一次
     * java/python 进程数。失败时只读最近一次样本，**不给失败路径加同步延迟**；
     * 若首次采样还没回来（例如 spawn 立刻失败），最多等 [waitMs] 再如实报「不可得」。
     */
    class ProcessSampler(private val periodMs: Long = 2_000L) {

        @Volatile
        private var latest: String? = null

        @Volatile
        private var latestAtNanos = 0L

        private var worker: Thread? = null

        fun start() {
            if (worker != null) return
            worker = Thread({
                while (!Thread.currentThread().isInterrupted) {
                    val t0 = System.nanoTime()
                    val sample = runCatching { processSnapshot() }
                        .getOrElse { "unavailable (${it::class.simpleName}: ${it.message})" }
                    latest = sample
                    latestAtNanos = System.nanoTime()
                    val spentMs = (System.nanoTime() - t0) / 1_000_000
                    try {
                        Thread.sleep((periodMs - spentMs).coerceAtLeast(250))
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
            }, "e2e-proc-sampler").apply {
                isDaemon = true
                start()
            }
        }

        /**
         * 最近一次样本 + 它的新鲜度；拿不到就如实说不可得（含原因），不编数字。
         *
         * [waitMs] = 15 s：本机实测「首次」全机枚举在 508 个进程上要 5–10 s（JVM 冷启），
         * 之后只要 43–180 ms。所以第一次失败最多等 15 s 拿一份真样本，之后是毫秒级。
         */
        fun snapshot(waitMs: Long = 15_000L): String {
            val deadline = System.nanoTime() + waitMs * 1_000_000
            while (latest == null && System.nanoTime() < deadline) {
                Thread.sleep(20)
            }
            val sample = latest
                ?: return machineFacts() +
                    " javaProcs=n/a pythonProcs=n/a (process enumeration did not finish within " +
                    "${waitMs}ms on a 500+ process machine — counts NOT collected; load cannot be " +
                    "judged from this run)"
            val ageMs = (System.nanoTime() - latestAtNanos) / 1_000_000
            return "$sample (sampled ${ageMs}ms before failure)"
        }

        fun stop() {
            worker?.interrupt()
            worker = null
        }
    }

    /** 尾部行裁剪：先按行取最后 N 行，再按字符截断（从尾部保留）。 */
    fun tailOf(text: String, lines: Int = CoreBootBudget.TAIL_LINES): Pair<String, Int> {
        if (text.isBlank()) return "" to 0
        val all = text.trimEnd('\n').split('\n')
        val kept = if (all.size > lines) all.subList(all.size - lines, all.size) else all
        var joined = kept.joinToString("\n")
        if (joined.length > CoreBootBudget.TAIL_CHARS) {
            joined = joined.takeLast(CoreBootBudget.TAIL_CHARS)
        }
        return joined to kept.size
    }

    /**
     * 一句结论提示，按**实测耗时分档**给出下一步该看哪里。
     * 分段依据是 [CoreBootBudget] 里的实测数字，不是拍脑袋。
     *
     * [hitCeiling] = 这次是「等满自己的上限」而不是「子进程提前死了/拒绝启动」——
     * 两者的下一步完全不同，所以必须区分（否则 5 s 的小预算会被告成「机器负载」）。
     */
    fun hint(elapsedMs: Long, timeoutMs: Long, hitCeiling: Boolean): String = when {
        hitCeiling && timeoutMs < CoreBootBudget.SLOW_MS ->
            "the wait hit the CALLER-SUPPLIED budget of ${timeoutMs}ms, which is below the measured " +
                "contention band. Production ceiling is ${CoreBootBudget.READY_TIMEOUT_MS}ms; this run was " +
                "cut short by the test itself, not by the core."

        hitCeiling ->
            "waited ${fmt(elapsedMs)}s and hit the deadlock ceiling — that is " +
                String.format("%.1f", elapsedMs / 37_700.0) + "× the worst measured boot (37.7s) and far " +
                "outside the contention band (${CoreBootBudget.MEASURED}). Either the machine is " +
                "catastrophically oversubscribed (check javaProcs/pythonProcs above) or the core is hung. " +
                "Not a threshold problem."

        elapsedMs < CoreBootBudget.FAST_FAIL_MS ->
            "failed after ${fmt(elapsedMs)}s, far below the measured idle boot cost (2.3-9.8s) — " +
                "this is NOT machine load. Read the stderr tail for the Python exception " +
                "(missing module / bad path / bad config), or the stdout fatal line."

        elapsedMs > CoreBootBudget.SLOW_MS ->
            "waited ${fmt(elapsedMs)}s > 25s — already inside the measured contention band " +
                "(12-way median 19.6-31.6s, worst 37.7s). Extremely likely MACHINE LOAD, not a core " +
                "crash: re-run when javaProcs/pythonProcs above are near idle before debugging the core."

        else ->
            "waited ${fmt(elapsedMs)}s — inside the measured contention band. Check the stdout tail " +
                "for a fatal line first (a real crash usually shows one within a few seconds); if there " +
                "is none, suspect load on this machine rather than the core."
    }

    fun fmt(ms: Long): String = String.format("%.1f", ms / 1000.0)

    /** 拼装最终异常消息（headline 保留旧文案，老测试按子串断言）。 */
    fun formatBootFailure(
        headline: String,
        elapsedMs: Long,
        timeoutMs: Long,
        command: List<String>,
        dataDir: File,
        childState: String,
        stdoutTail: String,
        stdoutLines: Int,
        stderrTail: String,
        stderrLines: Int,
        processSnapshot: String,
        hitCeiling: Boolean = true,
        spawnMs: Long = 0L,
    ): String = buildString {
        append(headline)
        append("\n--- core boot diagnostics -------------------------------------------------")
        append("\nelapsed   : waited ${elapsedMs}ms of ${timeoutMs}ms budget (deadlock guard, NOT expected cost)")
        append("\nspawn     : ${spawnMs}ms from ProcessBuilder.start() to the child running" +
            " (Windows 1.6-5.3s is normal; >10s means the machine is thrashing)")
        append("\ncommand   : ").append(quote(command))
        append("\nenv       : PYTHONUTF8=1 PYTHONIOENCODING=utf-8")
        append("\ndata_dir  : ").append(dataDir.absolutePath)
        append("\nchild     : ").append(childState)
        append("\nmachine   : ").append(processSnapshot)
        append("\nstdout tail (last ${CoreBootBudget.TAIL_LINES} lines, $stdoutLines kept):")
        if (stdoutTail.isBlank()) {
            append("\n  | (empty — the child never wrote a line)")
        } else {
            stdoutTail.split('\n').forEach { append("\n  | ").append(it) }
        }
        append("\nstderr tail (last ${CoreBootBudget.TAIL_LINES} lines, $stderrLines kept):")
        if (stderrTail.isBlank()) {
            append("\n  | (empty — the child never wrote a line)")
        } else {
            stderrTail.split('\n').forEach { append("\n  | ").append(it) }
        }
        append("\nhint      : ").append(hint(elapsedMs, timeoutMs, hitCeiling))
    }
}
