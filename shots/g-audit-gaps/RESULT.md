# 补面视觉审计 · g-audit-gaps（只记录不修）

- 仓库 / 提交：`vade-client` · 基点 `origin/main` = **bbfdf2f**，worktree 分支 `tmp/g-audit-gaps`（提交 `906f6f8` = 31 张真帧，本 RESULT 为第二个提交）
- 构建：`gradle :composeApp:assembleDebug`（BUILD SUCCESSFUL），APK 装 **emulator-5554**（API 37，1080×2400 @420dpi，手势导航）
- 截图：一律 `adb -s emulator-5554 exec-out screencap -p`；桌面暗色帧用自写的 PrintWindow 脚本（scratch 目录，**未使用也未改动 `tools/capture-window.ps1`**，未杀任何他人 java 进程）
- 本轮结论：**6 缺陷 / 7 可疑 / 9 通过**。暗色在 Android 端实测**无外部入口**（uimode 双向试过、逐像素未变），暗色四帧用桌面 `--dark` 代验并在每条注明口径。

---

## 0. 口径与可复现

### 0.1 各面落图清单（31 张，全部逐张开图判读）

| 图 | 面 | 说明 |
|---|---|---|
| `f01-light-sessions-fake.png` | 亮色基线 | 假源会话列表（对照用） |
| `f02-light-search-overlay-fixture.png` | 搜索浮层·开 | **移动端入口=顶栏搜索按钮**（点按实证） |
| `f03-light-search-empty-results.png` | 搜索空态 | 输入 `zzzz` → 0 命中文案 |
| `f04-light-search-esc.png` | 搜索关闭 | Esc 键值可关浮层（移动端无物理键，仅作旁证） |
| `f05-light-search-back-gesture.png` | **搜索×返回手势** | 浮层开着按返回 → **App 直接退出**（G1） |
| `f06-light-chat-top.png` | 审批卡·亮色 | 会话时间线内联审批卡 + 工具卡（D11 复查） |
| `f07-light-attachment-staged.png` | 附件链路 | 回形针 → IMG_0042.jpg chip |
| `f08-light-attachment-preview.png` | 附件预览·亮色 | 悬浮卡 + 遮罩（S7 手柄存疑） |
| `f09-light-preview-back-gesture.png` | **预览×返回手势** | 预览开着按一次返回 → **App 直接退出**（G1） |
| `f10-light-realcore-zero-sessions.png` | 空态·零会话 | 真核（新数据目录）→ **空白一片**（G3） |
| `f11-light-realcore-zero-modules.png` | 空态·零模块 | 真核零模块 → 模块区块**永远「正在拉取」**（G2/P4） |
| `f12-light-realcore-echo-capabilities.png` | 模块页面 | 真核 + echo 模块（core 侧实注册 1 页）→ 仍「正在拉取」（G2） |
| `f13-light-connecting-before-banner.png` | 错误态·前 15s | 启动后第 10s：只有「正在连接 core …」一行，无横幅（G6） |
| `f14-light-error-banner-settled.png` | 错误态·18s | 第 18s：横幅出现（红像素启发式 0→301 交叉证实） |
| `f15-check-realmode-echo.png` | 错误态·首拍 | 横幅+自救卡首拍（底部被系统 ANR 框遮，作佐证） |
| `f16-light-error-banner-clean.png` | 错误态·完整帧 | 横幅全文 + 重试按钮 + 自救命令卡（P2/G4） |
| `f17-light-error-retry-tapped.png` | 重试·在飞 | 点重试后「正在重试会话列表…」+ 按钮禁用（P3） |
| `f18-light-error-retry-after.png` | （污染帧） | 被并发驱动导航到「我的」页；顺带佐证 Profile 无暗色开关 |
| `f19-uimode-night-sessions.png` | **暗色·uimode 尝试** | `cmd uimode night yes` + 重启 → 画布逐像素未变（暗色 G 无入口） |
| `f20-dark-desktop-sessions.png` | 暗色·会话 | 桌面 `--dark`（代验，下同） |
| `f21-dark-desktop-activity.png` | 暗色·活动 | 四张空态卡 |
| `f22-dark-desktop-capabilities.png` | 暗色·能力 | 含假源 fixture「下载管理」模块页面入口 |
| `f23-dark-desktop-me.png` | 暗色·我的 | 外观/默认模型/其它卡 |
| `f24-dark-desktop-chat-approval.png` | 暗色·审批卡 | 时间线内联审批卡 + 工具卡（D11 暗色复查） |
| `f25-dark-desktop-attachment-preview.png` | 暗色·附件预览 | 玻璃几乎全透明（G5） |
| `f26-dark-desktop-miuix-sessions.png` | 暗色·miuix | 与 material 暗色对照 |
| `f27-dark-desktop-search-overlay.png` + `f27b`（同图查看副本） | 暗色·搜索浮层 | 经 `--search` 打开（S2 遮罩不可感知） |
| `f07b-recheck.png` | 环境事故留证 | 一次进程意外重启的冷启 splash 帧 |

