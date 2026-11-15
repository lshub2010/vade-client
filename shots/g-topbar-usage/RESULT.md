# g-topbar-usage · 会话顶栏 = 会话标题 + 用量，整体淡入淡出替换

日期：2026-11-15 · 分支 `tmp/g-topbar-usage`（基点本地 `main` 5e5067c = origin/main 的严格超集，origin fetch 当晚 SSL 握手失败，无新提交可拉）

## 一、交付了什么（对齐任务书四件事）

1. **接上用量**：`RealSessionSource.usage`（`session/usage.get`，此前**生产调用点 = 0**）经
   `destinations/activitySourcesOf` 的晚绑定窄口接进详情顶栏。进入会话详情拉一次；
   `turn.completed` 事件到达 → **失效并重拉一次**（见 §四偏差 1）；并发重拉由
   `UsageLoader`（g-usage-wire 交付）的代数守卫兜住，晚到回包丢弃。加载中 / 失败 /
   无数据 / 草稿一律**不显示副标题**——绝不编数字。
2. **副标题 = 用量一句话**：`«量» tokens · «轮» 轮`（tokens 取 `chat.totalTokens`，轮次取
   `chat.turns`，与「轮次取 chat.turns」同一口径、只取 chat 视图）。格式化是纯函数：
   `<1k` 原样 / `<1万` 一位小数 `k`（整档去尾）/ `≥1万` 一位小数 `万`，整数十分位
   half-up，不经 Double 字符串化。
3. **标题对整体淡换**：`AnimatedContent(targetState = title to subtitle)` +
   `fadeIn(tween(200)) togetherWith fadeOut(tween(160))`，`SizeTransform(snap, clip=false)`、
   `contentAlignment = TopStart`——**无位移、无缩放、无裁剪**。切会话、列表⇄详情、
   详情⇄列表三种情形走同一段过渡（见 §三：AppShell 只保留单一 TopBar 调用点是前提）。
   `TopBar` 新增可选形参 `animate: Boolean = true`（false = 瞬时替换，测试/无动画场景）。
4. **删掉过期假话**：`会话详情 · 消息流 / 工具卡为假数据` 已删；顶栏不再写内部实现状态。

## 二、文件清单

| 文件 | 动作 |
|---|---|
| `shell/TopBarUsage.kt` | **新增**：loader 持有（`SessionUsageBarLoader`：真源透传 `UsageLoader` 三态/代数守卫/retry/dispose；夹具路径同步 Ready 零协程，与 `UiState` 夹具纪律同构）+ 纯函数 `formatTokenCount` / `sessionUsageSubtitleText` + 假源确定性数字 `fixtureUsageResultOf` + 组合层 `rememberSessionUsageSubtitleLine`（状态带 sessionId 的 key 纪律） |
| `shell/TopBar.kt` | 修改：新增 `animate` / `onSubtitleClick` 可选形参（追加，不破坏既有调用）；标题对抽成 `TopBarTitlePair`（**必须 Column 包两行**，见 §五）；AnimatedContent 淡换 |
| `shell/AppShell.kt` | 修改：TopBar 调用点合并 + 删假话（逐行见 §三） |

越界（均为测试/守卫，理由见 §四偏差 3）：`desktopTest/…/IconGuardTest.kt`、
`icon/GlyphClearTest.kt`、`shell/AppShellAdapterWiringTest.kt`（"万" 放行 + 判别器换新）。

## 三、AppShell.kt 逐行改动（任务书要求逐行列出）

**说明：实际 +50/−32 行，超出任务书「≤6 行」预算。超预算的原因不是顺手改动，而是结构必要性：**
原 `if (detailOpen) { TopBar(详情) } else { TopBar(列表) }` 的两个分支是**两个组合组**，
`detailOpen` 翻转时整个 TopBar 实例被销毁重建，TopBar 内部 AnimatedContent 的过渡状态
随之丢失——「列表⇄详情也要走同一段淡换」只有**单一调用点**（表达式择值，不产生第二个
组合组）能做到。逐行如下（相对基点 5e5067c）：

