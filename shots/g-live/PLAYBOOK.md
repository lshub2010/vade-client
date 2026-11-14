# g-live · W4 实跑手册（真 core + 真数据，三端开关）

> 本手册所有命令都在 **客户端仓 worktree**（`D:/HermesWorkspace/Vade-client-wt/g-live`）与
> **主仓**（`D:/HermesWorkspace/Vade/Vade`，Python core）之间操作。所有命令均已实跑验证
> （2026-11-14，Windows 11 / MSYS bash）。**本文件不含任何 pair code / token / 密钥值** ——
> 它们只在进程 stdout 的固定行出现（位置见下文），用完即弃。

---

## 0. 侦察结论（先读这个）

| 问题 | 结论 | 证据（文件+行号） |
| --- | --- | --- |
| 真 core 怎么起 | 用 `shots/g-live/core_launcher.py`（本仓新文件），`python <脚本> --data-dir <临时目录> --port <端口>`；READY 打在 **stdout 第 2 行** | `composeApp/src/desktopTest/kotlin/vade/ui/e2e/RealCoreE2ETest.kt:81-169`（LAUNCHER_TEMPLATE 的同源脚本）+ 主仓 `core/vade/__main__.py:139-189`（`_serve` 同构） |
| `ui/attach` 要不要配对码 | **要**。浏览器路径必须先 `POST /ui/session {pair_code, client_name}` 换 `__Host-ac_ui` cookie，WS 升级带 cookie 才放行；**没有 cookie 的连接会被 core 当作设备端，要求 Noise 握手**，纯 JSON 客户端（`KtorTransportFactory`）根本说不上话 | 主仓 `core/vade/transport/server.py:305-322`（cookie 校验 + `if not browser and self._make_crypto is None` 分支）、`core/vade/transport/connection.py:152-155, 205-209`（browser_session=False 走 Noise）；客户端 `composeApp/src/commonMain/kotlin/vade/ui/protocol/VadeTransport.kt:78-123`（`ensureCookie`） |
| 配对码从哪来 | launcher（或 `RealCoreE2ETest` 的模板）在启动时 `app.pairing.create(Purpose.BROWSER, ttl=600)` 铸一枚一次性码，打印在 READY 行的 `"pair_code"` 字段（**stdout 第 2 行**）；标准 `python -m vade` 的 core 没有直接铸码的 CLI —— 配对码走管理面 HTTP API（主仓 `core/vade/admin/app.py:765-786`），需要 admin 登录，W4 演示一律用 launcher | launcher `shots/g-live/core_launcher.py`（`pair_code = app.pairing.create(...)`）+ 主仓 `RealCoreE2ETest.kt:132`（同款调用） |
| 默认端口 8443 对不对 | **对**。core schema 默认 `core.listen = 127.0.0.1:8443`，纯 http+ws，无 TLS（除非配 `tls.cert/key`） | 主仓 `schema/config.schema.json`（`core.listen` default `127.0.0.1:8443`，x-vade owner C2）；客户端 `SourceConfig.kt` 的 `DEFAULT_HOST/DEFAULT_PORT` 与之对齐 |
| 传输是什么协议 | HTTP(S) `POST /ui/session` + `GET /healthz` + WS `ws(s)://host:port/ws`；core 发 BINARY 帧（UTF-8 JSON） | 主仓 `core/vade/transport/server.py:3`（路由注释）；客户端 `VadeTransport.kt:127`（websocketUrl） |

---

## 1. 起一个真 core（可整段复制）

```bash
# ① 数据目录用一个一次性临时目录（绝不碰真实会话库）
mkdir -p /d/Hermes/cache/scratch/vade-w4-core

# ② 启动（前端进程，Ctrl+C 停掉；stdin 无需交互）
D:/HermesWorkspace/Vade/Vade/.venv/Scripts/python.exe \
  D:/HermesWorkspace/Vade-client-wt/g-live/shots/g-live/core_launcher.py \
  --data-dir /d/Hermes/cache/scratch/vade-w4-core --port 18744
```

