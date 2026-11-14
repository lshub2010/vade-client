# g-e2e-budget2 · 180 s 之后：满载下门禁到底绿不绿

> **分支**：`tmp/g-e2e-budget2`（基于 `main@c735037`）
> **代码提交**：`3ad724e`（4 个 `composeApp/src/desktopTest/**` 文件）；本报告与 `shots/g-e2e-budget2/**` 是紧随其后的第二个提交。
> **未 push、未合并**；无 `--amend` / `--rebase` / `--reset`。
> 报告里的每个数字都可从 `shots/g-e2e-budget2/{xml,logs}/` 重建：`python shots/g-e2e-budget2/summarize.py`。

## 结论先行

| 问题 | 答案 |
|---|---|
| 30 s → 180 s、失败取证包、spawn/wait 分开计时、Compose `testTimeout`、注入必失败命令 —— 这五件做了吗？ | **在 main 上已经做了**（上一棒 `fc21d2c`）。本棒逐条复核了代码，全部成立（§1），没有把它当"已完成"跳过 |
| 本棒补了什么 | 三处缺口：① 结论原来在 **80 行尾部输出之后**（等于没有结论）→ 提到 headline 下一行，且改成**可 grep 的标签**；② 判词的分档规则（<12 s 判崩 / >25 s 判负载）**没有任何用例锁住** → 新增纯函数用例；③ Compose 取证包同样把结论放在末尾 → 一并置顶 |
| 满载下还能不能绿（本棒实测） | **绿**。8 次运行全绿：单跑 e2e ×1、全量三目标 ×3（空载 / 12 路并发中 / 16 burner + 12 路并发下，其中满载那次墙钟 5m25 是空载 2m12 的 **2.4×**）、负载下 e2e ×3、过载 e2e ×1。每次全量都是 **1119 tests / 0 failures / 0 errors / 1 skipped**（skip 是 main 既有的 `VadeClientSmokeTest`，env 门控） |
| 旧 30 s 在本机这批数据上会怎样 | 12 路并发探针 **972 次启动全部到达 READY**，其中**第一批冷启动的中位数就是 41.1 s、最长 62.4 s** —— 光这一批，旧阈值就会判 7/12 红 |
| 下次红能不能一眼分清 | 能。消息第 2 行就是 `verdict   : <SPAWN-FAIL|CORE-FAIL-EARLY|LIKELY-LOAD|INCONCLUSIVE|CALLER-BUDGET|CEILING-HIT> — …`，可直接 grep JUnit XML |
| 宽松上限有没有被当借口 | 没有，而且是**可执行**的：注入 `exit(7)` 在 **1.6 s**、坏解释器在 **23 ms（等待口径）**、5 s 预算在断言区间 **4.5–8.0 s** 内失败（用例墙钟 6.9 s，多出的是失败时的进程枚举）——都远小于 180 s（§3、§5） |

---

## 0. 先说清楚：main 上已经有什么，本棒改了什么

`git log` 显示 `fc21d2c test(e2e): READY 上限 30s→180s（死锁保险，不是预期耗时）+ 失败取证包 + 注入故障自证`
是 `origin/main` 的祖先 —— 也就是说本任务的第 1–5 项**已经落地**。所以本棒不是重写，而是：

1. **复核**（§1）：逐条把要求对到代码位置，确认没有"报告说做了、代码没做"；
2. **补缺口**（§2）：把"一眼"这一环补上 —— 结论置顶 + 可 grep 标签 + 用用例锁住分档；
3. **实测**（§4、§5）：抬阈值之后**满载到底绿不绿**，这是我唯一能提供、上一棒给不了的东西（他们那台机器当时被 12 个子代理抢，我这台相对闲）。

---

## 1. 逐条对表（要求 → 代码位置 → 判定）