### 0.2 关键实测口径

- **真核**：`shots/g-live/core_launcher.py --no-seed`（端口 18755，零会话）与 `shots/g-module-page/core_echo_launcher.py`（端口 18756，core 侧确认 `com.example.echo/回声模块/1 页 echo` 已注册）。客户端用 intent extras 拉起。
- **extras 键名实测**：`--es vade.source real …` **不生效**（回落假数据）；`--es source real --es host … --es port … --es pair_code …` 才生效（代码读 `SourceKeys.SOURCE="source"` 等裸键，`SourceConfig.kt`）。两次复现。
- **错误注入**：端口 18757 挂主机侧 TCP accept-计数器（accept 即关、逐条打时间戳），`adb reverse tcp:18757 tcp:18757`；每次 App 连接尝试计数 +1。
- **暗色尝试**：`adb shell cmd uimode night yes` → `am start -S` 重启 → 截图 → 与 f01 逐像素比对：应用区差异 **555/80000 px**（仅状态栏时钟区），画布取样 `(247,247,248)` 前后一致 → **uimode 不生效**，随后 `night no` 复原。

---

## 1. 缺陷

### G1 · 移动端任何浮层都不拦截返回手势：搜索浮层/附件预览开着按返回 = 整个 App 退出（严重度：高）
- 图：`f05-light-search-back-gesture.png`（搜索浮层开着，BACK 后直接落到系统 Google 面板）、`f09-light-preview-back-gesture.png`（预览开着，一次 BACK 后同样退出）
- 期望（§9 / Android 惯例）：返回手势应先关闭最上层浮层（搜索浮层 / 附件预览），再逐层退出
- 实际：搜索浮层开着按返回：第一次收键盘、第二次**直接结束 Activity**；附件预览开着**一次**返回即退出，暂存附件与浏览位置全部丢失
- 代码旁证：`composeApp/src/commonMain` 全源 **0 处 `BackHandler`/`OnBackPressedCallback`**；关闭只有浮层内的 ✕/遮罩点击/桌面 Esc
- 一句话方向：给搜索浮层与预览各挂一个 `BackHandler`（桌面 Esc 语义同源）

### G2 · 真 core 下模块清单永远停在「正在拉取模块清单」，模块卡片与「模块页面」入口不可达（严重度：高）
- 图：`f11-light-realcore-zero-modules.png`（零模块 core，等待 >20s 仍是加载卡）、`f12-light-realcore-echo-capabilities.png`（echo 模块 core，模块在线且 core 侧实报 1 页，等待 >20s 仍是加载卡）
- 期望（C-05 / RFC-0015 / FR-8.79）：`ui/modules` 返回后显示模块卡片；模块声明了页则出现「模块页面」入口，可进入模块页面（ui/page.get）
- 实际：真核（两种 core）下能力页永远「正在拉取模块清单」，既不 Ready 也不 Failed，无重试入口 → 模块页面整条链路在真核客户端上**无法验证也无法使用**
- 代码旁证（定位用）：`RealSessionSource.kt` 连接成功路径里 `_moduleCatalog = ModulesLoader(WireModuleCatalogSource { created.uiModulesRaw() }, s)` **只构造、从不调用 `load()`**；`ModulesLoader` 的拉取只由 `retry()`（失败卡，而状态永远到不了 Failed）与 `invalidate()`（`module.status` 事件，静态 echo 模块连接后不会再发）触发 → 第一次拉取无人发起，状态停在初始 `Loading`
- 一句话方向：连接建立后补一次 `moduleCatalog.load()`（或在 `Loading` 态加超时转 Failed）

