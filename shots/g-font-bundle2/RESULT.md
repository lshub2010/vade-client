# g-font-bundle2 · 把 CJK 字体打进 wasm 包（修 Web 中文豆腐 + 离线永久豆腐）

- worktree: `D:/HermesWorkspace/Vade-client-wt/g-font-bundle2`（分支 `tmp/g-font-bundle2`，基于 **main@`d825690`**）
- 侦察依据: `shots/g-font-recon/RECON.md`（根因：CMP 的 `NotoFontDownloader` 运行时按码点从 `fonts.gstatic.com` 拉 Noto Sans SC 的 101 个子集，类与 base URL 都是 `internal`）
- 本棒：改源码 4 个文件 + 1 个二进制 + 1 份许可 + 1 份说明 + 1 个测试；未动 `build.gradle.kts`；未 push / 未合并。
- 口径：下面只写**实测**（字节、sha256、请求数、PASS/FAIL 逐项）。没有「全部通过」这种整段结论。

---

## 0. 一句话

Web 端不再有「先豆腐、后变正常」的窗口：**第一张有内容的画面就是最终画面**（69,488 B PNG，30 s 内 4 次抓拍逐字节相同），**0 个 gstatic 请求**，**断网跑出来是同一串字节**。

---

## 1. 方案（为什么只能走这条）

侦察结论已经排除了所有「配置 CMP」的路：`NotoFontDownloader` 和 `FONT_FALLBACK_BASE_URL` 都是 `internal`，没有 flag / `CompositionLocal` / 公开构造能改它。唯一受支持的杠杆是**让缺字形这件事不发生**：自己把覆盖码点的 `FontFamily` 交给排版。

采用 **B1**（侦察报告 §3 的推荐），落地时比侦察多做了两件事，因为 `g-wasm-rt` 的观测证明它们必须做：

1. **在 `ComposeViewport` 之前**把字体装进 typography —— 这样第一帧就有字形（不是「装好之后再补救」）。
2. 仍然显式给了一个失效点 `key(VadeFonts.generation)`：`g-wasm-rt` 实测「字形字节到齐 ≠ 界面重绘」（30 s 三张连拍逐字节相同，点击后才变），所以 `VadeFonts.installSans()` 会 bump 一个 `mutableStateOf`，入口把它当 key 读进组合。首帧路径用不到它（字体先到），但只要有人把安装改到 viewport 之后，界面就会自己失效——不是靠运气。

### 1.1 改动清单

| 文件 | 性质 | 行数 |
|---|---|---|
| `composeApp/src/commonMain/kotlin/vade/ui/skin/Skin.kt` | `object VadeFonts`（`sans` + `generation` + `installSans`）；`SkinTypography` 的 `title`/`body`/`caption` 默认值改成 `fontFamily = VadeFonts.sans` | **+41 / −4**（含 3 个 import） |
| `composeApp/src/wasmJsMain/kotlin/vade/ui/VadeFontWasm.kt` | 新增：`fetch` 本地 woff2 → `Int8Array.toByteArray()` → `androidx.compose.ui.text.platform.Font(...)` → `VadeFonts.installSans(...)`；失败/非 2xx 打日志并**照样启动**；`onDone` 恰好一次 | **新增 105 行** |
| `composeApp/src/wasmJsMain/kotlin/vade/ui/main.kt` | 装字体成功后才建 `ComposeViewport`；`key(VadeFonts.generation)`；`?font=off` / `?font=<path>` 诊断开关（文档写进文件头） | +32 / −6 |
| `composeApp/src/wasmJsMain/resources/fonts/vade-sans-sc.woff2` | 二进制：GB2312 子集，family 改名 `Vade Sans SC` | 1,012,640 B |
| `composeApp/src/wasmJsMain/resources/fonts/OFL-VadeSansSC.txt` | 许可全文随包 | 4,388 B |
| `composeApp/src/wasmJsMain/resources/fonts/README.md` | 来源/体积/许可/复现配方 | 新增 |
| `composeApp/src/desktopTest/kotlin/vade/ui/skin/VadeFontsTest.kt` | 新增 4 条测试（默认不动 / 安装生效且 bump / 卸载回 Default / miuix 未接入的洞） | 新增 99 行 |

