# g-dark-follow · 暗色主题接上系统入口

批次：`tmp/g-dark-follow`（基点 `origin/main@53625dc`）· 2026-11-15
执行：g-dark-follow 棒（子代理）

## 一、做了什么（机制）

审计（`shots/g-audit-gaps`）证实：暗色 token 两套都在（`MaterialTokens.darkColors` /
`MiuixTokens.darkColors`），但 Android 执行 `cmd uimode night yes` 后应用区逐像素无变化——
暗色在 Android **没有入口**，桌面靠 `--dark` 启动期先验，Web 无任何路径。本批把入口接上：

1. **跟随系统（默认）**——`skin/ThemeMode.kt`（commonMain）声明
   `@Composable expect fun systemDarkTheme(): Boolean`，三端 actual：
   - `androidMain`：委托 `androidx.compose.foundation.isSystemInDarkTheme()`（读
     `LocalConfiguration` 的 `Configuration.UI_MODE_NIGHT_YES` 掩码）。manifest 早已声明
     `configChanges=…|uiMode|…`，所以 uiMode 变化走 `onConfigurationChanged` →
     Compose 更新 configuration 状态 → **即时生效、不重建 Activity**（`ui.aar` 实查：
     `AndroidComposeView.onConfigurationChanged` → `updateConfiguration` →
     `configuration` 是 `mutableStateOf`）。
   - `desktopMain`：委托 foundation 桌面 actual（`foundation-desktop-1.12.0` jar 实查
     `_isSystemInDarkTheme` 存在）；取不到返回 false = 保持亮色现行为。
   - `wasmJsMain`：读 `prefers-color-scheme`（CMP 1.12 foundation-wasm-js klib **没有**
     isSystemInDarkTheme 实现，2026-11-15 实查 0 命中，所以必须自己接 `matchMedia`），
     顶层同步初始化（首帧即正确值）+ `change` 监听即时生效。
2. **手动覆盖**——`ThemeController.override`（跟随系统/亮/暗），入口在设置页
   「我的 → 外观 → 主题」（`ThemeChip` ×3，与 SkinChip/BarChip 同形态），手选优先于系统；
   默认 = 跟随系统（由守卫测试钉住源码）。覆盖是进程内状态，与皮肤/底栏选择同级
   （D98：UI 不做持久化），重启回到跟随系统。
3. **切换即时生效且不重建整棵树**——`App.kt` 唯一接线点（一行）：

   ```kotlin
   val skin = remember(state.skinId, vade.ui.skin.ThemeController.currentDark()) { SkinRegistry.get(state.skinId) }
   ```

   `currentDark()` 在组合期：读平台观测 → **先**同步两个皮肤工厂闭包读的 plain var
   （`MiuixSkinEntry.dark` / `MaterialSkinEntry.dark`——参数求值先于 remember 计算，
   同一帧内工厂拿到新值）→ 返回解析结果作 remember key。主题变化 = 只换
   `LocalSkin` 的值（与「皮肤切换运行时生效」同一条缝，任务书 §T5）：`UiState`
   （当前会话 id）、滚动位置、搜索词都是 `remember` 出来的对象，原样保留。
4. **commonMain 红线**——平台 API 全部走 expect/actual；新增
   `CommonMainPlatformGuardTest`：剥注释/字符串字面量后扫 `java.*`/`javax.*`/`android.*`
   （剥注释是必须的：基线注释里本来就有「android.graphics.BlurMaskFilter」「无 java.time」
   这类说明文字；`\bandroid\.` 不会误伤 `androidx.`）。

## 二、改动清单

| 文件 | 改动 |
|---|---|
| `commonMain/.../skin/ThemeMode.kt` | **新建**：`ThemeMode` 枚举 + `ThemeController`（override / systemDark / resolveDark / currentDark）+ expect 声明 |
| `androidMain/.../skin/ThemeMode.android.kt` | **新建**：actual = `isSystemInDarkTheme()` |
| `desktopMain/.../skin/ThemeMode.desktop.kt` | **新建**：actual = foundation 桌面探测 |
| `wasmJsMain/.../skin/ThemeMode.wasmJs.kt` | **新建**：actual = `matchMedia('(prefers-color-scheme: dark)')` + change 监听 |
| `commonMain/.../App.kt` | **一行**：skin 的 remember 增加 `ThemeController.currentDark()` key |
| `commonMain/.../destinations/ProfileScreen.kt` | 外观分区加「主题」一行（三态 chips + 说明文案 + `ThemeChip`） |
| `desktopMain/.../Main.kt` | **越界单列**：`--dark`/`VADE_DARK=1` 从直写皮肤工厂 var 改为 `ThemeController.override = Dark`；否则 FollowSystem。不改这行则 `--dark` 会被解析覆盖而失效（任务书要求「桌面跟随现有 --dark 旗标」，旗标处理就在此文件）；showcase 分支 `MiuixSkin(dark)` 未动 |
| `desktopTest/.../skin/ThemeModeTest.kt` | **新建**：三态解析规则（系统暗→暗 / 系统亮→亮 / 手选覆盖优先）+ App 接线钉子 + 亮色 token golden（material/miuix 逐槽） |
| `desktopTest/.../CommonMainPlatformGuardTest.kt` | **新建**：commonMain 平台 API 守卫（剥注释/字符串）+ 默认值钉源码 |
| `desktopTest/.../ThemeSwitchUiTest.kt` | **新建**：真组合验证「切换不重建」 |
| `shots/g-dark-follow/*` | 设备帧 + 像素比对脚本 + 本报告 |

