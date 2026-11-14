# g-e2e-budget · e2e 的 30 s 死线：它正好压在满负载的中位数上

> **fix commit**：`fc21d2c`（只有 4 个 `composeApp/src/desktopTest/**` 文件）。
> 本报告与 `shots/g-e2e-budget/**` 是紧随其后的第二个提交。分支 `tmp/g-e2e-budget`（基于 `main@6648901`）。
> **未 push、未合并。**

**结论先行**

| 问题 | 答案 |
|---|---|
| 30 s 是不是阈值错 | **是**。本机 12 路并发起核 33 轮 × 12 = 396 次 boot 里，有 **2 轮的中位数 > 30 s**（31.0 s、55.6 s），单次最长 **69.4 s**。阈值落在分布里面，不是分布外面 —— 上一棒更忙的机器上中位就是 25.8/31.6 s、最长 37.7 s |
| 改成多少 | `READY_TIMEOUT_MS = 180_000`（= 4.8× 上一棒最长 37.7 s、2.6× 本轮实测最长 69.4 s）。注释与常量文档里写明：**这 180 s 只是死锁保险，不是预期耗时**；等待仍是条件驱动，读到 READY 行立刻返回 |
| 失败时有诊断吗 | **有**，四种失败出口（spawn 失败 / 提前退出 / fatal 行 / 超时）都带同一套取证包：已等时长、spawn 耗时、完整命令行、data_dir、子进程状态、stdout 尾部 40 行、stderr 尾部 40 行、本机 java/python 进程数（拿不到写 `n/a` + 原因）、按实测耗时分档的结论提示。真实样例见 §4 |
| 会不会掩盖真故障 | **不会**，而且是可执行的证明：3 条注入故障用例 + 1 条口径用例（§5）。注入的 `exit(7)` 在 1.3–7.7 s 被识别，坏解释器在 21–47 ms 被识别，5 s 预算在 4.5–8.0 s 打住 —— 都远小于 180 s |
| 负载下稳定吗 | e2e 两类在「16 个 CPU burner + 12 路并发起核循环」下 **连续 5 次全绿**（每次 11 tests / 0 failures），最慢一次 READY 等了 **26 266 ms** —— 距旧上限 30 000 ms 只剩 3.7 s |
| 门禁 | `desktopTest --rerun` **792 / 0 / 0 / 1**（788 基线 + 4 条新用例）；`assembleDebug` **BUILD SUCCESSFUL**；`compileKotlinWasmJs` **BUILD SUCCESSFUL** |
| 顺带挖出的坑 | ① 只把 `waitUntil` 调大会更糟：`runComposeUiTest` 内层 `runTest` 有 60 s 看门狗，会先抛 `UncompletedCoroutinesError` 把有诊断的异常盖掉 → 已补 `testTimeout = 240 s`；② Windows 上 `ProcessBuilder.start()` 本身要 1.6–5.3 s，混进「等待时长」会把 5 s 预算记成 12 s → 已把 spawn 与 wait 分开计时 |

---

## 1. 先测：三个探针、同一台 16 核 Windows

### 1.1 复现上一棒的数字（`shots/g-flake-gen/ready_probe.py`，原样复用）

它测的是 READY **之前**的全部工作（冷解释器 + `import vade.session` + `import uvicorn` +
`App.startup()` + `install_components()` + 配码），**不含 uvicorn 绑定**：

| 场景 | 样本 | 最短 | 中位 | 最长 | import 占比 |
|---|---|---|---|---|---|
| 空载（par=1） | 3 | 8.34 s | 8.45 s | 9.81 s | 0.149–0.207 s |
| 12 路并发 | 2 × 12 | 10.72 s | **20.12 / 19.59 s** | 24.12 s | 0.148–0.226 s |

**和上一棒同量级、方向一致**（他们：空载 8.2–10.9 s、12 路中位 25.8/31.6 s、最长 37.7 s）。
差在绝对峰值：我测的这台机器当时比较闲（没有 12 个兄弟子代理的 gradle 一起跑）。

### 1.2 真 launcher 到 READY（新增 `launcher_ready_probe.py`，含 uvicorn 绑定）