**预期输出（真实贴片，2026-11-14 实跑）**：

```text
{"stage": "boot", "data_dir": "/d/Hermes/cache/scratch/vade-w4-core"}
{"ts":"2026-11-14T13:00:55.963Z","level":"info","logger":"vade.app","msg":"core identity loaded"}
{"stage": "ready", "port": 18744, "pair_code": "XXXX-XXXXXX"}
```

- **pair code 只出现在 stdout 第 2 行**（`"pair_code"` 字段，格式 `汉字-六位`，一次性，ttl 600s）。
  复制它，别提交、别贴日志。
- 脚本会种两个演示会话（`W4 实跑·真核心会话甲/乙`），`--no-seed` 可关。
- LLM 是脚本化 `MockLLMProvider`（core 自己 e2e 的同款注入），**不调外部 API**。
- 验证活着：`curl http://127.0.0.1:18744/healthz` → `{"ok": true, "version": "0.1.0", "policy_mode": "allow_all"}`。

**不换端口会怎样**：18744 与默认 8443 都无人占时可随意；`--port 0` = 随机端口（READY 行告诉你实际端口），
UI 测试就是这么做的，永不冲突。

### 1b. 备选：标准 `python -m vade`（了解即可，W4 不推荐）

```bash
cd D:/HermesWorkspace/Vade/Vade
PYTHONPATH='core;protocol/core/python' ./.venv/Scripts/python.exe -m vade --bind 127.0.0.1:8443
```

注意 Windows 下 PYTHONPATH 分隔符是 **`;`** 不是 `:`（MSYS bash 也不会帮你转）。这条路没有 launcher 的
种子数据与现成 pair code，浏览器配对码要走管理面 API（需 admin 初始化），所以 W4 实跑一律用 §1 的 launcher。

---

## 2. 三端开真模式（g-live 统一开关）

一个公共解析器：`composeApp/src/commonMain/kotlin/vade/ui/data/SourceConfig.kt`
（`RawSourceStrings` → `SourceConfig.parse` → `SourceSpec.Fake | SourceSpec.Real`），
三端只负责取原始字符串（`expect fun rawSourceStrings()` 的三个 actual）。语法三端一致：

- `source` 必须是 `real`（trim + 忽略大小写），其它值/缺省一律假数据（fixture 行为逐字节不变）；
- host 缺省 `127.0.0.1`，port 缺省 `8443`（与 core schema 默认对齐）；
- **port 非法（非 1..65535 整数）→ 直接抛错，绝不静默回退**；
- 配对码缺省时连接会失败（见 §0 的「没有 cookie = Noise 路径」），**真模式务必带上配对码**。

### 2a. 桌面（环境变量）

```bash
VADE_SOURCE=real \
VADE_HOST=127.0.0.1 \
VADE_PORT=18744 \
VADE_PAIR_CODE=<stdout第2行的pair_code> \
PATH=/d/Hermes/tools/gradle-9.6.1/bin:$PATH \
gradle :composeApp:run --args="--dest=sessions"
```

改动前后的差别（`desktopMain/kotlin/vade/ui/Main.kt:131-146`）：
以前 `VADE_SOURCE=real` 只换掉 source 对象（后来 w-wire 让 UiState 真读它），**但没有任何代码调
`connect()`**，界面只会停在 Failed 横幅；g-live 之后真模式返回的是
`AutoConnectingSessionSource`（`SourceConfig.kt`）：首次 `sessions()`/`messages()` 前自动
`connect()` 并等 Connected（15s 预算，超时报 `VadeErrorCodes.UNAVAILABLE` 的分类错误，横幅可见）。
core 可达 + 配对码有效 → 看到的是 core 里的真会话行；不可达 → 分类失败横幅（`LoadError.label`）。

