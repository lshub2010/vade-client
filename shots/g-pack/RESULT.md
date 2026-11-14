# g-pack · A5 可分发（W3）RESULT

分支 `tmp/g-pack`（基于 main @ f189de2）。commit：**a363d8b**（代码）+ RESULT 提交。
按任务书未拍截图（截图由父代统一拍，且并行期禁杀 java 进程）。

## 1. 交付内容

| 项 | 实现 |
|---|---|
| 版本号单一来源 | `gradle.properties:33` 定义 `vadeVersion=0.1.0-alpha.1`；`generateVadeBuildConfig` 任务（composeApp/build.gradle.kts:36-58）把它注入为生成类 `vade.ui.buildconfig.VadeBuildConfig`（commonMain srcDir，三端同读）；Android `versionName`（:201）与 jpackage `packageVersion`（:227，自动去掉 `-alpha.N` 后缀）均由同一 val 派生。全仓无第二个版本字面量 |
| 构建时间 | `generateVadeBuildConfig` 在每次构建写入 UTC ISO `yyyy-MM-dd'T'HH:mm:ss'Z'`（本次实例 `2026-11-14T11:27:23Z`） |
| 关于页 | ProfileScreen 的「关于」占位行改为可点（`onClick`，无弹层）→ `AboutPage` 全屏子页（D91），显示版本号、构建时间、四条许可行、返回钮（VadeIcons.Back）。状态为页面内局部 state，shell 未动 |
| 许可列表 | 逐字镜像 README「第三方许可与致谢」：KernelSU（Apache 2.0）/ Miuix（Apache 2.0）/ Tabler Icons（MIT, Copyright (c) 2018-2026 Tabler contributors）/ HarmonyOS Sans（**预留位 `adopted=false`**，README 标注待定，未写成已采用）。未伪造任何许可文本 |
| 启动脚本 | `deploy/desktop/README.md` + `run.bat`（CRLF/ASCII）+ `run.sh`：定位 app-image 直接启动、参数透传、缺产物时提示构建命令 |
| Msi | `packageMsi` 本机实测成功——Compose 插件自带 `downloadWix`/`unzipWix` 自动下载 WiX 3.x 到 Gradle 缓存，**无需手工装 WiX**（README 已按实测改写） |

## 2. 产物路径与体积（实测）

| 产物 | 路径 | 体积 |
|---|---|---|
| MSI 安装包 | `composeApp/build/compose/binaries/main/msi/vade-ui-0.1.0.msi` | **92,767,946 bytes（88.4 MB）** |
| 免安装 app-image | `composeApp/build/compose/binaries/main/app/vade-ui/` | **157,339 KB（153.6 MB，`du -sk`）**，含自带 jlink 运行时 `runtime/` 与启动器 `vade-ui.exe`（605,184 B） |

构建命令：`gradle :composeApp:packageMsi`（56s）与 `:composeApp:createDistributable`（61s），均 BUILD SUCCESSFUL。

**启动冒烟（真跑，非仅文件存在）**：直接执行 app-image 的 `vade-ui.exe`（未带任何参数/环境注入），12 秒后 `tasklist` 可见 launcher + JVM 两个 `vade-ui.exe` 进程存活（8.2 MB / 265.5 MB），随后按映像名结束（未触碰其它 java 进程）。产物含运行时，运行机无需 JDK/Gradle/Android SDK。

## 3. desktopTest 原始数字（--rerun 真跑）

