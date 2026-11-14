# c-dark · material 皮肤暗色支持修复

分支 `tmp/c-dark`（基于 main @ f189de2）· 代码 commit **bca4664** · 2026-11-14

## 缺陷与修法

`MaterialSkin` 原来把 `isDark` 硬编码 `false`（整块颜色对象只有一套亮色值），`--dark` 传参
毫无效果——截图脚本产出 light.png / dark.png 字节相同（36984 vs 36984，逐像素 md5 一致）。

修法照 `MiuixSkinEntry` / `MiuixTokens` 的既有机制抄，不发明新机制：

1. **`MaterialTokens.kt`**（新文件）：light / dark 两套 `SkinColors`，`colors(dark)` 二选一。
2. **`MaterialSkin(dark: Boolean = false)`** 构造参数；`colors` / `glass` / accentSoft alpha /
   阴影 blur 全部随 dark 翻转；`isDark` 正确上报（`GlassSurface` 靠它自动取暗色 tint）。
3. **`MaterialSkinEntry`**（新）：`var dark` + `register()`，与 `MiuixSkinEntry` 同款注册缝。
4. **`Skins.kt`**：material 注册改走 `MaterialSkinEntry.register()`；**`Main.kt`**：
   `--dark` / `VADE_DARK=1` 现在同时设 `MaterialSkinEntry.dark` 与 `MiuixSkinEntry.dark`
   ——对所有皮肤生效。

## 暗色 token 表（已同步进 docs/visual-language.md §2）

| token | light | dark | 来源 |
|---|---|---|---|
| `canvas` | `#F7F7F8` | `#0E0E10` | §2 暗色表原文 |
| `surface` | `#FFFFFF` | `#161618` | §2 暗色表原文 |
| `surfaceVariant`（sunken） | `#F0F0F2` | `#0E0E10` | 推导：暗色下比 surface 更深（Miuix 同法） |
| `hairline` / `outline` | `#E6E6E9` | `#2A2A2E` | §2 暗色表原文 |
| `onSurface`（text） | `#17171A` | `#EDEDF0` | §2 暗色表原文 |
| `onSurfaceVariant`（muted） | `#6B6B73` | `#9A9AA3` | §2 暗色表原文 |
| `primary`（accent） | `#5B5BD6` | `#8B8BF0` | §2 暗色表原文（暗色提亮，同一靛蓝色相，非 Material 紫） |
| `onPrimary` | `#FFFFFF` | `#0E0E10` | 推导：白字在 `#8B8BF0` 上 2.9:1 不达标 |
| `error`（danger） | `#D93A3A` | `#F16A6A` | §2 暗色表原文 |
| `onError` | `#FFFFFF` | `#1A0503` | 推导：白字在 `#F16A6A` 上 2.97:1 不达标 |
| `success` | `#2F7D5A` | `#4FD1A0` | 推导：保色相提亮（MiuixTokens 同一推导） |
| `warning` | `#B26A00` | `#E8A33D` | 推导：保色相提亮 |
| `surfaceGlass`（退化底） | `#FFFFFF`@84% | `#1C1C1F`@78% | §2 surfaceRaised 做玻璃退化读色；`#0E0E10`@34% 贴黑画布不可见 |
| `glass.tintDark`（真模糊染色） | — | `#0E0E10`@34% | §5 暗染原文（light 保持默认 `#FFFFFF`@26%） |
| `shadowSoft` / `shadowFloat` | 黑@30% / 50% | 黑@54% / 90% | §8：alpha ×1.8 |
| 阴影 blur | 2dp / 16dp | 2.4dp / 19.2dp | §8：blur ×1.2 |
| accentSoft（选中底 alpha） | 8% | 14% | §2 两张表各写的值 |
| `glowAccent` | `#5B5BD6` | `#8B8BF0` | 跟 accent 走 |
| `textOnGlass` | `#17171A` | `#EDEDF0` | 跟 onSurface 走 |

## 验证（原始数字，不写“已通过”）

- `gradle :composeApp:desktopTest --rerun`（真跑，非 UP-TO-DATE）：
  `TEST-*.xml` 求和 = **tests=164 failures=0 skipped=1**。
  其中本批新增 `MaterialSkinDarkTest`：**tests=10 failures=0 skipped=0**
  （timestamp 2026-11-14T10:33:17Z；基线 154 + 10 = 164）。
