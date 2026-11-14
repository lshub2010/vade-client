# g-module-decl RESULT — A3 模块入口：侦察结论 + 客户端侧注册表（声明通道仍未通，需 RFC）

分支 `tmp/g-module-decl`，基于 **main `09c7320`**。未 push、未合并。
改动范围：**协议层 2 个既有文件（+21/−1 行）+ 新建 `data/ModuleRegistry.kt` + 新建 `desktopTest/.../ModuleRegistryTest.kt` + 本报告**。
`RealSessionSource.kt` / `VadeAdapters.kt` / `shell/**` / `destinations/**` / `chat/**` / `meta/**` / `model/**` / `usage/**` / `search/**` / `module/**` / `skin/**` / `icon/**` / `build.gradle.kts` / `contracts/**`：**零改动**（见文末红线自查）。

> 结论先行：**A3 的阻断不是「解码器没写」，是冻结契约里根本没有这条通道。** 「解码三个模块事件」这一步本身不可能——`module.register` / `module.unregister` 在 events.json（v2.0.0 frozen）里**不是 topic**；`module/register` 是 module→core 的 **RPC**。`ui/attach` 快照也**不带**模块清单。所以本批按任务书的「不臆造」分支落地：把**真实存在**的那条数据（`module.status`）收进注册表 + 把「声明」做成**一行接线**的注入口，并给出通道缺失的完整证据与 RFC 草案。**能力页今天仍然不会出现模块入口**，差什么在 §6 写清。

---

## 1. 侦察四条（逐条 file:line）

### ① 事件解析器现在解了哪些事件？

| 项 | 事实 | 位置 |
|---|---|---|
| 已解码 topic 清单 | 33 个（`VADE_EVENT_TYPES`），其中模块类**只有** `module.status` | `composeApp/src/commonMain/kotlin/vade/ui/protocol/VadeEvents.kt:1183`（清单）、`:1239`（`module.status` 解码分支） |
| `module.register` | **不存在**：不在 `VADE_EVENT_TYPES`，也不在 `VadeEventParser.parse` 的 `when` 里（未知 topic → `else -> null`，`:1258`） | 同上 |
| `module.unregister` | **不存在**，原因同上 | 同上 |
| 契约侧 | events.json v2.0.0 `status: frozen`，`events` 表 33 个 topic + `turn.state` 枚举行；模块类只有 `module.status`（4 字段：`module_id`/`instance_id`/`online`/`version`） | `contracts/vade/schema/events.json:327-341` |
| core 侧 | 全仓 `emit("<topic>")` 扫描：模块类只发 `module.status` | `Vade/core/vade/session/status.py:61` |
| `module/register` 的真身 | **module→core RPC**（`module_to_core` 段），core 处理在 `app.py:229`；SDK 在握手后 `client.request("module/register", app.manifest(), timeout=15.0)` 主动发 | `contracts/vade/schema/rpc.json:220`；`Vade/core/vade/app.py:229`；`Vade/sdk/python/vade_sdk/transport.py:600` |

→ **纠错**：上一条子代理的「`module.register` 事件存在、客户端没解码」不成立。它把 **RPC 方法名**当成了事件 topic。客户端永远不会在事件流上看到 `module.register`。

### ② `ui/attach` 快照里到底带不带模块清单？——**不带**（三处独立证据）

| 证据 | 内容 | 位置 |
|---|---|---|
| 契约 | result 仅 `{session_id, from_seq, snapshot: object\|null}`；notes 逐条列了 `snapshot.session` / `messages[].parts` / `pending_inputs[]` / `tasks` / `tasks_truncated` / `pending_approvals[].task_id` / `messages[].partial` — **没有 modules** | `contracts/vade/schema/rpc.json:420-423` |
| 客户端模型 | `VadeSnapshot` 字段：`fromSeq / session / messages / turns / queue / pendingApprovals / pendingInputs / tasks / tasksTruncated` | `composeApp/src/commonMain/kotlin/vade/ui/protocol/VadeModels.kt:92-123` |
| core 拼装 | `snapshot()` 的字典字面量：`from_seq / session / messages / turns / queue / pending_approvals / pending_inputs`，`self.tasks` 存在时再加 `tasks` / `tasks_truncated` | `Vade/core/vade/session/manager.py:749-799`（键在 `:769-786`） |

