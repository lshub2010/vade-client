# g-miuix-slots —— miuix 皮肤的两个「丢槽」缺陷（D3 / D4）+ D9 过期文案

基线：`origin/main` @ `bbfdf2f`（worktree `Vade-client-wt/g-miuix-slots`，分支 `tmp/g-miuix-slots`）。
证据来源：`shots/g-visual-audit2/RESULT.md`（D3 高 / D4 低 / D9）。

**结论：D3、D4 是槽位契约违规，已按契约修好（不是"miuix 想不想画"）；D9 文案已与代码同真。**
三套皮肤现在对同一份数据给出的屏上内容一致，差异只剩框：圆角、底色、字号、状态条。

---

## 1 · 判据：这是契约，不是风格

`Skin.kt`（C-09 冻结，2026-11-14 追加两个槽）写得很清楚：

- `MessageBubble(body = …)`：**非 null 时皮肤渲染它**，而不是 `MessageView.text`；
- `ToolCallCard(meta = …)`：**页面给的内容**，皮肤只负责画。

两个槽的语义都是「页面给内容、皮肤给框」。皮肤可以换 padding / 行高 / 圆角 / 颜色，
**不能丢槽** —— 一旦丢，同一份数据只在这一套皮肤上消失，另一套正常。这正是审计里
「同一会话切回 material 立刻恢复成 Markdown」的成因。

修前实测（源码）：

| 组件 | 材质皮肤 | miuix（修前） |
|---|---|---|
| `MessageBubble` | `if (body != null) body() else Text(...)` | 三个角色分支全写死 `Text(message.text)`，`body` 形参**从未被调用** |
| `ToolCallCard` | `if (meta != null) { … meta() }` + `CodeBlock(label="ARGS"/"RESULT")` | `meta` 形参**从未被调用**；args 只有一个无标签的灰井，result 是裸 `Text` |

---

## 2 · 改了什么

### D3 · `MiuixMessageBubble` 消费 `body`

- 三处 `Text(message.text)` 全部换成 `SlottedOrFallback(body) { Text(…) }`：**有 body 用 body，
  没有才回落**，与 material 同构（`MaterialParts.kt:276`）。
- 回落**逐字保留**旧观感：assistant 用 `typography.body`、user 用 `typography.body`（14/10 内边距）、
  system 仍是 11sp `onSurfaceVariant` 的 pill —— 换槽不动盒子，miuix 的 padding / 行高 / 圆角
  （`lg` 24 + 6dp 气泡尾 / `pill`）都是自己的。
- 槽消费收敛到一个私有 `SlottedOrFallback(slot, fallback)`，全文件只有这一处 `slot()`，一眼可查。

### D4 · `MiuixToolCallCard` 消费 `meta` + 补 `ARGS` / `RESULT` 内嵌块

- `meta`：`if (meta != null) { Box(padding 12/12/10) { meta() } }`。页面（`ChatThread.ToolRow`）
  在折叠时传 `null`，皮肤只负责「有就画」—— 与 material 同构，四行不再凭空消失。
- 新增私有 `MiuixCodeBlock(text, label, …)`：**标签行（mono、11sp）→ 1dp `hairline` 分隔 →
  可滚动的 mono 正文（上限 140dp）**。
  - 圆角 `MiuixTokens.radiusXs`（12dp，v2 §10 的"代码块"档）；
  - 底色 `MiuixTokens.sunken(colors)`（新加：浅色 `surfaceVariant` 暖灰，深色纯黑 —— 即
    Miuix 暗色 `surfaceVariant` 本身）**不是**抄 material 的 12dp + 半透明白标签条；
  - RESULT 在 `isError` 时用 `colors.error` 着色；
  - 同一份 `sunken` 也接到审批弹窗的参数井上，一个语义只留一处字面量。

### D9 · 设置页文案

`destinations/ProfileScreen.kt` 的「外观」块：

