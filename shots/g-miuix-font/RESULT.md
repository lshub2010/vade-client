# g-miuix-font · 可选皮肤 `miuix` 接上字体缝（Web 端不再豆腐）

- worktree: `D:/HermesWorkspace/Vade-client-wt/g-miuix-font`（分支 `tmp/g-miuix-font`，基于 **main@`c735037`**）
- 上一棒的结论沿用不改：Web 中文豆腐的根因是 CMP 在无系统字体的 wasm 上按码点去 `fonts.gstatic.com` 拉 Noto 子集，修法是把随包 woff2 装进 `VadeFonts.sans`、在 `ComposeViewport` 之前装好（`shots/g-font-recon/RECON.md`、`shots/g-font-bundle2/RESULT.md`）。
- 本棒只补**最后一个绕过缝的皮肤**：`miuix`。**未 push、未合并**，无 `--amend`/`--rebase`/`--reset`，没删/没 skip/没弱化任何断言。

---

## 0. 一句话

`miuix` 的**两个**字形来源都接到了 `VadeFonts.sans`：`MiuixTokens.typography`（4 个显式 `TextStyle`）与 `MiuixTheme(textStyles = …)`（Miuix 自己的 14 个槽位，`Text`/`Button`/`BasicComponent` 真正走的就是它）。桌面/Android 上取到的仍是 `FontFamily.Default`，且 `textStyles` 在没有安装时**原样返回** Miuix 的 `defaultTextStyles()`（同一个对象），所以那两端观感零变化——有断言钉住。Web 端在**阻断 `*gstatic*` / `*fonts.googleapis.com*`** 的条件下切到 miuix，中文正常出字形，全程 **0 个外部字体请求**。

---

## 1. 根因：miuix 有两个字形来源，都绕过了 `SkinTypography` 的默认值

| # | 来源 | 为什么绕过 | 谁在用 |
|---|---|---|---|
| 1 | `MiuixTokens.typography` 里 4 个**显式构造**的 `TextStyle(fontSize=…, lineHeight=…)` | 显式构造不走 `SkinTypography` 的默认参数，`fontFamily` 是 `null` → 落到 `FontFamily.Default` | `MiuixSkin.typography` → 所有经由 `skin.typography` 的文本（`UiText`、消息气泡、附件状态…） |
| 2 | `MiuixScope` 里的 `MiuixTheme(textStyles = defaultTextStyles())` | Miuix 自己的 `TextStyles` 包（`main`/`paragraph`/`body1`/`body2`/`button`/`footnote1`/`footnote2`/`headline1`/`headline2`/`subtitle`/`title1..4`），与 `SkinTypography` 无任何关系 | Miuix `Text` 不带 `style` 时合并 `textStyles.**main**`；`Button` 合并 `textStyles.**button**`；`BasicComponent`（列表行）用 `body1`/`title*` —— 即 `MiuixParts.kt` / `MiuixSkin.kt` 里绝大多数 `Text("…", fontSize = …)` |

只修第 1 个是不够的：会话列表的标题/副标题由 `MiuixSkin.ListItem` → `BasicComponent(title = …, summary = …)` 画出，走的是第 2 个。这也是本次把 `MiuixScope` 一起改掉的理由（`top.yukonga.miuix.kmp` 的 `Text` 字节码实测读 `getLocalTextStyles()` + `getMain()`；`Button` 读 `getButton()`）。

---

## 2. 改动清单

