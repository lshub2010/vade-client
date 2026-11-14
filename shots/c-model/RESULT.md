# c-model RESULT — 客户端模型切换（RFC-0005 / FR-8.46 / FR-8.47 / D101）

分支 `tmp/c-model`（基于 main @ `f189de2`，工作区干净提交 `d3a3f69`，未 push、未合并）。
范围：新包 `vade/ui/model/`（4 文件）+ desktopTest 新目录（2 文件）+ 改
`destinations/SessionDetailScreen.kt`、`destinations/ProfileScreen.kt`、`icon/VadeIcons.kt`（仅追加）。

## 构建与测试（真实执行，原始数字）

| 检查 | 结果 |
|---|---|
| `gradle :composeApp:desktopTest --rerun`（GRADLE_USER_HOME=D:/ 原生形式） | **BUILD SUCCESSFUL**，XML 逐文件核对：**178 tests / 0 failures / 0 errors / 1 skipped** |
| 其中新增模型测试（ModelOptionTest **9** + ModelStateTest **15**） | **24**（目标 ≥14），0 failures |
| 基线核对 | 154（含 1 条 skip）+ 24 新增 = 178，对得上 |
| 跳过的 1 条 | `VadeClientSmokeTest` 的 `AssumptionViolatedException: VADE_SMOKE_PORT`（环境门控，先前就存在，与本棒无关，非静默 pass） |
| 截图 | 未拍（按任务约束，避免 kill 并行构建的 java 进程） |

## 交付物

- `vade/ui/model/ModelOption.kt` — 纯数据映射层：`ModelEntry`（协议 11 字段）→ `ModelOption`、
  三态 `CapSupport`、徽标文案、11 行明细映射、行样式决策（accent=选中）、
  `modelPickerContent`（Rows / NoModels 空态）。
- `vade/ui/model/ModelActions.kt` — 窄接口（`models` / `readOnly` / `defaultModelId` /
  `sessionOverride` / `setSessionModel` / `setDefaultModel`）+ `InMemoryModelActions` 假实现 +
  `ModelFixtures`（4 条与 FakeSessions 的 model 值对齐）。
- `vade/ui/model/ModelState.kt` — 选择状态机：单选、重复选中不发请求、离线拒绝、
  清会话覆盖、`lastNotice`（会话切换文案「下一轮对话生效」，来自 `effective_at: next_turn`）。
- `vade/ui/model/ModelPickerCard.kt` — 绘制层（GroupCard 视觉配方自持，不 import destinations）。
- 双入口：**会话详情页**头部模型行可点 → 内嵌选择卡（会话级）；**设置页**新增「默认模型」组
  （全局层，原「模型」禁用占位行已撤）。均走 `ModelActions` 注入，shell 未接线时用内存假实现，
  界面标注「假数据 · 未接 core」。
- `VadeIcons` 追加 `Model = Tabler.Outline.Sparkles`（jar 内已核，仅追加不删改）。

## 11 字段映射表（`model/list` entry → UI）

| # | wire 字段 | 协议属性（VadeModels.ModelEntry） | UI 落点 | null（未声明）时的渲染 |
|---|---|---|---|---|
| 1 | `id` | `id` | 行标题兜底、选中行明细「模型 ID」、**set 的唯一实参** | （必有，fromJson 缺省 ""） |
| 2 | `provider` | `provider` | 行 meta 行 / 明细「Provider」 | 「provider 未声明」/「未声明」 |
| 3 | `display_name` | `displayName` | 行标题（优先）/ 明细「显示名」 | 回退显示 `id` |
| 4 | `input_modalities` | `inputModalities` | **vision 徽标**（含 "image" ⇒ 支持）/ 明细「输入模态」（逗号串） | 徽标「未声明」/「未声明」 |
| 5 | `max_image_bytes` | `maxImageBytes` | vision 摘要行「≤20.0 MB」/ 明细「单图上限」 | 摘要里略去该段 /「未声明」 |
| 6 | `max_image_px` | `maxImagePx` | vision 摘要行「≤8.4M px」/ 明细「单图像素」 | 同上 /「未声明」 |
| 7 | `max_images_per_turn` | `maxImagesPerTurn` | vision 摘要行「≤4 张/轮」/ 明细「每轮图片」 | 同上 /「未声明」 |
| 8 | `context_window` | `contextWindow` | **ctx 徽标**（200K）/ 明细「上下文」 | 徽标「未声明」/「未声明」 |
| 9 | `supports_tools` | `supportsTools` | **tools 徽标** / 明细「工具调用」 | 「未声明」（false ⇒ 「无工具」） |
| 10 | `priority` | `priority` | 行 meta「· priority 10」/ 明细「priority」 | meta 略去 /「未声明」 |
| 11 | `available` | `available` | 明细「可用性」；false ⇒ 行灰 + 「离线 · 实例不可用」chip 且不可选 | 视为 Unknown，可选（服务端是校验方） |

穷举测试：`elevenFieldsAllRenderExhaustively` 断言全声明 fixture 的 **11 行齐全且值逐字正确**；
`nullFieldsSayDeclaredInsteadOfDisappearing` 断言全 null 条目不丢行、统一「未声明」。

