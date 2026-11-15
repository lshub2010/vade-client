# 视觉修复 · g-topbar-band —— 模糊覆盖整条顶栏带，浮卡本体不动

- 仓库 / 基线：`vade-client` · `origin/main` = **bbfdf2f**（worktree `tmp/g-topbar-band`）
- 用户拍板（2026-11-15，逐字）：「让模糊覆盖整条顶栏带（浮卡外圈加同色遮罩），卡片外观不变——修 D2 且不动你认可的观感」
- 缺陷来源：`shots/g-visual-audit2/RESULT.md` **D2**（顶栏是内缩浮卡，卡片本体有模糊，但卡片**上方 8–12dp / 下方 8–12dp** 两条空隙里的滚动内容**原样清晰**）
- 构建：`gradle :composeApp:assembleDebug`（BUILD SUCCESSFUL）
- 设备：**只用 `emulator-5554`**（API 37，`wm size 1080x1500` + `wm density 420` = 411×571dp，0 状态栏 inset 由根布局消费 24dp）
- 截图：`adb -s emulator-5554 exec-out screencap -p`（真帧缓冲；**未用** `tools/capture-window.ps1`；**未碰** 192.168.1.180 真机）

---

## 1. 改了什么（三处，都是"垫一层"，不动卡片）

| 文件 | 改动 |
|---|---|
| `component/liquid/GlassBand.kt`（新） | 一条**只有玻璃、没有内容**的带子：`hazeBlur`（同一 `HazeState`）+ `colorEffects(tint)`，**无描边、无阴影、无圆角**。`component/liquid` 只认 haze，皮肤语义留在 shell 侧 |
| `shell/TopBar.kt` | 新增 `TopBarBand()`：取 `Skin.glass` 的同色系染色（`tintLight`/`tintDark`）按 `TopBarBandTintScale = 0.6` 减淡 + 与卡片相同的 `blurRadiusDp`。**`TopBar()` 本体一行未改** |
| `shell/AppShell.kt` | 顶栏带那一段：一个 `Box(align=TopCenter, fillMaxWidth, padding(start=topBarStart,end=topBarEnd), height=TOP_BAR_ZONE)` 里放 `TopBarBand(fillMaxSize)`，**组合在浮卡 Row 之前**（z 序在卡片背后）。顺带把"带子到哪 / 卡片缩到哪"收敛成**一处**推导（`topBarStart` / `topBarEnd`），右面板一开两边一起收 |

三条设计约束（写进 KDoc，测试也钉住）：

1. **画在卡片背后，不画在卡外环形路径上**：卡片自己的玻璃是**不透明**的（blurred backdrop 不透明，染色走 `colorEffects`），所以垫在背后的东西透不进卡内；环形路径要在 28dp 圆角上求差集，任何 1px 误差都会啃到描边，而且四个圆角死角（D2 里"头像被卡片弧形切掉一半"那处）必须靠"垫在背后"才盖得住。
2. **恰好 `TOP_BAR_ZONE`（72dp）**：比它矮就露下半条缝，比它高就压进首行内容。测试用源码正则钉死 `.height(TOP_BAR_ZONE)` 出现且仅出现一次。
3. **不是色板**：染色只取卡片的 0.6 倍，模糊（24dp，与卡片逐点相同）才是主力；纯画布上带内外的亮度差实测 **2.1/255**（桌面像素测试断言 ≤12）。

**未改**：`destinations/**`、`skin/**` 的 token 值、`data/**`、`protocol/**`、`build.gradle.kts`、`contracts/**`；`AppShell.kt` 里 `metaGroupsOf` / `metaGroups =` 一行未动。

---

## 2. 验收 1 · 空隙不再是"锐的"（真机像素）

A/B 口径（`shots/g-topbar-band/capture.sh`，可复现）：

- 每次冷启前 `pm clear` + 显式 `--es source fake`（假源 fixture，六个会话，内容逐字固定）；
- 列表**滚到底**（3 次慢拖）——只有夹紧的滚动位置才逐像素可复现；
- 同一构建两次冷启，整帧（y≥50）**逐像素 0 差**（`control-after-second-cold-start-a.png` vs `after-a-*.png`）→ 采集本身没有噪声，下面的差值全是代码造成的。

