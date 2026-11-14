# W1 · miuix 皮肤升 v2 · RESULT

分支 `tmp/w1-miuix`（自 `main` @ `802c532`）。范围：**只改 `composeApp/src/commonMain/kotlin/vade/ui/skin/miuix/`**。
C-09 的 11 个 Composable 接口签名零改动；`Skin.kt`、`chat/`、`shell/`、`search/`、`build.gradle.kts` 均未动（`git diff 802c532..HEAD --name-only` 见下）。

> 代码 commit：**`3b5a283`**（v2 主体）+ **`c6f18b5`**（截图自查修复）。未 push。

## 改了什么

| 文件 | v2 落地点 |
|---|---|
| `MiuixTokens.kt` | ① `shapes` 对齐 **六档**：别名 small/medium/large 按名指向 **16/20/24**（=sm/md/lg），并显式写出 xs12/sm16/md20/lg24/xl28/sheet32；`radiusXs` 8→12、新增 `xlRadius` 28、`sheetRadius` 28→32。② 新 token：`canvas`/`hairline`/`surfaceGlass`/`shadowSoft`(6%/35%)/`shadowFloat`(18%/55%)/`glowAccent`/`textOnGlass`。③ 新组件度量：`composerMargin` 16 / `sendBallSize` 48 / `sendBallGap` 12 / `bubbleTail` 6。④ 字号 20/15/13/11 四档**不动**。⑤ KDoc 全部从 v1 阶梯（8/12/16/20/28）改写为 v2 六档口径 |
| `MiuixSkin.kt` | ① **Card**（静态卡）= hairline + `shadowSoft`（2dp 极浅）；② **BottomBar** 三态全部 64dp + 无选中 pill（保持）+ **hairline 顶边 + `shadowFloat`**（Fixed 顶边=HorizontalDivider，胶囊两态=1dp 描边+外阴影）；③ `glass` 覆写：亮色 `hairlineOnGlass` 用黑 20%（默认白 25% 在亮色玻璃上不可见）；④ 输入区委托传入 skin |
| `MiuixParts.kt` | ① **InputBar 重写为悬浮 composer**（v2 §1/§2）：四周 16dp 留白独立浮层 + `xl` 28dp 圆角 + 玻璃底（`surfaceGlass`）+ 1dp hairline（聚焦 2dp accent）+ `shadowFloat`；**框内无发送按钮**，发送=框外右侧 48dp 独立圆球（12dp 间距、垂直居中）：有内容=accent 实心+12dp `glowAccent` 径向辉光 / 空=玻璃+发丝线 muted ↑ / 流式中同球位变 ■ 只染图标与描边（不整球变红）；按压 scale 0.92 / 180ms（v2 曲线）。② **ApprovalDialog** 从贴底 sheet 改 **§9 悬浮卡片**：左右/底 12dp 留隙、四角 32dp 同圆、玻璃底+hairline+`shadowFloat`、36×4dp 手柄（亮=黑24%/暗=白70%）、遮罩统一 0.24。③ **用户气泡** lg(24)+固定 6dp 尾（`bubbleTail`，不随阶梯走）。④ **ToolCallCard / AttachmentChip** = hairline + `shadowSoft`（静态卡层），附件卡半径 sm→**md 20**（§10 附件卡）。⑤ RiskTag/徽标 10sp → 11sp（字号纪律：不回 8–10sp）；重试 → ↻ 图标 |
| `MiuixShowcase.kt` | 顶栏 批注/发送 → ⋯/↑ 图标；组件矩阵五态按钮改图标演示（↑/＋/✕/⋯/＋禁用）+ 11sp 说明标签 |

**禁止项自查**：无 Material 紫（accent 为 #2A62D4/#5B8DEF 蓝系）、无大面积 tonal 色块、无 FAB、无 4dp elevation 堆叠（阴影按 §8 分层，静态卡 2dp/浮层 12–16dp）。

## 验证（真实输出）

```
gradle :composeApp:desktopMainClasses      → EXIT=0（两轮）
gradle :composeApp:desktopTest --rerun     → BUILD SUCCESSFUL
build/test-results/desktopTest/TEST-*.xml  → tests=92 failures=0 errors=0 skipped=0
  ChatFlow 23 + Markdown 13 + ImportGuard 2 + BlurBox 12 + BlurMath 10 + BlurTri 13 + Search 19 = 92
```

## 截图（`shots/w1-miuix/`，6 张，逐张开图核过）

> 口径要求 4 张（手机 480×900 亮/暗 + 桌面 1280×800 亮/暗）；另加 2 张审批态（亮手机/暗桌面）用于核 §9 悬浮卡片。DWM 帧比请求窗口小 ~14×7px（capture-window.ps1 已知口径）：480×900 → 466×893，1280×800 → 1266×793。

