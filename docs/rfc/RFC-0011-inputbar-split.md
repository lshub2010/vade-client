---
id: RFC-0011
title: InputBar 拆分为「输入区」与 SendOrb（C-09 组件 11 → 12）
status: proposed
date: 2026-11-14
affects: [C-09, vade-client skin/, vade-client chat/]
supersedes: none
amends: C-09（AMEND-22 / D93 冻结的 Skin 组件清单）
---

# RFC-0011 · InputBar 拆分为「输入区」与 SendOrb

## §1 摘要

C-09 冻结的 `Skin.InputBar` 签名自带文字发送职责（`onSend` / `onStop` / `sendEnabled`），
而视觉规范 v2 §1/§2 明确：**输入框内不放发送按钮，发送是框外的 48dp 独立圆球**。这条冲突
在三个槽位独立撞车：

- `w1-material` 把发送控件从框内移除，`onSend/onStop/sendEnabled` 沦为从未被实现的死参数；
- `w1-miuix` 把球画进了组件内部（框外、但仍在组件里，页面无法控制球的间距与位置）；
- `w1-chat` 干脆在页面层用 GlassSurface + BasicTextField 重新拼了一个 composer，完全绕过
  `Skin.InputBar` —— C-09 组件 11 在产品主路径上 0 调用，皮肤切换时 composer 观感不跟随皮肤。

本 RFC 把 `InputBar` 收窄为纯输入区，把发送/停止独立成第 12 个 C-09 组件 `Skin.SendOrb`，
并配 `SkinOrb` motion token。这是**破坏性变更**（签名删除参数），C-09 组件数 11 → 12，
防回退测试同步 11 → 12。

### 决定表

| # | 决定 | 理由 |
|---|---|---|
| D1 | `InputBar` 删除 `onSend` / `onStop` / `sendEnabled`，只保留输入区职责 | v2 §1「输入框内不再放发送按钮」；三个参数在 material 实现里已是从未被读的死参数 |
| D2 | 新增 C-09 组件 12：`Skin.SendOrb(mode, enabled, onClick, modifier)` | v2 §2「球是独立元素」——独立组件才能让页面控制位置与间距 |
| D3 | 新增 `SkinOrb` token（size=48.dp / glowRadius=12.dp / pressScale=0.92f / pressDurationMs=180），`Skin.orb` 默认实现访问器 | §2 的四个数字钉进契约，皮肤可整体覆盖；不新增散装参数 |
| D4 | 球的三态由 `InputMode` 驱动：Idle=空玻璃球、Composing=armed（accent 实心 + 辉光）、Streaming=停止 | 页面已有 `ChatState.inputMode` 推导（草稿/附件非空 ⇒ Composing）；不引入第二个布尔信号 |
| D5 | 浮层边距（16dp / 桌面 24dp）归**页面布局**；InputBar 面板紧贴自身槽位，不再自带 16dp 外边距 | 页面把 `[＋] [InputBar] [12dp] [SendOrb]` 拼进同一行时，组件内边距会叠加成 32dp、框球间距变成 28dp，§1/§2 的数字全部失真 |
| D6 | 防回退断言从「恰好 11 个」改为「恰好 12 个」 | 守卫必须钉住 RFC 落地后的新契约，否则下一次 13 组件无人报警 |

## §2 动机

### §2.1 三个槽位的实际撞车记录

| 槽位 | 撞车方式 | 证据（本仓 HEAD `1d95a76`） |
|---|---|---|
| `w1-material` | `MaterialInputBar` 的浮层里没有发送控件，注释写明「发送/停止控件不在框内 —— 它们是框外一颗 48dp 正圆球（页面层绘制）……冻结签名里的 onSend / onStop / sendEnabled 原样保留，由页面层接到那颗球上」 | `skin/material/MaterialParts.kt` — 函数体从不读 `onSend/onStop/sendEnabled`；契约参数与实现脱节 |
| `w1-miuix` | `MiuixInputBar` 在组件内部的 Row 里画了 `SendBall`（球在框外、但组件内），页面拿到的仍是「框+球」一体组件，无法按 §2 控制球的独立性与间距 | `skin/miuix/MiuixParts.kt` — `SendBall` 私有函数 + `sendBallGap` token 全部锁死在组件里 |
| `w1-chat` | `ComposerOverlay` 在页面层用 `GlassSurface` + `BasicTextField` + 自绘 `SendBall` 重拼 composer，**完全绕过 `Skin.InputBar`** | `chat/ChatThread.kt` — C-09 组件 11 在产品主路径 0 调用（唯一调用方是 miuix showcase 演示页） |