图：`before-a-phone-sessions-scrolled.png` ↔ `after-a-phone-sessions-scrolled.png`（同名对照，1080×1500）
裁剪：`crop-before-a-top-2x.png` / `crop-after-a-top-2x.png`、`crop-before-a-strip-3x.png` / `crop-after-a-strip-3x.png`

**相邻像素梯度能量**（每像素平均，0..255 量纲；`python shots/g-topbar-band/metrics.py <before> <after> 2.625 63`）

| 取样区（带顶 y=63，浮卡 84..231，带底 252） | 修前 | 修后 | 下降 |
|---|---|---|---|
| 上空隙 8dp（63→84） | **3.64** | **1.30** | 2.8× |
| 下空隙 8dp（231→252） | **1.53** | **0.17** | 8.9× |
| 卡内·背后有字的区域（y 88..106） | 1.67 | 0.37 | 4.5× |
| 卡内·空玻璃（y 150..200） | 0.05 | 0.05 | — |

判据不止能量，还有"字形还在不在"：

| 上空隙统计（y 66..96, x 60..1020） | 修前 | 修后 |
|---|---|---|
| 最暗像素亮度 | **100.6**（黑字笔画） | **186.5** |
| p1 亮度 | 103.9 | 194.3 |
| 亮度标准差 | **25.8** | **8.8** |

→ 修前那两条缝里是**有黑笔画的字形**（最暗 100），修后连一个暗像素都不剩（最暗 186），对比度从 140/255 降到 54/255，只剩"看得出有字"的灰影。会话详情面同构复验：`before-b-phone-chat-detail.png`（缝里 `§6.1 / 以及 m/README…` 清晰可读）↔ `after-b-phone-chat-detail.png`（只剩 `IMG_0042 img` 的糊影），上空隙能量 7.11 → 2.10。

> 桌面 JVM 像素测试里的同构对照更干净（密排正文、无卡片阴影干扰）：**38.01 → 1.03**（37×）与 **46.45 → 0.66**（70×），卡内模糊参照 0.15（`TopBarBandTest` 的 println）。

---

## 3. 验收 2 · 卡片本体：取样证据（不是"看起来没变"）

同一对真机帧，卡片矩形 = x 32..1048 / y 84..231（12dp 内缩 + 56dp 高，由 `testTag` 几何在桌面侧同构验证）：

| 取样 | 面积 | 最大逐像素差 | 判据 |
|---|---|---|---|
| 卡片离边 **14dp 内侧**（填充 + 标题 + 副标题 + 图标） | 942×73 px | **5/255** @ (226,123) | ≤2 未达成，见下 |
| 其中**纯填充**子样（空玻璃，无文字无图标） | 473×53 px | **1/255** | ✓（≤2） |
| 图标区（搜索 / 新建） | 130×70 px | **1/255** | ✓ |
| 带下方内容（y>264，整幅宽） | 1080×1226 px | **0/255** | ✓ 改动没有外溢 |

**这 5/255 是什么**：只出现在副标题那一行字形的**抗锯齿像素**上（204 px，占卡片内部 0.3%）。卡片填充本身逐像素不变（≤1），字形底下的模糊纹理因为"背后多垫了一层"有 ≤5 的差别，被字形的 AA 放大到 5。**卡片的圆角位置、描边、填充、文字、图标、高度、左右边距全部未变**（`TopBar()` 函数体一行未改，几何由 `TopGlassGuardTest` 继续钉住）。

**离边 <30px 的羽化圈确实有差（最大 131/255 @ (54,228)）**，但差值的形状是**背后那行字的轮廓**、不是卡片的边：那 30px 正是卡片玻璃自身的模糊羽化（半透明渐隐）区，改的是"从这里漏出来的内容"——也就是 D2 里"正文行被卡片下沿切一半"的同一病灶。4× 裁剪可复核：`crop-before-a-cardbottom-4x.png`（卡片下沿外"今天 2"锐利）↔ `crop-after-a-cardbottom-4x.png`（同一位置已被糊掉，卡片下沿描边不动）。

