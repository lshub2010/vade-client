# g-live · RESULT —— 三端真数据开关统一 + W4 实跑手册

分支 `tmp/g-live`（基于 main @ **ac79c96**） · commit **9941a1c** · 未 push、未合并。

## 交付清单

| 交付 | 位置 |
| --- | --- |
| 统一开关（commonMain） | `composeApp/src/commonMain/kotlin/vade/ui/data/SourceConfig.kt` |
| 三端 actual | `desktopMain/.../data/SourceConfig.desktop.kt` · `androidMain/.../data/SourceConfig.android.kt` · `wasmJsMain/.../data/SourceConfig.wasmJs.kt` |
| 入口接线 | `desktopMain/.../Main.kt:131-146`（含过期注释修复） · `androidMain/.../MainActivity.kt:41-63` · `wasmJsMain/.../main.kt:53-58` |
| 测试 | `desktopTest/.../data/SourceConfigParseTest.kt`（15）· `desktopTest/.../ui/SourceSwitchUiTest.kt`（2）· `desktopTest/.../e2e/RealCoreUiE2ETest.kt`（1） |
| W4 手册 | `shots/g-live/PLAYBOOK.md` + 可复制脚本 `shots/g-live/core_launcher.py` |

`App.kt` **零改动** —— 三端入口显式传 `source = sessionSourceFromEnvironment()`，默认参数
`source: SessionSource = FakeSessionSource()` 原样未动。`RealSessionSource.kt`、`protocol/**`、
`shell/**`、`build.gradle.kts` 均未触碰；Android 未加 BuildConfig 字段（走 intent extra）。

## 侦察结论（全部带行号，详见 PLAYBOOK §0）

1. **起 core**：`RealCoreE2ETest.kt:81-169` 的 LAUNCHER_TEMPLATE 抽成人手命令 =
   `shots/g-live/core_launcher.py`（已实跑）。python 解释器
   `D:/HermesWorkspace/Vade/Vade/.venv/Scripts/python.exe`，数据目录任意临时目录，
   `--port 18744`。READY 在 **stdout 第 2 行**（`{"stage":"ready","port":N,"pair_code":"…"}`），
   预期输出真实贴片在 PLAYBOOK §1（pair code 值不落任何文件，只写位置）。
2. **ui/attach 要配对码**：要。浏览器路径 `POST /ui/session{pair_code}` → `__Host-ac_ui`
   cookie → WS 升级带 cookie（`VadeTransport.kt:78-123`）；无 cookie 的连接被 core 当设备端、
   要求 Noise 握手（主仓 `server.py:305-322`、`connection.py:152-155`），纯 JSON 客户端说不通。
   配对码由 core 启动时 `app.pairing.create(Purpose.BROWSER, ttl=600)` 铸（launcher 内；
   主仓 `RealCoreE2ETest.kt:132` 同款），标准 `python -m vade` 没有免登录的铸码 CLI。
3. **默认端口 8443 对**：core schema 默认 `core.listen=127.0.0.1:8443`（主仓
   `schema/config.schema.json`，x-vade owner C2），http+ws、无 TLS（除非配 tls.cert/key）。
   `SourceConfig.DEFAULT_HOST/DEFAULT_PORT` 与之对齐并有测试钉住。

## 三端开关实现

- **共用**（无平台判断）：`RawSourceStrings` → `SourceConfig.parse`（唯一语法：`source=="real"`
  trim+ignore-case 才切；host/port 缺省回 `127.0.0.1:8443`；**非法 port 抛
  IllegalArgumentException 拒绝静默回退**）→ `SourceSpec.Fake | Real` →
  `sessionSourceFrom`：Fake 返回 `FakeSessionSource()`（fixture 路径逐字节不变），
  Real 返回 `AutoConnectingSessionSource(RealSessionSource(config), host, port)` —— 首读前自动
  `connect()` 并等 Connected（15s 预算；超时抛 `VadeException(UNAVAILABLE, "core at host:port
  not reachable within …ms (auto-connect)")`，进 loader 的 Failed 分类横幅，绝不挂死、绝不回假数据）。
  带配对码时直接构造含 `BrowserPairing` 的 `VadeClientConfig`（`RealSessionSource(host,port)`
  便捷构造器 `browserSession=null` 对真 core 走不通，见 PLAYBOOK 卡点 1）。
- **桌面**：`System.getenv("VADE_SOURCE"/"VADE_HOST"/"VADE_PORT"/"VADE_PAIR_CODE")`（行为保持）。
- **Android**：`MainActivity.onCreate` 组合前把 `intent?.extras` 装进 `AndroidSourceEnv` 桥，
  actual 读 `vade.source/vade.host/vade.port/vade.pair_code`，**逐键**回退同名 env（
  `mergeRawStrings`），precedence 有测试。
- **Wasm**：`window.location.search` → `rawStringsFromQuery`（commonMain 共用 grammar，desktop 可测）。

