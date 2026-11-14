# g-zone-split · 底栏预留区按 barStyle 拆函数（Fixed 不再预留浮动胶囊）

- 分支 `tmp/g-zone-split`，基于 main 的 `6648901`（本 worktree 创建时 main 已把 g-flake-gen 合到 `a4121bb` 之上；`a4121bb` 即 g-appshell-zone 的底栏修复）
- 代码 commit：**`9e17be9`**（`fix(shell): 底栏预留区按 barStyle 拆成两条公式…+ 13 条测试`；先 commit 代码+图，本报告随后单独提交）
- **未 push、未合并**；未碰真机（`192.168.1.180:5555` 全程未连接），Android 侧只做了 `assembleDebug` 编译
- 改动文件：`composeApp/src/commonMain/kotlin/vade/ui/shell/AppShell.kt`、`composeApp/src/desktopTest/kotlin/vade/ui/shell/BarStyleZoneSplitTest.kt`（新建，13 条）、`composeApp/src/desktopTest/kotlin/vade/ui/shell/AppShellBottomZoneTest.kt`（1 条接线断言随签名更新，见 §7）、`composeApp/src/desktopTest/kotlin/vade/ui/chat/ComposerInsetsTest.kt`（**仅 KDoc**）、`shots/g-zone-split/**`
- 未触碰：`UiState.kt` / `SessionsLoader.kt` / `FloatingBottomBar.kt` / `BottomBar.kt` / `component/liquid/**` / `data/**` / `protocol/**` / `meta/**` / `model/**` / `usage/**` / `search/**` / `module/**` / `skin/**` / `icon/**` / `build.gradle.kts` / `contracts/**`；`chat/**` 只改了那一段 KDoc

## 1. 先测量：任务书的「40dp」不准，真值是 39dp（旧 88dp 时 23dp）

拆函数**之前**（main 原样，`bottomBarZone` 两形态共用）先跑了一次真渲染探针，`system-out` 原文：

```
ZONE_PROBE desktop(inset=0) fixedFootprint=65.0dp floatingFootprint=104.0dp sharedZone=104.0.dp sharedZoneFixedBlank=39.0dp sharedZoneFloatingBlank=0.0dp contentBottomInset=104.0.dp
```

拆函数**之后**同一支测试（改名 `desktopFixedBlankDropsFromThirtyNineToZero`）的 `system-out` 原文：

```
ZONE_SPLIT desktop(inset=0) fixedFootprint=65.0.dp fixedZoneBefore=104.0.dp blankBefore=39.0.dp fixedZoneAfter=65.0.dp blankAfter=0.0.dp blankUnderLegacy88=23.0.dp floatingFootprint=104.0.dp floatingZone=104.0.dp floatingBlank=0.0.dp
```

| 量 | 值 | 说明 |
|---|---|---|
| Fixed 条真渲染足迹 | **65.0 dp** | 1dp hairline（`Box(height(1.dp))`）+ 64dp 玻璃行；任务书按 64 算才会得出 40 |
| 拆前预留（共用 zone，inset 0） | 104.0 dp | `64 + 28 + 12` |
| **拆前桌面空白尾垫** | **39.0 dp** | 104 − 65，**不是 40**（漏掉了条顶 1dp 线） |
| 拆后预留（`fixedBarZone(0)`） | 65.0 dp | 与足迹逐 dp 相等 |
| **拆后桌面空白尾垫** | **0.0 dp** | 预留 = 足迹 |
| P1-3 之前的 88dp 常量留下的空白 | 23.0 dp | 88 − 65（任务书写 24，同样是 1dp 线之差） |
| Floating 胶囊足迹 / zone / 空白 | 104 / 104 / 0 dp | 上一棒的修复未被碰坏 |

两条都是**真渲染**（`runComposeUiTest` 里摆真的 `VadeBottomBar`，分别按 `ShellBody` 的两种包法），不是常量推算。

## 2. 真截图（桌面产物能跑，已拍，已开图）

用 `tools/capture-window.ps1`（并发安全版）从真桌面产物 `vade.ui.MainKt` 起窗口，`-Bar fixed` / `-Bar floating`，客户端 466×893（density 1.0 → 1dp = 1px）：

