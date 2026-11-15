# 快速开始 —— 从零到跑起桌面客户端

> 口径对齐主仓 **`docs/ALPHA.md`**（口径 A · 桌面 alpha）。本文只写**在本机真实执行过**的步骤与输出；
> 没跑过的命令一律标注「未在本机验证」。发现文档与代码打架时，以代码为准。

---

## 当前状态（先读这个，别把计划当现状）

| 项 | 状态 | 依据 |
|---|---|---|
| 桌面端编译 `gradle :composeApp:desktopMainClasses` | ✅ 可用 | 2026-11-14 实测 `BUILD SUCCESSFUL in 4s` |
| 桌面端启动 `gradle :composeApp:run` | ✅ 可用，窗口真实渲染 | 实测拉起 `vade.ui.MainKt` 进程；截图 `shots/g-docs/desktop-quickstart-fixed.png` |
| 客户端全量测试 `gradle :composeApp:desktopTest --rerun` | ✅ **1118 tests / 0 failures / 0 errors / 1 skipped** | 2026-11-15 实测（`build/test-results/desktopTest/TEST-*.xml` 逐文件 tests= 求和，105 个类）；skipped = `VadeClientSmokeTest`（环境变量门控，见 §4）。**判据是三目标**：另加 `assembleDebug` + `compileKotlinWasmJs`，只跑桌面测试会把 Wasm 的编译错误整个藏住 |
| Android APK 构建 `gradle :composeApp:assembleDebug` | ✅ 可用 | 2026-11-14 实测，`composeApp-debug.apk` **21,878,888 字节** |
| Wasm 编译与链接 | ✅ 编译 + 链接均通过 | `compileKotlinWasmJs` 绿；`compileProductionExecutableKotlinWasmJs` 产出 `composeApp.wasm` **13,681,513 字节**。**浏览器里跑起来未验证** |
| 桌面打包分发 | ✅ **已实测出包** | `packageMsi` → `vade-ui-0.1.0.msi` **93,312,713 字节**；`createDistributable` → app-image **153.6 MB**（自带 jlink 运行时，运行机不需 JDK）。⚠️ alpha 序列的版本号限制见 `deploy/desktop/README.md` |
| **UI 数据** | ✅ **默认 fixtures（刻意的），真模式一条开关切换** | `App()` 的默认参数仍是 `source = FakeSessionSource()`（`vade/ui/App.kt`）——这是**有意为之**，让「真模式」永远是显式选择，否则测试与真机行为会纠缠。真源已接线：`RawSourceStrings` → `SourceConfig.parse` → `Spec.Real` → `AutoConnectingSessionSource`（15 s 预算）→ `RealSessionSource`；三端开关分别是桌面 `VADE_SOURCE/VADE_HOST/VADE_PORT`、Android intent extra 裸键 `source=real`（键表 `SourceKeys`，g-rescue-text 修前的文档误写为 `vade.source=real`——解析器从不读带 `vade.` 前缀的键）、Wasm `?source=real&host=…&port=…`。端到端证据：`RealCoreUiE2ETest` 起真 core、断言「界面出现 core 铸造的会话标题」**且**「假数据专有字符串一个都不在」 |
| 真连接（`ui/attach`、配对、断线重连） | ✅ **已接线**（配对码仍是启动期一次读入，界面内不可输入） | `RealSessionSource.connect()` 走 `ui/session` 配对 + `ui/attach`；协议层 `VadeClient` 覆盖 **35/35 方法**（含 RFC-0015 的 `ui/modules`）。配对码来自配置（桌面 env / Android intent / Wasm URL），**运行期没有输入控件**；界面内给的是可照抄的自救命令而不是重试码输入框 |
| 真聊天（`ui/submit` / `turn.delta` 流式 / 中断） | 🟡 **通道已接，真核流式端到端未复验** | `TurnGatewayReal` 已接 `ChatTranscriptSource`（历史 + **原始事件流**，不是 `observeEvents()` 的有损投影）；写入侧走同步 seam。但「发一条消息看到真核流式吐字」这条验收句**本机没有跑过**，别按已完成记账 |
| 搜索 | ⚠️ 契约已支持，UI 仍降级 | 契约 1.5.0 **已有** `session/search`（RFC-0014）；客户端 codec 由 `p-search` 批补，UI 仍走本地缓存索引 |
| echo 模块在 UI 里可调（ALPHA A3） | ❌ 不可演示 | echo 模块已迁出主仓到独立仓 `vade-module-echo`；模块页宿主（`g-module`）未落地，UI 未接线 |
| 会话元数据编辑（重命名/置顶/分组/图标/颜色） | ✅ UI 完整，⚠️ 未接真 RPC | `vade/ui/meta/` 8 文件 + 30 条测试；`SessionMetaActions` 的真适配器由 `w-adapt` 批实现 |
| 模型切换 / 用量 / 子代理面板 / 编辑重发 / 附件 | ✅ UI 完整，⚠️ 未接真 RPC | `model/` `usage/` `subagent/` `chat/` 均已落地并有测试；真适配器同样在 `w-adapt` |
| ask_user 卡片 | ❌ 未合并 | 实现完成（37 条测试）但在 `tmp/c-askuser`，等 `g-glass` 落地后合并（避免 `AppShell.kt` 冲突） |
| Android 真机 / 模拟器运行时 | ⚠️ 部分 | API 37 模拟器与 API 30 真机均装包启动成功、无崩溃；**已发现两个真 bug**（手势条压底栏 → `c-inset`；模糊不可见 → `g-glass`） |
| Linux / macOS | ❌ 未验证 | 本文全部命令在 **Windows 11 + MSYS bash** 实测 |

