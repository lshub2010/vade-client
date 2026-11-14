# w1-proto 冒烟取证 — 真实 core 上的 attach / submit / 事件 / usage

日期：2026-11-14。驱动：`shots/w1-proto/smoke_core.py`（起**真** core：`python -m vade --config core.toml`，
通过 core 自己的 `PairingStore` 铸造一次性浏览器配对码；`PYTHONPATH` 指向 `core/` 与
`protocol/core/python`（native identity 绑定所在））。测试：`VadeClientSmokeTest`（环境变量门控，
普通 `desktopTest` 运行时自动 skip，不是静默通过）。

## 环境（如实记录）

- core：`D:/HermesWorkspace/Vade/Vade`（`python -m vade`，healthz `{"ok": true, "version": "0.1.0", "policy_mode": "allow_all"}`）。
- LLM：本环境无网络/无 API key。驱动内置了一个 **OpenAI 兼容的 stub HTTP 服务**（`/v1/chat/completions`，
  SSE 两段文本 + finish + usage + `[DONE]`）并写进 `[llm]` 配置，让模型可解析。
- **实际发生**：core 的 loop 引擎在"无任何模块在线"时走自己的 notice 短路路径——第二轮的
  `turn.delta` 文本是 core 生成的 "No capabilities are available: no module is online…"，stub 从未被调用
  （usage 全 0，`by_model` 里 `builtin/stub-model turns=1 totalTokens=0` 可证）。即：**协议层全程真实**
  （配对→cookie→WS→attach→submit→事件流→usage），模型层被 core 的无能力路径替代。
- 第一轮（未配 `[llm]`）submit 后 core 正确回 `turn.failed`（模型不可解析）——同样是真实协议行为；
  由此把冒烟测试的断言改为"完成轮必须有 delta、失败轮记录原因"（见 RESULT.md 调整清单）。

## 第二轮真实输出（`VadeClientSmokeTest` PASSED，0 failures / 0 skipped）

```
SMOKE ui/attach -> AttachResult(sessionId=8488050c39f649338930012fbbeaf276, fromSeq=0,
    snapshot=VadeSnapshot(fromSeq=0, session=SnapshotSession(id=8488050c…,
    createdAt=1794645721459, title=null, state=open, seqCounter=0,
    model=SnapshotModel(modelId=builtin/stub-model, source=priority)), messages=[], turns=[],
    queue=SnapshotQueue(running=null, runningState=null, pending=[]), pendingApprovals=[],
    pendingInputs=[], tasks=[], tasksTruncated=false))
SMOKE snapshot.from_seq=0 hasTasks=true
SMOKE ui/submit -> SubmitResult(turnId=7b2a7edf733e46b9be13ec8db32d9000, queuePos=1)
SMOKE event seq=1 SessionAttached session=8488050c… snapshot=true
SMOKE event seq=2 TurnQueued turn=7b2a7edf…
SMOKE event seq=3 QueueUpdated
SMOKE event seq=4 TurnStarted
SMOKE event seq=5 QueueUpdated
SMOKE event seq=6 TurnDelta turn=7b2a7edf… part=0 text=No capabilities are available: no module is online, so I can…
SMOKE event seq=7 TurnCompleted turn=7b2a7edf… tokens=0
SMOKE event seq=8 QueueUpdated
SMOKE session/usage.get -> SessionUsageGetResult(sessionId=8488050c…,
    chat=ChatUsage(promptTokens=0, completionTokens=0, totalTokens=0, turns=1, partialTurns=1),
    aux=AuxUsage(totalTokens=0, calls=0), totalTokens=0,
    byModel=[ModelUsage(modelId=builtin/stub-model, totalTokens=0, turns=1)],
    day=DayUsage(day=2026-11-14, coreTotalTokens=0, coreLimit=0, sessionTokens=0, state=off),
    budget=BudgetUsage(scope=session, limit=0, used=0, ratio=null, state=off, enforced=false))
```

验证点（全部来自真 core 的回答）：

- `ui/attach`（首次，不带 session_id/last_seq → 线上 params 为 `{}`）→ core 铸造 session id
  （`uuid4().hex` 32 位，与 `core/vade/session/models.py new_id` 一致）并返回完整快照。
- `ui/submit`（`client_msg_id` 幂等键 + 单 text part）→ `turn_id` + `queue_pos`。
- 事件流：`seq` 严格 1..8 递增；`SessionAttached(snapshot=true)` 收口 replay；`turn.delta` 带
  `part_index=0`；`turn.completed` 携带 usage 结构。客户端 `last_seq` 跟随到 8。
- `session/usage.get`：完整 decode 出 chat/aux/by_model/day/budget 五段。
- 断言全部通过：`turn.queued`、`turn.delta`、`session.attached`、snapshot=true、lastSeq 推进。

## 运行命令

```bash
# 1) 起真 core + 铸配对码（后台，stdout 首行是 JSON banner）
.venv/Scripts/python.exe shots/w1-proto/smoke_core.py > shots/w1-proto/smoke_banner.json

# 2) 用 banner 里的 port/pair_code 跑冒烟（显示测试 stdout 用 init-script，不改 build 文件）
VADE_SMOKE_HOST=127.0.0.1 VADE_SMOKE_PORT=<port> VADE_SMOKE_PAIR_CODE=<pair_code> \
  gradle --init-script shots/w1-proto/smoke_logging.init.gradle.kts \
  :composeApp:desktopTest --tests "*VadeClientSmokeTest*" --rerun
```

原始日志：`smoke_run.log`（测试 stdout）、`smoke_core.err`（core stderr）、`smoke_banner.json`。
