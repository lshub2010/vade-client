# g-appshell-zone · 修 w-verify P1-3（浮动胶囊把最后一行文字切掉一半）

- 分支 `tmp/g-appshell-zone`，基于 main @ `364bcf6`（含 g-icons / g-inset2 / g-meta-wire）
- 代码 commit：**`5e9ddfb`**（`fix(shell): 底栏预留区改按导航 inset 计算（P1-3 浮动胶囊切半最后一行）`；先 commit 代码+图，本报告随后单独提交）
- **未 push、未合并**
- 改动范围：`composeApp/src/commonMain/kotlin/vade/ui/shell/AppShell.kt`、`composeApp/src/desktopTest/kotlin/vade/ui/shell/AppShellBottomZoneTest.kt`（新增）、`composeApp/src/desktopTest/kotlin/vade/ui/shell/TopGlassGuardTest.kt`（1 条断言改数）、`shots/g-appshell-zone/**`
- 未触碰：`FloatingBottomBar.kt` / `TopBar.kt` / `BottomBar.kt` / `UiState.kt` / `SessionsLoader.kt` / `chat/**` / `meta/**` / `model/**` / `usage/**` / `search/**` / `module/**` / `skin/**` / `icon/**` / `data/**` / `protocol/**` / `build.gradle.kts`

## 1. 根因

`AppShell.kt` 把底部预留区写成常量 `BOTTOM_BAR_ZONE = 88.dp`，注释自述「64dp 胶囊 + 12dp 底垫 + 12dp 间隙」——**假设胶囊的底部外边距是 12dp**。真实值在 `BottomBar.kt`：

```kotlin
internal val FloatingBarPillHeight: Dp = 64.dp
internal fun floatingBarBottomMargin(navInset: Dp): Dp =
    if (navInset > 0.dp) 8.dp + navInset else 28.dp
```

即：无 inset 时是 KernelSU 的 **28dp**，有 inset 时是 `8 + inset`（手势 24 → 32，三键 48 → 56）。88dp 的预留比胶囊实际足迹少 16 / 20 / 44dp（inset 0 / 24 / 48），胶囊顶边落在预留区**之下**，滚动内容滚到底时最后一行正好被玻璃压掉一半。

顺带说明一处**范围外**的旧账：默认 `BottomBarStyle.Fixed` 的条自身高 `64 + navInset`，在三键机上 = 112dp，同样高于 88dp —— 默认形态其实也被切 24dp。本次 zone 两形态共用，一并修好。

## 2. 数字核对（任务书给的三个值）

| inset | 任务书给的胶囊高 | 实际 `FloatingBarPillHeight` | 任务书给的 margin | 实际 `floatingBarBottomMargin(inset)` | 任务书给的目标 | **实测/实算 zone** |
|---|---|---|---|---|---|---|
| 0 | 64 | **64** ✅ | 8 + inset，0 时回落 28 | **28** ✅ | ≥104 | **104** ✅ |
| 24（手势） | 64 | **64** ✅ | 8 + 24 = 32 | **32** ✅ | ≥108 | **108** ✅ |
| 48（三键） | 64 | **64** ✅ | 8 + 48 = 56 | **56** ✅ | ≥120 | **132** ✅ |

- 三个数字**全部对得上**，唯一需要纠正的表述：任务书把「三键 48 → ≥120dp」与「建议统一取 132dp」并列，这两个不是同一层含义 —— **120 = 64 + 56 是胶囊栈（不含 12dp 间隙）**，`ComposerInsetsTest` 第 8 条钉的就是这个 120；**132 = 120 + 12 才是 zone**。所以「建议 132」是对的，而 120 是 zone 必须越过的下界。
- 另外两点与代码对齐后需要写清：
  1. 那 12dp 不是额外的「呼吸间隙」，它就是 `ShellBody` 自己的 `barInsets` 底垫（`PaddingValues(bottom = 12.dp)`）。因此 `zone == 胶囊足迹`，桌面上不产生任何多余推开；
  2. `floatingBarBottomMargin` 在 inset 0 → ε 处有一段**回落台阶**（28 → 8+ε），所以 `zone` 对 inset 不是全局单调 —— 但它对「胶囊足迹」恒等（下文不变式），且三个真实档位（0 / 24 / 48）严格递增。

## 3. 新的计算方式（纯函数，随 inset 走）

```kotlin
internal fun bottomBarZone(navInset: Dp): Dp =
    FloatingBarPillHeight + floatingBarBottomMargin(navInset) + BOTTOM_BAR_DOCK_PAD   // 64 + margin + 12

internal fun contentBottomInset(showBar: Boolean, navInset: Dp): Dp =
    if (showBar) bottomBarZone(navInset) else 0.dp

internal val BOTTOM_BAR_DOCK_PAD = 12.dp
```

