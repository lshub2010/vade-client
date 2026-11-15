# 开发者指南

本文件收拢「怎么构建、怎么改、有哪些防回退约束」这类**开发侧**内容。README 只讲**怎么用**。

---

## 仓库结构

```
.
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties            # 构建矩阵 + vadeVersion（单一版本源）
├── composeApp/src/
│   ├── commonMain/kotlin/vade/ui/
│   │   ├── skin/                # C-09：11 个组件接口（冻结后只许加可选参数）
│   │   ├── skin/material/       # material 皮肤
│   │   ├── skin/miuix/          # miuix 皮肤
│   │   ├── shell/               # 顶栏 / 底栏 / 断点布局 / 快捷键 / 返回栈 / 抽屉
│   │   ├── destinations/        # 会话 / 活动 / 能力 / 我的
│   │   ├── chat/                # 消息流、输入、附件、工具卡、审批卡
│   │   ├── search/              # 搜索覆盖层与本地索引
│   │   ├── data/                # SessionSource / 适配器 / 真源接线
│   │   ├── protocol/            # 契约投影（VadeClient、方法枚举、事件模型）
│   │   └── component/liquid/    # 玻璃、模糊、阴影等视觉原语
│   ├── desktopMain/             # 桌面入口 Main.kt（--bar / --dest / --panel / --search）
│   ├── androidMain/             # Activity 入口
│   └── wasmJsMain/              # ComposeViewport 入口
├── contracts/vade               # git submodule → 主仓（schema / 契约的唯一来源）
├── docs/                        # QUICKSTART / ENVIRONMENT / DEVELOPING / visual-language
├── shots/                       # 真机·真窗口截图与测量证据（按批次分目录）
└── tools/                       # 截图、契约同步、gitleaks 等脚本
```

## 契约（submodule）

```bash
git clone --recurse-submodules https://github.com/lshub2010/vade-client.git
# 已 clone 过的：
git submodule update --init --recursive
```

只在本地需要 `schema/` 时：

```bash
bash tools/setup-contracts.sh      # 对 contracts/vade 做 sparse-checkout，只取 schema/ + protocol 绑定
```

> 升契约 = 一次独立提交（bump submodule 指针 + 重跑 `tools/setup-contracts.sh`），并在提交信息里写明升到哪个版本、为什么。

## 构建矩阵（已验证值，别改）

| 项 | 值 |
|---|---|
| AGP | **9.5.1** |
| Gradle | **9.6.1**（系统 `gradle`，本工程不生成 wrapper） |
| compileSdk / targetSdk / minSdk | **37 / 36 / 24** |
| AGP 兼容开关 | `android.builtInKotlin=false`、`android.newDsl=false`、`android.suppressUnsupportedCompileSdk=37` |
| Compose Multiplatform | 1.12.0 |
| 桌面 JVM 目标 | **17**（daemon 是更高版本 JDK，不钉会编出高版本字节码，截图进程加载不了） |
| JDK | 21 |

## 常用命令

```bash
source D:/Hermes/tools/env/vade-env.sh     # JAVA_HOME / ANDROID_HOME / GRADLE_USER_HOME
gradle :composeApp:desktopMainClasses      # 桌面编译
gradle :composeApp:run                     # 起桌面窗口
gradle :composeApp:assembleDebug           # Android APK
gradle :composeApp:compileKotlinWasmJs     # Web 编译验证
gradle :composeApp:desktopTest --rerun     # 全量测试（~1500 条）
gradle :composeApp:packageMsi              # 桌面安装器
```

> **同一工程目录同一时刻只许跑一个 Gradle**。两个进程同时写增量缓存会打坏 `constants.tab`，
> 现象是 `Storage corrupted` / `Could not close incremental caches` 而日志里**没有一条 `e:`**——
> 那是缓存坏了不是代码错。处置：`rm -rf composeApp/build/kotlin` 后串行重跑。

## 门禁（合并前必须三目标全绿）

```bash
gradle :composeApp:desktopTest --rerun
gradle :composeApp:assembleDebug
gradle :composeApp:compileKotlinWasmJs
```

测试数以 `composeApp/build/test-results/desktopTest/TEST-*.xml` 里 `tests=` 求和为准，
**不要**只看一句 `BUILD SUCCESSFUL`。

## 防回退测试（真跑的断言）

1. 业务页面**不得** import `material3` / `miuix` / `glass`（只能经 `Skin`）；扫描目录含
   `destinations`、`shell`、`search`、`chat`。
2. `Skin.kt` **恰好 11 个** `@Composable fun` —— 少一个说明组件被删，多一个说明有人绕过 C-09 流程加了第 12 个。
3. 玻璃层级：**模糊层必须是背景源的兄弟，不能是后代**（haze 2.1.0 对「effect 画在自己 source 子树里」不模糊）。
   见 `GlassStructureGuardTest`。
4. 契约覆盖：`VadeMethod` 枚举与 `contracts/vade/schema/rpc.json` 的方法集合逐名对齐（36 个）。

## 真实窗口截图（Windows）

```bash
gradle :composeApp:desktopMainClasses :composeApp:printRuntimeClasspath -q > cp.txt
for b in fixed floating blur; do
  pwsh -NoProfile -File tools/capture-window.ps1 \
    -Java "$JAVA_HOME/bin/java.exe" -ClassPathFile cp.txt \
    -Bar $b -OutFile shots/u1a/desktop-$b.png
done
```

> Android 截图用 `adb -s <serial> exec-out screencap -p > shot.png`。**并行跑多个任务时不要用窗口截图脚本**。

## 提交约定

- 提交信息：`<type>(<scope>): <中文描述>`，type ∈ `feat|fix|perf|refactor|test|docs|chore|balance`。
- 一次改动一个主题；测试与实现同提交（TDD 时先红后绿再合）。
- 不删测试、不加 `@Ignore`、不弱化断言、不靠调大超时掩盖竞态。
- 契约变更走主仓：先改 `schema/` 并登记，再升本仓 submodule。

## 进一步阅读

| 想看什么 | 去哪 |
|---|---|
| 从零跑起来（分步 + 常见错误） | [QUICKSTART.md](QUICKSTART.md) |
| 构建矩阵为什么钉死、动了会怎样 | [ENVIRONMENT.md](ENVIRONMENT.md) |
| 视觉规范（圆角 / 阴影 / 模糊 / token） | [visual-language.md](visual-language.md) |
| alpha 台账与已知缺陷 | 主仓 `docs/ALPHA.md` |
