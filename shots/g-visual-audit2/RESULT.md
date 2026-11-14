# 视觉验收 · g-visual-audit2（只记录不修）

- 仓库 / 提交：`vade-client` · `origin/main` = **d3c2178**（`merge: tmp/g-miuix-font`），worktree 分支 `tmp/g-visual-audit2`
- 构建：`gradle :composeApp:assembleDebug`（BUILD SUCCESSFUL），APK 装到 **emulator-5554**（API 37，物理 1080×2400 @420dpi，手势导航）
- 截图：`adb -s emulator-5554 exec-out screencap -p`（真帧缓冲，未用 `tools/capture-window.ps1`）
- 本轮结论：**11 条缺陷 / 8 条可疑 / 8 条通过**；缺陷集中在「顶栏浮卡的上下空隙」「输入区的玻璃与阴影」「miuix 皮肤正文与工具卡」

---

## 0. 口径与可复现信息

### 0.1 实际用的四种尺寸（都记在文件名对应处）

| 用途 | 命令 | 实际 dp |
|---|---|---|
| 手机（底栏） | 默认（`wm size reset` / `wm density reset`） | **411 × 914 dp**（1080×2400 px @ 420dpi） |
| 手机·缩短窗口（让列表能滚） | `wm size 1080x1500` | **411 × 571 dp** |
| 平板 / 窄桌面（rail） | `wm size 1080x2400` + `wm density 240` | **720 × 1600 dp** |
| 桌面（双栏 + 右面板） | `wm size 1280x800` + `wm density 160` | **1280 × 800 dp** |

### 0.2 皮肤开关（代码里的真实机制）

- 入口：**我的 → 外观 → 皮肤**，三个 chip：`material` / `miuix` / `glass`（`destinations/ProfileScreen.kt` 的 `SkinChip("material"/"miuix"/"glass")`）。
- 注册：`Skins.installBuiltIns()` → `MaterialSkinEntry.register()` + `MiuixSkinEntry.register()`（两者都是**真实现**）；`glass` 仍是占位（`SkinRegistry.registerPlaceholder("glass", MaterialSkin("glass","[glass 未安装]"))`）。
- 所以「两套皮肤各一组」= material 组 + miuix 组；**glass 无法作为真实皮肤验收**（只能切到占位）。
- 深色：`MaterialSkinEntry.dark` / `MiuixSkinEntry.dark` 只能由宿主 `--dark` / `VADE_DARK` 设定，Android `MainActivity` 没有读取该开关 → **外部无法切暗色，本轮只验亮色**（如实记录，未覆盖暗色）。

### 0.3 每面的落图数

共 **31 个 png**（30 张正片 + 1 张冷启帧），类别之间有重叠，按用途分：

- 四目的地：`01`（会话）/`03`（活动）/`04`（能力）/`06`（我的）
- 三形态导航 + 双栏右面板：`01`+`16`（fixed 底栏）/`17`（rail 720dp）/`18`+`19`+`20`（桌面 1280dp，20 开了右面板）/`25`（floating 底栏）
- 滚动后可采样顶栏/底栏：`05`/`12`/`13`/`16`/`19`/`25`
- 输入区：空 `09`/`21`；有内容 `10`/`11`；压内容 `23`/`29`
- miuix 与 A/B 对照：`07`/`08`/`09`/`11`/`12`/`13`/`27`/`32` vs `26`/`33`，皮肤确认 `31`
- 补面：`15`（短窗口未滚对照）、`24`（我的+floating）、`28`（搜索浮层）

> 文件名编号有缺号（02 / 14 / 22）：02 是滚动无效帧、14 误落到桌面、22 与 21 逐像素相同（`ImageChops.difference` 为 None），三张已删，编号不动以便对照。

---

## 1. 缺陷（用户一眼能看出）

