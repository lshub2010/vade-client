# RESULT · g-pair-first-run（首次运行配对界面）

分支 `tmp/g-pair-first-run`（基于本地 main `32970b1`，严格超集于 origin/main）。
**未 push、未合并。**

## 交付了什么

三端共用的首次运行引导卡：**真源且缺配对码**时，会话列表页顶部出现一张卡（不是弹窗、
不遮会话、不占空 item 行高）——一次性码输入框 + 「配对」按钮 + 一句「码在哪里找」
（照 `docs/PAIRING.md` §二口径）。提交走**现有连接链**，三种失败可区分，码不落盘不进日志。
成功后卡片消失、列表开始拉真数据。

## 改动清单（`git diff --name-only <base>..<HEAD>`）

| 文件 | 性质 | 说明 |
| --- | --- | --- |
| `destinations/PairingCard.kt` | **新建** | 引导卡本体：可见性判定（卡内自足，`AppShell` 零改动）、提交、三态失败文案 |
| `destinations/SessionsScreen.kt` | 顶部**只加一块** | `item(key = "first-run-pairing")` + 可注入 `pairingRaw` 形参（默认 = 生产通道）；现有横幅逻辑一行未动 |
| `data/SourceConfig.kt` | **只加透传** | `needsFirstRunPairing` / `firstRunPairingAttempt` / `pairingFailureKindOf` / `PairingRuntime`（运行期桥）/ `pairingEndpointOf`；`sessionSourceFrom` Real 分支**一行**消费桥 |
| `App.kt` | **越界单列**（见下） | `remember(source)` 以源为 key + `DisposableEffect` 释放旧 UiState |
| `desktopTest/.../PairingFirstRunTest.kt` | 新建（10 条） | 判定 / 一次性尝试 / 桥消费点 / endpoint 对照 / 扫描守卫 |
| `desktopTest/.../PairingCardUiTest.kt` | 新建（6 条） | 真组合：出现/不出现、恰好一次尝试、三失败文案、可重试 |

## 连接路径（没有新开一条）

提交 = `SourceConfig.parse(source, host, port, 输入的码)` → `VadeClientConfig(host, port,
BrowserPairing(码))` → `KtorTransportFactory(config).connect()` —— 与 `sessionSourceFrom`
Real 分支同一构造形状、与 runner 每次重连同一传输入口。**一次调用 = 恰好一次
`POST /ui/session` + 一次 WS 升级**；探活传输随即关闭，cookie 缓存在 factory 里，
一次性码不会被二次兑换。

**为什么不经 runner**：`VadeClient` 的 runLoop 把一切异常吞掉换成无限重试
（`catch (_: Exception) { attempt += 1; continue }`），三种失败在那条路上**不可区分**
（都变成 15 s 后的 UNAVAILABLE）。引导卡需要在尝试函数里直接拿到原始异常做分类：
`AUTH_FAILED` → 码错；异常消息含 "refused" → 服务端没起；其余 → 网络。

**成功后怎么让列表拉真数据**：成功时把**已换到 cookie 的 factory** 装进
`PairingRuntime`（进程内存 compose state，只装 factory 不装码）。`sessionSourceFrom`
的 Real 分支读桥建 `RealSessionSource(config, 桥.factory)` —— 同一条链、同一次配对。
读取桥的组合（入口处 `sessionSourceFromEnvironment()`）会随写刷新，UiState 随源重建、
loader 重拉 —— 码从输入框到消费，只走函数参数与进程内存。

## 越界单列（任务书允许清单之外）

**`App.kt` 两处**：`remember { UiState(...) }` → `remember(source) { UiState(...) }`，
外加 `DisposableEffect(source) { onDispose { state.dispose() } }`。

- **理由**：`UiState.source` 是 val、`AppShell.kt` 被禁改，而组合根 `remember` 无 key
  会在源变化时**丢弃**新源参数 —— 不改这一处，「成功后卡片消失、列表开始拉真数据」
  的后半句不成立（卡片消失但列表永远停在失败横幅，界面自相矛盾）。
- **可回退**：revert 这两处 → 引导卡出现/提交/三失败文案全部照旧（仅列表不随配对
  重拉），其余交付零影响。
- 桥默认空时源永不变，`remember(source)` 与旧 `remember {}` 行为相同（不碰 fixtures 路径）。

## 门禁（三门禁全绿，原始数字）

1. `desktopTest --rerun`：**1251 tests, 0 failures, 0 errors, 1 skipped**
   （基线 + 本棒 16 条；skipped 为既有基线的 1 条，与本棒无关）。
2. `assembleDebug`：BUILD SUCCESSFUL（APK md5 `e4b0bf22b955a776c7c9748ded6fb1bb`）。
3. `compileKotlinWasmJs`：BUILD SUCCESSFUL。