| 文件 | 改动 | 行数 |
|---|---|---|
| `composeApp/src/commonMain/kotlin/vade/ui/skin/miuix/MiuixTokens.kt` | ① `typography` 由 `val` 改成 **`val … get()`**，title/body/caption 的 `fontFamily = VadeFonts.sans`（`mono` 仍 `Monospace`）；② 新增 `miuixTextStyles(family = VadeFonts.sans)`：把 Miuix 的 14 个 `TextStyles` 槽位全部 `.copy(fontFamily = family)`，`family == FontFamily.Default` 时**原样返回** `defaultTextStyles()`；③ `MiuixScope` 改用 `remember(VadeFonts.generation) { MiuixTokens.miuixTextStyles() }` | +67 / −7 |
| `composeApp/src/desktopTest/kotlin/vade/ui/skin/VadeFontsTest.kt` | 把 `miuix_typography_does_not_inherit_the_seam_yet`（钉缺口）**翻成正向**，并新增 4 条 | +123 / −11 |
| `tools/wasm-interact.py` | 只加参数与新步骤：`--skin <id>`、`--block-external-fonts`、`Cdp.ax_full()/click_named()`、`external_font_urls()`、追加式第 11 步、`steps[].extra`。**既有 10 步矩阵一字未动** | +127 / −1 |

为什么是 `get()` 而不是 `val`：`MiuixTokens` 是 `object`，`val` 会在 object 初始化那一刻把 `FontFamily.Default` **捕获**下来；一旦某个更早的调用点先碰到 tokens（测试、`showcase`、将来的预览），之后 `installSans` 就再也进不来。`get()` 每次读缝，安装晚到也生效——这条正是 `install_after_the_fact_reaches_miuix_tokens` 守的。

`mono` 仍然**不**接缝：换掉会让 `MotionProbe` 的列对齐变（与 `SkinTypography.mono` 同一条既有规则）。

---

## 3. 桌面 / Android「零变化」是怎么保证的（不是靠眼看）

`VadeFonts.sans` 在桌面/Android 上就是 `FontFamily.Default`，而改动前那 4 个 `TextStyle` 的 `fontFamily` 是 `null` —— 绘制时 CMP 同样解析成平台默认族，字节级指标不变。`textStyles` 更进一步：

```kotlin
val base = defaultTextStyles()
if (family == FontFamily.Default) return base   // 桌面/Android：连对象都不换
```

这两条都有断言（`VadeFontsTest`）：

- `miuix_typography_is_default_family_when_nothing_is_installed`：没装字体时 `MiuixTokens.typography` 的 title/body/caption 必须**正好是** `FontFamily.Default`（不是 `null`、不是别的族），`mono` 仍是 `Monospace`，并顺带钉住 20/15/13 的字号阶梯没被顺手改掉；
- `miuix_own_text_styles_are_untouched_on_desktop`：14 个槽位逐个与 `miuixTextStyles(FontFamily.Default)` 相等，且 `main.fontFamily` 必须**仍为 `null`**（证明没有把 `Default` 硬写进副本）。

`material` 皮肤一行未动（`Skin.kt`、`skin/material/**` 都不在 diff 里）。

---

## 4. 测试：缺口断言翻成正向 + 两条新增

`VadeFontsTest` 由 4 条变 8 条：

| 用例 | 钉什么 |
|---|---|
| `typography_defaults_are_untouched_before_install` | 原有：默认值不动 |
| `install_reaches_every_typography_default_and_bumps_generation` | 原有：`SkinTypography` 默认值与 `generation` |
| `uninstalling_returns_to_default` | 原有：卸载回 Default |
| `miuix_typography_is_default_family_when_nothing_is_installed` | **桌面零变化**（见 §3） |
| `miuix_typography_inherits_the_seam` | miuix 的 title/body/caption `fontFamily == VadeFonts.sans`；`mono` 仍 `Monospace` |
| `install_after_the_fact_reaches_miuix_tokens` | 装进一个**源码里根本不存在**的族（桌面 `FontFamily("Vade Fake Sans")`，`@OptIn(ExperimentalTextApi)`），且**先摸一遍 tokens 让 object 完成初始化**再装 —— 只有「读取时读缝」才可能通过，把常量抄进来的写法必红 |
| `miuix_own_text_styles_follow_the_seam` | Miuix `TextStyles` 14 个槽位全部 `== FAKE_SANS` |
| `miuix_own_text_styles_are_untouched_on_desktop` | 未装字体时 14 个槽位与 Miuix 默认逐槽相等、`main.fontFamily == null` |

