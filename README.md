# vade-client —— Vade 客户端（Compose Multiplatform，三端同源）

Vade Agent Runtime（中文「偕行」）的客户端仓。**按模块管理**：独立仓、独立构建、独立发版；契约（`schema/`、错误码、Kotlin 绑定）通过 git submodule `contracts/vade` 从主仓取得。

| 项 | 值 |
|---|---|
| 主仓 | https://github.com/lshub2010/Vade（`dev_gingshan` 集成，`debug` 基线，只收 PR） |
| 本仓 | https://github.com/lshub2010/vade-client（`main` 基线，只收 PR） |
| 契约 | `contracts/vade` → 主仓，**钉 commit**；升契约要单独一次提交并说明 |
| 迁出时间 | 2026-11-14（原主仓 `ui/`，保留全部历史） |

> 当前范围：**U1-a 骨架 + U1-c 搜索**已落地。消息流 / 输入 / 附件 / 工具卡是 **U1-b**；`miuix` / `glass` 是 **U2 / U3**；任务书在主仓 `docs/agents/50-U1-*`～`53-U4-*`（其中 `ui/...` 路径即本仓路径）。

## 目录

```
.
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties            # 构建矩阵（见下）
├── composeApp/src/
│   ├── commonMain/kotlin/vade/ui/
│   │   ├── skin/Skin.kt           # C-09：11 个组件的接口（冻结后只许加可选参数）
│   │   ├── skin/material/         # material 皮肤的最小实现
│   │   ├── skin/SkinRegistry.kt   # material 真实现；miuix/glass 占位
│   │   ├── shell/                 # 底栏 / 顶栏 / 断点布局 / 快捷键
│   │   ├── destinations/          # 会话 / 活动 / 能力 / 我的
│   │   ├── search/                # U1-c：本地索引、派生状态、两形态覆盖层
│   │   └── model/                 # 假数据与 UI 侧视图模型
│   ├── desktopMain/kotlin/vade/ui/Main.kt   # 桌面入口（--bar / --dest / --panel / --search）
│   ├── androidMain/                          # Activity 入口
│   └── wasmJsMain/                           # ComposeViewport 入口
├── shots/                         # 真实窗口截图（u1a 骨架 / u1c 搜索）
├── tools/capture-window.ps1       # 真实窗口截图（PrintWindow + PW_RENDERFULLCONTENT）
└── contracts/vade                 # git submodule → 主仓（schema / 绑定的唯一来源）
```

## 契约（submodule）

```bash
git clone --recurse-submodules https://github.com/lshub2010/vade-client.git
# 已 clone 过的：
git submodule update --init --recursive
```

只在本地需要 `schema/` 时（客户端构建当前不依赖它，为 U1-b 的协议接入预留）：

```bash
bash tools/setup-contracts.sh      # 对 contracts/vade 做 sparse-checkout，只取 schema/ + protocol/core/kotlin
```

## 构建矩阵（照主仓 `spike/miuix` 已验证值，别改）

| 项 | 值 |
|---|---|
| AGP | **9.5.1** |
| Gradle | **9.6.1**（系统 `gradle`，本工程不生成 wrapper） |
| compileSdk / targetSdk / minSdk | **37 / 36 / 24** |
| AGP 兼容开关 | `android.builtInKotlin=false`、`android.newDsl=false`、`android.suppressUnsupportedCompileSdk=37` |
| Compose Multiplatform | 1.12.0 |
| 桌面 JVM 目标 | **17**（daemon 是 JDK 26，不钉会编出 26 的字节码，截图进程加载不了） |

> 两个 AGP 开关已 deprecated，AGP 10 会移除；届时的正解是拆独立 `androidApp` 模块 + `com.android.kotlin.multiplatform.library`。

## 常用命令

```bash
source D:/Hermes/tools/env/vade-env.sh     # JAVA_HOME / ANDROID_HOME / GRADLE_USER_HOME
gradle :composeApp:desktopMainClasses      # 桌面编译
gradle :composeApp:assembleDebug           # Android APK
gradle :composeApp:wasmJsBrowserProductionWebpack   # Wasm 生产包
gradle :composeApp:desktopTest             # 防回退测试 + 搜索流程测试
```

真实窗口截图（Windows）：

```bash
gradle :composeApp:desktopMainClasses :composeApp:printRuntimeClasspath -q > cp.txt
for b in fixed floating blur; do
  pwsh -NoProfile -File tools/capture-window.ps1 \
    -Java "$JAVA_HOME/bin/java.exe" -ClassPathFile cp.txt \
    -Bar $b -OutFile shots/u1a/desktop-$b.png
done
```

