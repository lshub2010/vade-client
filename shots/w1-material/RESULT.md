# w1-material · RESULT — material 皮肤 v2 观感

- 分支 / worktree：`tmp/w1-material` @ `D:/HermesWorkspace/Vade-client-wt/w1-material`（基线 `main` @ `802c532`）
- 代码 commit：**`b616f39`**（先 commit 后写报告；未 push）
- 改动文件（`git diff --name-only 802c532..b616f39`）：
  - `composeApp/src/commonMain/kotlin/vade/ui/skin/material/MaterialSkin.kt`（改写）
  - `composeApp/src/commonMain/kotlin/vade/ui/skin/material/MaterialParts.kt`（改写）
  - `composeApp/src/desktopTest/kotlin/vade/ui/MaterialSkinV2Test.kt`（新增）
  - `shots/w1-material/`（4 张截图 + 本文件）
- 接口零改动：`Skin.kt` 未触碰（防回退测试 `skinContractHasExactlyElevenComponents` 仍绿）；
  `MaterialSkin` 构造签名未变（`Skins.kt` 的占位注册不受影响）；11 个 Composable 签名逐字未动。

## 改了什么（对照任务书逐项）

1. **六档圆角（§10）**：`shapes` 显式落 `xs=12 / sm=16 / md=20 / lg=24 / xl=28 / sheet=32`（+pill）；
   v1 别名 `small/medium/large` 骑在抬升后的档位上（MiuixParts 等读别名的地方不受影响）。
   组件取用：用户气泡 `lg`+右下 6dp 尾；工具卡/附件卡 `md`；chip/缩略图/代码块 `xs`；徽标 pill；
   悬浮 composer `xl`；审批弹窗 `sheet`；输入框 `sm`；列表行 `sm`。
2. **消息**：助手全宽 ≤760dp 无气泡（原有行为保留）；用户气泡 `lg`（24）+ 右下 6dp 气泡尾 + hairline。
3. **静态卡 / 浮层 / 主行动（§8）**：Card、ToolCallCard = hairline + `shadowSoft`（2dp elevation，
   黑 30% tint）；浮层（composer、审批卡、Floating 底栏）= `shadowFloat`（16dp elevation，黑 50% tint）；
   审批主行动按钮带 `glowAccent` 辉光（accent@35% 的 6dp 低投影，读成柔光不是阴影）。
   阴影全部中性黑（除 accent 辉光），无 4dp elevation 堆叠、无 FAB、无大 tonal 色块。
4. **输入区悬浮（§1）**：`MaterialInputBar` 内部从贴底满宽改为独立浮层——四周留白 16dp、
   `xl`(28) 圆角（多行同圆角）、`surfaceGlass`（白 @84%）玻璃底、1px hairline（聚焦升 2dp accent）、
   `shadowFloat` 浮层阴影。文本域直接落在玻璃面板上（不再嵌内层框，同屏圆角 ≤3 种）。
   **框内发送控件已移除并标注**：冻结签名里的 `onSend/onStop/sendEnabled` 保留、原样转发，注释写明
   发送球由页面层画在框外（48dp 正圆、accent 辉光、■ 停止态）；⌘Enter 路径不受影响。
   ⚠️ 页面层的球属于 chat/ 槽位——本分支截图里输入区右侧还没有球，属预期中间态。
5. **按钮纯图标（§3）**：material 实现不再产生文字形态的发送/停止（源码级测试钉死）；
   `Button` 的 text 参数保留——单字形按 18sp 图标渲染且可点面积 ≥44dp 见方，多字文案按
   次要字号渲染；`BtnStyle.Text` 恒为透明底 + accent 文字的次要样式，绝不变成蓝底填充。
   附件 chip 的重试/删除改为 `↻` / `✕` 图标（原来各有一个"重试"/"×"文字钮），点击区 44dp。
   截图里仍可见的 `+ 附件` 文字钮与"正在生成 · 点 ■ 停止"提示是 chat/ 页面经 attachments
   槽位传入的（按次要 ghost 样式渲染），归 chat/ 槽位改。
6. **代码块**：`surfaceVariant`（= surfaceSunken #F0F0F2）+ `xs`(12) 圆角 + 顶部标签行
   （工具卡参数/结果标 `ARGS`/`RESULT`，审批参数标 `ARGS`）。
7. **触摸目标**：按钮/列表行/chip 行/chip 图标钮/消息操作行/开关行/底栏项全部 ≥44dp（44 同时满足桌面 ≥32）。
8. **去 Material 紫（§七.5）**：删除 `material3.OutlinedTextField` 与 `material3.Switch`——
   无 M3 主题时它们的聚焦描边/选中态正是 `#6750A4` 系；两者改 foundation 原语重写
   （TextField：sm 圆角 + surface 底 + hairline + 聚焦 2dp accent + error danger；
   Switch：52×32 轨道 + 24dp 圆钮 + 44dp 可点行，token 全自管）。