**没有改 `build.gradle.kts`**（也不需要：字体放在 `src/wasmJsMain/resources/`，`wasmJsBrowserDistribution` 会把它并进 `build/dist/wasmJs/productionExecutable/fonts/`，已实测产物在那里）。

### 1.2 为什么是「默认值 + 一个 object」，而不是在 wasm 侧包一层 Skin

- 全站正文都从 `LocalSkin.current.typography` 出（`UiText` → `SkinTypography.body.copy(...)`），改默认值一处覆盖所有页面文本；`MaterialSkin` 直接 `SkinTypography()`，所以**随包默认皮肤（`initialSkinId = "material"`）整屏生效**。
- 反例（**已知缺口**，见 §6）：`MiuixTokens.typography` 自己写死了 4 个 `TextStyle`，不走默认值，所以可选皮肤 `miuix` 仍未接入。
- `mono` 故意保留 `FontFamily.Monospace`：换掉会让 `MotionProbe` 的列对齐变。

---

## 2. 字体：来源、体积、许可

| 项 | 值 |
|---|---|
| 上游 | **Noto Sans SC**（`google/fonts` → `ofl/notosanssc`），`NotoSansSC[wght].ttf` 17,772,300 B，sha256 `a3041811…af0da` |
| 处理 | `varLib.instancer wght=400` → `pyftsubset --flavor=woff2`（GB2312 7,445 码点 + ASCII + 标点）→ 改写 `name` 表 |
| 产物 | **`vade-sans-sc.woff2`，1,012,640 B**，sha256 `64e469070eaa77ad8a6b77e8032e54803450d953026e7ac25ad974e56f65b209` |
| 覆盖率 | glyphs 7,556 / cmap 7,548；upem 1000；ascent 1160 / descent −288（与 Google 的 CDN 子集逐项一致） |
| 许可 | **SIL OFL 1.1**；`name` id 0 原样保留 `(c) 2014-2021 Adobe …, with Reserved Font Name 'Source'`；id 13/14（许可正文/URL）保留；**随包附 `OFL-VadeSansSC.txt` 全文**（web 产物里就是 `fonts/OFL-VadeSansSC.txt`） |
| 改名 | `Noto Sans SC` → **`Vade Sans SC`**（id 1/3/4/6/16/17；id 5/7–14 不动）。理由：子集化属于 OFL 意义上的「修改版」，保留名不得沿用 |
| 不采用 | **HarmonyOS Sans**（其许可只允许未修改副本，子集/格式转换都算修改） |
| 体积取舍 | 不用 13.4 KB 的「今天 UI 用字」微子集：加一句新文案就又豆腐。GB2312 是「常用字全覆盖」的最低成本折中。gzip 后 1,012,829 B（woff2 已压缩，服务器 gzip 不再变小） |

配方（可直接重跑）写在 `composeApp/src/wasmJsMain/resources/fonts/README.md` §Reproducing。

---

## 3. 验收 ①：首帧就正确（贴字节）

工具：`shots/g-font-bundle2/verify-font.py`（本棒新增，自起 `tools/wasm-serve.py` + 真 Edge headless + 原生 CDP；每次运行按「每次画面字节变化」存帧，所以「第一张有内容的帧」是文件而不是说法）。

`--phase fix`（**联网**、默认走随包字体）实测：

