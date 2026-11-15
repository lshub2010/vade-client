# g-state-coverage · 能力页 / 活动页 / 我的页的三态（Loading / Empty / Failed）补齐并用测试钉住

分支 `tmp/g-state-coverage`，基于 main `0ef1fde`。

---

## 0. 结论先看

1. **13 条渲染证据全部绿**（`composeApp:desktopTest`）：每屏 3 条三态底线（共 9 条）+ 每屏 1 条
   「不支持 ≠ 失败」第三态（共 3 条）+ 1 条「三屏任何状态都不许裸空白」守卫。
2. **补齐的一块真缺口**：core 对读数回答**方法不存在**（`-32601` / `NOT_FOUND` + `unknown method`）
   时，此前三张屏都把它渲染成「加载失败 + 重试」——重试永不成功，且 `-32601` 不在 34 码分类表里
   会兜底成「可重试」，文案整体在撒谎。现在三张屏各自有了独立的「不支持」卡：**没有重试钮**、
   传输原话保留、明说「不是失败也不代表没有数据」。这与 FR-8.46（model/list 的只读降级）同一判据、
   同一传统，只是把它推广到本棒的三张屏。
3. **补齐的第二块真缺口**：我的页「默认模型」卡在拉取失败后没有任何重试出口（卡内文案却承诺
   「重试后再判断」）。ProfileScreen 补了一行「重试拉取模型列表」，走 `ModelState.refresh()`
   ——既有的拉取入口，不是第二套重试。
4. 其余状态（能力页模块卡的 Loading/Empty/Failed、活动页两卡的 Loading/Empty/Failed、
   设置页模型卡的只读降级与「无可用模型」）**本来就在**，本轮用屏幕级测试钉死，不再依赖
   「有人看过一眼」。

## 1. 改了什么（全部在允许清单内）

| 文件 | 改动 |
|---|---|
| `destinations/CapabilitiesScreen.kt` | `Failed` 分支先过 [isMethodMissingAnswer]：方法缺失 → 新 `ModuleCatalogMethodMissingCard`（无重试）；其余照旧走带重试的失败卡。谓词与 `data/ModelActionsReal.isMethodMissing` 同形状（`-32601` 或 `NOT_FOUND`+`unknown method` 前缀）。 |
| `destinations/ActivityScreen.kt` | 用量/子代理两个读数区改为屏幕侧壳 `ActivityUsageLive` / `ActivitySubagentsLive`：**同一** `UsageLoader` / `SubagentLoader`、**同一** `loader.retry()`（不造第二套重试），渲染委托给既有 `UsageSection` / `SubagentSection`，只在「方法不存在」时拦下来画 `ActivityMethodMissingCard`（无重试）。空白 session 的「尚未选择会话」分支逐字保留。 |
| `destinations/ProfileScreen.kt` | `modelState.loadFailure != null` 时渲染「重试拉取模型列表」行 → `retryScope.launch { modelState.refresh() }`。方法缺失（-32601）走只读降级、不产生 loadFailure，所以不出现这行。 |
| `desktopTest/…/CapabilitiesThreeStateTest.kt` | 4 条：Loading / Empty / Failed+重试 / 不支持（真 `ModulesLoader` + 照稿源，整链）。 |
| `desktopTest/…/ActivityThreeStateTest.kt` | 4 条：同上四态（照稿 `UsageSource` / `SubagentSource`，Session 范围，整页断言）。 |
| `desktopTest/…/ProfileThreeStateTest.kt` | 4 条：同上四态（真 `ModelActionsReal` + FakeTransport，推真回包/错误帧）。 |
| `desktopTest/…/ThreeScreensNeverBlankGuardTest.kt` | 1 条守卫：14 个场景（能力页 6 面 × 活动页 5 面 × 我的页 3 面）逐一切换，每面断言「锚点文案在屏上 + 语义树至少一个非空文本」。 |

