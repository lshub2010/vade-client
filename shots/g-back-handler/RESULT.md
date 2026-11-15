# g-back-handler · 可扩展返回栈（修复 G1「开浮层按返回 = 整个 App 退出」）

- 分支：`tmp/g-back-handler`（基点 = 本地 main `32970b1`，含 g-audit-gaps 审计帧）
- 代码提交：`9379eda`（先 commit 后报告；本 RESULT 为第二提交）
- 红线：未 push、未合并；真机 `192.168.1.180:5555` 全程未碰；`wm size reset` 已执行

## 1. 缺陷与修法

审计 G1：搜索浮层 / 附件预览打开后按**系统返回**（键或手势），整个 App 直接退出
（证据 `shots/g-audit-gaps/f05.png`、`f09.png`——按下返回后是 launcher，不是 App）。
全仓 `grep -c BackHandler` = 0：注释里写的「返回关闭」只对可见的返回按钮成立。

修法是**一个栈，不是逐处打补丁**（`shell/BackDispatch.kt`）：

- `BackDispatcher`：栈式返回分发。**后注册的先处理（topmost wins）**；栈序 = 组合序
  （与 Box 视觉层叠一致）。`dispatch()` 返回 `Boolean`——栈空返回 `false` =
  **未消费、交还系统**（允许退出），这就是「不吞返回」的可观测判据。
- `LocalBackDispatcher`：CompositionLocal，组合根（`App.kt`）provide 一次。
- `BackLayer(enabled, onBack, content)`：一段组合注册成一层。enabled 时入栈；
  disabled / 离开组合自动出栈——「浮层真的没了」天然伴随「栈清干净」。
  `onBack` 经 `rememberUpdatedState` 读最新值，lambda 身份变化不打乱栈序。
- `BackHost` + `expect SystemBackHandler`：系统返回桥。栈非空才拦截
  （`enabled = isHandling`），栈空不注册任何回调 → 系统默认。
- 桌面/wasm 的 Esc：`AppShell.handleShellKeys` 新增一行「Esc 优先交给返回栈」——
  栈顶浮层先关，栈空 `dispatch()==false` 回落既有语义（关搜索 / 关会话详情），
  桌面行为不变（`SearchFlowTest` 19 条全绿佐证）。

## 2. 三门禁（原始结果，未截断口径）

| 门禁 | 结果 |
|---|---|
| `desktopTest --rerun` 全量 | **1243 tests / 0 failures / 0 errors**（125 个 XML，基线 1235 + 本批 8；`composeApp/build/test-results/desktopTest/TEST-*.xml`，11:18–11:19） |
| `assembleDebug` | BUILD SUCCESSFUL in 1m 55s；APK 22,304,931 B，md5 `343aff573d4008588fadf5516283afe2`（设备验证即用此包） |
| `compileKotlinWasmJs` | BUILD SUCCESSFUL in 44s（wasm 端 expect actual 为空实现，编译通过） |

新增测试 `BackDispatchTest` 8 条，对应任务书四条验收：

1. **开浮层 → 发返回 → 浮层真的没了、页面还在**：`backClosesTheOverlayForRealAndThePageSurvives`
   ——`onNodeWithText("G1_OVERLAY").assertDoesNotExist()` + 页面节点 `assertExists()`
   （语义树级，不是「调了个回调」）；另有一条**真的 SearchHost** 集成
   （`realSearchHostRegistersOneLayerAndBackClosesTheOverlay`：开着时注册恰好一层，
   dispatch 后搜索浮层标题从语义树消失）。
2. **两层叠放只关最上层**：`twoStackedLayersOnlyTheTopmostConsumesBack`（dispatch 层）+
   `compositionOrderRegistersTopmostLast`（真实组合序注册，dialog 关、sheet 留）。
3. **没开浮层 → 返回交还**：`emptyStackHandsBackToTheSystem`（`dispatch()==false`）+
   `emptyStackEscStillFallsThroughToNotOurs`（Esc 路径同样不吞）。
4. **开关 5 次不泄漏**：`repeatedOpenCloseDoesNotLeakLayers` ——每轮关后
   `depth==0` 且节点消失。

**变异自验**：把 `BackLayer` 的出栈改成 `onDispose { /* never pop */ }` → 8 条中
**恰好 4 条组合级用例全红**（leak / topmost / 语义树消失 / 真 SearchHost），
4 条纯 dispatch 用例不受影响（符合预期，它们不经过 DisposableEffect）。
还原后 `git diff --stat` 为空，重跑 8/0/0 绿。

## 3. 设备验证（emulator-5554，1080×2400）

**重要前置**：共享模拟器上有**另一批次的并发代理在反复 install/relaunch vade.ui**
（实测 pid 4536→4719→4890→…，`lastUpdateTime` 多次前移）。第一轮验证曾被它覆盖：
我安装后 35 秒它又装了自己的包，导致我拍到的「返回退出」实为**它的构建**（无本修复）。
第二轮起每轮验证改为**原子突发 + 前后双 md5 护栏**：install → `md5sum base.apk`
必须等于本批 APK 的 md5 → 操作 → 再验 md5，两端不等即作废重做。以下帧全部来自
md5 护栏通过的突发。

