# c-capture — tools/capture-window.ps1 并发安全重写（RESULT）

- 分支：`tmp/c-capture`（基于 main @ f189de2）
- 脚本提交：`b3bc1fb` fix(tools): capture-window.ps1 并发安全重写 — 只杀本轮 PID + 记录式残留清理
- 涉及文件：`tools/capture-window.ps1`（466 行，纯 ASCII）、`shots/c-capture/RESULT.md`（本文件）

## 新旧行为对比（一句话）

旧版在截图前按名强杀机器上全部 java 进程（打死后台并行构建的 Gradle daemon，其初衷是清掉上一轮残留窗口）；新版只杀两样东西——本轮 `Start-Process -PassThru` 启动的进程，以及 `shots/.capture-pid` 里记录的、通过四重校验的上一轮残留，任何一条校验不过就什么都不杀。

## 新判定逻辑

残留 PID 必须同时满足才被杀：

1. 进程存在（`Get-Process -Id <记录pid>`）；
2. 进程名恰为 `java`（本脚本只启动 `java.exe`，其他名字 = PID 被别的进程复用）；
3. 启动时间 ticks 与记录值偏差 ≤ 5 秒（PID 复用守卫：一个复用了该 PID 的新 java 进程过不了这一条）；
4. 启动时间早于本轮脚本启动时刻。

记录文件 `shots/.capture-pid` 格式：`<pid>|<startTimeTicks>`（ASCII 单行）。启动后立即写入；本轮进程确认退出后删除（删除前回读比对内容，防止覆盖另一个并发运行的记录）；发现记录失效或损坏时立即清除并自愈。

## 改动点清单

1. **删除全杀块**（旧 L112-116：按名杀全部 java + sleep 600ms），替换为上面的记录式清理。
2. **窗口匹配收紧**：`Process.MainWindowHandle` 优先（每轮 `Refresh()` 后取句柄，校验 `SunAwt*` 类名 + 可见 + >300x300）；句柄为 0 时每 400ms 轮询直至 `-WaitSec`（默认 45s）超时；唯一兜底是只枚举**本轮 PID 自己**的窗口（同样类名+尺寸校验）；超时输出 `NO_WINDOW` + exit 2，绝不猜一个窗口。
3. **自检输出**：每次运行打印 `launched pid=...`、记录文件路径与内容、cleanup 结论、`window matched by=... hwnd=... class=... title='...'`、`window rect=...`、`saved <路径> <WxH> <字节> bytes md5=<...>`。
4. **-Dark 生效性检查**：保存后与同目录把文件名 `dark`→`light` 的对图做字节/md5 对比；md5 相同或仅字节数相同 → `DARK_WARNING`。
5. **新增参数**：`-DryRun`（启动+匹配+全量自检、不落图、退出码 0）、`-CleanupOnly`（只做残留清理并退出）、`-DarkCheckOnly <png>`（对已有 dark 图做对比诊断；exit 5 = 输入不存在）。
6. **头部注释**：全部参数、退出码表（0/2/3/4/5/6）、记录文件格式与生命周期、并发约束（同 worktree 并行两张截图不支持；跨 worktree 天然隔离）。全文件纯 ASCII（旧文件注释里有一个中文词，PowerShell 5.1 对无 BOM UTF-8 会双重解码，一并清除）。

## 静态验证（原始输出）

### 解析检查

pwsh（7.x）与 powershell 5.1（10.0.26100.8875）双解析器：

```
=== [1] pwsh AST parse ===
PARSE_ERRORS=0
PARSE_OK
=== [2] powershell 5.1 AST parse ===
PARSE_ERRORS=0
PARSE_OK
```

`Get-Command -Syntax`（powershell 5.1，参数清单与头部注释一致）：

```
capture-window.ps1 [-Java] <string> [-ClassPathFile] <string> [-Bar] <string> [-OutFile] <string> [[-Size] <string>] [[-Dest] <string>] [[-Panel] <string>] [[-Search] <string>] [[-Skin] <string>] [[-Open] <string>] [[-Chat] <string>] [[-WaitSec] <int>] [[-SettleMs] <int>] [[-DarkCheckOnly] <string>] [-SearchEmpty] [-Showcase] [-Approval] [-Dark] [-Gallery] [-GalleryLight] [-DryRun] [-CleanupOnly] [<CommonParameters>]
```

