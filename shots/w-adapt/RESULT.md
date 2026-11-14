# w-adapt · 四个窄接口 → 真适配器（RESULT.md）

分支 `tmp/w-adapt`（基于 main @ 14f6040）。代码 commit：**f6a4837**（本报告 commit 为其后一笔）。
只新建文件，未改任何既有文件（`git show --stat f6a4837` 全部为 `create mode 100644`）。

## 1. 原始验证数字

- `:composeApp:desktopTest`：**401 tests / 0 failures / 1 skipped**（skip = 既有 `VadeClientSmokeTest`，与本轮无关）。
  基线 361 → 新增 **40** 条全过：MetaActionsRealTest 14、BlobUploaderRealTest 12、ModelActionsRealTest 8、TurnGatewayRealTest 6。
  数字取自 `composeApp/build/test-results/desktopTest/TEST-*.xml` 汇总，非 gradle 摘要。
- `:composeApp:compileKotlinWasmJs`：**仍然失败，错误列表与 14f6040 基线逐字一致（8 个）**——
  全部落在 4 个禁改文件（`RealSessionSource.kt` ×2 `Volatile`、`MetaColors.kt`/`MetaSheets.kt` `String.format`、
  `AccentEdge.kt` ×4 `Math`）。本轮 4 个新 commonMain 文件**零错误**（对照 `wasm-baseline.log` / `wasm-after3.log`，
  逐行 diff 一致）。这 8 个是既有债务，文件权限不在本轮，需要单独一笔修复。

## 2. 四个适配器 ↔ RPC 映射表

| 适配器 | 接口方法 | RPC / 协议 | 前置校验（违规 = 零帧 + INVALID_ARGUMENT） | 备注 |
|---|---|---|---|---|
| `MetaActionsReal.kt` | `renameSession` | `session/rename` | `session_id` 非空；title trim 非空且 ≤200 | `ok=false` ⇒ PROTOCOL_MISMATCH |
| | `setSessionPinned` | `session/pin` | `session_id` 非空 | |
| | `setSessionArchived` | `session/archive` | 同上 | |
| | `deleteSession` | `session/delete` | 同上 | |
| | `assignSessionToGroup(null)` | `session/group.assign`，`group_id` 为 JSON **null**（required-with-null，键必须在） | `session_id` 非空；blank 字符串 ≠ 未分组 | |
| | `listGroups` | `session/group.list`（params `{}`） | — | `MetaGroup.iconName/colorHex` 缺失 ⇒ PROTOCOL_MISMATCH（不兜底） |
| | `createGroup` | `session/group.create` | name 1..64；icon 非空；color 匹配 `^#[0-9a-fA-F]{6}$` | 返回**服务端** id |
| | `updateGroup` | `session/group.update` | groupId 非空；至少一字段；name 非空 | UI 二态 null=keep → wire 三态 Absent=keep（本接口无法表达清除 icon/color） |
| | `deleteGroup` | `session/group.delete` | groupId 非空 | 服务端 `unassigned` 只读不写 |
| `TurnGatewayReal.kt` | `editResubmit` | `message/edit_resubmit` | 三个 id 非空；content ≥1 part | **`message_id` 原样传原 id**；chat `ContentPart` → wire part 1:1（text/image/file）；收据逐字段（`newMessageId` = wire `message_id`） |
| | `regenerate` | `turn/regenerate` | 三个 id 非空（只校验参数，「最后一条」由 UI 保证） | |
| `BlobUploaderReal.kt` | `upload` | **HTTP**：`POST /ui/blob` → `PUT /ui/blob/upload/{id}?offset=N` ×n → `POST …/complete`；失败/取消追加 `DELETE /ui/blob/upload/{id}` | uploadId/name/mime 非空；size>0；image 必须正宽高；声明 size == 实际字节数 | 见 §3；sha256 真算（纯 Kotlin）；进度真字节 |
| `ModelActionsReal.kt` | `models`（val） | `model/list`（params `{}`，经 `refreshModels()` 拉取） | — | 服务端顺序不重排；`default.model_id` → `defaultModelId` |
| | `readOnly` | 同上 | — | `-32601` **或** `NOT_FOUND`+`unknown method '…'` ⇒ `readOnly=true` 且 `models=null`（不是空列表）；其余错误原样抛 |
| | `sessionOverride` | 缓存读取；`refreshSessionOverride()` → `session/model.get` | sessionId 非空 | `global_default` 顺带更新 `defaultModelId` |
| | `setSessionModel` | `session/model.set`（同步乐观 + `writeScope` 异步 RPC；失败回滚 + `lastWriteError`；`setSessionModelAwaiting` 为可抛变体） | sessionId 非空；modelId null 或非空 | 传 `model_id`（协议 id），**不传显示名** |
| | `setDefaultModel` | **无对应 RPC** | — | 抛 `UNSUPPORTED`（契约 1.5.0 的 34 个 ui_to_core 里没有全局默认写方法） |