## 能力徽标规则（数据驱动三态，禁止硬编码）

- 三态枚举 `CapSupport { Supported, Unsupported, Unknown }`；RFC-0005 §3.2：null 是 UNKNOWN，
  绝不与 false/[] 混用。徽标集合按 RFC-0005 §5 的过滤器命名：**vision / tools / ctx**。
- 派生：vision ← `input_modalities ∋ "image"`；tools ← `supports_tools`；ctx ← `context_window > 0`。
- 文案：支持「vision · 图片输入 / tools · 工具调用 / ctx · 200K」；不支持「vision · 仅文本 /
  tools · 无工具」；未知统一「· 未声明」。
- 视觉：支持 = primary 8% 底 + primary 字；不支持 = 透明底 + hairline 边 + variant 字；未知 =
  surfaceVariant 底。三种状态均由数据驱动渲染，无任何「全支持」硬编码。
- **任务书提到「cache」徽标的说明**：协议 11 字段里没有模型级缓存声明字段；
  `turn.completed.usage.cached_tokens` 是逐轮用量统计，不是能力声明，拿它做模型徽标会误导。
  故徽标集按 RFC-0005 的 视觉/工具/长上下文 落地；若未来协议加 `cache` 字段，
  在 `ModelOption.of` 里加一个 `CapSupport` 派生即可（枚举与徽标组件已三态就绪）。

## 行为语义（测试钉死）

- set 只传 `model_id`（协议 id），从不传显示名（`selectSendsTheModelIdNeverTheDisplayName`）。
- 同一模型重复选中不发第二次请求（`reselectingTheSameModelSendsNoRequest`）。
- 选中项全表唯一（`exactlyOneRowIsSelectedAndItIsTheAccentRow`）；选中行 = primary 8%（暗色 14%）
  底 + primary 边 + 对勾矢量（`modelRowStyle`，纯函数测试）。
- 同名不同 provider 不混淆：两行同 displayName、不同 id/provider，选中发的是被点行自己的 id。
- `available=false`：保留在列表、灰显 + 离线 chip、`select` 拒绝且 0 请求；
  `available=null`：可选（服务端校验），徽标 Unknown。
- 空态：`models` 为 null 或空 ⇒ 「无可用模型」卡（正文区分「无 provider」与
  「旧 core 未提供列表 → 管理员配置，FR-8.46 只读降级」），绝不渲染空列表。
- `readOnly=true`（-32601 降级）：行可看不可点，任何 select 都是 no-op。
- 会话级「跟随全局默认」行 = `session/model.set` 的 `model_id: null` 清覆盖，只清一次。

## 未接线清单（接真数据只需做这些）

1. **实现 `ModelActions`**（唯一接线点）：用 `VadeClient.modelList(ModelListParams())` 填
   `models`（缓存 `revision`/`not_modified` 语义在实现层处理）；`sessionOverride` ←
   `session/model.get` 或 attach 快照 `snapshot.session.model.model_id`；
   `setSessionModel` ← `VadeClient.sessionModelSet(SessionModelSetParams(sessionId, modelId))`；
   `setDefaultModel` ← 全局默认写法待定（core 侧 `[llm].default_model` 热改键，v1 UI 先落接口）。
   旧 core `-32601` ⇒ 实现里置 `readOnly=true` 并保持 `models=null`。
2. **把实现实例传进两个屏**：`SessionDetailScreen(modelActions = ...)` /
   `ProfileScreen(modelActions = ...)` 各有一个默认值为 null 的参数，AppShell 调用处加一行即可
   （本轮不改 AppShell——它在禁改清单里）。两个屏当前各自 remember 一个独立假实现，
   设置页改的默认模型暂不会反映到详情页的「跟随全局默认」——接同一实例后自动一致。
3. **revision 缓存/`not_modified` 复验**（打开切换器带 `if_revision`）在 ModelActions 实现层做；
   UI 层已保证 `models=null` 时不渲染空列表，`not_modified` 时实现层复用旧列表即可。
4. 能力徽标当前只覆盖协议已有字段；`cache` 若有朝一日入协议，见上节说明。

## 硬约束自查

- 只改/新建了允许清单内文件；`GroupCards.kt` / `ActivityScreen.kt` / `Destinations.kt` /
  `AppShell.kt` / `protocol/**` / `data/**` / `chat/**` / `skin/**` / `build.gradle.kts` 未动。
- 未删任何既有测试或断言；未加 skip；未 push、未合并。
- 无字符图标（IconGuardTest 扫描范围内新增文件全部走 VadeIcons）；纯图标均带
  contentDescription（Check=「已选中」、ChevronDown=「展开/收起」），可点行高 ≥44dp。
- 圆角只用了 skin.shapes 的 xs(12)/sm(16)/md(20)（同屏 3 种）；阴影用
  `Modifier.layeredShadow(VadeShadows.card, shape, dark=)`，未用 `Modifier.shadow`。
- 已知坑踩过并修复：KDoc 注释里出现字面 `<module>/*` —— Kotlin 块注释可嵌套，
  `/*` 会吞掉文件其余部分（编译期报 Unclosed comment），已改写为「m6-echo/...」形式。
