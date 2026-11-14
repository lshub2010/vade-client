# U1-b 会话详情 · RESULT（结构基线）

分支 `tmp/u1-b`。本棒交付的是**结构基线**：`chat/` 包（模型 / 状态 / 线程 / Markdown）、会话详情页接真线程、C-09 组件的四个可选槽位、以及真实窗口截图。

> **视觉 v2（悬浮输入框 / 框外发送球 / 纯图标 / 内联审批 / 高斯背景模糊）未落地，待统一重构。**

## 实现点

| 文件 | 内容 |
|---|---|
| `chat/Models.kt` (343 行) | `ContentPart`（Text / Code / Image / File）与 `textProjection()`；`ChatMessage` / `ChatRole` / `MessageState`；`ToolCall` / `ToolResult` / `ToolRunState`；`ChatItem`（Message / Tool）；`formatBytes` / `formatDuration`；`toView()` 把 RFC 字段映射到冻结的 `MessageView` / `ToolCallView`；`toolCardState()` 实现 D94 折叠策略（成功折叠、失败展开、`risk=high` 常驻展开） |
| `chat/ChatState.kt` (379 行) | 每会话一份、随页面销毁（D98 派生状态）：`draft` / `staged` / `queue` / `preview` / `lastNotice`；`inputMode`（Idle / Composing / Streaming）；`submit()` / `stop()` / `tick()` / 队列增删与取消；`tickUploads()` 推进假上传进度；`ChatPreset`（Live / ExpandedTools / Queue / Composer / Preview / Interrupted）只改派生状态，不动 RPC 形状 |
| `chat/ChatThread.kt` (508 行) | 消息列（助手无气泡全宽 ≤760dp、用户右对齐气泡、系统居中弱化）；工具卡行；附件行；`Composer`（多行、`+ 附件`、发送/停止）；全屏图片预览（页面级 overlay，不是平台 Dialog）；`handleComposerKeys()` 是 `internal` 纯函数，供测试直接驱动真处理器 |
| `chat/Markdown.kt` (266 行) | 无依赖的行内/块级解析：粗体、斜体、行内代码、链接、围栏代码（保留语言与原文）、列表、标题；未闭合标记按字面处理，病态输入不抛异常 |
| `chat/MarkdownView.kt` (204 行) | `MarkdownBlocks` / `MarkdownText` 渲染；`StatusPill` + `Tone`；代码块带语言标签 |
| `chat/ChatFixtures.kt` (243 行) | 假数据（6 条会话线程 + 流式脚本 + 待发附件），**未连 core** |
| `destinations/SessionDetailScreen.kt` | 从 U1-a 的 placeholder 换成真线程：`preset` / `animate`（截图与测试用）、`showTitle`（`<600dp` 推入详情时顶栏已带标题，避免 80dp 内重复） |
| `skin/Skin.kt` | **四个可选槽位 + 令牌**，均为追加式，缺省行为逐字节不变 |
| `skin/material/MaterialParts.kt` | 组件按视觉规范重画：助手无气泡、用户气泡 lg + 右下 6dp 尾、工具卡左侧 3dp 状态条、附件 28×28 缩略图、输入框聚焦 2dp 描边（不是发光）、主按钮 pill |
| `skin/material/MaterialSkin.kt` | 亮色令牌换成视觉规范值（accent `#5B5BD6`，**无 `#6750A4`**）；`canvas` / `hairline` / `success` / `warning`；圆角 8/12/16/20/28 |
| `shell/AppShell.kt` | 根底色改 `canvas`；发丝线统一走 `hairline`；详情页接 `chatPreset`，推入详情传 `showTitle = false` |
| `shell/UiState.kt` | `initialSessionId` / `chatPreset`（深链与截图造态） |
| `App.kt` / `desktopMain/Main.kt` | `--open=<id>` 与 `--chat=live\|tools\|queue\|compose\|preview\|stopped` 启动参数，走与点击行完全相同的代码路径 |
| `tools/capture-window.ps1` | 新增 `-Open` / `-Chat` |
| `desktopTest/MaterialImportGuardTest.kt` | 扫描目录加 `chat`（新包必须在守卫内） |

**组件纪律**：仍只用 C-09 已冻结的 11 个组件，未新增第 12 个；缺能力时给冻结组件加**可选**参数（C-09 冻结规则允许「只加可选参数」）。

## 四个可选槽位（C-09 冻结后追加，全部有缺省值）

| 组件 | 槽位 | 用途 |
|---|---|---|
| `MessageBubble` | `body: (@Composable () -> Unit)? = null` | 页面传入自己的 Markdown 渲染器，皮肤不必懂 Markdown |
| `ToolCallCard` | `meta: (@Composable () -> Unit)? = null` | 耗时 / 批次 / 错误码行（冻结的 `ToolCallView` 无此字段）；只在展开时绘制 |
| `InputBar` | `sendEnabled: Boolean = enabled`、`maxLines: Int = 6` | 禁用态由皮肤画（muted 30% 填充、无描边），页面不再用一句 hint 顶替；六行后内部滚动 |
| `AttachmentChip` | `leading: (@Composable () -> Unit)? = null` | 28×28 缩略图；页面拥有位图，chip 拥有边框；缺省回落到 mime 字母占位 |

附带新增令牌（同样有回落）：`SkinColors.canvas/hairline/success/warning`、`SkinShapes.xs/xl`。

## 验证（真实输出）

