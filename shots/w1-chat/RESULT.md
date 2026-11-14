# W1-chat · 会话详情 v2（悬浮 composer + 框外发送球 + 内联审批 + §9 弹层）

批次：`tmp/w1-chat`（客户端仓 worktree `D:/HermesWorkspace/Vade-client-wt/w1-chat`，基于 `main@802c532`）
日期：2026-11-14
规范：`docs/visual-language.md` v2 §1–§9（§11 运动模糊按用户拍板**废弃**，本页未调用 `motionBlur`）

## 验证

- `desktopMainClasses`：BUILD SUCCESSFUL
- `desktopTest --rerun`：**97 tests / 0 failures / 0 errors**（基线 92 全保留；`ChatFlowTest` 23→28，+5 条内联审批用例，无删减）
- 测试数取自 `composeApp/build/test-results/desktopTest/TEST-*.xml` 的 `tests=` 属性（非 "BUILD SUCCESSFUL" 自证）

## 实现点（对照任务书 7 条）

1. **悬浮 composer（§1）**：`ChatThread` 改为「全高滚动列 + 底部浮层」结构。浮层左右 16dp（桌面 ≥600dp 时 24dp）、距底 16dp、`GlassSurface` 玻璃底（`skin.shapes.xl`=28dp 圆角、1px 发丝线、`shadowFloat` 浮层阴影）；输入框内只有 ＋ 附件图标、多行文本域（六行封顶内部滚动）、空态占位文案，**无发送按钮**。列表底部内边距 = composer 实测高度（`onSizeChanged` 上报）+ 24dp。
2. **框外发送球（§2）**：48dp 正圆独立于玻璃容器，右侧间距 12dp、垂直居中。有内容 = `primary` 实心 + `glowAccent` 径向柔光（外扩 12dp，0.28→0 渐隐）+ 反白 ↑；空 = `surfaceGlass` + `hairline` + muted ↑；流式 = 同一球位 ■ 停止，`danger` 只染图标与 1.5dp 描边，球底不变红。按压 `scale 0.92` / 180ms / `Skin.motion.easing`。空态可点但 `submit()` 对空文本是 no-op，提示走占位文案（不造假）。
3. **纯图标（§3）**：页面所有动作 = `ChatIcons`（`ChatThread.kt` 同包 `ChatIcons.kt`）Canvas 单线宽手绘：↑ 发送、■ 停止、＋ 附件、✓ 允许、✕ 拒绝/关闭、⏷ 详情；`⋯`/`‹` 留在字形库里给 shell（会话页无此控件位）。每个图标按钮 `contentDescription` 齐全、触摸区 ≥44dp。旧 composer 的「发送/停止」文字 pill、「＋ 附件」「全部取消」文字按钮、预览的「×」文字全部移除。
4. **审批内联（§4）**：`isApprovalCard(call, outcome)` 判定的时间线行渲染为**内联审批卡**而非 `ToolCallCard`：卡头「需要批准」+ `risk` 徽标 + 工具名，卡体脱敏 `args_preview`（凹陷 mono 块），卡尾 ✓/✕/⏷ 图标按钮；⏷ 展开批次/发起时间等 meta 行。状态由 `ChatState` 驱动（`decideApproval`/`expireApproval`，即 `turn.approval_resolved` 的客户端投影）：待批准 → 已允许/已拒绝/已超时，**同一张卡就地演进 + T+N 经过时间，不新增任何消息行**（测试钉住 `items.size` 不变）。`ApprovalDialog` 组件原样保留给模块页面/管理面。卡样式 = §8 row 4：发丝线 + 极浅阴影 + 左侧 3dp 状态条，无红色辉光。
5. **全局弹窗形态（§9）**：图片预览改为悬浮卡片：遮罩 = 玻璃层（矩形）+ 24% 黑（不再用 86% 纯黑），卡片四周 12dp、`skin.shapes.sheet`=32dp 同圆角、玻璃底 + 浮层阴影 + 发丝线、顶部中央 36×4dp/2dp 圆角手柄（亮色黑 24% / 暗色白 70%）、标题行 ✕ 关闭。同一时刻唯一弹层。
6. **Markdown（§6）**：代码块圆角 `sm(16)` → `xs(12)`，凹陷底色 + 顶部语言标签保持；正文 15sp/1.55（原有）；助手消息 ≤760dp 无气泡（皮肤 C-09 行为，未动）。
7. **动效（§7）**：composer 高度 `animateContentSize(200ms)`、发送球按压 180ms、审批卡插入 fade+12dp 上滑 180ms，全部 `Skin.motion.easing`；结果态只变颜色不位移。**未调用 motionBlur**（§11 已废弃）。