### D1 · 悬浮输入区不是玻璃、也没有阴影：一块白卡 + 1px 描边（严重度：高）
- 图：`21-phone-chat-material-detail.png`、`26-phone-u1a-material-chat.png`、`09-phone-chat-empty-miuix.png`、`23`/`29-phone-chat-composer-over-text2.png`（29 是「背后真有工具卡」的那一次）
- 位置：会话详情底部 composer（附件图标 + 输入框 + 发送球里的**输入框**）
- 期望（§1 + §5 + §8）：玻璃层（haze 24dp + 26%/34% 淡染）+ `shadow2` 三层、只向下的渐变阴影，观感是「浮在内容上」
- 实际：
  - **阴影为零**：框上下两侧取 40px 均值，上=247.0 / 下=247.0（画布 247.0），三个状态（09 / 21 / 26）三次一致 → 三层阴影一层都没落上像素。今晚「`Modifier.shadow` → `layeredShadow(outsideOnly)`」在输入区上等于把阴影删掉了。
  - **完全看不到背后内容**：`29` 里框后面明确压着 `fs.read` / `shell.exec` 两张卡，框内**一个字都看不到**；框内取样的最小值 251（画布 247、框内 254）→ 不是「看得出有字、读不出」，而是「什么都看不见」，按 §5 反面判据=染色过重/根本没接玻璃
  - 框内只有 1px `hairline`（230,230,233）+ 聚焦时 2dp accent 描边
- 一句话方向：composer 的玻璃与阴影这一层没生效（先确认 `layeredShadow` 是否真的画出第一层，再谈玻璃）。

### D2 · 顶栏是浮卡，滚动内容从卡片上下空隙里**原样清晰**露出（严重度：高）
- 图：`16-phone-sessions-scrolled-material.png`（`crop-16-top-2x.png`）、`13-phone-chat-composer-over-content-miuix.png`（`crop-13-top-2x.png`）、`19-desktop-chat-two-pane.png`（`crop-19-topbar-redline-3x.png`）、`05-phone-capabilities-scrolled.png`
- 位置：顶栏玻璃卡 **上方 8–12dp、下方 8–12dp** 的两条空隙（卡片本体是内缩浮卡）
- 期望：顶栏整条区域（状态栏下沿到列表上沿）都要有模糊/遮罩；内容不得以可读形态出现在标题栏外侧
- 实际：卡片**内部**确实有模糊（能看见模糊字形，读不出）；但卡片**外**的两条空隙里：
  - 会话列表：上一行的预览「把 POST /ui/blob 的分片与断点续传写进 §3…」、右侧「置顶」全清晰，头像 `R` 被卡片弧形切掉一半
  - 会话详情：「细节见 RFC-0004 §6.1」贴在状态栏下沿、卡片上沿之间，清晰可读；下方正文行被卡片下沿切一半
  - 桌面：工具卡的**红色 3dp 状态条**穿过顶栏区域——卡内是模糊红晕、卡外是锐利色条，一眼看出「有东西从标题栏后面漏出来」
- 一句话方向：模糊要覆盖整条顶栏带（或给浮卡外圈加同色遮罩），不能只模糊卡片本体。

