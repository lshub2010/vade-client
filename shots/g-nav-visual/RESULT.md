# RESULT — 导航四图标视觉验收（`g-glyph-clear` 合入后的第一次看脸）

Worktree `D:/HermesWorkspace/Vade-client-wt/g-nav-visual` · 分支 `tmp/g-nav-visual`
基线 main @ **`0c3651c`**（`fix(icon): 合并 g-glyph-clear…`）
截图 commit：**`b35a984`**（42 张）· 本文件与 `w07-*` 两图为第二个提交

未 push、未合并。只用 `git add` + `git commit`，没有 `--amend` / `rebase` / `reset`。
**产品代码零改动**：`git status` 全程只有 `?? shots/g-nav-visual/`（`composeApp/src/**` 一字未动，`local.properties` 在 .gitignore 里）。

---

## 0. 环境与口径

| 项 | 值 |
|---|---|
| 设备 | **`emulator-5554`** 唯一使用。`ro.build.version.sdk=37`、`ro.kernel.qemu=1`（模拟器）、`product:sdk_gphone64_x86_64` |
| 真机 | `192.168.1.180:5555` 全程未下任何命令（`adb devices` 只读列过一眼） |
| 窄屏形态 | 物理 `1080x2400 @ 420dpi` = **411×914 dp** → `<600dp`：底栏单栏（Fixed / Floating / FloatingBlur 三形态） |
| 宽屏形态 A | `wm size 2400x1500` + `wm density 240` = **1600×1000 dp** → `≥1200dp` Tier Large：`NavigationRail 72dp · 双栏 + 右面板` |
| 宽屏形态 B | `wm size 1050x1800` + `wm density 240` = **700×1200 dp** → `600–720dp` Tier Medium：`NavigationRail 80dp · 单栏` |
| 收尾 | `wm size reset` + `wm density reset`（已恢复 `1080x2400 @ 420`） |
| 构建 | `gradle :composeApp:assembleDebug` → **BUILD SUCCESSFUL in 1m 6s**；`composeApp-debug.apk` **22,173,859 字节** |
| 装包 | `adb -s emulator-5554 install -r …` → **Success**（装前旧包 `lastUpdateTime=2026-11-14 16:17`，早于本次合并） |
| 截图 | 一律 `adb -s emulator-5554 exec-out screencap -p > *.png`（**没有用** `capture-window.ps1`）；裁切用 PIL `LANCZOS` |
| 被验代码 | `Destination.icon = VadeIcons.{Chat=MessageCircle, Activity, Grid=LayoutGrid, Person=User}`；`NavRail` 画 20dp；`FloatingBottomBar`/`FixedBottomBar` 画 20dp；`panelToggleIcon(open)`；`VadeIcons.Close` |

---

## 1. 逐图核对表

判据固定四项：**渲染**（真画出来了？不是空框/黑方块/豆腐）· **可区分**（四个彼此分得开）· **选中态**（一眼可辨）· **对齐**（图标 vs label / 有无遮挡切字）。
对应裁切图列在「同图」列，结论一体。

### 1.1 宽屏 NavRail（`≥1200dp`，rail 72dp，图标 20dp）

| 图 | 位置 / 形态 | 渲染 | 可区分 | 选中态 | 对齐 / 遮挡 |
|---|---|---|---|---|---|
| `wide/w01-rail-sessions.png`（+`-rail4x`） | NavRail，会话选中 | 4/4 完整：气泡 / 心电线 / 四宫格 / 人形，无空框无豆腐 | `Grid`(四宫格) vs `Activity`(折线) 完全不同；`Chat`(带尾气泡) vs `Person`(头+肩) 完全不同 | **可辨**：会话有 30dp `primary@16%` 圆角底 + 紫图标 + 紫半粗字，其余灰 | 图标 cx 全部 **54.5 px**（rail 几何中心 54）；label cx 53.5 → 偏 0.5–1 px（≤0.7 dp）；图标 y[79,102] 与 label y[115,140] 不重叠，10sp 字高 15 px 无切字 |
| `wide/w02-rail-activity.png`（+`-rail4x`） | NavRail，活动选中 | 同上 4/4 完整 | 同上 | 可辨：底框套在折线图标上，紫图标+紫半粗「活动」 | 同上 |
| `wide/w03-rail-capabilities.png`（+`-rail4x`） | NavRail，能力选中 | 同上 4/4 完整 | 同上（四宫格选中态仍与折线区分明显） | 可辨 | 同上 |
| `wide/w04-rail-me.png`（+`-rail4x`） | NavRail，我的选中 | 同上 4/4 完整；人形头圆+肩弧闭合完整 | 同上 | 可辨 | 同上 |
| `wide/w07-rail80-medium.png`（+`-rail4x`） | Tier Medium，rail **80dp**，无底栏，我的选中 | 4/4 完整，无被 rail 边缘裁切 | 同上 | 可辨（底框+紫） | 图标居 rail 正中，label 居中，无遮挡 |

