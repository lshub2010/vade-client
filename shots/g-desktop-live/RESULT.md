# g-desktop-live · 用户故事实跑：按文档跑 core → 看码 → 开桌面 → 配对 → 看真数据

> 本棒是**验收**，不是开发：`Vade/Vade/core/**` 与 `composeApp/src/**` 零改动。
> 全部命令在 Windows 11 + MSYS bash 实跑（2026-11-14/15）。仓内唯一新增：
> `shots/g-desktop-live/{RESULT.md, seed_session.py, *.png}`。
> **配对码不在本文件里**：只写前 2 字符 + 长度 + 出现位置。

---

## 0. 环境与输入

| 项 | 值 |
| --- | --- |
| 客户端 worktree | `D:/HermesWorkspace/Vade-client-wt/g-desktop-live`（`tmp/g-desktop-live`，基于 `main` @ `450f262`） |
| 主仓 | `D:/HermesWorkspace/Vade/Vade`（core，Python 3.12.14 venv） |
| core 端口 | `127.0.0.1:18755`（临时目录里的 `core.toml`） |
| core 数据目录 | `D:/Hermes/cache/scratch/g-desktop-live/core-data`（**新建，此前不存在**） |
| 桌面启动方式 | `tools/capture-window.ps1`（QUICKSTART §3 记载的既有截图路径：`java -cp <cp.txt> vade.ui.MainKt`） |

读过的文档：主仓 `docs/PAIRING.md`（88 行）、主仓 `docs/ALPHA.md` A1（第 31–34 行）、
客户端 `docs/QUICKSTART.md`（367 行）。

---

## 1. 步骤表

| # | 步骤 | 命令原文 | 实际输出关键行 | 截图 | 判定 |
|---|---|---|---|---|---|
| 0 | 读文档 | — | PAIRING.md 说「在客户端（App / 管理页面）的配对输入框里输入这个码即可」 | — | PASS（读完） |
| 1a | **按 PAIRING.md 原文起 core** | `python -m vade --config core.toml`（cwd=主仓根） | `D:\...\.venv\Scripts\python.exe: No module named vade` | — | **FAIL（文档命令不可执行）** |
| 1b | 加 PYTHONPATH 起 core | `PYTHONPATH='core;protocol/core/python' .venv/Scripts/python.exe -m vade --config D:/Hermes/cache/scratch/g-desktop-live/core.toml` | stdout 第 2 行 `pair_code=袋谦******（长度 9）purpose=browser expires_in=600s`；第 3 行人读横幅 | — | PASS |
| 1c | 探活 | `curl -s http://127.0.0.1:18755/healthz` | `{"ok": true, "version": "0.1.0", "policy_mode": "default"}`（HTTP 200） | — | PASS |
| 2a | 编译 + 取运行时 classpath | `gradle :composeApp:desktopMainClasses :composeApp:printRuntimeClasspath -q > cp.txt` | `real 1m41.111s`，`cp.txt` 16736 字节 | — | PASS |
| 2b | **按文档在客户端输入配对码** | 文档：在客户端配对输入框里输入 | **桌面端没有配对输入框**（`composeApp/src` 全仓无 pair-code 输入控件；ALPHA.md A1-2 亦承认） | — | **FAIL（无入口）** |
| 2c | 用环境变量配对并启动 | `VADE_SOURCE=real VADE_HOST=127.0.0.1 VADE_PORT=18755 VADE_PAIR_CODE=<码> pwsh ... tools/capture-window.ps1 -Bar fixed -Dest sessions -Open ...` | `window matched ... title='Vade · UI 骨架'`；`saved shots/g-desktop-live/02-paired-sessions.png 466x893 14886 bytes` | `02-paired-sessions.png` | PASS |
| 3a | core 侧造带独特标题的会话 | `python shots/g-desktop-live/seed_session.py --port 18755 --pair-code <码> --title E2E-cad91adc` | `POST /ui/session -> HTTP 200`；`ui/attach -> session_id=3cabd936a423404faf873d874d700833`；`session/rename -> {"ok": true, ... "title": "E2E-cad91adc"}`；`session/list -> 1 row(s)` | — | PASS |
| 3b | 界面里找这个标题 | 见 2c 启动 | 列表：`1 个会话` / 分组 `今天 1` / 行 **`E2E-cad91adc`** / `6 分钟前` | `02-paired-sessions.png` | **PASS（真数据）** |
| 3c | 点进去看消息 | `-Open 3cabd936a423404faf873d874d700833` | 标题 `E2E-cad91adc`、副标题「会话详情 · 消息流 / 工具卡为假数据」、`模型 全局默认 · 6 条消息`，内容是 gradle.properties / fs.write 审批等 **fixtures** 对话 | `03-session-open.png` | **FAIL（消息是假数据）** |
| 3d | 核对 core 侧该会话的真实消息 | `seed_session.py --attach 3cabd936...` | `ui/attach(...) -> from_seq=9`；`snapshot.messages count = 1`；`role=user text='E2E 消息：这条消息由 g-desktop-live 验收脚本写入。'` | — | 证据（core 有 1 条，界面显示 6 条假消息） |
| 4 | 不给配对码时的用户视角 | `VADE_SOURCE=real VADE_HOST=127.0.0.1 VADE_PORT=18755`（无 `VADE_PAIR_CODE`） | `0 个会话` + 固定一行「假数据 · 未连接 core（U1-a 只做骨架，U1-b 接线）」；**22 秒内无任何错误、无重试按钮** | `04-real-no-paircode.png` | 记录（静默失败） |
| 5 | 收进程 | `Stop-Process -Id <自己启的 pid> -Force` ×4 | `stopped core1/2/3/4 pid=... (confirmed gone)`；`no listener on 18755`；`no .capture-pid (clean)` | — | PASS |