**未动**（红线核对）：`chat/**`、`shell/AppShell.kt`、`shell/TopBar.kt`、`module/**`、
`data/**`、`protocol/**`、`build.gradle.kts`（零新依赖）、`contracts/**`。

## 三、证据

### 3.1 门禁（三目标，最终代码上重跑）

```
:composeApp:desktopTest --rerun   → BUILD SUCCESSFUL in 2m 6s
  tests=1222 failures=0 errors=0 skipped=1   （含本批新增 12 条）
:composeApp:assembleDebug         → BUILD SUCCESSFUL（apk md5 c5d86a83258a9377b77d1e291e16bec4）
:composeApp:compileKotlinWasmJs   → BUILD SUCCESSFUL
```

（`skipped=1` 为既有跳过项，与本批无关；gradle 9.6.1 绝对路径，工作目录 = 本 worktree。）

### 3.2 测试覆盖（任务书测试清单逐条）

| 要求 | 落点 |
|---|---|
| 系统暗 → 解析为暗；系统亮 → 亮；手选暗 → 暗（覆盖优先） | `ThemeModeTest` 四条解析规则（+手选亮覆盖系统暗） |
| 切换不重建会话（当前会话 id / 滚动位置不变） | `ThemeSwitchUiTest`：真组合 466dp 手机视口，点开另一会话 → 手选暗 → 列表行不回来 + 详情仍在；滚动到最远行 → 手选暗 → 远行仍在视口、首行不回。判据设计：若整棵树重建，`UiState`/`rememberLazyListState` 是新对象，断言必红 |
| commonMain 无平台 API（`isSystemInDarkTheme` 以外不得出现 `java.*`/`android.*`） | `CommonMainPlatformGuardTest.commonMainHasNoPlatformPackagesOutsideCommentsAndStrings`（剥注释/字符串后 0 命中，扫描域 >100 文件防退化） |
| 亮色 token 与改前一致（对比断言） | `ThemeModeTest.materialLightTokensAreUnchanged` / `miuixLightTokensAreUnchanged`：§2 亮色表逐槽钉值 + 既有 `MaterialSkinDarkTest` 全绿；miuix 的 success/warning 两槽是**改前现状**（默认回落 primary/error，状态条读 `MiuixTokens.success()/warn()` 函数），照实钉住并注明 |

### 3.3 设备验证（帧全部人工开图核对过，不凭字节数）

任务书指定 `emulator-5554`；该实例在本批验证期间被**并发用户占用**
（logcat 实证：`03:25:49 / 03:27:19 packageupdate` 安装 + `03:25:53 / 03:28:15`
带 extras 的 `am start` + `night yes/no/auto` 序列——不是本棒的命令），首跑帧被污染。
处置：按 skill 配方**自建独立实例** `vade-api37-b`（`emulator-5556`，同
`system-images;android-37.0;google_apis;x86_64`，pixel_6，1080×2400@420dpi），
全部验证在该实例完成，**未触碰真机 `192.168.1.180:5555`**。基线帧（b1/b2）摄于
污染开始前的 5554（干净，已核图）。

| 帧 | 内容 |
|---|---|
| `b1-base-light.png` / `b2-base-night.png` | **基线 APK（53625dc）**：night yes 前后逐字节一致（md5 同）——复现审计「基线无暗色入口」 |
| `f1-mine-light-sessions.png` | 本批 APK：会话页·亮（night no） |
| `f2-mine-light-capabilities.png` | 能力页·亮（uiautomator 确认页面后拍） |
| `f3-mine-dark-capabilities.png` | 能力页·**暗**（`night yes` 后同会话内即时生效） |
| `f4-mine-dark-sessions.png` | 会话页·暗 |
| `f5-mine-light-sessions-roundtrip.png` | `night no` 后·亮（往返还原） |
| `f6-mine-light-profile-themerow.png` | 设置页·亮：**「主题」一行可见，跟随系统选中态高亮** |
| `f7-mine-dark-activity.png` | 活动页·暗（tap 响应正常 = 切换后 app 可交互） |
| `probe-now-night-yes.png` | 会话页·暗（与 f1 同页成对：切换前后） |

