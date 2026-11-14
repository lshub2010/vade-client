# 环境矩阵 —— 每个版本为什么钉死、动了会怎样

> 本文是 `README.md`「构建矩阵」的展开：每项给出**依据来源**（仓内注释 / 本机实测）和**改动后果**。
> 矩阵在主仓 spike/miuix 端到端验证过（`gradle.properties` 头注释）；本文标「实测」的是
> 2026-11-14 在 worktree `tmp/g-docs` 上重跑确认过的。

## 一、核心链条（先记这条，再往下看）

```
Miuix 0.9.x（0.9.4 最新稳定版）
  └─ 它的 4 个 Android AAR 声明 minCompileSdk=37
       └─ compileSdk 必须 = 37
            └─ CMP 1.12 拉进的 compose/material3 AAR 也要求 compileSdk 37，
               而 AGP < 9.1 解析不了 platforms;android-37.0
                └─ AGP 必须 ≥ 9.1（本仓钉 9.5.1）
                     └─ AGP 9.5.1 硬性要求 Gradle ≥ 9.6.0（本机实测 8.10.2 被拒）
                          └─ AGP 9.0+ 拒绝 com.android.application 与
                             org.jetbrains.kotlin.multiplatform 同模块共存，
                             必须 gradle.properties 里显式关掉两个开关：
                             android.builtInKotlin=false + android.newDsl=false
                             （只关一个照样挂：newDsl 默认 true，配置期即失败）
                              └─ 两个开关已 deprecated，AGP 10 移除
                                 → 届时正解：拆出独立 androidApp 模块
                                 （com.android.kotlin.multiplatform.library）
```

**结论：不能只动矩阵里的一行。** 想升级 Miuix / compileSdk / AGP / Gradle 中的任何一个，
先按上面链条推一遍连带项；根 build 脚本注释也写了「Do not bump one line of this block in
isolation」。

## 二、矩阵表

| 项 | 钉死值 | 为什么是这个值 | 换了会怎样 |
|---|---|---|---|
| Kotlin | **2.4.20** | Miuix 0.9.4 就是对着 Kotlin 2.4.20 + CMP 1.12.0 发布的（根 build 脚本注释，源自 spike/miuix `versions.lock.md`） | 升：Miuix 的 API 元数据按发布对的编译器生成，错配可能出现奇怪的解析/重载失败（未实测）；降：Miuix 0.9.4 声明的元数据版本更高，读不了 |
| Compose Multiplatform（插件） | **1.12.0** | 与 Kotlin 2.4.20 是配对验证过的组合 | 注意：classpath 上实际有 `compose-ui:1.12.2`（某传递依赖拉高），实测有版本不一致**警告**但桌面窗口渲染正常、测试全绿——不要为消警告乱动插件版本 |
| AGP | **9.5.1** | ① CMP 1.12 的 compose/material3 AAR 声明 minCompileSdk=37，AGP < 9.1 解析不了 `platforms;android-37.0`；② 9.5.1 是首个能吃 compileSdk 37 的（根 build 脚本注释） | 降：配置期直接失败；升（→10）：`builtInKotlin`/`newDsl` 两开关被移除，本工程结构必须先拆 androidApp 模块 |
| compileSdk | **37**（targetSdk 36 / minSdk 24） | Miuix 0.9.4 的 4 个 Android AAR 声明 minCompileSdk=37（`composeApp/build.gradle.kts` 注释）；本机两套 SDK 都装有 `platforms/android-37.0`（实测 `ls` 确认） | 降到 36：AAR 元数据拒绝，资源编译失败 |
| Gradle | **9.6.1** | AGP 9.5.1 硬性下限 9.6.0——本机实测用 8.10.2 报 `Minimum supported Gradle version is 9.6.0. Current version is 8.10.2.` | 降：插件应用阶段即失败（见 `docs/QUICKSTART.md` 错误 ②）。本工程**不生成 wrapper**：本机拉不动 distribution 下载（`settings.gradle.kts` 头注释），换机器要自己装 9.6.1 并排进 PATH 最前 |
| JDK（跑 Gradle） | **21**（本机 Temurin 21.0.13） | AGP 9 运行需 JDK 17+；21 实测全绿 | ≥17 可跑（17 未实测）；若 daemon 跑在更高 JDK（如 26）上，见下一行的字节码钉死 |
| 桌面/Android 字节码目标 | **JVM 17**（`JvmTarget.JVM_17`） | `composeApp/build.gradle.kts` 显式钉死；**实测产物类文件 major=0x3d（61）= Java 17**（`od` 读 MainKt.class 前 8 字节） | 不钉时字节码跟随 daemon 的 JDK——仓注释记录过 daemon 在 JDK 26 上会编出 class 70、截图进程加载不了的场景；本机 daemon 是 21，钉 17 同时防住两类环境 |
| AGP 兼容开关 | `android.builtInKotlin=false`、`android.newDsl=false`、`android.suppressUnsupportedCompileSdk=37` | AGP 9.0+ 拒绝 com.android.application + KGP 同模块，**两个开关必须同时关**（`gradle.properties` 注释：只关一个仍失败，`newDsl` 默认 true；spike/miuix RESULT §8.2 ②③ 验证过） | 关掉/删掉：配置期失败。配置期实测警告（本机捕获）：`The option setting 'android.builtInKotlin=false' is deprecated. ... It will be removed in version 10.0 of the Android Gradle plugin.` |
| Miuix | **0.9.4**（`miuix-ui` + `miuix-blur` 两个 artifact） | 0.9.x 最新稳定版，正是它把 minCompileSdk=37 带进链条 | 这就是链条源头；升降都会牵动 compileSdk/AGP/CMP |
| Haze | **2.1.0**（+ `haze-blur`） | tmp/haze-probe 探针实测三端可编、桌面真模糊（`composeApp/build.gradle.kts` 注释）；染色必须走 `colorEffects(...)`，`backgroundColor` 在模糊层后面无效（探针 RESULT §坑1） | 未验证其他版本 |
| Ktor | **3.6.1**（core/websockets/content-negotiation/serialization 在 commonMain；CIO=desktop、OkHttp=android、JS=wasm） | Maven Central 当时最新稳定线；其 Gradle 元数据声明 stdlib 2.3.21，2.4.20 编译器可读（读一档 minor）；每个 artifact 有 wasmJsApiElements 变体（注释：对 repo1.maven.org 核过，非凭记忆） | 引擎按 target 各自进依赖，别把引擎挪进 commonMain |
| Tabler Icons | **2.2.1**（cmp / outline-cmp / filled-cmp 三坐标） | 2.2.1 的 jar 内容逐一对过（注释）；MIT 许可伞在 `icons-tabler-cmp` | 业务代码只能经 `vade.ui.icon.VadeIcons`，不许直接 import `com.composables.icons.tabler.*` |
| activity-compose | **1.9.3** | spike 用同一版本（注释） | 未验证 |
| Android SDK | platform **android-37.0** 必须在 | compileSdk 37 | 缺：assembleDebug 报 SDK/platform 错（android-37.0 在本机两套 SDK 均已实测在） |

