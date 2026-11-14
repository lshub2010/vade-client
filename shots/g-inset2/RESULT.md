# g-inset2 · 修 w-verify P1-1（会话详情输入胶囊被三键导航条覆盖）+ P1-3 内容侧

- 分支 `tmp/g-inset2`，基于 main @ `f2dfad4`
- 代码 commit：**`71b6dbb8475014469b6e5349ef90ca01e6bd3a2c`**（先 commit，本报告后提交）
- 未 push、未合并；未触碰 `shell/**`、`destinations/**`、`skin/**`、`icon/**`、`data/**`、`protocol/**`、`build.gradle.kts`

## 1. 根因（一句话）

`ChatThread.ComposerOverlay` 底部 padding 是固定 `16.dp`，只挂了 `imePadding()`；根 `MainActivity` 已 `statusBarsPadding()+imePadding()` 但没人消费 `navigationBars`，edge-to-edge 下 API 30 三键条（48dp）直接叠画在输入胶囊底缘，把胶囊圆角和输入热区吞掉（w-verify `08`/`09`、`ev-detail-navbar-over-capsule.png`）。

## 2. 改动点（3 个文件）

| 文件 | 改动 |
|---|---|
| `composeApp/src/commonMain/kotlin/vade/ui/component/insets/ComposerInsets.kt`（新增） | `composerNavClearance(imeBottom, navInset) = max(0.dp, navInset − imeBottom)` 纯函数 + 同名 `@Composable` 读 CMP 多平台 `WindowInsets.ime/navigationBars`（与 `BottomBar.kt` 同一读法；**复制而非 import**——`navigationBarBottomInset()` 是 shell 包 internal，且 `shell/**` 不在本批可写范围，重复原因已写进文件头注释） |
| `composeApp/src/commonMain/kotlin/vade/ui/chat/ChatThread.kt` | ① `ComposerOverlay` 底部 padding：`16.dp` → `16.dp + composerNavClearance()`（`onSizeChanged` 挂在 padding 外侧，实测高度随 clearance 1:1 跟涨）；② §1 列表 spacer 公式提成 `composerListSpacer(composerHeight) = composerHeight + 24.dp`（纯函数，供测试钉住） |
| `composeApp/src/desktopTest/kotlin/vade/ui/chat/ComposerInsetsTest.kt`（新增，8 条） | 见 §4 |

键盘态不重复计数：`ime + clearance ≡ max(ime, nav)`（键盘弹出时 IME 盖住导航条、根部 `imePadding()` 已抬升整页 → clearance = 0；键盘收起 → clearance = navInset）。

## 3. 真机验证（192.168.1.180:5555，全程 `-s` 指定，未碰 emulator-5554）

APK 防覆盖（装包前后 `pm path` + pull + md5）：

| 时点 | md5 | 字节 |
|---|---|---|
| 装包前（设备上别人的构建） | `3bc76b9077756ab2c7aab4a4ce0e7cac` | 21,878,888 |
| 装包后 pull 回 | `134ee76588f5b7ab9459f2673b627899` | 21,895,331 |
| 本批 `composeApp-debug.apk` | `134ee76588f5b7ab9459f2673b627899` | 一致 |

两张证据（1080×2400，density 440，1dp = 2.75px）：

- 键盘收起：`shots/g-inset2/04-detail-keyboard-closed.png`（249,938 字节）
- 键盘弹出：`shots/g-inset2/05-detail-keyboard-open.png`（194,521 字节）

辅助帧（过程记录）：`01-after-fix-list.png`（23,441 B，首帧黑屏，环境噪音同 w-verify `03`）、`02-wake-list.png`（178,465 B，横屏唤醒）、`03-portrait-list.png`（253,070 B，转回竖屏后会话列表）。

### 开图结论

- **键盘收起（04 图，放大底部 300px 条带核对）**：胶囊底缘圆角完整，约在 y≈2215±10（= 屏底 2400 − 176px = **64dp = 16dp 基垫 + 48dp 导航条 clearance**，与代码一致）；导航条按钮区（48dp = 132px，y 2268–2400）与 ◁ ○ □ 图标（中心 y≈2333）整体在胶囊下方，**无任何图标压在胶囊上**。对照 w-verify 缺陷图 `ev-detail-navbar-over-capsule.png`（图标直接画在白色胶囊上、圆角被切）已消除。
- **键盘弹出（05 图）**：胶囊（焦点框态）底缘 y≈1430，键盘顶（候选条）y≈1490，间距 ≈60px ≈ 22dp ≈ 16dp 基垫 + 阴影；若导航条被二次计入应为 16+48=64dp ≈ 176px——实际不是，**ime 与 nav 未重复计数**，与 w-verify 健康基线 `14-detail-keyboard.png` 形态一致。

