# Vade 客户端

[English](README.md) | **简体中文**

![Release build](https://github.com/lshub2010/vade-client/actions/workflows/release.yml/badge.svg)
![CI](https://github.com/lshub2010/vade-client/actions/workflows/ci.yml/badge.svg)

**Vade**（中文名「偕行」）是一个内核极薄、能力全靠模块的 Agent 运行时：连接、路由、回合循环、密钥与审计留在 core，模型、工具、记忆和客户端都以独立进程接入。

本仓库是它的**客户端**：Android、桌面（JVM）与 Web（wasmJs）共用同一套 Compose Multiplatform UI。协议内核、契约与设计文档在 **[lshub2010/Vade](https://github.com/lshub2010/Vade)**。客户端自身不包含模型，**必须连接一个 core** 才能工作。

> [!WARNING]
> **项目处于 alpha 阶段。** 功能尚不完整，协议、数据格式与界面行为都可能随版本变化。不要用于生产环境，也不要用它存放不可恢复的重要数据；升级前请自行备份。

> [!WARNING]
> **本项目由 AI 辅助开发。** 代码在 AI 生成的基础上经过了自动化测试与人工审查，但不保证风格统一、可读性良好、注释完备，也不保证所有边界条件与异常路径都已覆盖。用于关键场景前，请自行审计、加固并补充测试。

## 发布状态

- **当前发布：`0.0.2-alpha`** —— [下载页](https://github.com/lshub2010/vade-client/releases/tag/v0.0.2-alpha)。下列产物由 CI 构建并挂在该 Release 上。
- 唯一的版本源（[`gradle.properties`](gradle.properties) 里的 `vadeVersion`）是 `0.0.2-alpha`；Android 的 `versionName` 与 MSI 的 `packageVersion`（数字部分 `0.0.2`）都由它派生。
- Android APK 由 `:composeApp:assembleDebug` 构建，使用 **debug 签名**（见[debug 签名说明](#debug-签名说明)）。

## 三端状态

| 平台 | 状态 | 说明 |
|---|---|---|
| Android | ✅ 可用 | minSdk 24，targetSdk 36，compileSdk 37。已在 API 30 真机与 API 37 模拟器上验证。 |
| 桌面 · Windows | ✅ 可用 | 可用 MSI 安装器或免安装 zip（自带运行时）；也可用 `gradle :composeApp:run` 直接运行。 |
| 桌面 · macOS / Linux | ⚠️ 未验证 | 源码与构建路径相同，但没有现成产物，也没有记录在案的验证结果。需自备 JDK 21 从源码构建。 |
| Web · wasmJs | 🟡 能编译，CI 会打包静态站点 | 页面与 core 同源（或 Origin 在白名单）时可以完成首次配对；构建出的站点尚未在真实浏览器验证。 |

## 下载与安装（0.0.2-alpha）

发布页：**<https://github.com/lshub2010/vade-client/releases/tag/v0.0.2-alpha>**

| 产物 | 平台 | 安装方式 |
|---|---|---|
| `vade-ui-0.0.2-alpha-android.apk` | Android | 允许「未知来源」后侧载安装。 |
| `vade-ui-0.0.2-alpha-windows-x64.msi` | Windows | 运行安装器。若某次发布没有 MSI（WiX 步骤允许失败），改用 zip。 |
| `vade-ui-0.0.2-alpha-windows-x64.zip` | Windows | 解压到任意目录，运行 `vade-ui.exe`。自带 jlink 运行时，目标机器不需要 JDK。 |
| `vade-ui-0.0.2-alpha-web.zip` | Web | 解压后用 HTTP 静态服务器托管该目录；不能直接以 `file://` 打开。 |

### debug 签名说明

APK 来自 `debug` 构建变体，本仓库没有配置 release 签名，因此它携带的是 Android **debug 密钥**：

- 只适合测试与侧载，不要用于应用商店或正式分发；
- debug 密钥不是稳定的发布密钥：不同机器或不同 CI 运行产出的 APK 可能不是同一把密钥。若 Android 在覆盖安装时报签名不一致，请先卸载旧版本。

### Windows MSI 与 alpha 版本

`jpackage` 生成 MSI 的 `ProductVersion` 时会去掉 `-alpha` 后缀。数字部分相同的两个 alpha（例如 `0.0.2-alpha.1` 与 `0.0.2-alpha.2`）在 Windows Installer 看来是同一个版本：请先卸载旧版本，或改用免安装 zip。从 `0.0.1` 升到 `0.0.2` 属于正常版本变化。

## 连接 core

客户端默认处于**假数据模式**：只显示本地示例数据，不发出任何网络请求，适合离线预览界面。要使用真实会话、模型、工具与审批，需要先启动 core，再把客户端指向它。

### 1. 启动 core

core 不在本仓库，请在 [lshub2010/Vade](https://github.com/lshub2010/Vade) 根目录运行：

```bash
# Linux / macOS —— PYTHONPATH 里两处都必须有
export PYTHONPATH="core:protocol/core/python"
.venv/bin/python -m vade --config core.toml
```

```powershell
# Windows —— 分隔符是分号
$env:PYTHONPATH = "core;protocol/core/python"
.venv\Scripts\python.exe -m vade --config core.toml
```

`PYTHONPATH` 必须同时包含 `core`（`vade` 包本身）与 `protocol/core/python`（原生 `vade_protocol` 绑定）。少了前者会报 `No module named vade`，少了后者会报 `protocol core python binding is not importable`。

core 启动后会打印一行配对码，形如：

```text
pair_code=茶源-45JV2U purpose=browser expires_in=600s
```

配对码是**一次性**的，有效期 **600 秒（10 分钟）**。过期后重启 core 取新的。

### 2. 让客户端连上 core

三端使用同一组键：**`source`**、**`host`**、**`port`**、**`pair_code`**，区别只在传参方式。

| 键 | 是否必填 | 含义 |
|---|---|---|
| `source` | 否 | 只有精确值 `real` 才切到真实 core；缺省或其它值都保持假数据模式。 |
| `host` | 否 | core 地址，默认 `127.0.0.1`。 |
| `port` | 否 | core 端口，默认 `8443`；非空但格式非法时直接报错，不会静默回落到默认值。 |
| `pair_code` | 实际必填 | 一次性配对码。不传时 WebSocket 升级会被当作设备 peer，客户端无法 attach。 |

**Android** —— 用 ADB 传 intent extras。键名是**裸键**（`source`，不是 `vade.source`）：

```bash
adb shell am start -n io.github.lshub2010.vade/vade.ui.MainActivity \
  --es source real --es host 192.168.1.10 --es port 8443 --es pair_code ABCDEFGH
```

**桌面** —— 环境变量（示例为免安装目录内的可执行文件；Windows 下是 `vade-ui.exe`）：

```bash
VADE_SOURCE=real VADE_HOST=192.168.1.10 VADE_PORT=8443 VADE_PAIR_CODE=ABCDEFGH ./bin/vade-ui
```

```powershell
$env:VADE_SOURCE="real"; $env:VADE_HOST="192.168.1.10"; $env:VADE_PORT="8443"; $env:VADE_PAIR_CODE="ABCDEFGH"; .\vade-ui.exe
```

**Web** —— URL 查询参数：

```text
http://localhost:8080/?source=real&host=192.168.1.10&port=8443&pair_code=ABCDEFGH
```

> Web 端会解析 `pair_code`，并在配对请求里带 `x-vade-session-token: 1`；页面 Origin 通过 core 闸门时，core 会把同一个会话 token 放进 JSON 响应体。页面必须与 core 同源（或 Origin 在白名单）；拿不到 body token 时仍会尝试浏览器托管的 cookie，失败时提示“浏览器没有保存会话”，而不是“码错”。

也可以什么参数都不传：首次启动时会话列表顶部会出现一张配对卡，可在卡里填写 host、port 和一次性配对码；配对成功后客户端会按这个 core 重建真实数据源。桌面、Android 与同源 Web 页面都可以发起兑换。

### 3. 开始使用

1. 点右上角 **✎**（手机）或按 **⌘N / Ctrl+N**（桌面）新建会话。会话是在 core 上真实创建的，不是本地草稿。
2. 在底部悬浮输入框输入，点右侧圆形按钮发送。
3. 想换模型：点输入框右下角的**模型 chip**。
4. 模型要执行高风险动作时会弹出**内联审批卡**：✓ 只允许这一次调用，✕ 拒绝。超时无人处理即拒绝（fail-closed，安全默认）。

## 功能概览

- **会话** —— 新建、打开、重命名、置顶、归档、分组、删除；操作失败会整行回滚并显示错误，不会出现「界面改了、core 没改」。
- **聊天** —— 回复逐字流式上屏；生成中点 ■ 中断，已生成的部分保留。
- **审批** —— 内联卡片显示工具名与参数摘要，决定只对本次调用生效。浏览器会话按设计不能审批，请在设备端操作。
- **模型与用量** —— 会话内可单独指定模型，顶栏显示本会话累计 token 用量。
- **附件** —— 输入框左侧 **+** 添加图片或文件；纯图片消息也可以发送。
- **搜索** —— 至少输入 3 个字符触发，防抖 200 ms，命中片段高亮。
- **分组与外观** —— 分组支持图标与颜色，删组不删会话；`material` / `miuix` 两套皮肤，主题支持跟随系统 / 亮 / 暗。

## 从源码构建

要求：

- **JDK 21**
- **Gradle 9.6.1**，需在 `PATH` 上 —— 本工程不生成 wrapper
- **Android SDK**，需有 `platforms;android-37.0` 与 `build-tools;37.0.0`（仅构建 APK 时需要）
- 在 `local.properties` 里用正斜杠写 `sdk.dir`（该文件不入库）

```properties
sdk.dir=D:/Android/Sdk
```

在仓库根目录执行：

```bash
gradle :composeApp:run                          # 桌面：直接从源码运行
gradle :composeApp:createDistributable          # 桌面：自带运行时的免安装目录
gradle :composeApp:packageMsi                   # 桌面：MSI 安装器
gradle :composeApp:assembleDebug                # Android APK
gradle :composeApp:compileKotlinWasmJs          # Web：编译检查
gradle :composeApp:wasmJsBrowserDistribution    # Web：静态站点
gradle :composeApp:desktopTest --rerun          # 桌面全量测试
```

合并前必须让三道门禁全绿：

```bash
gradle :composeApp:desktopTest --rerun
gradle :composeApp:assembleDebug
gradle :composeApp:compileKotlinWasmJs
```

测试数以 `composeApp/build/test-results/desktopTest/TEST-*.xml` 汇总为准，只看一句 `BUILD SUCCESSFUL` 不算数。Gradle daemon 会缓存环境变量，改了 `JAVA_HOME` 或 `ANDROID_HOME` 后先执行 `gradle --stop`。构建矩阵与防回退约束见 [`docs/DEVELOPING.md`](docs/DEVELOPING.md)，每个版本为什么钉死见 [`docs/ENVIRONMENT.md`](docs/ENVIRONMENT.md)。

## 数据与隐私

- 配对码**只走内存**，不落盘、不进日志，并有专门的守卫测试盯着。例外是 Web 启动方式会把它放在 URL 查询参数里传输，在共用机器上请避免这种形式。
- 鉴权 token 由 core 下发，客户端不打印、不上报。
- 假数据模式**不发出任何网络请求**。

## 已知限制

1. **Web 首次配对要求同源页面。** 浏览器读不到 `Set-Cookie`，Web 构建会向 core 请求 body token；core 只在页面 Origin 与 `/ws` 通过同一闸门时返回。把 Web 静态站点与 core 放在同一 origin（或把页面 Origin 加进白名单）。构建出的站点尚未在真实浏览器验证。
2. **会话中段没有连接状态指示。** 断线会自动重连（指数退避，上限 30 秒），但连接状态只在会话列表首次加载时显示。
3. **同一会话在一条连接上可能 attach 两次。** 这是既有基线问题。正常使用基本无感，但长连接下事件可能被处理两次；重启客户端即可回收。

**验证缺口。** macOS 与 Linux 桌面端未验证；Android 只在 API 30 真机与 API 37 模拟器上测试过，未覆盖更多机型。真实 core 的流式聊天链路（`ui/submit` / `turn.delta`）尚未在本仓库重新做端到端验证；协议层的 attach 与配对已有测试覆盖。

## 文档

| 想看什么 | 去哪 |
|---|---|
| 整个项目的设计、协议、决策台账 | [lshub2010/Vade](https://github.com/lshub2010/Vade) 的 `docs/` |
| 分步搭建与常见错误 | [`docs/QUICKSTART.md`](docs/QUICKSTART.md) |
| 构建矩阵 / 门禁 / 防回退约束 | [`docs/DEVELOPING.md`](docs/DEVELOPING.md) |
| 构建环境与版本矩阵 | [`docs/ENVIRONMENT.md`](docs/ENVIRONMENT.md) |
| 视觉规范（圆角 / 阴影 / 模糊 / token） | [`docs/visual-language.md`](docs/visual-language.md) |
| 协议契约原文（rpc / events / schema） | [`contracts/vade`](contracts/vade)（git submodule） |
| alpha 台账与已知缺陷 | [主仓 `docs/ALPHA.md`](https://github.com/lshub2010/Vade/blob/main/docs/ALPHA.md) |

## 许可

**Apache License 2.0** —— 完整条款见 [`LICENSE`](LICENSE)，署名与第三方声明见 [`NOTICE`](NOTICE)。

二次修改与再分发时，请保留 `LICENSE` 与 `NOTICE`，在改动过的文件里显著声明「你改过」，不要暗示原作者为你背书，也不要使用项目商标。一句话：可以改、可以商用、可以不开源，但不能抹掉原作者，也不能把改过的版本说成原版。

## 引用

如果你在论文、文章或产品里用到 Vade，请按 [`CITATION.cff`](CITATION.cff) 中的元数据引用：

```bibtex
@software{vade2026,
  title     = {Vade: a thin-core, module-first agent runtime},
  author    = {{Vade contributors}},
  year      = {2026},
  url       = {https://github.com/lshub2010/vade-client},
  note      = {Client for Android / Desktop / Web (Kotlin Multiplatform)}
}
```

## 第三方组件与许可

本项目站在这些开源组件上。它们各自的许可与本项目许可相互独立，但再分发二进制时必须保留它们的声明。版本取自本仓库的构建文件，许可标识以上游项目公布的元数据为准。

| 组件 | 版本 | 许可 | 用途 / 链接 |
|---|---|---|---|
| Compose Multiplatform | 1.12.0 | Apache-2.0 | 三端 UI 运行时 · [JetBrains](https://github.com/JetBrains/compose-multiplatform) |
| Kotlin | 2.4.20 | Apache-2.0 | 语言运行时 |
| Ktor Client（core / websockets / content-negotiation / cio / okhttp / js） | 3.6.1 | Apache-2.0 | 传输层与 WebSocket · [Ktor](https://ktor.io) |
| AndroidX Activity Compose | 1.9.3 | Apache-2.0 | Android 入口 |
| **Miuix**（`miuix-ui`） | 0.9.4 | **Apache-2.0** | 主题、回弹与组件集 · [miuix](https://github.com/miuix-kotlin-multiplatform/miuix) |
| **haze**（haze / haze-blur） | 2.1.0 | **Apache-2.0** | 真高斯背景模糊 · [haze](https://github.com/chrisbanes/haze) |
| **Tabler Icons**（icons-tabler-cmp / outline / filled） | 2.2.1 | **MIT** | 矢量图标，经 [composeicons](https://github.com/composablehorizons/composeicons) 打包；图标集 Copyright (c) 2018-2026 Tabler contributors |
| **KernelSU**（浮动胶囊交互移植） | — | **Apache-2.0** | 阻尼拖拽指示器、按压缩放、速度拉伸、橡皮筋、内层阴影 · Copyright (c) 2023-2026 weishu & contributors · [KernelSU](https://github.com/tiann/KernelSU)（原实现改编自 compose-miuix-ui 的 `IosLiquidGlassNavigationBar`） |

以上组件均为 Apache-2.0 或 MIT，与本项目的 Apache-2.0 分发不冲突。它们的版权与许可声明随源码和二进制一同保留，详见 [`NOTICE`](NOTICE)。