| 文件 | 尺寸 | 核对结论 |
|---|---|---|
| `phone-light-showcase.png` | 466×893 | 亮手机。composer **悬浮**（左右 16dp 留白、xl 圆角、玻璃底、阴影），发送球在框外 accent 实心+辉光；顶栏 ⋯/↑ 图标按钮；工具卡 hairline+浅影；底栏顶发丝线、无 pill |
| `phone-dark-showcase.png` | 466×893 | 暗手机。玻璃底在黑底上以 #1C1C1E@78% 抬起可辨；发送球辉光清晰；文字对比正常无灰糊 |
| `desktop-light-showcase.png` | 1266×793 | 亮桌面。悬浮 composer 全宽适配 560+ 布局无错位；球+光晕在框外；fs.write 卡发丝线+浅影；1.18× 桌面密度下一切正常 |
| `desktop-dark-showcase.png` | 1266×793 | 暗桌面。同上形态；浮层阴影在暗色下更深；球辉光可见 |
| `phone-light-approval.png` | 466×893 | §9 悬浮卡片：四周留隙、四角 32dp、黑 24% 手柄条、玻璃底+发丝线+阴影；三按钮竖排危险在末；标题/倒计时清晰 |
| `desktop-dark-approval.png` | 1266×793 | 同上暗色；白 70% 手柄；遮罩 24% 黑、背后会话仍隐约可见；宽 560dp 上限生效（未拉满窗口） |

**四件 v2 必查**：输入区是**浮**的 ✓ / 球在框外 ✓ / 按钮全是图标 ✓ / 审批卡是悬浮卡片（§9）✓。浮层均有阴影、无文字按钮（顶栏/矩阵/发送/重试均图标）、无 alpha 渐变假模糊、无 Material 紫、无 4dp elevation。

## 与 material 皮肤的差异表

| 维度 | material（v1 现状） | miuix v2（本批） |
|---|---|---|
| 圆角阶梯 | 8/14/22（v1） | **12/16/20/24/28/32** 六档 + pill（SkinShapes 新值对齐） |
| 助手消息 | 有容器/气泡感 | **无气泡正文**，全宽 ≤760dp |
| 用户气泡 | 20dp 圆角 | **lg 24dp + 6dp 气泡尾**（右下角） |
| 静态卡 | hairline、无阴影 | hairline + **shadowSoft**（2dp 极浅） |
| 输入区 | 贴底通栏条 | **独立悬浮浮层**：四周 16dp、xl 28 圆角、玻璃底、shadowFloat；聚焦 2dp accent 描边 |
| 发送控件 | 框内 38dp 圆形按钮 | **框外 48dp 独立圆球**：accent+辉光 / 玻璃+发丝线 / 流式 ■ 只染图标描边；按压 0.92/180ms |
| 底栏 | 顶 hairline、无阴影 | 三态全部 + **shadowFloat**（Fixed 顶发丝线，胶囊 1dp 描边） |
| 审批弹窗 | 贴底 sheet，顶角 28dp | **§9 悬浮卡片**：12dp 留隙、四角 32dp、玻璃底、36×4 手柄、遮罩 0.24 |
| 阴影/辉光 | 无 | §8 分层：静态卡 shadowSoft / 浮层 shadowFloat / 主行动 glowAccent 辉光 |
| 字号 | 20/15/13/11 | 20/15/13/11（一致，miuix 未回 8–10sp） |
| 强调色 | Material 蓝 #2A62D4 系 | 同为蓝系（miuix 亮 #2A62D4 / 暗 #5B8DEF），无 Material 紫 |

## 已知偏差与后续（如实记录）

1. **玻璃底是 §5 的「无 haze」退化形**：`surfaceGlass`(78% 表面色) + hairline + `shadowFloat`。真 backdrop blur 需要宿主注册 `LocalGlassHazeState` + `Modifier.hazeSource`，其接线点在 `chat/`/`shell/`（本批禁改目录）；`Skin.glass` 的 §5 26/34 blur tint 已备好，接线后一行换 `GlassSurface` 即可点亮真模糊。
2. **审批卡玻璃透底**：玻璃语义下背后卡片文字隐约可见（§5 判据内：「看得出有内容」）；标题/倒计时/参数块/按钮全部清晰可读（参数块为不透明黑/灰底）。
3. **消息动作（复制/重试）与底栏 glyph 是页面数据**：`MessageAction.label`、`NavItem.glyph` 由页面传入（`chat/`/showcase 之外的页面数据），皮肤按字符串渲染；showcase 侧已全部改图标，真实页面（chat/）的文案不在本批范围。
4. **矩阵条（TextField 三态/ListItem/Switch）在手机与桌面截图的折叠线以下**：视口只到工具卡；其按钮已按图标构造（构造保证），不影响四件必查。
5. 越界改动：**无**（diff 全落在 `skin/miuix/` + `shots/w1-miuix/`）。