### 1.1 为什么这是真数据，不是 fixtures

1. core 数据目录是**新建的空目录**（`core-data` 之前不存在，见 §5 时间戳 00:51 首次创建）。
2. 会话标题 `E2E-cad91adc` 是随机 8 hex，**`composeApp/src` 全仓 grep 不到**（fixtures 里不可能有）。
3. 该会话 id `3cabd936a423404faf873d874d700833` 由 core 的 `ui/attach` 铸造，`session/list` 返回同一行。
4. core 进程**停掉再起**（同一 data dir、新码）后，桌面仍显示这一行 → 数据来自 core 的 sqlite，不是进程内缓存。
5. 同一份 core 数据在界面里显示为 `1 个会话`，而 fixtures 是固定 6 行（`FakeSessions`）——行数与内容都对不上 fixtures。

### 1.2 一次作废的尝试（诚实记录）

第一次抓图时我把配对码误传成了 `capture-window.ps1` 的 `-Size` 参数（外层 pwsh 的 `$args` 传参写错），
应用实际是"real 但无码"启动，得到 `0 个会话`。该截图已删除，其现象与 §1 步骤 4 相同，不计入证据。

---

## 2. 文档差异清单（本棒最重要的产出）

按"文档写的 → 实际"记，全部有上文命令或代码位置支撑。

