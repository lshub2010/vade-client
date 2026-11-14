# g-modulewire RESULT — ModulePageSource 真实现（接线 ui/page.get / ui/state.get）

分支 `tmp/g-modulewire`，基于 **main @ `c95c558`**（worktree 建立时 main 已被 w-adapt 合并前移到
`ac79c96`，按任务书钉回 c95c558，本树**不含** w-adapt）。代码 commit：**`ad7e1c3`**（本报告另提交）。
未 push、未合并。

范围：**只新建 2 个文件** + 本报告——`RealModulePageSource.kt`（commonMain）与
`RealModulePageSourceTest.kt`（desktopTest）。`git diff`（tracked 文件）为空：既有文件零改动
（含 `ModulePageSource.kt` / `ModulePageParser.kt` / `VadeClient.kt`），未删任何测试或断言。

## 三目标门禁（本 worktree 实跑原始数字）

| 检查 | 命令 | 原始结果 |
|---|---|---|
| 桌面全量测试 | `/d/Hermes/tools/gradle-9.6.1/bin/gradle :composeApp:desktopTest --rerun` | 49 个 XML 逐文件求和：**507 tests / 0 failures / 0 errors / 1 skipped**（基线 492 + 本批新增 15 = 507 ✓；skip 为既有 `VadeClientSmokeTest` 的 `org.junit.Assume` 门控，非本批引入） |
| Android 编译 | `gradle :composeApp:assembleDebug` | **BUILD SUCCESSFUL in 1m 1s**，APK 打包完成（仅既有文件的 deprecation 告警） |
| Wasm 编译（commonMain 无 JVM API） | `gradle :composeApp:compileKotlinWasmJs` | **BUILD SUCCESSFUL in 26s**（告警全部来自既有文件） |

commonMain 可移植性扫描（本批新文件）：`grep -nE "Math\.|\.format\(|java\.|System\.|String\.format|codePointCount"` 零命中；
`@Volatile` 走 `import kotlin.concurrent.Volatile`（照 `VadeClient.kt` 的既有写法）。

### 全量跑过程中的两次非本批红（如实记录）

1. **第一轮 9 failed**：6 条为本批测试的**装置 bug**（`runBlocking` 里失败子协程 `async` 把
   `VadeException` 结构性传播给父 Job，异常落在 `assertFailsWith` 之外）——照
   `VadeClientBehaviorTest.kt:327` 的既有习惯改为 `async { runCatching { … } }` +
   `await().exceptionOrNull()` 修掉；另 3 条是 `VadeContractCoverageGuardTest` 报
   contracts/vade submodule 未检出（新 worktree 环境问题，`git submodule update --init` +
   `tools/setup-contracts.sh` 稀疏检出 pinned `320384ae` 修复）。修复后全量绿。
2. **第三轮出现 1 条与本批无关的既有 flake**：`vade.ui.shell.UiStateSourceWiringTest.
   staleMessageRequestNeverOverwritesNewSession`（expected [a,b] / was [b,a]，第 355 行钉
   两次 selectSession 的派发顺序，同代码三轮里绿/绿/红）。单文件隔离跑 20/20 绿，全量重跑
   绿。本批只新增 `vade/ui/module/` 两个文件，与 shell/data 零交集，判定为既有时序 flake，
   交父代复验时留意。

## 四个方法的实现情况

| 方法 | 状态 | 说明 |
|---|---|---|
| `loadPage(moduleId, pageId)` | **真接上** | suspend 路径 `fetchPage`：发包前参数校验 → `VadeClient.uiPageGet`（`VadeMethod.UI_PAGE_GET` = `ui/page.get`）→ 返回的 `result.schema` 交现有 `ModulePageParser.parse` → **只有 Ok 才进缓存**；同步接口方法读缓存（接口 KDoc 明示的「实现内部缓存/挂起」形态）。 |
| `loadState(moduleId, instanceId, pageId)` | **真接上** | suspend 路径 `fetchState`：同参数校验 → `VadeClient.uiStateGet`（`ui/state.get`）→ `result.state` 原样进缓存；空 state 对象是合法状态，与「未 fetch → null」两个信号不混用。 |
| `declarations()` | **空（诚实）** | 无对应 RPC：`ui_to_core` 34 方法（`VadeMethod` 表）没有任何返回模块清单的方法；声明只随 `module.register` 事件到达，而 `VadeEventParser` 未解码它（不在 `VADE_EVENT_TYPES`）。返回 `emptyList()`，经 `ModulePageEntryRules.build` 推出空入口集 → 能力页整个模块页区块不渲染（FR-8.79 fail-closed），不是占位。 |
| `status(moduleId)` | **空（诚实）** | `module.status` 事件协议层**已**解码（`client.events` 有 `ModuleStatus`），但没有拉取式 RPC，且同步值语义接口无法消费 SharedFlow（收集事件需要宿主提供协程作用域）。事件接线落地前返回离线——宿主按 §7.4 显示「模块离线」并禁用动作，绝不假装在线。 |

### 失败分类（全部抛 `VadeException`，C-04 名 → `VadeErrorMapping` 分类）

- 参数校验（`module_id` / `instance_id` / `page_id` 任一空白，**发包前**，零帧）→
  `INVALID_ARGUMENT` → NeedsUserAction。