补充：`ui_to_core` 共 **34** 个方法（rpc.json），**没有任何一个返回模块清单**；`ui/page.get` 要 `module_id + instance_id + page_id` 三个参数（`rpc.json:882-890`），是「已知坐标取页」，不是枚举。core 侧 `modulepage/rpc.py:29-34` 只挂了 `ui/page.get` / `ui/state.get` / `ui/tool.call` 三个 handler。

### ③ `module.register` 谁发、payload 什么、之后 core 发什么？

- **谁发**：模块自己（SDK，握手完成、密文通道内），见 `Vade/sdk/python/vade_sdk/transport.py:600`。
- **payload**：**C-03 manifest 全文**（`contracts/vade/schema/manifest.schema.json`）。入口需要两处：
  - `display_name`：`manifest.schema.json:99`（1–64 字符）—— 即 UI 的「模块显示名」；
  - `ui.pages[]`：`manifest.schema.json:227`（C-19 append，≤8 页），每页 `id / title / icon / entry / version / requires / refresh_s / schema`。
- **之后 core 发什么**：`Registry.register()`（`Vade/core/vade/registry/table.py:150`）→ `_publish_status()`（`:151-160`）→ `_record_status()`（`:143-149`）**只投影 4 个字段**（`module_id / instance_id / online / version`）→ `ModuleStatusPublisher.publish()` 对每个有 live sink 的会话 `emit("module.status")`（`session/status.py:56-62`）。
- **关键缺口**：manifest 的标题与页面在 core 内部是齐的（`ModulePageService.pages()`，`Vade/core/vade/modulepage/service.py:202-221`，注释直接对着 FR-8.79），但**没有任何 wire 出口**：
  - `modulepage/rpc.py` 没有 list 方法（只有 3 个 handler）；
  - admin 面 `RegistryAPI._node_view()` 只给 `ui_page_count` / `ui_page_warnings`（`registry/api.py:44-45`），且 `admin-api.yaml:441-446` 明写「不透传 manifest」；
  - admin 面本身在数据面是 404（`admin-api.yaml` 顶部注释），UI 够不到。
  - `ModulePageService.pages()` 在 core 全仓**零调用者**（`grep "\.pages("` 只命中定义与 `ui_pages` 字段）——即 C-19 实现了这份数据却没接出口。

### ④ `declarations()` 要什么类型？

- `ModulePageSource.declarations(): List<ModuleDeclaration>` — `composeApp/src/commonMain/kotlin/vade/ui/module/ModulePageSource.kt:20`；
- `ModuleDeclaration(moduleId, instanceId, moduleTitle, online, moduleVersion, pages)` — `:34-41`；
- `PageDeclaration(pageId, title, icon: PageIcon, version: Int, requiresSession: Boolean)` — `:43-49`。

→ 需要 **标题** 与 **页面清单**；两者都只存在于 manifest，`module.status` 一个都没有。所以「靠 `module.status` 把 declarations 填出来」在数据上不成立。

### ⑤ 顺带查实：`module.status` 也不可拉取、不可补

`VadeClient.events` 是 `MutableSharedFlow(extraBufferCapacity = 256)` **无 replay**（`protocol/VadeClient.kt:190-191`）；`module.status` 只在**变化**时发、只发给**当时有 sink** 的会话，core 注释明说「是为触发 admin 重新拉取，不是给迟到客户端回放的状态」（`session/status.py:9-11`）。所以即使只做「在线态」，晚订阅也补不回来。

---

## 2. 选路：**快照不带 → 不臆造**

任务书给了两条路；侦察结果是第二条，且比预想的更硬：

- **不做**：新增 `module.register` / `module.unregister` 解码器。events.json 没有这两个 topic，core 也不发。为一个从不出现的报文写解码器 + 一堆测试，是在测试自己的想象，且会让报告谎称「已解码三事件」。
- **做**（真实存在、可验证的部分）：
  1. `data/ModuleRegistry.kt`：以 `(module_id, instance_id)` 为键维护身份/在线态/版本（来源 `module.status`），并带一个**声明注入口** `declare(manifest)`（解码 C-03 冻结 schema 的 `display_name` + `ui.pages[]` → `PageDeclaration`），`declarations()` 直接返回 `module/ModulePageSource.kt` 要的 `ModuleDeclaration` 类型，接线是一行。
  2. 协议层把 `module.status.version` 从「静默降级」改成**严格解码**（`string|null`，数字 → 该事件判 malformed 并进 `onMalformed`）。这是本批唯一的既有代码改动，动机见 §3.2。

---

## 3. 改了什么

### 3.1 新建 `composeApp/src/commonMain/kotlin/vade/ui/data/ModuleRegistry.kt`（285 行）

