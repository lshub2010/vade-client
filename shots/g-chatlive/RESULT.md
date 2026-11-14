# g-chatlive：协议事件 → 聊天行的纯投影器（LiveTranscript）——原始证据

分支 `tmp/g-chatlive`（基于 main @ `c95c558`）。代码 commit：`d23d6d5`（实现 + 测试），本文件单独 commit。
本文件只贴实测输出与设计事实，不含结论性词汇。

## 1. 交付物（`git diff --name-only c95c558..HEAD` 全集）

- `composeApp/src/commonMain/kotlin/vade/ui/chat/LiveTranscript.kt` — 纯投影器 + `ChatState.applyLive` 扩展（优先级 2 钩子）
- `composeApp/src/desktopTest/kotlin/vade/ui/chat/LiveTranscriptTest.kt` — 23 条测试
- `shots/g-chatlive/RESULT.md` — 本文件

**未改任何既有文件**（`ChatState.kt` / `ChatThread.kt` / `Models.kt` / `RealSessionEvents.kt` 均未触碰）；未引入 kotlinx-serialization / @Serializable（事件已是解码后的 `vade.ui.protocol.VadeEvent` 对象，无需 codec）；commonMain 无 JVM 专有 API（无 `java.*` / `String.format` / `Math.*` / `@Volatile`）。

## 2. 投影器签名

```kotlin
class LiveTranscript(seed: List<ChatMessage> = emptyList()) {
    fun apply(event: vade.ui.protocol.VadeEvent): List<ChatMessage>   // 输入事件流，输出可渲染全量快照
    val messages: List<ChatMessage>
    var changedIds: Set<String>   // 本次 apply 实际新增/替换的行 id；被丢弃的事件为空集
    var lastSeq: Long             // 已应用的最高连续 seq
    var duplicates: Int           // seq <= lastSeq 的幂等丢弃计数（重连重放是预期流量，不算异常）
    val anomalies: List<TranscriptAnomaly>   // 乱序丢弃/缓冲溢出/未知编辑目标等，绝不静默
    fun usageOf(turnId: String): VadeUsage?   // completed/failed/interrupted 的 RFC-0005 usage 侧信道
    fun failureOf(turnId: String): TurnFailure?   // failed 回合的 C-04 code + message + seq
    fun messageById(id: String): ChatMessage?
    companion object { const val PENDING_CAP = 64; fun liveRowId(turnId: String): String /* "live:<turn_id>" */ }
}
```

投影行为（全部有测试钉住）：

| 事件 | 行为 |
| --- | --- |
| `turn.delta` | 文本按 `(turn_id, part_index)` **累加**（StringBuilder 追加，不覆盖）；仅 `kind=text` 进气泡正文（reasoning/notice/tool_args 不进，也不开行） |
| `turn.completed` | 行定稿 `Completed`；usage 存入 `usageOf`（`ChatMessage` 无 usage 字段且 Models.kt 冻结——侧信道是刻意形状） |
| `turn.failed` | **产生错误行**：C-04 码进 badge 且 `failureOf` 可读；已有部分文本则保留，无文本则 wire message 兜底正文；`usage` 可选入库 |
| `turn.interrupted` | partial 文本**保留**；`partial_chars` 优先取 wire 值，缺省按**码点**计数（照 SnapshotMessages 规则，👍=1 不是 UTF-16 的 2） |
| `message.edited` | 对应行**就地替换**（同 id 同位），新文本投影 + 「已编辑」，附件随行；按 `superseded_by_message_id` 去重 |
| `turn.superseded` | 旧行标 `superseded`（「旧版本」）且保留不删 |
| `turn.queued/started/cancelled` | 记录 seq、不产行（用户气泡是本地回显/快照行，`turn.queued` 无文本可渲染） |
| 其余 26 个 topic | 忽略不崩，不计异常，seq 照常推进 |

seq 纪律（RFC-0007 §5.5）：只有 `seq == lastSeq + 1` 才投影。`seq <= lastSeq` → 幂等丢弃计入 `duplicates`（重放全序列转录完全不变）；`seq > lastSeq+1` → 先缓冲（cap 64，溢出丢最旧并记 `PendingOverflow`）等补洞；**coalesced** 帧跨缺口直接接受（自包含，被跳过的都是可丢帧）；中途 attach 的首个事件 bootstrap `lastSeq` 到入口点并记 `BootstrapGap`。助手行用稳定合成 id `live:<turn_id>`——`partial_message_id` 不中途换 key（行身份稳定优先，快照路径负责真 id）。

## 3. 优先级 2 结论：ChatState **能**被外部驱动，钩子已交付

