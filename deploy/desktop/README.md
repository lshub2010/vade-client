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

**alpha 序列的已知限制（2026-11-14 实测发现，尚未修）**：既然后缀被去掉，
`0.1.0-alpha.1` / `-alpha.2` / `-alpha.3` 打出来的 MSI **ProductVersion 全是
`0.1.0`**，Windows Installer 不会把它们互相识别为升级——测试者装第二个 alpha
时不会被当成新版本，需要先在「应用和功能」里卸载上一个，否则会报“已安装”或
留下两份。连续分发 alpha 时二选一：

1. 每轮公布时把补丁位往上走（`0.1.1-alpha.1`、`0.1.2-alpha.1`…），ProductVersion
   自然不同——**推荐**，代价是版本号跳得比实际改动快；
2. 继续用 app-image（免安装目录）：它没有 ProductVersion 概念，覆盖目录即更新，
   适合内测期反复替换。

真要做“同一 x.y.z 下多个 alpha 可升级”，需要给 jpackage 加 `--win-upgrade-uuid`
并让 ProductVersion 单调递增，那等于把 build 号塞进补丁位——等有正式发布通道时再设计。

## 启动参数

`composeApp/src/desktopMain/kotlin/vade/ui/Main.kt` 支持一组调试旗标
（`--skin=` / `--dark` / `--dest=` / `--bar=` 等，见其 KDoc）。双击/脚本启动不带参数
即默认产品形态。