## 变异自验（父代可复跑）

- **变异**：`needsFirstRunPairing` 删掉 `&& spec.pairCode == null` →
  `noPairingWhenCodePresent` 与 `cardAppearsOnlyForRealSourceWithoutCode` **恰好这两条变红**
  （16 tests, 2 failed）。**已还原**：还原后 `SourceConfig.kt` md5 =
  `fffb9e546dfaa917770b61634b3d7714`，`grep MUTATION = 0`。
- 开发过程中测试两次真实变红，红因与被测行为一致：
  ① `attemptFailureKindsAreClassified` 在 `transportFactory(config)` 构造还在 try 外时
  把 VadeException 裸抛（修复：构造移入 try —— 任何环节失败都分类成三种之一）；
  ② 三条失败文案用例在替身没提交时超时（修复：替身先输入并点「配对」）。
  这两条不是「跑绿了」，是先红后修的红。

## 设备验证（emulator-5554，未触碰真机 192.168.1.180:5555）

真 core 在宿主机运行（`PYTHONPATH="core;protocol/core/python" python -m vade`，
监听 127.0.0.1:8443），模拟器经 `adb reverse tcp:8443 tcp:8443` 访问（10.0.2.2 会被
Android cleartext 安全策略拦截，换 127.0.0.1 后放行）。

| 图 | 内容 |
| --- | --- |
| `01-card-appears.png` | **引导卡出现**：真源（`--es source real`）无码启动 → 卡（标题/说明/输入框/按钮）+ 下方「正在连接 core 127.0.0.1:8443 …」+ 空列表（假数据 6 行不再出现） |
| `02-bad-code.png` | **码错文案**：输入 `WRONG-000000` 点「配对」→ core 回 401/400，卡上三行：标题「配对码不对…」+ 传输原话（HTTP 400 JSON 逐字）+ 下一步（回 core 重取新码）；按钮仍可点（可重试） |
| `03-paired-connected.png` | **成功路径（桌面端 e2e）**：同一进程实例内，粘贴真码点「配对」→ 卡消失、失败横幅全消失、列表进入 Connected（0 个会话 = 真实空答案，fixtures 6 行消失） |
| `04-desktop-badcode-expired.png` | 桌面端码错分类：真码过期（`CODE_EXPIRED`）→ 同一文案三行，传输原话逐字 |
| `05-desktop-card-appears.png` | 桌面端引导卡 + loader TIMEOUT 分类横幅（真源无码状态三端一致） |

设备上「成功路径」截图不可行的原因：真码含汉字，`adb shell input text` 不支持 unicode
（实测抛异常）——故成功路径用桌面端同一份代码的 e2e 证明（同一组件、同一桥、同一链）。

## 发现（只记录，不修——均在本棒范围外）

1. **文档漂移**：`docs/PAIRING.md` §三与 `MainActivity.kt` KDoc 写 Android extras 键为
   `vade.source` / `vade.host` / `vade.port`，而 `SourceKeys` 实为裸键 `source` / `host` /
   `port` / `pair_code`（`--es vade.source real` 实测**无效**，会静默落到假源）。
   需要一次文档修正或键名统一（独立批次）。
2. **Wasm 限制**：浏览器 JS 引擎读不到 `Set-Cookie`（`KtorTransportFactory` KDoc 已注明），
   引导卡在 wasm 上任何提交都会落到「码错」分类，detail 行带真实原因（"browser targets
   cannot read Set-Cookie: pair through the page shell instead"）。wasm 的首次配对要走
   页面壳，属既有架构约束。
3. **非 core 服务占端口**：任何返回非 200 的 HTTP 服务都会被分类为「码错」，detail 行
   带 HTTP 状态码可人工分辨。
4. Android cleartext 策略默认拦非 loopback 地址（10.0.2.2 被拦），设备联调需
   `adb reverse` 或 manifest 放行——与既有 launcher 行为一致，未改。

## 码的边界（守卫）

扫描守卫 `enteredPairCodeHasNoPersistenceSink` 对 `SourceConfig.kt` + `PairingCard.kt`
（剥注释后）断言：无持久化 sink（DataStore/SharedPreferences/文件写/wasm 存储）、无日志
sink（println/Log./System.out…）、无 URL 构建（ws://…/encodeURIComponent）；
且 `SourceConfig.kt` 全部可变字段只有运行期桥一处、类型为 `mutableStateOf<VadeTransportFactory…`
（「输入值不进 SourceConfig 的任何持久化字段」的正身；String 在类型上就存不进去，
编译期即恒 false）。输入值只存在于组合内存 state 与尝试函数的参数里。
