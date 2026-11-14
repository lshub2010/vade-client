# w2-c09-inputbar · RESULT — RFC-0011：InputBar 拆分为输入区 + SendOrb

- 分支 / worktree：`tmp/w2-c09-inputbar` @ `D:/HermesWorkspace/Vade-client-wt/w2-c09-inputbar`（基线 `main` @ `1d95a76`）
- 代码 commit：**`d667442`**（先 commit 后写报告；未 push）；报告 + 截图为随后第二个 commit
- 任务：C-09 冻结的 `Skin.InputBar` 自带发送职责，与视觉规范 v2 §1/§2（发送是框外 48dp 独立圆球）冲突，
  三个槽位（w1-material / w1-miuix / w1-chat）各自绕路。本棒写追加式 RFC 并落地拆分。

## 交付清单

| 项 | 结果 |
|---|---|
| RFC | `docs/rfc/RFC-0011-inputbar-split.md`（status: **proposed**，§1–§8 齐全） |
| C-09 组件数 | **11 → 12**（组件 12 = `Skin.SendOrb`） |
| 三皮肤同步 | material / miuix 各自实现 SendOrb、InputBar 内发送控件全删；glass 占位 = `MaterialSkin(skinId="glass")`（`Skins.kt` 占位注册未动）自动继承 |
| ChatThread 改回 Skin | ✅ 页面自拼 composer 与自绘 `SendBall` 已删除，composer 仅由 `Skin.InputBar` + `Skin.SendOrb` 组成 |
| 测试 | `desktopTest --rerun`：**102 tests, 0 failures, 0 errors, 0 skipped**（8 个 suite，XML 在 `composeApp/build/test-results/desktopTest/`）；防回退测试改名 `skinContractHasExactlyTwelveComponents`（恰 12）并跑绿 |
| 三端构建 | `compileKotlinDesktop` / `compileDebugKotlinAndroid`（含 ANDROID_HOME）/ `compileKotlinWasmJs` 全绿；wasm 仅 MotionBlur.wasmJs.kt 的既有 deprecation 警告 |
| 截图 | 4 张 480×900（实出 466×893，capture-window.ps1 的 DWM 裁切惯例）→ `shots/w2-c09-inputbar/`，逐张开图自查见下 |

## 改动文件（`git diff --name-only 1d95a76..d667442`）

- `docs/rfc/RFC-0011-inputbar-split.md`（新增）
- `composeApp/src/commonMain/kotlin/vade/ui/skin/Skin.kt` — InputBar 删 `onSend/onStop/sendEnabled`（破坏性）；
  新增组件 12 `SendOrb(mode, enabled, onClick, modifier)`；新增 `SkinOrb` token（48dp / 12dp glow / 0.92 / 180ms）
  与 `Skin.orb` 默认实现访问器（与 motion/glass 同型）
- `…/skin/material/MaterialParts.kt` — `MaterialInputBar` 删三个发送参数与内置 16dp 浮层边距（RFC D5：边距归页面）；
  新增 `MaterialSendOrb`（移植页面层球的 v2 观感：`drawBehind` 径向辉光 + **Canvas 矢量** ↑/■ 自绘 —— 皮肤层
  不 import chat/ 的图标包；a11y 用「发送消息 / 停止生成」避开 MaterialSkinV2Test 的 `"发送"` 文字钮守卫）
- `…/skin/material/MaterialSkin.kt` — override 签名同步 + `SendOrb` 转发
- `…/skin/miuix/MiuixParts.kt` — `MiuixInputBar` 删发送控件与 `composerMargin` 内边距；`SendBall` 原样搬入
  `MiuixSendOrb`（halo 辉光、↑/■ 字形、按压缩放保留；驱动信号 `(streaming, sendEnabled)` → `mode` 三态）
- `…/skin/miuix/MiuixSkin.kt` — override 签名同步 + `SendOrb` 转发；`MiuixShowcase.kt` 调用点删 `onSend/onStop`
  并把 `SendOrb` 拼进演示行（InputBar 无 modifier 参数，weight 落在包裹 Box 上）
- `…/skin/miuix/MiuixTokens.kt` — 注释 eleven→twelve（token 值未动；`sendBallSize/sendBallGap/composerMargin`
  保留定义，页面 12dp 间距是 §2 页面布局职责）
- `…/chat/ChatThread.kt` — `ComposerOverlay` 改为 `Row { ＋ | Box(weight){ skin.InputBar } | 12dp | skin.SendOrb }`；
  已暂存附件移入 `attachments` 槽（新私有 `StagedAttachments`）；自绘 `SendBall`、`sendHint` 占位、9 个失效 import 删除；
  `glassFallback` 保留（ImagePreviewSheet 仍在用）
- `composeApp/src/desktopTest/kotlin/vade/ui/MaterialImportGuardTest.kt` — 恰好 11 → 恰好 12

## RFC 要点（详文见 RFC 本体）