运行时证据（临时 Log.d，已从提交代码撤除）：组合期 `systemDarkTheme` 输出
`dark=false uiMode=0x11`（launch，night no）→ `dark=true uiMode=0x21`（night yes 后，
**同一 pid** 5454、无进程重启）；系统侧 logcat `Config changes=200 … night` = 配置变化
以 handled 形态派发（未重建）。

### 3.4 像素比对（`pixdiff.py`，应用区 = 排除状态栏顶 130px + 手势条底 60px）

| 对比 | max/255 | mean | >8 像素 | 结论 |
|---|---|---|---|---|
| 基线 vs 本批 · 会话页亮 | 3 | 0.014 | **0 (0%)** | **亮色像素不变** ✔ |
| 基线亮 vs 基线 night-yes | 0 | 0.0 | 0 | 审计复现：基线夜间零变化 ✔ |
| 本批会话页亮 vs 暗 | 244 | 198.8 | 94.8% | 暗色真实生效 ✔ |
| 本批亮 vs 往返还原亮 | 0 | 0.0 | 0 | 往返像素级还原 ✔ |
| 正对照（不同页面） | 232 | 14.1 | 10.0% | 比对方法本身有效（排除"全零是没比"） ✔ |

### 3.5 环境伪影记录（诚实边界）

- 无窗口模拟器启动期偶发 **`FocusEvent(hasFocus=true)` 5s 超时 ANR**：ANR 现场 trace
  显示主线程 `epoll_wait` **空闲**、且随后同 pid 完成重组与渲染——是 headless 焦点
  管线伪影，不是 app 挂死；点「Wait」后 app 交互正常（f7 即切换后拍的可交互暗色页）。
  带窗口的 5554 上未复现。
- 首跑 5556 的 f3/f4 曾被误读为"没变暗"（按字节数推断）；开图核实 + 日志取证后确认
  是输入延迟导致帧序错位，与主题机制无关。所有入档帧均已逐张开图核验。

## 四、已知边界与后续

1. **glass 占位皮肤不随主题**：`Skins.installBuiltIns()` 注册的占位对象在安装期固化
   `dark=false`；glass 未安装（"待安装"标注），真实现（U3）注册时自然接上。
2. **系统栏图标色未随主题**（Android 暗色下状态栏仍是深色图标）：改它要动
   `MainActivity.enableEdgeToEdge` 的静态 `SystemBarStyle.light`——该文件不在本批
   允许清单，未动。亮色行为零变化。建议后续批次把系统栏样式接 `ThemeController`。
3. **覆盖不持久化**：与皮肤/底栏选择同级（D98 口径），重启回到跟随系统。要持久化
   需三端存储 expect/actual，另立批次。
4. **桌面系统主题为组合期读取**：随 `isSystemInDarkTheme` 的桌面实现语义；`--dark`
   先验语义不变（= 手选暗）。Web 为 matchMedia 监听（运行中切换即时生效）。
5. **origin/main 已前进**：本批基点 `53625dc`，合并窗口期 origin/main 已到
   `4c1579a`（g-audit-gaps / g-capability-detect 等）——合并前需按
   `references/batch-branch-sync.md` 先合 dev 再回合（本棒不合并、不 push）。

## 五、复现命令

```bash
# 门禁（gradle 9.6.1，worktree 根）
/d/Hermes/tools/gradle-9.6.1/bin/gradle :composeApp:desktopTest --rerun
/d/Hermes/tools/gradle-9.6.1/bin/gradle :composeApp:assembleDebug :composeApp:compileKotlinWasmJs

# 设备流程（5556 = vade-api37-b 实例；绝不碰真机）
adb -s emulator-5556 install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk
adb -s emulator-5556 shell cmd uimode night no   # → 亮
adb -s emulator-5556 shell cmd uimode night yes  # → 暗（即时、不重建）
adb -s emulator-5556 shell cmd uimode night no   # → 亮（往返）
adb -s emulator-5556 shell cmd uimode night auto # 还原

# 像素比对
python pixdiff.py   # 在 shots/g-dark-follow/ 下
```
