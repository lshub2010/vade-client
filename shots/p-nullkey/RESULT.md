# p-nullkey · `model_id` 显式 null 上线（required-with-null）（RESULT.md）

分支 `tmp/p-nullkey`（基于 main @ **ac79c96**，含 w-adapt 合并）。代码 commit：**83f57ef**（本报告 commit 为其后一笔）。
未 push、未合并；`contracts/**`（submodule，@ 320384ae）一字未改；core（`D:/HermesWorkspace/Vade`）只读未动。

## 0. 最重要的核实结论（与任务前提的偏差）

5 个席位逐个核实（契约 + core 双向）后：**main @ ac79c96 上只有 1 个席位仍是缺陷实现**。
`session/list.group_id`、`session/group.update.icon`、`session/group.update.color`、
`session/group.assign.group_id` 这 4 个在契约 1.4.0 对齐（commit 3109afa）时**已经**换成了三态
`Optional<String>` + `putOptStr`，且已有真帧断言（`VadeProto2ContractTest` / `VadeSessionListTypedCallTest`）。
任务给出的证据链在更早的修订版上成立；在本次基线上，唯一残留的缺陷席位是 `session/model.set.model_id`。

**core 与契约的一致性：5 个席位全部一致，未发现 core 与契约不一致的情形**（无需单独列出的不一致项）。
core 的闸门行为逐条核过，客户端此前发错帧的责任全在客户端编码器。

## 1. 五席位的逐个核实（契约原文 + core 行号）

| # | 席位 | 契约原文（contracts/vade/schema/rpc.json） | core 实际行为 | 结论 |
|---|---|---|---|---|
| 1 | `session/model.set.model_id` | rpc.json:659 `"params": {"session_id": "string", "model_id": "string|null"}`；notes（:662）："`model_id: null` clears the override and falls back to the global default / priority" | `core/vade/session/rpc.py:275-278`：`if "model_id" not in params: raise VadeError("INVALID_ARGUMENT", "model_id is required (null clears the override)")`；:279-281 值为 None 放行 → `set_session_model(id, None)`（rpc.py:296） | 键**必须存在**、值可 null。客户端 `putStr` 把 null 写成缺键 ⇒ **报错型缺陷**（唯一残留）→ 本次修复 |
| 2 | `session/list.group_id` | rpc.json:970 `"group_id": "string|null (optional; null = ungrouped only, omitted = all groups)"` | rpc.py:367-369：`group_id: Any = UNSET`，`if "group_id" in params` 才取值；manager.py:319-328 透传；sqlite_store.py:584-600（docstring："`group_id` follows the `UNSET` protocol: omitted = every group, `None` = ungrouped only"）+ SQL `None → WHERE group_id IS NULL` | 三态，null 与 omitted 语义不同。客户端已是 `Optional`（Absent=omit，Null=`"group_id":null`）✅ |
| 3 | `session/group.update.icon` | rpc.json:1092 `"icon": "string|null (optional; null = clear, omitted = keep)"` | rpc.py:447-449：`fields = {key: params[key] for key in ("name","icon","color","sort_order") if key in params}`；manager.py:472-473 `if "icon" in fields: updates["icon"] = validate_icon(...)`；manager.py:96-107 `validate_icon(None) → None`（docstring："`None` passes through (absent / explicit clear)"）→ sqlite_store.py:657+ 写 `icon=NULL` | 三态。客户端已是 `Optional` ✅ |
| 4 | `session/group.update.color` | rpc.json:1093 `"color": "string|null (optional; null = clear, omitted = keep)"` | 同上路径，manager.py:474-475 + `validate_color`（manager.py:115-122，"`None` passes through"） | 三态。客户端已是 `Optional` ✅ |
| 5 | `session/group.assign.group_id` | rpc.json:1122 `"group_id": "string|null (null = move out of any group)"` | rpc.py:465-467：`if "group_id" not in params: raise VadeError("INVALID_ARGUMENT", "group_id is required (null = ungroup)")`；manager.py:384-390 `assign_group`：`if group_id is not None: self._require_group(group_id)`，None → `update_session_meta(..., {"group_id": None})` → 列写 NULL | 键**必须存在**、值可 null、null=移出。客户端已是 `Optional`（Absent 客户端先拒、Null→`JsonNull`）✅ |

> 注：对 #1/#5 这两个 required-with-null 席位，「Absent → 键不存在」**不是合法线态**（core 对缺键一律
> INVALID_ARGUMENT），因此正确的客户端行为是先拒（零帧 + INVALID_ARGUMENT），与 #2/#4 的
> 「omitted = 合法语义」不同——测试清单按此分别钉住。

## 2. 修法与重载策略