**一句话**：今天你能「编译 → 起窗口 → 跑测试 → 连一个真 core 的协议层（测试内）」，但**窗口里的所有内容都是假数据**；把假数据换成真数据是 ALPHA.md W2 的活，打包分发是 W3 的活。

---

## 环境要求

| 项 | 要求 | 本机实测值 | 说明 |
|---|---|---|---|
| 操作系统 | Windows 10/11（其余未验证） | Windows 11 + MSYS bash（git-bash） | 本文命令全部按 MSYS bash 写；PowerShell / cmd 语法不适用 |
| JDK | **21**（AGP 9 运行需要 JDK 17+） | Temurin **21.0.13**（`D:/Hermes/tools/jdk21`） | `java -version` 应显示 `21.0.x` |
| Gradle | **9.6.1**（下限 9.6.0，见错误 ②） | `D:/Hermes/tools/gradle-9.6.1` | 本工程**不生成 wrapper**（见 `settings.gradle.kts` 头注释：本机拉不动 distribution 下载），用系统 PATH 上的 gradle |
| Android SDK | 仅构建 APK 时需要；需有 **platforms/android-37.0** | `D:/Android/Sdk`（`local.properties` 指向，未入库）或 `D:/Hermes/tools/android-sdk`（`ANDROID_HOME`） | 两套 SDK 都有 android-37.0（实测 `ls` 确认）；AGP 先读 `local.properties` 的 `sdk.dir`，没有才看 `ANDROID_HOME` |
| 服务端（可选） | Python 3.12 venv（主仓自带 `.venv/`） | 主仓 `D:/HermesWorkspace/Vade/Vade/.venv` | 只在你想连真 core / 跑协议冒烟时需要 |
| 磁盘 | Gradle 用户目录别放 C: | `GRADLE_USER_HOME=D:/Hermes/tools/gradle-home` | 本机约定，非硬性 |

> 本机工具链（JDK / Gradle / SDK）都由 `D:/Hermes/tools` 提供、不进系统 PATH；每个新 shell 先执行
> `source D:/Hermes/tools/env/vade-env.sh`（详见主仓 `docs/dev-windows.md`）。**但注意错误 ②：
> 该脚本会把 Gradle 8.10.2 排到 PATH 最前，本工程必须用 9.6.1，要再补一行 PATH 前缀。**

---

## 0. 取代码（含契约 submodule）

```bash
git clone --recurse-submodules https://github.com/lshub2010/vade-client.git
# 已 clone 过的：
git submodule update --init --recursive
```

