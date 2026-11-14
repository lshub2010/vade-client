# p-search RESULT — session/search codec + 契约覆盖守卫

- 分支 `tmp/p-search`，代码 commit **`eb6007d`**（基线：worktree 先 `merge --ff-only` 到 main **`2cf917b`**，无冲突，协议层零改动）
- 本轮改动仅限：`composeApp/src/commonMain/kotlin/vade/ui/protocol/{VadeModels,VadeClient,VadeJsonWire}.kt` + `composeApp/src/desktopTest/kotlin/vade/ui/protocol/` 三个新测试文件
- 未动：`vade/ui/data/**`、`shell/**`、`chat/**`、`build.gradle.kts`、`contracts/vade`（submodule pin 320384a 未动）
- 未引入 kotlinx-serialization / @Serializable（全手写 codec）；commonMain 无 JVM 专有 API（见 §5）

## 0. 原始数字（不写"已通过"，只给输出）

| 门禁 | 原始输出 |
|---|---|
| `:composeApp:desktopTest --rerun` | `tests=386 failures=0 errors=0 skipped=1`（XML 聚合 36 个 TEST-*.xml；基线 2cf917b 为 361，本轮 +25） |
| `:composeApp:compileKotlinWasmJs` | `BUILD SUCCESSFUL in 31s`（与 assembleDebug 同一 gradle 进程） |
| `:composeApp:assembleDebug` | `BUILD SUCCESSFUL`（packageDebug → assembleDebug 全链执行） |
| 守卫变异自验 | 删掉 `SESSION_SEARCH` 枚举条目 + wrapper 后，仅跑守卫类：`VadeContractCoverageGuardTest > everyUiToCoreContractMethodHasAClientCodec FAILED`，报错原文 **`client has NO codec for contract methods [session/search]`**；还原后 `git diff` 确认无残留（MUTATED-OUT 标记 0 处） |
| 新测试分布 | VadeSessionSearchCodecTest 17 / VadeSessionListTypedCallTest 5 / VadeContractCoverageGuardTest 3（skipped=1 是既有 VadeClientSmokeTest 的既有 skip，非本轮） |

## 1. session/search codec（VadeModels.kt，"34. session/search" 节）

按契约 `rpc.json` §directions.ui_to_core.session/search 原文（1.5.0，RFC-0014 §4/§5）逐字实现：

- **请求**（`SessionSearchParams`）：`query`（required，trim 后 1..200 **码点**）、`limit`（1..100，缺省 20）、`offset`（>=0，缺省 0）、`session_id`（可选）。全部**发帧前**校验，违规抛 `VadeException(INVALID_ARGUMENT)`；`query` 上线原样传输（校验用 trim 后副本，不改写输入）。
- **响应**（`SessionSearchResult` + `SessionSearchHit`）：`results`（required array，`mustArr`）、`total`（required，`mustInt`）；命中行逐字段 `mustStr/mustStrOrNull/mustLng/mustLngOrNull/mustBool`，缺失/错形抛 `PayloadDecodeException`——**绝不零值兜底**。`title/message_id/snippet/snippet_offset` 是 required-**with-null**（键必须在，值可 null），absent ≠ null。
- **snippet / snippet_offset 原样保留**：解码只做字符串透传（测试 `searchSnippetTravelsVerbatimNeverReclipped` 用 60 码点 astral 片段钉死），客户端不重截断、不重拼、不重算偏移。
- `total` 以服务端为准：`searchTotalIsTheServerCountEvenWhenItDisagreesWithHits`（1 行命中、total=7 → decode 为 7，不本地重数）。

### 参数校验清单（发帧前，全部 INVALID_ARGUMENT）

1. 空 query `""`（测试 3）
2. 空白 query `"  \t\n "`（测试 4）——两条都同时断言**连接态下 transport.record 为空**（帧根本没发）
3. `limit=0`（越下界）
4. `limit=-1`
5. `limit=101`（越上界）
6. `offset=-1`
7. query trim 后 >200 码点（含 astral：150 个 `𝕏`＝150 码点放行、201 个＝201 码点拒绝——限长按**码点**不按 UTF-16 单位）

## 2. session/list typed wrapper — 三态写法与真帧断言

**事实修正**：`sessionList` typed wrapper 在基线里**已存在**（`VadeClient.kt`，commit 3109afa 引入；本轮 ff 到 2cf917b 后仍在）。上一轮派工单写的"没有 wrapper"指的是数据层没接它（见 §4），协议层缺口当时只剩 session/search。本轮做的是把它**钉进测试**，防止再被绕过：

- 三态用既有 `Optional<T>`（`VadeJsonWire.kt`，Absent/Null/Value）：
  - `Optional.Absent` → 线上**无 `group_id` 键**（全部会话）
  - `Optional.Null` → 线上 `"group_id":null`（仅未分组）
  - `Optional.of("g1")` → `"group_id":"g1"`（指定组；未知组由 core 报 NOT_FOUND/unknown_group）