| # | 要求 | 代码位置 | 判定 |
|---|---|---|---|
| 1 | READY 硬死线 `30000 → 180000`，注释写明"只是死锁保险、不是预期耗时"并附实测分布 | `CoreBootDiagnostics.kt:45` `READY_TIMEOUT_MS = 180_000L`；类注释表格含 `idle 2.3–9.8 s / 12 路中位 19.6–31.6 s / 最长 37.7 s`，本棒补了 `396 次启动全部 READY、最长 69.4 s` | ✅ 成立 |
| 2 | 失败消息含：已等时长、完整 spawn 命令行、stdout/stderr 尾部、当时进程数、一句结论 | `CoreDiagnostics.formatBootFailure`：`elapsed / command / stdout tail / stderr tail / machine(javaProcs,pythonProcs,unreadable) / verdict` | ✅ 成立，**本棒把结论从末尾移到第 2 行并加标签** |
| 3 | `ProcessBuilder.start()` 与"等 READY"分开计时 | `CoreHandle.spawnMs()` / `elapsedMs()`；每次 boot 打印 `E2E-CORE-READY spawnMs=… waitMs=…`；诊断里 `spawn     :` 独立一行 | ✅ 成立；**本棒实测到 4 ms – 6 061 ms**，见 §6 |
| 4 | Compose 内层 `runTest` 60 s 看门狗一起抬 | `RealCoreUiE2ETest.kt:115` `runComposeUiTest(testTimeout = 240.seconds)`，注释写明 180 < 240 的关系 | ✅ 成立 |
| 5 | 注入必定失败的启动命令，证明不会干等到 180 s | `CoreBootDiagnosticsTest`：坏解释器 / `exit(7)` / 永不 READY 但只给 5 s 三条 | ✅ 成立（本棒跑出 23 ms / 1.6 s / 4.5–8.0 s） |
| — | 不许删测/skip/弱化断言 | `git diff c735037..3ad724e --stat`：4 文件 +220/−33，只有新增与改写；无 `@Ignore`、无 `Assume` | ✅ |
| — | 只动 `desktopTest/**` 与 `shots/g-e2e-budget2/**` | 同上；未触 `commonMain`、`build.gradle.kts`、`contracts` | ✅ |

---

## 2. 本棒改动（`3ad724e`，4 文件，全在 desktopTest）

| 文件 | 改了什么 | 为什么 |
|---|---|---|
| `e2e/CoreBootDiagnostics.kt` | 新增 `category()`（6 档标签）与 `verdictLine()`（标签 + 结论 + spawn 侧负载证据）；`formatBootFailure` 把 `verdict   :` 插在 **headline 下一行**，删掉埋在末尾的 `hint`；新增 `SPAWN_SLOW_MS = 10_000` | 一条红消息有 40+40 行尾部输出，结论在最后等于没有结论；标签让"这次是负载还是崩"变成 `grep verdict` 就能统计的事 |
| `e2e/RealCoreE2ETest.kt` | `bootFailure(...)` 多传 `spawnFailed = spawnFailure != null` | 让"解释器没起来"不再被归到 `CORE-FAIL-EARLY`（那会误导人去看 stderr 找 Python 异常） |
| `e2e/RealCoreUiE2ETest.kt` | `composeFailureDiagnostics` 同样置顶判词：`RENDER-STALL`（数据通路好、卡在组合）/ `DATA-PATH-FAIL`（同一 source 连 UiState 都填不满） | 上一棒是**人肉**读 probe 才得出的定性，现在机器直接给出 |
| `e2e/CoreBootDiagnosticsTest.kt` | 新增 `verdictSeparatesLoadFromCrashWithoutReadingTheTails`：合成 3 s / 18 s / 26 s / 180 s / caller-5 s / SPAWN-FAIL 六档 + 位置断言 + spawn 独立断言 | 「<12 s 看 stderr、>25 s 看负载」这条规则原本没有任何用例守着；改坏了没人知道 |

改后的判词表（就是 `category()` 的分档）：

| 标签 | 触发 | 读法 |
|---|---|---|
| `SPAWN-FAIL` | `ProcessBuilder.start()` 自己抛 | 命令/venv 坏了，与 core 无关 |
| `CORE-FAIL-EARLY` | 等 READY < 12 s 就死 | 看 stderr 的 Python 异常 / stdout 的 fatal 行 |
| `INCONCLUSIVE` | 12–25 s | 先找 fatal 行；没有就怀疑负载 |
| `LIKELY-LOAD` | > 25 s 才死 | **机器被抢**，先看 `javaProcs/pythonProcs` |
| `CALLER-BUDGET` | 调用方给了小于并发区间的预算 | 测试自己砍短的，不是 core |
| `CEILING-HIT` | 等满 180 s | 真挂死，或机器被压到 4.8× 超载 |

---

## 3. 诊断包原文（真实运行产出，不是手写样例）

### 3.1 `SPAWN-FAIL` —— 解释器路径不存在（本次运行，等待口径 **23 ms**）