### G3 · 零会话空态是空白一片，没有任何引导（严重度：中）
- 图：`f10-light-realcore-zero-sessions.png`（真核 0 会话：顶栏副标题「0 个会话」正确、无假数据标签，但列表区全灰）
- 期望（§12 空态 spec：居中图标 + 标题 + 说明；其它面都有此类空态卡）：给出「还没有会话 / 点右上角新建」之类的引导
- 实际：空白画布，仅顶栏和底栏；无图标、无文案、无可点的空态行动
- 备注：FR-8.79 的「不留空占位行」守住了（没有假行），但「有引导」没做

### G4 · 错误自救卡里的 Android 重连命令键名与代码不符：照抄会静默回落假数据（严重度：中）
- 图：`f16-light-error-banner-clean.png`（自救卡原文：`Android 用 --es vade.source real --es vade.pair_code <码>`）
- 期望：自救命令「可照抄」即必须真实可用
- 实测（本棒两次复现）：按卡上命令传 `--es vade.source real …` → App **静默回落假数据**（显示 6 条 fixture + 「假数据 · 未连接 core」标签）；改传裸键 `--es source real --es host … --es port … --es pair_code …` 才进真核
- 代码旁证：`SourceConfig.kt` 的 `SourceKeys` 是 `source/host/port/pair_code`（无 `vade.` 前缀）；`shots/g-live/PLAYBOOK.md:96-99` 与桌面 KDoc 同错（文档侧也写着 `vade.*`）
- 一句话方向：统一键名——要么代码改回 `vade.*`（同时修 PLAYBOOK），要么改自救卡与 PLAYBOOK 为裸键

### G5 · 暗色下附件预览浮层玻璃几乎全透明：背景文字整行透过卡片清晰可读（严重度：中，暗色专属）
- 图：`f25-dark-desktop-attachment-preview.png`（桌面 `--dark --chat=preview`）
- 期望（§9）：预览卡=玻璃层+染色，背后「隐约可见」；遮罩 `rgba(0,0,0,.24)` 提供模态感
- 实际：工具卡红字「exit 1 · No daemon running in this directory」、审批卡「需要批准 risk=high」、args JSON 全部**以可读形态透过预览卡**，与卡内标题/图片区叠成混排；四周遮罩在暗画布上完全不可感知（左右会话行全亮）
- 对照：亮色同路径（`f08`）卡片染色把背景压到只剩轮廓——暗色的玻璃 tint/退化底没有随之加深到可用程度
- 一句话方向：暗色下预览卡走 `glassFallback` 的更深退化底（或提高 tint alpha），遮罩单独为暗色调一档

### G6 · core 不可达的前 ~15 秒没有任何失败表达，只有一行「正在连接 core …」（严重度：低）
- 图：`f13-light-connecting-before-banner.png`（第 10s：仅「正在连接 core 127.0.0.1:18757 …」，无横幅）→ `f14-light-error-banner-settled.png`（第 18s：横幅出现）
- 期望：连接失败应尽早可见（连接环是 `while(true)` 永不放弃，首次失败即成事实）
- 实际：读失败要等该次读取的 15s 自动连接预算走完才转 Failed；期间用户不知道「已经连不上了」，也没有「第 N 次尝试」的计数表达
- 备注：横幅出现后表达是完整的（见 P2），本条只针对头 15 秒的空窗

---

## 2. 可疑（需设计拍板）