9. **BottomBar 三形态玻璃化**：Fixed = `surfaceGlass` + 顶部 hairline（高 64）；Floating =
   浮层玻璃 pill + `shadowFloat` + hairline；FloatingBlur = **`skin/glass/GlassSurface`**（真 haze 语义，
   替换了被 §5 明令禁止的垂直 alpha 渐变假模糊）。根 `hazeSource` 由 shell/ 槽位接线后
   FloatingBlur 即真模糊；未接线时按 GlassSurface 文档降级（hairline + 阴影），不再撒谎。
10. **审批弹窗 v2 化（§9）**：遮罩黑 24%、四周 12dp 空隙、四角同圆 `sheet`(32)、玻璃底 + hairline +
    浮层阴影、顶部 36×4 grabber（黑 24%）、风险徽标改 warn/error@12% 小 pill、参数块换带标签代码块。
    文案按钮（仅本次/本会话/拒绝）保留——这是 §3 例外口径：该组件只服务模块页面/管理面，
    scope 语义图标表达不了；聊天流的内联审批卡（✓/✕/⏷ 图标尾）是 chat/ 槽位的活。
11. **ListItem**：行高 ≥56、`sm` 圆角、选中 = `accentSoft`（accent@8%）+ 左侧 3dp accent 条。

## 测试

`gradle :composeApp:desktopTest --rerun`（Gradle 9.6.1，`GRADLE_USER_HOME=D:/Hermes/tools/gradle-home`）：
**97 tests，0 failed，0 errors，0 skipped**（基线 92 + 新增 5，`build/test-results/desktopTest/TEST-*.xml`
`tests=` 合计：23+13+2+5+12+10+13+19）。`desktopMainClasses` 绿。
新增 `MaterialSkinV2Test` 五条：六档阶梯与别名、相邻档差 ≥4dp、石墨靛色板（非 Material 紫）、
玻璃/阴影 token 分层且半透明、源码级防回退（无紫字面量 / 无 M3 主题组件 / 无文字发送按钮）。
`MaterialImportGuardTest` 两条原样绿。

## 截图（shots/w1-material/，真实窗口 PrintWindow 抓图，已逐张开图自查）

| 文件 | 参数 | 自查结论 |
|---|---|---|
| `mobile-chat-live.png` (466×893) | 480×900 · `-Open s-rfc0004`（流式中） | composer 四周留白 + xl 圆角 + 阴影清晰浮起；无紫；工具卡 md 圆角 + 状态条；ARGS/代码标签在；框内无发送按钮 |
| `mobile-chat-compose.png` (466×893) | 480×900 · `-Open s-rfc0004 -Chat compose` | 附件 chip md 圆角、✕/↻ 图标钮、28×28 xs 缩略图；用户气泡 lg+右尾；bash 代码块带标签；5 行草稿在玻璃面板内；无紫 |
| `desktop-chat.png` (1266×793) | 1280×800 · `-Open s-rfc0004` | 双栏布局；选中列表行 = accentSoft + 左 3dp 条；composer 悬浮；kotlin 代码块带标签；全图无 Material 紫、无 4dp elevation 观感 |
| `mobile-sessions.png` (466×893) | 480×900 · `-Dest sessions` | 底栏玻璃底 + 顶部 hairline，选中项靛蓝图标+文字（无色块 pill）；顶栏卡片 hairline；无 FAB/tonal 块 |

四条硬判据：**输入区是浮的 ✓、无 Material 紫 ✓、按钮全图标（皮肤实现内）✓、圆角明显 ✓**。

## 圆角清单（本皮肤落点 → 档位）

| 落点 | 档位 | 值 |
|---|---|---|
| chip / 徽标 / 代码块 / 缩略图 | `xs` | 12dp |
| 列表行 / 次要按钮 / 输入框（TextField） | `sm` | 16dp |
| 工具卡 / 附件卡 | `md` | 20dp |
| 用户气泡（+右下 6dp 尾） | `lg` | 24dp |
| 悬浮 composer | `xl` | 28dp |
| 审批弹窗卡（四角同圆） | `sheet` | 32dp |
| 主按钮 / 徽标 pill / 状态标签 | `pill` | 999dp |

同屏圆角种数 ≤3（消息流场景：xl composer / md 卡 / xs 代码块）。

## 边界与后续（如实记录）

- 真背景模糊：composer 的玻璃底目前是 `surfaceGlass` token 面（浮层质感完整）。composer
  浮在列表之上的页面结构由 chat/ 槽位所有，根 `hazeSource` 由 shell/ 槽位接线；两者就位后
  把 `MaterialInputBar` 的底换成 `GlassSurface` 是本文件内的一行级改动（BottomBar.FloatingBlur 已先行接上）。
- 发送球在框外（§2）由 chat/ 槽位绘制；`colors.glowAccent` / `surfaceGlass` 已备好 token。
- chat/ 页面传入的 `+ 附件` 文字钮与 hint 文案不在本槽位文件内，按次要样式渲染中。
- 新增 5 条测试使总数 92→97（任务书"不能少"口径）；未动 `build.gradle.kts`，未引入任何新依赖。
- 未 push；`cp-w1material.txt` 为截图脚本的 classpath dump（同 `cp.txt` 惯例不入库）。