> 客户端构建当前不读 `contracts/vade/schema/`（协议层是手写 codec），但 submodule 建议照常初始化，
> 后续批次要用。本步命令沿用 README 既有说明，属引用；本文实测发生在既有 clone + worktree 上。

## 1. 环境变量（每个新 shell 一次）

```bash
source D:/Hermes/tools/env/vade-env.sh
export JAVA_HOME=D:/Hermes/tools/jdk21
# 关键：把 9.6.1 排到 PATH 最前（vade-env.sh 会把 8.10.2 排最前，不加这行必挂，见错误 ②）
export PATH="/d/Hermes/tools/gradle-9.6.1/bin:$PATH"
```

**预期输出**（验证工具链版本，2026-11-14 实测）：

```
$ gradle --version
Gradle 9.6.1
Launcher JVM:  21.0.13 (Eclipse Adoptium 21.0.13+9-LTS)
Daemon JVM:    D:\Hermes\tools\jdk21 (no Daemon JVM specified, using current Java home)
```

## 2. 编译桌面端

```bash
gradle :composeApp:desktopMainClasses
```

**预期输出**（2026-11-14 实测，daemon 与缓存已热）：

```
> Task :composeApp:desktopMainClasses

Deprecated Gradle features were used in this build, making it incompatible with Gradle 10.
...
BUILD SUCCESSFUL in 4s
5 actionable tasks: 4 executed, 1 from cache
```

> ⚠️ 这一步**全绿不代表 Android 侧能编**：AGP 9 把 SDK 检查推迟到真正执行 Android 任务时——
> 实测在没有任何 SDK 配置的干净 clone 上 `:composeApp:desktopMainClasses` 照样 `BUILD SUCCESSFUL`。
> 验证 Android 要跑 `:composeApp:assembleDebug`（§5）。

## 3. 启动桌面客户端

```bash
gradle :composeApp:run
```

**预期现象**（2026-11-14 实测）：编译后弹出一个 466×893 的窗口，标题 **「Vade · UI 骨架」**，
内容为会话列表页：置顶 2 条 + 今天 2 条 + 昨天 1 条 + 7 天内 1 条（6 条**假数据**），
页面顶部有横幅「**假数据：未连接 core（U1-a 只做骨架，U1-b 接线）**」，底栏四个目的地
（会话 / 活动 / 能力 / 我的）。实测通过 `tasklist` 能看到 `vade.ui.MainKt` 的 java 进程。
关掉窗口即结束进程。

真实窗口截图（Windows，本仓验证截图的既定路径；2026-11-14 实测输出）：

```bash
gradle :composeApp:desktopMainClasses :composeApp:printRuntimeClasspath -q > cp.txt
pwsh -NoProfile -ExecutionPolicy Bypass -File tools/capture-window.ps1 \
  -Java "$JAVA_HOME/bin/java.exe" -ClassPathFile cp.txt \
  -Bar fixed -Dest sessions -OutFile shots/g-docs/desktop-quickstart-fixed.png
```

```
launched pid=20980 bar=fixed size=480x900 dest=sessions
window rect=55,48 466x893
PrintWindow=True center=Color [A=255, R=247, G=247, B=248]
saved shots/g-docs/desktop-quickstart-fixed.png 466x893 31940 bytes
killed pid=20980
```

常用启动参数（`desktopMain/kotlin/vade/ui/Main.kt` 头注释，均为代码内既有功能）：
`--bar=fixed|floating|blur`（底栏三态）、`--dest=sessions|activity|capabilities|me`、
`--skin=miuix` / `--dark`（miuix 皮肤与暗色）、`--open=<sessionId>` + `--chat=live|tools|queue|...`
（直开某会话并种派生状态）、`--search=<query>`（开搜索覆盖层）、`--motion-probe`（动效探针）。

## 4. 跑测试（防回退 + 协议层）

```bash
gradle :composeApp:desktopTest --rerun
```

**预期输出**（2026-11-14 实测；`--rerun` 强制重跑，见错误 ④）：

```
BUILD SUCCESSFUL in 5s
11 actionable tasks: 4 executed, 7 up-to-date
```