### S1 · 暗色卡片层次弱：面与画布只差 8/255，全靠发丝线分层（暗色·桌面代验）
- 图 + 取样：`f20/f24`——canvas `#0E0E10` (14,14,16)、卡面 `#161618` (22,22,24)、delta 8/255（contrast 1.07）；工具卡内层 sunken 块 `#0E0E10`（与画布同色，视觉上是「挖洞」）
- 说明：亮色 surface/canvas 差同样是 8/255（`FFF` vs `F7F7F8`），两套皮肤是同构设计；但暗色下观感层次明显更弱（f20 里顶栏浮卡与画布几乎融为一体）。是否接受=设计题；若要加强，动 token 而不是逐组件加阴影

### S2 · 暗色搜索浮层的遮罩不可感知：模态层级感消失（暗色·桌面代验）
- 图：`f27b-dark-search-check.png`——浮层卡背后的会话行（皮肤组件/wasm/AGP）**保持全亮全清晰**；24% 黑叠 `#0E0E10` 实际只差 ~4/255
- 期望：遮罩应表达「浮层在模态拦截」（亮色 f02 里可感知）
- 方向：暗色遮罩需要单独一档（更深黑/更高 alpha 或改用模糊+染色组合），不是调参数能从亮色公式里继承的

### S3 · 「重试」点击不会立即产生新的连接尝试：复用传输层退避节流（语义确认）
- 图+证据：`f17`（点重试 → 「正在重试会话列表…」+ 按钮禁用）+ TCP 计数器时间线（点击 09:05:05，无即时 accept；下一次 accept 09:05:14，恰为退避表落点）
- 说明：重试重新挂起读取是对的（按钮禁用态真实存在），但读取要等传输层的下一次退避连接（backoff cap 30s）——「点了重试却最多 30 秒没动静」是否可接受需要产品拍板；横幅文案也没有把「正在等连接」表达出来

### S4 · 移动端搜索是「近全屏 sheet」，桌面是「悬浮命令面板卡」，两态差异大
- 图：`f02`（手机：浮层几乎占满顶栏以下全部区域）vs `f27b`（桌面：上半部悬浮卡 + 键盘图例行）
- §9 写的是统一的「悬浮卡片（四周 12dp）」；上一轮 P4 判过移动端形态可用。两形态是否都算合规范需要设计确认（平台形态差异 or 收敛）

### S5 · 「我的→其它」卡三行在暗色下两行像禁用（与亮色 S4 同源）
- 图：`f23`——设备与通知 / 管理此 Core 标题与副文案偏灰，只有「关于」是亮白；亮色同问题（上一轮 S4），暗色下对比更明显

### S6 · 状态栏样式硬编码为深色图标：一旦接入暗色画布，状态栏图标将不可见（代码旁证，无真帧）
- 位置：`MainActivity.kt` `enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(...))` 注释自述「两根系统栏都强制 LIGHT」
- 说明：本轮 Android 无暗色入口（见 G-暗），该缺陷暂时不可达；接暗色时必须连这里一起改（或改读 `isSystemInDarkTheme`），否则暗画布上深色图标不可见

### S7 · 附件预览卡顶部的 36×4dp 手柄在两帧里都辨认不出
- 图：`f08`（亮色）、`f25`（暗色）——代码里有画（`ImagePreviewSheet` grabber，暗色白 70%），两帧的卡顶都只见标题行，未见手柄（可能太淡/被标题行挤掉）。需要放大复查或设计确认

---

## 3. 通过（本轮没看到问题）

