# g-topglass · 「内容漏到状态栏条带、清晰可读」验收与取证

分支 `tmp/g-topglass`（基于 main @ `c95c558`），代码 commit：**`da4b715`**。

## 一句话结论（对任务书前提的纠正）

**在当前基线 c95c558 上无法复现该泄漏**：状态栏条带（y=0..63px）里没有任何 app 文字，
只有系统图标；数值上它是一块**纯色**（variance 1.1，edge energy 0.05），满足任务书
验收判据「纯色（方差≈0）」。父代看到的可读文字（「gradle.properties 还是不行。」）
是真实的，但来自 **c-inset（`5459a2a`，enableEdgeToEdge + 根布局 `statusBarsPadding()`）
合并之前**的构建 —— 见下节时间线。因此本次**没有改布局几何**，交付的是：复现取证 +
几何常量提取 + 7 条回归钉测试 + 变异验证。

## 复现与时间线证据

| 证据 | 内容 |
|---|---|
| g-glass 自报基线 | 其 RESULT.md 明写「分支 `tmp/g-glass`（基于 `2cf917b`）」 |
| 截图文件时间 | `shots/g-glass/before-detail-swipe.png` 19:18、`b2-detail-tap/scroll.png` 19:48（文件 mtime） |
| c-inset 合并时间 | `5459a2a` 提交于 **20:00**（statusBarsPadding 进主干，经 `b6f87ba` 20:09 合入 main） |
| 结论 | g-glass 的截图全部摄于 **19:18–19:48**，早于 20:00 —— 它装在模拟器上的 APK 没有根布局 inset，app 全出血画到状态栏底下，消息文字自然清晰漏出 |

本棒实装复测（两轮构建都做了 `pm path` + pull + md5 双侧核对）：

- 第一轮（重构前，= c95c558 语义）：APK 22,026,344 B，md5 `7f0c092aace88e5d0b106df7ee9d4908`，本地与设备 pull 一致；
- 第二轮（最终提交态）：APK 22,165,987 B，md5 `72f0c064205da370dec15ebe72aafeb7`，本地与设备 pull 一致；`vade.shell.topbar.glass` 字符串实测在 `classes12.dex` 内（防旧包冒充）。

复现路径：`adb shell am start -n vade.ui/.MainActivity` → 点开 RFC-0004 会话 → 三次
上滑把旧消息滚到顶栏底下 → `screencap`。裁切顶部 300px 放大 2 倍逐张开图核对。

## 方向选择：B-as-shipped（根布局单点消费 inset = 条带天然是纯色），A 不可行

- **A（玻璃盖到屏幕顶端）在本约束下几何不可行**：`MainActivity` 根 Box 的
  `statusBarsPadding()` 把整棵树（含 haze 源 backdrop Row）垫到 inset 之下； padding
  内的节点画不到 y<63px。若强行为条带加一条玻璃，它背后**没有可采样的内容**
  （backdrop 也从 63px 才开始），等于给纯色加玻璃。真 A 必须「拆根布局的 inset、
  改由 shell 统一处理并给每个页面/浮层补垫」，任务书明令禁止（「不要为了这个把根
  布局的 inset 拆掉」），且要动全部 11 个面 —— 收益纯视觉，风险全量，不做。
- **B 已经由 c-inset 事实交付**：根布局消费 inset 后，app 在条带内**没有可绘制表面**
  （滚动容器裁剪在 inset 处），条带=窗口底色（浅色主题下与 canvas 同族、无接缝）。
  代价即任务书为 B 写明的：那一块是纯色、无玻璃感。
- 遗留跟进（记入 §遗留，不在本棒动）：暗色主题下窗口底色(白)会与暗 canvas 冲突；
  届时在根 Box 补 `background(canvas)` 一行即可（androidMain），当前 app 无可达暗色
  （`cmd uimode night` = no，`isDark` 无运行时出口，dark tokens 属 U3）。

## 锐利度数值（判据 §3）

度量：相邻像素 luma 差平方均值（水平+垂直）+ 最大局部对比度，L=0.299R+0.587G+0.114B。
区域：**x=250..780, y=0..63**（条带中段；剔除左侧时钟 x<250 与右侧 5G/信号/电池 x>780 ——
首轮用 x=250..830 时 max_contrast=250 是把「5G」系统文字扫进来了，属度量区域选取失误，
已收紧；状态栏 inset 实测：图标行占 y≈10..55px，`wm density`=420 → 24dp≈63px）。