对 `composeApp/build/test-results/desktopTest/*.xml` 逐文件汇总（实测）：**1118 tests / 0 failures / 0 errors / 1 skipped**（2026-11-15 合并夜间十余批后的数；这条数字随批次增长，读的时候以自己跑出来的为准）。

唯一 skipped 是 `vade.ui.protocol.VadeClientSmokeTest`：它用 `org.junit.Assume` 门控——只有设了
`VADE_SMOKE_PORT` 等环境变量才会真连一个 core 跑冒烟，普通执行标记 skipped（**不是静默 pass**）。
想真跑冒烟的完整步骤见 `shots/w1-proto/smoke.md`（含起真 core + 铸配对码的驱动脚本）。

## 5.（可选）构建 Android APK

```bash
gradle :composeApp:assembleDebug
```

**预期输出**（2026-11-14 实测）：

```
BUILD SUCCESSFUL in 1m 19s
38 actionable tasks: 25 executed, 13 from cache
```

产物：`composeApp/build/outputs/apk/debug/composeApp-debug.apk`（实测 21,534,706 字节 ≈ 20.5 MB）。

> APK 能编出来 ≠ 能装能跑。Android 运行时验收（API 37 模拟器 + API 30 真机）是 ALPHA.md A6
> 的未完成项，本机未验证。

## 6.（可选）起服务端 core —— 客户端 UI 目前连不上它，为什么还要写

**如实说明：当前 UI 仍是假数据，真连接见 ALPHA.md 的 W2。** 协议客户端（`ui/protocol/`）对真核
的 attach/submit/事件流已通过冒烟测试真跑过（`shots/w1-proto/smoke.md`），所以这一节的价值是：
给协议冒烟测试、以及 W2 接线后的验证，提供一个真实可连的 core。

core 在**主仓**（不是本仓）。最小启动配置（与 w1-proto 冒烟实测所用一致）：

```toml
# core.toml —— 最小可跑配置（冒烟实测版）
[core]
listen = "127.0.0.1:8010"          # 数据面（含 /healthz、/ws）
admin_host = "127.0.0.1:0"         # 管理面，0=随机口；配对码正常由管理面 /admin/api/invites 铸
data_dir = "D:/tmp/vade-dev/data"  # 改成你自己的目录
log_level = "warning"

[dev]
allow_all = true                   # 仅本机开发
allow_missing_components = true
```

```bash
cd D:/HermesWorkspace/Vade/Vade
PYTHONPATH=core:protocol/core/python .venv/Scripts/python.exe -m vade --config core.toml
```

**预期输出**（2026-11-14 实测，另开一个 shell）：

```
$ curl -s http://127.0.0.1:8010/healthz
{"ok": true, "version": "0.1.0", "policy_mode": "allow_all"}

$ curl -s -i -N -H "Connection: Upgrade" -H "Upgrade: websocket" \
    -H "Sec-WebSocket-Key: <16字节随机数的base64占位>" -H "Sec-WebSocket-Version: 13" \
    --max-time 5 http://127.0.0.1:8010/ws
HTTP/1.1 101 Switching Protocols
Server: uvicorn
Upgrade: websocket
```

配对说明（如实）：浏览器/UI 侧配对 = `POST /ui/session`（`{pair_code, client_name}`）换
`__Host-ac_ui` cookie，再拿 cookie 升级 `/ws`。配对码正常走管理面 `/admin/api/invites`；
`python -m vade` **不**起管理面，冒烟驱动是直接用 core 自己的 `PairingStore` 铸的一次性码
（`shots/w1-proto/smoke_core.py`）。JVM/Android 路径的这套配对在 `KtorTransportFactory`
（`commonMain/kotlin/vade/ui/protocol/VadeTransport.kt`）已实现；wasm 浏览器路径未实现。

模块现实（如实）：core 无模块在线时，`ui/submit` 会得到 core 的 notice 短路回复
（"No capabilities are available: no module is online…"，实测记录 `shots/w1-proto/smoke.md`）。
echo 模块已迁出到独立仓 `vade-module-echo`（本地 `D:/HermesWorkspace/Vade/Vade-module-echo`，
`README.md` 给了 `pip install -e` / `python -m echo_module` 的启动配方——**本机未验证**）。

