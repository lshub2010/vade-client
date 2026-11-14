# w1-proto RESULT — 协议层（21 方法 / 26 事件）client 侧收尾

分支 `tmp/w1-proto`（基于 `802c532`，工作区干净提交，未 push）。
范围：`composeApp/src/commonMain/kotlin/vade/ui/protocol/`（8 文件）+ desktopTest 4 文件 + `shots/w1-proto/`。

## 构建与测试（全部真实执行）

| 检查 | 结果 |
|---|---|
| `gradle :composeApp:desktopTest --rerun` | **BUILD SUCCESSFUL**，XML 逐文件核对：**140 tests / 0 failures / 0 errors / 1 skipped** |
| 其中原有测试（ChatFlow 23, Markdown 13, MaterialImportGuard 2, MotionBlurBoxKernel 12, MotionBlurMath 10, MotionBlurTriangleKernel 13, SearchFlow 19） | **92**，全绿 |
| 其中新增协议测试（VadeClientBehaviorTest **23** + VadeContractFramesTest **24** + VadeClientSmokeTest **1**） | **48**（目标 ≥20），23+24 全绿，冒烟 1 条真跑通过 |
| `gradle :composeApp:compileKotlinWasmJs` | **BUILD SUCCESSFUL**（GRADLE_EXIT=0） |
| 真核冒烟 | **通过**，见 `smoke.md`（attach/submit/8 个 seq 连续事件/usage.get 全部真实输出） |

跳过的 1 条 = `VadeClientSmokeTest`：`org.junit.Assume` 门控（无 `VADE_SMOKE_PORT` 时标记 skipped，
不是静默 pass）；冒烟轮设置了环境变量，真跑通过。

## 覆盖清单

### 21 个 ui_to_core 方法（`methodAndParamsOfEveryCallArePinnedOnTheWire` 逐条断言 method + 精确 params JSON）

`ui/attach`, `ui/detach`, `ui/submit`, `ui/interrupt`, `ui/queue.cancel`, `approval/decide`,
`model/list`, `session/model.get`, `session/model.set`, `session/usage.get`, `turn/input`,
`turn/input.cancel`, `turn/regenerate`, `message/edit_resubmit`, `task/list`, `task/log_tail`,
`task/cancel`, `subagent/message`, `ui/page.get`, `ui/state.get`, `ui/tool.call` — 21/21。
params 断言为精确字面量（如 attach→`{"session_id":"s1","last_seq":7}`、model/list→`{"if_revision":4}`、
tool.call→`{"module_id":…,"tool":"ping","args":{},"client_call_id":"cc1"}`），与手写 codec 的
字段顺序逐字一致；冒烟另在真 core 上实跑 attach/detach 路径的 attach、submit、session/usage.get。

### 26 个 core→UI 事件（`allDeclaredEventTypesParseIntoDistinctSubtypes` 走 `VADE_EVENT_TYPES` 全表）

`session.attached`, `turn.queued`, `turn.started`, `turn.delta`, `turn.tool_call`, `turn.tool_result`,
`turn.approval_required`, `turn.approval_resolved`, `turn.input_required`, `turn.input_resolved`,
`turn.completed`, `turn.failed`, `turn.interrupted`, `turn.cancelled`, `queue.updated`,
`module.status`, `task.started`, `task.status`, `task.progress`, `task.finished`, `session.turn`,
`session.closed`, `skill.proposed`, `usage.budget`, `turn.superseded`, `message.edited` — 26/26，
每个 topic 一个独立子类型 + 合同样例 payload decode。行为层另有：未知事件忽略且 last_seq 推进、
重复 seq 去重、gap 回调不断流、coalesce 透传、replay 先于 session.attached、快照 attach 采纳
from_seq、反向 RPC 回 UNSUPPORTED、坏帧不杀 reader、keepalive 无 id 通知、指数退避封顶 30s、
断线重连携带 last_seq 重挂、close 后 RPC 快速失败、超时映射 TIMEOUT 且清 pending、迟到回包被丢弃。

### 错误码

34 个 C-04 名称全部钉死（`errorCodesPinAll34DeclaredNames`，无重复）；客户端自身超时/断线复用
`TIMEOUT` / `UNAVAILABLE` 名称，不发明新码。

## 为何手写 codec（不改 build.gradle.kts）

`composeApp/build.gradle.kts` 冻结矩阵**没有** kotlinx-serialization 编译器插件（运行时 jar 只经
Ktor content-negotiation 进 classpath）——`@Serializable` 能编译但不会生成 serializer，运行时即炸。
协议层钉死在冻结合同上，显式字段映射反而更好：每个 key 只出现一次、紧挨其合同出处，零 codegen、
零反射（wasm 安全）。

## 测试预期调整清单（逐条理由；未删任何断言）

1. **`FakeTransport.kt` 补 `import kotlinx.serialization.json.longOrNull`**（纯编译修复）。
2. **Expectation 构造改全具名**：原 21 条只传 2 个位置参数无法编译；补 `method`/`params` 字面量，
   并把循环里宽松的 `params.isNotEmpty()` 升级为 `assertEquals(expectation.params, params)`——
   测试名叫 "pinned on the wire"，params 字面量本来就是它的意图。