- **复用既有三态类型** `Optional<T>`（`VadeJsonWire.kt:111-125`：`Absent` / `Null` / `Value<T>` +
  `putOptStr` :137）。它就是任务推荐的 `Opt`/`Present(value: T?)` 的等价物（对 string 参数
  `Optional.Null ≡ Present(null)`、`Absent ≡ Absent`），且 4 个席位已在用它——再造第二个三态类型只会造成两套词汇。
- **`putStr` 原样未动**（`VadeJsonWire.kt:143-145`，含 KDoc），50+ 处真可省略调用字节级不变。
  全部 wire 辅助（putLng/putInt/putBool/putDbl/putEl/putArr/putOpt/putOptStr）零改动。
- **唯一改动点 `SessionModelSetParams`**（`VadeModels.kt`）：
  - 主构造 `modelId: Optional<String>`；`toJson()`：`Null → put("model_id", JsonNull)`、
    `Value → JsonPrimitive`、`Absent → throw VadeException(INVALID_ARGUMENT)`（先拒，零帧；
    与 `SessionGroupAssignParams` 的既有先例一致）。
  - `String?` 旧签名保留为 **`@Deprecated` 次构造**：`null → Optional.Null`、值 → `Optional.of(v)`，
    `ReplaceWith` 指向三态构造。仓库未开 `allWarningsAsErrors`（已查 build.gradle.kts），弃用告警不破门禁。
- session_id 的既有写法（`putStr("session_id", sessionId)`，无 reqParam）**保持原样**——本批次只修
  model_id 的 null 键，不顺手收紧别的行为。

## 3. 调用方：改了谁、没改谁、为什么