设备状态注：验证前设备留在横屏，已置回竖屏（`user_rotation 0` + `accelerometer_rotation 0`），未重启设备、未卸载任何应用。

## 4. 测试（ComposerInsetsTest，8 条，覆盖要求的 6 类）

| # | 测试 | 钉住什么 |
|---|---|---|
| 1 | `threeButtonNavInsetLiftsTheComposerCapsuleByTheInset` | ①④ 三键：inset 48 → clearance 48，胶囊底垫 16→64dp |
| 2 | `gesturePillInsetLiftsTheComposerCapsuleByTheInset` | ④ 手势：inset 24 → clearance 24，底垫 16→40dp |
| 3 | `keyboardOpenAddsNoClearanceAboveTheKeyboard` | ③ 键盘弹出：clearance=0，底垫保持 16dp |
| 4 | `imePlusClearanceIsAlwaysTheUnionNeverTheSum` | ③⑥ union-not-sum 不变式：8 组 (ime,nav) 下 `ime+clearance == max(ime,nav)`，永不求和 |
| 5 | `zeroInsetsMeansZeroClearanceBottomOnly` | ⑥ 零 inset 零 clearance、bottom-only |
| 6 | `desktopWindowActualsReadZeroAndComposerPaddingStays16dp` | ② 真实（隐藏）ComposeWindow：nav/ime/clearance 全 0 → 底垫恰为今天的 16dp，桌面逐像素不变 |
| 7 | `listSpacerTracksComposerHeightSoTheNewestMessageScrollsClear` | ⑤ §1 spacer = 实测高(含 padding) + 24dp；最小 64dp 胶囊 + clearance 的 spacer > 三键态胶囊栈 120dp |
| 8 | `floatingPillStackExceedsTheShell88dpZoneOnBothNavStyles` | ⑤ P1-3 目标数字：胶囊栈 64+56=120dp（三键）/ 64+32=96dp（手势）均 > AppShell 现值 88dp |

## 5. 三目标门禁（原始数字）

- `gradle :composeApp:desktopTest --rerun`：**430 tests / 0 failures / 1 skipped**（基线 422 + 本批新增 8；1 个 skip 为基线既有）。首跑曾 4 failed：3 条为 `VadeContractCoverageGuardTest` 因 worktree 未检出 `contracts/vade` submodule（`git submodule update --init` + `bash tools/setup-contracts.sh` 后消除，环境问题非代码问题），1 条为本批测试内笔误断言（64+56 误写 128，实为 120，已改）。
- `gradle :composeApp:assembleDebug :composeApp:compileKotlinWasmJs`：gradle 原始输出 `BUILD SUCCESSFUL in 47s`（41 actionable tasks）。

## 6. P1-3 内容侧：AppShell 待改行号（无权改，未动）

- 文件：`composeApp/src/commonMain/kotlin/vade/ui/shell/AppShell.kt`（**g-glass 在改，本批未动**）
- 行号：**L107–108**（`private val BOTTOM_BAR_ZONE = 88.dp`，注释「64dp glass bar + 12dp bottom inset + 12dp gap」）；消费点 **L131–134**（`contentInsets` 的 `bottom = if (showBar) BOTTOM_BAR_ZONE else 0.dp`）
- 当前值：88dp 常量
- 目标值（浮动胶囊真实几何 = `FloatingBarPillHeight(64) + floatingBarBottomMargin()`，后者 = 8dp+inset，inset=0 时回落 28dp）：
  - inset=0（隐藏/无导航条）：胶囊栈 92dp → 内容区 ≥ **104dp**（92 + 12 间隙）
  - inset=24（手势条）：栈 96dp → ≥ **108dp**
  - inset=48（三键条）：栈 120dp → ≥ **120dp**（含 0 间隙即不够看，建议仍 +12 → **132dp**）
  - fixed 形态在 c-inset 后 = 1+64+inset = 113dp（三键），目标式同样覆盖
- 建议改法（一行，L133）：

```kotlin
bottom = if (showBar) FloatingBarPillHeight + floatingBarBottomMargin() + 12.dp else 0.dp,
```

（`FloatingBarPillHeight`、`floatingBarBottomMargin()` 都是同包 internal，AppShell 直接可用；数字已由本批 `ComposerInsetsTest` 第 8 条钉住。）

## 7. commonMain 合规

新增 commonMain 代码仅用 `androidx.compose.foundation.layout` + `ui.unit`，无 `@Volatile`/`String.format`/`Math.*`/`java.*`。