错误映射：全部复用 `VadeErrorCategory`/`VadeErrorMapping`（未另写一套）；8 个抽样码（TIMEOUT/OVERLOADED/UNAVAILABLE/NOT_FOUND/INVALID_ARGUMENT/PERMISSION_DENIED/PAYLOAD_TOO_LARGE/PROTOCOL_MISMATCH）经 MetaActionsReal 逐个验证 code 原样穿透且归类正确。

## 3. blob 上传的真实协议（实读 `core/vade/blob/` + `schema/rpc.json` §http_endpoints v1.5.0）

**分块（chunked）上传，不是 multipart，也不是预签名**；走数据面 HTTP listener（与 `/ws` 同进程）：

1. `POST /ui/blob`（JSON，≤64KiB）：`{kind:"image"|"file", sha256, size, mime, name[, width, height][, part_size]}`，
   幂等键 `(device_id, sha256, size)`。三种回答：
   - **dedupe 命中**（200）：`{file_id, upload_id: null, dedupe: true, …}` —— 不传字节，直接返回 `file_id`；
   - **resume**（200）：`(device, sha256, size)` 已有开放会话 → 带 `offset_next`，客户端跳过已确认字节；
   - **新建**（201）：`{file_id: null, upload_id, part_size, received, offset_next, confirmed_bytes, expires_at, max_bytes}`。
2. `PUT /ui/blob/upload/{upload_id}?offset=N`：**原始字节**，`Content-Type: application/octet-stream`，
   offset 必须落在 `part_size` 边界，`part_size + 1KiB` 为 body 上限。可选 `x-vade-chunk-sha256` 头（本轮未发，可选项）。
   **multipart/form-data 被显式拒绝**（415 UNSUPPORTED，`data.field="content-type"`——因为 multipart 是 CORS simple request，会跳过保护 cookie 凭证的 preflight，`blob/http.py` `_FORBIDDEN_TYPES`）。
3. `POST /ui/blob/upload/{upload_id}/complete`（JSON `{sha256, size}`）：core 重新 stat+re-hash，不一致即拒；成功 201 返回 `{file_id, sha256, size, mime, name, …}`。
4. `DELETE /ui/blob/upload/{upload_id}`：abort（204 幂等，永不删除已提交 blob）。
5. `HEAD /ui/blob/upload/{upload_id}`：进度/续传锚点（`offset_next`）；本轮用 begin 回答里的 `offset_next` 做了同一件事。

**鉴权**（`blob/auth.py`，rpc.json §auth）：`Authorization: Bearer <token>`（设备通道铸造，**600s TTL**）或浏览器 `__Host-ac_ui` cookie + `x-vade-csrf`（double submit）+ 精确 Origin 白名单 + `Sec-Fetch-Site: same-origin`。只读会话：写 403 `READ_ONLY_SESSION`，读不拦。

**进度**：每个 part ack 后回调 `sentBytes/size`（resume 从 `offset_next` 起算，不重复上报）；字节在途时保持 <1f；**1f 只在 complete 成功后、返回前发一次**。dedupe 无字节移动，直接一次 1f。

**客户端侧实现**（`BlobUploaderReal.kt`）：注入两个窄接口——`BlobHttpChannel`（HTTP 执行器，凭据归它管）与 `BlobByteSource`（字节来源，文件选择器是 shell 的接缝）；适配器自己不开连接、不看凭据。SHA-256 为纯 Kotlin commonMain 实现（`java.security.MessageDigest` 是 JVM 专有），desktopTest 用标准 KAT（空串/"abc"/双块消息）+ 10 个随机长度对 JVM digest 全等钉住。失败抛 `UploadFailedException`（chip 可重试形状）且 message 携带 C-04 code（`UploadFailedException` 冻结为单参、commonMain 无 `initCause`，无法挂 cause）；取消（`CancellationException`）经 `NonCancellable` 补发 DELETE 后**原样重抛**，绝不转成失败。