- `gradle :composeApp:desktopMainClasses`：BUILD SUCCESSFUL（独立跑一次）。
- WCAG（测试内纯 Kotlin 手算相对亮度）：暗色 主文本/canvas 16.5:1、次要 6.9:1、
  accent 作文字 6.5:1、error 作文字 6.4:1、onPrimary/accent 6.5:1 —— 全部 ≥4.5:1；
  亮色基线同测（主文本 16.7:1、次要 4.9:1）。

## 防回退断言（MaterialSkinDarkTest，10 条）

1. `lightAndDarkPalettesDiffer` —— light/dark 的 canvas、surface、hairline、primary 逐对
   `assertNotEquals`，且 `light.isDark == false && dark.isDark == true`。
2. `darkCanvasIsTrulyDarkNotANearWhiteSwap` —— 暗 canvas 相对亮度 < 0.10 且 < 亮 canvas；
   亮 canvas > 0.70 保持不动。
3. `darkTextContrastMeetsWcagAA` —— 主/次文本对 canvas 与 surface、error 文字、
   textOnGlass 对玻璃退化底（blend 合成后算）、onPrimary/onError 压实色，全部 ≥4.5:1。
4. `lightTextContrastStillMeetsWcagAA` —— §八.1 要求“亮暗两套”，亮色不许被暗色波及。
5. `darkAccentIsBrighterThanLightAccent` —— 暗 accent 相对亮度 > 亮 accent，且值钉 `#8B8BF0`。
6. `darkHairlineIsLighterThanCanvasNotWhiteOnWhite` —— 暗色发丝线比暗画布亮（线看得见）
   且亮度 < 0.5（不是白线）；亮色发丝线比画布暗（既有行为）。
7. `tokenKeySetsMatchEveryColorAuthoredPerTheme` —— 19 个槽位逐槽 `dark != light`，
   漏写暗色 token（静默回落到亮值或 data class 默认值）一律打红。
8. `darkTokensMatchVisualLanguageTable` —— §2 暗色表原文值逐个 `assertEquals` 钉死。
9. `darkGlassAndShadowFollowTheDocDerivation` —— tintDark `#0E0E10`@34%、tintLight
   `#FFFFFF`@26%、阴影 alpha ×1.8（30→54、50→90）、blur ×1.2（2→2.4、16→19.2）、
   退化玻璃 `#1C1C1F`@78%、accentSoft 8%/14%。
10. `entryPlumbsDarkThroughRegistryLikeMiuixSkinEntry` —— `MaterialSkinEntry.dark` 两态下
    `SkinRegistry.get("material")` 产出 `isDark` 相应翻转（即 Main.kt `--dark` 的真实路径）。

## 未做项 / 观察项

- **MaterialParts.kt 内三处 shadow elevation（约 124/481/632 行）未乘 ×1.2**：它们消费的
  阴影**颜色**已随 token 翻转，但 elevation 常量是文件内硬编码，函数签名没有 dark 入参；
  改它们要动 MaterialParts 的内部函数签名。MaterialSkin.kt 自有的两处（Card 2dp /
  BottomBar Floating 16dp）已随动。如需完全对齐 §8，留一个收尾棒把 dark 值传进 Parts。
- **亮色 `error #D93A3A` 对 `#F7F7F8` 画布 4.25:1**（<4.5）：文档 §2 既有值，非本批范围，
  未动；其暗色对应值 6.4:1 达标。若要修亮色 danger 属另一批（会改亮色基线截图）。
- **截图未拍**（按任务书：capture-window.ps1 会 kill 全部 java 进程，由父代统一拍）。
  `--dark` 生效的静态验证 = 上面第 1/10 条断言；像素级验证交给父代截图（light/dark
  字节数应不再相同）。
- `local.properties`（sdk.dir）为 worktree 内 untracked 文件，按约定不入库。

## 文件清单（git diff --name-only bca4664^..bca4664）

- `composeApp/src/commonMain/kotlin/vade/ui/skin/material/MaterialTokens.kt`（新）
- `composeApp/src/commonMain/kotlin/vade/ui/skin/material/MaterialSkin.kt`
- `composeApp/src/commonMain/kotlin/vade/ui/skin/Skins.kt`
- `composeApp/src/desktopMain/kotlin/vade/ui/Main.kt`
- `composeApp/src/desktopTest/kotlin/vade/ui/skin/MaterialSkinDarkTest.kt`（新）
- `docs/visual-language.md`（仅 §2 内追加 6 行 token + 1 行实测注记）
