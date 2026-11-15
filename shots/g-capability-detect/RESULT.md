# g-capability-detect · 统一「方法不存在」判定 + 两处可选能力降级

分支 `tmp/g-capability-detect`（基于 `origin/main` `bbfdf2f`）
代码 commit：**`d2820ea`**（本报告为第二个 commit）

## 0. 门禁（三目标，原始数字）

| 目标 | 结果 |
|---|---|
| `:composeApp:desktopTest --rerun` | BUILD SUCCESSFUL；`build/test-results/desktopTest/TEST-*.xml` 求和 **tests=1158 failures=0 errors=0 skipped=1**（111 个 XML；基线 1130 + 本棒新增 28） |
| `:composeApp:assembleDebug` | BUILD SUCCESSFUL（1m57s） |
| `:composeApp:compileKotlinWasmJs` | BUILD SUCCESSFUL（26s） |

skip=1 是主干既有的环境性跳过（本棒未新增 skip，未删测、未弱化断言；`VadeAdaptersTest` 一处类型断言
从 `BlobUploaderReal` 升级为「闸门包装 + 闸门内仍是 BlobUploaderReal」，断言强度不减反增）。

## 1. 统一判定（协议层，可测）

新文件 `protocol/VadeMethodMissing.kt`：

```kotlin
fun VadeException.isMethodMissing(): Boolean =
    code == METHOD_NOT_FOUND_CODE /* "-32601" */ ||
        (code == VadeErrorCodes.NOT_FOUND && message.orEmpty().contains("unknown method"))
```

两种形态都算「方法不存在」：
- `-32601`：JSON-RPC 标准（老/其它实现，或将来 core 收紧后可能出现）；
- `NOT_FOUND` + message 含 `unknown method`：本仓 core 今天的真实形态
  （`core/vade/session/rpc.py::SessionRPC.dispatch` 对未注册方法回
  `RpcError("NOT_FOUND", "unknown method '<name>'")`；父代实跑
  `SessionRPC.dispatch(conn, 'ui/blob.token', {})` → `{'code': 'NOT_FOUND', ...}`）。

**边界（专门用例钉住）**：`NOT_FOUND` 但 message 不含指纹 —— `blob not found` /
`session 'x' does not exist` / `no running turn with that id` / 空 message（VadeException
会回填 code）—— **不是**「方法不存在」；TIMEOUT / UNAVAILABLE / INTERNAL / INVALID_ARGUMENT /
PROTOCOL_MISMATCH / 客户端侧 CLIENT_TIMEOUT_CODE、TRANSPORT_CLOSED_CODE 一律 false。

## 2. 两处降级（外加第三处收编）

### 2.1 模块清单（`ui/modules`）— `data/ModuleCatalog.kt`

`ModulesLoader.pull()` 的 catch 命中统一判定 → `ModuleCatalogState.Failed`，携带
`ModuleLoadError.capabilityMissing = true`（新字段，默认 false，全部既有构造点不受影响），
三段**数据驱动**文案整体换成「不支持」语义：

- `label` = `该 core 不支持模块页面：unknown method 'ui/modules'`（保留 core 原话，可核对）；
- `hint` = `core 版本过旧，没有 ui/modules 方法（能力缺失，不是网络或服务故障）；升级 core 后模块卡片自动出现。`
- `retryLabel` = `core 升级后重试`（诚实：core 升级前重试无效）。

**集合保持 null**：注册表一个字节不动、成功快照不诞生；loader 新增可空快照
`ModulesLoader.declarations: List<ModuleDeclaration>?` —— 降级后 `assertNull`，
并断言 `state !is Ready`（**不许** `Ready(emptyList)` 兜底，那是「有但零条」）。

**约束说明（诚实记录）**：`destinations/CapabilitiesScreen.kt` 的
`when (catalogState)` 是穷尽分支且该目录禁改，新增 sealed 变体会直接打断三目标门禁，
所以「不支持」状态骑在既有 `Failed` 卡上、由 `ModuleLoadError` 的数据驱动文案表达；
用户读到的三行全是「不支持」语义，只有卡片标题「模块清单加载失败」是 destinations 的
硬编码字。将来若允许改 destinations，把 `capabilityMissing` 升格为独立卡片是一行的改动。

### 2.2 附件上传（`ui/blob.token`）— `data/BlobUploadCapability.kt`（新）