三条路都是对的（观感都符合 v2），但三条路互不相同 = 契约缺了一个槽位。组件缺槽位时，
冻结规则允许「只加可选参数」，但这里的变更方向是**删参数**（发送职责整体外移），可选参数
救不了 —— 必须走 RFC。

### §2.2 视觉规范 v2 原文（docs/visual-language.md，v2 · 2026-11-14）

**§1 输入框常驻悬浮（floating composer）**：

> - 不再贴底占满一行：它是一个**独立浮层**，左右各留 16dp（桌面 24dp），距底安全区 16dp。
> - 形状：`xl` = 28dp 圆角（多行时同圆角，不做胶囊变形）；背景 = 玻璃层（见 §5），描边 1px
>   `hairline`，**并带浮层阴影**（§8：它是浮层，不是静态卡片）。
> - 列表底部内边距 = composer 实测高度 + 24dp，滚到底时最后一条消息不被遮住；键盘弹出时
>   浮层随 `imePadding` 上移，列表不动画跳跃。
> - **输入框内不再放发送按钮**；只放：左侧附件图标、文本域、右侧字符计数（可选）。

**§2 发送球（独立于输入框）**：

> - 一个 **48dp 正圆**，位于 composer **右侧、垂直居中**，与输入框间距 12dp；
>   **不与输入框同容器背景（它是独立元素）**。
> - 有内容：`accent` 实心 + 向外 12dp 柔光（`accent` 24% alpha），图标反白。
> - 空内容：`surfaceGlass` + 1px `hairline`，图标 `muted`；此时仍可点（触发提示），不闪烁。
> - 生成中：同一球位变**停止**（方形图标），点击中断；球保持 `danger` 色但只染图标与描边，
>   不整球变红（避免视觉噪音）。
> - 按压：`scale 0.92`，180ms，`cubic-bezier(0.2,0,0,1)`；松手回弹。
> - 禁止：球里写字（"发送"）、球做成长条、球与输入框连成一体。

## §3 目标 / 非目标

**目标**

1. `Skin.InputBar` 与发送职责彻底解耦：签名里不再出现 send/stop。
2. 新组件 `Skin.SendOrb` 承接 v2 §2 的球，三态（空/armed/停止）由 `InputMode` 驱动。
3. 三个皮肤同步：material、miuix 各自实现 SendOrb；glass 占位（`MaterialSkin(skinId="glass")`）自动继承。
4. `chat/ChatThread` 改回 `Skin.InputBar` + `Skin.SendOrb` —— 页面只负责布局（行、间距、＋ 按钮），不再自拼输入面板或球。
5. 防回退断言 11 → 12，杜绝下一个组件无 RFC 溜进来。
6. 数字契约化：48/12/0.92/180 进 `SkinOrb`，皮肤不再各养一套常量。

**非目标**

1. 不给 InputBar 加 `leading` 附件槽位（＋ 按钮暂由页面放在框外左侧）—— 见 §7.1。
2. 不在 InputBar 内部接真背景模糊（GlassSurface/hazeSource 接线）—— 见 §7.2。
3. 不改圆角阶梯、阴影/辉光分层、图标体系 —— 本 RFC 只动职责边界，不动观感 token。
4. 不动 `InputMode` 枚举本身（Idle/Composing/Streaming 语义不变）。

## §4 方案

### §4.1 InputBar 收窄为输入区

```kotlin
@Composable
fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    mode: InputMode,
    attachments: (@Composable () -> Unit)? = null,   // 已暂存附件行（面板内、文本域上方）
    enabled: Boolean = true,
    voiceAvailable: Boolean = false,                  // D97：false = 不画麦克风
    maxLines: Int = 6,
)
```

删除：`onSend: () -> Unit`、`onStop: () -> Unit`、`sendEnabled: Boolean = enabled`。
保留：`mode`（皮肤可用它做流式时的输入区弱化，当前两份实现均未读，允许保留）。

面板行为（两份皮肤一致）：玻璃底（`surfaceGlass`）+ 1px `hairline`（聚焦 2dp accent 描边）+
浮层阴影（`shadowFloat`）+ `xl` 28dp 圆角 + 多行 `maxLines` 封顶内部滚动；**不再自带 16dp
浮层外边距**（D5，边距归页面 §1 布局）。

### §4.2 新组件 SendOrb

```kotlin
@Composable
fun SendOrb(
    mode: InputMode,          // Idle=空 / Composing=armed / Streaming=停止
    enabled: Boolean = true,
    onClick: () -> Unit,      // 页面接 send-or-stop：if (streaming) stop() else submit()
    modifier: Modifier = Modifier,
)
```