- 旧：`miuix / glass 是占位实现（返回 material token + 未安装角标），U2/U3 注册真实现后自动可用。`
- 新：`miuix 是完整实现：自有 token、真字体缝（VadeFonts，Web 也走同一族）与 12 个 C-09 组件，
  不是 material 换色；glass 仍是占位（复用 material token，行内标「glass 未安装」），U3 注册真实现后自动可用。`
  —— `glass` 那句仍然保留且是真的（`Skins.installBuiltIns()` 里它还是
  `registerPlaceholder("glass", MaterialSkin("glass", "[glass 未安装]"))`），反过来撒谎同样是缺陷。
- 皮肤列表：chip 改显示**用户可读名** `经典 / Miuix / 玻璃`，内部 id 退到下面一行 11sp：
  `内部 id：material · miuix · glass（皮肤注册表主键；当前 miuix）`。点 chip 仍按 id 回调
  （新增测试钉住 `Miuix` → `"miuix"`）。

> ⚠️ **越界说明（必须让主控看到）**：`ProfileScreen.kt`（`ui/destinations/`）不在任务书
> 「允许改」的三条路径里，但 D9 的过期文案与皮肤列表**只存在于这个文件**，第 3 条要求无法
> 在允许域内完成。改动严格限定在「外观」块的两段文案 + chip 的显示名/回调参数（不碰布局、
> 不碰其它块，新增 `label` 形参）。`ChatThread.kt` / `skin/Skin.kt` / `skin/material/**` /
> `component/liquid/**` / `shell/**` / `chat/**` / `build.gradle.kts` / `contracts/**` **零改动**。

---

## 3 · 新增测试（`desktopTest`）

`composeApp/src/desktopTest/kotlin/vade/ui/skin/MiuixSlotConsumptionTest.kt`（9 条）
—— 全部读**屏上语义树**（不是搜源码），逐条带整屏文本的失败报告：

| 测试 | 钉住什么 |
|---|---|
| `miuixBubbleRendersMarkdownInsteadOfLiteralMarkers` | 含 `**粗体**` + 三反引号 kotlin 代码块的消息在 miuix 下**不出现**字面 `**` / 三反引号；`原图未保留`、`kotlin` 语言标签、代码正文都在 |
| `miuixUserAndSystemBubblesAlsoRenderTheMarkdownBody` | 三个角色分支**各自**都消费 `body`（只修 assistant 会红） |
| `bothSkinsShowTheSameTextForTheSameMessage` | 同一份正文，material 与 miuix 屏上文本集合**逐项相等** |
| `nullableBodyKeepsTheFrozenPlainTextRendering` | `body = null` 时退回 `Text(message.text)`（槽是"覆盖"不是"替换"） |
| `miuixToolCardDrawsAllFourMetaRows` | 四行 meta 各自关键词 + 值（执行耗时 / 批次 / 错误码 / 发起于） |
| `miuixToolCardDrawsArgsAndResultLabels` | `ARGS` / `RESULT` 两个标签**和**两块正文都在 |
| `bothSkinsShowTheSameMetaRows` | material 侧零变化的回归 + miuix 侧四行与两标签 |
| `nullableMetaDrawsNothingOnEitherSkin` | `meta = null` 时两套皮肤都不许凭空画行 |
| `miuixPartsKeepTheSlotCalls` | 源码级兜底：`SlottedOrFallback(body)` / `meta()` 被删即红，报错直说「D3/D4 会回来」 |

`composeApp/src/desktopTest/kotlin/vade/ui/destinations/ProfileSkinCopyTest.kt`（3 条）
—— D9 的文案与列表：旧句消失 / 新句在位 / glass 仍写占位；`经典·Miuix·玻璃` 三个可读名 +
`内部 id` 次级行；`Miuix` 点击回调 `"miuix"`。

**变异验证（红过才算数）** —— 每次只改一处、跑完立刻还原：

