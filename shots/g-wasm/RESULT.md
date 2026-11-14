# g-wasm · Wasm/Web 端首次真浏览器运行时验证

- worktree: `D:/HermesWorkspace/Vade-client-wt/g-wasm`（分支 `tmp/g-wasm`，基于 main @ b6f87ba）
- 日期: 2026-11-14 · 浏览器: Edge 157.0.4341.46（headless=new, --disable-gpu）· 服务: 本机 `python tools/wasm-serve.py` @ 127.0.0.1:9173
- 结论口径：本文只记录原始数字与现象，**不含「已通过」判定**。

## 1. 构建

- 任务名：`:composeApp:wasmJsBrowserProductionWebpack`（`--dry-run` 确认存在后实跑；`:composeApp:tasks --all` 因无关的 Compose Preview/AGP9 任务创建失败而无法列任务清单，不影响本任务）
- 耗时：**GRADLE_EXIT=0, wall 554s（Gradle 报告 BUILD SUCCESSFUL in 9m 12s）**。daemon 已热、Kotlin 编译缓存存在，主要耗时在 `kotlinWasmNpmInstall` / `compileProductionExecutableKotlinWasmJsOptimize`（binaryen）/ webpack 5.108.1（4,630ms）。
- 输出目录：`composeApp/build/kotlin-webpack/wasmJs/productionExecutable/`（**不是**旧路径 `build/dist/wasmJs/…`）。`index.html` 在 `composeApp/build/processedResources/wasmJs/main/index.html`（482 B），引用 `composeApp.js`。

### 产物逐项字节数（发行包 = index.html + webpack 输出，合计 14,200,648 B）

| 文件 | 字节 |
|---|---|
| bfa5198fb2fe683c613a.wasm（skiko 渲染运行时） | 8,640,316 |
| b2d4e812c14a7c2e6150.wasm（app，binaryen 优化后；对应未优化 `compileSync/…/kotlin/composeApp.wasm` 13,682,087 B） | 4,035,006 |
| composeApp.js | 530,944 |
| composeApp.js.map | 1,496,002 |
| composeApp.js.LICENSE.txt | 1,298 |
| index.html | 482 |

服务用站点目录 = 上述文件 + index.html 复制到 `composeApp/build/wasm-web/`（gitignored build 输出，非源码改动）。

## 2. 本地服务与 MIME

- `tools/wasm-serve.py`（自写）：`.wasm → application/wasm`、COOP `same-origin` / COEP `require-corp` / CORP `same-origin`、毫秒时间戳访问日志。curl 实测三类资源均 200 且 MIME 正确。

## 3. 截图与开图确认（逐张人工查看）

| 文件 | 尺寸 | 字节 | 开图看到的内容 |
|---|---|---|---|
| `shots/g-wasm/web-1280.png` | 1280×800 | 44,629 | 桌面外壳：左图标栏 + 会话列表（RFC-0004 / vade core / U1-a / 皮肤组件 11 个冻结 / wasm 体积回归 / AGP 9.5.1 矩阵验证）+ 右侧空详情区居中占位文本；**部分中文标签为豆腐块**（字体未就绪帧，见 §6） |
| `shots/g-wasm/web-480.png` | 480×900 | 67,119 | 手机宽自适应布局：顶栏「会话 ~600dp·底栏单栏·6 个会话」+ 搜索/新建图标、置顶横幅「假数据·未连接 core（U1-a 只做骨架，U1-b 接线）」、置顶/今天/昨天/7天内分组列表（含置顶徽标、12:40 红点、claude-sonnet 标签）、底部四项导航「会话(紫色高亮)/活动/能力/我的」；中文全部正常 |
| `shots/g-wasm/budget-3000.png`（web-console-run.png 同字节） | 1280×800 | 71,462 | 桌面完整双栏：文字导航栏「会话/活动/能力/我的 + 底部 U1-a」、头部「会话 ~1200dp·双栏+右面板·6 个会话」、右侧「在左侧选择一个会话」；中文全部正常 |
| `shots/g-wasm/cdp-run3-1280-at-probe.png` | 1280×800 | — | CDP 会话内截图，同上桌面布局，中文正常 |
| `shots/g-wasm/watch-01.png` | 500×320(CDP 视口) | 19,262 | **导航后 0.747s 墙钟**：应用 UI 已完整渲染（列表+底栏），部分中文豆腐块 |
| `shots/g-wasm/watch-04.png` | 500×320 | 33,331 | 2.017s：同布局，中文全部正常（字体就绪后重绘） |

两张规定尺寸截图（web-1280 / web-480）均已逐张开图确认为**真实渲染的应用界面**，非白屏/灰屏/错误页。另用 budget 扫描（100→15000）与 0.5s 间隔连拍 20 张（watch-01..20）交叉确认：所有帧均为渲染态或字体过渡态，无白屏帧。

## 4. Console 与异常

三种采集方式，结论一致：**无未捕获异常、无 JS error**。