| # | 文档位置 | 文档写的 | 实际 |
|---|---|---|---|
| D1 | 主仓 `docs/PAIRING.md:15` | `python -m vade --config core.toml` | 在仓根直接跑报 `No module named vade`（包在 `core/vade`）。必须 `PYTHONPATH='core;protocol/core/python'`（Windows 分隔符是 `;`，MSYS bash 不会替你转）。**PAIRING.md 全文没提 PYTHONPATH 或 venv 解释器。** |
| D2 | `PAIRING.md:31` | 「stdout 会多出两行」（暗示第 1 行是 `pair_code=`） | 实际 stdout **第 1 行是 JSON 日志** `{"logger":"vade.app","msg":"core identity loaded"}`，`pair_code=` 在**第 2 行**，人读横幅在第 3 行。按"第 1 行解析"的脚本会拿到日志行。 |
| D3 | `PAIRING.md:59` | 「在客户端（App / 管理页面）的**配对输入框**里输入这个码即可」 | **桌面端没有配对输入框**。`composeApp/src` 全仓无 pair-code 输入控件；`ALPHA.md:33`（A1-2）自己写着「三端都没有输入配对码的首次运行界面」。唯一可用通道是 `VADE_PAIR_CODE` 环境变量（`composeApp/src/desktopMain/kotlin/vade/ui/data/SourceConfig.desktop.kt:13`）。 |
| D4 | 客户端 `docs/QUICKSTART.md`（全文） | 没有 `VADE_SOURCE` / `VADE_PAIR_CODE` 任何说明 | 这是**唯一能连上真 core 的路径**，却只存在于 `shots/g-live/PLAYBOOK.md`（证据目录，不是用户文档）。用户按 QUICKSTART 走完全程也连不上。 |
| D5 | `QUICKSTART.md:18` | 「**UI 数据** ❌ 仍是 fixtures，未连 core」 | 假。`VADE_SOURCE=real` 下列表就是 core 真数据（`02-paired-sessions.png`，1 行 `E2E-cad91adc`）。该行状态表整体过期。 |
| D6 | `QUICKSTART.md:19` | 「真连接（`ui/attach`、配对、断线重连接进 UI）❌ **UI 未接线**」 | 假。已接线：`Main.kt:141` → `sessionSourceFromEnvironment()` → `AutoConnectingSessionSource`，实测连上并拉真行。 |
| D7 | `QUICKSTART.md:171-175, 211-215`（§6） | 「客户端 UI 目前连不上它」；「配对码正常走管理面 `/admin/api/invites`；`python -m vade` **不**起管理面……冒烟驱动是直接用 core 自己的 PairingStore 铸码」 | 全节过期：`python -m vade` 现在启动即铸码并打印（`core/vade/__main__.py:announce_pair_code`，PAIRING.md 新文档即为此写），不再需要 launcher/PairingStore 旁路。 |
| D8 | `QUICKSTART.md:201` | healthz 期望 `"policy_mode": "allow_all"` | 按 PAIRING.md 的最小 `core.toml`（只有 `[core]`）实际返回 `"policy_mode": "default"`。两份文档的最小配置不一致。 |
| D9 | `shots/g-live/PLAYBOOK.md:164-166` | 「真模式下 core 不可达 → **Failed 横幅常驻**」 | 实测没有横幅：`UiState.sessionsPhase/sessionsError` **没有任何 composable 消费**（全仓 grep 只命中 `UiState.kt` 自己）。无码/连不上时界面是 `0 个会话` + 一行固定的"假数据"提示，无错误、无重试（`04-real-no-paircode.png`）。 |

### 2.1 顺带记下的产品缺陷（只记录，不修）

| # | 位置 | 现象 | 建议 |
|---|---|---|---|
| P1 | `commonMain/.../destinations/SessionsScreen.kt:64` | 列表头**硬编码**「假数据 · 未连接 core（U1-a 只做骨架，U1-b 接线）」，真数据下照显 → 用户看到真会话却被告知是假数据 | 由 `UiState.source` 决定是否渲染；真源时删除或改成连接状态 |
| P2 | `commonMain/.../shell/SessionsLoader.kt` + `UiState.kt:118-127` | Loading/Failed/错误分类齐全，但 **UI 层没人读** → 连不上时零反馈 | 在 `AppShell` 顶部接 `sessionsPhase/sessionsError`，带重试（`retrySessions()` 已存在但无人调用） |
| P3 | `destinations/SessionDetailScreen.kt` + `ChatFixtures` | 打开**真会话**，聊天流仍是 6 条 fixtures（标题副文案自认「消息流 / 工具卡为假数据」），且同一屏显示 core 的真实会话标题 → 真标题 + 假对话 | 接 `SessionsLoader.messages`/`RealSessionSource.messages`（ALPHA.md B2 已立项）；接线前至少给显式"示例数据"遮罩 |
| P4 | `desktopMain/.../Main.kt:103` | 窗口标题仍是「Vade · UI 骨架」 | alpha 改产品名 |
| P5 | core 侧 `ui/submit` | 无 LLM provider 时 turn 立即 `failed`（`no LLM provider is configured`），但用户消息已落库（snapshot 1 条） | 属预期行为，但首次运行文档应写明"没配模型也能看到自己的消息、回复会失败" |