§1.1 的探针停在配码，比真 launcher 少一段。所以我另写一个探针，跑的是**测试里那份 launcher 脚本本体**
（`shots/g-live/core_launcher.py`，与 `RealCoreUiE2ETest.LAUNCHER` 逐字一致），量的是
`ProcessBuilder.start()` → `{"stage":"ready"}` 的**真实墙钟**，也就是 `awaitReady` 覆盖的那一段：

| 场景 | 样本 | 最短 | 中位 | 最长 |
|---|---|---|---|---|
| 空载 | 3 | 2.31 s | 5.98 s | 7.29 s |
| 12 路并发（机器较闲） | 3 × 12 | 6.54 s | 12.15 / 11.10 / 18.31 s | 21.97 s |
| **12 路并发 × 33 轮，且 e2e 同时在跑**（16 burner + gradle） | 33 × 12 | 5.77 s | **中位的中位 10.5 s** | **69.4 s** |

**最后一行是本棒的判定依据**：把「12 路抢核」和「别的活也在跑」叠起来之后，
33 轮里有 **2 轮的中位数 > 30 s**（31.0 s、55.6 s），单次最长 **69.4 s**。
也就是说旧阈值 30 s 在那种时刻会让**过半**的 boot 判红 —— 与「满负载必现、隔离跑全绿」完全吻合。
396 次 boot **全部最终到达 READY**（`ready=12/12` ×33），没有一次真挂死：这正说明 30 s 红的是**预算**，不是 core。

> **探针本身的一个坑**（记下来省下一棒的时间）：`shots/g-live/core_launcher.py` 的 `--port` 默认是
> **18744**，而测试模板默认 0。12 路并发不带 `--port 0` 时，输家会死在
> `WinError 10048`（实测第一轮 11/12、第二轮 4/12 ready）。本探针因此固定传 `--port 0`；
> **测试里不存在这个问题**，别把这个失败模式算到 e2e 头上。

### 1.3 30 s 到底有多贴脸：两处直接证据

1. 上面 33 轮里：2 轮中位 > 30 s，最长 69.4 s。
2. 本棒 5 连跑里**最慢的一次 READY = 26 266 ms**（run1 的第二个 core）—— 距旧上限只剩 **3.7 s**。

---

## 2. 改了什么（4 个文件，全在 desktopTest 下）

| 文件 | 性质 | 改了什么 |
|---|---|---|
| `e2e/CoreBootDiagnostics.kt` | **新增** | `CoreBootBudget`（预算常量 + 实测数据 + 分档阈值）、`CoreBootException`（新增 5 个字段）、`CoreDiagnostics`（命令行引用、尾部裁剪、进程数采样、结论提示、消息拼装） |
| `e2e/RealCoreE2ETest.kt` | 改 | `CoreHandle.awaitReady` 默认预算 180 s；四种失败出口统一走 `bootFailure(...)`；spawn 与 wait 分开计时；spawn 失败不再裸抛 `IOException`；`ProcessSampler` 生命周期；`collectUntilTerminal` 30 s → 180 s；每次 boot 打印 `E2E-CORE-READY spawnMs=… waitMs=…` |
| `e2e/RealCoreUiE2ETest.kt` | 改 | READY 30 s → 180 s；`Connected` 20 s → 180 s；loader Ready 20 s → 180 s；组合等待 45 s → 180 s **且** `runComposeUiTest(testTimeout = 240 s)`；组合等待失败新增取证包（`mainClock` / `source.state` / 独立 UiState 探针 / core 存活与命令行 / 语义树 dump） |
| `e2e/CoreBootDiagnosticsTest.kt` | **新增** | 4 条：解释器不存在、python 立刻 `exit(7)`、永不 READY 但只给 5 s、预算口径不变量 |

**没改**：任何 `commonMain/**`、`main`/`androidMain`/`wasmJsMain`、`build.gradle.kts`（并行度没动）、
`protocol/**`、`data/**`、`shell/**`、`contracts/**`。断言**一条未删、未弱化、未 skip**；
唯一"放宽"的是**墙钟上限**，而且放宽后的上限只作死锁兜底（见 §5 的反向证明）。