| 帧 | edge_energy_h | edge_energy_v | total | max_local_contrast | luma_mean | variance |
|---|---|---|---|---|---|---|
| **修前**（g-glass 证据 `b2-detail-scroll.png`，pre-c-inset 构建） | 1098.84 | 989.90 | **2088.74** | **243.0** | 219.6 | 3405.5 |
| **修后**（`final-detail.png`，未滚动） | 0.0 | 0.05 | **0.05** | **1.0** | 249.1 | **1.1** |
| **修后**（`final-detail-scrolled.png`，滚动后） | 0.0 | 0.05 | **0.05** | **1.0** | 249.1 | **1.1** |
| 方法对照：同帧胶囊区 y=100..200（`rep-03-detail-scrolled.png`） | 1002.43 | 1043.74 | 2046.17 | 215.8 | 205.8 | 4152.0 |

（对照行说明度量管线没有失灵：胶囊区里的锐利前景标题测出高能量，而条带测出 ≈0。
修后两帧条带数值逐位相同是因为该区域除系统图标外是同一段窗口底色像素。）

**判读**：修后 = 纯色（variance 1.1 ≈ 0，max contrast 1.0 为量化噪声级），无任何细笔画
边缘；修前 2088.74/243.0 即任务书描述的「明显锐利字形边缘」。

## 截图清单（全部 `adb exec-out screencap -p`，1080×2400，emulator-5554 / API 37）

| 文件 | 尺寸 | 字节 | 内容 |
|---|---|---|---|
| `rep-01-launch.png` | 1080×2400 | 248,325 | 第一轮构建启动：条带干净，列表正常 |
| `rep-02-detail.png` | 1080×2400 | 300,361 | 进 RFC-0004 详情 |
| `rep-03-detail-scrolled.png` | 1080×2400 | 350,106 | 滚动后：条带干净 + 胶囊内模糊残影 |
| `final-detail.png` | 1080×2400 | 300,022 | **最终提交态构建**：详情未滚动 |
| `final-detail-scrolled.png` | 1080×2400 | 349,750 | **最终提交态构建**：滚动后，修后度量的取数帧 |
| `pre-crop-top300.png` | 2160×600（2×） | 83,322 | 修前证据 PNG 顶部 300px 裁切放大：y=0..45 清晰可读「gradle.properties 还是不行。」压在状态栏上 |
| `post-crop-top300.png` | 2160×600（2×） | 73,877 | 第一轮构建顶部 300px：条带无 app 文字 |
| `final-crop-top300.png` | 2160×600（2×） | 73,487 | 最终构建顶部 300px：条带无 app 文字，胶囊内残影为糊、标题锐利 |
| `ghost-zoom-ben.png` | 750×450（5×） | 9,630 | 胶囊残影 5× 放大：笔画边缘发虚晕开 = 真高斯模糊（非低透明度锐利字），前景「RFC」标题锐利 |
| `ghost-zoom-gong.png` | 800×350（5×） | 5,258 | 右侧残影同上 |
| `strip_metric.py` | — | 2,161 | 度量脚本（区域、公式可复核） |

## 代码改动（0 行几何变化）

- `shell/AppShell.kt`：`TOP_BAR_ZONE`/`BOTTOM_BAR_ZONE` private→internal；新增
  `TopBarTopGap=8dp` 并替换玻璃 Row 的内联 `top = 8.dp`。KDoc 写死契约：**状态栏
  inset 不是这些数的项**（根布局消费一次；shell 再垫 = 双计；桌面/Wasm 读 0）。
- `shell/TopBar.kt`：`TopBarHeight=56dp` 常量化；`TopBarGlassTag` testTag 挂在
  GlassSurface 上（供几何测试取节点）。
- `desktopTest/shell/TopGlassGuardTest.kt`：新增 7 条（见下）。
- 禁改目录零改动；`git show --stat da4b715` 仅上述 3 文件（+255/−5）。

## 测试（7 条，任务书 ①–⑥ 映射如实标注）