### D3 · miuix 皮肤的会话正文不渲染 Markdown（严重度：高）
- 图（两组同会话 A/B）：`27-u1a-miuix-chat-again.png` ↔ `26-phone-u1a-material-chat.png`；`32-miuix-third-session.png` ↔ `33-material-third-session.png`
- 位置：助手消息正文（第二段 + 代码块）
- 期望：`**粗体**`、`` `行内代码` ``、```` ```kotlin ```` 围栏解析成样式 + 代码块卡片（material 就是这样）
- 实际（miuix）：星号、反引号、围栏**原样上屏**——`**原图未保留**`、`**没有报错**`、`` `UP-TO-DATE` ``、`` ```kotlin `` / 结尾 ```` ``` ````，代码块没有底色卡片、没有语言标签与「代码」角标；同一会话切回 material 立刻恢复正常
- 代码定位：`ChatThread.kt:390` 已经把富文本塞进 `skin.MessageBubble(body = { MarkdownText(...) })`；material 的实现是 `if (body != null) body() else Text(...)`（`MaterialParts.kt:276`），**miuix 的 `MiuixMessageBubble` 直接把 `body` 参数丢掉**，只 `Text(text = message.text)`（`MiuixParts.kt:292-300`）
- 一句话方向：miuix 的 MessageBubble 没有消费 `body` 槽（签名里明明有）。

### D4 · miuix 的 `ToolCallCard` 丢掉 meta 槽，卡内信息量明显低于 material（严重度：低）
- 图：`27-u1a-miuix-chat-again.png` / `32-miuix-third-session.png` ↔ `26` / `33`（material）
- 位置：`shell.exec` 失败工具卡
- 期望：两套皮肤结构可以不同，但同一份 `meta`（执行耗时 / 批次 / 错误码 / 发起时间 / 「参数预览 · redacted」）不该凭空消失
- 实际：material = 内层 `ARGS` / `RESULT` 两块（12dp 圆角 + sunken 底）+ 上述 4 行 meta；miuix = 平铺「参数 {json}」+「结果 exit 1…」，**没有内层块、也没有 meta 四行**（参数 JSON 两边都显示，这条不是脱敏差异——先前判断有误，已纠正）
- 代码定位：`MiuixParts.kt` 的 `MiuixToolCallCard` 收下了 `meta` 形参但没调用
- 一句话方向：miuix ToolCallCard 没消费 `meta` 槽（和 D3 是同一类漏槽）。

### D5 · 会话列表里的分组仍是裸 id（`g-1` / `g-2`）（严重度：中）
- 图：`01-phone-sessions-top.png`、`16-phone-sessions-scrolled-material.png`、`17-rail-720dp-sessions.png`、`18-desktop-1280dp-sessions.png`
- 位置：有分组的会话行，标题/预览下方「● g-1」标签（uiautomator 也读到文本 `g-1`）
- 期望：真组名（`FakeMetaActions.defaultGroups` 里是「项目」「灵感」）
- 实际：裸 id。可从代码确认成因：默认（假源）路径 `metaGroupsOf(adapters=null, …)` → `SessionMetaState.forAdapters(null, …)` 拿到 `FakeMetaActions`，但 `loadGroups()` 只在 `adapters != null` 时才调用，`groups` 永远空表 → `SessionsScreen` 回退显示 `groupId`
- 一句话方向：假源这条路上补一次 group.list 的种子加载（或让 `forAdapters(null)` 直接用 `FakeMetaActions.defaultGroups`）。

### D6 · 顶栏卡片圆角外侧多出「第二道轮廓」（严重度：中）
- 图：`12-phone-chat-topbar-over-content-miuix.png`（`crop-12-corner-5x.png`）；左右两个下角都有
- 位置：顶栏玻璃卡的左下 / 右下圆角外侧
- 期望：1px 发丝线贴着本体，阴影是连续衰减
- 实际：描边（195）外面还有 4px 白条（251-252），再外面一条 **2–3px 的硬暗线 (217,215,210)**，比背景 (240,239,237) 暗 23/255，沿圆角外侧走、到直边处消失 → 5× 放大看是明显的「双边框/阶梯轮廓」，1× 看是「底边特别厚」
- 一句话方向：浮卡外圈有一层被画成硬边的描边（`layeredShadow` 的第一层没有模糊，或阴影 outline 的圆角与本体不一致）。

### D7 · 底栏三形态的选中态各不相同（严重度：中）
- 图：`16-phone-sessions-scrolled-material.png`（fixed）、`25-phone-capabilities-floatingbar-scrolled.png`（floating）、`17-rail-720dp-sessions.png`（rail）
- 期望：三种形态「语义与观感一致」（同一套选中表达）
- 实际：
  - **fixed**：只有图标+文字变 accent，没有底块
  - **floating**：图标+文字外面套一块**大 lavender 胶囊**（accentSoft 椭圆，约占胶囊高度的 2/3）——这同时违反 §12「不用大色块 pill」
  - **rail**：图标后面一块 30dp、16dp 圆角的 16% accent 方块（`NavRail.kt` `skin.shapes.small`），文字不动
- 一句话方向：三处各写了一套选中态，需要收敛成一个语义（颜色 / 底块 二选一）。

### D8 · 选中会话行缺左侧 3dp 强调条（严重度：低）
- 图：`19-desktop-chat-two-pane.png`（`crop-19-row2-4x.png`）
- 位置：桌面双栏左侧列表中被选中的行（RFC-0004）
- 期望：§12「选中态 `accentSoft` + 左侧 3dp 条」（两者都要）
- 实际：只有 `accentSoft` 填充 (235,235,246)，左边 x=80 起就是填充色，没有更深的 3dp 竖条
- 一句话方向：选中行的左条没画（或画在填充下面被盖住了）。

### D9 · 「我的 → 外观」写着错的说明 + 直接暴露内部 id（严重度：中低）
- 图：`06-phone-profile-top.png`、`07-phone-profile-miuix.png`、`24-phone-profile-floatingbar.png`
- 位置：我的 → 外观 → 皮肤/底栏 两行 chip 下面的小字与 chip 本身
- 期望：用户能读懂的中文，且与当前代码一致
- 实际：
  - 小字写「**miuix / glass 是占位实现（返回 material token + 未安装角标）**，U2/U3 注册真实现后自动可用」——但本轮 `MiuixSkinEntry.register()` 已注册真实现，miuix 的 accent（蓝）、工具卡、正文渲染、设置卡分割线都和 material 不同；这句是过期文案
  - chip 直接显示 `material` / `miuix` / `glass`、`fixed` / `floating` / `floating+blur` 这类内部 id，中文界面里出现英文标识符
- 一句话方向：这行小字要么删、要么改成真实状态；chip 需要中文标签（内部 id 留在无痕处）。

### D10 · 顶栏上沿外侧有暗带（阴影向上溢出，方向错）（严重度：中）
- 图：`06-phone-profile-top.png`（`crop-06-topbar-topedge-3x.png`）、`01-phone-sessions-top.png`、`03`/`04`（四张页面同样测到 247→237）、`18-desktop-1280dp-sessions.png`
- 位置：顶栏玻璃卡**上沿外侧**，状态栏下沿到卡片上沿那 20px 空隙
- 期望（§8.1 第 2 条 / §8.3）：光源在正上方，元素上方**不得有阴影**
- 实际：纯 canvas 页面上量到 **从 243 平滑压到 236**（画布 247，边沿处差 11/255 ≈ 4.3%，20px 内衰减），是一圈贴着卡片上沿的灰色暗带；同一张卡下沿的阴影强度相当（234→243，26px+），即上下都有阴影。桌面上同一位置只有 4/255，手机（2.625x）更明显
- 一句话方向：顶栏阴影的 blur 半径大于 2×offsetY，模糊上半圈漏到形状外面了。

### D11 · 审批卡 / 工具卡的左侧强调条是等宽直条（严重度：中低）
- 图：`19-desktop-chat-two-pane.png`（`crop-19-approval-leftbar-4x.png`）、`23-phone-chat-material-composer-over-text.png`、`26-phone-u1a-material-chat.png`
- 位置：审批卡（warn 色）与 `shell.exec` 失败卡（danger 色）的左边缘
- 期望（§13）：从圆角弧线起点顺弧走 → 上段 3dp 渐细到 0 → 中段 3dp → 60–70% 处再渐细消失，像「一道光扫下来」
- 实际：**等宽 3dp、方端头**，从卡片顶部直到底部；顶端直角切进圆角，底端也没消失
- 一句话方向：还是矩形块（`Box(width=3dp)` 那一路），没有走 §13.3 的 Path。

---

## 2. 可疑（需设计拍板）

### S1 · 同屏圆角 5 档，规则与组件表自相矛盾
- 图：`21`/`26`/`23`（会话详情）
- 实测同屏可见：`28`（composer）/ `24`（用户气泡）/ `20`（工具卡、审批卡）/ `12`（卡内 ARGS/RESULT 块）/ `pill`（`已压缩`、`置顶`、`risk=high`、发送球外的 chip）+ 全圆（头像）。
- §10 要求「同屏 ≤3 种」，但 §1/§5 的组件表本身就要求 12/16/20/24/28/pill 全用上 → 要么改规则、要么收组件档位。**不是实现 bug，是规范冲突。**

### S2 · 单行 composer 的 28dp 圆角读起来是胶囊
- 图：`21`、`26`（框高 67dp、圆角 28dp，接近胶囊）
- §1 说「`xl` 28dp，多行时同圆角，不做胶囊变形」。现在单行就已是视觉胶囊，多行才会显出「大圆角卡」。需要确认单行是否就该长这样。

### S3 · 附件图标在输入框外
- 图：`21`/`26`/`23`（`crop-23-composer-grid-2x.png`）
- §1 写「输入框内只放：左侧附件图标、文本域、右侧字符计数」；实际回形针在**框外左侧**独立悬浮，输入框内只有占位符。观感上多了一个孤立图标，需确认哪种是设计意图。

### S4 · 两套皮肤的设置分组卡不一致
- 图：`crop-06-profile-othercard-2x.png`（material）vs `crop-07-profile-othercard-2x.png`（miuix）
- miuix 每行之间加了**全宽分割线**（顶到卡片左右边缘、穿进 20dp 圆角区），material 完全没有分割线；miuix 的「设备与通知 / 管理此 Core」标题还比 material 更浅（像禁用），只有「关于」是黑的。同屏三行两种强弱，需要定一套。

### S5 · 顶栏玻璃在纯浅色背景上几乎看不出背后内容
- 图：`12`（`crop-12-topbar-2x.png`）——背后是白色工具卡时，卡内几乎看不到任何轮廓（只有极淡的字形阴影）；背后是红色状态条时（`19` 的 `crop-19-topbar-redline-3x.png`）能看出红晕。
- 判定：模糊采样是好的（有红晕为证），但 26%/34% 的染色在浅底上已接近「盖死」。需要设计确认这是想要的强度还是偏重。

### S6 · `rail` 选中项是 16% accent 方块
- 图：`17-rail-720dp-sessions.png`（`crop-17-rail-top-2x.png`）
- §12 写「选中项用图标填充 + accent，**不用**大色块 pill」；rail 现在是 30dp / 16dp 圆角的 16% accent 底块。它不算 pill，但和 floating 的胶囊、fixed 的纯文字又是三套。

### S7 · 三形态的选中都只有颜色，没有「图标填充」
- 图：`16`、`25`、`17`
- 底栏/rail 的选中图标仍是 Outline（会话=描边气泡、能力=描边四宫格），只换 tint；§3.2/§12 要求「选中态用 Fill 系列」。三处一致，但都与规范不一致 → 要么改规范，要么补 Fill 变体。

### S8 · 产品界面里有开发者容器标签
- 图：`01`（`<600dp · 底栏单栏 · 6 个会话`）、`17`（`600–839dp · NavigationRail 80dp · 6 个会话`）、`18`（`≥1200dp · 双栏 + 右面板 · 6 个会话`）、`15`（`假数据 · 未连接 core（U1-a 只做骨架，U1-b 接线）`）、rail 底部 `U1-a`
- 这些是验收/调试用的容器标签与版本号，正式观感里很出戏。需要拍板：留到调试构建，还是换成用户语言。

---

## 3. 通过（本轮没看到问题）

- **P1 图标全矢量**：底栏四宫格、rail 四项、顶栏搜索/新建/返回/面板、审批卡的 ✓ / ✕ / ⌄、工具卡的刷新/附件/归档、空态大图标全部是描边矢量；**没有任何字符、汉字、emoji 当图标**（`NavRail.kt` 里旧的 13sp 汉字图标已换成 Tabler 矢量，注释与实测一致）。头像里的「R / v / U / 皮 / w / A」是首字母头像，不是图标。
- **P2 安全区**：状态栏（0–63px）没有压住顶栏标题（卡片上沿在 y≈84，留 21px）；底部手势条（2364–2374px）没有压住底栏文字（文字底 2300，留 64px ≈ 24dp）。三种尺寸都成立。
- **P3 底栏玻璃是真模糊**：`16` 的 `crop-16-bottom-2x.png` 里，被压在底栏后面的「AGP 9.5.1」只剩一团灰块（看得出有字、读不出内容），不是 alpha 渐变假模糊。
- **P4 搜索浮层**：`28-miuix-search-overlay.png` 四角 28dp、四周留 12dp、背后列表模糊可见，符合 §9 的「浮卡」观感（这一层没有抓取到缺陷）。
- **P5 静态卡**：活动/能力页的空态卡只用「发丝线 + 底色」（无粗边框），阴影极浅——卡上沿 1–2/255、下沿 3/255，没有过渡硬 rim，也没有 §7.8 的「边框+阴影+底色三重叠加」。
- **P6 发送球**：直径 126px = **48dp 正圆**，与输入框间距 33px ≈ **12.5dp**（§2 要求 12dp），在框外、独立元素；空态=白底+1px 发丝线+灰箭头，有内容=accent 实心+白箭头+对称外发光，生成中=白底+红描边+红方块（不整球变红）。四种态都对。
- **P7 没有 Material 默认紫 / 4dp elevation 堆叠**：material 用 `#5B5BD6`，miuix 换成蓝；除顶栏/输入区外没有看到「卡片都带影子」的 Android 11 味。
- **P8 触摸/行高**：会话行 ≥64dp（411dp 宽下相邻行标题中心距 212px ≈ **81dp**），rail 行 ≥48dp，底栏四项等分。

