# g-wire-strict · protocol 层「静默语义漂移」全量消除 — RESULT

worktree: `D:/HermesWorkspace/Vade-client-wt/g-wire-strict`，分支 `tmp/g-wire-strict`，基于 main @ **50d2d58**。
代码 commit：**8750f6c**（本报告随后单独提交，未 push、未 merge）。

起因：`g-module-decl` 发现 `module.status.version` 用 `str()`，于是 `{"version":3}` 被**静默**当成"无版本"而不是 malformed。这是第二类静默失败（第一类是 `putStr` 把 `null` 当省略）。本切片不是修那一个 bug，而是把这一**类** bug 一次扫干净。

本次只改 `protocol/**` + 新建测试 + `shots/g-wire-strict/**`。未触碰 shell/destinations/chat/meta/model/usage/search/module/skin/icon/data/build.gradle.kts/contracts。

---

## 一、审计宏表

**总解码点：493 处**（protocol 三个解码文件里的取值调用点，不含 19 个助手定义行）。

| 取值族 | 解码点 | 处置 | 数量 |
|---|---|---|---|
| `.str(` / `.strOrNull(` | 162 | → `strStrict`（162 处全部） | **162** |
| `.lng(` | 54 | → `lngStrict` | **54** |
| `.int(` | 40 | → `intStrict` | **40** |
| `.bool(` | 31 | → `boolStrict` | **31** |
| `.dbl(` | 3 | → `numStrict` | **3** |
| `.obj(` / `.arr(` / `.el(` | 49 | **只记录不改** | 0 |
| `must*`（mustStr/mustLng/mustInt/mustBool/mustStrOrNull/mustLngOrNull/mustArr） | 33 | **只记录不改** | 0 |
| `req*`（VadeEvents 内的 required 取值族） | 118 | **只记录不改** | 0 |
| `stringList(` | 2 | → 元素与容器双重 shape 校验 | **2** |
| **合计** | **493** | | **292 改 / 201 只记录** |

另有 1 处 `strStrict`（`ModuleStatusPayload.version`）是 g-module-decl 已修好的源头，本次沿用其体例，未再改动。

按契约类型（与上表同构，因为每个助手只对接一种固定类型）：

| 契约声明 | 解码点 | 读成什么 |
|---|---|---|
| `string` / `string|null` / 枚举串（`open|closed`、`turn.state`、`session|global|priority|none`、`C-04 name`、`sha256:hex`、`msg:<hex>|null`…） | 162 | `String?` |
| `int` / `int|null`（含 `int (unix ms)`、`int >= 0`、`int 0..100`） | 94 | 54 处 `Long?`（时间戳/计数）+ 40 处 `Int?` |
| `bool` / `bool|null` | 31 | `Boolean?` |
| `number` / `number|null` | 3 | `Double?` |

按来源：**RPC result 170 处 / event payload 120 处**。按空值性：**契约写 `|null` 的 82 处 / 固定非空的 208 处**（两类都进 `*Strict`——这正是关键：严格不等于变必填）。

按文件：`VadeModels.kt` 162、`VadeEvents.kt` 111、`VadeContent.kt` 17。

### 只记录不改的 201 处，逐类理由

| 类 | 数量 | 理由 |
|---|---|---|
| `.obj(` / `.arr(` / `.el(` 容器取值 | 49 | 契约在这些位置写的就是 `object` / `array` / `any`（`ui/attach.snapshot`、`session/model.get.effective`、`turn.failed.usage`、`ui/tool.call.result`、`state.get.state`、`turn.input_required.default`、`turn.input_resolved.value`（`string|string[]|bool` 联合）、`page.get.schema`）。没有标量类型可校验；错型仍然读成"缺失"，是**同一类漂移**但修法不同（需要逐节点 schema 校验，属于另一次改动）。把联合类型强行收紧会造成假红。 |
| `must*`（33 处） | 33 | 已经在错型时**抛**（`mustStr` = `str(key) ?: throw`），不是静默。唯一瑕疵是报错文案说"misses required …"而不是"present but wrong shape"——语义上仍是 malformed，只是话说得不够准。 |
| `req*`（118 处，VadeEvents 内的 required 取值族） | 118 | 同上：`reqStr`/`reqLng`/`reqInt`/`reqBool`/`reqObj` 都是 `loose ?: throw`，错型即抛，不是静默；`reqStrOrNull` 本来就是 shape-checked。未改，避免把"必填校验"和"类型校验"两件事混在一次改动里。 |
| `strStrict`（已有 1 处） | 1 | g-module-decl 的源头修复，体例照抄。 |