### 2.1 为什么尾部是 40 行 / 4000 字符

- launcher 的 stdout 只有 2–3 行协议行（`boot` / `ready` / `fatal`），stderr 是 uvicorn 噪音 +
  `traceback.format_exc(limit=8)`（≈10 行）。**40 行**足够同时装下「协议行 + fatal 行 + 完整 traceback +
  触发它的 uvicorn 报错」；
- 再多只是把 JUnit XML 与控制台刷屏，反而没人看；
- **4000 字符**是单侧上限，防一条超长行（整段 JSON traceback）把消息撑爆；
- 实现上先按行取尾 40 行，再按字符从尾部截断 —— 保证截断永远丢开头、留结尾。

### 2.2 为什么进程数是「异步采样」而不是失败时现查

本机实测（508 个进程）：`ProcessHandle.allProcesses()` **冷启 5–10 s，机器忙时 40 s**，热态只要
43–180 ms。放在失败路径上同步调用，会把「一条 21 ms 就该报出来的 spawn 失败」拖成十几秒。
所以 `ProcessSampler` 在 `start()` 时就拉起（每 2 s 刷一次），失败时读最近一次样本并标出年龄
（`sampled 268ms before failure`）；万一首采还没回来，最多等 15 s，再不行就**如实写 `n/a` + 原因**，
绝不编 0。

---

## 3. 预算口径（写在代码注释里，也在这里）

```
READY_TIMEOUT_MS = 180_000   // 死锁保险，不是预期耗时
                             // 实测：空载 2.3–9.8 s；12 路并发中位 19.6/31.6 s；
                             // 本轮负载矩阵单次最长 69.4 s；上一棒最长 37.7 s
WAIT_TIMEOUT_MS  = 180_000   // READY 之后那几处条件等待（Connected / loader Ready / 首帧 / turn 事件）
```

- 等待**仍然是条件驱动**：`while (nanoTime < end) { ready?.let { return it } … }`，读到 READY 立刻返回，
  180 s 只在真挂死时兜底。5 连跑里 15 次 boot 的实测 wait **中位 8 675 ms、最长 26 266 ms**，没有一次靠"等满"通过。
- 180 s 的定位：**只有在 core 真挂住（或机器被压到 4.8× 超载）时才会用到**。真用到时，消息里那套取证包
  会直接告诉你是哪一种（见 §4 的 `hint` 分档）。

---

## 4. 诊断消息：真实输出

### 4.1 注入故障（python 立刻 `exit(7)`，stderr 写一行）—— 来自 5 连跑第 2 轮

```
core exited before READY (exit=7); no fatal line on stdout
--- core boot diagnostics -------------------------------------------------
elapsed   : waited 2453ms of 180000ms budget (deadlock guard, NOT expected cost)
spawn     : 4ms from ProcessBuilder.start() to the child running (Windows 1.6-5.3s is normal; >10s means the machine is thrashing)
command   : D:/HermesWorkspace/Vade/Vade/.venv/Scripts/python.exe C:\Users\LENSHLD\AppData\Local\Temp\vade-e2e-diag-9438400741595709795\diag_launcher.py --data-dir C:\Users\LENSHLD\AppData\Local\Temp\vade-e2e-diag-9438400741595709795\data
env       : PYTHONUTF8=1 PYTHONIOENCODING=utf-8
data_dir  : C:\Users\LENSHLD\AppData\Local\Temp\vade-e2e-diag-9438400741595709795\data
child     : alive=false exit=7 pid=25428
machine   : cpus=16 loadAvg=n/a(Windows) javaProcs=14 pythonProcs=59 unreadable=20 of 533 (sampled 268ms before failure)
stdout tail (last 40 lines, 1 kept):
  | {"stage": "boot", "data_dir": "injected"}
stderr tail (last 40 lines, 1 kept):
  | BOOM: injected missing dependency (vade.llm.nothere)
hint      : failed after 2.5s, far below the measured idle boot cost (2.3-9.8s) — this is NOT machine load. Read the stderr tail for the Python exception (missing module / bad path / bad config), or the stdout fatal line.
```

