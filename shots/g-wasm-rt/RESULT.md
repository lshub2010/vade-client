# g-wasm-rt · Wasm 分发包的第一次「交互验证」

- worktree: `D:/HermesWorkspace/Vade-client-wt/g-wasm-rt`（分支 `tmp/g-wasm-rt`，基于 `main` @ `f8e991a`）
- 日期: 2026-11-15 · 浏览器: **Microsoft Edge 157.0.4341.46**（`--headless=new --disable-gpu`，`deviceScaleFactor=1`）· 服务: 本机 `tools/wasm-serve.py`（`application/wasm` + COOP/COEP），回环随机端口
- 驱动: 自写 CDP 客户端（`websockets` + `Pillow`），脚本 `tools/wasm-interact.py`（证据提交 `15ee372`）
- 结论口径: 本文只贴**截图字节数、像素差异比、AX 断言、异常原文**；不写「已通过」。

## 0. 一句话结论

**Web 端现在算「能点」**：冷启动能出真画面、会话能点开、消息流能滚、输入框能打字且发送后正文真的进了消息流、Esc/返回按钮能回列表、1280↔480 断点切换正常、全程 0 未捕获异常 —— 但**它还不是「能用」**：唯一的阻塞项是**中文在冷缓存下先豆腐若干秒**（本次 2.9→20.9 s 才把 Google Fonts 子集拉齐，首帧全豆腐），且**首次加载没有任何加载态提示**；另外整轮只有假数据、没有连 core。

## 1. 构建与产物（本 worktree 自建，未复用旧产物）

旧 `g-wasm` 产物落后 main 82 个提交（UI 改过 icons/appshell/float/cards 等），**不可复用**，本棒重跑：

- `gradle :composeApp:wasmJsBrowserProductionWebpack`（Gradle 9.6.1，`BUILD SUCCESSFUL in 7m 10s`，27 tasks executed，exit 0）
- 产物 `composeApp/build/kotlin-webpack/wasmJs/productionExecutable/`

| 文件 | 字节 |
|---|---|
| `0eb77a3ae3fe62e76deb.wasm`（app，binaryen 优化后） | 4,940,561 |
| `bfa5198fb2fe683c613a.wasm`（skiko 运行时） | 8,640,316 |
| `composeApp.js` | 535,494 |
| `composeApp.js.map` | 1,505,958 |
| `composeApp.js.LICENSE.txt` | 1,298 |
| `index.html`（`processedResources/wasmJs/main/`） | 482 |

发行包（index.html + webpack 输出）= **14,116,853 B**。脚本每次把上述文件 stage 到 `composeApp/build/wasm-web/` 再起服务。

## 2. 交互矩阵（10 步，最后一次运行 `interaction.json` 原样）

截图全部在 `shots/g-wasm-rt/`。视口 1280×800（步骤 7–9 为 480×800）。