```
gradle :composeApp:desktopTest --rerun    BUILD SUCCESSFUL in 1m 11s
  ChatFlowTest[desktop]            23 tests  0 failed
  MarkdownTest[desktop]            13 tests  0 failed
  MaterialImportGuardTest[desktop]  2 tests  0 failed
  SearchFlowTest[desktop]          19 tests  0 failed
  TOTAL                            57 tests  0 failed  0 errors  0 skipped
  (证据：composeApp/build/test-results/desktopTest/TEST-*.xml，timestamp 2026-11-14T03:51:5xZ)

gradle :composeApp:assembleDebug          BUILD SUCCESSFUL in 1m 40s   (38 tasks)
```

`--rerun` 是真执行，不是 `UP-TO-DATE`；XML 时间戳为证。

- **APK**：`composeApp/build/outputs/apk/debug/composeApp-debug.apk`
  **12,040,838 B**，sha256 `84b252d0c55bd09f1f4a9bcb1f54566dc3394f1d3e92b641e9dbe552f8aea562`
  （120 个 zip 条目，含 `classes.dex` 与 `AndroidManifest.xml`）
- **测试覆盖**：流式（中途开始 / 停止保留已生成文本并报字数 / 中断后到达的增量被丢弃 / 走完转 Completed）、队列（流式中发送入队并编号 / 取消第 3 条后其余顺移 / 中断后自动起下一条 / 徽标计数不陈旧）、发送（空文本不可发 / 空闲发送先追用户再追加流式助手）、工具卡（D94 策略 / meta 缺字段丢行而不是打横线 / RFC 字段映射到冻结组件 / 同 `batch_id` 计数）、附件（文案必须说「原图未保留」/ 进度由 tick 驱动而非硬编码 / 单轮上限）、键盘（⌘Enter 发送、裸 Enter 换行、空输入时 ⌘Enter 被消费但不发）、Markdown 13 条（含未闭合粗体/围栏、病态输入不抛）

## 截图（`shots/u1b/`，逐张开图核过，全部由当前代码重截）

| 文件 | 尺寸 | 字节 | 核对结论 |
|---|---|---|---|
| `phone-live.png` | 480×900 | 36,633 | 手机推入详情：顶栏「← / RFC-0004…」，`等待审批 · risk=high` 工具卡带左侧琥珀状态条与「审批卡永远展开 (D94)」，失败工具卡红色状态条 + `EXIT_NONZERO` meta 行，代码块带 `kotlin` 语言标签，底部「+ 附件 / 正在生成 · 点 ■ 停止」+ 输入框 + 红色停止钮 |
| `phone-compose.png` | 480×900 | 43,037 | 手机多行输入：两枚附件 chip（`IMG_0042.jpg` / `spec.pdf`，28×28 缩略图 + 移除 ×）、六行草稿、`流式中` 药丸、用户右对齐气泡带右下 6dp 尾 |
| `desktop-live.png` | 1280×800 | 63,477 | 桌面双栏：左侧会话列表（置顶/今天/昨天/7 天内分组 + 未读点）、右侧详情；顶栏副标题「会话详情 · 消息流 / 工具卡为假数据」 |
| `desktop-compose.png` | 1280×800 | 71,430 | 桌面多行输入 + 附件 chip + 六行草稿 + 停止钮；右侧面板入口仍在 |

四张都是**真实窗口截图**（PrintWindow + `PW_RENDERFULLCONTENT`），可见原生标题栏「Vade · UI 骨架」与最小化/最大化/关闭按钮；`PrintWindow=True`、`center` 非透明。无 `probe*.png` 残留，无 `#6750A4` 及其 tonal 派生。

## 需要说明的三点（诚实记录）

1. **compose 两张里线程末行在输入区上沿被拦腰裁切**（`细节见 RFC-0004 §6.1,` 只剩上半截）。这是滚动容器在底部边界的正常裁切，不是层叠错误 —— 输入区是不透明白底、有 1px 发丝线分隔，文字没有透到输入框上。静态帧 `animate = false` 故意不把读者钉到底部，所以停在这个位置。视觉 v2 重构输入区时会一并处理，本期不改。
2. **compose 两张的附件 chip 显示「上传中 0%」**：预设帧 `animate = false` 同时冻结了流式与上传进度，所以定格在起点，这是刻意的确定性行为，不是进度坏了。Live 模式下 `tickUploads()` 每 160ms 推进一步（`stagedAttachmentProgressIsDrivenByTicksNotHardCoded` 覆盖）。Live 截图里线程走完后 chip 不再出现，故没有 100% 的对照图。
3. **截图曾早于最终源码**：接手时四张图的时间戳（最晚 11:30:29）早于 `ChatThread.kt`(11:31) / `ChatState.kt`(11:32)，即最后一次改动没进图。已用当前代码 `desktopMainClasses` 重编后**全部重截**（12:00），现图与提交源码一致。

## 未做 / 已知

- **视觉 v2 未落地**（悬浮输入框、框外发送球、纯图标、内联审批、高斯背景模糊）—— 用户已定后续统一重构，不在本棒。
- **未连 core**：线程、工具卡、附件全是假数据；`ui/submit`、`turn.queued`、`approval/decide` 的接线属后续批次。
- **附件不上传真字节**：没有文件选择器、没有压缩，进度是 `tickUploads()` 推进的假进度（但由状态驱动，不是硬编码字符串）。
- **Android / Wasm 运行时与截图未验证**（只验构建与 APK 产物），属 U1-g。
- `miuix` / `glass` 两个皮肤仍是占位，新令牌与槽位只有 material 实现落地。
