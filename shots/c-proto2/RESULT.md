# RESULT — c-proto2：客户端协议层对齐契约 1.4.0

worktree：`D:/HermesWorkspace/Vade-client-wt/c-proto2`，分支 `tmp/c-proto2`（基于 main @ f189de2）。
未 push、未合并。

## 提交

| commit | 内容 |
|---|---|
| `6fab11c` | contracts/vade submodule pin 升级（独立提交） |
| `3109afa` | 12 个方法 + 7 个事件 codec、护栏、46 条新测试 |
| （本文件） | RESULT.md |

## submodule pin

- 旧 pin：`5a95d2a5b17b1546b2087d855d1dba1e44653f14`（rpc.json 1.0.0，ui_to_core 6 个方法）
- 新 pin：**`ba25628`**（主仓 `dev_gingshan` 当前 HEAD，`docs(status): 登记 RFC-0010/0012/0013/0003-impl/ask_user …`）
- 拉取方式：`git submodule update --init`（GitHub origin 直连成功，**没有**用本地 remote，.gitmodules 未改动）
- 校验：checkout 后 `contracts/vade/schema/rpc.json` version = **1.4.0**、`ui_to_core` = **33**、`events.json` events = **34**；与主仓 `D:/HermesWorkspace/Vade/Vade/schema/` 两份文件 `diff -q` 完全一致。

## desktopTest 原始数字（`gradle :composeApp:desktopTest --rerun`）

```
200 tests completed, 0 failed, 0 errors, 1 skipped
```

（按 `composeApp/build/test-results/desktopTest/TEST-*.xml` 的 tests= 求和；基线 154 + 新增 46 = 200。
skipped 的 1 条是既有的 `VadeClientSmokeTest.attachSubmitAndLiveEventsAgainstARealCore`，
由 `VADE_SMOKE_PORT` 环境变量 Assumption 门控，与本次改动无关，未动过。）
新增的 `VadeProto2ContractTest[desktop]`：tests=46，failures=0。

## 覆盖数

- **方法 21/33**（任务书里写 19/33，实际起点是 21：`message/edit_resubmit`（§14）与 `task/log_tail`（§16）在 f189de2 已有 codec；本轮真缺 12 个，全部补齐 → 33/33）：
  subagent/list、subagent/get、session/list、session/rename、session/pin、session/archive、session/delete、session/group.list、session/group.create、session/group.update、session/group.delete、session/group.assign。
- **事件 33 topic + turn.state 枚举行 = 34/34 键**。任务书列的 8 个里，`turn.state` 经查 events.json（契约 1.4.0）原文自述 **"Not an event: the enum every state-change turn.* payload's state takes"** —— 它没有 payload，是枚举行（8 个冻结值）。既有 `TurnState.fromRaw` 已覆盖全部 8 值（VadeContractFramesTest.unknownTurnStateRendersAsRunning 逐值断言）。因此新增 7 个真 topic 的解析：session.updated、session.list_changed、subagent.spawned、subagent.status、subagent.result、turn.skill_catalog、turn.tool_discovered；`VADE_EVENT_TYPES` = 33，测试断言 `VADE_EVENT_TYPES + {"turn.state"} ==` events.json 全部 34 键，双向钉死，没有发明假事件。

## 新增测试 46 条（VadeProto2ContractTest）

- 12 个方法各 ≥1 条 FakeTransport round-trip（断言真实帧的 method + params JSON 串 + 响应解码字段），session/pin、session/group.assign 各 2 条（pin/unpin、assign/unassign）。
- `session/list` 三态各一条：缺省 group_id → `{}`（全部组）/ `Optional.Null` → `{"group_id":null}`（只看未分组）/ `Optional.of("g1")` → `{"group_id":"g1"}`（指定组）；group.update 的 icon/color 同型三态也在 round-trip 里逐帧验证（null=清除、缺省=保留同帧可见）。
- 必填缺失 → `INVALID_ARGUMENT`：blank title/name/id 共 10 条断言（含 limit 1..200、cursor≥0、lines/max_bytes/wait_ms 范围），并有 1 条走 client 的测试证明护栏在发帧**之前**触发（transport.record.size==0）。
- required 结果缺失 → `PayloadDecodeException`：session/rename 缺 `session`、group.create/update 缺 `group`、subagent/list 缺 `runs`（含 `"runs":null` 不是空列表）、session/list 缺 `sessions`、SessionRow 缺标量（updated_at/session_id）、group.delete 缺 `unassigned`；另有 1 条经 client 验证映射为 `PROTOCOL_MISMATCH`。
- 7 个新事件各 ≥1 条 payload 解析；subagent.spawned 两条专门验证 required-with-null（`parent_turn_id:null` 合法 vs 缺键即弃帧）；未知事件（session.cluster_rebalanced）不崩且 last_seq 前进。
- SubagentState 枚举钉 5 冻结值 + UNKNOWN 兜底；34 键覆盖钉。

## 手写 codec 的做法依据

- **无 kotlinx-serialization 编译器插件**：只有 runtime jar（经 Ktor content-negotiation 上类路径），`@Serializable` 编不过序列化器；build.gradle.kts 未动、未加插件、未加依赖。沿用 VadeJsonWire.kt 里既有注释声明的原因：契约冻结、逐字段显式映射、零反射（wasm-safe）。
- 请求侧：data class + 手写 `toJson()`（buildJsonObject + putStr/putLng/putInt/putBool/putArr，optional 键 null 即缺省不发）；结果侧：手写 `fromJson()`（safe 访问器 str/lng/int/bool/obj/arr + 新增 `must*` 必需访问器，缺 required 键抛 `PayloadDecodeException`，经 `VadeClient.call` 映射 PROTOCOL_MISMATCH）。
- 三态参数用新增 `Optional<T>`（sealed：Absent/Null/Value）+ `putOpt`/`putOptStr`，nullable String 表达不了"键存在且值为 null"。
- 字段名一律取自契约 verbatim（snake_case）；enum 取值一律 raw 字符串 + fromRaw 兜底（UNKNOWN/RUNNING），不猜未声明值。
- 既有 21 方法的 codec 除给 edit_resubmit/log_tail 的 toJson 加必填/范围护栏外一字未改（护栏不改变合法输入的输出帧，VadeClientBehaviorTest 的 21 方法逐帧 pin 测试原样全绿）。

## 仍缺项 / 备注

- 无缺方法、无缺 topic。方法覆盖 33/33；事件 33 topic + 枚举行 = 34/34 键。
- 结果域里 `SubagentRunView` 为 subagent/list 与 subagent/get 共用（RFC-0012 §6.2 结果字段与 §6.1 的 view 完全一致）。
- 改动文件（均在许可范围内）：`vade/ui/protocol/{VadeModels,VadeEvents,VadeJsonWire,VadeEnums,VadeClient}.kt`、desktopTest 的 `VadeProto2ContractTest.kt`（新）与 `VadeContractFramesTest.kt`（26→33 覆盖 pin 与 7 个新 topic 样本——这是契约版本升级必须的断言更新，未删任何既有测试或断言）、`contracts/vade` pin。