## 已实现 / 未实现（别当已完成）

| 项 | 状态 |
|---|---|
| 四目的地（会话 / 活动 / 能力 / 我的） | 已实现（活动/能力/我的为骨架与空态） |
| 底栏三态 | 已实现（w2-bottombar）：三种形态由 shell 层渲染。`Floating`/`FloatingBlur` 为 KernelSU 风格液态玻璃浮动胶囊（阻尼拖拽指示器、按压缩放、速度拉伸、橡皮筋、内层阴影）；`Fixed` 与 `FloatingBlur` 走 **miuix-blur 真模糊**（`drawBackdrop` + blur/lens/BloomStroke 高光）。旧的"垂直 alpha 渐变遮罩"假模糊已从应用路径下线（skin 内实现保留但不再被 shell 调用）。Android < 13 无 RuntimeShader，按库内门控自动退化为纯染色（见 `shots/w2-bottombar/RESULT.md`） |
| 四档断点 | 已实现：<600 底栏；600–839 rail 80；840–1199 rail 72 + 双栏；≥1200 双栏 + 右面板（默认收起） |
| C-09 Skin 11 组件 | 接口 + material 实现；`miuix`/`glass` 仅占位 |
| 会话列表 | 6 条假数据（2 置顶 + 今天 2 + 昨天 1 + 7 天内 1），**未连 core** |
| 搜索（手机全屏覆盖层 / 桌面 ⌘K） | 已实现（U1-c）；**降级为本地缓存索引**并常驻「范围：已缓存」——`schema/rpc.json` 里没有 `session/search` |
| 消息流 / 输入区 / 附件 / 工具卡页面 | 已实现（U1-b，**全假数据，未连 core**）：流式、队列、中断、工具卡折叠策略、Markdown、图片预览、多行输入。视觉 v2（悬浮输入/发送球/纯图标/内联审批/高斯模糊）未落地，待统一重构。见 `shots/u1b/RESULT.md` |
| 快捷键（⌘N/⌘1–4/⌘B/⌘\\/Esc） | **未实现**（U1-g）；`⌘K` / `Ctrl+K` 已随 U1-c 落地 |
| Android / Wasm 运行时与截图 | **未验证**（只验了构建） |
| 无障碍（字体缩放 / TalkBack） | **未验证**（U1-g） |

## 防回退测试

`desktopTest` 里有真跑的断言：

1. 业务页面**不得** import `material3` / `miuix` / `glass`（只能经 `Skin`）；扫描目录含 `destinations`、`shell`、`search`、`chat`。
2. `Skin.kt` **恰好 11 个** `@Composable fun` —— 少一个说明组件被删，多一个说明有人绕过 C-09 流程加了第 12 个。

## 第三方许可与致谢

### KernelSU
底栏的浮动胶囊交互（阻尼拖拽指示器、按压缩放、速度拉伸、橡皮筋、内层阴影）移植自
[KernelSU](https://github.com/tiann/KernelSU)，Copyright (c) 2023-2026 weishu &
contributors，以 **Apache License 2.0** 授权。原实现改编自 compose-miuix-ui 示例
`IosLiquidGlassNavigationBar`（Apache 2.0）。本项目的移植版本保持同一许可
（`ui/component/liquid/` 与 `ui/shell/FloatingBottomBar.kt` 文件头均带来源声明）。

### Miuix
毛玻璃、回弹与主题基础组件来自 [Miuix](https://github.com/miuix-kotlin-multiplatform/miuix)
（`top.yukonga.miuix.kmp`，含 `miuix-ui` 与 `miuix-blur`），**Apache License 2.0**。

### Tabler Icons
矢量图标来自 [Tabler Icons](https://tabler.io/icons)，经
`com.composables:icons-tabler-cmp` 2.2.1（打包：[Composable Horizons
composeicons](https://github.com/composablehorizons/composeicons)）接入，统一重导出于
`vade.ui.icon.VadeIcons`。

Tabler Icons 以 **MIT License** 发布，版权行：**Copyright (c) 2018-2026 Tabler
contributors**（[许可原文](https://github.com/tabler/tabler-icons/blob/main/LICENSE)）。
`shell/`、`skin/` 之外的业务代码不得直接 import `com.composables.icons.tabler.*`。

### HarmonyOS Sans（待定）
若后续采用，需在此声明：本软件使用 HarmonyOS Sans 字体（© Huawei Device Co., Ltd.），
按 HarmonyOS Sans Fonts License Agreement 使用。