3. **`toolCall` 调用补 `args = JsonObject(emptyMap())`**：`ToolCallParams.args` 无默认值，原调用缺参
   无法编译；一阶段无参 tool call 合法（args 摘要对 `{}`）。
4. **冒烟 `Assume` 改 JUnit4**：`kotlin.test` 无 Assume API（2.4.20 也没有），desktop 测试跑在
   kotlin-test-junit 上，用 `org.junit.Assume` 语义不变（skip 而非 fail）。
5. **冒烟 session id 断言重写**：原 `assertEquals("ui-", attach.sessionId.take(0) + attach.sessionId)`
   实际断言 `sessionId == "ui-"`，对真 core 必挂；core 实际铸 `uuid4().hex`（核对
   `core/vade/session/models.py new_id`），改为 32 位 hex 格式断言（更强且为真）。
6. **事件收集 7 处 + 冒烟改订阅握手**：`client.events` 是无 replay 的热 SharedFlow，reader 在自己
   线程 emit——collector 未 dispatch 完就 push 会静默丢事件（首轮 3 个失败 + 1 个无限挂起都源于此）。
   `onSubscription { … }` 握手只消除竞态，断言零改动。
7. **`transportDeathFailsPendingAndReconnects`**：失败 `async` 子协程按结构化并发取消 runBlocking
   父级，`assertFailsWith` 永远执行不到 → async 内 `runCatching`，用 `exceptionOrNull` 断言；
   断言语义不变（仍要求 VadeException/UNAVAILABLE）。
8. **`lateReplyForTimedOutRequestIsIgnoredGracefully`**：`awaitSent()` 拿到的是已超时请求的陈旧帧
   （channel 里还在），第二个请求永远收不到回复 → `next.await()` 无超时挂死整轮测试（首轮 420s
   超时的元凶）。改为 `waitUntil { record.size >= 2 }` 后取 `record.last()`——意图不变（迟到回包被
   丢弃、后续请求正常）。
9. **`unknownEventAdvancesLastSeqWithoutEmission`** 补 `waitUntil { events.isNotEmpty() }`：last_seq
   在 emit 派发**之前**更新，原断言与派发竞态；断言本身不变（恰好 1 条事件）。
10. **`turnDeltaBufferKeepsPartsSeparateAndJoinsInOrder`**：`segments.size` 期望 5→4。segments 按
    (turn, part, kind) 去重计数，delta 2 按设计并入 delta 1 的段；4 条文本断言全部原样保留。
11. **`unknownTurnStateRendersAsRunning`**：`fromRaw("waiting_input")` 期望 RUNNING→WAITING_INPUT。
    核对 `schema/events.json`：`turn_states` 冻结表**含** waiting_input，rule 6 的 running 回退只适用
    于客户端不认识的状态；本测试自己的第二条断言（8 个冻结态映射到 8 个不同值）已隐含此语义。
12. **codec 修复（commonMain，测试是对的）**：`TurnCompletedPayload.fromJson` 原来把缺失/形状不对的
    `usage` 静默兜底成全零对象；`events.json` 的 turn.completed 明确 `required: [turn_id, state,
    "usage": "object"]` → 改为 `o.reqObj("usage")` 抛 PayloadDecodeException，事件被丢弃并回调
    onMalformed——与 VadeEvents 自身文档"形状错误即丢弃、流不断"一致。

## 未实现 / 已知边界（如实）

- **冒烟轮的模型层**：core 的 loop 引擎在无模块在线时走 notice 短路（"No capabilities are
  available…"），冒烟配置的 stub LLM（`[llm] provider=openai, base_url=http://127.0.0.1:<stub>/v1`）
  未被实际调用（usage 全 0、`by_model` 行可证）。协议层（配对→cookie→WS→attach→submit→事件→usage）
  全程真实；`openai_compat` 出网的完整流式轮未在本环境端到端跑通（无网络/无 API key）。
- **wasm 端**：只验证 `compileKotlinWasmJs` 编译绿；wasm 运行时冒烟未做。浏览器 pairing
  （Set-Cookie 不可读，需 page shell 配对）在 `KtorTransportFactory` 文档注明，本批未实现。
- **反向 RPC**：v1 core→UI 无请求；客户端按 JSON-RPC 规范回 `UNSUPPORTED`（有测试钉死）。
- **TLS**：冒烟按任务书刻意明文（plain HTTP data plane）。
- `approval/decide`、`subagent/message` 等真核路径未进冒烟脚本（浏览器会话上 core 按 design 回
  APPROVER_INVALID / UNSUPPORTED，行为层仅以 FakeTransport 覆盖 wire 形状）。

## 产物

- `composeApp/src/commonMain/kotlin/vade/ui/protocol/`：VadeJsonWire / VadeException / VadeEnums /
  VadeContent / VadeModels / VadeEvents / VadeClient / VadeTransport（本批仅 1 处合同性修正：
  VadeEvents `TurnCompletedPayload.usage` 改为必填对象——上面调整清单第 12 条）。
- `composeApp/src/desktopTest/kotlin/vade/ui/protocol/`：FakeTransport / VadeContractFramesTest /
  VadeClientBehaviorTest / VadeClientSmokeTest。
- `shots/w1-proto/`：smoke_core.py（+stub LLM）、smoke.md、smoke_run.log、smoke_logging.init.gradle.kts、
  desktopTest.log、wasm.log、RESULT.md。