### 4.2 解释器不存在（spawn 失败，进程数「不可得」的那条路径）

```
core could not be spawned: IOException: Cannot run program ".../python-that-does-not-exist.exe": CreateProcess error=2, 系统找不到指定的文件。
--- core boot diagnostics -------------------------------------------------
elapsed   : waited 21ms of 180000ms budget (deadlock guard, NOT expected cost)
spawn     : 2ms from ProcessBuilder.start() to the child running (Windows 1.6-5.3s is normal; >10s means the machine is thrashing)
command   : C:\...\python-that-does-not-exist.exe C:\...\diag_launcher.py --data-dir C:\...\data
data_dir  : C:\...\data
child     : not spawned (spawn failure: IOException: Cannot run program "...": CreateProcess error=2, ...)
machine   : cpus=16 loadAvg=n/a(Windows) javaProcs=n/a pythonProcs=n/a (process enumeration did not finish within 15000ms on a 500+ process machine — counts NOT collected; load cannot be judged from this run)
stdout tail (last 40 lines, 0 kept):
  | (empty — the child never wrote a line)
stderr tail (last 40 lines, 0 kept):
  | (empty — the child never wrote a line)
hint      : failed after 0.0s, far below the measured idle boot cost (2.3-9.8s) — this is NOT machine load. ...
```

**为什么第 2 条会是 `n/a`**：这是该 JVM 里**第一次**失败，全机进程枚举还没跑完（冷启要 5–10 s，
那一刻机器还在编译负载下）。按设计如实写「NOT collected」而不写 0 —— 这两条正好把
「拿得到」和「拿不到」两种形态都演示了一遍。

### 4.3 组合 UI 超时（`RealCoreUiE2ETest`）—— 顺带证明新诊断真的能定位

三目标同跑、机器很忙时它仍会红，但消息已经不需要重跑就能定性：

```
composed App never rendered the seeded title 'W4 实跑·真核心会话甲': ComposeTimeoutException: Condition still not satisfied after 180000 ms
--- compose e2e diagnostics ----------------------------------------------
mainClock    : 180016ms advanced
source state : Connected
loader probe : phase=Ready rows=[W4 实跑·真核心会话乙, W4 实跑·真核心会话甲] error=null
core         : alive=true command=D:/HermesWorkspace/Vade/Vade/.venv/Scripts/python.exe C:\...\g_live_ui_launcher.py --data-dir C:\...\data
hint         : if the probe reached Ready with rows, the data path works and this is a Compose/rendering stall ...
```

读法：**数据通路是好的**（同一秒内新建的 UiState 直接 Ready、两行都在；core 活着；socket Connected），
红的是**组合/渲染**这一段 —— 不是 READY 阈值，也不是 core。详见 §8。

---

## 5. 证明没掩盖真故障（4 条新用例）

| 用例 | 注入 | 断言（关键几条） | 实测 |
|---|---|---|---|
| `missingInterpreterFailsImmediatelyWithFullDiagnostics` | `python` 指向不存在的 exe | `err.elapsedMs < 5 000`（不许等满 180 s）；消息含全部字段；`child: not spawned`；无孤儿；`kill()` 幂等 | 等待 **21–47 ms** 就抛出；墙钟 10.4–15.3 s（全花在首次进程枚举，§2.2） |
| `launcherExitingImmediatelyFailsFastWithTailEvidence` | launcher 立刻 `sys.exit(7)` + stderr 写 `BOOM: …` | `exit=7`；stderr 尾部含 `BOOM`；stdout 尾部含 `{"stage": "boot"…}`；`hint` 明确 **NOT machine load**；`elapsedMs < 10 000`；无孤儿 | **1.3–7.7 s** 内识别；用例墙钟 2.2–10.8 s |
| `neverPrintingLauncherFailsAtItsOwnBudgetNotAtTheCeiling` | `time.sleep(300)`、永不打印 READY，但只给 **5 s** 预算 | 在 `4 500..8 000 ms` 失败（不是 180 s）；消息含 `within 5000ms`；`hint` 写明这是 **CALLER-SUPPLIED budget**（不把 5 s 说成机器负载）；`child: alive=true` | 4/5 轮 5.0–5.3 s；机器最忙那轮 11.9 s（多出的是进程枚举，不是等待） |
| `budgetIsDeadlockInsuranceNotAPerformanceTarget` | — | `READY_TIMEOUT_MS == 180 000`；`≥ 4 × 37 700`；`> SLOW_MS`；`TAIL_LINES ∈ 10..100`；后置等待 `≥ 120 000` | 0.0 s |