| 帧 | 内容 |
|---|---|
| `d01-launch.png` | 冷启动后会话列表（fixture 6 条，顶栏玻璃胶囊） |
| `d02-search-open.png` | 点顶栏搜索图标 → 搜索浮层开（「搜索会话 · 列表内覆盖层」+ 输入框 + 范围标注 + 玻璃模糊） |
| `d03-after-back.png` | `KEYCODE_BACK` → **浮层关闭，会话列表与顶栏原样还在**（App 未退出；进程存活） |
| `d04-no-overlay-back-exits.png` | 无浮层时 `KEYCODE_BACK` → 回 launcher（`mCurrentFocus=NexusLauncherActivity`）——**栈空交还系统**，退出语义保留 |

md5 护栏记录：d02/d03 突发前后均 `343aff573d4008588fadf5516283afe2`；d04 突发前后同值。

## 4. 接入文档（后续批次怎么用）

抽屉（会话列表抽屉，另一批在建）——在抽屉自己的组合里加**一行**：

```kotlin
BackLayer(enabled = drawerOpen, onBack = { drawerOpen = false })
```

开着时入栈、关掉/离开组合自动出栈；与搜索浮层/附件预览完全同构，不需要登记、
不需要碰 App.kt。桌面 Esc 自动生效（handleShellKeys 已把 Esc 交给栈）。

模型选择浮层（另一棒在建）落地处同上加一行即可。**不要另起第二套返回机制。**

## 5. 待接清单（本批不做）

1. **模型选择浮层**：当前 `ModelPickerCard` 是内联展开卡（不是浮层），无返回可接；
   浮层化批次落地时在其浮层组合里加一行 `BackLayer`。
2. **会话列表抽屉**：同上（§4 一行）。
3. **会话详情的 Android 系统返回**：桌面 Esc 已有 `state.back()`（既有语义，未动）；
   Android 上详情页按返回目前仍会退出 App。若要改为「返回关会话」，在详情屏注册
   `BackLayer(enabled = selectedSessionId != null, onBack = { state.back() })`。
   未做原因：任务书范围是三个已知浮层 + 抽屉钩子，且 SessionDetailScreen 是
   并行批次（模型选择）的目标文件，避免同文件对撞。

## 6. 越界单列（目录所有权的偏离，逐条可回退）

| 文件 | 任务书预期 | 实际 | 理由 |
|---|---|---|---|
| `search/SearchOverlay.kt` | 改 `destinations/**`（叠加浮层的那几处） | 搜索浮层的组合点是 `SearchHost`，在 `search/` 包不在 `destinations/` | +1 行注册（其余为注释/import），纯追加 |
| `shell/AppShell.kt` | 未列入 | Esc 路由（18 行） | 任务书要求「复用已有 Esc 处理」——全仓唯一的 Esc 处理就是 `handleShellKeys`，复用它必然改此文件；默认参数 `backDispatcher = null` 保住 `SearchFlowTest` 原签名调用 |
| `androidMain/desktopMain/wasmJsMain …/BackDispatch.{android,desktop,wasmJs}.kt` | 只新建 `shell/BackDispatch.kt` | expect 的三个 actual | CMP 1.12 的 `androidx.compose.ui.backhandler.BackHandler` 所在 `org.jetbrains.compose.ui:ui-backhandler` **不在任何已解析依赖的传递闭包**（`gradle dependencies` 0 命中；gradle cache 全库扫 0 条依赖边），加依赖必须改 build.gradle.kts（禁改）。Android actual 用已在依赖里的 `androidx.activity.compose.BackHandler`（`setContent` 同源），零新增依赖；桌面/wasm 无系统返回，actual 为空 |

禁改清单核验：`skin/**`、`data/**`、`protocol/**`、`module/**`、build.gradle.kts、
contracts/** **零改动**（`git diff --name-only 32970b1..HEAD` 全部落在上表 + 允许清单）。

## 7. 已知限制 / 诚实声明

- 设备验证覆盖**搜索浮层**（任务书指定）；附件预览走的是同一行注册模式，其行为由
  BackLayer 的 8 条测试与「真 SearchHost」集成测试承重，未单独在设备上走完
  「暂存附件 → 预览 → 返回」流程（预览入口需要真实会话 + 附件暂存的多步 UI 流）。
- 桌面 Esc 优先级变化仅一处：浮层开着时 Esc 由**栈**关掉最上层（此前搜索浮层由
  既有分支关，附件预览 Esc 原本无效果）；栈空时全部语义与改前逐字节相同。
- 共享模拟器的并发代理仍在活动；其后续 install 不影响已入库截图（帧内容以
  md5 护栏为准），但若复验请沿用同款护栏。
