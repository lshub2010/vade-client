# g-model-chip · 设备与桌面证据说明

会话页模型选择的合规形态：composer 内小 chip（触发点）+ §9 floating sheet card（选择面）。
内联 `ModelPickerCard`（插在 header 下面、把会话往下顶）已从会话页移除；组件本身保留
（ProfileScreen 设置分区的「默认模型」卡不变，仍用它）。

## 截图（emulator-5554，adb screencap，全程未触碰真机 192.168.1.180）

| 文件 | 状态 | 看什么 |
|---|---|---|
| `01-detail-composer-chip.png` | 手机宽度（1080x2400 ≈ 411x914dp） | composer 输入面板内左下角的 chip「✦ 全局默认 ⌄」，与发送球同一行；header「模型 全局默认 · 6 条消息」行**无** chevron、不可点 |
| `02-sheet-open.png` | 手机宽度，点 chip 后 | §9 浮层：底部悬浮卡、左右/底部 12dp 空隙、四角 28dp、顶部 36×4dp 把手、标题行（✦ 会话模型 + ✕）、「跟随全局默认」行、模型行、脚注；遮罩 = 玻璃 + 24% 黑，背后内容隐约可见 |
| `03-selected-notice.png` | 选中 Qwen2.5 32B | 行选中态（accent 边框 + ✓ + 11 字段 detail）+「模型已切换：Qwen2.5 32B（下一轮对话生效）」提示行；header 行即时变「模型 Qwen2.5 32B」 |
| `04-chip-label-updated.png` | 关闭浮层后 | chip 文字即时变「✦ Qwen2.5 32B ⌄」（乐观选择，不关浮层等回包的设计见 ModelPickerSheet KDoc） |
| `05-short-window-chip.png` | `wm size 1080x1500` | 短窗口下 chip 仍在 composer 底行 |
| `06-short-window-sheet.png` | 短窗口，浮层打开 | 卡片完整可见（把手/标题/模型行/脚注都在屏内），不溢出 |

`wm size reset` 已执行（恢复 Physical 1080x2400，Override 已清）。

## 交互链路（设备实测）

点 chip → 浮层打开 → 点模型行 → 行选中 + 提示行 + header/chip 即时更新 → ✕ 关闭 →
chip 显示新模型。遮罩/Esc 关闭由桌面 UI 测试覆盖（`ModelChipSheetUiTest`，Esc 走焦点注入）。

## 已知限制（如实记录）

1. **真应用中浮层玻璃不出模糊**（截图 02/06 中卡片背后文字有淡影）：haze 2.1.0 硬规定
   「hazeEffect 节点不得绘制祖先 hazeSource」（`glass/GlassBridge.kt` 结构规则）。AppShell
   把整个 ShellBody 注册成唯一 hazeSource（AppShell.kt 的 `asGlassSource(glass)`），所以
   **所有页内玻璃**（本浮层、composer、附件预览浮层）都是 source 的后代 → 模糊结构性不可用；
   顶栏/搜索浮层是 source 的兄弟，模糊正常。修复 = shell 侧把浮层挪到 source 之外或换注册面
   —— AppShell.kt 是本棒禁改文件，未动。
2. 按 §5 的退化形状（tint + 近实底）给卡片内容垫了 88% `surfaceGlass`（`sheetReadabilityBacking`，
   与 composer 的 `glassFallback` 同配方），保证模型行可读；遮罩保持玻璃 + 24% 黑（背后仍
   隐约可见，符合 §9「不是纯黑 60%」）。shell 结构修好后拆掉这一行即可恢复全玻璃。
3. Esc 在真应用里会被 shell 根部的预览键处理先消费（Esc = 返回上一级，AppShell.kt
   `handleShellKeys` 既有行为）——浮层自身的 Esc 处理已实现并经桌面测试验证，应用内要生效
   需要 shell 把「浮层开着」纳入快捷键路由，同为 shell 侧工作。

## 桌面测试

`composeApp/src/desktopTest/kotlin/vade/ui/model/ModelChipSheetUiTest.kt`（10 条，全绿）：
几何断言 chip 与发送球同行且在 header 之下；「展开/收起模型列表」（内联卡的语义签名）在
会话页绝不允许出现；开浮层时首条消息 y 不动；浮层结构（标题/行/脚注）+ 标题在 header 之下；
遮罩点击与 Esc 关闭；选中走 seam 写入（值 = 协议 model_id）；长名 chip 宽度 ≤148dp、单行、
发送球不被挤走；无模型/只读三态 chip 文案 + 浮层诚实正文；关态无浮层节点。
