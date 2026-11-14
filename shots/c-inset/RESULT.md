# c-inset · Android 安全区修复（手势条压底栏）

分支 `tmp/c-inset`（基 main @ f189de2）。代码 commit：**5459a2a**。本报告随 shots 一起提交。
**不 push、不合并。**

## 根因（一句话）

`MainActivity` 只有 `setContent { App() }`，没有任何 edge-to-edge/insets 处理；API 35+
强制 edge-to-edge（`setDecorFitsSystemWindows(true)` 被忽略），窗口画到系统栏下面而
Compose 侧没人垫安全区 → API 37 手势条压住 Fixed 底栏标签、顶栏被状态栏压；API 30
decor 还代 fit，所以真机三键导航看不出来。桌面/Wasm 没有系统栏，此 bug 三端只有
Android 真机暴露。

## 选型与代价

**选了推荐路线：`MainActivity.enableEdgeToEdge()` + insets 在 Compose 侧应用。**

- `enableEdgeToEdge()` 在所有 API 统一生效 → inset 语义全平台一致（不再依赖
  「API<35 被 decor 消费所以读 0」的隐式行为），API 30 三键导航也拿到真实 inset。
- 状态栏/键盘内边距放在 `MainActivity` 的根 Box（`statusBarsPadding()` +
  `imePadding()`）—— 该文件只在 Android 运行，桌面/Wasm 布局**按构造就不受影响**。
- 底栏 inset 在 `BottomBar.kt`（共用层）读 `WindowInsets.navigationBars`：
  CMP 的桌面/Wasm actual 报 0 → 桌面像素不变；Android 拿到真实手势/三键 inset。
  Fixed 形态 = KSU `BlurredBar` 语义：玻璃面延伸进 inset 区、标签行垫在 inset 之上
  （玻璃继续覆盖系统条区域，字在安全区内）。Floating 胶囊沿用既有
  `floatingBarBottomMargin()`（`8dp+inset`，桌面 0→28dp 分支不变），未改
  FloatingBottomBar.kt。
- `SystemBarStyle` 强制 light（深色图标）：主题 `Theme.Material.Light` + 默认 material
  皮肤都是浅色；`auto` 会跟随**系统**夜间模式，深色模式的设备上会把浅图标铺在浅底上。

**代价（如实列出）：**

1. API 30 真机的三处视觉变化（都是 e2e 统一化的直接结果）：
   - Floating 胶囊底边距从「28dp（inset 被 decor 消费时的 fallback）」变为 KSU 语义的
     **8dp+inset**（≈8dp 悬在三键条上沿）——这正是该函数注释里写的本意，旧渲染是
     消费行为造成的偏差；
   - 三键导航条区域不再是系统黑色条，而是**玻璃底 + 深色按键**（系统按键画在我们的
     染色玻璃上）；
   - 状态栏区域是窗口背景色（白），不再是系统主题色条。
2. IME：e2e 后窗口不再被键盘顶起，`imePadding()` 补回「窗口整体缩到键盘上方」的
   adjustResize 等价行为（旧 API 30 行为的保持 + API 37 强制 e2e 下的修复）。
3. 几何余量：Fixed 栏总高 = 1dp 发丝线 + 64dp + inset；AppShell 的
   `BOTTOM_BAR_ZONE=88dp` 内容垫不变 → 全滚到底时最后一行与发丝线有 ~1dp 相触的
   余量差（AppShell 不在本批可改范围；视觉上不可辨，未处理）。
4. 状态栏图标风格按浅色皮肤定死 light（深色图标）；若将来默认皮肤切深色，需要跟着
   切 `SystemBarStyle.dark`（本批不动皮肤系统）。
5. 顶栏是「整体下移到状态栏下方」，状态栏条本身不透出滚动内容（AppShell 的
   TOP_BAR_ZONE/top padding 不可改；全出血玻璃顶栏要等 AppShell 批次）。

## 改动文件（4 个，全部新增/修改都在白名单或单列说明）

| 文件 | 变更 |
|---|---|
| `composeApp/src/androidMain/kotlin/vade/ui/MainActivity.kt` | 改：enableEdgeToEdge + 根部 statusBars/ime padding |
| `composeApp/src/commonMain/kotlin/vade/ui/shell/BottomBar.kt` | 改：Fixed 玻璃面延伸 inset；`floatingBarBottomMargin` 拆出纯函数 |
| `composeApp/src/desktopTest/kotlin/vade/ui/InsetsGuardTest.kt` | **新增（单列）**：防回退测试。白名单未列 desktopTest，但任务书第 4 条要求可测的防回退断言，只能落在 desktopTest；纯新增文件，未动任何既有测试/断言 |
| `shots/c-inset/**` | 新增：截图 + 本报告 |