```
core could not be spawned: IOException: Cannot run program "C:\Users\LENSHLD\AppData\Local\Temp\vade-e2e-diag-12684465501953623653\python-that-does-not-exist.exe": CreateProcess error=2, 系统找不到指定的文件。
verdict   : SPAWN-FAIL — the interpreter/launcher never started (ProcessBuilder.start() itself threw): this is a broken command line or missing venv — NOT machine load and NOT the core. Compare the 'command' line below with a working run, and read the IOException text in 'child'.
--- core boot diagnostics -------------------------------------------------
elapsed   : waited 23ms of 180000ms budget (deadlock guard, NOT expected cost)
spawn     : 5ms from ProcessBuilder.start() to the child running (Windows 1.6-5.3s is normal; >10s means the machine is thrashing)
command   : C:\Users\LENSHLD\AppData\Local\Temp\vade-e2e-diag-12684465501953623653\python-that-does-not-exist.exe C:\...\diag_launcher.py --data-dir C:\...\data
env       : PYTHONUTF8=1 PYTHONIOENCODING=utf-8
data_dir  : C:\Users\LENSHLD\AppData\Local\Temp\vade-e2e-diag-12684465501953623653\data
child     : not spawned (spawn failure: IOException: Cannot run program "...": CreateProcess error=2, 系统找不到指定的文件。)
machine   : cpus=16 loadAvg=n/a(Windows) javaProcs=15 pythonProcs=39 unreadable=23 of 493 (sampled 11ms before failure)
stdout tail (last 40 lines, 0 kept):
  | (empty — the child never wrote a line)
stderr tail (last 40 lines, 0 kept):
  | (empty — the child never wrote a line)
```

### 3.2 `CORE-FAIL-EARLY` —— 注入 `sys.exit(7)`（最终树单跑，等待 **1563 ms**）

```
core exited before READY (exit=7); no fatal line on stdout
verdict   : CORE-FAIL-EARLY — failed after 1.6s, far below the measured idle boot cost (2.3-9.8s) — this is NOT machine load. Read the stderr tail for the Python exception (missing module / bad path / bad config), or the stdout fatal line.
--- core boot diagnostics -------------------------------------------------
elapsed   : waited 1563ms of 180000ms budget (deadlock guard, NOT expected cost)
spawn     : 10ms from ProcessBuilder.start() to the child running (Windows 1.6-5.3s is normal; >10s means the machine is thrashing)
command   : D:/HermesWorkspace/Vade/Vade/.venv/Scripts/python.exe C:\...\diag_launcher.py --data-dir C:\...\data
env       : PYTHONUTF8=1 PYTHONIOENCODING=utf-8
data_dir  : C:\Users\LENSHLD\AppData\Local\Temp\vade-e2e-diag-15496931043142320124\data
child     : alive=false exit=7 pid=43028
machine   : cpus=16 loadAvg=n/a(Windows) javaProcs=11 pythonProcs=44 unreadable=22 of 504 (sampled 1421ms before failure)
stdout tail (last 40 lines, 1 kept):
  | {"stage": "boot", "data_dir": "injected"}
stderr tail (last 40 lines, 1 kept):
  | BOOM: injected missing dependency (vade.llm.nothere)
```

两份都满足"不看代码就能判方向"：**第一行说是什么，第二行说是哪一类，其余是证据**。

### 3.3 `LIKELY-LOAD` 的形态

本轮**没有**真跑出一次 >25 s 的失败（全绿），所以不贴伪造的样本；该档由 §2 的纯函数用例用合成耗时（26 000 ms）锁住，断言原文：

```
verdict   : LIKELY-LOAD — waited 26.0s > 25s — already inside the measured contention band
            (12-way median 19.6-31.6s, worst 37.7s). Extremely likely MACHINE LOAD, not a core crash:
            re-run when javaProcs/pythonProcs above are near idle before debugging the core.
```

---

## 4. 实测：单跑 / 全量 / 满载 / 过载

### 4.1 e2e 与全量的每次运行

| # | 场景 | 负载 | 墙钟 | tests | fail/err/skip | e2e boot `waitMs`（spawn） |
|---|---|---|---|---|---|---|
| 1 | **单跑 e2e（最终树）** | 空载 | 1m41 | 12 | 0/0/0 | 4 324 / 2 638 / 2 205（spawn 11–37 ms） |
| 2 | **全量三目标**（空载） | 空载 | 2m12 | **1119** | **0/0/1** | — |
| 3 | e2e ×3 · run1 | 16 burner + 12 路并发起核 | 2m47 | 12 | 0/0/0 | 8 717 / 8 880 / **14 171** |
| 4 | e2e ×3 · run2 | 同上 | 2m05 | 12 | 0/0/0 | 5 848 / 4 144 / 13 727 |
| 5 | e2e ×3 · run3 | 同上 | 2m01 | 12 | 0/0/0 | 10 899 / 9 478 / 8 040 |
| 6 | **全量三目标**（负载中） | 12 路并发起核循环 | 2m10 | **1119** | **0/0/1** | 8 643 / 7 007 / 13 711（spawn **6 061**） |
| 7 | **全量三目标**（满载，三目标全 `--rerun`） | 16 burner + 12 路并发 | **5m25** | **1119** | **0/0/1** | 4 761 / 7 884 / 6 311 |
| 8 | 过载 e2e | 24 burner + 24 路并发起核 | 2m05 | 12 | 0/0/0 | 9 356 / 10 733 / 10 601 |