---

## 常见错误与解法（全部在本机复现过）

### ① SDK location not found

**症状**（2026-11-14 实测：干净 clone、无 `local.properties`、清掉 `ANDROID_HOME`/`ANDROID_SDK_ROOT`，
执行 `gradle :composeApp:assembleDebug`）：

```
* What went wrong:
Could not determine the dependencies of task ':composeApp:compileDebugJavaWithJavac'.
> SDK location not found. Define a valid SDK location with an ANDROID_HOME environment
  variable or by setting the sdk.dir path in your project's local properties file at
  'D:\...\vade-client\local.properties'.
```

**解法**：二选一——写未入库的 `local.properties`（推荐，路径用**正斜杠**，见错误 ③）：

```properties
sdk.dir=D:/Android/Sdk
```

或导出 `ANDROID_HOME`（MSYS bash 里用 `/d/...` 形式没问题，这个变量是给 bash 展开的；
`local.properties` 里则必须 Windows 形式）。注意还要**装有 android-37.0 平台**
（`sdkmanager "platforms;android-37.0"`），compileSdk 37 编译要读它。

### ② Gradle 版本不符（Minimum supported Gradle version is 9.6.0）

**症状**（2026-11-14 实测：用 `D:/Hermes/tools/gradle-8.10.2` 跑）：

```
w: Deprecated Gradle Version
The used Gradle version (Gradle 8.10.2) is deprecated and will not be supported in
future Kotlin Gradle Plugin releases.
...
* What went wrong:
An exception occurred applying plugin request [id: 'com.android.application']
> Failed to apply plugin 'com.android.internal.version-check'.
   > Minimum supported Gradle version is 9.6.0. Current version is 8.10.2.
```

**根因**：`source D:/Hermes/tools/env/vade-env.sh` 会把 **gradle-8.10.2 排到 PATH 最前**
（脚本为 Rust/Python 侧准备，没跟着客户端矩阵更新）。本工程 AGP 9.5.1 硬性要求 Gradle ≥ 9.6.0。

**解法**：每次 source 之后补 `export PATH="/d/Hermes/tools/gradle-9.6.1/bin:$PATH"`，
再 `gradle --version` 确认是 9.6.1。`desktopMain/kotlin/vade/ui/Main.kt` 的头注释也写了同一件事
（「vade-env.sh puts 8.10.2 first, so prefix PATH=...」）。

### ③ `D:\d\` 双前缀路径（MSYS 形式路径漏进 JVM/原生程序）

**根因**：JVM 与所有原生 Windows 程序都把 `/d/xxx` 解析成「**当前盘符**根目录下的 `d/xxx`」。
cwd 在 D: 时，`/d/Android/Sdk` 实际变成 `D:\d\Android\Sdk`。实测（2026-11-14，JDK 21）：

```
new java.io.File("/d/Android/Sdk").getAbsolutePath()
  -> D:\d\Android\Sdk
   exists? = false
```

**典型踩法**：把 `sdk.dir=/d/Android/Sdk`（MSYS 形式）写进 `local.properties` —— AGP 找不到这个
目录，报回 ① 的 SDK location not found（**报错文本不会告诉你它解析成了什么**，这是它阴的地方）。

**变体**：`.properties` 文件里反斜杠是转义符。写 `sdk.dir=D:\Android\Sdk` 实际读到的是
`D:AndroidSdk`，实测报：

```
> java.io.IOException: 文件名、目录名或卷标语法不正确。
```

**解法**：`local.properties` 里永远写正斜杠 Windows 形式：`sdk.dir=D:/Android/Sdk`。
MSYS 的 `/d/...` 形式只用于 bash 内部（PATH、cd 等），凡是要落进文件或传给 java/gradle 的路径
都用 `D:/...`。这与主仓 `docs/dev-windows.md` §5 的规则一致。

### ④ UP-TO-DATE / 构建缓存假绿灯