### 2b. Android（intent extra 优先，env 兜底）

```bash
adb shell am start -n vade.ui/.MainActivity \
  --es vade.source real \
  --es vade.host 192.168.1.20 \
  --es vade.port 18744 \
  --es vade.pair_code <stdout第2行的pair_code>
```

`MainActivity.onCreate` 在组合前把 `intent?.extras` 装进 `AndroidSourceEnv`
（`androidMain/.../MainActivity.kt` + `androidMain/.../data/SourceConfig.android.kt`）；
extra 缺的 key 逐键回退到同名环境变量（`mergeRawStrings`）。没有任何 extra/env 时仍是假数据。

### 2c. Wasm（URL query）

```text
http://127.0.0.1:8080/?source=real&host=127.0.0.1&port=18744&pair_code=<stdout第2行的pair_code>
```

（先 `gradle :composeApp:wasmJsBrowserDevelopmentRun` 或部署后打开页面；query 解析在
`SourceConfig.kt` 的 `rawStringsFromQuery`，wasm actual 一行调用。）

**已知限制**：浏览器 JS 拿不到 `Set-Cookie`，Ktor 工厂注释里写明了配对必须由页面 shell 做
（`VadeTransport.kt:64-66`）；桌面/Android 的工厂自己处理 cookie。g-live 只是让 wasm 也能被
同一个开关切到真源，**把 pair code 放 URL 只适合本机演示**，正式部署要走 page-shell 配对（未接线，
见 §4 卡点）。

---

## 3. UI 层机器证据（alpha A1）

`composeApp/src/desktopTest/kotlin/vade/ui/e2e/RealCoreUiE2ETest.kt` —— 全链路零 mock：

```
测试内嵌 launcher 起真 core（临时数据目录，随机端口）
→ READY 行拿 port + pair_code（只在本进程内）
→ RealSessionSource(VadeClientConfig(browserSession = BrowserPairing(pair_code)))
→ connect + 等 Connected
→ UiState(source = real)（与组合同一加载路径）拉真行，断言种子标题
→ runComposeUiTest { setContent { App(source = real) } }
→ 断言：真核心的两个种子标题出现在界面；
        四个 fixture 标记（RFC-0004…/AGP 9.5.1…/wasm 体积回归/构建矩阵）一个都不在；
        行 id 全部 core 铸造，无一来自 FakeSessions
→ finally disconnect + kill core（断言进程死透，无孤儿 python）
```

跑法：

```bash
PATH=/d/Hermes/tools/gradle-9.6.1/bin:$PATH gradle :composeApp:desktopTest \
  --tests "vade.ui.e2e.RealCoreUiE2ETest" --rerun
```

配套的「旧注释已死」UI 断言：`composeApp/src/desktopTest/kotlin/vade/ui/SourceSwitchUiTest.kt` ——
`App()` 不带 source 仍出 fixture 行；`App(source = 注入源)` 后注入标题上屏、fixture 行消失
（`Main.kt:132-135` 的旧注释「changes nothing visible」就此作废）。

---

## 4. 已知卡点清单（全部实碰，含原文）

1. **没有配对码 = 纯 JSON 客户端说不通话**。`RealSessionSource(host, port)` 便捷构造器传
   `browserSession = null`（`data/RealSessionSource.kt:80-87`），对真 core 的 WS 升级会被
   当设备端走 Noise（`server.py:314-317`）。g-live 的开关因此加了 `VADE_PAIR_CODE` /
   `vade.pair_code` / `?pair_code=`，从 `SourceConfig.kt` 的 `sessionSourceFrom` 直接构造带
   `BrowserPairing` 的 `VadeClientConfig`。实测：不带配对码时 WS 能升级但帧全乱，最终
   `session/list` 以 `TIMEOUT` 失败横幅收场（不是崩溃，也不是假数据）。