三条的骨头都是同一句：**「远小于 180 s 就失败」**。如果哪天有人把 180 s 当 sleep 用/把校验换成软校验，
这三条先红。

---

## 6. 负载下 5 连跑（16 burner + 12 路并发起核循环）

负载是两层叠加的（不是自己造 12 路 gradle，但同性质）：
① `burn.py 2400 16` 把 16 核压满；② `launcher_ready_probe.py 12 1` 循环（≈40 次 × 12 路并发起核）。

| run | 墙钟 | tests | failures | core#1 wait | core#2 wait | UI core wait | 该轮最慢 |
|---|---|---|---|---|---|---|---|
| 1 | 206 s | 11 | 0 | 6 146 ms | **26 266 ms** | 11 064 ms | **26 266 ms** |
| 2 | 133 s | 11 | 0 | 12 342 ms | 8 052 ms | 6 359 ms | 12 342 ms |
| 3 | 126 s | 11 | 0 | 7 367 ms | 11 528 ms | 10 362 ms | 11 528 ms |
| 4 | 131 s | 11 | 0 | 11 888 ms | 8 675 ms | 6 157 ms | 11 888 ms |
| 5 | 149 s | 11 | 0 | 7 298 ms | 12 384 ms | 5 827 ms | 12 384 ms |

- **5/5 全绿**（55 个用例执行，0 失败）；15 次 boot 的实测 wait：**中位 8 675 ms、最长 26 266 ms**。
- 最慢的那次 26.3 s 若按旧阈值 30 s，只剩 3.7 s 余量 —— 这轮没红是运气，不是余量。
- 另有 2 次**全量 792 + 两个编译目标**在 16 burner 下同跑的复验（`logs/suite-load-{1,2}.log`）：**rc=0 ×2**，
  RealCore* 7 条全绿。

---

## 7. 三目标门禁（原始数字，跑在提交后的树上）

```
gradle :composeApp:desktopTest --rerun :composeApp:assembleDebug :composeApp:compileKotlinWasmJs --console=plain --offline
```

| 目标 | 结果 |
|---|---|
| `:composeApp:desktopTest --rerun` | **792 tests / 0 failures / 0 errors / 1 skipped**（75 个 XML 求和）。= 基线 **788** + 本棒新增 **4** 条。skipped 仍是 `VadeClientSmokeTest.attachSubmitAndLiveEventsAgainstARealCore`（环境变量门控，main 既有） |
| `:composeApp:assembleDebug` | **BUILD SUCCESSFUL**（`gate-three.log` 里**实际执行**；产物 `composeApp/build/outputs/apk/debug/composeApp-debug.apk`。最终树上为 `UP-TO-DATE` —— 输入没变，Gradle 的正确判定） |
| `:composeApp:compileKotlinWasmJs` | **BUILD SUCCESSFUL**（最终树上以 `--rerun` **重新执行**过一次，`logs/gate-final-compile2.log`） |
| 整体 | `BUILD SUCCESSFUL`（`logs/gate-final-calm.log`、`logs/gate-final-compile{,2}.log`） |

---

## 8. 遗留：组合 UI 在极端过载下仍会卡（**不是**阈值问题，不在本轮可改范围）

- **现象**：`RealCoreUiE2ETest.realCoreSessionTitlesRenderInAppAndFixturesStayAway` 在「全量 792 + 两个编译目标
  + 机器同时被别的活抢」时，偶发在 180 s 内没有渲染出标题 —— 本棒观察到 **2 次**：一次在补
  `testTimeout` 之前（190.8 s，被 60 s 的 `runTest` 看门狗打断，见下），一次在补之后（191.6 s，带着 §4.3 的
  取证包）。上一棒观察到的是同一个测试的 45 s 版本。
  单独跑 / e2e 两类跑 / 全量在 16 burner 下跑（2 次）都绿。
