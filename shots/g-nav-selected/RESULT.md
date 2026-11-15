# g-nav-selected · 三形态选中态收敛成一处共享渲染

分支 `tmp/g-nav-selected`（基点 `origin/main` = `bbfdf2f`）。**未 push、未合并。**

## 结论

三形态（rail / fixed / floating）的选中态**只剩一份实现**：`shell/NavSelection.kt`。
`NavRail.kt` / `BottomBar.kt` / `FloatingBottomBar.kt` 只决定条目**放在哪**，不再决定它**怎么被选中**。

| | 之前 | 现在 |
|---|---|---|
| rail | 图标后 30 dp / `shapes.small` 的 16 % accent 方块，文字变色 | 同一个 `NavSelectionIconSlot` |
| fixed | **只有图标+文字变色，没有任何底块** | 同一个 `NavSelectionIconSlot`（本轮补上底块） |
| floating | 整条 tab 宽（≈56 dp 高）的 lavender 胶囊作为选中态 | 胶囊**删除**；改为同一个 `NavSelectionIconSlot`，容器胶囊原样保留 |

规格（用户 2026-11-15 拍板逐字：「统一成「图标外包小圆角块（≈32dp，16% accent）+ 文字变色」，
三形态一致（近 miuix/KSU，且不是大色块）」）：

- 块：`32.dp` 见方，圆角 `skin.shapes.sm`（v2 = 16 dp，**token 不是字面量**），填充 `primary.copy(alpha = 0.16f)`；
- 未选中：`Color.Transparent`（显式无填充，不写父底色的第二份拷贝）；
- 图标：20 dp，选中 accent / 未选中 `onSurfaceVariant`；
- 文字：选中 accent + SemiBold，未选中 `onSurfaceVariant` + Normal（三形态同一条规则）；
- 徽标：三形态统一成 `NavBadge`（13 dp 圆、error 底、onError 字）。

## 改了什么

**新增 `composeApp/src/commonMain/kotlin/vade/ui/shell/NavSelection.kt`**

| 符号 | 作用 |
|---|---|
| `NavSelectionBlockSize = 32.dp` | 块的唯一尺寸 |
| `NavSelectionIconSize = 20.dp` | 块内图标尺寸 |
| `NavSelectionAccentAlpha = 0.16f` | 唯一的染色透明度 |
| `navSelectionCorner(shapes)` | 圆角 = `shapes.sm`（纯函数，可无渲染断言） |
| `navSelectionFill(selected, accent)` | 选中 = accent@16%，未选中 = `Color.Transparent` |
| `navTabColor(selected)` / `navTabWeight(selected)` | 文字/图标的唯一两态规则 |
| `NavSelectionIconSlot(icon, cd, selected, …)` | **共享渲染器**：块 + 图标 + 徽标槽 |
| `NavTabLabel(text, selected, size, …)` | 共享标签（字号按形态保留，颜色/字重共享） |
| `NavBadge(text)` | 统一徽标 |

**`shell/BottomBar.kt`（fixed）**：`FixedBottomBarItem` 改用 `NavSelectionIconSlot` + `NavTabLabel`；
图标解析从 `item.icon ?: VadeIcons.Circle` 改为共享的 `bottomBarIcon(item)`（icon → id 查表 → Circle），
与浮动形态对齐。**没有**动容器、inset、`FloatingBarPillHeight` 与 semantics 块。

**`shell/NavRail.kt`（rail）**：删掉自带的 30 dp 方块与自带的两态颜色，改调共享渲染器；
图标 `testTag("vade.nav.rail.icon.<id>")` 通过 `iconModifier` 保留（`GlyphClearTest` 依赖它）。

**`shell/FloatingBottomBar.kt`（floating / floating+blur）**：删掉 KernelSU 的滑动指示器
（`bottomBarIcon` 之外的整段 indicator：模糊分支的 glass lens + 按下内阴影 + 速度拉伸、
无模糊分支的整宽 accent 胶囊，以及只服务于该 lens 的隐藏 accent 副本行与
`rememberGlassBackdrop`/`rememberCombinedSurfaceBackdrop`/`captureGlassBackdrop`）。
胶囊**容器**（64 dp 行高、4 dp 内边距、26 %/34 % 或 100 % 染色、24 dp 模糊、hairline、halo、
按压缩放、4 dp 橡皮筋、拖动改选）一行未动。`FloatingBottomBarItem` 去掉
`contentColor`/`selectedContentColor` 两个形参（现在由共享渲染器决定颜色）。

## 门禁（原始输出）

```
:composeApp:desktopTest --rerun
  BUILD SUCCESSFUL in 5m 7s
  suites=109  tests=1143  failures=0  errors=0  skipped=1
  （skipped 1 = VadeClientSmokeTest.attachSubmitAndLiveEventsAgainstARealCore，
    由源码里既有的 Assume.assumeTrue("VADE_SMOKE_PORT set") 决定，本轮未动该文件）

:composeApp:assembleDebug :composeApp:compileKotlinWasmJs
  BUILD SUCCESSFUL in 2m 2s   41 actionable tasks
  APK: composeApp/build/outputs/apk/debug/composeApp-debug.apk  22,272,163 B  08:47
```

## 新增守卫（`desktopTest/.../shell/NavSelectionGuardTest.kt`，10 条）