| 成员 | 作用 |
|---|---|
| `onEvent(event): Boolean`（`:87`） | 只吃协议层 `ModuleStatus` 事件；其余 topic 返回 `false`（调用方可据此跳过重组）。 |
| `applyStatus(moduleId, instanceId, online, version): Boolean`（`:102`） | 字段级入口。空白 id 不建节点、返回 `false`（不替 core 编身份）。 |
| `declare(manifest: JsonObject)`（`:139`） | **声明注入口**：C-03 manifest 原文 → `display_name` / `version` / `ui.pages[]`。严格解码，坏形状抛 `PayloadDecodeException`；未知键忽略；未知 `icon` → `PageIcon.None`（schema 默认值）。 |
| `declarations(): List<ModuleDeclaration>`（`:214`） | 类型与 `ModulePageSource.declarations()` 逐字段一致；**只含已注入 manifest 的实例**（没标题就没入口，`filter { it.isDeclared }`）。 |
| `status(moduleId): ModuleStatus`（`:232`） | 按 `module_id` 聚合：任一实例在线即在线；未知模块 → 离线（fail-closed，不返回 null 不崩）。 |
| `nodes()` / `node(...)`（`:243-250`） | 只读快照 / 单实例查询（未注册 → null）。 |
| `clear()`（`:253`） | 断开/换连接时清空。 |
| 并发 | `@Volatile`（`kotlin.concurrent.Volatile`）+ **单写者 copy-on-write**：写入整体替换不可变 `Snapshot` 引用，读侧无锁、看不到半更新表。 |

不变量：`module.status` 只能改 `online`/`version`，**永不**写标题或页面；`declare` 只能改声明，**永不**重置在线态（测试 15 钉住）。

### 3.2 `protocol/VadeJsonWire.kt`（+16）与 `protocol/VadeEvents.kt`（+5/−1）

- 新增 `JsonObject.strStrict(key)`（`VadeJsonWire.kt:69`）：absent → null、JSON null → null、字符串 → 值、**别的形状 → `PayloadDecodeException`**。
- `ModuleStatusPayload.fromJson` 的 `version` 改用 `strStrict`（`VadeEvents.kt:544`）。原来用 `str()`：`{"version":3}` 会被静默当成「没有版本」，而 events.json 明写 `string|null`。客户端对「已知 topic 的坏 payload」的既有纪律是「抛 → parser 丢弃 → `onMalformed` 观察」，这里补上同一纪律（**没有改任何 topic、没有新增事件类型**）。

### 3.3 新建 `composeApp/src/desktopTest/kotlin/vade/ui/data/ModuleRegistryTest.kt`（20 条）

见 §4。

---

## 4. 测试（20 条，≥12 达标）

全部走**真 `VadeEventParser`**（不直接 new payload），manifest 用 **C-03 原文形状**（含 `capabilities` 等无关块，验证未知键忽略），其中 `echoManifest` 逐字取自 `Vade/Vade-module-echo/manifest.json`（`com.example.echo` / `回声模块` / **无 `ui` 块**）。