一句话：**没有任何一处标量取值因为"契约含糊"而被放过**——放过它们的全部理由是"它本来就是容器类型"或"它本来就会抛"。

---

## 二、三列核对：契约声明的类型 / 现在错型的行为 / core 实际发什么

### ① 契约侧

`contracts/vade/schema/rpc.json` 与 `events.json` 的类型短语是机器可读的（`string`、`string|null`、`int (unix ms)`、`session|global|priority|none`、`bool`、`number|null`、`object|null`、`[model]|null`、`TaskView[] (RFC-0007 §5.1 …)`、`array of {ts:int, …}` …）。
用 `shots/g-wire-strict/classify2.py` 把 **373 个带字面量 key 的调用点**逐条 join 到 80 个契约对象节点上（RPC result 树 + 34 个事件 payload 树）。

契约只写别名的 16 个节点（**只能靠 RFC 才拿到字段类型**，join 时显式补进来并在脚本里留了引用）：

| 别名 | 解析来源 |
|---|---|
| `[model]`（`model/list.models`） | RFC-0005 §4.2 条目字段表 |
| `TaskView[] (RFC-0007 §5.1 fields + log_available)` | RFC-0007 §5.1 Task 记录表 + §6.1 `log_available` |
| `ui/attach.snapshot` 及其 `session` / `session.model` / `queue` | rpc.json `ui/attach` notes + RFC-0005 §5（`session.model: {model_id, source}`）+ 真帧 |
| `turn.completed.usage`（`object`） | RFC-0005 §6.1 兼容扩表（7 个追加键的名字与类型） |
| `session/model.get.effective`（`object|null`） | RFC-0005 §5 示例 + core `ModelEntry.effective()` |
| `session_row` / `group_row` | rpc.json 里"same shape as session/list|group.list items" |
| `ui/submit.params.content.items`（oneOf） | rpc.json 内联 JSON-Schema：`type`/`text` string、`size`/`width`/`height` integer、`sha256` string |
| `task/log_tail.lines` | rpc.json 内联 `array of {ts:int, stream:stdout|stderr|core, text:string}` |
| `turn.input_required.options[]` / `.source` | rpc.json 内联 `{id,label,description|null}` / `{tool,module_id,depth}` |

### ② 现在（改前）错型的行为

| 助手 | 正确类型 | 缺键 | 显式 null | 错型（改前） |
|---|---|---|---|---|
| `str` | string | null | null | **静默 null** |
| `strOrNull` | string\|null | null | null | **静默 null**（名字骗人：它只表达 null 合法，并不校验 shape） |
| `lng` | int | null | null | **静默 null**（`"3"` → null，`3.5` → null） |
| `int` | int | null | null | **静默 null**（另加：超出 Int 范围也静默 null） |
| `bool` | bool | null | null | **静默 null**（`"true"` → null） |
| `dbl` | number | null | null | **静默 null**（`"0.42"` → null） |
| `stringList` | string[] | null | null | **静默变味**：`[1]` → `["1"]`；非 primitive 元素被**丢弃** |

### ③ core 实际发什么（两路取证）

**A. 真帧语料（最硬的证据）** —— `shots/g-wire-strict/capture2.py` 用 core 自己的测试方式在进程内拉起真 python core（uvicorn 临时端口 + 真 `POST /ui/session` 配对码 cookie + 真 WS `/ws`），注入脚本化 `MockLLMProvider`，录下 **22 条 RPC result + 14 条 event payload** 的原始 JSON。所有事件 topic：`session.attached, turn.queued, turn.started, turn.delta, turn.completed, queue.updated, session.updated`。

真帧直接钉死了几个只有真链路才知道的事实：
- `session/list.sessions[].title` 同一个接口两次 reply，一次是 `"cap title"`、一次是 `null` → 确实 `string|null`；
- `session/usage.get.budget` = `{"scope":"session","limit":0,"used":21,"ratio":null,"state":"off","enforced":false}` → `ratio` 真的会发 null；`day` 的 `day` 是 `"2026-11-14"` 字符串；
- `turn.completed.usage` = `{prompt_tokens:9, completion_tokens:12, total_tokens:21, model_id:null, provider:null, source:"provider", steps:1, cached_tokens:0, reasoning_tokens:0}` → 三个冻结键是 int，`model_id`/`provider` 真是 null（不是省略）；
- **`session.updated` 带 `created_at` 与 `state` 两个 events.json 从未声明的键** → 未知键必须忽略这条边界必须钉住；
- `snapshot.session.model` = `{model_id:null, source:"none"}`，`snapshot.session.title` = null。