`ChatState` 有公开可变集合入口：`val items = mutableStateListOf<ChatItem>()`（含 `messages` 只读视图）。因此本轮**不需要改 ChatState.kt**——钩子以扩展函数形式落在 LiveTranscript.kt：

```kotlin
fun ChatState.applyLive(transcript: LiveTranscript, event: VadeEvent): List<ChatMessage>
```

语义：跑一次投影，然后只把 `transcript.changedIds` 里的行按 id upsert 进 `items`（原地替换或尾部追加）；被丢弃的事件 `changedIds` 为空 → 天然重放安全；fixture 行与 live 行 id 不相交，互不干扰。调用方持有 `LiveTranscript` 实例（每屏一个），页面在收集事件流的 `LaunchedEffect` 里逐事件调它即可。测试 `applyLiveDrivesChatStateItemsFromTheEventStream` 实测：fixtures 行数 +1、正文「真数据管道」、状态 Completed、重放不再增行。

ChatState.kt 解冻后的持久归宿（签名 + 位子）：

```kotlin
private val live = LiveTranscript()                        // 放 `private val seed` 旁
fun applyLiveEvent(event: vade.ui.protocol.VadeEvent): List<ChatMessage>   // 放 tick()/stop() 旁
    = vade.ui.chat.applyLive(live, event)
```

注意：若另一棒把 `items` 改为私有/只读，该扩展会编译失败——届时上面的两个 hook 位子就是迁移指引。

## 4. 测试（23 条，全部新写）

穷举清单逐条落位：delta 累加不覆盖 · 两片 delta 拼真 e2e 句（w-e2e §2 原文 seq 6/7/8，usage 9/12/21 逐字段）· 同 seq 重放不追加 · 全序列重放幂等 · 乱序缓冲等补洞（到达序≠seq 序）· 旧 seq 幂等丢弃 · coalesced 缺口跳进 · 中途 attach bootstrap · edited 就地替换不增行（附件随行）· edited 未知行记异常 · superseded_by 去重 · interrupt 保留 partial（码点计数 👍=2）· interrupt 取 wire partial_chars · 无 delta 的 failed 产错误行（C-04 码）· 有 partial 的 failed 保留文本 · 空 delta 无害 · 非 text kind 不进正文 · 双 turn 有序两行 · lastSeq 全程单调 · 无关 topic 干净忽略 · superseded 标记不删行 · 无文本 completed 也定稿 · **applyLive 驱动 ChatState**。

JUnit 原文：`name="LiveTranscriptTest[desktop]" tests="23" skipped="0" failures="0" errors="0"`。

## 5. 三目标门禁（原始输出）

```
$ gradle :composeApp:desktopTest --rerun
BUILD SUCCESSFUL in 29s
DESKTOP-TESTS tests=515 skipped=1 failures=0 errors=0     ← 基线 492 + 新增 23 = 515 ✓
$ gradle :composeApp:assembleDebug
BUILD SUCCESSFUL                                           （39 actionable tasks）
$ gradle :composeApp:compileKotlinWasmJs
BUILD SUCCESSFUL                                           （4 actionable tasks）
```

途中踩过并已修复的环境问题：本 worktree 的 `contracts/vade` submodule 未检出 → `VadeContractCoverageGuardTest` 3 条失败（报错信息自带修复指引），`git submodule update --init contracts/vade` 后归零。该 gitlink 与主仓记录一致（`320384ae`），不产生任何待提交变更。唯一 skipped 是既有 `VadeClientSmokeTest` 的 1 条，与本棒无关。

## 6. 越界核对

```
$ git diff --name-only c95c558..HEAD
composeApp/src/commonMain/kotlin/vade/ui/chat/LiveTranscript.kt
composeApp/src/desktopTest/kotlin/vade/ui/chat/LiveTranscriptTest.kt
shots/g-chatlive/RESULT.md        ← 本文件（第二个 commit）
```

## 7. 未覆盖 / 已知边界

1. **工具卡/审批/ask_user 不在本投影器范围**：`turn.tool_call/tool_result/approval_*/input_*` 走 ChatItem.Tool 事件投影（另一棒/slice），LiveTranscript 只产消息行。
2. **多设备编辑的行序**：`message.edited` 就地替换要求目标行已在转录里（seed 快照行或本地回显）；对完全未知的目标行记 `UnknownEditTarget` 并跳过——不猜位置。
3. **`turn.completed` 无 assistant message id**（协议不携带）：live 行 id 只能是合成 `live:<turn_id>`；重连后以快照行真 id 为准，不做 id 对账。
4. **pending 缓冲是有界等待**：cap 64 之后丢最旧记 `PendingOverflow`，不做无限重排。
5. `TurnQueued/TurnStarted` 只记录 seq 不建行；排队徽章（`queue.updated`/`turn.queued`）仍归队列 UI。
