# ui —— Vade 客户端（KMP，三端同源）

> 当前范围：**U1-a**（骨架）。四目的地 + 三态底栏 + 四档断点 + C-09 `Skin` 接口（11 组件）+ `material` 最小实现。
> 消息流 / 输入区 / 附件 / 工具卡的业务页面是 **U1-b**；`miuix` / `glass` 皮肤是 **U2 / U3**。

## 目录

```
ui/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties            # 构建矩阵（见下）
├── composeApp/
│   └── src/
│       ├── commonMain/kotlin/vade/ui/
│       │   ├── skin/Skin.kt           # C-09：11 个组件的接口（U1 独占，冻结后只许加可选参数）
│       │   ├── skin/material/         # material 皮肤的最小实现
│       │   ├── skin/SkinRegistry.kt   # material 真实现；miuix/glass 占位（[未安装]）
│       │   ├── shell/                 # 底栏 / 顶栏 / 断点布局
│       │   ├── destinations/          # 会话 / 活动 / 能力 / 我的
│       │   └── model/                 # 假数据与 UI 侧视图模型
│       ├── desktopMain/kotlin/vade/ui/Main.kt   # 桌面入口（--bar / --dest / --panel）
│       ├── androidMain/                          # Activity 入口
│       └── wasmJsMain/                           # ComposeViewport 入口
├── shots/u1a/                     # 三态底栏的真实窗口截图（466×893）
└── tools/capture-window.ps1       # 真实窗口截图脚本（PrintWindow + PW_RENDERFULLCONTENT）
```

## 构建矩阵（照 `spike/miuix` 已验证值，别改）

| 项 | 值 |
|---|---|
| AGP | **9.5.1** |
| Gradle | **9.6.1**（系统 `gradle`，本工程不生成 wrapper） |
| compileSdk / targetSdk / minSdk | **37 / 36 / 24** |
| AGP 兼容开关 | `android.builtInKotlin=false`、`android.newDsl=false`、`android.suppressUnsupportedCompileSdk=37` |
| Compose Multiplatform | 1.12.0（与 spike 一致） |
| 桌面 JVM 目标 | **17**（daemon 是 JDK 26，不钉会编出 26 的字节码，截图进程加载不了） |

> 两个 AGP 开关已 deprecated，AGP 10 会移除；届时的正解是拆独立 `androidApp` 模块 + `com.android.kotlin.multiplatform.library`，不在 U1 范围。

## 常用命令

```bash
source D:/Hermes/tools/env/vade-env.sh     # JAVA_HOME / ANDROID_HOME / GRADLE_USER_HOME

cd ui
gradle :composeApp:desktopMainClasses      # 桌面编译
gradle :composeApp:assembleDebug           # Android APK
gradle :composeApp:wasmJsBrowserProductionWebpack   # Wasm 生产包
gradle :composeApp:desktopTest             # 两条防回退测试
```

真实窗口截图（Windows）：

```bash
cd ui
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
| 底栏三态 | 已实现；`blur` 是**垂直 alpha 渐变遮罩**，**不是真模糊**（`haze` 未接） |
| 四档断点 | 已实现：<600 底栏；600–839 rail 80；840–1199 rail 72 + 双栏；≥1200 双栏 + 右面板（默认收起） |
| C-09 Skin 11 组件 | 接口 + material 实现；`miuix`/`glass` 仅占位 |
| 会话列表 | 6 条假数据（2 置顶 + 今天 2 + 昨天 1 + 7 天内 1），**未连 core** |
| 搜索（手机全屏覆盖层 / 桌面 ⌘K 命令面板） | 已实现（U1-c）；**降级为本地缓存索引**并常驻「范围：已缓存」——`schema/rpc.json` 里没有 `session/search` |
| 消息流 / 输入区 / 附件 / 工具卡页面 | **未实现**（U1-b） |
| 快捷键（⌘N/⌘1–4/⌘B/⌘\\/Esc） | **未实现**（U1-g）；`⌘K` / `Ctrl+K` 已随 U1-c 落地 |
| Android / Wasm 运行时与截图 | **未验证**（只验了构建） |
| 无障碍（字体缩放 / TalkBack） | **未验证**（U1-g） |

## 前置：原生协议库

`core` 的测试需要 `protocol/core/python/vade_protocol.dll`（约 32 MB，**不入库**，见 `.gitignore`）。缺失时 `pytest core/tests` 会有 22 条失败——那是环境问题，不是 UI 代码问题。重建方法见 `docs/dev-windows.md` 的 `build-protocol-dll.sh`。

## 两条防回退测试

`desktopTest` 里有两个真跑的断言：

1. 业务页面**不得** import `material3` / `miuix` / `glass`（只能经 `Skin`）。
2. `Skin.kt` **恰好 11 个** `@Composable fun` —— 少一个说明组件被删，多一个说明有人绕过 C-09 流程加了第 12 个。