状态映射（D4）：

| mode | 观感 | 可点击 |
|---|---|---|
| `Idle` | `surfaceGlass` + 1px `hairline`，图标 muted（§2 空内容态） | 仍可点（§2「触发提示」）——onClick 交给页面 |
| `Composing` | `accent` 实心 + 向外 `glowRadius`(12dp) 柔光（glowAccent @28% 渐隐），图标反白 | 可点 |
| `Streaming` | 同一球位变停止 ■；只染图标与 1.5dp 描边为 `danger`，**不整球变红** | 可点（中断） |

页面职责（policy）：草稿/附件非空 ⇒ 传 `Composing`，生成中 ⇒ `Streaming`，否则 `Idle`；
`onClick` 由页面接 send-or-stop。皮肤职责（rendering）：上述三态的每一个像素，球里永远
没有文字，球与输入框永不连成一体（§2 禁止项）。

### §4.3 SkinOrb token

```kotlin
@Immutable
data class SkinOrb(
    val size: Dp = 48.dp,          // §2：48dp 正圆
    val glowRadius: Dp = 12.dp,    // §2：向外柔光半径（glow，不是阴影）
    val pressScale: Float = 0.92f, // §2：按压缩放
    val pressDurationMs: Int = 180, // §2：按压时长；曲线用 SkinMotion.easing
)
```

`Skin` 接口新增 `val orb: SkinOrb get() = SkinOrb()` —— 与 `motion` / `glass` 同型的默认
实现访问器：不吃老皮肤的编译，想要自定义的皮肤整体覆盖。

### §4.4 页面组装（chat/ChatThread）

```
Row(verticalAlignment = CenterVertically) {
    ChatIconButton(＋)            // 页面层，框外左侧（§7.1）
    Box(weight(1f)) { skin.InputBar(...) }   // 面板紧贴槽位
    Spacer(12dp)                            // §2 框球间距
    skin.SendOrb(mode = state.inputMode, onClick = { … })
}
```

浮层左右边距（16dp / 桌面 24dp）、距底 16dp、列表底部内边距 = 实测高度 + 24dp 仍由页面
的 `ComposerOverlay` 承担（§1 的位置语义本来就是页面的事）。

## §5 契约变更

### §5.1 C-09 组件清单：11 → 12

| v1（AMEND-22 / D93 冻结） | v2（本 RFC） |
|---|---|
| 1 Button · 2 TextField · 3 Card · 4 ListItem · 5 Switch · 6 BottomBar · 7 ApprovalDialog · 8 MessageBubble · 9 AttachmentChip · 10 ToolCallCard · 11 InputBar | 1–11 同左 · **12 SendOrb（新增）** |

### §5.2 InputBar 签名变更（破坏性）

```diff
 @Composable
 fun InputBar(
     value: String,
     onValueChange: (String) -> Unit,
     mode: InputMode,
-    onSend: () -> Unit,
-    onStop: () -> Unit,
     attachments: (@Composable () -> Unit)? = null,
     enabled: Boolean = true,
     voiceAvailable: Boolean = false,
-    sendEnabled: Boolean = enabled,
     maxLines: Int = 6,
 )
```

删除参数是**破坏性变更**：C-09 契约版本升一级（11 组件冻结版 → 12 组件版），冻结语义
（"只准加可选参数"）按 RFC 流程豁免 —— 本 RFC 即豁免凭证。

### §5.3 SendOrb 完整签名（新增）

```kotlin
/**
 * 12 · SendOrb (RFC-0011): the send/stop ball v2 §2 moved OUT of the input frame.
 * States are driven by [mode] (Idle = empty glass ball, Composing = accent + glow,
 * Streaming = stop ■); the page owns policy (when armed, what onClick does), the skin
 * owns every pixel. Metrics live in [Skin.orb] (48dp / 12dp glow / 0.92 / 180ms).
 */
@Composable
fun SendOrb(
    mode: InputMode,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
)
```

### §5.4 SkinOrb token（新增）与访问器

见 §4.3；挂载点为 `Skin.orb`（默认实现，不强制皮肤覆盖）。

## §6 兼容策略

1. **破坏性变更，不留旧签名兼容层**。客户端仓只有三个调用方，同一提交内全部迁移：
   - `skin/material/`：`MaterialInputBar` 删三个死参数；新增 `MaterialSendOrb`（移植页面层
     球的 v2 观感：drawBehind 辉光 + Canvas 矢量图标，皮肤层不得 import chat/ 的图标包）；
   - `skin/miuix/`：`MiuixInputBar` 移除内部 SendBall；`SendBall` 逻辑原样搬入
     `MiuixSendOrb`（halo 辉光、↑/■ 字形、按压缩放全部保留），驱动信号从
     `(streaming, sendEnabled)` 换成 `mode`；
   - `glass` 占位 = `MaterialSkin(skinId="glass")`，随 material 自动获得 SendOrb，无需单独改；
   - `skin/miuix/MiuixShowcase.kt`：调用点删 `onSend/onStop`，同时把 SendOrb 拼进演示行
     （证明组件可独立渲染）。