> 换方案也换不掉这一圈：若把带子从卡片**矩形**里挖掉，四个 28dp 圆角死角会重新露出锐利内容（就是 D2 的"头像被弧形切掉一半"）；若按**圆角轮廓**挖，环的内边界会压到卡片 1px 的抗锯齿描边上，变成第三条轮廓（D6 的同类）。所以选择"垫在背后"，并把这一圈如实报出来。

---

## 4. 验收 3 · 守卫测试（能盯住"空隙区又变锐"）

`composeApp/src/desktopTest/kotlin/vade/ui/shell/TopBarBandTest.kt`（3 条，全绿）：

1. `gapStripsStopBeingSharpButTheCapsuleStaysByteIdentical` —— **像素测试**。同一套密排正文背板渲染两遍（有带子 / 无带子），量两条空隙的梯度能量：无带子帧必须**够锐**（>8，防空转），有带子帧必须 **≤1/3**，且与卡内模糊区同量级；同时断言卡片离边 6dp 内侧两帧 **≤2/255**（谁把带子改画到卡片前面，这条先红）。
2. `bandOverPlainCanvasIsAVeilNotAPlate` —— 纯画布上带内外亮度差 **≤12/255**、带内自身梯度 ≤1.5（"不是一块不透明的色板"）。
3. `bandIsComposedBeforeTheCapsule` —— **源码守卫**：`TopBarBand(` 必须出现在 `TopBar(` 之前（z 序在卡片背后）、`.height(TOP_BAR_ZONE)` 恰好一处、`fillMaxSize` 铺满、`topBarStart/topBarEnd` 各自只推导一次且带子/卡片共用（右面板一开不会漂出 12dp 锐缝）。

---

## 5. 门禁（三目标，全绿）

| 目标 | 结果 |
|---|---|
| `:composeApp:desktopTest --rerun` | **BUILD SUCCESSFUL**；`build/test-results/desktopTest/TEST-*.xml` 求和：**tests=1136**、failures=0、errors=0、skipped=1（`VadeClientSmokeTest.attachSubmitAndLiveEventsAgainstARealCore`，**改动前就 skip**：需要真 core） |
| `:composeApp:assembleDebug` | **BUILD SUCCESSFUL** |
| `:composeApp:compileKotlinWasmJs` | **BUILD SUCCESSFUL** |

未删除/未跳过/未弱化任何既有断言。

---

## 6. 未覆盖 / 局限（如实）

- **桌面双栏的真机帧只有"修后"**（`after-c-desktop-chat.png`，1280×800）。该轮的"修前"帧因模拟器 `wm size` 没切过来（出成 1080×2400）而作废，未入报告；桌面的**几何**由 `TopBarBandTest` 的源码守卫（带子止于右面板前、与卡片共用边界）+ `TopGlassGuardTest` 的既有几何断言覆盖，**像素**未做 A/B。
- **暗色主题**未验（`MaterialSkinEntry.dark` 只能由宿主 `--dark`/`VADE_DARK` 设定，Android `MainActivity` 不读该开关——与 `g-visual-audit2` 同一限制）。
- **miuix 皮肤**未单独截帧：带子读的是 `Skin.glass` 的同色系 token 与同一 `blurRadiusDp`，两套皮肤走同一条代码路径，但未做 miuix 的 A/B 图。
- 卡片内部 5/255（副标题 AA）没有压到 ≤2：成因是字形底下的模糊纹理随"背后多一层"变化，不换方案（不挖洞）就消不掉；换方案要拿四个圆角死角的锐利内容去换。

## 7. 文件清单

- 新增：`composeApp/src/commonMain/kotlin/vade/ui/component/liquid/GlassBand.kt`、`composeApp/src/desktopTest/kotlin/vade/ui/shell/TopBarBandTest.kt`、`shots/g-topbar-band/**`（图 + `capture.sh` + `metrics.py` + 本文件）
- 修改：`shell/TopBar.kt`（+`TopBarBand`、两个常量、KDoc）、`shell/AppShell.kt`（顶栏带那一段 + `topBarStart/topBarEnd` 收敛）
