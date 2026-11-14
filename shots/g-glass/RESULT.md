# g-glass · 「看不出有模糊」修复取证

分支 `tmp/g-glass`（基于 `2cf917b`），代码 commit：**`c5f5706`**。

## 根因（一句话）

`AppShell.kt` 详情分支把 `TOP_BAR_ZONE` 写成了 `Box(Modifier.padding(top=…))` 的**容器内边距**，视口被裁在玻璃顶栏下方，消息永远滚不到玻璃底下——haze 的模糊代码本身是通的，只是**永远没有东西可模糊**，玻璃只剩一层染色，看起来像纯色。

## 改动点（3 个文件，+97/−41）

| 文件 | 改动 |
|---|---|
| `shell/AppShell.kt` | 详情分支（手机 pushed + 桌面 two-pane 两处）删除容器 `Box(padding(top=))`，改为把 `PaddingValues(top = TOP_BAR_ZONE)` 作为 `contentPadding` 参数传给 `SessionDetailScreen`；`←`/`⌕`/`✎` 三个字符图标换成 `VadeIcons.Back` / `VadeIcons.Search` / `VadeIcons.Compose` 矢量 |
| `destinations/SessionDetailScreen.kt` | 新增 `contentPadding` 参数；根布局从 `Column(header + ChatThread(weight))` 改为全出血 `Box`：`ChatThread(fillMaxSize)` 铺满整个窗口（消息从玻璃与悬浮 header 底下穿过），header + 展开的 `ModelPickerCard` 作为后绘制的悬浮列浮在 `contentPadding.calculateTopPadding()` 之下（c-model 的 header→picker 流关系原样保留） |
| `shell/TopBar.kt` | `TopBarButton` 增加 `icon: ImageVector` 重载（`Image + rememberVectorPainter + ColorFilter.tint(textOnGlass)`，20dp 图标 / 44dp 触摸区，与 `BottomBar.kt` 同一画法）；`glyph: String` 重载保留（右面板 ◂/▸ 与 `search/SearchOverlay.kt` 仍在用，未标 `@Deprecated`） |

遵守的约束：
- **没有往 `VadeIcons.kt` 加任何条目**（一度加过 `ChevronLeft`，按新约束撤销；右面板 ◂/▸ 保持字符形态，`VadeIcons` 里暂无左向 chevron 的既有语义名）。当前 diff 对 `VadeIcons.kt` 为空。
- `GlassSurface.kt` 未动：26%/34% 染色与 24dp 模糊在真机上判读为「能看出有字、读不出内容」，**无需调染色**（理由见下节数字）。
- 底栏侧核查：`SessionsScreen`（LazyColumn `contentPadding`）/ `ProfileScreen` / `ActivityScreen` / `CapabilitiesScreen` 都是内容内边距用法，无需修改；`contentInsets.bottom` 在 Fixed 模式下同为内容内边距，路径正确。
- 禁改文件（data/protocol/chat/skin 两皮肤/GroupCards/ActivityScreen/build.gradle.kts）零改动；未 push、未合并。

## 截图（全部 `adb exec-out screencap -p`，1080×2400，emulator-5554 / API 37）

| 文件 | 尺寸 | 字节 | 内容 |
|---|---|---|---|
| `before-detail-swipe.png` | 1080×2400 | 297,037 | **改前**（旧构建）：顶栏玻璃内部完全纯色，无任何轮廓 |
| `b2-detail-tap.png` | 1080×2400 | 293,447 | **改后**（新基线）进详情、滚到底钉住态：胶囊内已见淡轮廓 |
| `b2-detail-scroll.png` | 1080×2400 | 327,432 | **改后**滚向旧消息：胶囊内可见模糊文字条带 |
| `compare-before-after.png` | 1080×736 | 119,626 | 顶栏 360px 前后对比拼图（上=改前 / 下=改后） |

## 模糊可见性：前后对比（像素数字 + 读图结论）

读图（人眼判读，按 §5 反面判据「能分辨那里有字、读不出内容」）：

- **改前**：玻璃胶囊内部均匀浅灰，看不出任何文字/图形轮廓（纯色板）。
- **改后（`b2-detail-scroll.png`）**：胶囊内部可见**模糊文字条带**与模糊字形残迹（胶囊左缘、右下各一片），**读不出内容**；同一帧里胶囊上缘之外的原始内容（`gradle.properties 还是不行。`）是清晰的——同一画面内「玻璃外清晰 / 玻璃内糊」形成直接对照。

灰度像素统计（PIL，L 通道）：

| 区域 | 改前 | 改后（滚到旧消息） | 含义 |
|---|---|---|---|
| 胶囊内右侧区 (760,70)-(1000,160) | mean 232.7 / **sd 3.59** | mean 237.2 / **sd 5.91** | 改后该区方差 ×1.65：出现了模糊轮廓 |
| 胶囊左缘字形区 (35,120)-(95,175) | mean 237.1 / **sd 3.26** | mean 225.9 / **sd 43.90** | 模糊字形残迹使方差 ×13.5：明显可见但不可读 |

结论：模糊**看得出来**（轮廓存在、内容不可读），26%/34% 染色在亮色主题下没有把模糊洗掉，**不需要调染色规格**，`docs/visual-language.md` §5 无改动。

## 验证数字

| 验证 | 命令 | 结果 |
|---|---|---|
| 桌面测试 | `gradle :composeApp:desktopTest --rerun` | **361 tests / 0 failures / 0 errors / 1 skipped**（skip = `VadeClientSmokeTest` 的 `VADE_SMOKE_PORT` 假设门，基线自带，非本棒引入） |
| Wasm 编译 | `gradle :composeApp:compileKotlinWasmJs` | BUILD SUCCESSFUL（重跑确认 UP-TO-DATE，曾实际执行） |
| APK | `gradle :composeApp:assembleDebug` | composeApp-debug.apk 22,088,360 B（2cf917b 基线 + 本棒改动） |

## 备注 / 遗留

1. **会话列表页的玻璃底下目前仍没有东西**：列表只有 6 条（内容总高 < 视口），LazyColumn 无滚动量，行永远到不了底栏/顶栏底下。这是**假数据量**的问题（`data/**` 禁改），页面侧的内容内边距机制本来就正确——需要另一棒把 fixture 会话加多之后，列表玻璃才会显出模糊。
2. `reverseScrolling = true` 的聊天流里，「滚向旧消息」的手势是**下滑**（不是常规列表的上滑）；任务书给的验收手势（上滑 1500→700）在钉底态实测位移为零（两次截图逐字节相同），本棒验证全部改用下滑。
3. 右面板 ◂/▸ 与 `SearchOverlay` 的 ←/✕/⌕ 字符图标仍在（IconGuardTest 现阶段排除 shell/ 与 search/），留给后续棒用既有/新增语义名统一收编。