**测试覆盖**：帧逐字（begin JSON 含真 sha256 / PUT 路径 offset / complete JSON）、多块 16/40 切片与进度序列、dedupe 短路、resume 跳过已确认字节、begin 失败（无 DELETE）、complete 失败（有 DELETE）、PUT 中取消（有 DELETE、无 complete）、5 类前置校验零 channel 调用、失败后重试全新会话、同 adapter 并发两上传互不串。

## 4. 缺哪些 wrapper / 契约缺口

1. **`ui/blob.token` 不存在**：设备通道铸造 blob token 的 RPC 在 core 未实现（`transport/RESULT-device-id.md` §83 明确记录），契约 34 方法里也没有。原生客户端目前**无法自铸** blob 凭证；`BlobHttpChannel` 的实现方（下一轮）必须由外部供给凭证（浏览器 cookie 会话，或等 core 补 token RPC）。
2. **全局默认模型写方法不存在**：34 个 ui_to_core 方法逐字核对（`rpc.json` §directions），没有 `default_model.set` 类方法。`ModelActions.setDefaultModel` 因此抛 UNSUPPORTED——这是契约边界，不是漏实现。
3. **`session/model.set` 清除语义缺陷（协议层既有 bug）**：core 要求 `model_id` 键必须显式存在（缺失 = INVALID_ARGUMENT，`session/rpc.py:275`），而 `SessionModelSetParams.toJson` 用 `putStr` 会把 null **整个省略** ⇒ `setSessionModel(s, null)` 的 wire 帧是 `{"session_id":s}`，真实 core 必拒。protocol/** 本轮禁改，帧形状已用测试钉在现状；修法是 `putStr("model_id", …)` 改为显式 JsonNull（一行），留给 protocol 的 stick。
4. **`session/search`**：`VadeClient.kt` 尚无 typed wrapper（p-search 在补）。四个适配器不依赖它，不影响本轮。
5. **`meta` 组 icon/color 可空 vs `MetaGroup` 必填**：契约允许 icon/color 为 null 的组，`MetaGroup` 不允许 ⇒ 适配器抛 PROTOCOL_MISMATCH。UI 侧建组永远带 icon+color，真实数据不会触发；若未来出现这种组，需要先在契约/接口层决定语义。

## 5. 下一轮接线点（行号，本轮 HEAD）

| 实例 | 传入位置 |
|---|---|
| `BlobUploaderReal(channel, byteSource)` | `vade/ui/chat/ChatState.kt:39` — `private val uploader: AttachmentUploader = InMemoryAttachmentUploader()`（默认参数处替换；byteSource 接文件选择器、channel 接 Ktor 实现并持有凭证） |
| `TurnGatewayReal(client)` | `vade/ui/chat/ChatState.kt:41` — `private val turns: ChatTurnGateway = InMemoryTurnGateway()` |
| `MetaActionsReal(client)` | `vade/ui/meta/SessionMetaState.kt:71` — 构造参数 `private val actions: SessionMetaActions`（`SessionMetaState(` 在 :68） |
| `ModelActionsReal(client, writeScope)` | `vade/ui/model/ModelState.kt:32` — 构造参数 `private val actions: ModelActions`；入口工厂 `ModelState.forSession(...)/forDefault(...)` 在 :44-48 |

共同前提：传入的 `VadeClient` 必须是已 `connect()` 的同一实例（建议复用 `RealSessionSource` 持有的那条连接）；`ModelActionsReal.refreshModels()` 需要在挂起上下文里至少调用一次（`models` 是拉取后的快照）。

## 6. 本轮新建文件清单

```
composeApp/src/commonMain/kotlin/vade/ui/data/MetaActionsReal.kt
composeApp/src/commonMain/kotlin/vade/ui/data/TurnGatewayReal.kt
composeApp/src/commonMain/kotlin/vade/ui/data/BlobUploaderReal.kt   （含纯 Kotlin Sha256）
composeApp/src/commonMain/kotlin/vade/ui/data/ModelActionsReal.kt
composeApp/src/desktopTest/kotlin/vade/ui/data/MetaActionsRealTest.kt    (14)
composeApp/src/desktopTest/kotlin/vade/ui/data/TurnGatewayRealTest.kt    (6)
composeApp/src/desktopTest/kotlin/vade/ui/data/ModelActionsRealTest.kt   (8)
composeApp/src/desktopTest/kotlin/vade/ui/data/BlobUploaderRealTest.kt   (12)
shots/w-adapt/RESULT.md
```

未动：protocol/**、shell/**、chat/**、meta/**、model/**、destinations/**、skin/**、usage/**、subagent/**、icon/**、data/RealSessionSource.kt、build.gradle.kts。未 push、未合并。