| # | 测试 | 钉住什么 |
|---|---|---|
| 1 | `statusBarInsetIsConsumedExactlyOnceAtTheAndroidRoot` | MainActivity 恰好 1 处 `statusBarsPadding()`、有 `enableEdgeToEdge`、**无** `navigationBarsPadding`（任务书①的 Android 面 + ⑤ 单点消费） |
| 2 | `shellNeverAddsASecondStatusBarInset` | shell 全目录（剔除注释后）零 `statusBarsPadding`/`WindowInsets.statusBars`；BottomBar 恰 1 处 `WindowInsets.navigationBars`（⑤ 不双计 + ④ 顶/底分工对称） |
| 3 | `detailThreadIsContentPaddedNotContainerPadded` | `contentPadding = PaddingValues(top = TOP_BAR_ZONE)` 恰 3 处（pushed+双栏两处）、`contentPadding = contentInsets` 恰 4 处、容器形态 `padding(top = TOP_BAR_ZONE)` 为 0（⑥ 内容可滚到玻璃下 + g-glass 修复回归钉） |
| 4 | `topBarZoneArithmeticIsInsetFree` | `TOP_BAR_ZONE=72 / TopBarTopGap=8 / TopBarHeight=56`、`TopBarTopGap+TopBarHeight < TOP_BAR_ZONE`、`BOTTOM_BAR_ZONE=88`（② 无 inset 桌面几何不变 + ④ 对称 zone） |
| 5 | `desktopTopBarRendersFlushWithTheWindowTop` | 真渲染：胶囊 `boundsInRoot.top==0dp`、`height==56dp`（② 的渲染面） |
| 6 | `topBarTitleDrawsInsideTheCapsule` | 标题 bounds 在胶囊 bounds 内（同时证明 testTag 贴在胶囊上） |
| 7 | `insetConsumerWhitelistIsUnchanged` | 全仓 inset 消费白名单：MainActivity(statusBars×1, ime×1)、BottomBar(navBars×1)、ChatThread(ime×1)（③ 的几何断言面；任务书③允许「几何断言+截图数值代替」，Android 条带像素证据=上表数字） |

**变异验证**：`TOP_BAR_ZONE 72→88` → `topBarZoneArithmeticIsInsetFree` 精确变红
（`expected:<72.0.dp> but was:<88.0.dp>`，7 条中恰 1 红），还原后 `grep 88 = 0`、
diff 无残留。

## 三目标门禁（原始数字）

| 门禁 | 结果 |
|---|---|
| `:composeApp:desktopTest --rerun` | **499 tests / 0 failures / 0 errors / 1 skipped**（XML 汇总；基线 492 + 新增 7 = 499，1 skip 为既有） |
| `:composeApp:assembleDebug` | BUILD SUCCESSFUL（41s，与 compileKotlinWasmJs 同一条命令）；APK 22,165,987 B、md5 `72f0c064…`、marker 字符串在 classes12.dex |
| `:composeApp:compileKotlinWasmJs` | 同上 BUILD SUCCESSFUL，无 `e:` 编译错 |
| commonMain 可移植性 | 本棒 diff 无 `Math.`/`String.format`/`java.*`/裸 `@Volatile`（shell/glass 扫描为空；既有 `SessionsLoader.kt` 为 `kotlin.concurrent.Volatile`） |

**如实记录的非本棒因素**：
1. 首次全量红 3 条 —— `VadeContractCoverageGuardTest` 报 `contracts/vade/schema/*.json
   not found`：**worktree 未初始化 submodule**（环境问题，与代码无关）。已
   `git submodule update --init`（钉在 `320384ae`），随后的全量为绿。
2. 第二次全量红 1 条 —— `UiStateSourceWiringTest.staleMessageRequestNeverOverwritesNewSession`
   `expected:<[a, b]> but was:<[b, a]>`：该类自述用「wall-clock waitUntil 轮询」，
   且历史上已有同类时序修（`1ea3631`）。单独 `--rerun` 该类连跑 3 次（20 tests,
   0 failures ×3）后**全量第三次 = 499/0/0/1 绿**。本棒未改该文件（禁改+非我引入），
   建议父代知悉其负载下偶发。

## 遗留观察（不属本棒，供父代裁量）

1. **胶囊内残影的 5× 形态为真模糊**（ghost-zoom 图），但 24dp 半径下短字符串
   （「本会」「工具卡」）放大后可半猜 —— 与 g-glass 自报「能看出有字、读不出内容」
   一致，是否要加大半径/加深染色是产品决策，本棒未动（GlassSurface 禁改范围外为
   skin/glass/**，可改但无任务书授权）。
2. **暗色主题的条带色**：当前条带=窗口底色（白），浅色主题与 canvas 无接缝；U3 暗
   色落地时需在根 Box 补 canvas 背景（一行，androidMain），否则暗 canvas 上方是一条白带。
3. 真机（192.168.1.180:5555）本棒全程未触碰，所有 adb 命令均 `-s emulator-5554`。
