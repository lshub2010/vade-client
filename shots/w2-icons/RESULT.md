# w2-icons · Tabler Icons 接入（按父代理收窄范围）

分支 `tmp/w2-icons`（main @ `1d95a76`）。**范围以父代理中途指令为准**：只做依赖 + `vade/ui/icon/` 包 + Destinations 汉字图标 + 防回退测试 + README 版权 + IconGallery 截图；`shell/AppShell.kt`、`shell/NavRail.kt`、`skin/material/MaterialParts.kt`、`skin/miuix/MiuixParts.kt` **未动**（并行棒在改）。

## 1. 依赖（composeApp/build.gradle.kts，仅 commonMain 依赖块 +12 行）

任务书原定一行 `icons-tabler-cmp:2.2.1`，**jar 实测不够**（`unzip -l`）：该 artifact 只是 2.4KB 的 `Tabler` 命名空间 stub（`object Tabler { object Outline; object Filled }`），无任何图标类。真实图标在两个兄弟 artifact 里（Maven Central 目录枚举 + jar 内容核实，三端变体 jvm/wasmJs/android 齐全）：

```kotlin
implementation("com.composables:icons-tabler-cmp:2.2.1")        // Tabler 命名空间 + MIT 许可伞
implementation("com.composables:icons-tabler-outline-cmp:2.2.1") // Tabler.Outline.*（4985 个）
implementation("com.composables:icons-tabler-filled-cmp:2.2.1")  // Tabler.Filled.*（唯一的 fill 用它）
```

Kotlin/CMP/AGP 三行未动。图标名全部对实际 jar 用 `javap` 核过 getter（不是凭记忆）。

## 2. 图标清单（vade/ui/icon/VadeIcons.kt，20 个语义名 → 实际 Tabler 名）

| 语义名 | Tabler 名 | 语义名 | Tabler 名 |
|---|---|---|---|
| Send | `Outline.ArrowUp` | Compose | `Outline.Pencil` |
| **Stop** | **`Filled.PlayerStop`（唯一 fill）** | Check | `Outline.Check` |
| Attach | `Outline.Paperclip` | Trash | `Outline.Trash` |
| More | `Outline.Dots` | Refresh | `Outline.Refresh` |
| Back | `Outline.ChevronLeft` | Chat | `Outline.MessageCircle` |
| Search | `Outline.Search` | Activity | `Outline.Activity` |
| Close | `Outline.X` | Grid | `Outline.LayoutGrid` |
| ChevronDown | `Outline.ChevronDown` | Person | `Outline.User` |
| ChevronRight | `Outline.ChevronRight` | Pin | `Outline.Pin` |
| | | Folder / Palette | `Outline.Folder` / `Outline.Palette` |

任务书写的是 `Tabler.PlayerStopFilled`——**实际是 `Tabler.Filled.PlayerStop`**（filled artifact 无 `PlayerStopFilled` 类）。

- `VadeIcon.kt`：`Image` + `ColorFilter.tint`，不 import material3。**一处有据偏离任务书**：签名默认 `tint = Color.Unspecified` 而非 `LocalContentColor.current`——`LocalContentColor` 只在 material3 里，任务书同时禁止 import material3。约定：调用点显式传 token 色（`skin.colors.onSurface` / `textOnGlass` 等）。
- `IconGallery.kt`：4 列网格预览页，自持色参数（不依赖 Skin），经 `Main.kt --gallery`（`--light` 切亮色）进入。

## 3. 替换点（本棒实际改的）

- `destinations/Destinations.kt`：新增 `val icon: ImageVector`（Sessions→Chat、Activity→Activity、Capabilities→Grid、Me→Person，FR-8.77 序）；`glyph: String` **按父代理指令保留为过渡字段**，带 `@Deprecated("Tabler 图标合入后删除")`，4 个枚举项两字段并存 → `NavRail.kt` / `AppShell.kt` 调用点不改仍编译（实测：`desktopMainClasses` 绿，仅新增 2 条预期 deprecation warning，正好落在那两个文件）。
- `desktopMain/Main.kt`：**additive** `--gallery` / `--light` 分支（沿用 `--showcase`/`--motion-probe` 模式；capture-window.ps1 硬编码 MainKt，需此入口截图）。
- `tools/capture-window.ps1`：**additive** `-Gallery` / `-GalleryLight` 开关透传。

## 4. 防回退测试（desktopTest/IconGuardTest.kt，4 条）