---

## 4. 逐图判读（图名 → 这张图回答了什么）

> 每张图都按同一张检查表看过：文字压叠/透底/被裁、阴影方向与硬边、圆角是否贴本体、同屏圆角档位、图标是否矢量、组名、安全区、背后内容模糊程度。下表只写**这张图上看到的东西**（正常项写「无…」，异常项直接挂 D/S 编号）。

| # | 图 | 面 / 尺寸 | 判读 |
|---|---|---|---|
| 00 | `00-boot.png` | 冷启首帧 411×914dp | 首屏即会话列表；顶栏浮卡、底栏玻璃都已在位；无闪屏残留 |
| 01 | `01-phone-sessions-top.png` | 会话 / 411×914dp / material / fixed | 文字无压叠；行内「● g-1」= **裸 id（D5）**；顶栏卡上方有 11/255 暗带（D10）；底栏无内容可透，看不出模糊（正常，未滚动） |
| 03 | `03-phone-activity.png` | 活动 / 411×914dp | 三张空态卡：圆角一致、发丝线贴本体、无硬边；底栏背后内容模糊可见（P3）；"暂无运行中的 turn" 与说明行距正常，无裁切 |
| 04 | `04-phone-capabilities-top.png` | 能力 / 411×914dp | 卡片 + 列表行；「下载管理」卡被底栏压住属正常滚动遮挡；顶栏无滚动内容，故无 D2 |
| 05 | `05-phone-capabilities-scrolled.png` | 能力·滚动后 / 411×914dp | **D2 复发**：顶栏卡下沿露着「技能页是原生页（D99）」半行；底栏背后有模糊（P3）；无文字压叠 |
| 06 | `06-phone-profile-top.png` | 我的 / 411×914dp / material | 皮肤 chip（D9 的过期文案 + 英文 id）；「其它」卡三行两种强弱（S4）；顶栏上方 6–11/255 暗带（D10） |
| 07 | `07-phone-profile-miuix.png` | 我的 / miuix | 切皮肤成功（accent 蓝）；设置卡出现全宽分割线（S4）；chip 选中态正确 |
| 08 | `08-phone-sessions-miuix.png` | 会话 / miuix | 与 01 同构；行内 `g-1`/`g-2` 裸 id（D5）；typography 与 material 同族（`VadeFonts.sans` 在 Android 上就是 `FontFamily.Default`，字体缝在 Android 无观感差异） |
| 09 | `09-phone-chat-empty-miuix.png` | 会话详情·空输入 / miuix | 输入框零阴影（D1）；发送球=停止态（红描边+红方块）；顶栏卡上沿露「参数」文字（D2） |
| 10 | `10-phone-chat-typed-miuix.png` | 会话详情·有内容+IME / miuix | 键盘弹起：输入区随 `imePadding` 上移、列表未被顶跳；发送球在框外、accent 实心；焦点 2dp 描边贴本体；状态栏与标题不冲突 |
| 11 | `11-phone-chat-typed-nokb-miuix.png` | 同上·收起键盘 / miuix | 正文 `**原图未保留**`、`` `UP-TO-DATE` ``、```` ```kotlin ```` **原样上屏（D3）**；审批卡左条是等宽直条（D11）；发送球在框外 12dp（P6） |
| 12 | `12-phone-chat-topbar-over-content-miuix.png` | 会话详情·顶栏压内容 / miuix | 卡内模糊成立，但**卡外锐利**（D2）；卡片下角**双轮廓**（D6，5× 裁剪确认）；卡内看不到背后内容（S5） |
| 13 | `13-phone-chat-composer-over-content-miuix.png` | 会话详情·输入区压内容 / miuix | 文字从顶栏上方空隙漏出、清晰可读（D2）；输入框内**完全不透明、无阴影**（D1）；框下方有列表渐隐（这是内容 fade，不是玻璃透底——已用像素取样排除） |
| 15 | `15-phone-sessions-short-top.png` | 会话 / 411×571dp / 未滚动 | 缩短窗口后列表可滚；顶栏/底栏位置不变；此帧内容未滚动，作为 16 的对照 |
| 16 | `16-phone-sessions-scrolled-material.png` | 会话·滚动后 / 411×571dp | **D2 主证据**：上一行预览 + 头像 + 置顶全清晰露在顶栏卡外；底栏背后内容模糊（P3）；手势条不压底栏（P2） |
| 17 | `17-rail-720dp-sessions.png` | rail / 720×1600dp | rail 80dp + 列表 280dp + 空详情；标签 `600–839dp · NavigationRail 80dp`（S8）；选中项=30dp 圆角方块（S6）；行内标题/预览清洁省略，未撞时间列；`g-1` 裸 id（D5） |
| 18 | `18-desktop-1280dp-sessions.png` | 桌面 / 1280×800dp | rail 72 + 列表 300 + 详情空态 + 右上「展开右面板」；列表行「置顶 / claude-sonnet / 5 天前」挤但不重叠；顶栏目标签 `≥1200dp …`（S8） |
| 19 | `19-desktop-chat-two-pane.png` | 桌面·会话双栏 / 1280×800dp | 工具卡红色状态条穿顶栏（D2）；选中行无 3dp 左条（D8）；审批卡左条直条方头（D11）；composer 与停止球在右、气泡 lg 圆角正常 |
| 20 | `20-desktop-chat-right-panel.png` | 桌面·右面板 / 1280×800dp | 右面板 320dp、左发丝线、右面板把顶栏让开（顶栏不压面板）；输入区随面板收窄不出框；无文字压叠 |
| 21 | `21-phone-chat-material-detail.png` | 会话详情·空输入 / material | Markdown 正常渲染（对照 27）；输入框零阴影（D1）；空态球=白底+灰描边+灰箭头；顶栏上方仍露「参数」 |
| 23 | `23-phone-chat-material-composer-over-text.png` | 会话详情·输入区压文字 / material | 输入区不透明、无阴影（D1 二次确认）；框下方渐隐是列表 fade；正文排版正常、无裁切 |
| 24 | `24-phone-profile-floatingbar.png` | 我的 / 411×914dp / floating | 底栏从贴底变成浮胶囊（16dp 边距）；选中=lavender 胶囊（D7）；胶囊自身有下发阴影、无上方暗带 |
| 25 | `25-phone-capabilities-floatingbar-scrolled.png` | 能力·滚动 / floating | 浮胶囊背后的内容没被压住（胶囊下方已无内容）；顶栏下沿仍露半行（D2）；选中胶囊块明显大于 fixed/rail（D7） |
| 26 | `26-phone-u1a-material-chat.png` | 会话详情 / material（同 27 的会话） | Markdown + 代码块卡片 + 「参数预览 · redacted」四行元信息 = 正确基线；「流式中」徽标与「正在生成 · 已 156 字」在输入框上方，未压叠 |
| 27 | `27-u1a-miuix-chat-again.png` | 同一会话 / miuix | **D3/D4 主证据**：`**` / `` ` `` / ```` ``` ```` 原样；工具卡没有 ARGS/RESULT 内层块、也没有 meta 四行 |
| 28 | `28-miuix-search-overlay.png` | 搜索浮层 / 411×914dp / miuix | 28dp 四角、左右约 12–16dp 留白、背后列表模糊可辨（P4）；面板内无文字压叠；顶栏被遮罩压暗属正常 |
| 29 | `29-phone-chat-composer-over-text2.png` | 会话详情·输入区压内容 / miuix | 输入框背后确实是工具卡（`fs.read` / `shell.exec`），框内**一个字都看不到**（不透光，D1）；框外文字被框左沿切断；本条消息的 `**` / ```bash 围栏仍是原样（D3） |
| 30 | `30-chat-after-wait.png` | 同上·等 10s 后 / miuix | 与 29 逐字一致（说明不是「流式中还没解析完」，D3 站得住）；`停止生成` 一直在 |
| 31 | `31-profile-which-skin.png` | 我的 / 411×914dp | A/B 对照帧：确认当时选中的是 **miuix**（蓝 chip），用来排除「其实没切皮肤」 |
| 32 | `32-miuix-third-session.png` | 第三个会话 / miuix | 换一个会话仍然 `**原图未保留**`、`` `UP-TO-DATE` ``、```` ```kotlin ```` 原样 → 不是单个会话的偶发（D3） |
| 33 | `33-material-third-session.png` | 同一会话 / material | 立刻恢复为粗体 + 行内代码 + `kotlin` 代码块卡片（D3 的决定性对照，两次 A/B 都一致） |

---

## 5. 裁剪放大清单（都用 2–4 倍，用于二次确认）

| 文件 | 倍数 | 确认了什么 |
|---|---|---|
| `crop-16-top-2x.png` | 2× | 顶栏卡外文字/头像原样清晰（D2） |
| `crop-16-bottom-2x.png` | 2× | 底栏背后是真模糊（P3） |
| `crop-12-corner-5x.png` | 5× | 顶栏下角「第二道轮廓」硬边（D6） |
| `crop-12-topbar-2x.png` / `crop-11-topbar-2x.png` | 2× | 卡片本体有模糊、卡外没有（D2/S5） |
| `crop-13-top-2x.png` | 2× | 「细节见 RFC-0004 §6.1」压在状态栏与顶栏之间、清晰可读（D2） |
| `crop-13-composer-2x.png` / `crop-23-composer-grid-2x.png` | 2×（带坐标刻度） | 输入框边界、框外文字、框下渐隐 → 确认「不是透底、是完全不透光」（D1 的判据修正） |
| `crop-19-topbar-redline-3x.png` | 3× | 红色状态条卡内模糊、卡外锐利（D2） |
| `crop-19-approval-leftbar-4x.png` | 4× | 审批卡左条=等宽直条、方头、贯穿全高（D11） |
| `crop-19-row2-4x.png` | 4× | 选中行无 3dp 左条、chips 不重叠（D8） |
| `crop-17-rail-top-2x.png` / `crop-17-row-2x.png` | 2× | rail 与顶栏不重叠；280dp 列表省略号干净 |
| `crop-06-topbar-topedge-3x.png` | 3× | 顶栏上沿外侧的灰色暗带（D10，阴影方向） |
| `crop-06-profile-othercard-2x.png` / `crop-07-profile-othercard-2x.png` | 2× 对裁 | 两套皮肤设置卡的分割线差异（S4） |
| `crop-25-floatingbar-2x.png` | 2× | 浮胶囊 ↔ 选中 lavender 胶囊（D7） |
| `crop-10-topbar-2x.png` / `crop-10-statusarea-3x.png` / `crop-10-sliver-5x.png` | 2× / 3× / 5× | 键盘弹起时「顶栏上方有一条碎字」的复核：只是状态栏时钟字形被裁的下沿，不是 App 文字 → 排除误判，未记账 |
| `crop-11-toolcard-2x.png` / `crop-11-approval-2x.png` / `crop-11-composer-2x.png` | 2× | miuix 下工具卡/审批卡/输入区的细节（D3、D4、D11 的辅证） |

---

## 6. 附注：截图外的代码旁证（供定位，不算独立缺陷）

1. **D11 的实现线索**：`crop-19-approval-leftbar-4x.png` 显示它是**等宽 3dp 直条、方端头、从卡片顶部直到底部**；§13 要求「顺圆角弯 → 上段 3→0 渐细 → 60–70% 处消失」。`19`/`21`/`23`/`26` 四张都一致，属实现未做 Path。严重度中低。
2. **禁用的 4–8dp 圆角**：`shell/NavRail.kt:110` 角标 `RoundedCornerShape(7.dp)`（13dp 见方）。当前 `pendingApprovals = 0`，这个角标不上屏，所以截图里看不到；但一旦有待审批就会出 7dp 圆角，属禁用档位。其余 `2dp` 用法（grabber、细条）是规范允许的。
3. **`ProfileScreen` 的占位文案**（D9 的根）：`"miuix / glass 是占位实现（返回 material token + 未安装角标）…"` 是 U1 时代留下的字符串，U2 已把 miuix 注册成真实现，字符串没跟着改。

---

## 7. 未覆盖 / 限制

- **暗色主题**：Android 入口无外部开关（见 0.2），本轮全部为亮色。
- **`glass` 皮肤**：仍是 placeholder（`MaterialSkin("glass","[glass 未安装]")`），没有可验收的真实皮肤。
- **真实 core 数据**：全部走默认 `FakeSessionSource`；D5（裸 `g-1`）只证明**假源路径**，真源（adapters 非 null）未验证。
- **动态效果**（发送球按压、列表项 120ms、弹层 200ms）是静态截图，未验；如需可另跑录屏棒。
- **桌面 ≥1200dp 的右面板内容**只有「暂无模块页面」空态，未验真实模块卡。
- 会话详情是「流式中」的假数据，`流式中` 徽标与「正在生成 · 已 156 字」在截图中多次出现，可能与真实生成态观感不同。