---

## 3. 一句话结论

**第一次用 Vade 的人按文档连不上：PAIRING.md 让他"在客户端配对输入框里输入码"，而这个输入框根本不存在；唯一能用的 `VADE_PAIR_CODE` 环境变量在任何用户文档里都没写过——卡在第 2 步（配对入口）。** 即使内部人用环境变量连上，列表页仍顶着"假数据 · 未连接 core"的横幅，点开会话看到的是 6 条 fixtures 假消息（core 侧只有 1 条真消息）。

---

## 4. 截图

| 文件 | 字节 | 内容 |
|---|---|---|
| `02-paired-sessions.png` | 14886 | `VADE_SOURCE=real` + 配对码：`1 个会话`，行 `E2E-cad91adc`（真 core 数据） |
| `03-session-open.png` | 43596 | 打开同一会话：标题真、消息流是 fixtures（「消息流 / 工具卡为假数据」） |
| `04-real-no-paircode.png` | 13215 | `VADE_SOURCE=real` 但不给配对码：`0 个会话`，无错误提示 |

三张图都用 `pwsh` + `tools/capture-window.ps1`（`PrintWindow` + `PW_RENDERFULLCONTENT`）抓取，
本人都逐张开图核对过；`PrintWindow=True center=Color [A=255, ...]`（非全白/非透明）。

---

## 5. 复现路径（临时目录，**未删除**）

```
D:/Hermes/cache/scratch/g-desktop-live/
├── core.toml            # [core] listen=127.0.0.1:18755, data_dir=D:/Hermes/cache/scratch/g-desktop-live/core-data
├── core1.stdout.log     # 含未脱敏配对码（一次性，已消费/过期）—— 勿提交、勿外传
├── core1.stderr.log     # 空
├── core1.pid … core4.pid
├── core2/3/4.stdout.log
└── core-data/           # core 数据（sqlite：sessions、pairing、blob…）
```

复现要点：
1. `PYTHONPATH='core;protocol/core/python' .venv/Scripts/python.exe -m vade --config <core.toml>`；
2. stdout 第 2 行取 `pair_code=`；
3. 客户端：`VADE_SOURCE=real VADE_HOST=127.0.0.1 VADE_PORT=18755 VADE_PAIR_CODE=<码>` 起桌面；
4. 造数据：`shots/g-desktop-live/seed_session.py`（只走文档路径：`POST /ui/session` → `ui/attach` → `session/rename` → `ui/submit`）。

## 6. 进程收尾

| 对象 | 证据 |
|---|---|
| core ×4（pid 46164 / 37160 / 22284 / 32552） | 每个都 `Stop-Process -Id <该 pid> -Force` 后 `Get-Process -Id` 复核：`stopped ... (confirmed gone)`，最后一次 `no listener on 18755` |
| 桌面 ×3（capture-window 的 pid 11352 / 44724 / 41992） | 脚本自身输出 `own pid=... stopped (confirmed exited); pid record removed`；`shots/.capture-pid` 不存在 |
| 其他 java/python | **未动**。收尾时 `Get-Process java` 仍有 14 个（都是 22:16–01:11 之间别的棒起的 gradle/daemon），未杀任何一个 |