- 消费点一（内容侧）：`contentInsets = PaddingValues(top = TOP_BAR_ZONE, bottom = contentBottomInset(showBar, navInset))`，`navInset` 由 `navigationBarBottomInset()` 读一次并复用（`WindowInsets.navigationBars` 的唯一合法读点仍在 `BottomBar.kt`，`TopGlassGuardTest` 第 2/7 条不变）。
- 消费点二（胶囊外层）：`barInsets` 的底垫改用同一个 `BOTTOM_BAR_DOCK_PAD`，zone 与胶囊外框由此不可能漂移。
- 桌面/Wasm：`navigationBars` actual = 0（`InsetsGuardTest` 在真窗口量过）→ zone = 104dp。比旧的 88dp 多 16dp，而这 16dp 正是旧常量漏掉的（28 − 12）；**没有一 dp 是凭空加的**（`AppShellBottomZoneTest.zoneIsExactlyThePillFootprintPlusTheDockPad` 对 0/1/24/32/48 逐个钉死 `zone − 胶囊栈 == 12dp`）。
- `showBar == false`（推入详情 / rail 断点，D88）→ 0dp，不预留。

## 4. 测试（`AppShellBottomZoneTest`，10 条；要求 ≥8）

| # | 测试 | 钉住什么 |
|---|---|---|
| 1 | `noNavInsetReservesTheKernelSuFootprint` | inset 0 → 104dp（≥104），且 > 旧 88dp；前置断言 `floatingBarBottomMargin(0) == 28` |
| 2 | `gesturePillInsetReservesOneHundredAndEight` | 手势 24 → 32 margin → 108dp（≥108），且严格大于 inset 0 档 |
| 3 | `threeButtonInsetReservesOneHundredAndThirtyTwo` | 三键 48 → 56 margin → 132dp（≥120），且与 0 档差 28dp |
| 4 | `zoneNeverShrinksAsTheInsetGrows` | 三档单调不减 + `8+inset` 连续段每 +4dp inset 恰 +4dp zone（写死常量必红） |
| 5 | `zoneIsExactlyThePillFootprintPlusTheDockPad` | 0/1/24/32/48 五档：`zone − (pill+margin) == 12dp` 恒等 → 桌面不多推、三键不少留 |
| 6 | `hiddenBarReservesNothing` | `showBar=false` → 0dp（inset 0 与 48 都验）；`showBar=true` 回到 zone |
| 7 | `targetNumbersAgreeWithComposerInsetsTest` | 与 g-inset2 第 8 条同一组数字：120 / 96，且 zone 比它们各大 12dp、且 > 88dp |
| 8 | `desktopPillRendersExactlyTheReservedZoneTall` | **真渲染**：真 `VadeBottomBar` 按 AppShell 包法摆好，量「胶囊顶边 → 容器底」== `bottomBarZone(0)` |
| 9 | `lastRowScrollsClearOfTheRenderedPill` | **真滚动**：真滚动列吃 zone 当 content padding，滚到最底后最后一行底边 == zone 上沿且 ≤ 胶囊顶边（P1-3 原症状的反面） |
| 10 | `shellFeedsTheInsetDerivedZoneIntoTheContentInsets` | 接线守卫：`contentBottomInset(showBar, navInset)` 出现 1 次、`navigationBarBottomInset()` 只读 1 次、dock pad 复用、`BOTTOM_BAR_ZONE` 出现 0 次 |

**对既有测试的唯一改动**：`TopGlassGuardTest.topBarZoneArithmeticIsInsetFree` 里 `assertEquals(88.dp, BOTTOM_BAR_ZONE)` → `assertEquals(104.dp, bottomBarZone(0.dp))`。那条断言原文把「底部 zone 不携带 inset 项」当成契约，而这正是 P1-3 的病灶；改成钉新值并写明理由。**没有删除任何测试或断言**（该文件其余 6 条断言、其余文件全部未动）。

## 5. 真机验证（`192.168.1.180:5555`，全程 `-s` 指定，未碰 emulator-5554）

设备：1080×2400，density 440（1dp = 2.75px），Android 11，`navigation_mode = 0`（**三键**，正是缺口最大的 48dp 档）。`dumpsys window displays` 实测 `ITYPE_NAVIGATION_BAR frame=[0,2268][1080,2400]` = 132px = **48dp**。

APK 防覆盖（装包前 `pm path` + pull + md5，装包后同上）：

| 时点 | md5 | 字节 |
|---|---|---|
| 装包前（设备上原有构建） | `428742ffa8d50666bd23cb3922fc0d02` | 22,010,997 |
| 装包后 pull 回 | `981853cf66ab2413785a4385facea4eb` | 22,124,707 |
| 本批 `composeApp-debug.apk` | `981853cf66ab2413785a4385facea4eb` | 一致 |