- **P1 移动端搜索入口与空态**：顶栏搜索按钮一点即开（`f02`）；输入 `zzzz` → 「没有匹配「zzzz」的会话」+ 范围说明 + 未命中原因与出路提示（`f03`）；0 命中与失败是两个分支（失败走 `SearchFailureNotice`，本轮假源不触发）。「至少 3 个字符」约束有前置说明。
- **P2 错误横幅表达完整**（`f16`）：标题「会话列表加载失败」+ 分类「需要重连 · UNAVAILABLE」+ 原始消息 `core at 127.0.0.1:18757 not reachable within 15000ms (auto-connect)` + 提示句 + ↻重试按钮 + 自救命令卡（桌面环境变量一行命令 + Android/Wasm 变体 + 配对码来源说明）。命令文本本身有 G4 的键名问题，但「自救卡」这个面存在且结构正确。
- **P3 重试链路是真的**：`f17` 点重试 → 横幅变「正在重试会话列表…」、按钮禁用变灰（重试中…）、保留上次失败信息；TCP 计数器记录 18 次真实连接（启动退避序列间隔 2/4/8/16s 与代码 500ms 基数+上限 30s 一致）；点击后读取重挂，下一次连接按退避表落地。S3 是语义问题，机制本身在工作。
- **P4 能力页占位纪律符合 FR-8.79**：加载卡明写「ui/modules 返回前不显示任何模块占位行——『还没有结果』不是『没有模块』」（`f11`）；「模块页面」区块在入口集合为空时整体不渲染（真核两态都没有出现假入口）；假源 fixture 的入口（下载管理/下载队列/下载设置）只在声明存在时出现（`f22` 桌面暗色）。
- **P5 暗色 token 体系与 c-dark 文档吻合**（桌面代验 7 帧）：canvas `#0E0E10`、surface `#161618`、sunken `#0E0E10`、accent 提亮（紫/蓝 periwinkle）、miuix accent 蓝色相可辨（`f26`）；主/次文字对比度全部可读（次文字 token `9A9AA3` on `0E0E10` ≈ 6.9:1，与文档一致）；**没有白边发丝线**（全部是低亮度细线，f20-f27b 逐帧看过）。
- **P6 审批卡内联形态两主题一致**：插在时间线触发位置（非弹窗），卡头「需要批准 + risk=high 徽标」、卡体脱敏 args、卡尾 ✓/✕/⌄ 图标按钮（`f06` 亮 / `f24` 暗）；暗色下卡头徽标、args、按钮全部可读。左条仍是**等宽直条**贯穿全高（warn=黄、danger=红，`f24` 可见）——D11 已知修复中，本轮如实记录暗色同样存在。
- **P7 附件链路（亮色）**：回形针 → chip（IMG_0042.jpg / 1.7MB · 2048×1536 · 已压缩）→ 点 chip 开预览（`f07`→`f08`）；预览=悬浮卡（32dp 圆角、12dp 留白、✕ 关闭、meta 行完整）。
- **P8 零会话/零模块没有假占位行**：真核 0 会话时列表区虽空（G3）但没有任何 fixture 残留或假行；0 模块时无假模块卡（P4）。
- **P9 桌面暗色空态卡**（`f21`）：活动页四张空态卡（暂无运行中的 turn / 暂无待审批 / 暂无失败记录 / 暂无数据）暗色下层次、图标、文案全部正常，无白边。

---

## 4. 暗色专答（任务四问，桌面 `--dark` 代验）

| 问 | 答 | 证据 |
|---|---|---|
| 卡片层次是否崩（全一样黑） | **没有全崩，但很弱**：卡面 #161618 vs 画布 #0E0E10 差 8/255（与亮色同构），分层主要靠 1px 发丝线；顶栏浮卡在空背景上几乎与画布融合（f20） | §2 S1 取样 |
| 文字对比度（尤其 secondary/disabled） | **够**：主文字/标题/按钮全清晰；次文字（预览、时间戳、meta 行）= `9A9AA3` 档，可读；「我的→其它」两行明显偏灰像禁用（S5，与亮色同源） | f20-f27b 逐帧 |
| 玻璃染色是否仍可见 | **部分**：顶栏/底栏在纯深背景上是「比画布略亮的一层」（取样 +3~8/255，f20）；**附件预览卡例外——玻璃近乎全透明、背景可读（G5）**；搜索浮层卡本体不透（但遮罩不可感知 S2） | f20/f25/f27b |
| 发丝线是否变成白边 | **没有**：全部是低亮度细线（#2A2A2E 档），七帧无一处白边 | f20-f27b |