2. **attach(sessionId = null) 铸的新会话没有标题**：core 的 `attach` 走 `create_session(title=None)`
   （主仓 `core/vade/session/manager.py:670-674, 309-313`），客户端映射成 `（无标题）`
   （`data/SessionRowMapping.kt:39`）。所以「首次运行配对流程」如果不先改名，列表里会出现一行
   `（无标题）` —— 属实数据行为，不是 bug，但 UI 要给改名的入口。
3. ~~**Failed 横幅没有重试按钮**~~ → **已关闭（`g-error-retry`）**。当时的现状是 `UiState.retrySessions()`
   没有任何 UI 调它、横幅常驻只能重启；现在 `LoadErrorBanner` 带「⟳ 重试」胶囊（`retryDescription`
   有会话列表与单会话两条），点击 → 清错 → 按钮变「重试中…」并禁用。防连点的不变量是
   `error == null && phase ∈ {Loading, Failed}`。`g-first-run` 又补了第二件事：连不上时横幅里
   **可照抄的启动命令**（host/port 取真实配置，配对码只显示占位符 `<配对码>`，绝不回显）。
   视觉证据：`shots/g-error-retry/banner-light.png`（淡红卡片 + 警示图标 + 分类与传输原话 + 人话提示）。
4. **wasm 的配对码只能进 URL**（见 §2c）：`fetch` 读不到 `Set-Cookie`，页面 shell 配对是正路，
   未接线。
5. **`git worktree` 不带 submodule**：本 worktree 一开始 `contracts/vade` 是空的，
   `VadeContractCoverageGuardTest` 三条全红，原文
   `contracts/vade/schema/events.json not found walking up from ...\composeApp — the contracts/vade
   submodule is not checked out (bash tools/setup-contracts.sh)`。
   修复：`git submodule update --init contracts/vade`（本棒已做）。**每换一个 worktree 都要重来**。
6. **时间敏感测试在重载机器上会闪红**：`UiStateSourceWiringTest`（wall-clock 轮询断言）与
   `RealCoreE2ETest.wrongPairCodeSurfacesAuthFailedFromUiSession` 在满负荷跑全套时偶发
   （同一次全套里红的是 A，下一次是 B，单独跑皆绿）。与本棒改动无关（对照实验见
   RESULT.md），但跑门禁时尽量别并行压机器。
7. **launcher 脚本里 core/protocol 目录是写死的**（`D:/HermesWorkspace/Vade/Vade/...`）：
   测试模板（`RealCoreUiE2ETest.LAUNCHER`）用 `@CORE_DIR@/@PROTOCOL_DIR@` 占位符 +
   `RealCoreE2ETest.PYTHON/CORE_PKG_DIR/PROTOCOL_PY_DIR`（可被 `VADE_PYTHON` 等环境变量覆盖），
   shots 里的独立脚本把同样路径直接写死。换机器改这两处常量即可。

---

## 5. 对照表：一个 raw 串进三端，出来的 SourceSpec 一样

| 输入 | 桌面 env | Android extra | Wasm query |
| --- | --- | --- | --- |
| 不给 | `Fake` | `Fake` | `Fake` |
| `source=real`（无 host/port） | `Real(127.0.0.1, 8443)` | 同左 | 同左 |
| `source=real&host=10.0.0.5&port=9911` | `VADE_SOURCE=real VADE_HOST=10.0.0.5 VADE_PORT=9911` | `--es vade.source real --es vade.host 10.0.0.5 --es vade.port 9911` | `?source=real&host=10.0.0.5&port=9911` |
| `port=8443x` | 抛 `IllegalArgumentException`（不静默回退） | 同左 | 同左 |

钉死这三端一致性的测试：`desktopTest/kotlin/vade/ui/data/SourceConfigParseTest.kt`
（`threeRawChannelsAgreeOnTheSameSpec`、`androidPrecedenceExtrasBeatEnvPerKey` 等 14 条）。
