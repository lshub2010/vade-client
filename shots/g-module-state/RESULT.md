# g-module-state · RESULT — `ui/state.get` 接上模块页面：绑定页真取状态，静态页零帧零提示

分支 `tmp/g-module-state`（本地，**未 push、未合并**）。基于本地 `main`（`22c06e4`，含
上一棒 `g-modulepage-wire` 的合入）——按 `origin/main` 建的 worktree 拿不到上一棒交付物，
已重建为本地 main（本地 main 是 origin/main 的严格超集，`git log origin/main..main` 非空）。

## 一句话结论

`ui/state.get` 从**零调用点**变成宿主真发帧：树里有 `page.state.*` 绑定的页面进入时发
**恰好一帧**、值上屏；echo 式纯文本页一帧不发、也**不再出现**「模块状态不可用」那行真话。
绑定页失败画带 C-04 名的降级横幅 + 可重试（次数 1→2 实测）；`module.status`/声明快照变化
后，已打开的绑定页在下一次页内交互补一帧拿到新状态（不自动轮询）。三门禁全绿。

## 三件先核实的事（逐字，不猜）

**1. 契约（`contracts/vade/schema/rpc.json` 1.6.0，`ui/state.get`，pin `15312d00`）**：

```
params:  { module_id: string, instance_id: string, page_id: string }
result:  { rev: int(monotonic), state: object(flat: string|number|bool|null),
           fetched_at: int(unix seconds),
           unchanged: bool OPTIONAL —「模块回包不比缓存新，返回缓存 rev/state」时才有 }
errors:  NOT_FOUND / UNSUPPORTED / UNAVAILABLE / TIMEOUT / PERMISSION_DENIED / READ_ONLY_SESSION
notes:   state 上限 8 KiB / 64 键；模块不实现 state/get → UNSUPPORTED，
         客户端显示 unavailable banner——never a white page（§5.4）；不发 session_id。
```

**2. core 实际回包（`core/vade/modulepage/service.py::get_state`，权威在 core）**：
正常路径返回 `{"rev", "state", "fetched_at"}`；同 rev / 更旧回包走 CAS 后返回
`{"rev", "state", "fetched_at", "unchanged": True}`；模块 state 非法 → `UNAVAILABLE`。
**与契约一致，无需停下写报告**；`unchanged` 客户端已有类型化解码
（`StateGetResult.fromJson`），本棒不需要它（「要不要重发」由客户端自己的节奏决定，
core 的 TTL 缓存对客户端透明）。

**3. 声明式节点树里谁引用状态**（决定何时发帧）——全部出现位置：
节点 `visible` / 组件 `enabled`（`PageVisibility.Bound`）、props 的 `PropValue.Bound`
（含 `tabs.selected`）、`list` 与 `Switch` 的 `data_ref`、动作参数里 `{"bind": …}` 对象
（§6.4 顶层与数组两层）、`ui/submit` 的 `SubmitText.Bound`。三个根里**只有
`page.state.*` 需要 `ui/state.get`**：`module.status.*` 走清单快照、`item.*` 走列表项。

## 实现（全部落在允许目录：`ui/module/**` + `desktopTest/**`）

| 件 | 文件 | 内容 |
|---|---|---|
| 发帧判据 | `ModulePageBindings.kt` | `referencesPageState(root)`：扫**解析过的**类型化树（不是 raw JSON，不复制解析规则），偏保守——动作参数里的 bind 也算（漏发的代价是点击时绑定值静默落空串） |
| 状态接缝 | `ModulePageSource.kt` | `fun interface ModulePageStateFetcher`，与 `ModulePageBodyFetcher` 同一条纪律 |
| 状态装载器 | `ModulePageStateLoader.kt`（新） | 三态 `Loading→Ready/Failed` + generation 守卫 + dispose，与 `ModulePageBodyLoader` 同一套；复用 `ModulePageBodyFailure` |
| 真实现 | `RealModulePageSource.kt` | implements 里追加接口；既有 `fetchState` 加 `override`——**零 destinations 改动**（宿主从 `pageFetch as? ModulePageStateFetcher` 派生，生产接线点 `CapabilitiesScreen` 已在传） |
| 宿主 | `ModulePageRenderer.kt` | 两个 `LaunchedEffect`（① key/attempt/needsState；② stateStale）+ 横幅决定逻辑上移 + `BannerRow` 只管画 |

**为什么 phase 迁移不在 LaunchedEffect 键里**：回包到达（Loading→Ready）会改
`statePhase`，若在键里则每次回包都重触发发帧 = 死循环。键只放「触发器」（key / attempt /
needsState / stateStale / status.online），phase 只作读取与守卫。

## 逐条验收