- `shots/g-zone-split/desktop-fixed.png`（32,453 B，md5 `33DCD0BE941843C25EA290404971CA42`）+ 底部 200px 裁图 `desktop-fixed-bottom200.png`
- `shots/g-zone-split/desktop-floating.png`（35,172 B，md5 `D3D69D979FDD1D83F0FE00462788CF1A`）+ 裁图 `desktop-floating-bottom200.png`

像素实测（Python/PIL 逐行扫描，我自己开图核对）：

- **Fixed**：条顶 hairline 在 y=827（行色 `(230,230,233)`），玻璃 828–891，窗口最底 1px 边框 y=892 → 条顶到内容底 **65px = 65.0dp == `fixedBarZone(0)`**。条贴窗口底，上方无任何异常留白，列表没有内容被切。
- **Floating**：胶囊顶边（1px 边线 `(228,228,247)`→内部 `(253,253,254)`）在 y=788 → 距内容底 **104px = 104.0dp == `floatingBarZone(0)`**；胶囊底边 y≈851，距底 41px ≈ 40dp（28 margin + 12 dock pad）。

**这张图能证明什么、不能证明什么（如实写）**：它证明两形态的**条/胶囊几何**在真窗口里逐 dp 正确、Fixed 条贴底且无内容被裁；但**静态首帧看不出 39dp→0dp 的空白尾垫差别** —— 会话列表是假数据，最后一行停在 y=677（距 Fixed 条 150px、距胶囊顶 111px，本来就短于视口），预留区是 content padding，只有滚动到底才现形，而 `capture-window.ps1` 没有滚动参数。所以 39→0 这个数字由 §1 的真组合几何测试（量真实 bounds）给出，截图只作旁证。

## 3. 新函数（`AppShell.kt`）

```kotlin
internal fun floatingBarZone(navInset: Dp): Dp =
    FloatingBarPillHeight + floatingBarBottomMargin(navInset) + BOTTOM_BAR_DOCK_PAD   // 104 / 108 / 132

internal fun bottomBarZone(navInset: Dp): Dp = floatingBarZone(navInset)              // 历史名，= Floating

internal val FixedBarRowHeight: Dp = 64.dp                                           // Fixed 自己的行高（字面量）
internal val FixedBarTopRule: Dp = 1.dp                                              // 条顶 hairline

internal fun fixedBarZone(navInset: Dp): Dp =
    FixedBarTopRule + FixedBarRowHeight + navInset                                   // 65 / 89 / 113

internal fun barZoneFor(style: BottomBarStyle, navInset: Dp): Dp = when (style) {
    BottomBarStyle.Fixed -> fixedBarZone(navInset)
    BottomBarStyle.Floating, BottomBarStyle.FloatingBlur -> floatingBarZone(navInset)
}

internal fun contentBottomInset(showBar: Boolean, navInset: Dp, style: BottomBarStyle = BottomBarStyle.Floating): Dp =
    if (showBar) barZoneFor(style, navInset) else 0.dp
```

- 消费点：`contentInsets = PaddingValues(top = TOP_BAR_ZONE, bottom = contentBottomInset(showBar, navInset, state.barStyle))`（`navInset` 仍只读一次；`barInsets` 仍是 `BOTTOM_BAR_DOCK_PAD`）。
- **104 / 108 / 132 一个 dp 都没变**（`floatingBarZone` 的表达式与拆前逐字相同，`bottomBarZone` 变为它的别名；`AppShellBottomZoneTest` 三条目标值测试、`TopGlassGuardTest` 的 104 全部原样通过）。
- Fixed 三档 **65 / 89 / 113**：桌面 1+64；手势 +24；三键 +48（`FixedBottomBar` 自己就是 `height(pill + navInset)` 且 labels 垫在 inset 之上，所以三键机上这条 inset 是它的真实高度需求，内容必须让开）。

## 4. 关于「差值 == FloatingBarPillHeight」：字面要求与正确性互斥，已换成可钉为真的形式

任务书要求「Fixed 的 zone 小于 Floating，且差值 == `FloatingBarPillHeight`(64)」。这条在算术上做不到：