| # | 步骤 | 预期 | 实际（数字为脚本打印） | 截图（字节） | 结论 |
|---|---|---|---|---|---|
| 1 | 冷启动 → 真画面 | 非纯色帧 + AX 含档位文本 | 首帧 `stddev=16.8`、890 色；计时基准=自 Edge 进程启动；FCP=11,676 ms（**宿主机满载，见 §9**）；`<canvas>`=0；OffscreenCanvas=true；AX 195 节点；档位「≥1200dp · 双栏 + 右面板 · 6 个会话」 | `02-cold-settled.png` (68,998) | **PASS** |
| 1b | （同一过程的**首帧**证据） | 首个真画面单独留档 | 首帧 42,874 B（中文全豆腐，见 §4.1），字体到齐后重绘帧 68,998 B，两帧差异 **0.0284** | `01-cold-first-frame.png` (42,874) | 证据 |
| 2 | 点会话列表第 1 行 @(222,176) | AX 出现 `textbox` + 「添加附件」，详情区像素变化 >5% | `textbox`=1、「添加附件」=True、detail 变化 **0.1790** | `03-open-session.png` (139,011) | **PASS** |
| 3 | 详情页滚轮向上 dy=-480，再向下 dy=+480 复位 | 消息流区变化 >2%、列表区静止 <1%、滚回复原 <2% | thread 变化 **0.1217**、list 变化 **0.0000**、滚回后与基线差异 **0.0000** | `04-scroll-thread.png` (138,488) / `04b-scroll-return.png` (139,011) | **PASS** |
| 4 | 点输入框 @(700,750)，键入 `wasm 交互验证 hello` | Composer 区像素变化 >0.05% 且 AX 有 textbox | composer 变化 **0.05375**；AX `textbox` value = **`wasm 交互验证 hello`** | `05-typed.png` (142,423)（前一帧 `05a-before-type.png` 139,602） | **PASS** |
| 5 | `Ctrl+Enter` 发送 | AX 出现所发正文 | AX 含所输入文本 = **True**；thread 变化 0.1213；会话头消息数 6→8、出现「正在生成 · 已 0 字」 | `06-sent-as-message.png` (136,328) | **PASS** |
| 6 | 按 Esc 返回列表 | AX 不再有 textbox + 列表行仍在 + 详情区回占位 | `textbox`=0；详情区与冷启动差异 **0.0000**；列表行仍在（整帧差异 0.0000） | `07-back-to-list.png` (68,998) | **PASS** |
| 7 | 窗口 1280 → 480 | AX 档位变「<600dp · 底栏单栏」 | viewport=(480,800)；档位「<600dp · 底栏单栏 · 6 个会话」；底栏四项 会话/活动/能力/我的 | `08-narrow-480.png` (66,966) | **PASS** |
| 8 | 窄屏点第 1 行 @(240,176) | 全屏推入：AX 出现「返回」+ textbox，正文区变化 >10% | 「返回」=True、`textbox`=1、body 变化 **0.1363** | `09-narrow-detail.png` (75,962) | **PASS** |
| 9 | 窄屏点顶栏返回 @(44,36) | AX 无「返回」、档位回 <600dp、帧与窄屏列表一致 | 「返回」=False；档位「<600dp · 底栏单栏」；与窄屏列表差异 **0.0000** | `10-narrow-back.png` (66,966) | **PASS** |
| 10 | 窗口 480 → 1280 复原 | 档位回「≥1200dp」，帧与 1280 列表帧一致 | 档位「≥1200dp · 双栏 + 右面板」；差异 **0.0056** | `11-restored-1280.png` (70,042) | **PASS** |

**逐张目视复核（我用视觉逐张看过，不只信像素比）**：`01` 中文全豆腐；`02` 中文正常；`03` RFC-0004 详情 + 底部 composer（红点=流式中）；`04` 确实滚上去了（附件 chip `IMG_0042.jpg`/`spec.pdf` 与 `fs.read`/`shell.exec` 工具卡露出来，内容从玻璃顶栏底下穿过）；`05` 输入框里有 `wasm 交互验证 hello`；`06` 右侧用户气泡 `wasm 交互验证 hello` + 「正在生成 · 已 0 字」；`08` 底栏四项 + 单栏列表；`09` 顶栏左「<」返回 + 「会话详情 · 消息流 / 工具卡为假数据」，**底栏不存在**；`10` 回到窄屏列表。

### 复跑一致性（同一脚本连跑 3 次）

`run5` / `run7` / `run8` 十步全部 PASS，截图字节数几乎逐张相同（脚本输出比对）：

```
run5: 68998 139011 138488 142423 136332 68998 66966 75962 66966 70042
run7: 68998 139011 138488 142423 136328 68998 66966 75962 66966 70042
run8: 68998 138831 138488 142423 136332 68998 66966 75962 66966 70042
```

只有 `03-open-session`（流式动画的落帧时机，±180 B）与 `06-sent`（±4 B）有亚像素级差别。断言口径定型后共 5 次运行（含更早两次）均 10/10。

## 3. 控制台与异常（原文）

`interaction.json` 里：`console=0  exceptions=0  networkFailures=0  cdpReconnects=0`。

`Runtime.consoleAPICalled`：**0 条**。`Runtime.exceptionThrown`：**0 条**（全程无未捕获异常/未处理 rejection）。`Network.loadingFailed`：**0 条**。

