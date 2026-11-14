# U2 · miuix 皮肤 · RESULT

分支 `tmp/u2-miuix`。C-09 的 11 个组件全部有了 miuix 实现（Miuix 0.9.4 / `top.yukonga.miuix.kmp`），
注册进 `SkinRegistry`，桌面端可用 `--skin=miuix` 启动真实 `App()`。

> 代码 commit：**`7c39602`**（本 RESULT 为其后续独立 docs commit，未 push）。

> **视觉 v2（悬浮输入区/框外发送球/纯图标/内联审批/高斯背景模糊）未落地，待统一重构。**
> 本期只做「miuix 组件替换 + token 对齐规范」，形态仍是 v1 的底部输入条 / 居中偏下审批 sheet。

## 实现点

| 文件 | 行数 | 内容 |
|---|---|---|
| `skin/miuix/MiuixTokens.kt` | 260 | 亮/暗两套 `SkinColors`；半径阶梯 12/16/20/pill（8 留 `radiusXs`、28 给 sheet）；字阶 20/15/13 + 11/15 特例；动效 120/160/200ms；`MiuixScope()` 统一套 `MiuixTheme` 并**只施加一次** 1.18× 桌面密度补偿（`MiuixDensityApplied` 守卫嵌套重入） |
| `skin/miuix/MiuixSkin.kt` | 526 | 组件 1–6：Button（Primary 实心 pill、Secondary/Danger 1dp 描边、Text 纯字色）/ TextField / Card / ListItem / Switch / BottomBar（三态） |
| `skin/miuix/MiuixParts.kt` | 613 | 组件 7–11：ApprovalDialog（底部 sheet、参数块 96–240dp 内滚、按钮竖排危险置末）/ MessageBubble（三种角色三种形态）/ AttachmentChip / ToolCallCard（3dp 状态条 success/warn/danger）/ InputBar（38dp 12dp 圆角方形发送键） |
| `skin/miuix/MiuixShowcase.kt` | 232 | 验收用页面：U1-b 聊天页未建，本页用**真实 C-09 组件**拼出消息/工具卡/输入区，供截图 |
| `skin/miuix/MiuixPlatform.{desktop,android,wasmJs}.kt` | 8/4/4 | `expect/actual` 仅承载「是否桌面」一个开关，决定要不要 1.18× 密度补偿 |
| `composeApp/build.gradle.kts` | +5 | 仅 `implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.4")`，无 AGP/Gradle/compileSdk 改动 |
| `skin/Skins.kt` | +3/−1 | 注册 seam，见下「越界」 |
| `desktopMain/Main.kt` | +27/−8 | `--skin=` / `VADE_SKIN` / `--dark` / `VADE_DARK` / `--showcase` / `--approval`，见下「越界」 |
| `tools/capture-window.ps1` | +12 | 同名四个开关透传，无键鼠注入也能截到真实界面 |

**纪律**：`MiuixSkin` 不 import `androidx.compose.material3`；业务页（`destinations/` `shell/` `search/`）不 import
`top.yukonga.miuix`——两条都由 `MaterialImportGuardTest` 强制（本期把 `top.yukonga.miuix` 加进了禁用词表）。
`Skin.kt` 仍是 11 个 `@Composable fun`（第二个测试断言 =11）。

## 验证（真实输出）

```
gradle :composeApp:desktopTest --rerun
BUILD SUCCESSFUL in 8s
11 actionable tasks: 7 executed, 4 up-to-date
```

`build/test-results/desktopTest/`：**21 tests，0 failures，0 errors，0 skipped**
（`MaterialImportGuardTest` 2 + `SearchFlowTest` 19）。`compileKotlinDesktop` 无 `e:`；唯一告警是既有的
`AppShell.kt:103:40 Unnecessary safe call`（U1 遗留，不在本期范围）。

## 截图（`shots/u2-miuix/`，7 张逐张开图核过）

> 任务口径写的是「6 张」；实际交付 **7 张** —— 亮/暗 × 手机/桌面的 showcase 4 张 + 审批态 3 张
> （`phone-light-approval.png` 是第 7 张，未在口径内但一并入库，故总数为 7）。

| 文件 | 尺寸 | 字节 | 核对结论 |
|---|---|---|---|
| `desktop-light-showcase.png` | 1266×793 | 45,001 | 亮色。背景 `#F2F1EF` 暖灰、卡片纯白 + 1dp 发丝线，零阴影；assistant 是**无气泡正文**（与 material 最大差异），user 右对齐白底气泡且右下角切角；工具卡左 3dp 绿/橙状态条（`已完成·已展开` / `等待审批·high`）；底栏四档、活动角标 2 |
| `desktop-dark-showcase.png` | 1266×793 | 44,672 | 暗色。底 `#000000`、卡片 `#1C1C1E` 抬起（非 Material 的 `#121212`）；正文/次要文字对比正常无灰糊；气泡与工具卡同形态；无截断、无错位 |
| `desktop-light-approval.png` | 1266×793 | 52,950 | 亮色审批。底部 sheet 居中、宽上限 560dp（**未拉满 1266px**，`widthIn` 链序已修）；32% 遮罩压暗背后；`risk=high` 药丸；参数块等宽 4 行完整；三键竖排：仅本次允许（实心）/ 本会话都允许（浅底）/ 拒绝（红），危险在末；`剩余 43s · 超时按拒绝处理` |
| `phone-light-showcase.png` | 466×893 | 38,190 | 手机亮色。<600dp 单列、底栏 Fixed；消息/工具卡/附件 chip（`已上传` / `42%` 进度发丝）齐全；输入条贴底、发送键 38dp 圆角方形；无重叠 |
| `phone-dark-showcase.png` | 466×893 | 38,010 | 手机暗色。同上结构，暗色 token 生效；正文在纯黑底上清晰；顶栏标题与「批注/发送」无遮挡（放大复核过，此前怀疑的黑色色块是裁剪伪影） |
| `phone-light-approval.png` | 466×893 | 32,157 | 手机亮色审批（第 7 张）。sheet 贴底升起、28dp 顶角、32% 遮罩；`cwd` 长路径在参数块内折行未溢出；三键竖排、红色拒绝在末 |
| `phone-dark-approval.png` | 466×893 | 31,621 | 手机暗色审批。sheet 从底部升起、圆角 28dp 顶角；参数块 3 行折行完整未溢出；三键竖排、红色拒绝在末；倒计时行可见 |

