# w-e2e：真链路端到端测试（RealCoreE2ETest）——原始证据

分支 `tmp/w-e2e`（基于 main @ `8f7825e`）。commit：`0b96a61`（测试 + 脚本）、RESULT 提交见 git log。
本文件只贴实测输出与参数，不含结论性词汇。

## 1. 交付物

- `composeApp/src/desktopTest/kotlin/vade/ui/e2e/RealCoreE2ETest.kt` —— 6 条测试（1 主链路 + 5 失败路径）
- `tools/e2e-run.sh` / `tools/e2e-run.ps1` —— 一条命令跑 e2e，退出码透传
- 本文件

未改动：`commonMain/**`、`build.gradle.kts`、`docs/**`、主仓任何文件。`git diff --name-only 8f7825e..HEAD` 仅含上列三个新文件（见 §7）。

## 2. 主链路实测事件序列（wire 原文，来自 desktopTest 的 JUnit stdout，未加工）

测试：`realCoreAttachSubmitStreamsOrderedTurnEvents`（8.361s）

```text
E2E-CORE port=56877 pair_code=明慎-H6HKVJ data_dir=C:\Users\LENSHLD\AppData\Local\Temp\vade-e2e-core-13783943502708272369\data
E2E-ATTACH session_id=dc41b564be3b4ce98fe5f17924945e39 from_seq=0 snapshot=true
E2E-SUBMIT turn_id=ddd4cb8f37034ce7b127e52fa8a5d13c queue_pos=1
E2E-EVENTS collected=8
E2E-EVENT idx=0 seq=1 type=SessionAttached SessionAttached(seq=1, coalesced=false, payload=SessionAttachedPayload(sessionId=dc41b564be3b4ce98fe5f17924945e39, fromSeq=0, state=open, snapshot=true, replayCount=null))
E2E-EVENT idx=1 seq=2 type=TurnQueued TurnQueued(seq=2, coalesced=false, payload=TurnQueuedPayload(turnId=ddd4cb8f37034ce7b127e52fa8a5d13c, state=queued, clientMsgId=e2e-1794656919888, rootTurnId=ddd4cb8f37034ce7b127e52fa8a5d13c, version=1, parentTurnId=null, userMessageId=7a638c85506c4c34890dac384ecafe22, reason=submit))
E2E-EVENT idx=2 seq=3 type=QueueUpdated QueueUpdated(seq=3, coalesced=false, payload=QueueUpdatedPayload(sessionId=dc41b564be3b4ce98fe5f17924945e39, running=null, runningState=null, pending=[QueuePending(turnId=ddd4cb8f37034ce7b127e52fa8a5d13c, state=queued, clientMsgId=e2e-1794656919888, enqueuedAt=1794656919900)]))
E2E-EVENT idx=3 seq=4 type=TurnStarted TurnStarted(seq=4, coalesced=false, payload=TurnStartedPayload(turnId=ddd4cb8f37034ce7b127e52fa8a5d13c, state=running, modelId=null, modelSource=null))
E2E-EVENT idx=4 seq=5 type=QueueUpdated QueueUpdated(seq=5, coalesced=false, payload=QueueUpdatedPayload(sessionId=dc41b564be3b4ce98fe5f17924945e39, running=ddd4cb8f37034ce7b127e52fa8a5d13c, runningState=running, pending=[]))
E2E-EVENT idx=5 seq=6 type=TurnDelta TurnDelta(seq=6, coalesced=false, payload=TurnDeltaPayload(turnId=ddd4cb8f37034ce7b127e52fa8a5d13c, text=E2E echo: scripted provider reply., partIndex=0, kind=text))
E2E-EVENT idx=6 seq=7 type=TurnDelta TurnDelta(seq=7, coalesced=false, payload=TurnDeltaPayload(turnId=ddd4cb8f37034ce7b127e52fa8a5d13c, text= -- done., partIndex=0, kind=text))
E2E-EVENT idx=7 seq=8 type=TurnCompleted TurnCompleted(seq=8, coalesced=false, payload=TurnCompletedPayload(turnId=ddd4cb8f37034ce7b127e52fa8a5d13c, state=completed, usage=VadeUsage(promptTokens=9, completionTokens=12, totalTokens=21, modelId=null, provider=null, source=provider, steps=1, cachedTokens=0, reasoningTokens=0), stoppedReason=stop))
```

顺序断言落在 `turn.*` 子序列上：`turn.queued(seq=2) < turn.started(seq=4) < turn.delta(seq=6,7) < turn.completed(seq=8)`，`seq` 严格递增；`turn.completed.usage.source=provider`、三 token 数 9/12/21 与 provider 脚本常量逐字段相等（防「静默兜底全零」回归的断言就在这里）。`ui/detach` 返回 `ok=true` 后收尾。

## 3. core 启动参数（测试内 launcher 实际使用的）