### 1.2 窄屏底栏三形态（`411dp`，图标 20dp）

| 图 | 位置 / 形态 | 渲染 | 可区分 | 选中态 | 对齐 / 遮挡 |
|---|---|---|---|---|---|
| `narrow/n00-launch.png` | 冷启动，Fixed 底栏，会话选中 | 4/4 完整 | 四个分得开 | 可辨：紫图标+紫半粗字（Fixed 形态**无**选中底块，见 §4-①） | 见测量：icon cx 与 label cx 逐项相等 |
| `narrow/n01-fixed-sessions.png`（+`-bar2x`） | Fixed，会话选中 | 4/4 完整 | 同上 | 可辨（颜色+字重） | icon x[113,157] y[2213,2253]；label y[2267,2294]；**无重叠无切字** |
| `narrow/n02-fixed-activity.png`（+`-bar2x`） | Fixed，活动选中 | 4/4 完整 | 同上 | 可辨 | 同上（几何完全一致） |
| `narrow/n03-fixed-capabilities.png`（+`-bar2x`） | Fixed，能力选中 | 4/4 完整 | 四宫格与折线在 1x 下仍一眼不同 | 可辨 | 同上 |
| `narrow/n04-fixed-me.png`（+`-bar2x`） | Fixed，我的选中 | 4/4 完整；人形肩弧两端止于图标框内（Tabler 原生开口，不是裁切） | 同上 | 可辨 | 同上 |
| `narrow/n05-float-me.png`（+`-bar3x`） | 浮动胶囊（无模糊），我的选中 | 4/4 完整，全部在胶囊内 | 同上 | **可辨**：12–15% accent 药丸底（实测 `#E5E5F7`）+ 紫图标 + 紫半粗字 | label 与图标同轴；选中药丸与胶囊同半径，末端几乎无白边（KernelSU 原几何，非缺陷） |
| `narrow/n06-float-sessions.png`（+`-bar3x`） | 同上，会话选中 | 4/4 完整 | 同上 | 可辨 | 同上 |
| `narrow/n07-float-activity.png`（+`-bar3x`） | 同上，活动选中 | 4/4 完整 | 同上 | 可辨 | 同上；**背后内容透出**（例如活动右侧透出一个下载图标），见 §4-③ |
| `narrow/n08-float-capabilities.png`（+`-bar3x`） | 同上，能力选中 | 4/4 完整 | 同上 | 可辨 | 同上；「下载队列」四个字从「会话」下方透出压字，见 §4-③ |
| `narrow/n09-blur-me.png`（+`-bar3x`） | 浮动胶囊（模糊），我的选中 | 4/4 完整，描边未被模糊涂抹 | 同上 | 可辨：玻璃指示器落在选中项下方 | 同上 |
| `narrow/n10-blur-sessions.png`（+`-bar3x`） | 同上，会话选中 | 4/4 完整 | 同上 | 可辨 | 同上 |
| `narrow/n11-blur-activity.png`（+`-bar3x`） | 同上，活动选中 | 4/4 完整 | 同上 | 可辨 | 同上 |
| `narrow/n12-blur-capabilities.png`（+`-bar3x`） | 同上，能力选中 | 4/4 完整 | 同上 | 可辨 | 同上 |

### 1.3 右面板（`≥1200dp` 才有入口）

