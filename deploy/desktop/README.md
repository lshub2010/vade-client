# 桌面端分发（A5）

版本号的唯一来源是仓库根 `gradle.properties` 的 `vadeVersion`。构建时
`composeApp` 的 `generateVadeBuildConfig` 任务把它注入为
`vade.ui.buildconfig.VadeBuildConfig`，「关于」页读的就是这个常量；Android 的
`versionName` 与 jpackage 的 `packageVersion` 也由同一个值派生。**改版本只改
gradle.properties 一处**，不要在任何代码或脚本里写第二份。

## 产物

| 形态 | 命令 | 产物路径 | 打包机额外依赖 |
|---|---|---|---|
| 免安装 app-image（当前交付形态） | `gradle :composeApp:createDistributable` | `composeApp/build/compose/binaries/main/app/vade-ui/` | 无（jpackage 随 JDK 内置；产物自带 jlink 运行时） |
| MSI 安装包 | `gradle :composeApp:packageMsi` | `composeApp/build/compose/binaries/main/msi/` | WiX Toolset 3.x（见下） |

app-image 是**免安装目录**：双击其中的 `vade-ui.exe`（Windows）或
`bin/vade-ui`（Linux / macOS）即可运行，**运行机器不需要安装 JDK、Gradle 或
Android SDK** —— jlink 运行时已打进目录内。

## 怎么跑

在仓库根：

- Windows：`deploy\desktop\run.bat`
- Linux / macOS：`./deploy/desktop/run.sh`

两个脚本都会先找已构建的 app-image（`composeApp/build/compose/binaries/main/app/vade-ui/`），
找到就直接启动并把额外参数原样传给应用（如 `run.bat --dark`）；没找到会提示先执行
`gradle :composeApp:createDistributable`。

开发期替代：`gradle :composeApp:run`（需要 JDK + Gradle，且不走分发产物路径）。

## 打包机需要什么（只在打包时需要，运行时不需要）

- **JDK 17+**：`createDistributable` 走 JDK 自带的 jpackage/jlink（JDK 13+ 均含）。
- **Gradle 9.6.1**：版本矩阵锁死（AGP 9.5.1 / Kotlin 2.4.20 / CMP 1.12.0 / compileSdk 37）。
- **Android SDK**：仅当同时构建 Android 端（`assembleDebug`）才需要
  （`local.properties` 的 `sdk.dir`）；只做桌面分发用不到。

## MSI 的前置条件

`gradle :composeApp:packageMsi` **开箱即用**：Compose Desktop 插件自带
`downloadWix` / `unzipWix` 任务，会自动把 WiX 3.x 下载到 Gradle 缓存
（`GRADLE_USER_HOME/caches/…`），打包机无需手工安装 WiX（2026-11-14 实测
本机即由此产出 MSI）。只有绕过 Gradle 直接调 jpackage 时才需要自备 WiX。

MSI 的产品版本必须是纯数字 `x.y.z`——构建脚本已自动取 `vadeVersion` 去掉
`-alpha.N` 后缀（`0.1.0-alpha.1` → `0.1.0`），无需手工维护第二个版本号。

## 启动参数

`composeApp/src/desktopMain/kotlin/vade/ui/Main.kt` 支持一组调试旗标
（`--skin=` / `--dark` / `--dest=` / `--bar=` 等，见其 KDoc）。双击/脚本启动不带参数
即默认产品形态。