| # | 测试 | 钉住什么 |
|---|---|---|
| 1 | `moduleStatusDecodesEveryFieldVerbatimAndEntersTheRegistry` | 四字段逐字（`com.example.echo` / `main` / `true` / `1.0.0`）+ `seq=7` / `coalesced=false` + 进表后无标题无页面 |
| 2 | `unknownFieldsOnModuleStatusAreIgnored` | 事件里夹带 `title` / `pages` / `x-future` 也不许进注册表（状态事件不能走私声明） |
| 3 | `moduleStatusWithoutOnlineIsMalformedAndLeavesTheRegistryUntouched` | 缺 required `online` → event=null + `onMalformed` 收到 `PayloadDecodeException`，注册表零变化 |
| 4 | `moduleStatusWithNumericVersionIsMalformedNotSilentlyUnversioned` | `"version":3` → malformed（不是「没版本」），错误信息点名 `version` |
| 5 | `twoModulesAccumulateInArrivalOrder` | 多模块累积 + 顺序 = 到达顺序 |
| 6 | `repeatedStatusForTheSameInstanceIsIdempotent` | 同 `(module, instance)` 收 3 次仍 1 节点 |
| 7 | `statusFlipUpdatesOnlineAndVersionInPlace` | 在线态/版本更新、**不重排** |
| 8 | `sameModuleTwoInstancesAreSeparateNodesAndStatusAggregates` | 身份是 `module_id#instance_id`；module 级状态「任一在线即在线」 |
| 9 | `statusOfAnUnregisteredModuleIsOfflineAndQueriesReturnNull` | 未注册查询返回离线/null 不崩；空白 id 不建节点 |
| 10 | `onEventIgnoresEveryNonModuleEvent` | `turn.started` → `false`，零节点 |
| 11 | `manifestDeclarationKeepsTitleAndPagesVerbatimInManifestOrder` | `显示名/版本/pages[]` 逐字（id/title/icon/version/requires）+ **manifest 顺序**；未收 status 前 `online=false` |
| 12 | `manifestWithoutUiDeclaresTheModuleButProducesNoPages` | echo 真 manifest：有标题、`pages=[]`（FR-8.79 无入口） |
| 13 | `statusOnlyModuleNeverAppearsInDeclarations` | **线上生产态**：只有 status 时 declarations 为空且 `ModuleEntryIndex.isEmpty`（能力页整块不渲染） |
| 14 | `registryDeclarationsFeedEntryRulesWithTitlesIconsAndTheVersionGate` | 类型对齐 + 全链路：注册表 → `ModulePageSource` 适配 → `ModulePageEntryRules`：2 个 entry（`queue`/`settings`）、标题/图标/`requiresSession`/`instanceId` 逐字，`version=2` 页进 `hidden` 而不是消失 |
| 15 | `redeclaringTheSameManifestKeepsOnlineStatusAndPosition` | 重复声明幂等，**不重置在线态**、不重排 |
| 16 | `unknownIconNameFallsBackToNoneAndUnknownPageKeysAreIgnored` | 未知 `icon` → `PageIcon.None`；`refresh_s`/`entry`/`x-*` 忽略 |
| 17 | `manifestMissingModuleIdThrowsInsteadOfInventingIdentity` | 缺 `module_id` → 异常，不编身份 |
| 18 | `pageWithoutIdFailsTheWholeManifestInsteadOfVanishing` | 坏页 → 整份拒绝，不「少一个入口」 |
| 19 | `wrongShapedUiOrPagesIsRefusedNotTreatedAsNoPages` | `ui:{pages:{}}` / `ui:[]` / 页内 `title:5` → 异常（不是「等于没有页面」） |
| 20 | `clearDropsEverythingSoAReconnectStartsFromTheWireNotFromMemory` | `clear()` 后零节点零声明、状态回离线 |

### 变异自验（自己跑的两条）

| 变异 | 手法 | 结果 |
|---|---|---|
| A | `version = o.strStrict("version")` → `o.str("version")`（`VadeEvents.kt:544`） | 只跑本类：**20 tests, 1 failed**，红的正是 #4（`expected null, but was ModuleStatus(...)`）。还原后 `git diff` 回到 +21/−1。 |
| B | `declarations()` 的 `.filter { it.isDeclared }` → `.filter { true }` | 只跑本类：**20 tests, 2 failed**，红的正是 #9 与 #13（「unknown module never becomes a declaration」/「nothing is declared」）。还原后 `grep` 确认回到 `filter { it.isDeclared }`。 |

---

## 5. 三目标门禁（本 worktree 实跑原始数字）

| 检查 | 命令 | 原始结果 |
|---|---|---|
| 桌面全量测试 | `gradle --continue :composeApp:desktopTest --rerun :composeApp:assembleDebug :composeApp:compileKotlinWasmJs` | 见下「门禁原始输出」 |
| Android 编译 | 同上 | 同上 |
| Wasm 编译（commonMain 无 JVM API） | 同上 | 同上 |

### 基线（改动前，pristine main @09c7320）

`gradle :composeApp:desktopTest --rerun` → **588 tests completed, 3 failed, 1 skipped**，`BUILD FAILED in 16m 13s`。
3 条红全部与本批无关，且**在同一台机器、同一 worktree 的最终全量里全绿**（说明是环境/时序 flake，不是被我改绿也不是被我弄红）：

1. `vade.ui.e2e.RealCoreE2ETest.realCoreAttachSubmitStreamsOrderedTurnEvents` — `AssertionError: real WS upgrade with cookie accepted`（需要真 core 进程/端口，环境相关）；
2. `vade.ui.e2e.RealCoreE2ETest.requestBudgetExpiresWithTimeoutCodeWhenCoreNeverReplies` — `expected TIMEOUT but was UNAVAILABLE`（同上，同样是真 core 路径）；
3. `vade.ui.shell.UiStateSourceWiringTest.staleMessageRequestNeverOverwritesNewSession` — `expected [a, b] but was [b]`，即 `shots/g-modulewire/RESULT.md:31-34` 记录过的同一族时序 flake。