`Log.entryAdded` 共 7 条，原文逐条：

```
error   | network   | Failed to load resource: the server responded with a status of 404 (File not found) | http://127.0.0.1:9173/favicon.ico
warning | rendering | WebGL: INVALID_ENUM: getParameter: invalid parameter name, WEBGL_debug_renderer_info not enabled | http://127.0.0.1:9173/composeApp.js
（同上 WebGL warning 共 6 条，来源均为 composeApp.js）
```

- `favicon.ico` 404：`index.html` 没带 favicon，服务也没有；无害，但会让每条加载多一条 error 级日志（若在意可加个空 favicon）。
- `WEBGL_debug_renderer_info`：skiko 启动探测 GPU 名称，`--disable-gpu` 下必然触发；INFO/无害，且本次是软渲染（真实 GPU 路径未测）。

## 4. 判据：为什么不能拿 canvas / DOM 判渲染（这条决定本次能不能做交互）

实测（`page_dom`）：`document.querySelectorAll('canvas').length === 0`、`document.body.children = ['DIV']`、`document.fonts.size === 0`、`OffscreenCanvas` 存在。渲染在 worker 的 OffscreenCanvas 里，**DOM 上既没有 canvas 也没有文本**。

但 Compose 会发布一层 **shadow-DOM 无障碍节点**，CDP `Accessibility.getFullAXTree` 能读到 **195 个节点**，例如：

```
button: 搜索 / 新建会话 / 展开右面板 / 添加附件 / 停止生成 / 更改会话模型 / 允许 fs.write / 拒绝 fs.write
button: R RFC-0004 附件与多模态评审 把 POST /ui/blob 的分片与断点续传写进 §3… 3 天前 置顶
textbox: （value 随输入变化）
StaticText: ≥1200dp · 双栏 + 右面板 · 6 个会话 / <600dp · 底栏单栏 · 6 个会话
```

于是本次断言全部走**语义**：`textbox` 出现/消失、`返回` 按钮出现/消失、档位文本变化；像素差异只作第二个独立证据（证明合成器真的重画了，而不只是 AX 变了）。**这条能力是本棒新增的可复用结论**：wasm 端可以用 AX 树做 e2e 文本级断言。

输入侧同理：`document.querySelectorAll('input,textarea') = []`、`document.activeElement = DIV` —— Compose 1.12 走 EditContext，没有可读的 DOM 输入元素。**「读 DOM value 判输入成功」在 wasm 端不可用**；本次改用 AX `textbox` 的 `value`（输入后读到 `wasm 交互验证 hello`）+ 发送后 AX 出现正文。

## 5. 定因记录（含我自己的两次误判，避免下一棒重踩）

### 5.1 中文：豆腐 → 正常，不是「永久豆腐」

时间线（同一次运行：`01-cold-first-frame.png` → `02-cold-settled.png`）：

| 事件 | 本机在**负载较轻**时的观测 | 本机在**满载**时的观测（见 §9） |
|---|---|---|
| FCP（页面 performance API） | 954 / 991 / 1,056 / 1,266 / 1,639 ms | 3,816 / 4,534 / 11,676 / 39,101 ms |
| 首帧真实渲染 | 1.33–1.87 s | 2.4–10.9 s |
| 首个 Google Fonts 子集响应 | 2.51–2.94 s | 13.1–26.4 s |
| 最后一个子集响应 | 7.88–15.86 s | 13.6–38.1 s |
| 画面变成正常字形（重绘） | 15.3–19.5 s | 14.8–28.2 s |

**这些时间数字只做「有这段窗口期」的定性证据，不做性能结论**：同一脚本在本机跑出的 FCP 从 954 ms 到 39 s，跨两个数量级，期间宿主机 CPU 100%、13 个 java 进程、内存仅剩 4.3 GB（其他棒在跑 Gradle）。但「首帧豆腐 42,874 B → 重绘后 68,998 B」这个**像素事实每次运行都一致**。