亮暗两套均为**第二套值**（`isDark` 语义切换），不是给亮色套滤镜。

## 数字：哪些是过期的

| 项 | 值 | 状态 |
|---|---|---|
| APK 字节 | `composeApp-debug.apk` **14,045,006 B**，sha256 `36f98e3f6dfd4b3f650498463001641d4dc6492e01705087c16e26eb3e98c701` | **已过期（重打前值）**。构建于 11:17，之后 `MiuixParts.kt` 11:48 与本期注释修正未并入；确认 dex 里已含 miuix（`classes13.dex` 有 2806 处 `yukonga`、`classes6.dex` 有 142 处 `MiuixSkin`），但**不是最终源码的字节数**，重打前不可引用 |
| wasm 生产包 | baseline `composeApp.wasm` **3,426,830 B（3.27 MiB）** → u2 **4,165,013 B（3.97 MiB）**，**+738,183 B / +0.70 MiB / +21.5%**；`composeApp.js` 两侧均 517 KiB 未变；`skiko.wasm` 8.24 MiB 未变 | **已测量但未复跑**。baseline 取 `_baseline-material`（同 commit `36c405e`、无 miuix 依赖、工作区干净）11:48 的 `optimized/composeApp.wasm`；u2 取本仓 11:28 产物，与 `build-baseline-wasm.log` / `build-u2.log` 里 webpack 打印的 `3.27 MiB` / `3.97 MiB` 一致。**未做**：没有为最终源码重跑 wasm（31 分钟，且 v2 会重来），因此该差值对应 11:28 的树，之后仅注释改动，量级可信但非最终树实测 |

## 两处越界（`Skins.kt` / `Main.kt`）单列理由

1. **`skin/Skins.kt`（+3/−1）** —— 按 U1 注释的原始设想，U2 应当「from their own package without touching this file」。
   实际做不到：`Skins.installBuiltIns()` 是 `App()` 首帧前**唯一**执行点，且是 `private var installed` 的单次入口，
   外部没有注册钩子，所以 `MiuixSkinEntry.register()` 必须在这一行调用。同时删掉 U1 给 `miuix` 留的
   `registerPlaceholder`——不删则 `placeholderIds()` 会继续把 `miuix` 报成「待安装」，而 `SkinRegistry.get` 又优先
   `impls`，两处真相互相矛盾。改动共 1 行调用 + 注释订正，未碰 `SkinRegistry` 本身。
2. **`desktopMain/Main.kt`（+27/−8）** —— 桌面专属入口，加 `--skin=` / `VADE_SKIN` / `--dark` / `VADE_DARK` /
   `--showcase` / `--approval` 六个开关。理由是验收要求「截图必须是无头可复现的真实界面」，而本会话没有键鼠注入能力；
   与 U1-c 加 `--search=` 是同一先例。走的都是 `App()` **既有**参数 `initialSkinId`（U1 已存在），
   **未改任何 C-09 签名**；Android/Wasm 入口不经过此文件，零影响。`--showcase` 只切页面，不进 `App()`、不建 `destinations/`。

## 未做 / 已知

- **视觉 v2 未落地**（见开头横幅）：悬浮输入区、框外发送球、纯图标按钮、内联审批、高斯背景模糊（`haze` 未接）全部未做，待统一重构。
- **Android / Wasm 运行时未验**：只验了构建与桌面截图；两端无实机/浏览器截图，属 U1-g / 后续范围。Wasm 侧 `MiuixPlatform` 只有 4 行 `false`，从未跑起来过。
- **`MiuixSkinEntry.dark` 是全局可变 `var`**：为了让 `SkinRegistry.register` 的 `() -> Skin` 签名（C-09 冻结）保持不变，
  暗色选择挂在单例上，由宿主在首次组合前赋值。多窗口/热重载下会串味，等外观设置页落地时应改成工厂闭包或 `LocalSkin` 派生。
- **README 口径已旧**：`README.md:25`「`skin/SkinRegistry.kt` # material 真实现；miuix/glass 占位」、`:93`「`miuix`/`glass` 仅占位」
  与现状不符。本期按范围只动 `skin/miuix/` 等六个位置，未改 README，留待文档同步单独处理。
- **`MiuixShowcase.kt` 是验收脚手架**，不是业务页：U1-b 聊天页落地后应删除，或改为预览用 `@Preview`。

## 本期顺手修的注释订正（零行为变更）

- `MiuixSkin.kt` 类注释与 `Button` KDoc 仍写着「8/12/16 dp」「每个按钮 8dp 圆角」，与代码已改到的
  12/16/20 + Primary `pill` 矛盾 → 按实际值订正。
- `MiuixParts.kt` 头注释「every corner is 12/16 dp rather than material's 14/22」→ 改为规范阶梯 12/16/20/28 与 material 的 8/14/22。
- `Skins.kt` 注释原称「the placeholder line below is kept」，但该行本期已删 → 订正为真实状态。