### 门禁原始输出（三次全量 + 一次隔离，全部本 worktree 实跑）

| 轮次 | 代码状态 | 命令 | 原始结果 |
|---|---|---|---|
| 基线 | main `09c7320`（本批 20 条测试尚不存在，故 588） | `gradle :composeApp:desktopTest --rerun` | **588 tests completed, 3 failed, 1 skipped**；`BUILD FAILED in 16m 13s` |
| 全量 A | 本批最终代码 | `gradle :composeApp:desktopTest --rerun` | **608 tests, 0 failures, 0 errors, 1 skipped**（57 个 XML 求和）；`BUILD SUCCESSFUL in 3m 11s` |
| 全量 B | 同一份代码（仅删掉一个冗余 `else` 分支后重跑） | `gradle --continue :composeApp:desktopTest --rerun :composeApp:assembleDebug :composeApp:compileKotlinWasmJs` | **608 tests completed, 2 failed, 1 skipped**；`BUILD FAILED in 2m 41s`（只有 `:composeApp:desktopTest` 红，另两个目标绿） |
| 隔离复跑 | 同上 | `gradle :composeApp:desktopTest --tests "vade.ui.shell.UiStateSourceWiringTest" --tests "vade.ui.e2e.RealCoreE2ETest"` | `UiStateSourceWiringTest` **20/20 绿（1.9 s）**、`RealCoreE2ETest` **6/6 绿（68.3 s）**；`BUILD SUCCESSFUL in 1m 23s` |

- 全量 B 的两条红（**都不是本批代码**，且这两个类在基线全量里也红过）：
  1. `vade.ui.e2e.RealCoreE2ETest.realCoreAttachSubmitStreamsOrderedTurnEvents` — `CoreBootException: core did not print READY within 30000ms`（真 core 冷启动在并行全量下超时；隔离跑 68 s 全绿）；
  2. `vade.ui.shell.UiStateSourceWiringTest.messagesFailureHitsOnlyThatSessionAndRetryMessagesWorks` — `expected:<Retryable> but was:<null>`（时序；隔离 20/20 绿）。
- 本批 20 条测试在 A / B 两轮全量里**全绿**（B 轮 608−2 failed−1 skipped = 605 passed，其中含本批 20）。
- `ModuleRegistryTest` 单独跑：**20 tests, 0 failures, 0 errors**（XML：`tests="20" failures="0" errors="0"`）。
- Android 目标：`:composeApp:assembleDebug` 在两轮里都执行成功，产物 `composeApp/build/outputs/apk/debug/composeApp-debug.apk`（22 169 397 B，22:44 重建）。
- Wasm 目标：`:composeApp:compileKotlinWasmJs` 两轮都执行成功（`> Task :composeApp:compileKotlinWasmJs` 无 FAILED），本批新文件零告警（修掉了一条 `'when' is exhaustive so 'else' is redundant`）。

**不写「全绿」的原因**：全量 B 确实有 2 条红。它们是既有的环境/时序 flake（同一个类在基线也红、隔离跑绿），我没有把它们改绿，也没有拿 `--tests` 过滤掉——原始数字照抄如上。

### commonMain 可移植性

`grep -nE "Math\.|String\.format|java\.|System\.|codePointCount|synchronized\("` 扫 `data/ModuleRegistry.kt`：**零命中**；`@Volatile` 走 `import kotlin.concurrent.Volatile`（同 `RealModulePageSource.kt:9`）。`compileKotlinWasmJs` 绿即证。

---

## 6. 还差什么才能让能力页真的出现模块入口

**三件事，缺一不可。**

### ① 契约层（必须 RFC，客户端无法自救）

今天没有任何 wire 通道把「模块清单 + `manifest.ui.pages`」送到 UI（§1②③）。两条候选，都要走 RFC：