## UI 层真数据断言 —— 做成了（机器证据）

`RealCoreUiE2ETest.realCoreSessionTitlesRenderInAppAndFixturesStayAway`（7.8s 全链路）：

- 测试自起真 python core（临时数据目录、随机端口、脚本化 MockLLM、种两个会话），READY 行拿
  port+pair_code（只在本进程内流转）；
- `RealSessionSource(VadeClientConfig(browserSession=BrowserPairing(pair_code)))` connect →
  Connected → UiState loader 拉真行 → `runComposeUiTest { setContent { App(source = real) } }`；
- **断言字串**：`onNodeWithText("W4 实跑·真核心会话甲").assertExists()`、
  `onNodeWithText("W4 实跑·真核心会话乙").assertExists()`；
- **反例断言**：四个 fixture 标记 `RFC-0004 附件与多模态评审` / `vade core 构建矩阵升级` /
  `wasm 体积回归` / `AGP 9.5.1 矩阵验证` 全部 `fetchSemanticsNodes().isEmpty()`；行 id 全部
  core 铸造，`FIXTURE_IDS` 六个假 id 无一出现；
- finally `disconnect` + `kill` 并断言 core 进程死透（无孤儿 python）。

`SourceSwitchUiTest` 两条钉住 Main.kt:132-135 旧注释（"changes nothing visible"）已失效：
`App()` 默认仍出 fixture 行；`App(source = 注入源)` 后注入标题
`W4-UI 断言·注入源的一行` 上屏、`RFC-0004 附件与多模态评审` 消失。新注释写的是事实（见
`Main.kt:131-146`）。

## 三目标门禁（原始数字）

| 门禁 | 结果 |
| --- | --- |
| `gradle :composeApp:desktopTest --rerun` | **550 tests, 0 failures+errors, 1 skipped**（skip =
  `VadeClientSmokeTest` 的 `Assume`——需外部 `VADE_SMOKE_PAIR_CODE`，设计如此，非 skip 变绿）；
  基线 532 → **+18**（15 parse + 2 UI-switch + 1 e2e） |
| `gradle :composeApp:assembleDebug` | BUILD SUCCESSFUL（`composeApp-debug.apk` 产出确认） |
| `gradle :composeApp:compileKotlinWasmJs` | BUILD SUCCESSFUL（同一 invocation） |

commonMain 无 JVM 专有 API（grep `java.`/`String.format`/`Math.` 干净；`@Volatile` 用
`kotlin.concurrent.Volatile`）。

## 环境与已知卡点（实碰，PLAYBOOK §4 有全文）

- **worktree 缺 submodule**：`contracts/vade` 未检出 → `VadeContractCoverageGuardTest` 3 条红，
  原文 `contracts/vade/schema/events.json not found walking up from ...\composeApp — the
  contracts/vade submodule is not checked out (bash tools/setup-contracts.sh)`。
  已修：`git submodule update --init contracts/vade`（检出 @ 320384ae）。
- **时间敏感测试满负荷闪红（与本棒无关，做了对照实验）**：全套首跑与二跑各闪红过不同的
  既有测试（`RealCoreE2ETest.wrongPairCodeSurfacesAuthFailedFromUiSession`、
  `UiStateSourceWiringTest` 两条 wall-clock 断言 `expected:<Retryable> but was:<null>` /
  `expected:<[v2a]> but was:<[v1a]>`）。对照：基线 HEAD（stash 我的改动）单独跑
  `UiStateSourceWiringTest` 绿；带我的改动单独跑同一条绿；清掉机器上的常驻 python core 后
  **全套三跑 BUILD SUCCESSFUL（550/0/1）**。结论：机器负载型 flake，非本次改动引入；
  跑门禁别并行压机器。
- 我的 e2e 首版在满负荷下 waitUntil 超时一次：已加固（keepalive 用默认 10s——core 对 browser
  socket 有 2×heartbeat 静默关闭；UiState 先行断言 + 45s waitUntil + 失败时带 loader 状态报错）。
- 未解的接线缺口（留给后续棒）：Failed 横幅无重试按钮（`UiState.retrySessions()` 无人调）；
  wasm 的 pair code 进 URL 只适合本机演示，正路是 page-shell 配对（`VadeTransport.kt:64-66`
  注释写明）；attach(null) 新会话无标题（客户端显示 `（无标题）`，需要改名入口）。

## 不变量自检

- 未 push / 未 merge（HEAD=9941a1c 仅在本地 `tmp/g-live`）。
- `App.kt` 默认值未动；`shell/**`、`data/RealSessionSource.kt`、`protocol/**`、`build.gradle.kts`
  等禁区零改动（`git show --stat 9941a1c` 可验）。
- pair code / token / 密钥：本文件与 PLAYBOOK 均只有位置描述（stdout 第 2 行 `"pair_code"` 字段），
  无任何值。
- 未删任何既有测试/断言；唯一 skip 是既有 `Assume` 门。