**变异验证（证明断言有牙）**：临时把 `MiuixTokens` 退回修前形态（`typography` 去掉 `fontFamily`、`miuixTextStyles` 无条件 `return base`）后单跑该测试类 → `tests=8 skipped=0 failures=**4** errors=0`，红的正是四条 miuix 用例（报错原文如 `miuix.title 必须取自 VadeFonts.sans`、`桌面/Android：title 必须仍是 Default expected:<FontFamily.Default> but was:<null>`）。恢复后 `tests=8 failures=0`。

---

## 5. 三目标门禁（原始数字）

一条命令跑完：`gradle :composeApp:desktopTest --rerun :composeApp:assembleDebug :composeApp:compileKotlinWasmJs`（`GRADLE_USER_HOME=D:/Hermes/tools/gradle-home`，gradle 9.6.1）

| 门禁 | 结果 |
|---|---|
| `desktopTest --rerun` | `BUILD SUCCESSFUL in 5m 11s`；按 `composeApp/build/test-results/desktopTest/TEST-*.xml` 求和：**105 个 suite / tests=1122 / skipped=1 / failures=0 / errors=0** |
| `assembleDebug` | `BUILD SUCCESSFUL`；`composeApp/build/outputs/apk/debug/composeApp-debug.apk` **22,272,163 B** |
| `compileKotlinWasmJs` | `BUILD SUCCESSFUL`（同一条命令内的任务） |

唯一那条 `skipped=1` 是改动前就有的 `vade.ui.protocol.VadeClientSmokeTest`：`AssumptionViolatedException: VADE_SMOKE_PORT set`（没有真 core 就跳过），与本棒无关。

（为跑 Web 端另有 `:composeApp:wasmJsBrowserProductionWebpack`：`BUILD SUCCESSFUL in 10m 6s`。）

---

## 6. Web 端实测：miuix 皮肤 + 断网条件

命令（工具是仓里既有的，只加了两个参数；既有 10 步矩阵没动）：

```
python tools/wasm-interact.py --skin miuix --block-external-fonts --out-dir shots/g-miuix-font/web
```

- `--block-external-fonts` 在**第一帧之前**调 `Network.setBlockedURLs(["*gstatic*", "*fonts.googleapis.com*"])`，所以屏幕上任何一个汉字都只可能来自随包 `vade-sans-sc.woff2`。
- `--skin miuix` 在第 10 步之后按 **AX 名字**点「我的」→「miuix」皮肤 chip →「会话」（`Cdp.click_named`，靠 `DOM.getBoxModel` 取坐标；皮肤切换会改行高，坐标定位不可靠），追加成第 11 步。

**结果：`RESULT 11/11 PASS`（EXIT=0），跑了两次都是 11/11。** 第 11 步原文：

```
[PASS] skin  切到 miuix 皮肤（我的 → 皮肤 chip → 会话）  -> 13-skin-miuix-sessions.png (70287 B)
       我的=[36.0, 255.0] chip=[220.0, 163.0] 会话=[36.0, 45.0] 帧 stddev=17.87 colors=1239
       中文 AX 节点=73 外部字体请求=0
       AX 样例=['搜索', '新建会话', '展开右面板', '会话', '活动', '能力']
```

- **外部字体请求 = 0**（`external_font_urls(c.events)` 扫全程 `requestWillBeSent`/`responseReceived`），`networkFailures=0`（连「请求被挡」都没发生 → 根本没尝试）。
- 唯一一条 console：`[vade-font] installed 'fonts/vade-sans-sc.woff2' (1012640 B, family Vade Sans SC)`。
- `page_dom = {dpr:1, canvases:0, offscreen:true, fontFaces:0}` —— 与已验收的默认皮肤一致（Compose 画在 worker 的 OffscreenCanvas 里，`document.fonts` 恒空，不能用它当判据）。

### AX 树里的中文节点文本（miuix 帧，`steps[10].extra.ax_cjk`，前 40 条节选）