- **方案 A（推荐）：`ui_to_core` 增加 `ui/modules`**
  ```json
  // result
  {"revision": 12,
   "modules": [
     {"module_id": "com.example.download", "instance_id": "dl-1",
      "display_name": "下载管理", "version": "0.3.1", "online": true,
      "pages": [{"id": "queue", "title": "下载队列", "icon": "list",
                 "version": 1, "requires": [], "refresh_s": 30}]}]}
  ```
  - core 侧**几乎是现成的**：`ModulePageService.pages(module_id, instance_id)`（`modulepage/service.py:202`）已经产出 `id/title/icon/entry/version/requires/refresh_s`，只需套一个遍历 `Registry.snapshot()` 的 handler；
  - `revision` 语义可复用 `Registry.revision()`（`registry/table.py:135`，`module/register`、`capabilities.update`、断连、健康翻转都会 +1）——客户端按「不相等即全量」处理；
  - 优于 attach 快照的原因：**模块与会话无关**（能力页可以在没有会话时打开），而 `ui/attach` 必带 `session_id`；且快照是「会话状态」的容器，塞全局清单会让「重连即拉」变成「必须先开会话」。
- **方案 B：`ui/attach` 加 `snapshot.modules[]`**（形状同上，去掉 revision）。改动小，但只在「已 attach」时可用；模块变更时还得配一个 `module.status` 触发重新 attach 或新增事件。
- 无论哪个，`module.status` 都应保持「变更通知」定位；**订阅早于 attach** 的问题（§1⑤）由方案的「拉取式」语义顺带解决。

### ② 数据层：echo 模块今天声明不出入口

`Vade/Vade-module-echo/manifest.json` 的键是 `capabilities / concurrency / display_name / instance_id / limits / module_id / priority / protocol / role / version` —— **没有 `ui`**。按 FR-8.79/§8.2，无 `ui.pages` = 无入口，**通道补齐后 echo 仍然一个入口都不会出现**。

所以 A3 的「echo 模块在线、工具卡渲染」需要额外一步：给 echo 加一页 C-19 页面（inline 树 + `state/get` 实现），或换一个有页面声明的模块做验收对象。这不是客户端能决定的（属 core 侧资产/module 仓）。

### ③ 客户端接线（本批按约束没碰，行号给全）

1. **收集事件**：`composeApp/src/commonMain/kotlin/vade/ui/data/RealSessionSource.kt:144-153` 已有两个 collector，加第三个即可 —— 必须在 `:157 _client!!.connect()` **之前**订阅（`client.events` 无 replay，文件里 `:139-141` 的注释已经写明这个坑）：
   ```kotlin
   s.launch { created.events.collect { moduleRegistry.onEvent(it) } }
   ```
2. **一处适配**：`composeApp/src/commonMain/kotlin/vade/ui/module/RealModulePageSource.kt:57` → `override fun declarations() = registry.declarations()`；`:68` → `override fun status(moduleId) = registry.status(moduleId)`（类型已逐字段对齐，测试 #14 钉住）。
3. **声明进注入口**：RFC 落地后，把 `ui/modules` 的每条 `modules[]`（或 `snapshot.modules[]`）原样 `registry.declare(...)`。若走 `ui/modules`，`declare` 需要能接受「已投影的 modules 行」而不只是 manifest 原文——那一步要么在 core 侧保持 manifest 原文（推荐，零解码分歧），要么给注册表加一个 `declareProjected(...)`。
4. **UI 重组**：`composeApp/src/commonMain/kotlin/vade/ui/destinations/CapabilitiesScreen.kt:47` 是 `remember(pageSource) { ModulePageEntryRules.build(pageSource.declarations()) }` —— `pageSource` 引用不变，事件到了**不会重组**。接线时注册表要暴露一个可观察的 `revision`（或 `StateFlow<List<ModuleDeclaration>>`），把 `remember` 的 key 换成 `(pageSource, revision)`。这一处属 `destinations/**`，本批不可改。

---

## 7. 红线自查

- **绝不 push / 合并**：本分支只在 worktree，`git log` 仅本批 commit。
- **contracts/**：一字未改（`git status --porcelain` 只有 §3 的 2 改 2 新）。
- **禁改面**：`RealSessionSource.kt` / `VadeAdapters.kt`（不存在，未新建）/ `shell` / `destinations` / `chat` / `meta` / `model` / `usage` / `search` / `module` / `skin` / `icon` / `build.gradle.kts` 全零改动。
- **未删测试/断言**：本批只新增文件；既有两个协议文件只加代码（+21/−1，那 1 行是 `version = o.str` → `o.strStrict` 的替换）。
- **未加 skip**：唯一 skip 是既有的 `VadeClientSmokeTest`（`org.junit.Assume` 门控，基线即有）。
- **commonMain**：无 JVM 专有 API（§5 扫描零命中，wasm 编译绿）。
- 报告里的数字均为本 worktree 实跑原始输出（XML 求和 / `BUILD ...` 行 / `tests completed` 行）。
