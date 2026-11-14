# C-Activity · Activity 页「用量 + 子代理」面板 — RESULT

worktree: `D:/HermesWorkspace/Vade-client-wt/c-activity`，分支 `tmp/c-activity`，基于 main @ f189de2。
代码 commit：**00074fa**（报告本身随后单独提交）。**未 push、未 merge。**

按约束**未拍任何截图**（截图脚本会 kill 全部 java 进程，影响并行构建）；本文件即交付记录。

## desktopTest 原始数字（--rerun，来自 build/test-results/desktopTest/TEST-*.xml）

- 总计：**tests=193，failures=0，errors=0，skipped=1**
- skipped 是既有的 `VadeClientSmokeTest.attachSubmitAndLiveEventsAgainstARealCore`（真核冒烟，无核时跳过，基线内已有，与本切片无关）
- 基线 154 + 本切片新增 39 = 193：
  - `vade.ui.usage.UsageProjectionTest` — 11
  - `vade.ui.usage.BudgetLevelTest` — 13
  - `vade.ui.subagent.SubagentBadgeTest` — 5
  - `vade.ui.subagent.SubagentPreviewTest` — 10
- 测试全部喂**真实契约形状的 DTO**（多数直接用 protocol 层 `VadeUsage.fromJson` / `SessionUsageGetResult.fromJson` 解码 rpc.json/events.json 形状的 JSON 字面量），不经过任何假数据层。本模块 desktopTest 无 compose ui-test 依赖（不许改 build.gradle.kts），故断言落在纯投影函数层；组合函数只做薄渲染。

## 五状态徽标映射表（穷举断言：SubagentBadgeTest.fiveStatesMapToFiveDistinctIcons）

| state（契约 raw） | VadeIcons 语义名 | Tabler outline | 徽标词 | contentDescription | §13 强调边/徽标色 token |
|---|---|---|---|---|---|
| spawned | `Spawned`（本次追加） | rocket | 已生成 | 子代理状态：已生成 | `primary` |
| running | `Running`（本次追加） | player-play | 运行中 | 子代理状态：运行中 | `primary` |
| completed | `Check`（既有） | check | 已完成 | 子代理状态：已完成 | `success` |
| failed | `Failed`（既有） | ban | 失败 | 子代理状态：失败 | `error` |
| cancelled | `Close`（既有） | x | 已取消 | 子代理状态：已取消 | `onSurfaceVariant` |
| （未知 raw） | `Waiting`（既有） | hourglass | 未知状态 | 子代理状态：未知状态 | `onSurfaceVariant` |

- 五值两两不同图标（Set size == 5 穷举）；未知 raw 不折叠成 running（不假装活跃），渲染中性「未知状态」。
- **VadeIcons.kt 追加清单（只追加，无删改）**：`Spawned` → `Tabler.Outline.Rocket`、`Running` → `Tabler.Outline.PlayerPlay`（getter 名已对 2.2.1 jar `javap` 核实）。

## 预算阈值表（BudgetLevelTest.fourTiersAt79_80_100_andOverLimit）

契约 `budget.state`（session/usage.get，权威）优先；无权威态时按 UI 阈值：

| 输入 | level | 进度条填充色 token | UI 标签 |
|---|---|---|---|
| ratio < 0.80 | `NORMAL` | `primary`（强调） | — |
| ratio ≥ 0.80（如 79%→80% 边界测试点 0.80） | `WARNING` | `warning` | 警告线 |
| used == limit（100%，契约 state=warn 亦强制此档以上） | `CRITICAL` | `error` | 已达上限 |
| used > limit（超限；契约 state=exceeded 亦强制） | `EXCEEDED` | `error` | 已超限 |
| limit=0 / state=off | `NONE` | 不画条 | 状态行显示 verbatim "off" |

- CRITICAL 与 EXCEEDED 共用 `error` 是有意的：视觉语言 §二固定状态色板、禁造第四种状态色；两档由 level + 「已超限」标签区分（测试里断言了色差哨兵，不是第四色）。
- `enforce=block`（budget.enforced=true）显示「超出即阻断（enforce=block）」；false →「仅警告，不阻断」；null → 暂无数据。
- `usage.budget` 事件横幅：warn/exceeded/degraded/未知原文四条路径各有测试；`degraded=true` 时明确说「enforce=block 的拒绝已降级为警告 + 账本不可信」，并标注「权威状态以 session/usage.get 为准」（契约 notes 原义）。

## 真数据 / 空态诚实清单（当前接线状态）

**当前运行中的 App 全部四参都是默认 null（AppShell 只传 contentPadding），因此页面上所有面板都是空态——没有任何一个数字是编造的。** 面板数据一旦由 data 层传入即变真数据，UI 零改动：