**症状**：改了被测行为后跑 `gradle :composeApp:desktopTest`，4 秒就绿——实际测的是**上一次**的
结果（`2 from cache, 3 up-to-date`），或测试任务整体 up-to-date 被跳过。2026-11-14 实测对比：

```
# 不带 --rerun（可能吃缓存/UP-TO-DATE）：
BUILD SUCCESSFUL in 4s
11 actionable tasks: 6 executed, 2 from cache, 3 up-to-date

# 带 --rerun（强制真跑测试任务）：
BUILD SUCCESSFUL in 5s
11 actionable tasks: 4 executed, 7 up-to-date
```

**解法**：验「真的绿」用 `--rerun`（只强制测试任务重跑，编译仍走缓存，代价小）；
看测试数以 `build/test-results/desktopTest/*.xml` 汇总为准，别只看 BUILD SUCCESSFUL。
w1-proto 批次就是这么核的（`shots/w1-proto/RESULT.md`：XML 逐文件核对 140 tests）。
另一个假绿灯变体：`desktopMainClasses` 绿 ≠ Android 侧能编（SDK 检查被推迟，见 §2 警告）。

### ⑤ Gradle daemon 吃着旧环境变量（改了 env 不生效）

**症状**：2026-11-14 实测，在已跑过构建的机器上 `env -u ANDROID_HOME gradle ...` 想复现
SDK-not-found，结果构建照样成功——**daemon 是用带 ANDROID_HOME 的环境启动的**，客户端进程
`env -u` 管不到它。

**解法**：改环境变量后先 `gradle --stop` 杀掉 daemon 再跑；否则新变量对 daemon 不生效。

### ⑥ `gradle :composeApp:tasks` 直接失败（本仓已知怪癖）

**症状**（2026-11-14 实测）：

```
> Task :composeApp:tasks FAILED
> Could not create task ':composeApp:generateDebugComposePreviewRunfiles'.
   > Compose Preview failed for ':composeApp' (variant 'debug'): Android resources are
     excluded from unit tests ...
```

**解法**：没有——别用 `tasks` 列任务，直接跑已知任务名（`run` / `desktopMainClasses` /
`desktopTest` / `assembleDebug` / `packageMsi` 均实测存在或 dry-run 通过）。

### ⑦（良性警告）Compose 插件 1.12.0 vs classpath 上的 ui:1.12.2

**症状**（2026-11-14 实测，`gradle :composeApp:run` 输出中出现）：

```
expected: 'org.jetbrains.compose.ui:ui:1.12.0'
actual:   'org.jetbrains.compose.ui:ui:1.12.2'
This may lead to compilation errors or unexpected behavior at runtime.
```

**现状**：某传递依赖把 compose-ui 拉到 1.12.2；实测桌面窗口正常渲染、368 条测试全绿，当前
**只是警告**。不要自行乱升插件版本去消警告——动矩阵前先读 `docs/ENVIRONMENT.md` 的链条。

---

## 命令验证状态一览

| 命令 | 状态 |
|---|---|
| `gradle --version` | ✅ 实测（9.6.1 / JVM 21.0.13） |
| `gradle :composeApp:desktopMainClasses` | ✅ 实测 |
| `gradle :composeApp:run` | ✅ 实测（窗口进程 + 截图） |
| `tools/capture-window.ps1`（截图循环） | ✅ 实测 |
| `gradle :composeApp:desktopTest` / `--rerun` | ✅ 实测（368 / 0 / 1 skipped） |
| `gradle :composeApp:assembleDebug` | ✅ 实测（APK 20.5 MB） |
| `gradle :composeApp:packageMsi` / `createDistributable` | ⚠️ 仅 `--dry-run` 验证任务存在，未真正执行 |
| `gradle :compileKotlinWasmJs` | ❌ 未在本机验证（w1-proto 批次记录为绿，引用） |
| core：`python -m vade --config core.toml` + healthz + /ws | ✅ 实测（经 `shots/w1-proto/smoke_core.py` 驱动） |
| echo 模块启动（独立仓配方） | ❌ 未在本机验证 |
| Linux / macOS 全部命令 | ❌ 未在本机验证 |