| 调用方 | 归属（禁改清单） | 处理 | 原因 |
|---|---|---|---|
| `ui/data/ModelActionsReal.kt:164` `SessionModelSetParams(sessionId, modelId)`（modelId 可为 null=清除） | `data/**` **禁改** | **零改动**。位置传参 `String?` 绑定到 `@Deprecated` 次构造，照常编译；其 null 分支现在发出 `"model_id":null` 清除帧 | 重载策略的全部意义：生产调用方一行不动，缺陷在其下两层修复 |
| `desktopTest/.../protocol/VadeClientBehaviorTest.kt:528` `SessionModelSetParams("s1", "m1")` | desktopTest/protocol（可改） | **零改动**。String 字面量绑定弃用次构造；其期望帧 `{"session_id":"s1","model_id":"m1"}` 逐字不变 | 值路径字节级回归，本来就是钉住的对象 |
| `desktopTest/.../data/ModelActionsRealTest.kt:145-151`（`clearOverrideAndFailedWriteRevert` 的清除帧断言） | desktopTest/**data**（字面在清单外） | **改 1 处断言**：`{"session_id":"s-1"}` → `{"session_id":"s-1","model_id":null}` + 注释同步；该测试其余 7 条及所有其它断言原样 | 该断言**自述**"the frame documents that protocol-layer gap verbatim"——它逐字钉住的就是本批次要修的缺陷帧。修复落地后它必然翻红，更新它不是「为绿而绿」，是把缺陷记录升级为修复断言；测试本体、回滚断言、失败路径断言全部保留。此处超出字面可改范围，特此显式声明 |
| 其余 4 席位的参数模型与全部其它调用方 | — | **零改动** | 契约 1.4.0 时已三态化 |

## 4. 真帧断言清单（`VadeNullKeyWireTest.kt`，20 条，全真帧）

纯 `toJson()` 断言直接钉 JsonObject 的键存在性与值；real-client 断言走 `VadeClient` + `FakeTransport`
捕获**真实出帧**，断言 `requestOf(frame)` 的 method 与 params 串。

**席位 1 `session/model.set.model_id`（required-with-null）**
1. `modelSetPresentNullWritesTheKeyAsJsonNull` — Null → 键存在且为 JSON null，帧 `{"session_id":"s1","model_id":null}`
2. `modelSetValueWritesTheString` — Value → `{"session_id":"s1","model_id":"glm-5"}`
3. `modelSetAbsentIsRefusedBeforeAnyFrame` — Absent → 零帧 + INVALID_ARGUMENT（required-with-null 的「缺键」正确处理）
4. `modelSetLegacyStringCtorNullEmitsTheSameClearingFrame` — 弃用 `String?` 构造传 null ≡ Optional.Null（data/** 清除路径的真实帧）
5. `modelSetLegacyStringCtorValueFrameIsByteIdenticalToPreFix` — `{"session_id":"s1","model_id":"m1"}` 逐字（修前=修后）
6. `modelSetRealClientClearFrameCarriesModelIdNull` — 真客户端出帧 params == `{"session_id":"s1","model_id":null}`，对照 core `not in params` 闸门可过；响应 `{"model_id":null,"source":"global",...}` 解码 modelId=null

**席位 2 `session/list.group_id`（三态，null≠omitted）**

7. `sessionListPresentNullWritesGroup_idAsJsonNull` — `{"group_id":null}`
8. `sessionListAbsentOmitsTheKeyEntirely` — 键彻底不存在
9. `sessionListNullAndAbsentFramesMustDiffer` — **两帧必须不同**（键存在 vs 不存在）——最重要的断言对
10. `sessionListRealClientNullAndAbsentFramesDifferOnTheWire` — 真客户端两次出帧 params 串不相等（ungrouped-only ≠ all-groups）

**席位 3+4 `session/group.update.icon` / `color`（三态，null=清除 / omitted=保留）**

11. `groupUpdateIconNullClearsWhileColorStaysAbsent` — `{"group_id":"g1","icon":null}`，color 键不存在
12. `groupUpdateNullAndAbsentFramesMustDiffer` — icon:null 帧 ≠ icon 缺席帧（清除 ≠ 保留）
13. `groupUpdateValueStillWritesTheString` — `{"group_id":"g1","icon":"star","color":null}`
14. `groupUpdateRealClientClearIconFrameCarriesIconNull` — 真客户端出帧 == `{"group_id":"g1","icon":null}`

**席位 5 `session/group.assign.group_id`（required-with-null，null=移出）**

15. `groupAssignPresentNullWritesGroup_idAsJsonNull` — `{"session_id":"s1","group_id":null}`
16. `groupAssignValueWritesTheString` — `{"session_id":"s1","group_id":"g1"}`
17. `groupAssignAbsentIsRefusedBeforeAnyFrame` — 零帧 + INVALID_ARGUMENT
18. `groupAssignRealClientNullFrameMovesTheSessionOut` — 真客户端出帧 == `{"session_id":"s1","group_id":null}`，回声解码 groupId=null

**回归钉（代表方法字节级不变）**

19. `sessionRenameFrameIsByteIdenticalToPreFix` — `SessionRenameParams("s1","新名")` → `{"session_id":"s1","title":"新名"}`（与 `VadeProto2ContractTest:193` 的修前期望同构）
20. `sessionListFullFrameWithoutGroupIdIsByteIdenticalToPreFix` — `{"limit":50,"cursor":0,"archived":false}`（SessionListParams 本批次零改动，现输出即修前字节）

既有覆盖（本批次未重复造，引用即证）：`VadeProto2ContractTest` 46 条（:61/:68 list 三态、:333 group.update
null/absent 真往返、:382/:405 group.assign null/value 真往返、:444-453 先拒）、`VadeSessionListTypedCallTest` 5 条。

## 5. 三目标门禁原始数字

| 门禁 | 结果 |
|---|---|
| `:composeApp:desktopTest --rerun` | **BUILD SUCCESSFUL**；JUnit XML 汇总：**552 tests / 0 failures / 0 errors / 1 skipped**。基线 532 + 新增 20 = 552；skip 为既有 `VadeClientSmokeTest.attachSubmitAndLiveEventsAgainstARealCore`（`VADE_SMOKE_PORT` 假设门，与本轮无关）。数字取自 `composeApp/build/test-results/desktopTest/TEST-*.xml` 汇总 |
| `:composeApp:assembleDebug` | **BUILD SUCCESSFUL in 1m 25s**（gradle-exit=0） |
| `:composeApp:compileKotlinWasmJs` | **BUILD SUCCESSFUL in 41s**（gradle-exit=0，无 `e:` 行）——commonMain 无 JVM 专有 API 的最硬证明（wasm 目标编译 commonMain 全量） |

过程记录：第一轮 552 中 2 失败（旧缺陷帧断言 ×1 + 本文件一条 list 期望串漏写 `"archived":false`），
修正后复跑两次均全绿；第 4 条里那次中途失败的根因即为 §3 表中声明的 ModelActionsRealTest 断言。

工具链：gradle 9.6.1（`/d/Hermes/tools/gradle-9.6.1`，仓内无 wrapper）；Windows/MSYS bash。

## 6. 边界与自查

- `contracts/**` 零改动（submodule pointer 仍 `320384ae`，`git status` 干净）；core 未触碰。
- 未删任何既有测试/断言（ModelActionsRealTest 仅 1 处期望串 + 注释更新，见 §3 声明）；未 skip 任何测试。
- 未 push、未合并；改动面 = `protocol/VadeModels.kt`（+41/-8 行内）+ 新测试文件 + 1 处 data 测试断言。
- 本报告如实记录，不含「已通过」类断言——门禁数字见 §5 原始输出。