- 字体请求来自 **fonts.gstatic.com**（Noto Sans SC 按 unicode-range 切出的多个 woff2 子集 + Noto Sans Symbols2），由 Skiko 自己在 worker 里 fetch，`document.fonts` 看不到（`document.fonts.size = 0`），**`Network.responseReceived` 能看到**（一次运行 2–17 个响应，`loadingFailed` = 0）。
- CDN 可达性实测（**未断网**）：页面内 `fetch('https://fonts.googleapis.com/css2?family=Noto+Sans+SC')` → **200**（111,918 B）；真实 gstatic woff2 URL 有正常响应。所以本次**没有出现永久豆腐**。
- **「断网即永久豆腐」仍只是机制推断，本轮没有做断网实测**（不敢断整机网，会干扰其他棒）。
- 另有 1 次独立观测（probe，非本脚本）：加载后 3 s / 12 s / 30 s 三张连拍**逐字节相同**（仍为 42,874 B 豆腐帧），直到第一次点击才重绘成正常字形 —— 说明**字体到齐与「自动失效重绘」之间没有保证**，有时要等下一次状态变化。这一点与 5.2 的「点击立刻正常」是同一现象的两面。

结论：**中文渲染能力是好的（字形、字重、标点、中英混排都正常），坏的是首屏窗口期**：冷缓存下先几十秒豆腐，且没有任何 loading 态。

### 5.2 滚动第一版被判 FAIL：是**测法错**，不是产品错

第一版脚本对详情页发 `dy=+480`（向下滚），`thread` 与 `list` 变化都是 **0.0000**，而 `03`/`04` 两张 PNG 逐字节相同。定因：**会话打开时线程已自动滚到最新一条**，再往下没有内容 → 向下滚轮是合法 no-op。改成 `dy=-480`（向上翻历史）后 `thread` 变化 0.1216、`list` 0.0000；再 `dy=+480` 滚回，与基线差异 0.0005（说明是真滚动容器，不是一次性重绘）。

顺带定因第二个假差异：第一版 `list` 变化 **0.0711** 是我自己造成的 —— 点完行以后鼠标停在行上，行保留 hover/press 高亮，等到滚轮把指针移到详情区，高亮消失就被算成「列表变了」。修法：点完行先把指针停到详情区再取基线（脚本里有注释）。**这条不是产品 bug。**

### 5.3 CDP 掉线（环境/工具问题，已兜住）

7 次运行里 2 次在渲染器启动阶段抛：

```
RuntimeError: Page.captureScreenshot failed: {'code': -32000, 'message': 'Not attached to an active page'}
```

`about:blank` → `http://127.0.0.1` 的跨源导航期间页面 target 会换渲染进程，DevTools 会话随之失效。**不是产品异常**（页面侧 console/exception 始终为空）。脚本已改为可重连：`send()` 命中 `Not attached`/`Target closed` 时重新发现 page target、重连、重开五个域、重设视口再重试一次；重连次数写进 `interaction.json` 的 `cdp_reconnects`。**注意**：发生过重连的那次运行，其「首帧真实渲染」墙钟值不可采信（run7 10.88 s / run8 10.88 s 均为重连等待所致），§5.1 的时间表取的是无重连的运行。

## 6. 「能用 / 不能用 / 没反应」逐项

| 交互 | 结论 | 依据 |
|---|---|---|
| 冷启动出画面 | **能用** | `02` 非纯色真实 UI，AX 195 节点 |
| 点会话进详情（双栏） | **能用** | `03`：AX textbox/添加附件出现，detail 变化 0.1789 |
| 消息流滚动（上/下、滚回） | **能用** | `04`：0.1216 / 0.0000 / 0.0005 |
| 输入框聚焦 + 输入（含中文） | **能用** | `05`：AX value 读回全串；composer 变化 0.05375 |
| 发送（Ctrl+Enter） | **能用** | `06`：AX 出现正文；消息数 6→8；出现「正在生成」 |
| Esc 返回列表 | **能用** | `07`：textbox 消失、详情区回占位 0.0000 |
| 480 断点（底栏单栏） | **能用** | `08`：档位文本 + 四项底栏 |
| 窄屏推入详情 + 顶栏返回 | **能用** | `09`/`10`：`返回` 出现/消失，帧 0.0000 复原 |
| 断点往返（1280→480→1280） | **能用** | `11`：0.0056 |
| 无障碍语义（AX） | **能用** | 195 节点，按钮/行/输入框都有可读名字 |
| 向下滚轮（已在底部时） | **没反应（正确）** | no-op 是预期行为，见 §5.2 |
| 中文首屏 | **能用但有窗口期** | 冷缓存下首帧豆腐，字体到齐并重绘后正常（负载轻时 15–20 s，满载更久），见 §5.1 |
| 未测 | — | 横向滚动、拖拽滚动、双击、右键菜单、多指手势、Tab 焦点遍历、`⌘K`/`Ctrl+K` 搜索面板、附件按钮、模型切换卡、审批卡点击、右面板开合、深色皮肤、真实 GPU 路径、DPR>1、Firefox/Safari、真网络（非回环）延迟与压缩、真 core |