**B. 静态核对 core 发射处** —— `shots/g-wire-strict/corecheck.py` 对 290 处严格化涉及的 **152 个不同 key**，在 `D:/HermesWorkspace/Vade/Vade/core/vade/**` 里逐个 grep `"key":` 的右值表达式，并按"字符串字段给了数字/布尔字面量""数值字段给了字符串/布尔""int 字段给了 float 表达式"三类规则筛可疑点。

结果：**3 个可疑点全部落在审计行，不在线上帧里**——
- `core/vade/modulepage/service.py` 与 `core/vade/policy/approval.py` 的 `duration_ms` 是 `round(float(...), 3)`，但那是 `llm.call` / `module_page_call` **审计事件**行，不是任何 `ui_to_core` result 或 `C-06` payload；
- `core/vade/loop/engine.py` 命中是我正则的假阳性（`int((time.perf_counter() - started) * 1000)`，外面已经 `int()`）。

而真正**是** float 的那个 `duration_ms` 是 `core/vade/input/manager.py` 的 `duration_ms: float`（`0.0` 也真的发过），它对应的契约是 `turn.input_resolved.duration_ms = "number"`、客户端用 `.dbl()` —— 所以那 3 处走 `numStrict`，`3.0` 在那里是合法的。**这条正好证明"按契约类型选手助"不是形式主义**：同一个字段名，一处必须是 int，一处必须是 number。

其余 4 个静态 grep 没命中的 key（`tasks_truncated`、`model_source`、`step`、`tool_name`）单独确认：`bool(truncated)`、`str | None`、`step[:STEP_MAX]`（str 切片）、`tool_name` 有 `isinstance(str)` 校验。

---

## 三、新增 / 沿用的严格助手

`protocol/VadeJsonWire.kt` 新增 4 个，沿用 1 个（`strStrict`，体例与 KDoc 一致：先看缺键、再看 `JsonNull`、最后把错型抛进 `PayloadDecodeException`）：

| 助手 | 签名 | 接受 | 拒绝 |
|---|---|---|---|
| `strStrict`（沿用） | `(String) -> String?` | 缺键、`null`、string | 数字/布尔/对象/数组 |
| `lngStrict`（新增） | `(String) -> Long?` | 缺键、`null`、整数字面量 | `"3"`、`3.5`、`3.0`、`true`、对象、数组 |
| `intStrict`（新增） | `(String) -> Int?` | 同上且落在 `Int` 范围 | 同上 + 超范围（**抛出，不是静默 null**） |
| `boolStrict`（新增） | `(String) -> Boolean?` | 缺键、`null`、`true`/`false` | `"true"`、`1`、`0`、对象、数组 |
| `numStrict`（新增） | `(String) -> Double?` | 缺键、`null`、任意 JSON 数字（`3` 或 `3.0`） | `"0.42"`、布尔、对象、数组 |

`intStrict` 复用 `lngStrict` 做 shape，再补范围判断——所以 `3.0` 在两者都拒，行为一致。

**边界铁律写进了 KDoc 并被测试钉住**（`VadeJsonWire.kt` 的 `shape-strict scalars` 段）：

```
缺键            -> null     （可选字段缺失仍合法）
键在 + null     -> null     （契约写 X|null 的地方 null 合法）
键在 + 对型     -> 值
键在 + 错型     -> PayloadDecodeException -> 调用方的 onMalformed
未知键          -> 忽略     （"严格"永远不是"封闭"）
```

另外 `VadeEvents.kt` 的 `stringList(key, o)` 从 `o.arr(key)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }` 改成：缺键/null → null，非数组 → 抛，数组里有非字符串元素 → 抛。两处调用（`turn.tool_call.args_preview_redacted`、`turn.skill_catalog.entries`）在 events.json 里都是固定 `string[]`。

**`3.0` 的决定**：契约写 `int` 的字段拒绝 `3.0`。理由：core 是 Python，`int` 字段由 `json.dumps` 写成裸整数字面量；出现 `3.0` 意味着生产者换成了 float——正是我们想听见的漂移。契约写 `number` 的地方用 `numStrict`，那里 `3.0` 合法。这条在 KDoc 里写明，并有正反两条测试。

---

## 四、测试：22 条，全绿