- **定性（靠 §4.3 的取证包，不靠猜）**：那次失败时 `source state=Connected`、独立探针 `phase=Ready` 且
  两行标题都在、core 存活 —— **数据通路正常，卡的是组合/渲染**。READY 只花了 7.5 s，所以**与 30 s/180 s
  这个阈值无关**。
- **已做的两件事**：
  1. `runComposeUiTest(testTimeout = 240 s)`：它的内层 `runTest` 默认 60 s 看门狗，只调大 `waitUntil`
     会先撞它，把一条带诊断的异常盖成 `UncompletedCoroutinesError: After waiting for 1m, the test body
     did not run to completion`（本棒实测踩到，`logs/gate-three-final.log`）。现在内层 180 s < 外层 240 s。
  2. 组合失败时的取证包（`mainClock` / `source.state` / 探针 / 语义树 dump），下次红可以直接看屏幕内容。
- **建议下一棒**：先看语义树 dump 判断是「空列表（数据没进组合）」还是「错误横幅（App 自己的 loader 失败）」；
  再决定是给 `App` 一个可注入的 `UiState` 观察点，还是把 e2e 拆成独立串行 task（见 §9）。
  这已经超出「改阈值 + 加诊断」的范围，**本轮不猜着改产品代码**。

---

## 9. 未做 / 建议（明确不实施）

1. **不改并行度**（本轮硬约束）：本仓 `org.gradle.parallel=true` + `workers.max=4`，12 路并发时 e2e 与
   其它任务抢核。§1.2 的数字就是「e2e 不该在 12 路并发下跑」的正式证据 —— 建议把 `RealCore*E2ETest`
   拆到独立 task（`:composeApp:desktopTestE2E`）并串行执行，`desktopTest` 里 `exclude("**/e2e/**")`。
   **本轮按要求只在报告里提出，不动 `build.gradle.kts`。**
2. `requestTimeoutMs = 15_000`（客户端请求预算）是**产品级**参数，不是测试等待，本轮不动；
   若将来它在过载下也红，那是另一条链路的预算问题。
3. `FakeTransport.waitUntil` 的 5 s 墙钟（124 个调用点）仍是上一棒交接的同族风险，本轮未触及。

---

## 10. 复现命令

```bash
export PATH="/d/Hermes/tools/gradle-9.6.1/bin:$PATH"   # 本仓不生成 wrapper，必须 9.6.1
cd D:/HermesWorkspace/Vade-client-wt/g-e2e-budget

# 1) 复现测量（boot-only / 真 launcher）
python shots/g-flake-gen/ready_probe.py 1 3
python shots/g-flake-gen/ready_probe.py 12 2
python shots/g-e2e-budget/launcher_ready_probe.py 1 3
python shots/g-e2e-budget/launcher_ready_probe.py 12 3

# 2) 注入故障自证（4 条）
gradle :composeApp:desktopTest --tests "vade.ui.e2e.CoreBootDiagnosticsTest" --console=plain --offline

# 3) 负载下 5 连跑（16 burner + 12 路并发起核循环）
bash shots/g-e2e-budget/run-load-matrix.sh 5

# 4) 三目标门禁
gradle :composeApp:desktopTest --rerun :composeApp:assembleDebug :composeApp:compileKotlinWasmJs \
       --console=plain --offline

# 5) 从提交的证据重建所有数字
python shots/g-e2e-budget/summarize.py   # → shots/g-e2e-budget/summary.tsv
```

**证据文件**：`shots/g-e2e-budget/logs/`（探针输出、每轮 gradle 日志、门禁日志）、
`shots/g-e2e-budget/xml/`（5 连跑 ×5 + 全量负载复验 ×2 的 JUnit XML，pair code 已脱敏为 `<REDACTED>`）、
`shots/g-e2e-budget/summary.tsv`（55 条用例 + 15 条 READY 时序，机器可读）。