## 7. 产品 bug 评估（**只记录，不修**）

1. **冷缓存中文豆腐窗口（唯一阻塞「能用」的项）**
   - 现象：首帧全豆腐（42,874 B），字体子集到齐后重绘（68,998 B）；跨 13 次运行，最后一个子集在 7.9–38.1 s 之间到齐（宿主机负载影响巨大），重绘后中文完全正常。
   - 成因：**没有内嵌 CJK 字体**，Skiko 运行时按 unicode-range 从 `fonts.gstatic.com` 拉 Noto Sans SC 子集；首屏无 loading 态、也无字体就绪后的强制重绘保证（见 §5.1 的 30 s 无重绘观测）。
   - 修法体量：需要打包 CJK 子集字体 + 字体注册/回退链（动资源与构建，远超 10 行、且属 wasm/web 打包策略），**不在本棒授权范围内**。
   - **待下一棒**：建议归给「Web 分发/字体策略」棒；最小可行是内置一份常用汉字子集 woff2 + `FontFamily` 绑定，退一步至少加首屏骨架/进度并在字体就绪后主动 `invalidate`。
2. `favicon.ico` 404（无害）。若要清掉，加 1 个空 favicon 即可 —— 同样**未修**（属资源/构建）。

除上述外，**本轮没有发现交互级产品 bug**：点击/滚动/输入/发送/返回/断点切换、AX 语义全部按预期工作。

## 8. 复现

```bash
# 前置：wasm 产物存在（否则先 gradle :composeApp:wasmJsBrowserProductionWebpack，约 7 分钟）
cd D:/HermesWorkspace/Vade-client-wt/g-wasm-rt
python tools/wasm-interact.py            # 全流程，约 2 分钟，退出码 0=全 PASS
```

脚本自己完成：stage `build/wasm-web/` → 起 `tools/wasm-serve.py`（随机回环端口）→ 起 Edge headless（随机调试端口、临时 profile，结束即删）→ 10 步交互 → 打印 `[PASS]/[FAIL]`、矩阵、console 原文 → 写 `shots/g-wasm-rt/interaction.json` + 13 张 PNG。

参数：`--out-dir DIR`（截图落点，默认 `shots/g-wasm-rt`）、`--text "..."`（输入并发送的文本，默认 `wasm 交互验证 hello`）、`--keep-open`（跑完不杀 Edge/服务，便于手动接管）、`--repo`。

依赖：`websockets`、`Pillow`（本机已有），Edge 在 `EDGE_CANDIDATES` 里自动找。

## 9. 限制与抖动（读结论前请先看）

- **基线是 `f8e991a`，不是当前 main**：本 worktree 从 `f8e991a` 切出，本轮期间 main 前进了 **24 个提交**（含 `g-glyph-clear`：导航的 `话/活/能/我` 汉字图标换成 `ImageVector`、删掉 `Destination.glyph`）。本报告**没有在新 main 上复跑**。脚本的断言不依赖被改掉的那些名字（只依赖 `返回`/`添加附件`/`textbox`/档位文本 `…dp · …`），但下一棒合并时应在最新 main 上重跑一次 `tools/wasm-interact.py` 确认。