```
files: 13
tests: 161  failures: 0  errors: 0  skipped: 1
```
- 基线 154 → 161（+7，全部来自本批 `AboutPageTest`）；skip 的 1 条是既有 env-gated 真核冒烟（`VadeClientSmokeTest`，未设 `VADE_SMOKE_PORT` 时的 Assume 跳过机制，本批未新增任何 skip）。
- 新增 7 条：
  1. `versionConstantIsNonEmptySemverWithAlpha` — 常量非空 + `^\d+\.\d+\.\d+-alpha\.\d+$`
  2. `buildTimeIsUtcIsoFormat`
  3. `gradlePropertiesIsTheSingleVersionSource` — gradle.properties 的值 == 注入常量（钉住同源）
  4. `generatedFileCarriesTheSameVersion` — 生成文件内容 == gradle.properties 值（钉住注入链路）
  5. `licenseListHasAtLeastThreeEntries` — ≥3 条 + 四个名字全在列 + HarmonyOS 不得伪造成已采用
  6. `aboutPageRendersVersionBuildTimeAndLicences` — `runComposeUiTest` 真渲染，版本/构建时间/四个许可名节点全部存在
  7. `profileAboutRowOpensTheFullSubpage` — 点「关于」行 → 子页出现版本号（占位行确实可点、D91 全屏子页成立）

原始输出：`AboutPageTest[desktop] tests= 7 failures= 0 errors= 0`（逐条 ok，见 `composeApp/build/test-results/desktopTest/TEST-vade.ui.AboutPageTest.xml`）。

## 4. 版本号来源（文件与行号）

- **唯一字面量**：`gradle.properties:33` → `vadeVersion=0.1.0-alpha.1`
- 读取与 fail-fast：`composeApp/build.gradle.kts:29-31`（缺失即配置期报错）
- 注入任务：`composeApp/build.gradle.kts:36-58` → 生成 `composeApp/build/generated/vadeBuildConfig/vade/ui/buildconfig/VadeBuildConfig.kt`（VERSION + BUILD_TIME）
- 派生：`versionName = vadeVersion`（build.gradle.kts:201）；`packageVersion = vadeVersion.substringBefore('-')`（:227）
- 消费：`ProfileScreen.kt` 关于行副标题 + `AboutPage` 版本卡

## 5. 干净机器还差什么（诚实清单）

1. **产物不入库**：MSI 与 app-image 在 `build/` 下，仓库没有发布通道（`release/` 目录、artifact 上传、更新通道都不存在）。alpha 目前只能「打包机重建」或手动拷贝目录给试用者。
2. **「干净机器」本身未验证**：本机即开发机。app-image 自带运行时、无 PATH/D:/Hermes 依赖，理论上干净 Windows 双击即起；建议下一步在一台无 JDK 的机器上做一次双击验证（MSI 安装同理）。
3. **无代码签名**：MSI/EXE 未签名，SmartScreen/杀软会告警——分发前需要证书与签名流水线。
4. **离线打包机注意**：WiX 由 Compose 插件 `downloadWix` 首次联网下载进 `GRADLE_USER_HOME` 缓存；完全离线的打包机需预热缓存或自备 WiX 3.x。
5. **关于页「E2E 设备标识」仍是 U1-f 占位**（本批只交付版本/构建时间/许可）。
6. **Android 分发未做**：APK 签名/渠道没有，仅 versionName 已接单一来源（0.1.0-alpha.1）。

## 6. 过程中踩到的坑（对后续棒有用）

- `MaterialImportGuardTest` 是**全文 contains** 扫描（含注释/字符串）：ProfileScreen 里写许可文案时不能出现 `top.yukonga.miuix` / `androidx.compose.material3` / `dev.chrisbanes.haze` 字面量——Miuix 条目的坐标改写成「Miuix KMP 构件：miuix-ui 与 miuix-blur」（README 保留完整坐标）。
- Gradle KTS 默认导入不含 `java.time.*`/`java.io.*`（报 `Unresolved reference 'time'`），构建脚本里要用必须显式 `import`。
- `compose.uiTest` 是 `@ExperimentalComposeLibrary`：build.gradle.kts 顶部要 `@file:OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)`。
- 桌面 `runComposeUiTest` 在 Windows 无头环境可用（两条渲染测试均真跑绿）。
- MaterialImportGuard 之外：`IconGuardTest` 的禁字符表同样适用于 destinations 新文案（返回钮走 `VadeIcons.Back`，未用字符箭头）。