- 解析拒绝（脚本键 / 宿主组件 / 白名单外组件 / 深度·节点越界 / 非对象 schema）→
  `PROTOCOL_MISMATCH` → Fatal，解析器拒渲理由原样进 message（≤300 字符）。
- 其余失败原样穿透：core 错误名（如 `AUTH_FAILED` / `INTERNAL`）、客户端超时 `TIMEOUT` →
  Retryable、传输不可用 `UNAVAILABLE` → NeedsReconnect。不吞、不改名、不重试。
- **绝不返回空树伪装成功**：解析拒绝抛异常且不进缓存；`loadPage`/`loadState` 未 fetch 时
  返回 null（接口 §7.4 的「不可用」信号）。

## 测试（15 条，全绿；≥12 达标）

`RealModulePageSourceTest`（照 `VadeSessionListTypedCallTest` / `VadeClientBehaviorTest` 的假
transport 用法：真 `VadeClient` + `FakeTransport`，不 mock 协议层）：

1. `declarationsAndStatusAreHonestEmptyWithoutAnyRpc` — 空声明/离线 + 入口集为空。
2. `loadPageAndLoadStateReturnNullBeforeFirstFetchNeverEmptyTree` — 未 fetch = null，非空树。
3. `fetchPageSendsTheExactMethodStringAndParams` — 方法字串 `ui/page.get` + 参数逐字节。
4. `fetchStateSendsTheExactMethodStringAndParams` — `ui/state.get` + 参数 + 类型化解码。
5. `fetchPageSuccessKeepsExactNodeCountAndCachesTheRawSchema` — demo queue 形状 **7 节点 / 1 动作** + 原样 schema 缓存。
6. `fetchStateSuccessCachesTheStateObject` — state 缓存往返。
7. `blankParamsAreRefusedBeforeAnyFrameIsSent` — 4 种空白参数组合全部 INVALID_ARGUMENT + **transport.record 零帧**。
8. `scriptKeyPageIsRejectedAsFatalAndNothingIsCached` — `on_load` → PROTOCOL_MISMATCH/Fatal + 理由随行 + 不进缓存。
9. `offWhitelistComponentIsRejectedThroughTheParserPath` — `WebView`（白名单外）→ Fatal，复用现有解析器白名单路径。
10. `depthNinePageIsRejectedAndSurfacesToTheCaller` — 深度 9 → Fatal，拒渲理由穿到调用方。
11. `nonObjectSchemaIsRejectedAsFatal` — `"schema":42` → Fatal。
12. `authFailedErrorKeepsItsC04NameAndClassifiesAsNeedsReconnect` — 401 类（AUTH_FAILED）。
13. `internalErrorKeepsItsC04NameAndClassifiesAsNeedsReconnect` — 500 类（INTERNAL）。
14. `timeoutIsClassifiedAsRetryable` — requestTimeoutMs=100 不回帧 → TIMEOUT → Retryable（且请求帧确实发出）。
15. `transportNeverConnectedIsClassifiedAsNeedsReconnect` — 未连接 → UNAVAILABLE → NeedsReconnect（零 transport 零帧）。

## 变异自验（本批自己跑的）

`pageRejected` 的 `code = VadeErrorCodes.PROTOCOL_MISMATCH` → 改成 `INVALID_ARGUMENT`（sed，
唯一命中），只跑 `RealModulePageSourceTest`：**恰好 4 条变红**（8/9/10/11 号，ComparisonFailure
落在 PROTOCOL_MISMATCH 断言上），其余 11 条绿；还原备份后 `git status --porcelain` 与
`git diff` 均为空。

## 接线点与建议签名（接口本身不需要改）

`ModulePageSource` 四方法语义均可实现（同步值语义 + 内部缓存是接口 KDoc 明示的合法形态），
**没有改接口的理由**。真正缺的是「事件侧声明/状态如何进来」——留给宿主接线批，建议形态：

```kotlin
// 1) 协议层（w1-proto）需要先补：VadeEventParser 解码 module.register（manifest.ui.pages[]）。
// 2) 宿主收集事件后喂给源（RealModulePageSource 增补两个 seed，同步方法即可）：
fun seedDeclarations(declarations: List<ModuleDeclaration>)   // module.register 快照
fun seedStatus(moduleId: String, status: ModuleStatus)        // module.status 事件逐条喂
// 宿主侧：scope.launch { client.events.filterIsInstance<ModuleStatus>().collect { seedStatus(it.moduleId, ModuleStatus(it.payload.online, it.payload.version)) } }
// 注意：seed 前提是「真实事件数据」，不得由 UI 臆造——本次未加，遵守「不臆造」约束。
```

`ui/tool.call` 不在本接口上：动作仍经宿主 `onToolCall` 回调出去（`ModulePageRenderer:199`），
两阶段审批（validate → approval → redeem）是下一批接线点。

## 红线自查

- 只新建 2 文件 + `shots/g-modulewire/**`；tracked 文件 diff 为空（含接口/解析器/协议层）。
- 未引 kotlinx-serialization 依赖变化、未加 `@Serializable`；只用模块包已有的
  `JsonObject`（`kotlinx.serialization.json` 是既有依赖）。
- commonMain 无 JVM 专有 API（grep 零命中，wasm 编译绿）。
- 未删任何测试/断言，未加 skip，未 push，未合并。
- 报告数字均为本 worktree 实跑原始输出（XML 求和 / BUILD SUCCESSFUL 行）。