未改：AppShell.kt、FloatingBottomBar.kt（floating 分支的 inset 处理已在既有代码中，
无需改）、App.kt、chat/**、destinations/**、data/**、protocol/**、skin/**、
build.gradle.kts、版本矩阵。

## 防回退测试（desktopTest）

`InsetsGuardTest`（3 条，全绿）：

- `noNavInsetFallsBackToKsuMargin`：`floatingBarBottomMargin(0.dp) == 28.dp`；
- `navInsetRidesOnTheEightDpMargin`：`24.dp→32.dp`（手势条）、`48.dp→56.dp`（三键条）；
- `desktopSystemBarInsetsReadZeroInARealWindow`：**真 ComposeWindow**（隐藏窗口）里
  断言 `WindowInsets.statusBars` 顶部 / `WindowInsets.navigationBars` 底部 padding
  均为 `0.dp`。走真窗口是因为 insets 的 CompositionLocal（`LocalPlatformWindowInsets`）
  是 internal API，裸 composition 无法提供（实测直接抛
  `CompositionLocal LocalPlatformWindowInsets not present`，改用真窗口）。

**诚实边界**：Android 侧 inset 非零无法用 JVM 单测覆盖（需要真窗口/真设备），证据是
下面的设备截图 + 像素量测；wasm actual 属同一 expect/actual 组，但 JVM 测试跑不到，
未运行时验证。

## 构建 / 测试原始数字

- `gradle :composeApp:assembleDebug`：BUILD SUCCESSFUL（45s），APK
  `composeApp/build/outputs/apk/debug/composeApp-debug.apk`，**21,534,706 bytes**
  （2026-11-14 19:30，md5 `c7558f5b1da8ac27ead691d1c3fe7daf`）。
- `gradle :composeApp:desktopTest --rerun`：**tests=157 failures=0 errors=0 skipped=1**
  （157 = 基线 154 + 新增 3；skipped 是既有
  `VadeClientSmokeTest.attachSubmitAndLiveEventsAgainstARealCore`，条件跳过：
  需要 `VADE_SMOKE_PORT`，与本批无关）。
- `gradle :composeApp:desktopMainClasses`：BUILD SUCCESSFUL（5s）。

## 设备验收（adb screencap，非窗口截图）

安装核验：两台设备 `install -r` 后都 pull `pm path` 的 base.apk 回来对 md5（与磁盘
APK 一致才继续）。**发现并排掉一个坑**：第一次 install 报 Success 后几十秒内被并行
批次用旧构建覆盖（设备上的 dex 无 `navigationBarBottomInset`/`SystemBarStyle` 符号，
首组截图因此全部作废重拍）；后续每次截图前后都拉 md5 双向核验。

| 文件 | 设备 | 系统/导航 | 尺寸 | 字节数 | 模式 |
|---|---|---|---|---|---|
| `api37-fixed.png` | emulator-5554 | API 37 / 手势 | 1080×2400 | 247,174 | Fixed |
| `api37-floating.png` | emulator-5554 | API 37 / 手势 | 1080×2400 | 266,679 | Floating |
| `api37-floating-blur.png` | emulator-5554 | API 37 / 手势 | 1080×2400 | 267,554 | FloatingBlur |
| `api30-fixed.png` | 192.168.1.180:5555 | API 30 / 三键 | 1080×2400 | 252,918 | Fixed |
| `api30-floating.png` | 192.168.1.180:5555 | API 30 / 三键 | 1080×2400 | 275,361 | Floating |

crash buffer：两台设备 `logcat -d -b crash` 中 vade.ui 命中 0 条（模拟器上唯一一条
FATAL 是 `com.termux` 的既有崩溃，与本应用无关）。

### 看图结论（逐张看过 + 像素量测，非目测估计）

**api37-fixed.png（API 37 / 手势，本 bug 现场）**
- 发丝线（玻璃栏顶）y=2166-2168；图标带 y=2212-2253；标签带 y=2268-2293；
  系统手势条 y=2364-2373（灰 (100,100,100)，x≈400-680 居中）。
- 标签底 2293 → 手势条顶 2364：**71px（≈27dp）间隙，无重叠、无压字**。
- 玻璃面 2169-2400（231px = 64dp + 63px inset），inset 已计入；手势条落在玻璃延展区。
- 顶部：时钟/电量在 y≈10-45，玻璃卡片顶 ≈84px（8dp 垫 + 63px 状态栏 inset），
  **分离无重叠**；状态栏图标为深色（SystemBarStyle.light 生效）。
- 对照修复前（父代现场证据）：手势条紧贴「活动」底部并横压标签 → 修复后标签整体
  上移 63px（一个 inset 的高度）。

**api30-fixed.png（API 30 / 三键真机）**
- 图标带 y=2117-2184；标签带 y=2192-2220；三键按键带 y=2314-2354。
- 标签底 2220 → 按键带顶 2314：**94px 间隙**；三键区背后是玻璃染色（浅底深键），
  不是黑色系统条（e2e 后的新形态，见代价 1）。
- 顶部状态栏与玻璃卡片分离，渲染正常。

**api37-floating.png（API 37 / 手势）**
- 浮动胶囊（会话/活动/能力/我的，选中态高亮）完整渲染；胶囊底边 ≈2316
  （= 2400 − (8+24)dp×2.625），手势条 2364-2373 → **约 48px 间隙，不重叠**。
- 截图自「我的」页，floating chip 处于选中态（accent (91,91,214)）。

**api30-floating.png（API 30 / 三键真机）**
- 胶囊 y≈2086-2256（= 56dp 边距 + 64dp 胶囊，三键 inset 126px 计入），
  三键按键 ≈2320-2370 → **约 64px 间隙**；floating chip 选中态可见。

**api37-floating-blur.png（API 37 / 手势）**
- floating+blur chip 选中；胶囊带真模糊管线渲染，无黑块/破碎；与手势条不重叠。

## 遗留 / 未覆盖

- AppShell 的 `BOTTOM_BAR_ZONE=88dp` 不含 inset（代价 3 的 1dp 余量差）；真正
  「内容垫 = bar 实高」要对 AppShell 动刀，留给 AppShell 批次。
- 深色皮肤下的状态栏图标风格（代价 4）。
- wasm 侧 actual 的运行时验证（同一 expect/actual 组，未跑浏览器环境）。
- 并行批次共用两台设备：模拟器曾两次被其它批次重装/导航（详见上文安装核验），
  本批最终证据均在「md5 前后双核验 = 本批构建」的窗口内取得。