## 截图清单（`shots/w1-chat/`，真实窗口 PrintWindow 截图）

| 文件 | 尺寸 | 状态 | 自查 |
|---|---|---|---|
| `phone-stream.png` | 466×893（480×900 - DWM 边框） | `-Open s-rfc0004 -Chat live` | 消息流：底部锚定；工具卡 meta / 内联审批卡 / 流式尾 / 停止球（红环红■，球未变红）；composer 悬浮、圆角明显；无文字按钮 |
| `phone-composer.png` | 466×893 | `-Chat compose` | 多行输入+发送球：玻璃卡内附件 chips（上传中 54%）+ 五行草稿（第六视觉行截断内部滚动=设计封顶）；accent 实心球反白 ↑ 在框外；无重叠无透字 |
| `phone-approval-card.png` | 932×540 | 同上图的 2× 裁切特写 | 内联审批卡：卡头「需要批准 + risk=high 徽标 + fs.write」、卡体脱敏 args、卡尾 ✓绿 ✕红 ⏷灰、左侧 3dp 琥珀状态条、20dp 圆角 + 浅阴影 |
| `desktop-live.png` | 1266×793 | `-Size 1280x800 -Chat live` | 双栏：左会话列表右详情；详情内 composer 玻璃卡（24dp 边距）+ 框外停止球；审批卡内联；代码块 xs 圆角 + 语言标签 |

**逐张开图自查结论**：球在框外（glass 卡与球之间 12dp 空隙清晰）；按钮全图标；审批内联在时间线触发位（非弹窗）；composer/sheet 圆角肉眼可辨（28/32dp）。遗留可见瑕疵：无。流式帧停在与 U1-b 基线相同的 13 tick 处（见坑 2）。

## 需要 RFC / 后续批次的坑

1. **C-09 `InputBar` 冻结签名装不下 v2 composer**：皮肤实现自带「发送/停止」**文字** pill（§3 违规）且无 `showSend=false` / 自定义发送控件槽位；`TextField` 是 `singleLine`。本批不改 `Skin.kt`，composer 用 foundation 原语 + `GlassSurface` + 页面级图标按钮在页面层组装（与 `UiText`/`StatusPill`/Markdown 渲染器同一页面级先例）。正解 = 给 `InputBar` 加**可选参数**（冻结规则允许），需一次单独的追加提交。
2. **真模糊未接线**：shell 未提供 `LocalGlassHazeState`、列表未 `hazeSource` 注册 → `GlassSurface` 全站退化成薄染色，composer 背后文字整段透过（§5 反面判据双侧失败）。本批在玻璃**内容层**垫 `surfaceGlass@88%` 兜底（`LocalGlassHazeState` 有值时自动消失）；接线要改 `shell/`（本批禁区）→ 下一批统一接。
3. **帧时钟冻结 → 会话锚底改为结构化**：无头/被遮挡窗口下 Compose Desktop 停帧后 `delay()` 驱动的效果全部停摆（本批实测 8s 截图与 2.6s 字节级相同；U1-b 基线同样停在第 13 tick）。滚动列改为 `verticalScroll(reverseScrolling = true)`：offset 0 = 内容末端，**首帧即最新**，不依赖后置帧；`snapshotFlow` 顺序 collect + 非挂起重断言做 FR-8.35 追新。遗留：桌面滚轮在长内容下跟随 reverse 语义，后续换 `LazyColumn(reverseLayout = true)` 时一并归位。
4. **`surfaceSunken` token 不存在**：`SkinColors` 冻结色板无 sunken，代码块用 `surfaceVariant` 表达凹陷层；正名走可选 token 追加。
5. **审批超时无计时源**：内联卡「已超时」由 `expireApproval()` 显式驱动（协议事件缺位时不造假倒计时）；接 `turn.approval_resolved` 后换事件驱动。
6. **§9 手柄交互未接**：下拉关闭/Esc 未实现（无手势路径，不做假交互）；关闭 = ✕ 图标 + 点遮罩。
7. **§4「列表顶部小圆点提示」**（未处理审批时）需要会话列表联动，超出本批目录，留后续。

## 越界说明

无。`git diff` 全部落在 `composeApp/src/commonMain/kotlin/vade/ui/chat/` 与 `desktopTest`（首轮提交 `7563120`；本轮为截图诊断后的锚底/兜底/预设修正 + 交付物）。