## 三、SDK 位置解析顺序（Windows 实测行为）

1. `local.properties` 的 `sdk.dir`（**未入库**，`.gitignore` 首行就是它；本仓 worktree 里指向
   `D:/Android/Sdk`）
2. 环境变量 `ANDROID_HOME` / `ANDROID_SDK_ROOT`（`vade-env.sh` 指向 `D:/Hermes/tools/android-sdk`）

本机两套 SDK 都有 android-37.0，所以哪种来源都能编。注意两个坑（均在 QUICKSTART 详述）：

- `local.properties` 是 `.properties` 格式：**反斜杠是转义符**，`sdk.dir=D:\Android\Sdk` 实测报
  `IOException: 文件名、目录名或卷标语法不正确`；必须写 `sdk.dir=D:/Android/Sdk`。
- MSYS 形式 `/d/...` 写进该文件，JVM 按当前盘符解析成 `D:\d\...`（实测
  `new java.io.File("/d/Android/Sdk").getAbsolutePath()` → `D:\d\Android\Sdk`，不存在）。
- AGP 9 把 SDK 检查**推迟**到第一个 Android 任务：实测没有任何 SDK 配置时
  `:composeApp:desktopMainClasses` 照样 BUILD SUCCESSFUL，跑 `:composeApp:assembleDebug` 才报
  `SDK location not found`。

## 四、 Gradle daemon 与环境变量

实测：daemon 的环境变量在**它启动那一刻**定格。之后客户端进程用 `env -u ANDROID_HOME ...`
改变量，daemon 依旧用旧值（复现 SDK-not-found 失败、被迫先 `gradle --stop`）。
含义：改了 `ANDROID_HOME` / `JAVA_HOME` 后，先 `gradle --stop` 再跑，否则「改了没用」。

## 五、本机工具链落点（Hermes 环境，非通用）

| 工具 | 位置 | 备注 |
|---|---|---|
| JDK 21 | `D:/Hermes/tools/jdk21` | Temurin 21.0.13 |
| Gradle 9.6.1 | `D:/Hermes/tools/gradle-9.6.1` | **vade-env.sh 会把 8.10.2 排 PATH 最前**，本仓要手动把 9.6.1 排最前 |
| Android SDK（env） | `D:/Hermes/tools/android-sdk` | 有 android-36 / android-37.0 |
| Android SDK（local.properties） | `D:/Android/Sdk` | 有 android-34/36/37.0、build-tools 34–37 |
| Gradle 用户目录 | `D:/Hermes/tools/gradle-home` | 别塞 C: |

换机器时上表路径全部无效，按第一节链条自装等价版本即可（未在其他机器验证）。

## 六、AGP 10 迁移预告（已有人开路）

`tmp/c-agp10` worktree 已存在（worktree 列表实测可见），对应「拆独立 androidApp 模块 +
`com.android.kotlin.multiplatform.library`」的方向。在那条路落地之前，**不要**单独动
`builtInKotlin` / `newDsl` 两个开关。

## 七、本文引用的实测证据（2026-11-14）

- `gradle --version` → Gradle 9.6.1，Launcher JVM 21.0.13，Daemon JVM `D:\Hermes\tools\jdk21`
- `gradle :composeApp:desktopMainClasses` → BUILD SUCCESSFUL in 4s
- `gradle :composeApp:desktopTest --rerun` → 368 tests / 0 failures / 1 skipped（XML 汇总）
- `gradle :composeApp:assembleDebug` → BUILD SUCCESSFUL in 1m 19s，APK 21,534,706 字节
- gradle-8.10.2 → `Minimum supported Gradle version is 9.6.0. Current version is 8.10.2.`
- 干净 clone 无 SDK → `SDK location not found. ...`
- JVM 路径解析探针 → `/d/Android/Sdk` ⇒ `D:\d\Android\Sdk`（exists=false）
- `local.properties` 反斜杠 → `java.io.IOException: 文件名、目录名或卷标语法不正确。`
- `od -N 8 MainKt.class` → `ca fe ba be 00 00 00 3d`（major 61 = Java 17）
- `gradle :composeApp:tasks` → BUILD FAILED（Compose Preview 任务创建失败，已知怪癖）