| 项 | 值 |
|---|---|
| 全流程不同帧总数 | **2**（`frame-00.png` 4,718 B = 纯背景，未画内容；`frame-01.png` 69,488 B = 应用） |
| **首张有内容的帧** | **`shots/g-font-bundle2/runs/fix/frame-01.png`，69,488 B，sha256 `7ef52e63bb85de5a97aa56275cb84f7a98d1e1fa2bddafa51e0a8f3f04e30069`** |
| 稳定帧 | 同一个文件（首帧 == 稳定帧，六个区域像素差全 0.0） |
| 字体安装日志 | `[vade-font] installed 'fonts/vade-sans-sc.woff2' (1012640 B, family Vade Sans SC)` |
| 首屏 CX 区墨量（列表文字区暗像素） | **3,198**（豆腐对照 2,427，见 §5） |
| `<canvas>` / `document.fonts.size` | 0 / 0（Compose 用 OffscreenCanvas + worker，DOM 里没有 canvas，符合判渲染铁律） |
| AX 树 | **189 节点**，中文标签齐（`会话 / 活动 / 能力 / 我的 / 添加附件 / ≥1200dp · 双栏 + 右面板 · 6 个会话` …） |

我把这张图逐张看过：会话行标题、正文、时间、置顶标记全部是真汉字，没有空心方框、没有缺字。

---

## 4. 验收 ②③：0 个 gstatic 请求；断网同一串字节

四个 phase 是同一份产物、同一台机器、只改「网络」与「字体开关」：

| phase | 网络 | 字体 | gstatic 请求 | gstatic 失败（`loadingFailed`） | 首帧字节 | 稳定帧字节 | 首帧==稳定帧 |
|---|---|---|---|---|---|---|---|
| `fix` | 联网 | **随包** | **0** | 0（根本没有请求） | 69,488 B `7ef52e63…` | 69,488 B（同 sha） | **是（差 0.0）** |
| `fix-offline` | **断网**（`MAP * 127.0.0.1:9, EXCLUDE 127.0.0.1`） | **随包** | **0** | 0 | **69,488 B `7ef52e63…`（与联网逐字节相同）** | 同左 | **是** |
| `ref` | 联网 | `?font=off`（修复前路径） | 11 | 0（全部成功） | 45,439 B `7d2ddc81…`（豆腐） | 70,271 B `cb0d42a8…`（gstatic 到货后重绘） | 否（列表区差 13.5%，`cjk_rows` 15.9%） |
| `control` | **断网** | `?font=off`（修复前路径） | 77 次尝试 | **77 × `net::ERR_CONNECTION_REFUSED`** | 45,439 B `7d2ddc81…`（豆腐） | 45,439 B（同 sha） | 是 → **永久豆腐** |
| `missing` | 断网 | **404 的字体 URL** | 77 次尝试 | 77 × `net::ERR_CONNECTION_REFUSED` | 45,439 B（豆腐） | 45,439 B（同 sha） | 是（但页面**照样启动**，日志见下） |

`missing` 的控制台原文（失败不致命，退化成修复前行为，不白屏）：

```
[vade-font] FAILED to install 'fonts/nope.woff2': HTTP 404 — falling back to FontFamily.Default
            (CJK will go through CMP's gstatic fallback, i.e. tofu when offline)
```

- **断网证据是页面自己给的**：`control` 里 77 条 `Network.loadingFailed` 全是 gstatic（`network.json` 的 `gstatic_failures`），而 `ref`（同代码、网络开）在同一秒里把画面从 45,439 B 换成了 70,271 B。同一份产物、只差网络，结论不靠命令行声明。
- **`fix-offline` 与 `fix` 逐字节相同**（同一 sha256）——这才是「永久豆腐」被治好的标准：离线不是「也能显示」，而是**和在线一模一样**。
- 全程 **0 条未捕获异常**；控制台只有 favicon 404 与 3 条 WebGL `getParameter` 警告（后者是本仓既有现象，`g-wasm-rt` 的日志里同样有）。

---

## 5. 验收：就绪之后「不做任何交互」会不会自己变对

**结论：不需要变——第一帧就已经是对的。**