- **仅 Edge 157 / Chromium，headless，`--disable-gpu`（软渲染）**：真实 GPU、有头模式、Firefox/Safari（含 iOS）、移动浏览器全部未测。
- **仅本机回环 HTTP、DPR=1**：公网首屏（4.9 MB + 8.6 MB wasm）会明显更慢；`DPR>1` 下 dp≠px，脚本里的坐标常量需要按缩放重算。
- **Live 流式动画**：会话详情里假数据会自己往下流，帧随时间变化，所以脚本用「连续两帧逐字节相同」判定静止，个别步骤的字节数会有 ±180 B 的落帧差别（不影响 PASS）。
- **CDP 掉线与截图瞬时失败都已兜住**：`Not attached to an active page` / `Unable to capture screenshot` 都会重试（前者重连），重连次数写入 `cdp_reconnects`；重连发生的那次运行首帧墙钟不可采信（§5.3）。
- **宿主机满载**：本轮期间 CPU 100%、13 个 JVM、空闲内存 4.3 GB（其他棒跑 Gradle），FCP 从 954 ms 波动到 39 s，且渲染器在启动阶段被换掉（§5.3）。**所有时间数字只作定性证据**，交互结论基于像素/AX 不受影响。
- **假数据**：应用自身标注「假数据 · 未连接 core」，未测任何真实连接/`ProtocolLaunch`/真 core 往返。
- **AX 树不是产品契约**：Compose 的无障碍层由运行时生成，节点命名/层级可能随版本变化；脚本对这些名字做了断言，升级 Compose 后若改名需要同步（失败会以 FAIL 暴露，不会静默）。
- 坐标常量（行 y=176、composer 700,750、返回 44,36）是按 1280×800 / 480×800、DPR=1 的当前布局算出来的；布局大改后需重算。

## 10. 约束遵守

- 只 `git add` + `git commit`，**未 push / 未合并**，未用 `--amend`/`rebase`/`reset`。
- 未改 `src/**`、`build.gradle.kts`、`contracts/**`、`shell/**`、`data/**`、`protocol/**`、`component/liquid/**`、`icon/**`；`git status` 仅新增 `shots/g-wasm-rt/**` 与 `tools/wasm-interact.py`。因此不需要跑 desktopTest/assembleDebug 门禁（产物构建本身已覆盖 `compileKotlinWasmJs`：本次 `:composeApp:wasmJsBrowserProductionWebpack` 成功）。
- 未碰真机/模拟器；未用 `tools/capture-window.ps1`。

## 11. 证据文件清单（均在 `shots/g-wasm-rt/`）

| 文件 | 字节 | 内容 |
|---|---|---|
| `01-cold-first-frame.png` | 42,874 | 首个真画面：中文豆腐 |
| `02-cold-settled.png` | 68,998 | 字体到齐后的列表（后续基线） |
| `03-open-session.png` | 138,831 | 点开 RFC-0004：详情 + composer |
| `04-scroll-thread.png` | 138,488 | 向上滚动后的消息流 |
| `04b-scroll-return.png` | 139,011 | 反向滚回（与 03 差异 0.0000） |
| `05a-before-type.png` | 139,602 | 聚焦但未输入（`05` 的对比帧） |
| `05-typed.png` | 142,423 | 输入框内 `wasm 交互验证 hello` |
| `06-sent-as-message.png` | 136,328 | 发送后：用户气泡 + 「正在生成」 |
| `07-back-to-list.png` | 68,998 | Esc 返回列表 |
| `08-narrow-480.png` | 66,966 | 480×800 底栏单栏 |
| `09-narrow-detail.png` | 75,962 | 窄屏推入详情（顶栏返回、无底栏） |
| `10-narrow-back.png` | 66,966 | 窄屏返回列表 |
| `11-restored-1280.png` | 70,042 | 复原 1280 双栏 |
| `interaction.json` | 9.5 KB | 每步 actual/verdict、`timing_mode`、paint 时间线、AX 采样、console/log 原文、`cdp_reconnects` |
| `serve.log` | 595 B | 逐请求访问日志（含 4 个资源 200、favicon 404） |