- **8/8 全绿**；两次满载全量都是 **1119 / 0 / 0 / 1**（`tests=` 求和，不采信 BUILD SUCCESSFUL，见 §5）。
- 满载那次墙钟 **5m25** 是空载 2m12 的 **2.4×** —— 负载是真的，不是"假装忙"。
- `skip=1` 自始至终只有 `VadeClientSmokeTest.attachSubmitAndLiveEventsAgainstARealCore`
  （`AssumptionViolatedException: VADE_SMOKE_PORT set`，main 既有，与本任务无关）。e2e 三个类**一条都没 skip**。

### 4.2 12 路并发起核探针（负载侧证据）

跑的是测试里那份 launcher 本体（`shots/g-e2e-budget/launcher_ready_probe.py`，固定 `--port 0`），量的是
`ProcessBuilder.start()` → `{"stage":"ready"}` 的真实墙钟。

| 探针 | 轮数 | 启动次数 | 到达 READY | 中位的中位 | 最长 | 单轮中位 >30 s |
|---|---|---|---|---|---|---|
| 与 e2e 同跑（16 burner + 12 路） | 34 | 408 | **408/408** | 10.1 s | **62.4 s** | **1**（41.1 s） |
| 满载门禁期间（16 burner + 12 路） | 25 | 300 | 300/300 | 8.2 s | 23.1 s | 0 |
| 过载 A（24 burner + 24 路） | 11 | 132 | 132/132 | 12.3 s | 22.4 s | 0 |
| 过载 B（同上） | 11 | 132 | 132/132 | 10.7 s | 25.7 s | 0 |
| **合计** | **81** | **972** | **972/972** | — | **62.4 s** | **1** |

最关键的一行是**第一批冷启动**（16 burner 刚压满、12 路同时起）：

```
round1 par=12: n=12 wall=81.457s to-READY min=8.58 median=41.149 max=62.374 ready=12/12
```

中位数 **41.1 s** —— 旧死线 30 s 落在这批的分布**里面**，这一批至少 7/12 会红；而 12/12 最终都到了 READY，
一次真挂死都没有。**这与"30 s 是差阈值、不是 core 慢"完全一致**，也是我把 180 s 当成死锁保险的唯一理由。

> 说清楚口径：我这台机器比较闲，除了第一批冷启动，其余轮次的中位落在 8–12 s ——
> **没能复现**上一棒"12 路并发中位 25.8 / 31.6 s"的稳态。那份数字来自他们 33 轮 × 12 路的日志，
> 我引用但不冒领；我能独立证明的是：峰值侧 62.4 s、冷启动中位 41.1 s，**30 s 确实不够**。

---

## 5. 三目标门禁（原始数字）

```
gradle :composeApp:desktopTest --rerun :composeApp:assembleDebug :composeApp:compileKotlinWasmJs \
       --console=plain --offline          # 空载：shots/g-e2e-budget2/logs/gate-three.log
```

| 目标 | 空载 | 满载（16 burner + 12 路并发，三目标全 `--rerun`） |
|---|---|---|
| `:composeApp:desktopTest --rerun` | **1119 tests / 0 failures / 0 errors / 1 skipped**（105 个 XML 求和） | **1119 / 0 / 0 / 1** |
| `:composeApp:assembleDebug` | 执行，BUILD SUCCESSFUL | `--rerun` **重新执行**，BUILD SUCCESSFUL |
| `:composeApp:compileKotlinWasmJs` | 执行，BUILD SUCCESSFUL | `--rerun` **重新执行**，BUILD SUCCESSFUL |
| 整体 | `BUILD SUCCESSFUL in 2m 12s` | `BUILD SUCCESSFUL in 5m 25s`（real 5m47.9） |

- 三个 target 在日志里都出现 `> Task :composeApp:{desktopTest,assembleDebug,compileKotlinWasmJs}`（执行过，不是 UP-TO-DATE）。
- `tests=1119` 比上一棒报告里的 792 大 —— 那是 main 上其它棒新增的用例（本棒只 +1）。求和脚本：
  `python shots/g-e2e-budget2/summarize.py`。

---

