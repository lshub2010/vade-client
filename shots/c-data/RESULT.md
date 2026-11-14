# c-data · 客户端真实数据层

分支 `tmp/c-data`（基于 main @ f189de2），commit **bcd6b88**。
全量 `gradle :composeApp:desktopTest --rerun`：**190 tests / 0 failures / 0 errors / 1 skipped**
（skip 为既有 `VadeClientSmokeTest.attachSubmitAndLiveEventsAgainstARealCore`，需要真核，基线即如此）。
新增 36 条测试：Lifecycle 6 / Events 9 / List 11 / Actions 5 / ErrorMapping 5（154 → 190）。

## 1. UI↔协议 方法映射表

UI 接口 = `vade.ui.data.SessionSource`（既有接缝，本轮未改）。数据源实现 = `RealSessionSource`。

| UI 侧 | 协议方法 | 接线状态 | 位置 |
|---|---|---|---|
| `SessionSource.sessions()` | `session/list`（分页 next_cursor，limit=50） | ✅ 已接 | RealSessionSource.kt `sessions()` + TransportTap.kt（原始帧通路） |
| `SessionSource.messages(id)` | `ui/attach`（取 `result.snapshot.messages` 解码为 ChatMessage） | ✅ 已接 | RealSessionSource.kt `messages()` + SnapshotMessages.kt |
| `SessionSource.observeEvents()` | 事件流（26 事件中投影核心 6 个） | ✅ 已接 | RealSessionSource.kt `mapEvent()`；未知/未处理事件忽略、last_seq 由协议层逐帧推进 |
| `RealSessionSource.state` | 连接状态机（Disconnected/Connecting/Connected/Reconnecting(attempt,delayMs)） | ✅ 已接（StateFlow，镜像 `VadeConnectionState`） | RealSessionState.kt |
| `connect()` / `disconnect()` | `VadeClient.connect()` / `close()`（重连退避由协议层承担，base→cap 指数退避） | ✅ 已接 | RealSessionSource.kt |
| `RealSessionSource.attach(sessionId?)` | `ui/attach` | ✅ 已接（供后续 UI 页面用） | RealSessionSource.kt |
| `RealSessionSource.detach(id)` | `ui/detach` | ✅ 已接 | 同上 |
| `RealSessionSource.submit(id, text)` | `ui/submit`（自动生成幂等 `client_msg_id=ui-<epochMs>-<seq>`） | ✅ 已接 | 同上 |
| `RealSessionSource.interrupt(id, turnId)` | `ui/interrupt` | ✅ 已接 | 同上 |
| `RealSessionSource.usage(id)` | `session/usage.get` | ✅ 已接 | 同上 |
| 会话列表 preview | —（`session/list` 行刻意不含 preview，内容属于 ui/attach） | ⚠️ `SessionSummary.preview` 恒为空串 | SessionRowMapping.kt |
| `SessionSummary.model` | —（list 行无 model 字段，D101 徽标在 attach 快照里） | ⚠️ 恒为 null | 同上 |
| 其余 22 个 ui_to_core 方法（turn/regenerate、message/edit_resubmit、task/*、subagent/*、ui/page.get、ui/state.get、ui/tool.call、approval/decide、session/group.*、session/pin 等） | 未包 typed wrapper（协议层 VadeClient 也未包其中 12 个，契约共 33 个） | ❌ 本轮未接 | 待后续批次按页面需求逐个加 |

**session/list 的原始通路**：契约 `ui_to_core` 有 33 个方法，协议层 VadeClient 只封装了 21 个（协议层本轮禁改），`session/list` 无 typed wrapper 且其 `rpc()` 是 private。RealSessionSource 用 `TeeTransportFactory`/`TeeTransport`（TransportTap.kt）包住注入的 transport：入站帧先同步分发给 raw 请求的监听器（按 id 匹配，raw id 从 1e9 起、与客户端 0 起的 id 无碰撞），再原样转发给协议层读循环——同一连接、无双连接；客户端对不认识的 id 的响应本来就静默丢弃，telemetry 上是安全的。

## 2. 错误码映射表（34/34 全覆盖，测试穷举断言）

单文件 `vade.ui.data.VadeErrorCategory`（`VadeErrorMapping.categories`）。测试 `VadeErrorMappingTest` 断言 `categories.keys == VadeErrorCodes.ALL.toSet()` 且 size==34——漏一个即红。

| 分类 | 语义 | 错误码 |
|---|---|---|
| Retryable（可重试） | 限流/超时/容量类，重试同一操作即可 | TIMEOUT、CANCELLED、OVERLOADED、ADMIN_LOCKED、MODULE_DRAINING |
| NeedsReconnect（需要重连） | 连接/同步态失效，重连+重同步恢复 | UNAVAILABLE（也是客户端传输死亡码）、AUTH_FAILED、NOT_FOUND（陈旧视图，重挂即愈）、INTERNAL、BIND_UNAUTHORIZED |
| NeedsUserAction（需要用户操作） | 输入/权限/配额/决策类 | INVALID_ARGUMENT、PERMISSION_DENIED、MEMORY_FULL、PAYLOAD_TOO_LARGE、UNSUPPORTED、TOOL_CONFLICT、RISK_DOWNGRADE_FORBIDDEN、APPROVER_INVALID、READ_ONLY_SESSION、BIND_DEVICE_LIMIT、BIND_CLOCK_SKEW、BIND_SETUP_LOCKED、CONFIRM_REQUIRED、UNKNOWN_CONFIG_KEY、CODE_INVALID、CODE_EXPIRED、BUDGET_EXCEEDED |
| Fatal（致命） | 版本不匹配/安全态破坏 | PROTOCOL_MISMATCH、MANIFEST_INVALID、ORIGIN_MISMATCH、SETUP_UNAUTHORIZED、BIND_FINGERPRINT_MISMATCH、BIND_HELLO_SIG_INVALID、BIND_KEY_UNSUPPORTED |

未知码（未来 core 新增）回落 `Retryable`，UI 只展示消息+重试入口，不崩。

## 3. 事件投影

核心 6 个 → `vade.ui.data` 的 UI 事件类型（实现既有空 `sealed interface VadeEvent`，UI 不 import 协议层）：
`session.attached→SessionAttachedEvent`、`turn.queued→TurnQueuedEvent`、`turn.started→TurnStartedEvent`、
`turn.delta→TurnDeltaEvent`（text/partIndex/kind，UI 按 seq 序拼接）、`turn.completed→TurnCompletedEvent`（usage.totalTokens）、
`turn.failed→TurnFailedEvent`（C-04 code/message）。
其余事件**显式忽略**（`mapEvent` 的 `else -> null`，附注释「未知/未处理事件忽略、last_seq 继续前进」）——
测试断言：已知但未投影（queue.updated）、未知类型（x-vade.future_event）、坏 payload 三种都不崩且 `lastSeq` 逐帧前进。

## 4. 真假数据切换方法（默认假数据）

环境变量 **`VADE_SOURCE=real`**（desktop Main.kt，已沿用 `VADE_SKIN`/`VADE_DARK` 的既有做法）：

```bash
# 默认（不变）：假数据 fixtures
gradle :composeApp:run

# 真数据源（指向本机 core 数据面 8443）：
VADE_SOURCE=real gradle :composeApp:run
# 可选端点覆盖：VADE_HOST=127.0.0.1 VADE_PORT=8443
```

- 切换点（唯一越界改动，一行构造表达式）：`composeApp/src/desktopMain/kotlin/vade/ui/Main.kt:135-140`
  —— `App(source = if (VADE_SOURCE=="real") RealSessionSource(host, port) else FakeSessionSource())`。
  构造 RealSessionSource **无副作用**（不自动 connect），默认路径下行为与之前逐字节相同。
- 另一个既有默认点（未改）：`UiState.kt:39` `val source: SessionSource = FakeSessionSource()`。

## 5. 未接线清单（本轮明确不做）

1. **UI 页面本轮未接**：`vade/ui/chat/`、`vade/ui/destinations/`、`vade/ui/shell/`（UiState 仍直接读 `FakeSessions.groups()`，未读注入的 source）、`vade/ui/search/` —— 按任务书只做数据层，页面仍显示 fixtures。切换开关打开后界面上**没有任何可见变化**，`RealSessionSource` 处于待接入状态。
2. `sessions()` 的 preview 为空串、model 为 null（见映射表 ⚠️ 行），列表页会显示空预览行——等 preview 来源决策（attach 或后续 list 追加）再接。
3. `messages()` 依赖 attach 快照；同一会话二次 attach 走 replay 路径时快照为 null、线程经 `observeEvents()` 增量到达——页面接线批次需处理两种来源。
4. 协议层未封装的 12 个 ui_to_core 方法（见映射表最后一行）与 `tool_calls` 的快照渲染（tool 行故意跳过）。
5. wasm/Android 端未做真连接验证（desktopTest 覆盖 commonMain 逻辑；KtorTransportFactory 三端实现由协议层提供）。

## 6. 已知实现说明（复核提示）

- `TeeTransport.incoming` 是冷 flow，唯一 collector 是协议层读循环；监听器非阻塞（只 complete deferred）。
- `disconnect()` 后再 `connect()` 会新建 VadeClient（协议客户端 close 后不可重启）；测试覆盖。
- backoff 测试用 `failFirstN=5` 造连续失败：协议层 attempt 计数在**每次成功连接后重置**（语义=连续失败次数），「连接成功再断线」永远显示 attempt=1，这是协议层既有行为，不是缺陷。
- close 后 transport 会被关两次（显式 close + runner 清理），测试断言 `closedCount >= 1`。
- 时间函数用 `kotlin.time.Clock`（Kotlin 2.4 stdlib，三端可用）；桶/标签按 UTC 日龄算（与 fixtures 的相对时间语义一致），标签 HH:mm 为 UTC 墙钟（KMP 无公共时区 API）。