- 进程：`VADE_PYTHON`（默认 `D:/HermesWorkspace/Vade/Vade/.venv/Scripts/python.exe`）运行测试写入临时目录的 `e2e_core_launcher.py`
- 配置（`load_config(cli=...)`）：`core.data_dir=<一次性临时目录>/data`、`core.listen=127.0.0.1:0`（uvicorn 绑定后回读真实端口）、`dev.allow_all=true`
- provider 注入：patch `vade.session._build_llm` → `MockLLMProvider([[Delta(text=<echo>), Delta(text=" -- done.", usage={prompt:9, completion:12, total:21})]])` —— 与 `core/tests/e2e/test_turn_lifecycle.py` 同一注入缝、同一 provider 类，零外部 API
- 鉴权：launcher 内 `app.pairing.create(Purpose.BROWSER, ttl=600)` 铸配对码 → 客户端 `KtorTransportFactory` `POST /ui/session` → `__Host-ac_ui` cookie → WS `/ws`
- sys.path 注入 `VADE_CORE_PKG_DIR`（`D:/HermesWorkspace/Vade/Vade/core`）与 `VADE_PROTOCOL_PY_DIR`（`.../protocol/core/python`，`vade_protocol.dll` 在此）

## 4. 临时目录

- 形态：`Files.createTempDirectory("vade-e2e-core-")`（本次实例：`C:\Users\LENSHLD\AppData\Local\Temp\vade-e2e-core-13783943502708272369`，测试结束已删除）
- 每次运行全新目录，launcher 与 `data/` 同居一处；`kill()` 在进程死透后重试三次递归删除（Windows sqlite WAL 句柄滞留时不会静默留目录，清不掉会打印 `E2E-WARN`）

## 5. 失败路径（5 条）实测

| 测试 | 场景 | 实测报错（JUnit 捕获的断言消息/异常） | 耗时 |
| --- | --- | --- | --- |
| `coreCrashBeforeReadySurfacesExitCodeAndLeavesNoOrphan` | 假 core 启动即 `sys.exit(3)` | `CoreBootException: core exited before READY (exit=3)` | 2.437s |
| `coreBootTimeoutKillsTheProcessInsteadOfHanging` | 假 core 永不打印 READY | `CoreBootException: core did not print READY within 4000ms`，等待实测有界（<10s） | 8.806s |
| `wrongPortFailsFastWithClearErrorAndNoPythonAtAll` | 端口被非 WS 套接字占住 | `awaitConnected=false`；`VadeException: not connected (state=...)` | 2.525s |
| `wrongPairCodeSurfacesAuthFailedFromUiSession` | 真核 + 错配对码 | `VadeException code=AUTH_FAILED`，message 含 `POST /ui/session failed with HTTP ...` | 2.624s |
| `requestBudgetExpiresWithTimeoutCodeWhenCoreNeverReplies` | WS 升级后永不回帧 | `VadeException code=TIMEOUT`，message 含 `no reply within 500ms` | 1.559s |

每个测试 `finally` 两段式 `destroy() → waitFor(3s) → destroyForcibly()`，并断言 `!process.isAlive`（进程不死即测试失败）。e2e-run.sh 收尾按命令行里的 `e2e_core_launcher.py` 精确匹配杀孤儿（不碰其他 python），本次输出 `cleanup: no orphan core launcher processes`。

## 6. 如何重跑

```bash
# bash（MSYS）
tools/e2e-run.sh
# PowerShell
powershell -NoProfile -File tools/e2e-run.ps1
# 单跑测试类（等价）
/d/Hermes/tools/gradle-9.6.1/bin/gradle :composeApp:desktopTest --tests "vade.ui.e2e.RealCoreE2ETest"
```

脚本退出码 = gradle 退出码。最近一次脚本运行尾部原文：

```text
BUILD SUCCESSFUL in 41s
cleanup: no orphan core launcher processes
E2E RESULT: PASS (exit=0)
```

全量 desktopTest（`--rerun-tasks`，含本测试）原始计数：`tests=206 failures=0 errors=0`（基线 200 + 新增 6），gradle exit 0。

JUnit suite 原文：`name="RealCoreE2ETest[desktop]" tests="6" skipped="0" failures="0" errors="0" time="26.325"`（各用例耗时见 §5 表与 §2）。

## 7. 越界核对

```
$ git diff --name-only 8f7825e..HEAD
composeApp/src/desktopTest/kotlin/vade/ui/e2e/RealCoreE2ETest.kt
tools/e2e-run.sh
tools/e2e-run.ps1
shots/w-e2e/RESULT.md        ← 本文件（第二个 commit）
```

## 8. 未覆盖 / 已知边界

1. **真 LLM provider**：provider 是 core 自带 `MockLLMProvider`（脚本化），`turn.completed.usage` 的 token 数来自脚本常量而非真实计量端点；真实 provider（OpenAI 兼容 HTTP）的计费/限流/重试路径未覆盖。
2. **Noise 模块通道**：`module/*`（module register、Noise IK 握手、module 当 provider）不在本测试范围 —— 走的是 browser session（cookie）通道。
3. **tls**：核心以明文 HTTP/WS 启动（未配 `tls.cert`），`wss`/Secure cookie 的浏览器语义未覆盖（JVM 客户端不校验 Secure 位）。
4. **重连/重放**：`autoReattach`、replay 窗口、seq 缺口等行为已有 `VadeClientBehaviorTest` 的 FakeTransport 覆盖；本测试只打真核的单连接主链路，未在真核上制造断线重连。
5. **`/ui/blob` 上传、审批、ask_user**：本链路纯文本 turn，未触达这些面。
6. **端口占用场景的 core 自身行为**：失败路径里的「端口不对」是客户端视角（非 WS 套接字占端口），不是 core 绑定冲突（core 用 `:0` 由 OS 分配，天然避开）。