- 两形态行高**都是 64dp**（Fixed 条与胶囊共用 KernelSU 56+2×4 的几何），差值 64 只能意味着 Fixed 预留 `margin + dockPad = 40dp`（inset 0）；
- 而 Fixed 条自身 65dp 高 → 40dp 预留会让最后一行被压掉 **25dp**，正是 P1-3 的反面（本次要修的病）。

实际差值是 margin 与 hairline 的差：inset 0 → **39dp**（28+12−1），24/48 → **19dp**（(8+i)+12−i−1）。两条测试里都写明了这一点。换成三条**为真**的解耦证据：

1. `fixedZoneDropsThePillTermButKeepsItsOwnRow`：`floatingBarZone(i) − pill − margin(i) == 12dp`（pill 项只在 Floating 侧），`fixedBarZone(i) − FixedBarRowHeight − i == 1dp`（只有自己的线），且 `floating − fixed < 64` 恒成立；
2. `fixedZoneExpressionNeverMentionsThePillHeight`：源码级反向守卫 —— `fixedBarZone` 函数体、`FixedBarRowHeight` 声明（必须是 `64.dp` 字面量）、`barZoneFor` 的 Fixed 分支**均不得出现** `FloatingBarPillHeight`；
3. 两条真渲染几何 + Fixed 场景的真滚动，证明两形态各自顶边到容器底 == 自己的 zone。

## 5. 测试（`BarStyleZoneSplitTest`，13 条；要求 ≥10）

| # | 测试 | 钉住什么 |
|---|---|---|
| 1 | `floatingZoneKeepsTheThreePinnedTargets` | **回归**：104/108/132，且 `Floating`/`FloatingBlur`/历史名 `bottomBarZone` 三个入口同一组数字 |
| 2 | `fixedZoneIsItsOwnRowBehindItsRulePlusTheInset` | 65/89/113；`FixedBarRowHeight` 必须是 64dp 字面量；每档 `zone − 行高 − inset == 1dp` |
| 3 | `fixedZoneIsSmallerThanFloatingOnEveryInset` | 0/24/48 三档都 `Fixed < Floating` |
| 4 | `fixedZoneDropsThePillTermButKeepsItsOwnRow` | 差值 39/19/19 + `Floating − pill − margin == 12dp` + 差值 < 64（结构解耦） |
| 5 | `barZoneForRoutesEachStyleToItsOwnFormula` | 三形态各自路由到自己的公式，Fixed ≠ Floating |
| 6 | `hiddenBarReservesNothingOnBothForms` | `showBar=false → 0`，三形态 × 三 inset 全测；`showBar=true` 回到各自 zone |
| 7 | `bothFormsGrowWithTheInset` | 两形态三档单调递增；`8+inset` 段每 +4dp inset 恰 +4dp zone |
| 8 | `desktopFixedBlankDropsFromThirtyNineToZero` | **核心数字**：真渲染两形态，打印并断言 65/39/0/23/0 |
| 9 | `floatingBarRendersExactlyItsZoneTall` | 真渲染：胶囊顶边到容器底 == `floatingBarZone(0)` = 104 |
| 10 | `fixedBarRendersExactlyItsZoneTall` | 真渲染：条顶边到容器底 == `fixedBarZone(0)` = 65，条高 == 1+64 |
| 11 | `fixedContentScrollsClearOfTheDockedBar` | 真滚动：Fixed 内容滚到底，最后一行底边 == zone 上沿且 ≤ 条顶边 |
| 12 | `fixedZoneExpressionNeverMentionsThePillHeight` | **反向守卫**（源码）：Fixed 路径表达式/常量/分支不得含 `FloatingBarPillHeight` |
| 13 | `shellFeedsTheStyleAwareZoneIntoTheContentInsets` | 接线：`contentBottomInset(..., state.barStyle)` 1 次、inset 读 1 次、dock pad 复用、`BOTTOM_BAR_ZONE` 0 次、ShellBody 不绕过 `barZoneFor` |

## 6. 三目标门禁（原始数字）