1. `import androidx.compose.foundation.layout.RowScope`（新增 1 行：`listActions` 的显式类型需要）
2. 删 `if (detailOpen) {`（旧分支包装）
3. 删 `TopBar(`（详情分支）
4. 删 `title = session.title,`
5. 删 `subtitle = "会话详情 · 消息流 / 工具卡为假数据",`（**任务书第 4 件事：假话删除点**）
6. 删 `leading = { TopBarButton(VadeIcons.Back, …) },`
7. 删 `)`（详情分支 TopBar 收尾）
8. 删 `} else {`
9. 删 `TopBar(`（列表分支）
10. 删 `title = state.dest.label,`
11–14. 删 `subtitle = buildString { append(metrics.tier.label) … }` 四行（原样上移为 `listSubtitle`）
15. 删 `actions = {`
16–27. 列表分支 actions 块原样**降一级缩进**保留（搜索/新建/右面板三个按钮，逻辑零改动）
28. 删 `},` / `)` / `}`（旧 if/else 收尾）
29. 增注释四行：为什么必须单一调用点（组合组销毁过渡状态）
30. 增 `val listSubtitle = buildString { … }`（内容与原来逐字相同，只是上提）
31. 增 `val usageLine: UsageSubtitleLine = when { !detailOpen || session == null || session.isDraft -> None; else -> rememberSessionUsageSubtitleLine(state.source, session.id) }`（详情态才持有 loader；草稿不发注定失败的请求）
32. 增 `val detailLeading: (@Composable () -> Unit)? = if (detailOpen) { { TopBarButton(Back, …) } } else null`（原详情 leading 原样搬移）
33. 增 `val listActions: (@Composable RowScope.() -> Unit)? = if (detailOpen) null else { { …原 actions 块… } }`（显式类型：if-表达式里的 @Composable lambda 经参数位推断不过，实测）
34. 增 `TopBar(` 单一调用：
    - `title = if (detailOpen && session != null) session.title else state.dest.label`
    - `subtitle = when { !detailOpen -> listSubtitle; usageLine is Text -> …; usageLine is Retry -> …; else -> null }`（加载中/无数据/草稿 → 不显示）
    - `onSubtitleClick = (usageLine as? Retry)?.onRetry`（失败态重试入口）
    - `leading = detailLeading,`
    - `actions = listActions,`

## 四、与任务书的偏差（逐条，带理由）

1. **「事件里带 usage 就用它，不重复请求」没有照字面实现，改为失效+重拉。**
   契约原文（`contracts/vade/schema/events.json` `turn.completed`，冻结）：payload 的
   `usage` 是**该轮**的 `prompt_tokens / completion_tokens / total_tokens`（RFC-0005 append
   加 model_id/steps 等），**不是会话总量**；会话总量与轮次只在 `session/usage.get` 的
   `chat` 视图（「`chat` sums terminal turns」）。事件里拿不到 `turns`，拿单轮 total 冒充
   会话总量就是编数字（任务书自己的红线）。且事件 payload 无 `session_id`（契约原文），
   无法按会话过滤。故 `turn.completed` → 失效并**重拉一次** `session/usage.get`
   （`SessionUsageBarLoader.onTurnCompleted`，KDoc 引了契约原文）。请求成本：每完成
   一轮一次 RPC，与 `SessionsLoader` 对同一事件的列表重拉同量级。
2. **AppShell 改动 +50/−32 行 > 「≤6 行」预算**：理由见 §三开头——if/else 双组合组会
   销毁 AnimatedContent 状态，单调用点是三种情形同一段过渡的结构前提。改动全部落在
   TopBar 调用那一处（+1 行 import），无其他函数被触碰。
3. **三个测试/守卫文件越界**（任务书未列）：
   - `IconGuardTest.kt` + `GlyphClearTest.kt`：新文件里 `"万"`（量级后缀文案）触发字符/
     汉字图标守卫；确属文案，按守卫自己的出口在 `IconGuardTest.allowed` 写理由放行
     （`TopBarUsage.kt:70:万`），并同步 GlyphClearTest 的期望集合。
   - `AppShellAdapterWiringTest.kt`：`thePushedDetailReceivesTheBundle` 原以
     `PUSHED_DETAIL_SUBTITLE = "会话详情"`（旧假话）作 pushed-detail 判别器——假话已删，
     判别器换成用量副标题（"tokens" 节点，仅 pushed 分支的顶栏有），并按守卫更新规则
     「换照片、断言意图不动」处理；两 pane 测试的对应否定断言同步换为
     `"tokens" == 0`。unused 常量删除。