点源冒烟（`& { . tools/capture-window.ps1 ... }`，无记录文件状态下执行到 `NO_CLASSPATH` 守卫）：

```
cleanup: no pid record at D:\HermesWorkspace\Vade-client-wt\c-capture\shots\.capture-pid; nothing to clean
NO_CLASSPATH
```

（首次解析曾在 L325 报错：`[void]Invoke-CaptureLeftoverCleanup` —— PS 的 `[void]` 强转不能套在裸命令上，改为直呼后双解析器 PARSE_OK。）

### 「按名杀进程」模式彻底消失（grep 证据）

```
=== final grep: forbidden kill patterns in tools/capture-window.ps1 ===
grep -nE 'Get-Process +java|Stop-Process +-Name|taskkill|/IM +java' tools/capture-window.ps1
grep rc=1 (1 = zero hits)

=== every Stop-Process call site (final) ===
257:  Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
267:  Stop-Process -Id $ProcId -Force -ErrorAction SilentlyContinue

=== every Get-Process call site (final) ===
229:  $proc = Get-Process -Id $rec.Pid -ErrorAction SilentlyContinue
268:  $proc = Get-Process -Id $ProcId -ErrorAction SilentlyContinue
275:  if ($null -eq (Get-Process -Id $ProcId -ErrorAction SilentlyContinue)) {
```

脚本中仅有的 2 处 `Stop-Process` 均为精确 `-Id`：一处杀「通过全部校验的记录残留」，一处杀「本轮自己启动的进程」。

## 动态验证（清理路径，哨兵进程法）

**环境**：验证期间机器上始终有 4 个非本任务启动的 java 进程（T0 快照：2788 / 28548 / 36092 / 38384，启动于 18:41-18:53，与并行子代理的 Gradle 构建吻合）。哨兵进程全部由本任务自己启动：notepad / cmd / ping / 单文件源 java（`java.exe` 直接跑 `SleepMain.java`，进程名 = `java`）。

| 用例 | 记录内容 | 预期 | 实际 |
|---|---|---|---|
| T1 | `999999\|...`（pid 不存在） | 不杀 | `recorded leftover pid=999999 is not running ... nothing killed`，记录清除 |
| T2 | `garbage-line` | 不杀 | `pid record corrupt ... removing it; nothing killed`，记录清除 |
| T3c | live `PING` 进程 pid + 真实 ticks | 拒杀（非 java） | `pid=32340 is now 'PING', not java ... refusing to kill`，PING 存活 |
| T4 | java sleeper pid + **偏差 1 小时**的 ticks | 拒杀（PID 复用守卫） | `diff 36000000000 ticks; refusing to kill`，sleeper 存活 |
| T5 | java sleeper pid + 真实 ticks | 只杀记录的那个 | `killed leftover pid=28592`，sleeper 退出，记录清除 |
| T7 | 全程前后 java 快照 diff | 外来进程零变化 | `IDENTICAL` |

关键原始输出：

```
=== [T4] java sleeper; record WRONG ticks (1h off) -> pid-reuse guard must refuse ===
sleeper pid=28592 real ticks=639302792687958678
28592|639302756687958678
cleanup: pid=28592 is java but started 2026-11-14T18:54:28.7958678+08:00, which does not match the recorded run (diff 36000000000 ticks); refusing to kill (pid was probably recycled)
SLEEPER_STILL_ALIVE (pid-reuse guard refused, as designed)

=== [T5] record sleeper REAL pid|ticks -> validated leftover -> exactly that one killed ===
28592|639302792687958678
cleanup: killed leftover pid=28592 (java, started 2026-11-14T18:54:28.7958678+08:00, recorded by a previous run of this script)
SLEEPER_GONE (exactly the recorded pid was killed)
[record removed]

=== [T3c] record: live ping.exe pid (name != java -> must refuse) ===
cleanup: pid=32340 is now 'PING', not java (pid was recycled by another process); refusing to kill
PING_STILL_ALIVE (non-java pid refused, as designed)

=== [T7] diff before vs after ===
IDENTICAL
```

T7 之前/之后快照（外来 4 个 daemon 的 Id+StartTime 完全一致；本任务自己启动的 java sleeper 只在 T5 被脚本精确杀掉一个，且已退出）：

```
   Id ProcessName StartTime
   -- ----------- ---------
 2788 java        2026/11/14 18:52:38
28548 java        2026/11/14 18:41:52
36092 java        2026/11/14 18:42:13
38384 java        2026/11/14 18:53:24
```