- `fix` / `fix-offline`：**首帧 == 稳定帧**，六个区域（all / list / detail / topbar / thread / cjk_rows）像素差全部 **0.0**。
- 端到端交互回归（`tools/wasm-interact.py`）里的 `paint.cjk_repaint_s` 是 **`null`** —— 这个字段就是 `g-wasm-rt` 用来量「豆腐→正常」那次重绘的；拿不到可量的重绘，是因为**没有豆腐帧**。
- 反面同样成立：`ref` 的首帧 30 s 内一直是同一串字节（`g-wasm-rt` 观察到的「字体到齐不自动重绘」在这里复现），最后是**新到的字体数据**触发了 13.5% 的整帧变化；`control`（断网）则永远停在豆腐帧。

也就是说：
- 「等几秒会变好」这一段在修复后**不存在**；
- 就算将来有人把安装挪到 viewport 之后，`key(VadeFonts.generation)` 也会让界面失效一次（这是本棒加的显式失效点，不是默认路径）。

---

## 6. 已知缺口（不藏）

1. **可选皮肤 `miuix` 仍是豆腐**：`MiuixTokens.typography`（`skin/miuix/MiuixTokens.kt:178`）自己构造 4 个 `TextStyle`，不带 `fontFamily` → 解析成 `FontFamily.Default` → 回到 gstatic 回退。默认皮肤 `material` 走 `SkinTypography()` 默认值，**已覆盖**。要补只能改 `skin/miuix/**`（本棒允许清单之外）。`VadeFontsTest.miuix_typography_does_not_inherit_the_seam_yet` 把这个洞**钉成断言**，接入后测试会红，提醒改断言与报告。
2. **代码块/等宽**：`MarkdownView` 的代码块用 `skin.typography.mono`（`FontFamily.Monospace`），其中若出现汉字，仍走 gstatic；离线时是豆腐。等宽场景目前只有 ASCII 代码，实际影响为 0。
3. **GB2312 之外的字**（生僻字、日韩文、emoji）仍会触发一次 gstatic 回退：在线只是「偶发慢」，离线是豆腐。要彻底消灭需按侦察 §4.2 上 101 片全量（2.41 MB）。
4. **`SemiBold` 没有独立字面**：family 里只有 400 一个字面，18sp 标题的 SemiBold 由 Skia 合成（原 CDN 路径同样只有一份字面）。想更贴近设计得再带一个 600 子集（+1 MB）或换成保留 `wght` 轴的可变子集。

### 与修复前的像素差异（如实）

`ref`（gstatic 稳定帧）与 `fix`（随包字体首帧）比：

| 区域 | 差异比例（>12/255 的像素占比） |
|---|---|
| detail（右侧空态文字） | **0.00008** |
| thread | **0.00011** |
| topbar | 0.01037 |
| list（300 px 会话列） | 0.10841 |
| cjk_rows | 0.12343 |
| all | 0.02423 |

拆开看：
- 同一行正文的**墨迹跨度逐像素相同**（`把 POST /ui/blob 的分片与断点` 在两张图里都是 0..210 px，纵向 0..17 px），字号/行高/断行没变；
- 暗像素数 176（CDN）vs 166（随包），差 −5.7%：同一份字形轮廓（`态` 的 bbox 两边都是 `45,-58,958,844`），差别在**光栅化**层面（CDN 走的是 Skia 的 fallback 族，随包走显式注册的 typeface），不是排版/缺字；
- 列表区 10.8% 主要是双字重边缘 + 每行标题一个字符宽度的 ellipsis 位置差（截图对照见 §7 的 `cmp` 说明）。

结论口径：**不是「更差」，也不是「逐字节相同」**——是同一版式、同一字形、不同光栅化路径。判渲染不看这个数字，看 §3 的 AX + 墨量 + 我自己逐张开图。

---

## 7. 交互回归（复用 `tools/wasm-interact.py`，未重写）