| 图 | 位置 / 形态 | 渲染 | 可区分 | 选中态 | 对齐 / 遮挡 |
|---|---|---|---|---|---|
| `wide/w05-panel-closed.png`（+`w05-toggle-expand-7x.png`） | 顶栏右侧开合按钮，**关闭态** = `LayoutSidebarRightExpand` | 完整 stroke 图标：圆角矩形 + 右侧竖分隔 + **向左箭头 ◀**。不是空框 | 与打开态是**两枚相反箭头**的矢量 | n/a（按钮无选中态） | 44dp 按钮 `[2301,57][2367,123]`=66×66 px 居中，图标 20dp 不偏 |
| `wide/w06-panel-open.png`（+`w06-toggle-collapse-7x.png`、+`w06-panel-close-x-7x.png`） | 面板打开：开合按钮变 `LayoutSidebarRightCollapse`（**向右箭头 ▶**），面板头 `×` = `VadeIcons.Close` | 两枚都完整；`×` 是干净等长两笔，居中 | 开/合两态相反箭头；`×` 与开合按钮形状无关 | n/a | `×` 按钮 `[2322,51][2388,117]`=66×66 px=44dp；面板 320dp 不压 nav rail，顶栏右端让位 320+1 dp，无遮挡切口 |
| `sheets/sheet-four-icons.png` | 四图标 × [NavRail 未选中 / NavRail 选中 / 固定底栏 未选中 / 固定底栏 选中] 16 格 | **16/16 全部完整**，无空格 | 行间形状差别在放大后仍成立：`Chat` 有左下尾巴、`Activity` 是单折线、`Grid` 四圆角方、`Person` 头+肩弧 | 选中列整体转为 accent 紫 | 同格内居中；`Person` 墨迹比其它三行**明显更窄更轻**（见 §4-②） |
| `sheets/sheet-capsule.png` | 四图标 × [浮动胶囊 未选中 / 选中] 8 格 | 8/8 完整 | 同上 | 选中列带 `#E5E5F7` 药丸底 | 居中 |

**关于 Tabler 两态是否搞反**（唯一需要外部对证的一点）：上游 `layout-sidebar-right-expand` 的箭头 path 是 `M10 10l-2 2l2 2`（尖端 x=8，**朝左**），`…-collapse` 是 `M9 10l2 2l-2 2`（尖端 x=11，**朝右**）。渲染出来的「关闭态 ◀ / 打开态 ▶」与上游命名逐字一致，且语义自洽（能拉出来时箭头朝左，能推回去时朝右）。**没搞反。**

---

## 2. 像素级测量（不是目测，是数出来）

密度换算：宽屏 `240dpi → 1.5 px/dp`；窄屏 `420dpi → 2.625 px/dp`。

**图标墨迹尺寸（都装在 20dp 的方槽里）**

| 图标 | 宽屏 rail (px / dp) | 窄屏底栏 (px / dp) |
|---|---|---|
| `Chat` (MessageCircle) | 26×24 → **17.3×16.0 dp** | 45×41 → **17.1×15.6 dp** |
| `Activity` | 26×24 → **17.3×16.0 dp** | 45×41 → **17.1×15.6 dp** |
| `Grid` (LayoutGrid) | 24×24 → **16.0×16.0 dp** | 41×41 → **15.6×15.6 dp** |
| `Person` (User) | 18×26 → **12.0×17.3 dp** | 31×45 → **11.8×17.1 dp** |

- **尺寸一致性**：三者（Chat/Activity/Grid）墨迹宽 15.6–17.3 dp，差 ≤1.7 dp，同属 Tabler 24×24 网格的正常差异；`Person` 宽只有 ≈11.9 dp（Tabler `User` 画的是头圆 r=4 + 肩弧 `M6 21v-2a4 4 0 0 1 4-4h4a4 4 0 0 1 4 4v2`，横向只占 12/24），是**四者里最窄**的。
- **视觉重心**：四个图标的墨迹中心 `cy` **完全相同**（rail 全部 `cy=90.5/196.5/302.5/408.5`，底栏全部 `cy=2233.0`）→ 没有谁高谁低。
- **图标 vs label 对齐**：底栏四项 `icon cx = 135.0 / 405.0 / 675.0 / 945.0`，`label cx = 135.0 / 405.0 / 674.5 / 944.5` → **最大偏差 0.5 px（0.2 dp）**；rail 四项 `icon cx` 全为 54.5，`label cx` 53.5–54.0 → 偏差 ≤1 px（0.7 dp，CJK 字面本身不对称）。
- **遮挡/切字**：底栏图标带 `y[2211,2255]`、label `y[2267,2294]`，中间 12 px 空隙，label 高 28 px = 11sp 全字高 → 无压字、无切字；rail label 高 15 px = 10sp 全字高，同样完整。
- **选中态配色**：rail 选中底框 = `primary@0.16`（30dp 圆角），浮动胶囊选中药丸实测 `(229,229,247)` ≈ `#E5E5F7`（12–15% accent），两处与未选中（灰 `onSurfaceVariant`）对比度足够。