```
搜索 · 新建会话 · 展开右面板 · 会话 · 活动 · 能力 · 我的
RFC-0004 附件与多模态评审 / 把 POST /ui/blob 的分片与断点续传写进 §3… / 3 天前 / 置顶
vade core 构建矩阵升级 / AGP 9.5.1 + Gradle 9.6.1 已验证，出 APK 13.5 MB / 5 天前 / 置顶 / claude-sonnet
U1-a 骨架验收 / 四个一级目的地 + 三态底栏，等真窗口截图
皮肤组件 11 个冻结 / D93：+MessageBubble / AttachmentChip / ToolCallCard / InputBar
已归档 · 假数据 · 未连接 core（U1-a 只做骨架，U1-b 接线） · 今天 · 昨天 · 7 天内
≥1200dp · 双栏 + 右面板 · 6 个会话
```

这些会话行标题/副标题由 `MiuixSkin.ListItem` → Miuix `BasicComponent` 画出，即**第 2 个来源**（`MiuixTheme.textStyles`）；「我的」页里的「外观 / 皮肤 / 底栏 / 当前容器」则由 `UiText` → `skin.typography`（**第 1 个来源**）画出。两个来源都在同一次断网运行里出了中文。

### PNG（`shots/g-miuix-font/web/`）

| 文件 | 字节 | 内容 |
|---|---|---|
| `12-skin-miuix-profile.png` | 74,082 | 点完 miuix chip 后的「我的」：**`miuix` chip 为实心 accent 选中态**，中文全程正常（外观/皮肤/底栏/当前容器：NavigationRail 72dp · 双栏 + 右面板…） |
| `13-skin-miuix-sessions.png` | 70,287 | 回到「会话」的 miuix 列表帧（第 11 步的截图） |
| `01…11-*.png` | — | 既有 10 步矩阵的原始截图（material 皮肤，未变） |
| `interaction.json` | 13,026 | 全部步骤、AX 节选、console/网络失败列表 |

我逐张看过这两帧：没有豆腐方块、没有空白字形。皮肤真的换了，不是「点了没生效」——同一列表区域的底色从 material 的 `#F7F7F8 (247,247,248)` 变成 miuix 的 `#F2F1EF (242,241,239)`（整帧像素差 0.00324，差异集中在调色板/圆角这类「皮肤本来就要改」的地方）。

---

## 7. 边界（这些没做，别当成做了）

1. **没有重编译一版「修前」wasm 包做 A/B**（一次 webpack 10 分钟）。「断言有牙」的证据来自 §4 的桌面变异验证，不是来自 Web A/B。Web 端的因果链是：`0 个 gstatic 请求` + `汉字正常出字形` + `文字来自 miuix 组件`——若 miuix 仍用 `FontFamily.Default`，Skiko 只能去拉 CDN（被阻断 → 豆腐），本运行里既没有请求也没有豆腐。
2. 脚本**不判断像素级的豆腐**（AX 只携带语义字符串，不管字形）。所以第 11 步的 PASS 判据是「点到了 + 重绘了 + 有中文语义 + 0 外部字体请求」，字形本身是我看图确认的，这一点写清楚而不是让脚本假装断言。
3. `miuix` 的 `mono` 仍走 `FontFamily.Monospace`，与 `SkinTypography.mono` 同一规则；Web 上等宽字体同样没有系统字体，但那不是本次范围（`mono` 只用于参数块/工具卡正文，本次未涉及）。
4. `wasmJsMain/**`、`AppShell.kt`、`destinations/**`、`build.gradle.kts`、`contracts` 均未改。

---

## 8. 复现

```bash
cd D:/HermesWorkspace/Vade-client-wt/g-miuix-font
export GRADLE_USER_HOME=D:/Hermes/tools/gradle-home
/d/Hermes/tools/gradle-9.6.1/bin/gradle :composeApp:desktopTest --rerun :composeApp:assembleDebug :composeApp:compileKotlinWasmJs
/d/Hermes/tools/gradle-9.6.1/bin/gradle :composeApp:wasmJsBrowserProductionWebpack
mkdir -p shots/g-miuix-font/web
python tools/wasm-interact.py --skin miuix --block-external-fonts --out-dir shots/g-miuix-font/web
```