- `VadeClient.blobTokenProbe()`（protocol/**）：`ui/blob.token` 裸帧探测，params `{}`。
  **故意不进 `VadeMethod`**：契约（submodule 当前 pin）的 `ui_to_core` 没有它
  （`DRAFT-blob-token.md` 是提案、未登记），`VadeContractCoverageGuardTest` 会把 enum
  与契约逐名对齐，加进去就红 —— 这是全客户端唯一一条不经 `VadeMethod` 的出站 RPC。
- `BlobUploadGate`（data/**）：探测结论缓存 —— **不支持粘住**（每连接一帧，不发探测风暴），
  **真故障不缓存**（TIMEOUT/UNAVAILABLE 原样穿透、support 保持 Unknown），token 只在探测
  成功且回包带 `token` 时非空（「集合保持 null」的那一格）。
- `CapabilityGatedUploader`：探测命中「方法不存在」→ 抛
  `UploadFailedException("该 core 不支持附件上传：core 版本过旧，没有 ui/blob.token 方法
  （能力缺失，不是网络或服务故障）；升级 core 后可发附件。")` —— 聊天页把这条显示在附件
  chip 的 detail 上（**可见提示，不是故障弹错**），**字节通道一次交换都不碰**（测试钉住
  `channel.calls` 为空）。探测通过 → 一字不差委托 `BlobUploaderReal`。
- 接线：`RealSessionSource.connect()` 给 `RealAdapters` 传
  `blobGate = BlobUploadGate { created.blobTokenProbe() }`；`RealAdapters` 新参
  `blobGate: BlobUploadGate? = null` —— **null = 不设闸**（通道机制直构用例行为零变化），
  生产路径必带闸门（`VadeAdaptersTest` 钉住）。

**约束说明**：附件入口/圆片在 `chat/**`、`destinations/**`（均禁改），所以可见提示走
`UploadFailedException` 的消息文案（chip detail 数据驱动），而不是新增 UI 状态。

### 2.3 第三处：`ModelActionsReal`（FR-8.46 模型选择）

原有的私有 `isMethodMissing()`（`startsWith("unknown method")`）收编为共享判定，
规则只有一处实现；行为差异仅为 startsWith→contains（任务口径「message 含」），既有
`ModelActionsRealTest` 的正反用例全部照旧通过。

## 3. 测试（desktopTest，全部新增）

| 文件 | 用例 | 钉住什么 |
|---|---|---|
| `protocol/VadeMethodMissingTest.kt` | 8 条 | 两形态都识别（-32601、`NOT_FOUND`+`unknown method 'ui/blob.token'`、marker 在任意位置）；`blob not found`/unknown session/unknown turn/空 message **不得**当不支持；TIMEOUT/UNAVAILABLE/INTERNAL/INVALID_ARGUMENT/PROTOCOL_MISMATCH/OVERLOADED 及两个客户端侧码 **不得**当不支持 |
| `data/ModuleCatalogCapabilityTest.kt` | 5 条 | 两形态降级（NOT_FOUND 形态 / -32601 形态）：`capabilityMissing=true`、label 含「该 core 不支持模块页面」、retryLabel=「core 升级后重试」、`declarations == null`、`state !is Ready`；降级粘住（retry 后结论不变）；反例：`blob not found` → 普通故障（label 保留 NOT_FOUND、不含「不支持」）、TIMEOUT/UNAVAILABLE → 普通故障 |
| `data/BlobUploadCapabilityTest.kt` | 12 条 | gate：两形态 → `Unsupported` 且 `token==null`；TIMEOUT/UNAVAILABLE/`blob not found` → 原样穿透且 support 保持 Unknown；探测成功 → token 提取。uploader：不支持 → chip 文案含「该 core 不支持附件上传」「能力缺失」且**字节通道零交换**；粘住（两连上传只探测一帧）；真故障穿透且文案不含「不支持」；放行 → 委托真通道。wire 三条：线上方法名逐字 `ui/blob.token`、params `{}`，两种错误帧都能到统一判定，成功帧原文上交 |

原有用例零改动（除 `VadeAdaptersTest` 上述一处类型断言），`ModelActionsRealTest` 的
FR-8.46 降级正反用例照旧全绿。

## 4. data/** 改动清单（红线要求逐文件列出）

| 文件 | 性质 |
|---|---|
| `data/BlobUploadCapability.kt`（新） | 附件降级本体（gate + 包装上传器 + 统一文案） |
| `data/ModuleCatalog.kt` | 降级调用点：loader catch 分支 + `ModuleLoadError.capabilityMissing` 字段与三段文案 + `moduleCapabilityMissingError` 工厂 + 可空快照 `declarations` |
| `data/VadeAdapters.kt` | 调用点：`RealAdapters` 新参 `blobGate`（默认 null）+ `blobUploader` 包一层闸门 |
| `data/RealSessionSource.kt` | 调用点（一行）：`connect()` 传入 `BlobUploadGate { created.blobTokenProbe() }` |
| `data/ModelActionsReal.kt` | 调用点：删除私有判定，改用共享 `isMethodMissing` |

禁改区核查：`git status --porcelain` 中 `shell/`、`destinations/`、`skin/`、`chat/`、
`icon/`、`build.gradle.kts`、`contracts/`、`schema/` **零命中**；submodule 未动。

## 5. 文档与实现不一致：`-32601` 注记是错的（已实测，建议主仓单独一笔文档修正）

**本棒不改契约。** 事实与证据：

1. **实现（真源）**：`core/vade/session/rpc.py` `SessionRPC.dispatch`（第 52-55 行）：

   ```python
   handler = self.handlers().get(method)
   if handler is None:
       return RpcError("NOT_FOUND", f"unknown method {method!r}")
   ```

   即未注册方法回 **C-04 名 `NOT_FOUND` + `unknown method '<name>'`**，不是 JSON-RPC
   数字码 `-32601`。父代实跑：`SessionRPC.dispatch(conn, 'ui/blob.token', {})` →
   `{'code': 'NOT_FOUND', 'message': "unknown method 'ui/blob.token'"}`。

2. **文档（错的那一侧）**：
   - `schema/rpc.json` grep `32601` 命中 **6 处**注记（第 62、107、154、168、665、1209 行），
     全部声称「core 没有该方法时答 -32601」——与今天的 core 实现不符；
   - `core/vade/session/rpc.py` 第 81-83 行注释也写着「an unregistered method answers
     JSON-RPC -32601」——**同一文件里注释与它头顶上 30 行的代码互相矛盾**。

3. **为什么这不是小事**：能力探测（模块页面 / 附件上传）全靠「方法不存在」信号。客户端若只认
   `-32601`，在真 core 上**永远**认不出「core 太旧」——把「不支持」报成故障（UNAVAILABLE/
   超时横幅），或者反过来把「资源不存在」的真故障降级成「不支持」。本棒的第 2 种形态
   （NOT_FOUND + message 指纹）就是为今天的 core 而写；第 1 种（-32601）为老实现与将来
   收紧保留。

4. **建议**：由主仓单独一笔**纯文档修正**（不碰 `schema/**` 的结构、不动方法与错误码集合）：
   把 6 处 notes 与 `session/rpc.py:81-83` 的注释改为「未注册方法答 `NOT_FOUND` +
   `unknown method '<name>'`（老实现可能答 JSON-RPC `-32601`，客户端两种都认）」，并在
   `session/fork` 那条（rpc.json:62）补一句「错误码以 `dispatch` 实现为准」。该修正若与
   `DRAFT-blob-token.md` 的登记同批做，`ui/blob.token` 的注记可直接写对。

## 6. 过程中的坑（对后续批次有用）

**async 子协程失败会「泼」到下一个用例头上（实测，非理论）**：wire 级用例最初写成
`async(Dispatchers.Default) { client.blobTokenProbe() }` + 主 runBlocking `await` 捕获 ——
单跑全绿，与其它用例同跑时，这条 VadeException 会**漂移**到后续执行的无关用例上
（JUnit 报的失败栈只有 `handleResponse:388` 的创建点，指向一个根本没建 client 的
gate 用例）。修复 = 本仓 `ModelActionsRealTest` 的既有纪律：**`runCatching` 放进 async，
await 拿 `Result`** —— async 子协程自己永不失败。派 wire 级失败路径用例时直接写对，
不要重新发现。

## 7. 交付物路径

- `composeApp/src/commonMain/kotlin/vade/ui/protocol/VadeMethodMissing.kt`（新）
- `composeApp/src/commonMain/kotlin/vade/ui/protocol/VadeClient.kt`（+`blobTokenProbe`）
- `composeApp/src/commonMain/kotlin/vade/ui/data/BlobUploadCapability.kt`（新）
- `composeApp/src/commonMain/kotlin/vade/ui/data/ModuleCatalog.kt` / `VadeAdapters.kt` /
  `RealSessionSource.kt` / `ModelActionsReal.kt`
- `composeApp/src/desktopTest/kotlin/vade/ui/protocol/VadeMethodMissingTest.kt`（新）
- `composeApp/src/desktopTest/kotlin/vade/ui/data/ModuleCatalogCapabilityTest.kt`（新）
- `composeApp/src/desktopTest/kotlin/vade/ui/data/BlobUploadCapabilityTest.kt`（新）
- `composeApp/src/desktopTest/kotlin/vade/ui/data/VadeAdaptersTest.kt`（一处类型断言升级）

未 push、未合并、无 amend/rebase/reset。