| 变异 | 结果 |
|---|---|
| `SlottedOrFallback` 改成永远走 fallback（= 丢槽） | 3 红：`miuixBubble…`、`miuixUserAndSystem…`、`bothSkinsShowTheSameText…` |
| 删掉 `meta()` 调用 | 3 红：`miuixToolCardDrawsAllFourMetaRows`、`bothSkinsShowTheSameMetaRows`、`miuixPartsKeepTheSlotCalls` |
| `label = "ARGS"/"RESULT"` 改成中文块名 | 2 红：`miuixToolCardDrawsArgsAndResultLabels`、`bothSkinsShowTheSameMetaRows` |

---

## 4 · 门禁（以 `build/test-results/desktopTest/TEST-*.xml` 的 `tests=` 求和为准）

最终一次跑在**将要提交的源码**上：

```
desktopTest --rerun :  110 个 XML，tests=1145  skipped=1  failures=0  errors=0
                       其中新增 MiuixSlotConsumptionTest=9、ProfileSkinCopyTest=3
assembleDebug      :  BUILD SUCCESSFUL（composeApp/build/outputs/apk/debug/composeApp-debug.apk）
compileKotlinWasmJs:  BUILD SUCCESSFUL
```

`skipped=1` 是基线就有的（与本次改动无关）。

---

## 5 · 截图（桌面真帧，非手绘）

同一份夹具（含 `**粗体**`、行内代码、三反引号 kotlin 代码块、展开的工具卡 + 四行 meta）在两套皮肤下渲染：

- `shots/g-miuix-slots/miuixSlots.png` —— miuix：正文是 Markdown（粗体真加粗、行内代码有底、
  代码块有 `kotlin` 语言标签 + 凹陷底），工具卡有 `ARGS` / `RESULT` 两个标签井 + 四行 meta；
- `shots/g-miuix-slots/materialSlots.png` —— material 对照：同样的内容，不同的框；
- `shots/g-miuix-slots/profileAppearance.png` —— 设置页「外观」：`经典 / Miuix / 玻璃` +
  两段新文案，旧句已不在。

（截图由一个临时探针测试生成，跑完即删，未进提交；留下的是 PNG 本身。）

---

## 6 · 遗留 / 需要主控决定

1. **`ProfileScreen.kt` 越界**：见 §2 的说明 —— D9 的落点不在允许域内，是否接受由主控判定。
2. **`fixed / floating / floating+blur` 仍是英文内部 id**：审计只点了"皮肤列表"，底栏这排
   确实也是内部枚举名（`BottomBarStyle`）。本次**故意不动**（D9 范围外，且它同时是
   `--bar` 类参数的取值，改名要连带文档与命令行）。若要一并做，是独立一棒。
3. **`ModelStateSeamPathTest.forSessionWithTheRealAdapterPullsOnConstructionAndRendersTheRows`
   在本机全量跑时偶发红**（`assertFalse(state.loadInFlight)` 紧跟在 `waitUntil { models != null }`
   之后，是时序竞态）。单独连跑 3 次全绿；与本次改动无关（不碰 model/ 与 skin/），未修改。
   最终门禁那次是绿的。
4. `VectorIconSwapTest.profileChipsShowSelectionByStyleNotGlyph` 原来断言 chip 文本是 `"material"`，
   随可读名改动同步为 `经典 / Miuix / 玻璃`（**加强**：三个名都在，且仍扫 `●` 类字符图标为 0）。

---

## 7 · 未越界清单（逐条核对）

| 禁区 | 状态 |
|---|---|
| `skin/Skin.kt` 接口形状 | 未改 |
| `skin/material/**` | 未改（改后回归测试证明行为不变） |
| `component/liquid/**`、`shell/**`、`chat/**` | 未改 |
| `ChatThread.kt` | 未改（修 D3 不需要动页面 —— 它早就在传 `body`） |
| `build.gradle.kts`、`contracts/**` | 未改 |
| push / merge / amend / rebase / reset | 全部未做，只在本地 `tmp/g-miuix-slots` 上提交 |
