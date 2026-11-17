# f3-tests · 客户端测试隔离 / 缺失测试 / 非法皮肤 / 外观回滚

分支 `tmp/f3-tests`（仅本地提交，未 push）。本批**没有跑 Gradle**：结论是代码与静态核对，
不是测试运行结果 —— 末尾「未验证」一节写明。

## 改了什么

### P1-3 · 桌面测试隔离（不再写开发机真 prefs）

- 新增 `composeApp/src/desktopTest/kotlin/vade/ui/testutil/IsolatedPreferencesTest.kt`：
  `@BeforeTest` 换 `InMemoryPreferenceStore`，`@AfterTest` 把进入时的实现放回。
- 12 个**会组合 `App()` / `ProfileScreen()`** 的测试类改为继承它（点皮肤 / 底栏 / 主题 chip
  的那几个文件就是真正在写真 prefs 的地方）：
  `SourceSwitchUiTest`、`ThemeSwitchUiTest`、`ListStatusBannerTest`、`ProfileSkinCopyTest`、
  `ProfileThreeStateTest`、`ScreenAdapterWiringTest`、`ThreeScreensNeverBlankGuardTest`、
  `AboutPageTest`、`VectorIconSwapTest`、`AppShellAdapterWiringTest`、`BottomBarSemanticsTest`、
  `RealCoreUiE2ETest`。
- `SkinsBootTest.preferencesAreInMemoryDuringDesktopTests` 断言这条钩子真的生效（防「继承漏了
  也没人发现」）。
- 已知边界：`ThemeController` 的 `init`（恢复主题）发生在类加载时，早于 `@BeforeTest`；
  需要确定性主题基线的测试仍要自己赋值 `ThemeController.override`（类 KDoc 已写明）。

### P1-5 · `--skin=glass` / 任意非法 id 不再组合期崩溃

- `Skins.resolveSkinId(requested: String?)`：装得上就采纳；装不上退回
  `bootSkinId()` 链（上次选过的 → 出厂 `material`）；空 / 空白 = 没给。
  `bootSkinId()` 复用同一个私有 `converge()`，两条路只有输入不同。
- `Main.kt`：`--skin=` / `VADE_SKIN` 不再直接塞给 `App()`，统一过 `resolveSkinId`，并在
  值被替换时向 stderr 打一行「未安装，本次用 X 启动」。
- `App.kt`：`initialSkinId` 默认值改为 `null`（组合期读偏好，而不是在调用点冻成实参）；
  `SkinRegistry.get` 前再收敛一次 `state.skinId` —— `UiState.setSkin(未知 id)` 是公开入口，
  以前那里就是 `error(...)`。
- `ProfileScreen.kt`：chip 的「当前选中」用收敛后的 id，失效 id 不会让三个 chip 全不亮。

### P1-13 · 桌面外观选择被 UiState 重建回滚

- 病因：`remember(effectiveSource) { UiState(initialSkinId = 启动实参, initialBar = 启动实参) }`。
  首次配对成功换源 → UiState 重建 → 设置页刚选的皮肤 / 底栏被打回启动值。
- 修法：`CarriedAppearance`（普通 var，`SideEffect` 每帧同步 `state.skinId` / `state.barStyle`）
  跨重建携带；决定皮肤 id 的规则提成纯函数 `appearanceOnRebuild(current, initial)`
  （本轮已选且还装得上 > 启动实参 → 偏好 → 出厂）。

### 补的测试（三处 KDoc 点名的那两个 + 两个守卫）

- `chat/ChatMotionTest`：`reducedDurationMs`（封顶 100ms 而非归零、短时长不被拉长）、
  `reducedTravelPx`（归零）、`SkinMotion` 走同一条规则、`iconPressSpec` 三数联动 /
  静止 / 禁用态 / 自定义 pressScale，以及「图标按压比 == 发送球 == 0.92（material & miuix）」。
- `chat/ChatFollowScrollGateTest`：贴底跟随 / 上滚不拽（`onContentChanged` 返回 false 且举胶囊）/
  回底清提示并恢复跟随 / 点胶囊立刻收起；外加阈值 `<=` 的边界。
- `skin/SkinsBootTest`：偏好缺失、记忆恢复、`glass` 旧偏好收敛、显式 id 采纳与 trim、
  非法 id 退回记忆值且**不改偏好**、任意输入的结果都在 `installed()` 里、测试跑在内存 prefs 上。
- `AppAppearanceRebuildTest`：`appearanceOnRebuild` 的顺序 / 失效 carry 回退 / 底栏 carry
  语义，加两条 `App.kt` 源码钉子（没走 `appearanceOnRebuild`、或直接
  `SkinRegistry.get(state.skinId)` 就红）。

## 未验证 / 留给下一棒

- **没跑 `gradle desktopTest`**（本棒禁止 Gradle）：以上为代码改动 + 静态核对，测试是否
  全绿需要下一棒在有 Gradle 9.6.1 的环境跑一次
  `PATH=/d/Hermes/tools/gradle-9.6.1/bin:$PATH ./gradlew :composeApp:desktopTest`。
- `ChatThread.kt` 里 `NewContentCapsuleTag` 的 KDoc 写的是 `ChatFollowScrollTest`（本棒的新类
  叫 `ChatFollowScrollGateTest`，按任务命名）。`ChatThread.kt` 不在本棒可改文件范围内，
  所以那处 KDoc 链接仍是旧名 —— 一行注释，随下一棒顺手改。
- `skin/ThemeMode.kt` 类 KDoc 里的示例仍是 `remember(state.skinId, …) { SkinRegistry.get(state.skinId) }`；
  `App.kt` 已改成先 `Skins.resolveSkinId`。同样不在可改范围，建议下一棒同步这句示例。
- `LoadErrorBannerTest` 自己 `SkinRegistry.get(state.skinId)`（测试内部构造，id 固定 `material`），
  不需要收敛；若以后改成参数化，记得走 `Skins.resolveSkinId`。