1. `allThreeFormsRenderSelectionThroughTheSharedSymbols` —— 三个形态文件都必须出现
   `NavSelectionIconSlot(` 与 `NavTabLabel(`，且都不许再出现 `size(30.dp)` / `accentColor.copy(` /
   `0.16f` / `NavSelectionBlockSize`（**分叉即红**）。
2. `theSharedRendererIsDefinedExactlyOnce` —— 全仓 `internal fun NavSelectionIconSlot(` 恰好 1 处。
3. `blockGeometryComesFromOneSizeAndTheShapeToken` —— 32 dp / 20 dp / 16 %，圆角 = `shapes.sm`
   且 ≠ `shapes.xs`（取错阶梯也红）。
4. `unselectedPaintsNothingAndSelectedIsAccentAt16` —— 未选中 `Color.Transparent`；选中只降 alpha 不改色相。
5. `labelColourRuleIsAccentVersusOnSurfaceVariant` —— 两态颜色规则。
6. `fixedBarDrawsFour32dpBlocks` / `railDrawsFour32dpBlocks` / `floatingBarDrawsFour32dpBlocks` /
   `blurredPillDrawsFour32dpBlocks` —— 真实组合里每个形态恰好 4 个块，宽高 `assertWidthIsEqualTo(32.dp)`。
7. `floatingIconSitsInsideItsSelectionBlock` —— 图标 rect 必须**落在**块 rect 内且块比图标大（「外包」不是「旁边一块色」）。
8. `floatingContainerGeometryDidNotMove` —— `FloatingBarPillHeight == 64.dp`、源里 `.height(64.dp)` /
   `.padding(4.dp)` 仍在、`SkinGlass().blurRadiusDp == 24f`。

## 变异自验（真跑，不是声称）

| 变异 | 预期 | 实测 |
|---|---|---|
| `NavSelectionAccentAlpha` 0.16 → 0.30 | 几何/染色两条红 | `unselectedPaintsNothingAndSelectedIsAccentAt16` + `blockGeometryComesFromOneSizeAndTheShapeToken` 红（另加 `allThreeForms…`，因为守卫按 `0.16f` 扫源码） |
| 在 `NavRail.kt` 里塞回 `val legacyBlockAlpha = 0.16f`（模拟「rail 又自己写一份」） | 分叉守卫红 | `allThreeFormsRenderSelectionThroughTheSharedSymbols` 红 |

变异后已还原：`grep -c 0.16f NavSelection.kt = 1`、`grep -c MUTATION NavRail.kt = 0`，
`git diff --stat` 只剩本轮预期改动。

## 设备验证（`emulator-5554`，真帧 `adb exec-out screencap -p`，未碰真机）

| 文件 | 形态 | 内容 |
|---|---|---|
| `01-fixed-phone.png`（1080×2400） | fixed | 会话选中：图标后 32 dp 圆角块、标签 accent |
| `01b-fixed-bar-crop.png` | fixed | 底栏 1:1 裁切，可见**只有选中项**有块 |
| `01c-fixed-phone-capabilities.png`（1080×1500） | fixed | 改选「能力」——选中态会跟着走，块不残留 |
| `02-rail-wide.png`（1100×800 @160dpi） | rail | 会话选中：图标后小块 + accent 标签 |
| `02b-rail-wide-capabilities.png` / `02c-rail-crop.png`（2× 裁切） | rail | 改选「能力」；另三项无块、标签灰 |
| `03-floating-phone.png`（1080×2400） | floating | 胶囊容器原样，选中项只有 32 dp 小块 |
| `03b-floating-bar-crop.png` | floating | 1:1 裁切：**宽 lavender 胶囊已消失** |
| `03c-floating-capabilities.png` / `03d-…-crop.png` | floating | 改选「能力」，块随选中项移动 |

**没做到的**：`floating+blur` 形态**没有拍照**（只有第 6 条测试覆盖）。
原因：拍照期间这台模拟器被并发的另一条工作反复改 `wm size`（1080×2400 ↔ 1080×1500 ↔ 1280×800），
有一次连 `system_server` 都被拖挂（`Can't find service: window`），第 4 次尝试时点到的
「floating+blur」在 App 重新启动后已被重置回 fixed，拍到的图不是该形态，**已删除**，没有留下冒充证据的图。
`wm size`/`wm density` 的 override 已 `reset` 回物理 1080×2400。

## 需要复验者留意的三处「越界但必要」

1. **`BottomBarSemanticsTest.navRailUnselectedSlotPaintsNoFill` 的断言对象跟着源码搬了**：
   原来断言 `NavRail.kt` 里有 `else Color.Transparent`；那段绘制现在在 `NavSelection.kt`，
   所以改成扫共享文件（同样的两条不变量），并**新增**一条对 rail 的反向断言
   （rail 自己不许再出现 `else skin.colors.surfaceVariant`）。不变量一条没减。
2. **`GlyphClearTest` 两个调用点删掉了 `contentColor`/`selectedContentColor` 实参**：
   `FloatingBottomBarItem` 不再收这两个参数（颜色归共享渲染器）。断言（矢量图标存在、无字符回退）
   一条未改。
3. **floating 徽标由 accent 底改成共享 `NavBadge`（error 底）**：三形态原来各一份徽标实现，
   合并后取 rail/fixed 那套。若认为这超出「选中态」范围，回退点只有 `NavBadge` 一处。