---

## 3. 语义（a11y 树，不是「代码里有」而是「设备上看得见」）

用 `adb shell uiautomator dump` 抓运行中的树，存在 `evidence/`：

| 树 | 位置 | `selected="true"` 节点数 | 说明 |
|---|---|---|---|
| `a11y-wide-rail-selected.xml` | 宽屏 NavRail（会话选中） | **1** | `[9,56][99,152]` = 会话那一格，`focusable=true`；其余 3 项 `selected=false` |
| `a11y-narrow-pill-selected.xml` | 窄屏浮动胶囊（我的选中） | **1** | `[740,2127][940,2273]` = 第 4 格（我的）；四个 `content-desc` 分别为 会话/活动/能力/我的 |
| `a11y-narrow-fixed-no-selected.xml` | 窄屏 **Fixed** 底栏 | **0** | 整棵树没有任何 `selected=true` → 见 §4-① |

`Role.Tab` 在 uiautomator 里落成 `class="android.view.View"`（framework 层角色串没体现），但 `selected` 两态是真的对读屏可见。

---

## 4. 问题（**只记录，未修**）与建议

**① Fixed 底栏没有 `selected` 语义 —— 而它是默认形态。**
`FixedBottomBarItem`（`BottomBar.kt:200`）只有 `.clickable`，没有 `NavRail`/`FloatingBottomBarItem` 那套 `.semantics(mergeDescendants=true){ selected; role=Role.Tab }`。`App()` 的 `initialBar` 默认就是 `BottomStyle.Fixed`，所以**默认手机形态**下选中态只活在颜色和字重里，读屏和 e2e 都断言不到（实测 `selected=true` 计数 0，与另两处的 1 形成对照）。
建议：给 `FixedBottomBarItem` 补同构的三行（`selected = …; role = Role.Tab`），与 `FloatingBottomBarItem` 逐字对齐；这是本棒唯一「改了会让语义三处一致」的点。

**② `Person` 视觉重量最轻。**
墨迹 11.9 dp 宽 vs 其余 15.6–17.3 dp（rail 下更明显：`User` 只有 18 px 宽，旁边四宫格 24 px）。它不是坏的，但在 20 dp 里读起来比另外三个「细一号」。
建议（可选，非缺陷）：若要四个等重，把 `VadeIcons.Person` 换成 `Tabler.Outline.UserCircle`（外圈+人形，视觉体积与 `Grid`/`Chat` 的包围面积接近）；或单独把 Person 提到 22 dp —— 但后者会破「四个同 20 dp」的一致性，不推荐。

**③ 浮动（无模糊）形态背后内容直接透出，压到图标/标签上。**
`FloatingBottomBar` 的 `isBlurEnabled=false` 分支 `containerColor = skin.colors.surfaceGlass`（半透明、不模糊）。`n07` 里「活动」右侧透出一个下载图标，`n08` 里「下载队列」四个字从「会话」下方透出。**不是 g-glyph-clear 引入的**（形态本身如此），但它正好落在本次要看的四个图标/标签上，削弱辨识。
建议：无模糊形态把容器改成不透明 `skin.colors.surface` 或把 `surfaceGlass` 的 alpha 提到 ≥0.9；模糊形态不受影响。

**④ 未选中项的 30 dp 底框与 rail 背景同色**（`NavRail.kt:80` 的 `else skin.colors.surfaceVariant` 等于 rail 底色）。这不是缺陷（本来也不该有框），但意味着选中态由「底色 + 颜色 + 字重」三件套共同承担；底框是唯一形状线索，色弱用户只剩颜色/字重两条。

---

## 5. 未取证项与原因