（注：本任务最初检查时快照为 1744/15080/28548/36092，与 T0 相比有外部性轮换——那是其他代理构建自身的生命周期，与本脚本无关；本脚本从未按名枚举任何进程。）

哨兵踩坑记录（非脚本问题）：Win11 的 `System32\notepad.exe` 是启动器，秒退转生商店版进程；`cmd /c timeout` 在无控制台 stdin 时立即报错退出。两次记录都落成「pid 已不在 → 脚本正确地什么都不杀」，name-guard 最终用 `ping.exe` 完成。

## -Dark 生效性检查（动态，三分支全命中）

shots/ 内无现成 dark/light 图对，用 `shots/motion/freeze-0.png` / `freeze-6.png` 副本合成（测毕已删除）：

```
=== [D1] identical pair -> DARK_WARNING (same md5) ===
DARK_CHECK: dark=shots/c-capture/parity-dark.png bytes=36373 md5=42F8D8B80DA898B5B2B89F5020272EAF
DARK_CHECK: light=shots/c-capture/parity-light.png bytes=36373 md5=42F8D8B80DA898B5B2B89F5020272EAF
DARK_WARNING: -Dark capture is byte-identical to the light counterpart (same md5) -> -Dark may not have taken effect (skin may not implement dark theme)

=== [D2] different content -> differs ===
DARK_CHECK: dark=shots/c-capture/parity2-dark.png bytes=127440 md5=7F450951EE986654606613A42A257D9E
DARK_CHECK: light=shots/c-capture/parity2-light.png bytes=36373 md5=42F8D8B80DA898B5B2B89F5020272EAF
DARK_CHECK: dark differs from light (bytes and md5 both differ) -> dark theme probably rendered

=== [D3] same byte count, different md5 -> byte-count warning ===
DARK_CHECK: dark=shots/c-capture/parity3-dark.png bytes=36373 md5=9701B0D0FA056D85D407C5D0F1258E65
DARK_CHECK: light=shots/c-capture/parity3-light.png bytes=36373 md5=42F8D8B80DA898B5B2B89F5020272EAF
DARK_WARNING: -Dark capture has the same byte count as the light counterpart but a different md5 -> -Dark may not have taken effect; inspect the image
```

对真实截图批次（如 material 皮肤 light/dark 字节数相同的情形）可直接复用：`tools/capture-window.ps1 -Java <java> -ClassPathFile cp.txt -Bar x -OutFile y -DarkCheckOnly shots/<批>/xxx-dark.png`，零副作用，不启动任何进程。

## 实拍情况：未实拍（0 张）

按任务指令「先确认机器上没有其他 java 进程，发现有则不要拍」执行：验证窗口内本机始终有 4 个外来 java 进程（并行构建的 Gradle daemon，见 T0 快照），且本 worktree 的 `cp.txt` 与 `desktopMainClasses` 编译产物缺失，实拍需先跑一次 Gradle 构建，会与正在进行的并行构建争用资源。因此本轮交付 = 静态验证（双解析器 + 语法清单 + 点源冒烟）+ 哨兵进程动态验证（清理路径 5 个用例 + diff IDENTICAL + -Dark 三分支），全程外来 java 进程零变化。

给父代的首拍提示：拍前先 `pwsh -NoProfile -Command "Get-Process java | Select Id,StartTime"`；脚本每次运行都会打印 `launched pid=` / `window matched by=...` / `saved ... bytes md5=...` 自检行，逐张核对即可判断抓的是不是新构建；-Dark 真拍时先拍 light 再拍 dark（同目录、文件名含 `dark`），若出现 `DARK_WARNING` 即皮肤未实现暗色（MaterialSkin.kt 当前硬编码 isDark=false，与本次实测的历史现象一致）。

## 仅静态走查、未动态覆盖的部分

- 真实应用的启动 → `MainWindowHandle` 匹配路径（需要编译产物 + 无外来 java 的窗口，见上）。
- `PRINTWINDOW_FAILED` / `TRANSPARENT_CAPTURE` 分支（采集代码与旧版逐行一致，未改动，历史批次已多次真跑）。
- 同一 worktree 内两张截图并发的行为（设计上不支持，见头部注释；跨 worktree 由各自的 `shots/.capture-pid` 天然隔离）。