1. `--enable-logging=stderr --v=1`（/tmp/edge-console.log，954 行）：5 条 CONSOLE——4×`WebGL: INVALID_ENUM: getParameter … WEBGL_debug_renderer_info not enabled`（来自 composeApp.js，skiko GPU 探测，良性 INFO）+ 1 条 Edge 自身扩展日志；无 uncaught/exception/Failed to load resource（favicon 404 除外，见下）。
2. CDP `Runtime.consoleAPICalled` + `Runtime.exceptionThrown` + 页内 `window.onerror`/`unhandledrejection` 钩子（cdp-run1/2/3）：**console=[]、exceptions=[]、pageErrors=[]、rejections=[]**（三次运行全部为空）。
3. CDP `Log.entryAdded`（run2/run3）：仅 2×WebGL INVALID_ENUM warning + 1×favicon.ico 404（站点未提供 favicon，无害）。

## 5. 首屏时间（测法已标明）

墙钟、本机回环、冷 Edge profile（字体走网络）、`--disable-gpu`。两次独立复测（run2 视口 ~500px、run3 经 `Emulation.setDeviceMetricsOverride` 固定 1280×800）：

| 时间点（ms，自导航起，performance API） | run2(500px) | run3(1280×800) |
|---|---|---|
| index.html responseEnd | 13 | 9 |
| composeApp.js 传输完 | 121 | 153 |
| 两个 wasm 传输完（8.6MB+4.0MB） | 362/379 | 405/404 |
| load 事件 | 251 | 259 |
| Compose 容器 DIV 挂载（DOM 轮询法, loader-watch） | ~694 | —（同机制未单独复测） |
| **first-contentful-paint** | **1,056** | **938.9** |
| 首张含完整应用 UI 的截图（0.5s 连拍法） | 747 | — |
| 13 个 woff2 字体子集全部取完 | 1,930 | 2,237 |
| 中文完全正常帧（连拍目视） | ~2,017 | — |

测法说明：①FCP 为浏览器 performance API 标准口径；②连拍法以 CDP `Page.captureScreenshot` 每 0.5s 一张、逐张目视判定首张应用帧（747ms 早于 FCP，因截图管线可先于 FCP 启发式取到合成帧）；③budget 扫描法被证伪——新 headless 截图时序不严格受 `--virtual-time-budget` 约束（budget=100 仍出图），不作为时间证据；④服务器访问日志（导航→最后资源请求 +1.1~2.1s）仅作交叉参考，含浏览器启动/代理初始化开销。
**区间结论：首帧 UI ≈ 0.75–1.0s；文字（CJK 字体）就绪 ≈ 2.0–2.2s。**

## 6. 现象与观察（非阻塞）

- **豆腐块是字体就绪时序问题，不是缺字体**：首帧（~0.7s）以回退字体绘制，13 个 Google Fonts woff2 子集在 ~1.9–2.2s 取完后重绘为正常 CJK；`--virtual-time-budget` 截图落在不同窗口时出现两种字节级确定的状态（44,629B 豆腐态 / 71,462B 正常态）。
- `document.querySelectorAll('canvas')` 恒为 0：新版 Compose/Skiko 用 **OffscreenCanvas**（渲染在 worker 内），DOM 上只有容器 DIV——canvas 存在性不能作为渲染判据（本次验证初期因此误判，后以截图目视纠正）。
- `firstRAF` ~410–420ms、主线程 rAF 仅 3 次：主线程非渲染循环（worker 承担）。
- Edge 内部噪声（QQBrowser importer path not found、task_manager fallback）为浏览器自身日志，与页面无关。

## 7. 未验证清单

- **仅 Edge 157（Chromium）**：Chrome、Firefox、Safari（含 iOS）、移动浏览器均未测；OffscreenCanvas/COOP-COEP 在 Safari 的行为未验证。
- **仅本机回环 HTTP**：未测真实网络延迟、gzip/brotli 压缩传输（4MB+8.6MB wasm 的公网首屏会显著高于 0.94s）、CDN。
- **无交互验证**：点击导航、打开会话、输入、滚动等均未测（headless 截图验证仅覆盖静态首屏两个断点宽度）。
- **假数据后端**：应用自身标注「假数据·未连接 core」，未测任何真实连接/ProtocolLaunch。
- **GPU 差异**：全程 `--disable-gpu`（软渲染）；真实 GPU/其他驱动的 WebGL 路径未测。
- **非生产模式**（`wasmJsBrowserDistribution` dev 包、热重载）未测。
- 浏览器窗口缩放/DPR>1、多标签、标签恢复等场景未测。

## 8. 证据文件清单（均在 shots/g-wasm/）

- 截图：`web-1280.png`、`web-480.png`、`budget-{100,200,300,500,700,1000,1500,2000,2500,3000,4000}.png`、`web-console-run.png`、`watch-01..20.png`、`cdp-run2-at-probe.png`、`cdp-run3-1280-at-probe.png`
- 数据：`cdp-run1.json`、`cdp-run2.json`、`cdp-run3-1280.json`（含 performance 时间线/console/异常）、`watch-samples.json`（DOM 轮询 + 连拍索引）
- 工具：`cdp-probe.py`（CDP 墙钟 FCP + console + 异常）、`loader-watch.py`（DOM loader 消失 + 连拍首帧）
- 构建日志副本：/tmp/build-wasm.txt（服务器 /tmp，未入库）；服务访问日志 /tmp/wasm-serve.log

## 9. 约束遵守

- 未 push、未 merge、未改任何 `.kt`/`.kts`/`gradle.properties`/`composeApp/src/**`（`git status` 仅新增 `shots/g-wasm/**` 与 `tools/wasm-serve.py`）。
- 未使用 `tools/capture-window.ps1`；未触碰模拟器/真机；未删任何测试。