```
python tools/wasm-interact.py
RESULT 10/10 PASS
```

| # | 步骤 | 关键读数 |
|---|---|---|
| cold | 冷启动 → 真画面 | 首帧真实渲染 ≈ 2.32 s；档位「≥1200dp · 双栏 + 右面板 · 6 个会话」；AX 189 节点；`<canvas>`=0；**`cjk_repaint_s = null`**；字体响应 1 个（就是本地 `fonts/vade-sans-sc.woff2`） |
| open | 点第 1 行 | textbox=1，`添加附件`=True，detail 变化 0.1763 |
| scroll | 上下滚 480 | thread 变化 0.1184，滚回差 0.0000 |
| type | 输入「wasm 交互验证 hello」 | AX value 命中，composer 变化 0.0538 |
| send | Ctrl+Enter | AX 含所输入文本，thread 变化 0.1230 |
| back | Esc | textbox=0，详情区差 0.0000 |
| resize480 / narrow_open / narrow_back / resize1280 | 断点往返 | 档位文本正确，「返回」按钮出现/消失，帧差 0.0000 / 0.0059 |

产物：`shots/g-font-bundle2/regression/`（12 张 PNG + `interaction.json`）。
**说明**：该工具的默认输出目录是 `shots/g-wasm-rt/`（上一棒的证据目录）。本棒跑完后把那批文件复制到 `shots/g-font-bundle2/regression/`，并把 `shots/g-wasm-rt/` **还原成 HEAD 的原样**——不覆盖别人已合入的证据。

---

## 8. 门禁（三目标）

见 `shots/g-font-bundle2/logs/`：

| 目标 | 结果 |
|---|---|
| `:composeApp:compileKotlinWasmJs` | BUILD SUCCESSFUL |
| `:composeApp:assembleDebug` | BUILD SUCCESSFUL（APK 见 `composeApp/build/outputs/apk/debug/`） |
| `:composeApp:desktopTest --rerun` | 见 `logs/desktopTest.log`（数字与抖动处置在下方「测试」节） |

### 8.1 基线漂移（必须先说）

本 worktree 的 base 是 **`d825690`**（分支时 main 的 HEAD）。此后 main 又前进了 12 个提交到 `36b2419`，其中 **`98fa211`** 恰好修了一个**在 `d825690` 上就编译不过的测试**：

```
e: LoadErrorBannerTest.kt:113 Class 'RecordingSource' … does not implement abstract member
e: LoadErrorBannerTest.kt:128 'sessions' overrides nothing
```

`g-archive-ui` 给 `SessionSource.sessions()` 加了 `includeArchived` 形参，`LoadErrorBannerTest` 的测试替身没跟上（继承默认值只写在接口上，实现必须声明形参）。这**不是本棒引入的**，但会让 `desktopTest` 连编译都过不去，本棒就无法验证自己的测试。

处置：把该文件**按 main 的原样**取过来（`git checkout 36b2419 -- …/LoadErrorBannerTest.kt`，+4/−1 行，就是 `98fa211` 的内容），**没有改任何断言**。已在 §9 单独登记。

### 8.2 测试

| 跑法 | 结果 |
|---|---|
| `:composeApp:desktopTest --rerun`（全量） | **963 tests completed, 1 failed, 1 skipped**（`logs/desktopTest.log`） |
| 单独重跑失败类 `--tests "vade.ui.e2e.RealCoreUiE2ETest" --rerun` | **1 test / 0 failed，13.568 s**（`logs/desktopTest-e2e-rerun.log`） |