复现路径：装包 → 启动（默认 `Fixed`）→ 我的 → 底栏点 `floating+blur`（P1-3 报的是浮动胶囊；`App()` 的默认是 `Fixed`，不切这一下看不到胶囊）→ 颜色/设置页滑到底 → 截图；再回会话 → 打开「U1-a 骨架验收」→ 滑到底 → 截图。

两张证据（1080×2400）：

- `shots/g-appshell-zone/settings-bottom.png`（**283,894 字节**，md5 `0909787cb7289dd570128abb6da45751`）
- `shots/g-appshell-zone/detail-bottom.png`（**306,604 字节**，md5 `829824c75c70a01e14199f3417dff261`）

### 开图结论（我自己开图核对，非日志推断）

- **设置页（settings-bottom.png）**：滑到最底，页面最后一行 `U1-f。` 与上一行 `设置项打开为全屏子页（D91）…` 都完整可见，胶囊顶边之下再无内容。像素测量：最末文字墨迹行 y=1979，胶囊顶边 y=2037 → **净空 58px = 21.1dp**，文字没有任何一半被压。**同时反过来验证了 zone 数值**：`屏底 2400 − 胶囊顶 2037 = 363px = 132.0dp`，与 `bottomBarZone(48dp)` **逐 dp 相等**（旧 88dp 时这条线会是 88dp，缺口 44dp —— 正是报障里被切的高度）。
- **会话详情（detail-bottom.png）**：滑到最底，最后一屏是 kotlin 代码块（`tasks.register("printRuntimeClasspath") { doLast { println("…") } }`）与底部工具条，整块在输入胶囊之上结束（代码块底 y≈1945，输入胶囊顶 y≈2040），没有被胶囊或导航条切掉任何笔画。详情页不绘制底栏（D88），这条是「没被这次改动带坏」的回归证据。
- 设备状态：验证前设备是横屏（`SurfaceOrientation: 1`，rail 断点，看不到底栏胶囊），临时切竖屏 `user_rotation 0` + `accelerometer_rotation 0`；**验证完已把 `accelerometer_rotation` 还原为 1**，未重启、未卸载任何应用。

## 6. 三目标门禁（原始数字）

- `gradle :composeApp:desktopTest --rerun` → **BUILD SUCCESSFUL**；汇总 build/test-results：**63 个测试类 / 688 tests / 0 failures / 0 errors / 1 skipped**（skip 为基线既有 `VadeClientSmokeTest.attachSubmitAndLiveEventsAgainstARealCore`，需要真 core 二进制）。688 = 基线 678 + 本批 10。
- `gradle :composeApp:assembleDebug` → **BUILD SUCCESSFUL**（2m 19s；39 tasks，20 executed / 18 from cache）。
- `gradle :composeApp:compileKotlinWasmJs` → **BUILD SUCCESSFUL**（39s）。

**已知抖动（`UiStateSourceWiringTest` / `RealCoreE2ETest`）**：本轮 `--rerun` **没有命中**，两个类都是绿的；按要求未改它们的断言、未加 skip、未碰 `UiState.kt` / `SessionsLoader.kt`。也就是说本轮不存在「红在它们身上」的情形需要重跑；若后续批次在此之上重跑出现红，仍应按原约定归因到那两个类自己的抖动，与本批 4 处文件无关（本批未 import 也未改动它们的任何符号）。

commonMain 合规：本批只加了 `androidx.compose.ui.unit.Dp` 一个 import 与三个纯 Kotlin 函数/常量，无 `@Volatile`、无 `String.format` / `Math.*` / `java.*`、无新平台 API（`navigationBarBottomInset()` 是既有 CMP 多平台实现）。

## 7. 遗留 / 未做

- `composeApp/src/desktopTest/kotlin/vade/ui/chat/ComposerInsetsTest.kt` 的 KDoc 仍写着「`AppShell.kt:108 BOTTOM_BAR_ZONE = 88.dp` belongs to another batch」——该文件在 `chat/**` 禁改范围内，**未动**；它的断言（120 / 96 / > 88dp）在新值下仍成立。等 chat 棒或主线顺手把这句注释更新为 `bottomBarZone`。
- `Fixed` 形态现在拿到 132dp 预留而自身只有 64 + inset，桌面会多出 40dp 空白尾垫（旧 88 时是 24dp）。这是「两形态共用一条 zone」的已知代价，换来的是三键机上 `Fixed` 也不再被切 24dp；若要贴紧，需要把 zone 拆成按 `barStyle` 的两条函数并各自加测，本批未做（超出 P1-3 范围）。
- 未做手势导航（inset 24）真机档：设备只有三键（`navigation_mode=0`，`wm size` 改不了导航模式），手势档由单测钉死 108dp，与三键档共用同一条 `8 + inset` 公式。