| 字段 | 数据来源（契约） | 注入参数 | 当前状态 |
|---|---|---|---|
| 本轮 输入/输出/合计 tokens | `turn.completed.usage`（events.json，required 对象） | `lastTurnUsage: VadeUsage?` | **空态**（未接线） |
| 本轮 cached_tokens / reasoning_tokens / steps | 同上（RFC-0005 append 可选键） | 同上 | **空态**（未接线；缺失→「暂无数据」，0→「0」，两者不同且有测试） |
| 本轮 model_id / provider / source | 同上 | 同上 | **空态**（未接线） |
| source=none 降级标识 | `usage.source == "none"` | 同上 | 逻辑已实现+测试；**实际显示待接线** |
| 会话累计 chat/aux/by_model/day | `session/usage.get`（rpc.json） | `usage: SessionUsageGetResult?` | **空态**（未接线） |
| 预算条 used/limit/ratio/state/enforced | `session/usage.get.budget`（权威） | 同上 | **空态**（未接线） |
| 预算通知横幅 | `usage.budget` 事件（非权威） | `budgetNotice: UsageBudgetPayload?` | **空态**（未接线） |
| 子代理卡片列表 | `subagent/list` + `subagent/get`（RFC-0012 §6.1/§6.2） | `subagentRuns: List<SubagentRunView>?` | **空态**（未接线；protocol 层尚无 subagent.* 模型，data 层接 RPC 后映射成 `SubagentRunView` 传入即可） |
| `result_preview` 摘要 | `subagent/get.result_preview`（≤500 字读时投影） | 随 SubagentRunView | 空态；为 null/blank 时显示「无摘要 — 子会话没有可投影的助手输出」 |
| 「仅预览，全文在子会话」 | RFC-0012 §5.3 脱敏规则 | 常驻 | **每张卡都显示**（常量 `PREVIEW_ONLY_NOTE`，措辞由测试钉死）；卡面只有 digest 短指纹 + 字数 + 预览，永不出现全文 |

## 交付物清单

新增（commonMain）：
- `vade/ui/usage/UsageModel.kt` — TokenCell / BudgetLevel / projectUsage·projectTurnUsage·projectSessionUsage·projectBudget / budgetNotice / formatTokens·formatDuration（纯函数，可测层）
- `vade/ui/usage/UsageCard.kt` — UsagePanel 渲染（降级横幅、行、预算条、通知）
- `vade/ui/subagent/SubagentRunView.kt` — SubagentState（五值+UNKNOWN）+ SubagentRunView 快照 DTO（subagent/get 形状，无网络代码）
- `vade/ui/subagent/SubagentBadges.kt` — 图标/标签/描述/强调色映射表
- `vade/ui/subagent/SubagentModel.kt` — PreviewCell / PREVIEW_ONLY_NOTE / digestText / charsCell / durationText / errorCell
- `vade/ui/subagent/AccentEdge.kt` — `Modifier.accentEdge`（§13：沿左缘变宽丝带，跟随 topLeft 圆角弧、顶 8dp 内 0→3dp、末 12dp 3dp→0、65% 高度处消失；宽度渐变而非 alpha 渐隐；clip 到卡片 shape）
- `vade/ui/subagent/SubagentCard.kt` — SubagentCard + SubagentPanel（null=未接线空态 / emptyList=真实为空，两态分开）

修改：
- `vade/ui/destinations/ActivityScreen.kt` — 保留三个 U1-d 占位块原样，新增「用量」「后台任务·子代理」两节，四个可注入参数（默认 null）
- `vade/ui/icon/VadeIcons.kt` — 仅追加 Spawned/Running

新增（desktopTest）：上述 4 个测试类，39 条。

## 守住的约束

- 未改 `vade/ui/data/**`、`vade/ui/protocol/**`、`shell/`、`chat/`、`skin/`、`GroupCards.kt`、`Destinations.kt`、`meta/`、`input/`、`build.gradle.kts`（git status 仅 ActivityScreen.kt + VadeIcons.kt 两个修改，其余全是新增）。
- 无网络/协议调用：卡片只吃 DTO 快照。
- 圆角只用 md(20)/xs(12)/全圆；阴影走 `Modifier.layeredShadow(VadeShadows.card, …)`；图标全部 `VadeIcon`+`VadeIcons` 矢量（IconGuardTest 4 条仍 0 失败）；徽标图标均带中文 contentDescription；本面板无按钮，无 <44dp 触摸区问题。
- 未删改任何既有测试/断言；未新增 skip。