**Android 暗色入口结论**：实测无入口。`cmd uimode night yes` + 重启后画布逐像素未变（f19 vs f01，应用区差异仅状态栏时钟 555/80000 px，画布取样一致）；代码全仓无 `isSystemInDarkTheme`/uiMode 监听，`MaterialSkinEntry.dark`/`MiuixSkinEntry.dark` 只由桌面 `Main.kt` 的 `--dark`/`VADE_DARK` 设置；Profile 页无暗色开关（f18 污染帧顺带佐证）。因此**暗色四帧（会话/活动/能力/我的）用桌面 `--dark` 代验**（f20/f21/f22/f23），Android 真帧未覆盖——上一棒「无入口」的结论维持成立。

---

## 5. 未覆盖 / 限制

- **模块页面渲染（ui/page.get → 页面树）**：因 G2（清单永远加载中）真核下入口根本不出现，模块页面真帧未取得；core 侧事实（echo 模块 1 页已注册）由 launcher stdout 佐证。
- **桌面 Ctrl+K 真按键与顶栏按钮真点击**：自动化两次尝试（AppActivate+SendKeys；真实鼠标点击 (683,66) 图标坐标——经像素扫描精确定位）都未在 App 窗口生效——根因是模拟器窗口叠在 App 窗口上方，合成输入落到别的窗口 + SendKeys 不达 AWT 窗口。浮层本体经 `--search` 旗标取证（`Main.kt` 注明与顶栏按钮同一代码路径）；**键盘入口的实击验证留给人工**。移动端顶栏按钮入口已实证（f02）。
- **重试→失败回归帧**（重试中 → 横幅恢复失败态）未取得：f18 被并发驱动污染；链路逻辑由 f16/f17 + 计数器覆盖。
- **并发干扰**：模拟器与另一审计棒共用，期间发生：一次模拟器被 `| head` 杀掉（父代已重启并告知）、`wm size` 被设过 1080x1500 override（本棒已 `wm size reset && wm density reset`，收工时复测 1080x2400/420 正常）、多帧被对方导航污染（f12 首拍/f14/f18，均用重拍帧替代）、系统 ANR 对话框弹过两次（f15/f16 底部遮挡）。所有被污染帧都已注明并用干净重拍替代。
- **动态效果**（重试按钮按压、预览下拉关闭手势、搜索浮层进场动画）是静态帧，未验证。

## 6. 修复建议优先级（供派棒参考，本棒未动任何产品代码）

1. G1（BackHandler ×2）——一个文件两行级，用户可感知度最高
2. G2（首次 `load()` 接线）——模块页面整条链路的闸门
3. G4（键名统一）——自救卡/PLAYBOOK/代码三处对齐，否则真核演示永远翻车
4. G3（零会话空态卡）——按 §12 空态 spec 补一张卡
5. G5/S2（暗色玻璃 tint/遮罩单独调档）——c-dark 表补两行「浮层专用暗色值」

---

## 父代复核（2026-11-15，合入后补记）

**G2「真核下模块清单永远『正在拉取』」= 假阳性，撤销该缺陷。**

原因：本审计的 worktree 建自 `bbfdf2f`，而模块接线的合并（`tmp/g-modulepage-wire` → `22c06e4`）当时只在本地 `main`、尚未推远端，
所以本棒看到的树里确实没有首次拉取的触发点。这不是审计的错——是父代「合并后没有立刻推」造成的取证环境偏差（同一坑当晚第二次）。

复核证据（在合并后的当前树上）：
- `composeApp/src/commonMain/kotlin/vade/ui/destinations/CapabilitiesScreen.kt:141` 有 `moduleCatalog?.ensureLoaded()`，幂等旗标长在 loader 上；
- 真 core + 真 echo 模块 + 真 WS 的端到端用例 `vade.ui.e2e.RealCoreModulePageE2ETest` **1 test / 0 failures**（`--rerun`，串行重跑，2m39s）；
- 该用例的断言链是「进入能力页 → 入口自己出现 → 点开 → `ui/page.get` 回 4 个节点 → 三行中文逐行在树」。

**其余 5 条缺陷（G1/G3/G4/G5/G6）与 7 条可疑全部保留，已按优先级派人修**：
G1 → `tmp/g-back-handler`；G4 → `tmp/g-rescue-text`；灭色入口（暗色无入口）→ `tmp/g-dark-follow`；G3 并入 `tmp/g-state-coverage`；G5 待下一批。