| 项 | 原因 |
|---|---|
| 右面板**内部**图标 | 面板内容只有「暂无模块页面」空态（C-19 冻结），没有可点的图标入口；能取证的只有顶栏开合按钮两态 + 面板头 `×` —— 已拍 |
| 皮肤 `miuix` / `glass` 下的导航 | 我的页自己写着「miuix / glass 是占位实现（返回 material token + 未安装角标）」，换过去是同一套 token，无新视觉信息 |
| 深色模式 | 本棒未要求；`main` 未改配色，只改了图标字形，色值由 skin 提供 |
| 桌面 / wasm 目标 | 本棒只走 Android 真机模拟器；桌面窗口捕捉被明令禁用（避免抢别的棒的窗口） |
| 真机 `192.168.1.180:5555` | 硬约束：全程不碰 |

---

## 6. 结论（一句话）

**四个新图标能用**：都完整渲染出来了（无空框/豆腐）、四者彼此一眼可分（`Grid` 与 `Activity`、`Chat` 与 `Person` 都不糊）、选中态在 rail 与两种胶囊形态下都一望即知、图标与 label 逐项对齐无遮挡切字 —— 唯一要补的是**固定底栏缺 `selected` 语义**（默认形态反而没有），`Person` 偏轻属可选优化。

---

## 附：文件与字节数（45 个）

| 文件 | 字节 |
|---|---|
| `wide/w01-rail-sessions.png` | 154,925 |
| `wide/w01-rail-sessions-rail4x.png` | 107,654 |
| `wide/w02-rail-activity.png` | 156,343 |
| `wide/w02-rail-activity-rail4x.png` | 107,604 |
| `wide/w03-rail-capabilities.png` | 140,265 |
| `wide/w03-rail-capabilities-rail4x.png` | 107,335 |
| `wide/w04-rail-me.png` | 167,402 |
| `wide/w04-rail-me-rail4x.png` | 107,667 |
| `wide/w05-panel-closed.png` | 154,621 |
| `wide/w05-toggle-expand-7x.png` | 29,239 |
| `wide/w06-panel-open.png` | 192,650 |
| `wide/w06-toggle-collapse-7x.png` | 29,213 |
| `wide/w06-panel-close-x-7x.png` | 17,883 |
| `wide/w07-rail80-medium.png` | 153,310 |
| `wide/w07-rail80-medium-rail4x.png` | 107,350 |
| `narrow/n00-launch.png` | 254,627 |
| `narrow/n01-fixed-sessions.png` | 253,422 |
| `narrow/n01-fixed-sessions-bar2x.png` | 73,606 |
| `narrow/n02-fixed-activity.png` | 198,727 |
| `narrow/n02-fixed-activity-bar2x.png` | 101,804 |
| `narrow/n03-fixed-capabilities.png` | 197,817 |
| `narrow/n03-fixed-capabilities-bar2x.png` | 89,614 |
| `narrow/n04-fixed-me.png` | 250,956 |
| `narrow/n04-fixed-me-bar2x.png` | 73,421 |
| `narrow/n05-float-me.png` | 263,019 |
| `narrow/n05-float-me-bar3x.png` | 173,682 |
| `narrow/n06-float-sessions.png` | 263,473 |
| `narrow/n06-float-sessions-bar3x.png` | 174,420 |
| `narrow/n07-float-activity.png` | 207,666 |
| `narrow/n07-float-activity-bar3x.png` | 212,864 |
| `narrow/n08-float-capabilities.png` | 203,003 |
| `narrow/n08-float-capabilities-bar3x.png` | 207,871 |
| `narrow/n09-blur-me.png` | 262,483 |
| `narrow/n09-blur-me-bar3x.png` | 166,345 |
| `narrow/n10-blur-sessions.png` | 262,165 |
| `narrow/n10-blur-sessions-bar3x.png` | 170,020 |
| `narrow/n11-blur-activity.png` | 209,692 |
| `narrow/n11-blur-activity-bar3x.png` | 191,332 |
| `narrow/n12-blur-capabilities.png` | 207,303 |
| `narrow/n12-blur-capabilities-bar3x.png` | 189,902 |
| `sheets/sheet-four-icons.png` | 211,589 |
| `sheets/sheet-capsule.png` | 141,753 |
| `evidence/a11y-wide-rail-selected.xml` | 41,562 |
| `evidence/a11y-narrow-pill-selected.xml` | 24,215 |
| `evidence/a11y-narrow-fixed-no-selected.xml` | 32,603 |