- §2 动机记录三槽位撞车：material 参数成死参 / miuix 球锁死组件内 / chat 页面整体绕过组件 11。
- §4 方案：D4 三态映射 `Idle=空玻璃球 / Composing=armed accent+辉光 / Streaming=停止`（页面已有
  `ChatState.inputMode` 推导，不新增布尔参数）；D5 浮层边距归页面（组件内边距叠加会把 §1/§2 数字全部失真）。
- §5 契约变更：C-09 11→12；InputBar 删参 = **破坏性变更，升版本**（冻结「只准加可选参数」按 RFC 流程豁免）。
- §7 未决问题 4 条：InputBar `leading` 槽位、真背景模糊进 InputBar 的时机、空球点击提示语义、桌面 24dp gutter 归属。

## ⚠️ 需同步到主仓（本仓 docs/ 不是主仓 docs/）

RFC 先落客户端仓 `docs/rfc/`；批准后需同步主仓三处：
① `docs/rfc/RFC-0011-inputbar-split.md` 原文；② `docs/requirements.md`（AMEND-22 §8.22.4 的 11 组件清单追加修订，
注明破坏性升版）；③ `docs/status.md` 台账行。

## 截图（480×900，逐张开图自查）

| 文件 | 皮肤 / 状态 | 自查结果 |
|---|---|---|
| `material-armed.png` | material / `-Chat compose`（草稿 + 2 附件） | ✅ 球在框外、12dp 间距、垂直居中；accent 实心 + 柔光辉光 + 反白箭头；框内只有附件 chips + 文本域，无发送控件；无 Material 紫 |
| `material-idle.png` | material / `-Chat stopped`（空草稿、无流式） | ✅ 球 = 玻璃底 + 1px hairline + muted 箭头，无辉光无红；框内无发送（放大核过） |
| `material-stop.png` | material / `-Chat live`（夹具自动流式） | ✅ 同一球位变停止：玻璃球（**不整球变红**）+ 1.5dp danger 描边 + 红色 ■；框内无停止钮 |
| `miuix-armed.png` | miuix / `-Chat compose` | ✅ miuix 观感：accent 球 + halo 辉光 + 浅色 ↑ 在框外；框内无发送；附件 chips 在框上方（miuix 原有结构，玻璃只在文本域盒子上） |

> 过程记录：第一次 idle 用 `-Chat live` 重拍前拍到的是**流式 stop 态**——放大确认渲染正确（玻璃球 + 红 ring + 红 ■，
> 非实心红球），恰好证明 A7；夹具会话默认自带一个进行中的流式回合（`runningId = last.id`），idle 改用 `-Chat stopped`。

## 验收对照（RFC §8 A1–A8）

- **A1** ✅ `skinContractHasExactlyTwelveComponents` 绿（XML：`failures=0`，testcase 在列）
- **A2** ✅ material/miuix SendOrb 落地、InputBar 无发送控件；glass 占位继承；`compileKotlinDesktop` 全仓绿
- **A3** ✅ ChatThread 无自拼面板/自绘球（`grep SendBall` = 0；composer 仅 skin.InputBar + skin.SendOrb）
- **A4** ✅ 球在框外（三张 armed/idle 图均见独立球 + 12dp 间距）
- **A5** ✅ 框内无发送（四张图逐张核）
- **A6** ✅ armed = accent 实心 + 12dp 辉光（material-armed / miuix-armed）
- **A7** ✅ 流式停止态有真截图（material-stop.png，放大核过「不整球变红」）
- **A8** ✅ 三端编译 + 102 tests 0 failed（基线 102 不缩水）

## 遗留 / 给父代理的交接

1. **＋ 附件按钮在框外左侧**：v2 §1 把「左侧附件图标」列在框内，但 InputBar 无 leading 槽位 —— 按 RFC §7.1 暂放框外，
   后续若回收进框内走「追加可选参数 `leading`」即可（无需再走 RFC）。
2. **`ChatState.sendHint` 无调用方**：⌘Enter 提示文案随自拼 composer 一起下线（皮肤占位固定「输入消息…」）。
   `ChatState.kt` 不在本棒范围未动，属性保留；如确认不再需要可删。
3. **与并行棒的边界**：本棒未触碰 `destinations/`、`shell/`（AppShell / NavRail / Destinations 一行未改）；
   本分支独立编译绿。Tabler 图标棒改 `Destinations.kt`（glyph→icon）后若 NavRail 编译不过，需父代理合并时修 —— 本棒不处理。
4. **真背景模糊未进 InputBar**：仍是 tint 玻璃退化形（RFC §7.2），GlassSurface/hazeSource 接线留给 U2/U3。
5. `Skin.kt` 头注释与 `UiText.kt` / `FakeSessions.kt`（「皮肤组件 11 个冻结」会话标题）的旧「11」字样仅在
   注释/夹具文案里，功能无涉；前者已改，后两者不在本棒范围。
