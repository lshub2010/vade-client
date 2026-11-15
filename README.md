# Vade

![Release build](https://github.com/lshub2010/vade-client/actions/workflows/release.yml/badge.svg)

**Vade**（中文名「偕行」）是一个「内核极薄、能力全靠模块」的 Agent 运行时。

本仓库是它的**客户端**：Android / 桌面 / Web 三端共用同一套 Compose UI（Kotlin Multiplatform）。协议内核、契约与设计文档在 **[lshub2010/Vade](https://github.com/lshub2010/Vade)**。

> **当前发布：`0.0.1-alpha`（pre-release）**
> alpha 的含义：能连真 core、能发消息、能审批、能管会话——**不保证**协议 / 数据 / 界面稳定，前后版本的数据结构随时可能变。

---

## 三端状态

| 平台 | 状态 | 怎么装 |
|---|---|---|
| Android | ✅ 可用（minSdk 24，实测 API 30 / API 37） | 装 `composeApp-debug.apk` |
| 桌面 · Windows | ✅ 可用 | 装 MSI，或 `gradle :composeApp:run` |
| 桌面 · macOS / Linux | ⚠️ 未验证 | 同上，需自备 JDK 21 |
| Web · wasmJs | 🟡 能编译通过，**未出可分发的站点** | 仅源码构建 |

---

## 快速开始

### 1. 先跑一个 core

Vade 客户端自己**不产生任何智能**——它必须连一个 core 才有模型、工具和会话。

```bash
# 在内核仓库里（见 lshub2010/Vade）
python -m vade --config core.toml
```

core 起来后会给出一个 **8 位配对码**（2 个汉字 + 6 位 base32，**一次性、180 秒有效**）。找不到就重启 core 再看。

### 2. 让客户端连上这个 core

客户端**默认是假数据模式**（用来离线看界面）。要连真 core，三端各有传参方式，键名固定为 `source` / `host` / `port` / `pair_code`：

**Android**（ADB 启动，注意是**裸键**，不是 `vade.source`）：

```bash
adb shell am start -n vade.ui/.MainActivity \
  --es source real --es host 192.168.1.10 --es port 8443 --es pair_code ABCDEFGH
```

**桌面**（环境变量）：

```bash
VADE_SOURCE=real VADE_HOST=192.168.1.10 VADE_PORT=8443 VADE_PAIR_CODE=ABCDEFGH ./vade-ui
# Windows PowerShell: $env:VADE_SOURCE="real"; ... ; .\vade-ui.exe
```

**Web**（URL 查询参数）：

```
http://localhost:8080/?source=real&host=192.168.1.10&port=8443&pair_code=ABCDEFGH
```

> 也可以什么都不传：首次启动时**会话列表顶部会出现一张引导卡**，在卡里输入配对码即可。
> 三种失败（码错 / core 没起 / 网络不通）会**分开报**，不会含糊成一句「连接失败」。

### 3. 开始用

1. 点右上角 **✎**（手机）或按 **⌘N / Ctrl+N**（桌面）**新建会话**；
2. 底部悬浮输入框里输入，点右侧圆形按钮发送；
3. 想换模型：输入框右下角的**模型 chip** → 弹出选择面板；
4. 模型要执行危险动作时会弹一张**内联审批卡**（不弹独立窗口），点 ✓ 批准 / ✕ 拒绝。

---

## 日常使用

### 会话

- **新建**：✎ / `⌘N`——会在服务端**真建**一个会话（不是本地假草稿），其他设备会同步看到。
- **打开**：点列表任意一行进入会话详情。
- **回到列表**：手机端从左侧滑出抽屉，返回键 = 收起抽屉；桌面端是双栏，直接点左栏。
- **长按一行**：重命名 / 置顶 / 归档 / 移动到分组 / 新建分组 / 删除（删除有二次确认）。
- 任何失败都会**整行回滚**并在顶部显示错误横条——不会出现「界面改了、服务端没改」的假象。

### 发消息

- 输入框是**常驻悬浮**的，发送键是右侧独立圆钮。
- 回复**逐字流式上屏**（不是等完成再整段替换）。
- 生成中点 **■** 中断：已生成的部分会保留。
- 发送失败的消息气泡留在屏上并带错误分类（可重试 / 需重连 / 需你操作 / 致命）。

### 审批（approval）

模型要执行高风险动作时，会话流里会出现一张卡：

- 显示工具名与参数**摘要**（完整参数不上屏，避免泄漏）；
- ✓ 允许 / ✕ 拒绝，**只对这次调用生效**；
- 决定会发往 core；**超时未点 = 拒绝**（fail-closed，安全默认）；
- 若你用浏览器会话连入，审批会回「浏览器会话不能审批」——这是设计行为，请在设备端操作。

### 模型与用量

- 会话内可单独指定模型；不指定则用全局默认。
- 顶栏副标题显示**本会话累计用量**（`6.6k tokens · 5 轮`），数据来自 `session/usage.get`。
  加载中或失败**不显示任何数字**；失败时那一行可点重试。

### 附件

- 输入框左侧 **+** 添加图片 / 文件；纯图片消息（无文字）也可以发。
- 预览浮层是**真高斯模糊的玻璃卡**，点空白或按 Esc 关闭。

### 搜索

- 手机：列表页顶部搜索图标；桌面：`⌘K / Ctrl+K`。
- 输入 3 个字符触发，防抖 200ms，命中片段高亮。

### 分组

- 会话行显示**组色点 + 组名**。
- 分组可建 / 改图标（39 个内置）/ 改颜色 / 删除；**删组不删会话**，会话回到未分组。

### 外观

- 两套皮肤：`material`（默认）与 `miuix`。
- 主题三态：跟随系统 / 亮 / 暗（「我的 → 外观」）。
- 底栏两态：贴底（docked）与悬浮胶囊（floating）。

---

## 从源码构建

**要求**：JDK **21**、Gradle **9.6.1**、Android SDK（仅构建 APK 时需要）、Node 22（仅 Web）。

```bash
gradle :composeApp:run                     # 桌面：直接运行
gradle :composeApp:packageMsi              # 桌面：打包安装器
gradle :composeApp:assembleDebug           # Android APK
#   → composeApp/build/outputs/apk/debug/composeApp-debug.apk
gradle :composeApp:compileKotlinWasmJs     # Web：只验证编译
gradle :composeApp:desktopTest --rerun     # 全量测试（~1500 条）
```

构建前把 `local.properties` 里的 `sdk.dir` 指到你自己的 Android SDK（该文件不进仓库）。
构建矩阵、门禁、防回退约束、截图工具见 **[`docs/DEVELOPING.md`](docs/DEVELOPING.md)**。

---

## 数据与隐私

- 配对码**只走内存**：不落盘、不进日志、不进 URL（有专门的守卫测试盯着）。
- 鉴权 token 由 core 下发，客户端不打印、不上报。
- 默认假数据模式**不发出任何网络请求**——想离线看界面就一直用默认模式。

---

## 已知限制

alpha 阶段的完整清单在 **[主仓 `docs/ALPHA.md` §八](https://github.com/lshub2010/Vade/blob/main/docs/ALPHA.md)**（每条都写了「用户会看到什么 / 怎么绕」）。最常被问到的三条：

1. **Web 端首次配对不可用**——浏览器读不到 `Set-Cookie`，请在桌面 / 手机完成首次配对。
2. **会话中段没有连接状态指示**——断线会自动重连（指数退避，上限 30 s），但界面只在列表首次加载时显示连接态。
3. **同一会话在一条连接上会 attach 两次**（既有基线问题）——正常使用无感，长连接下事件可能被处理两次，重启客户端即回收。

---

## 文档

| 想看什么 | 去哪 |
|---|---|
| 整个项目的设计、协议、决策台账 | [lshub2010/Vade](https://github.com/lshub2010/Vade) 的 `docs/` |
| 从零跑起来（分步命令 + 常见错误） | [`docs/QUICKSTART.md`](docs/QUICKSTART.md) |
| 构建矩阵 / 门禁 / 防回退约束 | [`docs/DEVELOPING.md`](docs/DEVELOPING.md) |
| 构建环境与版本矩阵 | [`docs/ENVIRONMENT.md`](docs/ENVIRONMENT.md) |
| 视觉规范（圆角 / 阴影 / 模糊 / token） | [`docs/visual-language.md`](docs/visual-language.md) |
| 协议契约原文（rpc / events / schema） | [`contracts/vade`](contracts/vade)（submodule） |
| alpha 台账与已知缺陷 | [主仓 `docs/ALPHA.md`](https://github.com/lshub2010/Vade/blob/main/docs/ALPHA.md) |

---

## 许可

**Apache License 2.0** —— 完整条款见 [`LICENSE`](LICENSE)，署名与第三方声明见 [`NOTICE`](NOTICE)。

```
Copyright (c) 2026 Vade contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

### 二次修改与再分发（必须标明原作者）

Apache-2.0 是宽松许可（可商用、可闭源衍生），但**署名不能省**：

- 再分发时必须保留 [`LICENSE`](LICENSE) 与 [`NOTICE`](NOTICE)（§4(c)(d)）；
- **凡改动过的文件，必须在文件内显著声明「你改过」**（§4(b)）；
- 不得暗示原作者为你背书（§4(g)），不得使用项目商标（§6）。

一句话：**可以改、可以商用、可以不开源，但不能抹掉原作者、不能把改过的版本说成原版。**

## 引用

如果你在文章、论文或产品里用到 Vade，请按下面格式引用：

```bibtex
@software{vade2026,
  title     = {Vade: a thin-core, module-first agent runtime},
  author    = {{Vade contributors}},
  year      = {2026},
  version   = {0.0.1-alpha},
  url       = {https://github.com/lshub2010/vade-client},
  note      = {Client for Android / Desktop / Web (Kotlin Multiplatform)}
}
```

机器可读的引用元数据见 [`CITATION.cff`](CITATION.cff)。

---

## 第三方组件与许可

本项目站在这些开源组件上。**它们各自的许可与你使用本项目无关，但再分发二进制时必须保留它们的声明**。
下表由构建脚本里的真实坐标 + 各组件 POM 里声明的许可证核实（2026-11-15），不是凭印象写的。

| 组件 | 版本 | 许可 | 用途 / 链接 |
|---|---|---|---|
| Compose Multiplatform | 1.12.0 | Apache-2.0 | 三端 UI 运行时 · [JetBrains](https://github.com/JetBrains/compose-multiplatform) |
| Kotlin / kotlinx（coroutines、serialization） | 2.x / 1.11 | Apache-2.0 | 语言与协程、序列化 |
| Ktor Client（core / websockets / content-negotiation / cio / okhttp / js） | 3.6.1 | Apache-2.0 | 传输层与 WebSocket · [Ktor](https://ktor.io) |
| Skiko | 0.150.1 | Apache-2.0 | Compose 的 Skia 绑定 |
| AndroidX Activity Compose | 1.13.0 | Apache-2.0 | Android 入口 |
| **Miuix**（miuix-ui / miuix-blur） | 0.9.4 | **Apache-2.0** | 毛玻璃、回弹、主题组件 · [miuix](https://github.com/miuix-kotlin-multiplatform/miuix) |
| **haze**（haze / haze-blur） | 2.1.0 | **Apache-2.0** | 真高斯背景模糊 · [haze](https://github.com/chrisbanes/haze) |
| **Tabler Icons**（icons-tabler-cmp / outline / filled） | 2.2.1 | **MIT** | 矢量图标，经 [composeicons](https://github.com/composablehorizons/composeicons) 打包 · 图标集本身 Copyright (c) 2018-2026 Tabler contributors |
| **KernelSU**（浮动胶囊交互移植） | — | **Apache-2.0** | 阻尼拖拽指示器、按压缩放、速度拉伸、橡皮筋、内层阴影 · Copyright (c) 2023-2026 weishu & contributors · [KernelSU](https://github.com/tiann/KernelSU)（原实现改编自 compose-miuix-ui 的 `IosLiquidGlassNavigationBar`） |

> **许可兼容性**：上述组件均为 Apache-2.0 / MIT，与本项目所选许可互不冲突。
> 本项目按 Apache-2.0 分发，这些组件的版权与许可声明随源码与二进制一同保留（见 `NOTICE`）。

### HarmonyOS Sans（待定）
若后续采用，需在此声明：本软件使用 HarmonyOS Sans 字体（© Huawei Device Co., Ltd.），
按 HarmonyOS Sans Fonts License Agreement 使用。
