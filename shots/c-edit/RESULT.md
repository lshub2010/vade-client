# C-edit · v1.1 会话三交互 — 编辑重发 / 重新生成 / 附件上传 UI

批次：`tmp/c-edit`（客户端仓 worktree `D:/HermesWorkspace/Vade-client-wt/c-edit`，基于 `main@f189de2`）
代码提交：`800c41b`（本报告为报告提交，位于其后）
契约来源：主仓 `schema/rpc.json` `directions/ui_to_core` 的 `message/edit_resubmit` / `turn/regenerate`；`schema/events.json` 的 `message.edited` / `turn.superseded`（字段一律以契约为准）

## 验证（原始数字）

- `desktopTest`（`composeApp/build/test-results/desktopTest/TEST-*.xml` 的 `tests=`/`failures=` 属性汇总，非 BUILD SUCCESSFUL 自证）：
  **tests=176 · failures=0 · errors=0 · skipped=1**
  - skipped 的 1 条是既有 `VadeClientSmokeTest.attachSubmitAndLiveEventsAgainstARealCore`（`AssumptionViolatedException: VADE_SMOKE_PORT`，环境门控跳过，未改动）。
- 本批新增 3 个套件，共 **22 条**（均 0 failures / 0 skipped）：
  - `EditResubmitFlowTest[desktop]`：tests=9 · failures=0
  - `RegenerateFlowTest[desktop]`：tests=5 · failures=0
  - `AttachmentUploadFlowTest[desktop]`：tests=8 · failures=0
- 既有套件保留核对：`ChatFlowTest` 28 条 / 0 failures（基线原样保留，无删无 skip）；`IconGuardTest` 4 条、`MaterialImportGuardTest` 2 条均 0 failures。
- 其他目标编译：`:composeApp:compileKotlinWasmJs` BUILD SUCCESSFUL（commonMain 改动对 wasm 目标无破坏）。
- 按任务书要求**未拍截图**（截图脚本会 kill java 进程，影响并行构建）。

## 实现点（对照任务书 3+1 条）

1. **编辑重发（`message/edit_resubmit`）**：最后一条用户消息（`canEditMessage` = 最后一条用户消息 ∧ 非排队中，对应契约 "only the last user message is editable"）hover 显示铅笔按钮（`VadeIcons.Compose` 矢量、44dp 触摸区、contentDescription「编辑这条消息并重发」）→ **内联编辑卡替换气泡本身，不是弹窗**：`lg`(24) 圆角 + 发丝线 + `Modifier.layeredShadow(VadeShadows.float)`，多行 foundation 输入域（冻结的 `Skin.TextField` 是 singleLine，页面走与 composer 相同的 foundation 先例），✓ 确认（空内容禁用）/ ✕ 取消，确认 = ⌘/Ctrl+Enter、取消 = Esc（`handleEditKeys` 纯函数）。确认后 `confirmEdit()` 以**原 message_id** 调网关；成功后行内投影 `message.edited`：气泡文本就地更新 + 「已编辑」徽标，附件原样保留；流式中重发进队列（`QueuedTurn.reason="edit_resubmit"`，取消该队列项**不删除**原行）；失败（网关抛错）编辑器保持打开、原文未动、`lastNotice` 提示可重试。
2. **重新生成（`turn/regenerate`）**：`canRegenerate` = 最后一条助手消息 ∧ 终态（Completed/Interrupted/Failed/Cancelled）∧ 无流式 ∧ 无排队 ∧ 未被取代过（对应 `data.reason=turn_active|not_tail|not_head` 的客户端前置拒绝）；只在满足条件的助手行下渲染 `VadeIcons.Refresh` 按钮（44dp、contentDescription「重新生成这条回复」）。成功后旧行**灰化保留**（`alpha 0.55`，绝不删除）+ 「旧版本」徽标（`ChatMessage.superseded` → `toView()` 徽标），新助手回合在其下流式生成；`turn_id` 传的是旧助手回合 id（内存投影中助手消息 id 即 turn id）。
3. **附件上传 UI**：composer 左侧改回形针按钮（`VadeIcons.Attach` 矢量、44dp、contentDescription「添加附件」）→ `stageUpload` 加 chip（上传中 0%，走 `Skin.AttachmentChip` 既有组件 + `attachments` 槽）→ 进度由通道事件驱动（chip 内既有进度条文案「上传中 NN%」）；**上传中可取消**（chip ✕ = 取消协程 job + 移除 chip，迟到事件一律丢弃不复活）；**失败可重试**（chip ↻ = 同一 chip 原位回到 0%，不新增 chip）；同文件未完成时重复点回形针拒绝加第二条 chip；**上传中点发送不带未完成附件**（`submit()` 只带 `Uploaded` 的 chip，未完成的留在 composer 继续走）；发送后完成的 chip 转成 `ContentPart.Image/File` 进消息 parts。本轮无文件选择器（shell 层职责）：点回形针从内置样例 `ChatFixtures.nextPending()` 取假文件走完整真实通道流程。
4. **测试 22 条**：任务书 10 项断言全覆盖——① 网关收到原 message_id（`resubmitPassesTheOriginalMessageIdAndDraftContent`）② content 与输入框一致（同上）③ 「已编辑」标记（`editedRowWearsTheBadge...`）④ 空内容拒绝（`blankDraftCannotBeResubmitted` + 键盘用例）⑤ regenerate 仅最后一条（`onlyTheLastAssistantMessageCanRegenerate`）⑥ 旧版本保留并标记（`oldVersionIsKeptMarkedSuperseded...`）⑦ 进度 0→100%（`uploadProgressRunsFromZeroToOne`，runBlocking 确定性泵观察到 0.25/0.5/0.75/1.0 中间值）⑧ 失败重试不重复加 chip（`failedUploadRetriesWithoutDuplicatingTheChip`）⑨ 取消移除 chip 且迟到事件不复活（`cancelUploadRemovesTheChipAndLateEventsAreIgnored`）⑩ 上传中发送不带未完成附件（`sendingDuringUploadExcludesTheUnfinishedAttachment`）。编外加固：编辑/重生成失败路径、排队重发取消不删原行、重复 pending 去重、单轮上限、只发一条 chip 的端到端（blob part 进消息）。

