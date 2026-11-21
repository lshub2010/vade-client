# F8 · ask_user 客户端弹框与答复链路

分支 `tmp/f8-askuser`（基于 `main` @ 0d16ec3）。契约：RFC-0008（`turn.input_required` /
`turn/input` / `turn/input.cancel` / `turn.input_resolved`），事件 payload 与 RPC 模型沿用
仓库里已有的 `TurnInputRequiredPayload` / `TurnInputResolvedPayload` / `TurnInputParams`。

## 做了什么

### 1. 状态层（`chat/ChatState.kt`、`chat/ChatInputs.kt`）

- `pendingInputs: SnapshotStateList<PendingInput>`、`currentInput`（最上面那张）、
  `hasPendingInput`；`turn.input_required` 进列表（同一 `input_id` 重放幂等，不叠卡）。
- 读者作答状态是纯客户端的：`updateInputDraft`（free_text）与 `toggleInputOption`
  （single = 替换，multi = 切换；不认识的 option id 一律忽略）。
- `submitInput` 按 kind 组装 `turn/input.value`：
  - `free_text` / `single_choice` → `InputValue.Text`（trim 后非空才可提交）；
  - `multi_choice` → `InputValue.Multi`，**按选项显示顺序**归一（点击顺序不影响帧）；
  - `confirm` 走 `submitConfirm(true|false)` → `InputValue.Flag`，不从选中态猜。
- `cancelInput` → `turn/input.cancel(reason = "user_cancel")`，只关问答、回合继续；
  `timeoutInput` 只做本地摘卡 + `回答超时（未作答），回合继续`，**不发 cancel**
  （core 的超时自己会发 `turn.input_resolved(status=timeout)`；`default` 永远不会变成答案）。
- `turn.input_resolved`（任意 status）→ 按 `input_id` 关掉**那一张**；未知 id 是 no-op。
- 提交 / 取消失败：卡片留在原地 + 四类口径的可读状态行（可重试），不假装送达。
- 时钟可注入（`clock`，默认 `epochNowMs()`）：倒计时 / 超时判定在测试里是确定性的。

### 2. 出网通道（`chat/ChatTurns.kt`、`data/TurnGatewayReal.kt`）

- `ChatTurnGateway` 新增 `answerInput(sessionId, inputId, value)` 与
  `cancelInput(sessionId, inputId, reason)`，默认实现是**响亮失败**（与 submit/interrupt
  同一条约定：真通道忘了接，界面永远停在「等你回答」比报错更糟）。
- `TurnGatewayReal` 适配到 `VadeClient.turnInput` / `turnInputCancel`，`input_id` 原样上帧；
  `ok=false` 且 status 不可识别才报 `NOT_FOUND`（别处作答 / 超时是正常收尾，交给
  `turn.input_resolved`）。`InMemoryTurnGateway` 显式 no-op（演示路径没有 core）。

### 3. 界面（`chat/ChatInputDialog.kt`、`chat/ChatThread.kt`、`destinations/SessionDetailScreen.kt`）

- 模态问答卡画在 **ChatThread 自己的层叠里**（不是平台 `Dialog`）：遮罩只覆盖聊天内容链，
  表头 / 玻璃顶栏是它之后绘制的兄弟，**不被遮挡且仍可点击**（有回归测试）。
- 独立点击吞噬层：卡片外点击不会漏到消息行 / composer。
- 四种卡体：
  - `free_text`：`BasicTextField`（占位「输入回答…」、最多 5 行、无障碍名「回答输入框」）；
  - `single_choice` / `multi_choice`：选项行 = `selectable` + RadioButton / Checkbox 角色，
    选中态自带语义；选项多时卡内滚动（上限 360dp）；
  - `confirm`：`是，继续` / `否，不继续` 两键，提交布尔。
- 键盘：卡一出现即取焦点；`Esc` 取消（preview，焦点在输入框/选项里也生效）；
  `Enter` 在选择类提交、自由文本 `⌘/Ctrl+Enter` 提交（裸 Enter 换行）；confirm 不绑定
  Enter（方向不靠猜），Tab + Enter 走两键。策略抽成纯函数 `inputKeyAction` 供测试钉住。
- 读屏：卡根 `contentDescription = "问答对话框：<prompt>。<kind>"`，标题 heading，
  倒计时、选项、输入框各有名字。
- 倒计时只读 `expires_at`（250ms 刷新读数；到点调 `timeoutInput` 自动关窗 + 提示）。
- `SessionDetailScreen` 把 `hasPendingInput` 并入 `overlayOpen`：问答期间底栏让路，
  不会「切走标签页把问题留在后台」。

## 测试（`desktopTest/chat/AskUserFlowTest.kt`，15 条）

| 验收点 | 用例 |
| --- | --- |
| 事件 → 弹框 | `inputRequiredOpensTheModalDialogWithPrompt`、`duplicateInputRequiredFramesDoNotStackTwoDialogs` |
| 提交 → RPC value 正确 | `submittingFreeTextSendsTheRpcValueAndCloses`、`emptyFreeTextCannotBeSubmitted`、`singleChoiceSendsOnlyTheLastPickedOption` |
| 多选 | `multiChoiceSendsEverySelectedIdInDisplayOrder`、`multiChoiceUiTogglesOptionsAndSubmitsThem` |
| confirm 两键 | `confirmSendsABooleanFromEitherKey` |
| resolved → 只关对应 id | `resolvedClosesOnlyThatInput`、`resolvedForAnUnknownInputIsANoOp` |
| 超时 → 关窗 + 提示 | `expiredInputClosesItselfAndSaysTheTurnContinues`、`countdownReadsExpiresAt` |
| 键盘 | `escapeCancelsFromInsideTheTextField`、`keyPolicyMatchesTheFourKinds` |
| 不遮挡顶栏 | `theModalLeavesTheHeaderSiblingClickable`（表头仍可点到） |

全部走真 `ChatState` + 真 `ChatThread`，假通道只替换传输层并记录帧参数（session_id /
input_id / value）。

## 已知边界

- 不新增依赖、不改协议层：事件与 RPC 模型用仓库已有实现。
- 多张问答卡并存时按到达顺序逐张弹（`currentInput` 是第一张），一次答一张。
- 问答期间底栏让路依赖宿主消费 `onOverlayChange`（`AppShell` 既有链路，本批未改）。
- 真实 core 的 `turn/input` E2E 未在本批加（CI 的 real-core 仅跑既有用例）；本批验证到
  「帧参数正确 + 状态闭环」为止。