- `gradle :composeApp:desktopTest --rerun` → **BUILD SUCCESSFUL**（1m35s）；汇总 build/test-results：**75 个测试类 / 801 tests / 0 failures / 0 errors / 1 skipped**。`801 = 基线 788 + 本批 13`（基线 788 用拆函数前的一次全量 `--rerun` 实测：788 + 当时的 1 条探针 = 789，探针已被 13 条正式测试取代）。skip 是基线既有 `VadeClientSmokeTest.attachSubmitAndLiveEventsAgainstARealCore`（需真 core 二进制）。
- `gradle :composeApp:assembleDebug` → **BUILD SUCCESSFUL**（1m13s；39 tasks，20 executed / 18 from cache）。
- `gradle :composeApp:compileKotlinWasmJs` → **BUILD SUCCESSFUL**（44s）。

**已知抖动**（`UiStateSourceWiringTest` / `RealCoreE2ETest`）：基线那次全量与终验全量**都没命中**，两个类全绿；未改它们的断言、未加 skip、未调超时、未碰 `UiState.kt` / `SessionsLoader.kt`。

## 7. 对既有测试的改动（两处，均未删测/未 skip）

1. `AppShellBottomZoneTest.shellFeedsTheInsetDerivedZoneIntoTheContentInsets`：唯一变化是接线断言的正则跟签名走 ——
   `bottom = contentBottomInset\(showBar, navInset\)` → `bottom = contentBottomInset\(showBar, navInset, state\.barStyle\)`（同文件 3 条目标值测试与其余 6 条断言一字未动）。
2. `chat/ComposerInsetsTest.kt`：**只改 KDoc**（88dp「属于别的批次」已过期）。`git diff --numstat` = `10 5`，逐行检查所有增删行都以 ` *` 开头：

```diff
-     * ⑤ (P1-3 shell-side target, pinned arithmetic only): `AppShell.kt:108
-     * BOTTOM_BAR_ZONE = 88.dp` belongs to another batch (g-glass) and is out of
-     * this batch's writable scope — this test pins the numbers the fix must
-     * reach: the floating pill stack is 64 dp + (8 dp + inset), i.e. 128 dp on
-     * 3-button nav and 96 dp on the gesture pill, both over the current 88 dp.
+     * ⑤ (P1-3 shell-side numbers, pinned arithmetic only): the shell's zone is **no longer**
+     * the old `AppShell.kt` `BOTTOM_BAR_ZONE = 88.dp` constant — that fix landed, and since
+     * g-zone-split the zone is derived per bar form: `floatingBarZone` = 104 / 108 / 132 dp
+     * at inset 0 / 24 / 48, `fixedBarZone` = 65 / 89 / 113 dp (no pill term). This test still
+     * pins the *pill stack* the floating zone must cover: 64 dp + (8 dp + inset), i.e.
+     * 120 dp on 3-button nav and 96 dp on the gesture pill — each dock-pad 12 dp below the
+     * zone (`FloatingBarPillHeight + floatingBarBottomMargin(i) == floatingBarZone(i) −
+     * BOTTOM_BAR_DOCK_PAD`). The `> 88.dp` assertion below is the historical lower bound,
+     * kept as a regression: the stack must never shrink back under the constant that used to
+     * slice the last row in half.
```

（顺带修掉旧 KDoc 里「128 dp on 3-button」的笔误：真值是 64+56 = 120，该测试自己的断言就是 120。）

## 8. 遗留 / 未做

- **未做 Android 真机验证**：按本批硬约束不碰在用的 `192.168.1.180:5555`，也没启 `emulator-5554`。Fixed 在 Android 三键（inset 48）→ 113dp、手势（24）→ 89dp 由单测钉住，`assembleDebug` 通过；像素级证据留给后续有设备的棒。
- 静态截图看不出 39→0dp 的尾垫差（原因见 §2）；若要补一张滚动到底的图，需要 `capture-window.ps1` 增加滚动参数，或人工滚到底再拍。
- `FloatingBarFootprintTest`（g-float，`component/liquid` 测试）的注释仍写「the shell's `BOTTOM_BAR_ZONE` reserves 88dp … another batch owns AppShell.kt」——该文件不在本批可改范围（且它在 `component/liquid` 测试目录，本批禁改），断言本身（104/132 足迹）仍全部成立，注释留待其归属棒更新。