扫描 `commonMain/kotlin/vade/ui` 全部 .kt，**排除 `icon/`、`shell/`、`skin/`**（父代理指令：后两者由并行棒改，合并后统一扫）：

1. `noCharacterIconTextLiterals` — `\bText(` 与 `UiText(` 后跟符号字面量（类含任务书字符集 + `×◂▸⧗`）。正则做了真跑变异验证：5 组正例全中、6 组反例（中文标签/变量/普通文本）全不误报。**显式 allowlist**：`SearchOverlay.kt` 的 `UiText("⌕")` 是当前扫描域内唯一已知残留（search/ 不在任何棒的范围内），父棒统一替换后删该行即恢复全盖。
2. `businessCodeDoesNotImportTablerDirectly` — 扫描域内 `com.composables.icons.tabler` 必须为 0。
3. `destinationsCarryTablerIcons` — `icon: ImageVector` 存在、四入口映射 VadeIcons.Chat/Activity/Grid/Person；`glyph` 若还在必须带 @Deprecated（父棒删 glyph 后此断言自动 vacuous，无需改测试）。
4. `vadeIconsExposesAtLeast20Vectors` — ≥20 个公开 `ImageVector`。

**测试数：106 = 102 基线 + 4 新增**，`desktopTest --rerun` 全绿（0 fail 0 error，XML 汇总逐文件核对）。

## 5. 三端构建（原始输出，本 worktree 实跑）

| 任务 | 结果 |
|---|---|
| `desktopMainClasses` | BUILD SUCCESSFUL（1m46s；仅 2 条预期 deprecation warning） |
| `desktopTest --rerun` | BUILD SUCCESSFUL；106 tests / 0 fail / 0 err |
| `compileKotlinWasmJs` | BUILD SUCCESSFUL（与 assembleDebug 合跑，5m10s） |
| `assembleDebug` | BUILD SUCCESSFUL；`composeApp/build/outputs/apk/debug/composeApp-debug.apk` 21,141,380 B |

构建坑（供父代理记档）：本 worktree 的 Gradle 客户端实际落到了默认 `~/.gradle` 缓存 + 复用旧 daemon（未带 ANDROID_HOME），首次 `assembleDebug` 报 "SDK location not found"。修法：写 `local.properties`（`sdk.dir=D:/Hermes/tools/android-sdk`，.gitignore 明文允许的机制，**未入库**）。

## 6. 截图（逐张开图核验）

- `shots/w2-icons/gallery-dark.png`（886×753，canvas #0E0E10 实测中心像素一致）：20 格全渲染、形状逐格比对正确（Send=↑箭头、Stop=实心方块、Attach=回形针、More=三点、Back/Search/Close/ChevronDown、ChevronRight/Compose/Check/Trash、Refresh/Chat=气泡/Activity=心电折线/Grid=四宫格、Person/Pin=图钉/Folder/Palette），2dp 细描边、**Stop 唯一实心**，无空格、无豆腐块、无字符当图标。
- `shots/w2-icons/gallery-light.png`（886×753，canvas #F7F7F8）：同上全过。

（收窄范围不含手机导航栏/桌面导航截图——那些调用点归后续棒；本棒 2 张均为 IconGallery。）

## 7. README 版权确认

`README.md` 新增 `## 第三方资产许可 → ### Tabler Icons`：MIT，**Copyright (c) 2018-2026 Tabler contributors**，注明打包 artifact（com.composables icons-tabler-cmp/-outline-cmp/-filled-cmp 2.2.1，Composable Horizons composeicons，MIT）与「shell/skin 外不得直接 import」约束。原「两条防回退测试」节改为「防回退测试」并补第 3 条（IconGuardTest）。

## 8. 给父代理的合并清单

- [ ] 并行棒合入后：删 `Destinations.glyph` 与 `shell/` 2 处调用点（deprecation warning 会指路），测试 3 无需改。
- [ ] 把 `IconGuardTest.excludedDirs` 收窄回 `setOf("icon")`、删 allowlist 的 `SearchOverlay.kt "⌕"` 行 → 全量扫描恢复（search/ 的 ⌕ 也需一并换 `VadeIcons.Search`）。
- [ ] 本棒改动文件全集：`README.md`、`composeApp/build.gradle.kts`、`Destinations.kt`、`desktopMain/Main.kt`、`tools/capture-window.ps1`、新增 `icon/{VadeIcons,VadeIcon,IconGallery}.kt`、新增 `desktopTest/IconGuardTest.kt`、`shots/w2-icons/*`。`git diff --name-only` 核对。