## 接口设计（两个可注入窄接口，均在 `vade/ui/chat/`）

```
// AttachmentUploader.kt —— 真 blob 通道（/ui/blob 六路由）只需实现这一个 fun interface
fun interface AttachmentUploader {
    suspend fun upload(request: PendingAttachment, uploadId: String,
                       onProgress: (Float) -> Unit): String   // 返回 blob_id；失败抛 UploadFailedException
}
class InMemoryAttachmentUploader(steps=5, pause={ yield() })  // 本轮内存假实现

// ChatTurns.kt —— 接真 RPC 只需包一层 vade.ui.protocol.VadeClient
interface ChatTurnGateway {
    suspend fun editResubmit(sessionId, messageId, content: List<ContentPart>, clientMsgId): EditResubmitReceipt
    suspend fun regenerate(sessionId, turnId, clientMsgId): RegenerateReceipt
}
class InMemoryTurnGateway()                                   // 本轮内存假实现
```

- 注入点：`ChatState(sessionId, script, preset, uploader, turns)` 构造参数（`destinations/SessionDetailScreen.kt` 的既有调用 `ChatState(session.id, preset = preset)` 不受影响，未改它）。
- 取消语义：**页面持有协程 job**（`ComposerOverlay` 内 `snapshotFlow` 驱动、每个 chip 恰好一个 job）；`cancelUpload` = cancel job + 移除 chip + 记入 `cancelledUploads`（迟到 progress/completion 全部丢弃）；`runUpload` 对 `CancellationException` 原样重抛（结构化取消不吞）。
- 页面驱动：`snapshotFlow { staged.map { uploadId to state } }`，chip 回到「上传中」（重试）自动再起 job；`animate=false`（截图模式）时不跑。
- 进度契约：onProgress 单调 0→1、成功前最后一步=1f；blob id 由通道返回，`ChatState` 自己把 pending+blob 组装成 `ContentPart`（通道不碰视图模型）。

## 新增 / 修改文件清单

新增（commonMain，均在 `vade/ui/chat/`）：
- `ChatTurns.kt` — ChatTurnGateway + 两张 receipt + InMemoryTurnGateway
- `AttachmentUploader.kt` — PendingAttachment / UploadFailedException / AttachmentUploader / InMemoryAttachmentUploader

新增（desktopTest，`vade/ui/chat/`）：
- `EditResubmitFlowTest.kt`（9）、`RegenerateFlowTest.kt`（5）、`AttachmentUploadFlowTest.kt`（8）、`RecordingTurnGateway.kt`（测试替身）