- 唯一失败 = **已知抖动**：`RealCoreUiE2ETest.realCoreSessionTitlesRenderInAppAndFixturesStayAway[desktop]` → `ComposeTimeoutException: Condition still not satisfied after 45000 ms`。满负载下 45 s 不够，单独重跑 13.568 s 就过。**没有改断言、没有 skip、没有调超时**（阈值问题在另一棒 `g-e2e-budget` 里处理）。
- 1 个 skip 是仓里既有的 opt-in 冒烟：`vade.ui.protocol.VadeClientSmokeTest` 用 `Assume.assumeTrue("VADE_SMOKE_PORT set")`，没有环境变量就跳过——不是本棒加的。
- 本棒新增 `VadeFontsTest` **4/4 PASS**（`typography_defaults_are_untouched_before_install` / `install_reaches_every_typography_default_and_bumps_generation` / `uninstalling_returns_to_default` / `miuix_typography_does_not_inherit_the_seam_yet`）。
- 963 − 4（本棒新增）= **959**，与本棒接手时的基线一致：新增测试是唯一增量，没有测试被删/被跳过。
- `:composeApp:assembleDebug`：**BUILD SUCCESSFUL**，`composeApp-debug.apk` = 22,223,011 B。

---

## 9. 偏差登记（不在允许清单内但必须动的两处）

1. **`tools/wasm-interact.py` 的 staging（+11 / −2）** —— 原来只把 webpack 输出里的**文件**复制到暂存目录，再单独补 `index.html`；`src/wasmJsMain/resources/**` 一个都不复制。字体在里面，于是 harness 会 404 字体、静默走回 gstatic，**测到的是没人部署的包**。改成把 `build/processedResources/wasmJs/main` 整棵树并进去 —— 这正是 `wasmJsBrowserDistribution` 的行为（实测 `build/dist/wasmJs/productionExecutable/` 里确实有 `fonts/`）。不改这一处，「交互回归」跑的不是修复后的包。
2. **`LoadErrorBannerTest.kt`（+4 / −1）** —— §8.1：base 上编译不过，按 main 原样回填。只补形参，不动断言。

没有动 `build.gradle.kts`（无需动）。没有动 `shell/** / destinations/** / chat/** / data/** / protocol/** / component/liquid/** / icon/** / contracts/**`。

---

## 10. 怎么重跑

```bash
cd D:/HermesWorkspace/Vade-client-wt/g-font-bundle2

# 1) 产物
gradle :composeApp:wasmJsBrowserProductionWebpack :composeApp:wasmJsBrowserDistribution

# 2) 字体验收（五相；每相约 1.5–3 分钟，含 30 s 空闲观察）
python shots/g-font-bundle2/verify-font.py --phase ref          # 对照：在线 gstatic（豆腐窗口）
python shots/g-font-bundle2/verify-font.py --phase fix          # 联网 + 随包字体（0 gstatic）
python shots/g-font-bundle2/verify-font.py --phase fix-offline  # 断网 + 随包字体（同一串字节）
python shots/g-font-bundle2/verify-font.py --phase control      # 断网 + ?font=off（永久豆腐）
python shots/g-font-bundle2/verify-font.py --phase missing      # 404 字体 → 照样启动
python shots/g-font-bundle2/verify-font.py --report-only        # 只重打矩阵，不启动浏览器

# 3) 交互回归（复用上一棒的工具）
python tools/wasm-interact.py

# 4) 门禁
gradle :composeApp:compileKotlinWasmJs :composeApp:assembleDebug :composeApp:desktopTest --rerun
```

<details>
<summary>产物字节（可核对）</summary>

```
app wasm   composeApp/build/dist/wasmJs/productionExecutable/a31ca1b93bfa07fbdca9.wasm  5,126,332 B
skiko wasm composeApp/build/dist/wasmJs/productionExecutable/bfa5198fb2fe683c613a.wasm  8,640,316 B
js         composeApp/build/dist/wasmJs/productionExecutable/composeApp.js                535,548 B
font       composeApp/build/dist/wasmJs/productionExecutable/fonts/vade-sans-sc.woff2   1,012,640 B
license    composeApp/build/dist/wasmJs/productionExecutable/fonts/OFL-VadeSansSC.txt       4,388 B
```
</details>