- 真调用帧断言（`sessionListTypedWrapperSendsARealFrameAndDecodesTypedRows`）：FakeTransport 上 `awaitSent` 取到帧，断言 `method == "session/list"`、params 逐字节 `{"limit":50,"cursor":0}`，回包后 typed `SessionListResult`/`SessionRow`（must* 解码）——证明走的是真 client 方法。
- 反向证明（`sessionListUndecodableResultIsProtocolMismatchNotRawPassthrough`）：缺 `sessions` 数组的回包 → `VadeException(PROTOCOL_MISMATCH)`。老的 tee 路径会把 JsonObject 原样吐回，typed 路径必须拒绝——这条测试就是"没在用 tee"的证据。

## 3. 契约覆盖守卫（VadeContractCoverageGuardTest，3 条）——以后不会再漏方法

**方法集怎么"推出来"（不手写）**：引入 `internal enum class VadeMethod(val wire: String)`（34 条目），并把 `VadeClient.call()` 的签名从 `method: String` 改为 `method: VadeMethod`——原来 33 处 `call("session/list", …)` 字符串字面量**全部改道**（`grep 'call("' = 0 残留）。此后：

- **枚举就是唯一分发表**：wrapper 不经过枚举根本无法发帧（变异删条目 → `compileKotlinDesktop` 直接失败，不用等测试跑）；
- 守卫第 1 条在**运行时真读** `contracts/vade/schema/rpc.json`（从测试工作目录向上找 `contracts/vade/schema/`，找不到就 error 并提示 `bash tools/setup-contracts.sh`），取 `directions.ui_to_core` 全部方法名，与 `VadeMethod.entries.map { it.wire }` 做**双向集合差**：缺 → 报 `client has NO codec for contract methods [...]`，多 → 报 `client dispatches [...]`，并断言集合大小相等；
- 只留两个 1.0.0 冻结成员（`ui/attach`、`approval/decide`）作**读取锚点**（JSON 结构挪位时先红在锚点，不会空集假绿）；方法名本身零手写；
- 守卫第 2 条是负向控制：模拟"少一个方法的客户端"（`VadeMethod.entries - "session/search"`，即 1.4.0 时代的状态），断言 diff 恰好点名 `session/search`——证明对比机制非空转。

**events 同法**：真读 `events.json` 的 `events` 全部键；`turn.state` 这种枚试行按**文件自身结构**排除（键值对象里带 `enum` 键的行不是 topic，它的 notes 原文就写 "Not an event"）——不是硬编码名字，未来再加枚举行自动跳过；与 `VADE_EVENT_TYPES` 双向集合差。配套的既有测试（`allDeclaredEventTypesParseIntoDistinctSubtypes`）保证 VADE_EVENT_TYPES 里每个 topic 真有 parser 分支，两个方向都被钉死。

## 4. 下一轮：数据层应该改哪里（本轮一行未动）

文件 `composeApp/src/commonMain/kotlin/vade/ui/data/RealSessionSource.kt`：

1. **第 181–205 行 `override suspend fun sessions()`**：把 186–193 行的 `rawRpc(METHOD_SESSION_LIST, buildJsonObject { … })` 换成真调用：

   ```kotlin
   val result = client.sessionList(
       SessionListParams(limit = SESSION_LIST_PAGE_LIMIT, cursor = cursor.toInt()),
   )
   val rows = result.sessions            // 已是 typed SessionRow
   val next = result.nextCursor          // Int?
   ```

   随后 194 行 `result.arr("sessions").orEmpty().mapNotNull { it as? JsonObject }` 换成直接用 `result.sessions`（`SessionRowMapping.kt` 的 `sessionRowToSummary` 若吃 JsonObject，加一个吃 `SessionRow` 的重载或改签名——数据层自己定）。195 行 `result.lng("next_cursor")` 同理。
2. **第 362 行 `METHOD_SESSION_LIST` 常量**、**第 316–359 行 `rawRpc()`**、**`TransportTap.kt` 整个文件（TeeTransport/TeeTransportFactory + listener 机制）**：`sessions()` 换掉后就没有调用方了，按"实现了但没人调用 ≈ 没写"的原则整块删除（注意 TransportTap.kt 里 `internal`，删除前 grep 全仓引用）。
3. 既有 `RealSessionListTest` 若依赖 tee 注入，需同步改用 FakeTransportFactory + `responseFrame` 注入。

## 5. 基线变化与本轮踩的坑

- 派工单基线 aeae70a/315 已过期：中途按用户指示 ff 到 **2cf917b**（main 七个新 commit：c-model/c-edit 合入 + Wasm 修复），合并后基线 **361**，本轮 +25 = 386。
- **commonMain JVM 专有 API 坑复现一次**：码点计数首版用了 `String.codePointCount`——desktopTest 全绿，`compileKotlinWasmJs` 红（`Unresolved reference 'codePointCount'`）。已改为 common 安全的 `vadeCodePointCount()`（surrogate 对判定，stdlib `isHighSurrogate/isLowSurrogate` 为 common API），并补 astral 码点断言。教训与用户警告一致：**desktopTest 绿 ≠ 三端绿，wasm 门禁必须单独跑**。
- `kotlinx.serialization` 的 `JsonPrimitive.longOrNull` 会**宽松解析数字字符串**（`"7"` → 7）：required-with-null 数字字段想测"错形拒绝"，夹具得用真非数字（`"seven"`）。已写进测试注释。
- `assertFailsWith` 的 lambda 版 `assertTrue(cond) { msg }` 在本仓 kotlin.test 版本属 `ExperimentalKotlinTestApi`，直接用 String 消息参数，避免 opt-in。