`composeApp/src/desktopTest/kotlin/vade/ui/protocol/VadeWireStrictTest.kt`（+ `RealCoreFrameCorpus.kt` 真帧语料）。

| # | 测试 | 钉的是什么 |
|---|---|---|
| 1 | `strStrictAcceptsStringAndNull` | 正型 + null + 缺键 |
| 2 | `strStrictRejectsNumber` | **源头 bug 原样**：`{"version":3}` → 抛，且报错点名 `version` 与 `string|null` |
| 3 | `strStrictRejectsEveryNonStringShape` | `true/false/1.5/{}/[]` 全拒 |
| 4 | `lngStrictAcceptsIntegersAndNull` | 正型（含负数）+ null + 缺键 |
| 5 | `lngStrictRejectsQuotedNumbersAndFractions` | `"1794670914245"`、`1.5`、`true`、`{}` |
| 6 | `intStrictAcceptsInRangeAndNull` | 0 / 100 / null / 缺键 |
| 7 | `intStrictRejectsFractionalQuotedAndOutOfIntRange` | `3.0`、`"3"`、`4294967296`（超范围必须抛，不能静默 null） |
| 8 | `boolStrictAcceptsBooleansAndNull` | true / false / null / 缺键 |
| 9 | `boolStrictRejectsTheStringTrueAndNumbers` | `"true"`、`1`、`0` |
| 10 | `numStrictAcceptsAnyJsonNumberAndRejectsStrings` | `0.42`、`3`、null、`"0.42"`、`true` |
| 11 | `absentOptionalKeyIsNeverMalformed` | **对照**：可选字段缺失不被严格化搞成必填（5 个助手全覆盖） |
| 12 | `explicitJsonNullIsNeverMalformed` | **对照**：显式 null 仍合法（5 个助手全覆盖） |
| 13 | `moduleStatusVersionAsNumberIsMalformedThroughTheParser` | 走 `VadeEventParser`，事件被丢 + `onMalformed` 带上 `module.status` |
| 14 | `moduleStatusVersionAsStringStillDecodes` | 同帧契约形状仍然解（严格不是封杀） |
| 15 | `unknownKeysAreIgnoredAtEveryLevel` | 顶层 + 嵌套未知键都忽略（含真核会发的 `created_at`/`state`） |
| 16 | `stringListRejectsANonStringElement` | `entries:["alpha",1]` → malformed（不再静默变 `"1"`） |
| 17 | `stringListAcceptsStringsAndTreatsAbsentAsNull` | 正型 + 缺键（`.orEmpty()` 路径） |
| 18 | ★ `realCoreFramesStillDecode` | **22 条真 RPC result + 14 条真 event 全部仍能解**，且断言语料规模不得缩水 |
| 19 | `realCoreSessionUpdatedIgnoresUncontractedKeys` | 真 `session.updated` 帧确实带契约外键，且仍能解 |
| 20 | `realCoreUsageCountersSurviveStrictDecoding` | 真 `turn.completed.usage` → 9/12/21、`source="provider"`、`steps=1`、`model_id=null` |
| 21 | `realCoreSessionListRowsTolerateNullTitle` | 真 `session/list` 里 `title:null` 与 `title:"…"` 并存都能解 |
| 22 | `noLooseScalarAccessorRemainsOutsideTheHelperFile` | 结构性守卫：`protocol/` 除 `VadeJsonWire.kt` 外不许再出现 `.str(`/`.lng(`/`.int(`/`.bool(`/`.dbl(` |

`realCoreFramesStillDecode` 是这 22 条里最重要的一条：它守的是**反向**风险——过度严格把真链路搞挂。它的语料不是手写契约字面量，是从真 python core 录下来的原帧。

### 变异验证（证明测试不是摆设）

把 5 个 `*Strict` 全部改成委托给对应的宽松助手（`strStrict = str(key)` …）后重跑该类：

```
22 tests completed, 7 failed
  intStrictRejectsFractionalQuotedAndOutOfIntRange      FAILED
  moduleStatusVersionAsNumberIsMalformedThroughTheParser FAILED
  boolStrictRejectsTheStringTrueAndNumbers              FAILED
  strStrictRejectsNumber                                FAILED
  numStrictAcceptsAnyJsonNumberAndRejectsStrings        FAILED
  lngStrictRejectsQuotedNumbersAndFractions             FAILED
  strStrictRejectsEveryNonStringShape                   FAILED
```