## 五、设备实拍抓到并修掉的一个真 bug（诚实记录）

第一版 `TopBarTitlePair` 直接吐出标题/副标题两个兄弟 UiText——AnimatedContent 的内容
容器是 **Box 语义**，两行叠在同一位置（设备截屏上副标题 "6.6k tokens · 5 轮" 与标题
重叠成一团鬼影）。**桌面测试全绿着放行了它**：「左沿 x 相等」断言分不清叠放与竖排，
像素 alpha 断言也穿透了叠层——这道防线只有真机抓得住。修复 = Column 包两行
（commit bc2f424），KDoc 写明。教训：过渡/几何类交付，桌面断言 + 真机目视缺一不可。

## 六、测试（desktopTest，全量 1256 条）

新增 21 条（3 个文件）：
- `TopBarUsageTest`（13）：格式化五种（0/999/1.2k/123456→12.3万/null）+ 档位边界 + 整句拼装 + 夹具确定性 + loader 夹具同步/失败重试 1→2/无数据 chat=null/turn.completed 重拉/切会话代数守卫（闸门构造迟到回包）。
- `TopBarCrossfadeTest`（3）：animate=false 瞬时无中间态；animate=true 进场层/离场层各一条——中途帧两层同在语义树 + 两层左沿 x、顶沿 y 相等 + **像素级 alpha∈(0,1) 三帧比对**（F1≠F0 ⇒ alpha<1；F1≠F2 ⇒ alpha>0）。
- `SessionUsageTopBarWiringTest`（5）：AppShell 假源详情顶栏 = 标题+用量、假话消失、切会话换数、列表顶栏回归「会话」、真源无数据不显示（连 "0 tokens" 都不出现）、失败重试入口点一次请求 1→2。

全量门禁（--rerun，最终树）：
```
desktopTest --rerun : tests=1256 failures=0 errors=0, BUILD SUCCESSFUL, EXIT=0
assembleDebug      : BUILD SUCCESSFUL in 3m 1s, composeApp-debug.apk 22,321,315 B
compileKotlinWasmJs: BUILD SUCCESSFUL, EXIT=0
```
（中间轮次的三处红均为本批改动引发的守卫/判别器更新，已按 §四.3 处理后全绿。）

## 七、设备证据（emulator-5554，本批构建）

> **口径**：设备走**夹具源**（app 默认，未接 core）——数字是 `fixtureUsageResultOf`
> 按 session id 确定性派生的假数字（任务书明示「假源：显示假源数字（不报错）」）；
> **真源路径**（`session/usage.get` 原文数字）的正确性由 §六 的 21 条测试覆盖（含
> 窄口强校验、三态、失败重试）。安装护栏：install 前后 `md5sum base.apk` 与本地 APK
> 一致（80f88bda…），截图期间未被并发批次覆盖。

- `detail-rfc0004.png`：进会话后的顶栏——`RFC-0004 附件与多模态评审` / `6.6k tokens · 5 轮`，两行清晰分离，无假话。
- `detail-matrix.png`：切到另一会话后的顶栏——`vade core 构建矩阵升级` / `6.5k tokens · 12 轮`，**数字变了**（确定性派生，与第一条不同）。
- 淡换本身是 160–200ms 的过渡，静态截屏拍不出中间帧；中间态的存在性由
  `TopBarCrossfadeTest` 在虚拟时钟下逐帧证明（两层同在 + alpha∈(0,1) + 左沿相等）。

## 八、已知限制

- 事件无法精确按会话过滤（payload 无 session_id）：任何 `turn.completed` 都会重拉**当前
  显示的**会话一次；代数守卫保证不串数字，最坏只是一次多余请求。
- `turn.completed` 重拉期间副标题会淡出再淡入（Loading 不显示是任务书口径）；若想
  stale-while-revalidate 需另开口径。
- 失败态的重试入口是副标题位的小号可点文字（非 44dp 图标按钮）；§3 的 ≥44 规则
  约束图标按钮，文案链接未按图标按钮处理。如需升格为图标按钮，`actions` 槽位已就绪。