| 要求 | 结果 | 钉住它的测试 |
|---|---|---|
| ① 只在需要时发；静态页零帧零提示 | ✓ | `ModulePageStateFetchTest.aStaticPageSendsZeroStateGetFramesAndShowsNoUnavailableBanner`：`stateCalls == 0`，全树无「状态不可用」「正在取回模块状态」「模块离线」，三行原文逐行在屏；`aModuleStatusBindingDoesNotTriggerStateGet`：只有 `module.status.online` 绑定的页也是 0 帧 |
| ② 一页一帧；重试才重发 | ✓ | `aBoundPageSendsExactlyOneStateGetAndShowsTheBoundValue`：恰好 1 帧、请求只发给本页、多次重组仍 1、绑定值上屏；`ModulePageStateLoaderTest` 同型 5 条 |
| ③ 失败降级诚实 | ✓ | `aFailedStateGetShowsTheDegradationAndRetryReallyResends`：横幅带 `NOT_FOUND` + 传输原话，点重试（contentDescription=重试）真重发 1→2，第二次成功值上屏、横幅消失 |
| ④ module.status 失效联动 | ✓ | `aModuleStatusChangeInvalidatesTheFetchedStateOnTheNextRecomposition`：下线→交互→离线横幅在、不补帧（补了也必然失败）；重新上线+版本变化→交互→补一帧、新值上屏、`stateCalls == 2` |
| ⑤ 连点两页不串 | ✓ | `switchingPagesDoesNotLetALateStateReplyCrossOver`：A 状态回包被闸门挂住，切 B 后 A 迟到回包被丢弃（generation + key 双闸），请求序列 `[m/a, m/b]` |
| ⑤ 回归 5 个测试类全绿 | ✓ | `ModulePageBodyLoaderTest`(5) / `ModulePageHostFetchTest`(3) / `ModulePageStatusBannerTest`(3) / `RealModulePageSourceTest`(15) / `OfflineAndSubmitTest` 全绿（全量 XML 无 failures） |
| ⑥ 契约 vs core 一致 | ✓ | 一致（见上），未自编字段 |

**边角两钉**：合法空状态 `{}` 不是「状态不可用」（`anEmptyStateObjectIsALegalStateNotAnUnavailableBanner`，
§5.4 缺失=空值无提示）；`module.status.*` 绑定不触发状态帧。

## 三门禁（原始数字，本 worktree 实跑）

```
gradle :composeApp:desktopTest --rerun      → BUILD SUCCESSFUL in 1m 53s
  build/test-results/desktopTest/TEST-*.xml 求和：files=115 tests=1166
  failures=0 errors=0 skipped=1（skip = VadeClientSmokeTest，既有、协议烟测、非本棒范围）
gradle :composeApp:assembleDebug            → EXIT=0；APK 22,304,931 B（09:42 新鲜）
  进包查证：classes15.dex 含 ModulePageStateLoader / ModulePageStateFetcher /
  referencesPageState / 「正在取回模块状态」四个本轮独有符号
gradle :composeApp:compileKotlinWasmJs      → EXIT=0
新测试类连跑 3 次 --rerun 全绿（防抖动采样）。
```

## module.status 联动的诚实边界（重要）

失效边 = `module.status` → `ui/modules` 重拉 → registry 快照整体替换。宿主在每次重组里
**重读** `status()`/`declarations()`，并把「取这帧状态时的模块快照」记成章；快照对象不等
（在线态/版本/页面声明任一变化，data class 逐字段相等）→ 标记过期 → 下一次**宿主重组**
补一帧。但宿主是 strong-skipping 的：**外部重组（如能力页清单刷新）参数逐项相等会被跳过，
不会重读 status**。所以「下一次交互」在产品里指**页内交互**——往 TextField 打字、点重试、
任何改变宿主自身状态的动作——它们必然让宿主重组并重读快照；零交互元素的页面在
「重进页面」时也会重取（每次打开=一帧）。没有自动轮询，每次失效至多补一帧；模块离线期间
不补帧（补了必然 UNAVAILABLE，等重新上线那一代重组再发）。测试用 `performTextInput`
驱动这条真路径（`hasSetTextAction` 定位 skin 的 BasicTextField）。

## 本棒踩的坑（都修了）

1. **测试侧全等匹配**：`onAllNodesWithText("正在取回模块状态")` 默认全等，而文案是
   「正在取回模块状态…」（带省略号）→ 10s 超时。改 `substring = true`。先误判成
   「横幅没渲染」，靠失败栈的行号定位到第二条 waitUntil 才发现状态帧早已发出（`stateCalls==1`
   那条过了）。
2. **宿主 strong-skipping**：test 5 最初靠 `tick += 1` 从外部驱动重组期待离线横幅出现——
   宿主参数逐项相等被跳过，`status()` 根本没被重读。这不是产品缺陷（外部参数没变，跳过是
   正确行为），是测试驱动方式错：改成真页内交互（typing 写宿主自己的 `inputs` 快照态）。
   由此把「下一次交互」的产品语义钉死在实现与测试两边（见上节）。
3. **worktree 基点**：按任务书 `origin/main` 建树拿不到上一棒交付物（本地 main 领先 4 个提交），
   重建为本地 main（未违反红线；`tmp/g-module-state` 上无任何历史改写，只有 `git add` + `commit`）。

## 越界与未做

- **越界：零**。`git diff --name-only` 全部落在 `ui/module/**` + `desktopTest/**`；
  `protocol/**`、`shell/**`、`destinations/**`、`skin/**`、`chat/**`、`build.gradle.kts`、
  `contracts/**`、`schema/**` 一行未动；无新增 codec（`unchanged` 客户端解码已存在）。
- **未做（如实列出）**：① `module.status` 到打开页面的**主动推送**——需要可观察的源接口
  （Flow/Compose state），超出本棒目录权限，现状是「重读靠重组」；② core 对 `ui/state.get`
  的 TTL 缓存意味着重复打开同一页时 core 侧可能直接回缓存（对客户端透明）；③ 真机/三端
  视觉验证未做（本棒无 UI 视觉改动，横幅沿用既有 BannerRow 形状）。
- 提交：`0dc5c8e`（代码+测试）→ 本报告。之后无其它提交。