注意 `realCoreFramesStillDecode` 在**宽松**变异下仍是绿的——这正确：它守的是"别太严"，宽松变异本来就不该让它红。两个方向各有人守。变异后已还原，`git diff` 与 8750f6c 一致（`VadeJsonWire.kt` 只有那 +53 行新增）。

---

## 五、三目标门禁（原始数字）

命令：`source D:/Hermes/tools/env/vade-env.sh` + `PATH` 覆盖为 `gradle-9.6.1`（README 第 148 行要求，`vade-env.sh` 把 8.10.2 排最前）。

| 目标 | 结果 |
|---|---|
| `gradle :composeApp:desktopTest --rerun` | **BUILD SUCCESSFUL**，`tests=779 failures=0 errors=0 skipped=1`（71 个测试类） |
| `gradle :composeApp:assembleDebug` | **BUILD SUCCESSFUL**，产出 `composeApp/build/outputs/apk/debug/composeApp-debug.apk`（22,157,475 B） |
| `gradle :composeApp:compileKotlinWasmJs` | **BUILD SUCCESSFUL** |

- **779 = 757 + 22**。757 是本基线上的既有条数：任务书给的基线 **737** 加上 `g-module-decl` 合并进 main 的 **+20 条**（见 50d2d58 提交说明），完全对上。
- `skipped=1` 是既有的 `VadeClientSmokeTest`（`Assume.assumeTrue("VADE_SMOKE_PORT set", …)` 真核冒烟，无 env 时跳过），基线内已有，与本切片无关，**未动**。
- 已知抖动 `UiStateSourceWiringTest` 与 `RealCoreE2ETest` 本次**都没红**：`RealCoreE2ETest` 6 条全过（含主链路 attach→submit→事件序→usage），`RealCoreUiE2ETest` 1 条过。**没有改任何断言、没有 skip、没有调超时。**
- 关键：`RealCoreE2ETest` 与 `RealCoreUiE2ETest` 都是用真 python core 跑的，它们绿就是"解码严格化没有把真链路搞挂"的现场证据；`realCoreFramesStillDecode` 是同一结论的离线版（不依赖 core 进程，因此不会因 30s READY 抖动而失去信号）。

---

## 六、诚实记录：本次**没有**修的东西

1. **容器型取值仍会把错型读成"缺失"**（49 处 `obj`/`arr`/`el`）：`"snapshot": "oops"` 依旧解成"没有快照"。这是同一类漂移，但修法是逐节点 schema 校验，与"标量类型"不是一回事，放另一次改动更安全。
2. **`must*` / `req*` 的报错文案**（151 处）：错型时报的是"misses required string 'x'"而不是"present but not a string"。行为正确（抛），只是话说得不准；改文案要动 151 处错误路径，收益是日志可读性，风险是测试里对 message 的断言——留给专门一次。
3. **`?: 0` / `?: false` 这类"缺键补零"**（如 `VadeUsage.promptTokens = o.lngStrict(...) ?: 0`）：这是**必填性**问题（"all-zero row"那类），不是类型问题。本次严格化之后，错型会先抛，只有"真的缺键"才走到补零；是否该改成 `mustLng` 需要单独的必填性审计（尤其 `turn.interrupted.usage` 是否可能只发部分键）。
4. **`stringList` 的 `entries` 缺键 → 空列表**：`turn.skill_catalog.entries` 在 events.json 的 `required` 里，缺键严格说应该 malformed；但 `.orEmpty()` 是既有行为，改成必填会让一条缺键的目录事件整条被丢。留着，记录在此。

---

## 七、复现方式

```bash
cd D:/HermesWorkspace/Vade-client-wt/g-wire-strict
# 重新拉真 core 帧语料（需要 D:/HermesWorkspace/Vade/Vade/.venv）
D:/HermesWorkspace/Vade/Vade/.venv/Scripts/python.exe shots/g-wire-strict/capture2.py shots/g-wire-strict/capture.json
python shots/g-wire-strict/captypes.py        # 真帧里每个路径实际出现的 JSON 类型
python shots/g-wire-strict/classify2.py       # 契约 × 调用点 join，输出每处裁定
python shots/g-wire-strict/verify.py          # 断言"磁盘上的代码 == 审计裁定"，0 mismatch
python shots/g-wire-strict/corecheck.py       # 静态核对 core 发射处
python shots/g-wire-strict/stats.py           # 宏表数字
```

`verify.py` 的输出是这条审计最强的自证：**mismatches between the audit verdict and the file on disk: 0**——290 处该改的全部改了，201 处该留的一处没动。