2. **页面迁移**：`chat/ChatThread.ComposerOverlay` 拆掉自拼 composer，改用
   `Skin.InputBar`（附件槽承载已暂存附件行）+ `Skin.SendOrb`；页面保留：布局 Row、12dp
   间距、＋ 附件按钮、队列条、键盘发送路径（`handleComposerKeys` 不动）。
3. **防回退测试同步**：`MaterialImportGuardTest.skinContractHasExactlyElevenComponents`
   改名 `skinContractHasExactlyTwelveComponents`，断言 11 → 12，KDoc/错误消息同步；新增
   组件再次走 RFC 时由下一次 RFC 改这条。
4. **主仓同步（实施后置任务）**：本 RFC 先落客户端仓 `docs/rfc/`（主仓 docs/ 不在客户端
   仓）；批准后需同步到主仓：① `docs/rfc/RFC-0011-inputbar-split.md` 原文；②
   `docs/requirements.md`（AMEND-22 §8.22.4 的 11 组件清单追加修订，注明破坏性升版）；
   ③ `docs/status.md` 台账行。
5. **观测面不变**：InputMode、SkinColors、SkinShapes、SkinMotion、SkinGlass 本 RFC 全部
   不动（`SkinOrb` 是新增 data class，不是修改）。

## §7 未决问题

1. **InputBar 是否增加可选 `leading` 槽位**：v2 §1 把「左侧附件图标」列在框内清单里，但
   冻结签名无此槽位。本 RFC 不加（页面暂把 ＋ 放框外左侧）；后续若要回收到框内，按冻结
   规则以「追加可选参数 `leading: (@Composable () -> Unit)? = null`」落地，无需再走 RFC。
2. **真背景模糊何时进 InputBar**：当前面板是 tint 玻璃退化形（`surfaceGlass` + 发丝线 +
   阴影）。shell 的 `hazeSource` 接线完成后，真模糊应由皮肤在 InputBar 内部接
   `skin/glass/GlassSurface`（页面不需要知道）；由 U2/U3 批次决定时机。
3. **空球点击的提示语义**：§2 说空态「仍可点（触发提示）」，但提示形态（聚焦输入框 /
   toast / no-op）是页面策略，皮肤不可见。契约现状是「空态必须仍触发 onClick」，提示由
   `onClick` 的页面实现决定 —— 是否需要把「提示」语义写进 KDoc 验收，待审查定。
4. **桌面 24dp gutter 的归属**：目前由页面断点计算（16/24）；是否升格为 token（避免下一个
   皮肤自己养一份 16dp 常量）—— 与 §7.2 一并留给 U2/U3。

## §8 验收

| # | 验收项 | 判据 |
|---|---|---|
| A1 | 防回退断言 12 组件 | `MaterialImportGuardTest.skinContractHasExactlyTwelveComponents` 绿：Skin.kt 中 `@Composable fun` 恰好 12 |
| A2 | 三皮肤同步 | material / miuix 各有 SendOrb 实现且 InputBar 内无发送控件；glass 占位（MaterialSkin skinId="glass"）继承 SendOrb；`compileKotlinDesktop` 全仓编译通过 |
| A3 | 页面改回 Skin | `chat/ChatThread.kt` 无自拼输入面板、无自绘发送球；composer 仅由 `Skin.InputBar` + `Skin.SendOrb` 组成（代码可见） |
| A4 | 球在框外 | 480×900 截图：球是独立圆元素，与输入框间距 12dp、垂直居中，不与框同容器背景 |
| A5 | 框内无发送 | 同上截图：输入面板内只有文本域（+ 附件槽/麦克风位），无发送/停止控件 |
| A6 | armed 辉光 | 有内容（草稿或附件）时球 = accent 实心 + 向外 12dp 辉光，图标反白 |
| A7 | 流式停止态 | 流式时同一球位变停止（图标与描边染 danger，球不变红）—— `--chat live` 夹具自动流式，截图可驱动（material-stop.png） |
| A8 | 全量测试不缩水 | 三端编译（desktop / android / wasmJs）+ `desktopTest --rerun` 全绿，测试总数 ≥ 102（本批不删任何用例） |