**未动**：SessionsScreen.kt、AppShell.kt、shell/**、skin/**、data/**、protocol/**、
build.gradle.kts、contracts/**（以及 usage/**、subagent/**、model/** ——三个屏幕文件之外
的 commonMain 一行没改）。

## 2. 每屏的状态面（改后）

| 屏 | Loading | Empty | Failed（可重试） | 不支持（第三态，无重试） |
|---|---|---|---|---|
| 能力页·模块 | 「正在拉取模块清单」 | 「core 报告没有模块」+ 装模块引导 | 分类+code+原话+重试 → 真重发 `ui/modules` | 「此 core 还没有模块清单方法」（原话保留） |
| 活动页·用量 | 「加载中…」（全局范围是「按会话读取」契约卡） | 「本会话暂无用量」（读数已成功）/「尚未选择会话」 | 「用量加载失败」+重试 → 复用同一 session_id | 「此 core 还没有 session/usage.get 方法」 |
| 活动页·子代理 | 「加载中…」 | 「当前没有子代理」（本会话/全部会话文案跟随范围） | 「子代理列表加载失败」+重试 → 复用同一范围 | 「此 core 还没有 subagent/list 方法」 |
| 我的页·默认模型 | 「正在加载模型列表」（绝不闪「无可用模型」） | 「无可用模型」+ core 侧配置引导 | 「模型列表加载失败」+ 分类行 + **重试行** → 重发 `model/list` | 「此 core 不支持模型选择」+ 只读章（FR-8.46 原有降级，本轮钉住） |

「不支持 vs 空」的区分没有倒退：方法缺失 ≠ 拉取失败（重试永不成功）≠ core 明说没有
（Empty）。三条路径的字面完全不同，测试逐字钉住。

## 3. 测试怎么跑的（真实证据）

```
export GRADLE_USER_HOME=D:/Hermes/tools/gradle-home
gradle :composeApp:desktopTest --tests "vade.ui.destinations.CapabilitiesThreeStateTest" \
  --tests "vade.ui.destinations.ActivityThreeStateTest" \
  --tests "vade.ui.destinations.ProfileThreeStateTest" \
  --tests "vade.ui.destinations.ThreeScreensNeverBlankGuardTest"
→ BUILD SUCCESSFUL（13/13）
```

随后全套 `:composeApp:desktopTest` 回归（结果见提交说明；守卫里 14 个场景矩阵全部
「锚点在屏 + 非空文本」双断言通过）。

装置说明（为什么不直接调渲染函数）：这批要钉的病是「数据有了/状态变了但屏幕看不见」，
所以全部用 `runComposeUiTest` 真渲染 + 语义树断言；能力页走真 `ModulesLoader`（照稿源），
活动页走照稿读取口，我的页走真 `ModelActionsReal` + `FakeTransport`（推契约形状的回包/错误帧），
三层都不掺假件短路。

## 4. 侦察里发现、本轮**没有**修的（在禁区里，留给下一棒）

1. **`ModelState.refresh()` 把请求超时当「取消」处理**（`data/ModelActionsReal.kt` + `ModelState.kt`，
   均不在本棒允许清单）：`model/list` 超时抛 `TimeoutCancellationException`（是
   `CancellationException`），`refresh()` 的 `catch (c: CancellationException) { throw c }` 先于
   `loadFailure` 兜底接住它 —— 结果：回包不到、失败也不记、`loadInFlight=false`、`models=null`，
   卡片塌成**「无可用模型」假空态**。诊断时在语义树上确认过这条路径。修法一行：超时类取消
   先落 `loadFailure` 再抛。对应用户可见症状：弱网下点「重试拉取模型列表」，失败脸闪一下后
   变成「无可用模型」。本轮测试用 60s 超时绕开它（测试注释里写明了原因）。
2. 守卫测试的场景矩阵里，我的页 Loading/Empty 面只覆盖到「真路径可见面」（FailureNotice /
   只读章）；展开后的 Loading/Empty 正文由 ProfileThreeStateTest 单独钉（守卫不再复制一遍
   FakeTransport 时序，保持一条测试里零 sleep）。

## 5. 验收对照

| 任务要求 | 落点 |
|---|---|
| 每屏 Loading 有可见占位不是空白 | 9 条三态底线 + 守卫的非空文本断言 |
| 零数据给可行动引导 | 能力页「装上模块后这里会出现卡片」/ 活动页「请选一个会话」「本会话尚未派生…」/ 我的页「由管理员在 core 侧配置」 |
| Failed = 分类 + 原话 + 可点重试（用现成 retry） | 三张失败卡 + 活动页 `loader.retry()` 双钮测试 + 我的页 `refresh()` 重试行测试 |
| 不把「不支持」当 Failed | 三屏独立「不支持/只读」面；Capabilities/Activity/Profile 各 1 条专测（无重试钮、原话保留、不洗成空/失败） |
| 守卫：任一状态不许裸空白 | `ThreeScreensNeverBlankGuardTest`（14 场景 × 「锚点 + 非空文本」） |
| 不动禁区文件 | `git status`：仅 3 个 destinations 文件 + 4 个 desktopTest 文件 |