修改（全部在 `vade/ui/chat/`）：
- `ChatState.kt` — 编辑/重生成/上传三段状态与动作；`canSend`/`submit()` 改为只带已完成的附件；`cancelQueued` 对 `edit_resubmit` 只撤回合不删行；`tickUploads` 只驱动遗留 fixture chip（uploadId==null），通道 chip 只听事件；`StagedAttachment` 增 `uploadId`/`pending` 可选字段
- `ChatThread.kt` — hover 编辑按钮 + 内联编辑卡 + `handleEditKeys`；重生成按钮行；旧版本 alpha 灰化；回形针按钮；上传 job 运行器；StagedAttachments 取消/重试回调
- `Models.kt` — `ChatMessage` 增 `edited`/`superseded` 可选字段；`toView()` 追加「已编辑」/「旧版本」徽标
- `ChatIcons.kt` — 新增 `ChatVectorIconButton`（`vade.ui.icon.VadeIcon` 矢量 + 44dp 触摸区 + contentDescription）
- `ChatFixtures.kt` — 增 `nextPending()` 内置样例（回形针无选择器时的假文件）

**改动的皮肤文件：无。** 三个功能所需的槽位全部已存在：`MessageBubble.body`（markdown 槽，编辑后文本就地刷新走它）、`InputBar.attachments`（chip 槽）、`AttachmentChip`（含进度/↻/✕）；`Skin.kt` 十二组件契约（`MaterialImportGuardTest` 计数=12）未动，material/miuix/glass 三皮肤实现零改动、零风险。`VadeIcons.kt` 也未追加（Attach/Compose/Refresh/Check/Close 均已存在）。

## 未接线清单（下一棒接手点）

1. **真上传通道**：实现 `AttachmentUploader`（对接 `/ui/blob` 六路由：multipart 上传 + complete 轮询/事件），传入 `ChatState(uploader = ...)` 即可——`ChatState`/`ChatThread` 无需再改。真取消 = 取消调用方协程（接口已是 cancellation-cooperative）。进度事件如来自协议层事件流，把流接到 `onProgress` 回调即可。
2. **真文件选择器**：`PendingAttachment` 的来源目前是 `ChatFixtures.nextPending()`；平台选择器（desktop JFileChooser / Android SAF）属 shell 层，接好后把真 `PendingAttachment(name/mime/size/width/height)` 传进 `stageUpload`。本轮点击回形针上传的是内置样例假文件（芯片、进度、取消、重试、发送全流程为真，字节为假——页面 `MockImage` 占位符同样只画图不读字节，与任务书"不许假上传显示假成功"的口径一致：UI 全真、字节通道待接）。
3. **真 RPC**：实现 `ChatTurnGateway`（包一层 `vade.ui.protocol.VadeClient.messageEditResubmit` / `.turnRegenerate`，字段名/schema 已对齐），传入 `ChatState(turns = ...)`。receipt 目前只用于成功/失败分支，未消费 `version`/`queue_pos` 展示。
4. **`message.edited` / `turn.superseded` 事件源**：当前为客户端本地投影（confirmEdit/regenerate 成功即改行）；接事件总线后应改为事件驱动（`ChatState` 的投影字段已按事件 payload 形状命名，换源不动视图）。多端去重键（`superseded_by_message_id` / `(turn_id, superseded_by)`）在事件接线时实现。
5. **触屏长按入口**：编辑按钮目前是 desktop hover 显示（`hoverable`）；触屏 long-press 入口未做（desktopTest 是本批门槛，手机端留到 U2/U3 触控批）。
6. **未消费的 receipt 字段**：`EditResubmitReceipt.newMessageId`（本轮原行就地投影，未插入新用户行）、`version`、`queue_pos`（本轮 busy 时按本地队列语义处理）——事件接线时统一。

## 越界说明

无。`git show 800c41b --stat` 全部落在 `composeApp/src/commonMain/kotlin/vade/ui/chat/`、`composeApp/src/desktopTest/kotlin/vade/ui/chat/` 与本报告（`shots/c-edit/`）。未动 `protocol/`、`data/`、`shell/`、`destinations/`、`meta/`、`input/`、`usage/`、`subagent/`、`build.gradle.kts`、`skin/**`、`icon/VadeIcons.kt`。未 push、未合并。