## 6. spawn 与 wait 分开计时：本棒的实测证据

18 次 e2e boot 的读数（全部来自 JUnit XML 的 `E2E-CORE-READY` 行）：

| 项 | min | 中位 | max |
|---|---|---|---|
| `spawnMs`（`ProcessBuilder.start()` 返回） | **4 ms** | 12.5 ms | **6 061 ms** |
| `waitMs`（到 READY） | 2 205 ms | 8 798.5 ms | 14 171 ms |

那次 **6 061 ms** 的 spawn 出现在满载全量门禁的第一个 core 上（`shots/g-e2e-budget2/xml/load-full/`）。
如果按老口径把 spawn 并进"等待"，这次会被记成 `6061 + 8643 = 14 704 ms`，
而**同为一次 5 s 预算的等待，就会从 5 s 报成 11 s** —— 量测口径直接失真。
这就是"必须分开计时"不是理论洁癖的现场证据。

---

## 7. 遗留与判断

1. **上一棒 §8 的 Compose 卡死（180 s 内没渲染出标题）本轮未复现**：6 次 e2e（3 次负载 + 1 次负载全量 + 1 次过载 + 1 次空载）全绿。
   `b71fd1a` 修掉了 `DestinationHost` 用 `currentRecomposeScope.invalidate()` 自失效导致的「内容区渲染上一帧」，
   是合理候选原因，但**本轮没做 A/B，不断言因果**。真再红时，取证包现在第一行就给出
   `RENDER-STALL` / `DATA-PATH-FAIL`，不用再人肉读 probe。
2. **并行度没动**（硬约束）：`org.gradle.parallel=true` + `workers.max=4`。11 分钟内的 8 次运行说明"e2e 在负载下能跑"，
   但把 `RealCore*` 拆成独立串行 task 仍是更干净的方案 —— 属于 `build.gradle.kts`，本轮按要求不动。
3. `requestTimeoutMs = 15_000`（客户端请求预算）是产品参数，非测试等待，未动。
4. 判词分档的 12 s / 25 s 阈值来自实测分布；等下一棒有更忙的机器数据时，应同步更新
   `CoreBootBudget.FAST_FAIL_MS / SLOW_MS` 与 `budgetIsDeadlockInsuranceNotAPerformanceTarget` 的边界断言。

---

## 8. 复现命令

```bash
export GRADLE_USER_HOME=D:/Hermes/tools/gradle-home
G=/d/Hermes/tools/gradle-9.6.1/bin/gradle
cd D:/HermesWorkspace/Vade-client-wt/g-e2e-budget2

# 1) 单跑 e2e（含 4 条注入自证 + 1 条判词分档）
$G :composeApp:desktopTest --tests "vade.ui.e2e.*" --rerun --console=plain --offline

# 2) 空载三目标
$G :composeApp:desktopTest --rerun :composeApp:assembleDebug :composeApp:compileKotlinWasmJs \
   --console=plain --offline

# 3) 负载矩阵（16 burner + 12 路并发起核循环 + e2e ×N + 一次全量）
bash shots/g-e2e-budget2/run-load-matrix.sh 3 1

# 4) 满载三目标（三目标全 --rerun，机器 2.4× 慢）
python shots/g-flake-gen/burn.py 900 16 &
for i in $(seq 1 60); do python shots/g-e2e-budget/launcher_ready_probe.py 12 1; done &
$G :composeApp:desktopTest --rerun :composeApp:assembleDebug --rerun \
   :composeApp:compileKotlinWasmJs --rerun --console=plain --offline

# 5) 从 XML 重建报告里所有数字
python shots/g-e2e-budget2/summarize.py
```

## 9. 证据文件

- `shots/g-e2e-budget2/xml/{load-e2e-run-1..3,load-full,overload-e2e,e2e-isolated-final}/TEST-*.xml` —— 每次运行的原始 JUnit XML
  （`E2E-CORE-READY spawnMs/waitMs` 就在 `system-out` 里；一次性 pair code 已全部替换为 `<REDACTED>`，共 12 处）。
- `shots/g-e2e-budget2/logs/gate-three.log`、`gate-maxload.log` —— 空载 / 满载三目标完整日志（含 `rc=`）。
- `shots/g-e2e-budget2/logs/probe-*.txt` —— 81 轮 × 12 路起核探针的每轮 min/median/max。
- `shots/g-e2e-budget2/logs/{load-e2e-run-*,load-full-gate,overload-e2e,diag-only}.log` —— 每次 gradle 跑。
- `shots/g-e2e-budget2/summarize.py` —— 把上面全部重算成报告里的数字。
