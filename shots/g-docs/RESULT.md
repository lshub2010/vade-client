# g-docs RESULT —— alpha 快速开始与环境矩阵文档

- 分支 `tmp/g-docs`（worktree `D:/HermesWorkspace/Vade-client-wt/g-docs`，基于 main @ `f189de2`）
- 文档提交：`75f6c6f`；本报告随后单独提交。**未 push、未 merge。**
- 口径对齐主仓 `docs/ALPHA.md`（口径 A）。本文只记真实执行的事，不含「已通过」类结论——结论看输出。

## 一、交付文件

| 文件 | 内容 |
|---|---|
| `docs/QUICKSTART.md`（新建） | 当前状态表（能用/假数据/未实现逐项）→ 环境要求 → 取码 → 环境变量 → 编译 → 启动 → 测试 → APK → core 启动 → 7 条常见错误与解法 → 命令验证状态一览 |
| `docs/ENVIRONMENT.md`（新建） | 版本链条（Miuix 0.9.4 → compileSdk 37 → AGP 9.5.1 → Gradle 9.6.1 → builtInKotlin/newDsl 双关 → AGP 10 拆模块）+ 矩阵表（每项：为什么 / 换了会怎样）+ SDK 解析顺序 + daemon 环境变量行为 + 本机工具链落点 |
| `README.md`（仅追加） | 末尾新增「## 快速开始」一节，指向两文 + 最小命令路径。既有内容**一字未改**（`git diff README.md` 全为 `+` 行） |
| `shots/g-docs/desktop-quickstart-fixed.png` | 桌面客户端真实窗口截图（PrintWindow，466×893；会话列表 + 假数据横幅 + 底栏四目的地） |

## 二、实测过的命令（2026-11-14，Windows 11 + MSYS bash，Temurin 21.0.13 + Gradle 9.6.1）

| # | 命令 | 实测结果（摘录） |
|---|---|---|
| 1 | `gradle --version` | `Gradle 9.6.1`，`Launcher JVM: 21.0.13 (Eclipse Adoptium...)` |
| 2 | `gradle :composeApp:desktopMainClasses` | `BUILD SUCCESSFUL in 4s`（5 tasks: 4 executed, 1 from cache） |
| 3 | `gradle :composeApp:desktopTest` | `BUILD SUCCESSFUL in 4s`；XML 汇总 **154 tests / 0 failures / 1 skipped** |
| 4 | `gradle :composeApp:desktopTest --rerun` | `BUILD SUCCESSFUL in 5s`（强制重跑，防缓存假绿灯）；skipped=`TEST-vade.ui.protocol.VadeClientSmokeTest.xml` |
| 5 | `gradle :composeApp:run` | 拉起 `vade.ui.MainKt` java 进程（pid 17052，CIM 查证），后经 kill 终止；窗口渲染见截图 |
| 6 | `tools/capture-window.ps1`（真实窗口截图） | `launched pid=20980 ... window rect=55,48 466x893 / PrintWindow=True / saved shots/g-docs/desktop-quickstart-fixed.png 466x893 31940 bytes / killed pid=20980` |
| 7 | `gradle :composeApp:assembleDebug` | `BUILD SUCCESSFUL in 1m 19s`；产物 `composeApp-debug.apk` 21,534,706 字节 |
| 8 | `gradle :composeApp:packageMsi :composeApp:createDistributable --dry-run` | `BUILD SUCCESSFUL`（任务存在；**未真正执行**） |
| 9 | `gradle-8.10.2 :composeApp:desktopMainClasses`（版本不符复现） | `> Minimum supported Gradle version is 9.6.0. Current version is 8.10.2.` |
| 10 | 干净 clone（scratch）+ 无 SDK + `assembleDebug`（SDK not found 复现） | `> SDK location not found. Define a valid SDK location with an ANDROID_HOME environment variable or by setting the sdk.dir path ...` |
| 11 | JVM 路径解析探针（`java Probe.java`） | `get("/d/Android/Sdk") -> D:\d\Android\Sdk`，`exists? = false`（`D:\d\` 双前缀根因） |
| 12 | `local.properties` 写 `sdk.dir=D:\Android\Sdk`（反斜杠复现） | `> java.io.IOException: 文件名、目录名或卷标语法不正确。` |
| 13 | `env -u ANDROID_HOME ... gradle assembleDebug`（daemon 环境复现） | 先成功（daemon 吃旧环境）→ `gradle --stop` 后复现 ⑩ |
| 14 | `od -N 8 .../MainKt.class` | `ca fe ba be 00 00 00 3d`（major 61 = Java 17，字节码钉死为真） |
| 15 | `gradle :composeApp:tasks` | `BUILD FAILED`（Compose Preview 任务创建失败——怪癖，写进 QUICKSTART 错误 ⑥） |
| 16 | `shots/w1-proto/smoke_core.py`（起真 core） | banner JSON（port 58391 / pair_code / health）+ `curl /healthz` → `{"ok": true, "version": "0.1.0", "policy_mode": "allow_all"}` + `/ws` → `HTTP/1.1 101 Switching Protocols`（Server: uvicorn） |

错误复现 ⑩–⑬ 在 `D:/Hermes/cache/scratch` 的临时 clone 里做（已删除），未触碰本 worktree。

## 三、引用但未在本机验证

| 项 | 来源 | 为何没跑 |
|---|---|---|
| `compileKotlinWasmJs` 绿 | `shots/w1-proto/RESULT.md` | 时间成本；QUICKSTART 已标注「未在本机验证」 |
| echo 模块启动（`pip install -e` / `python -m echo_module`） | `D:/HermesWorkspace/Vade/Vade-module-echo/README.md` | echo 已迁出主仓且 UI 未接线，本任务不做；文档如实标注 |
| `packageMsi` / `createDistributable` 真正执行 | — | U1-a 从未打包（build 脚本注释）；A5 属 W3 |
| Android 运行时（模拟器/真机安装启动） | ALPHA.md A6 | 超出本任务范围 |
| Linux / macOS 全流程 | — | 本机为 Windows；文档明确标注未验证 |
| `gradle :composeApp:desktopMainClasses` 的**冷**启动耗时 | — | 本机缓存已热（4s 是热跑数字） |

## 四、发现的文档与代码（/环境）不一致之处

1. **README「常用命令」第一步就会挂**：`source D:/Hermes/tools/env/vade-env.sh` 把 **Gradle
   8.10.2 排到 PATH 最前**，紧接着的 `gradle :composeApp:desktopMainClasses` 用 8.10.2 跑必失败
   （复现 ⑨）。`Main.kt` 头注释早已写明该坑（"vade-env.sh puts 8.10.2 first, so prefix
   PATH=..."），README 的命令清单却没提。→ QUICKSTART 的环境变量步骤与 README 追加节都补了
   `PATH` 前缀行。**README 旧命令段未改**（约束只许追加），差异留给后续批次修。
2. **README 构建矩阵「daemon 是 JDK 26」**：本机 `gradle --version` 实测 daemon 是
   `D:\Hermes\tools\jdk21`（21.0.13）。JVM 17 钉死本身为真（复现 ⑭：class major 61），注释里的
   「编出 26 的字节码」是写注释那台机器的 daemon 场景。文档改用「字节码跟随 daemon JDK」的表述。
3. **ALPHA.md A3 / §四「真模块只有 echo」**：主仓 `modules/README.md` 写明 echo
   「参考实现（**已迁出**）」到独立仓 `vade-module-echo`（本地存在于
   `D:/HermesWorkspace/Vade/Vade-module-echo`，主仓 `modules/` 只剩 README）。按 ALPHA 口径做 A3
   前需先 clone 该仓——QUICKSTART 与状态表均按此现实写。
4. **测试基线数字**：ALPHA.md A6 写「客户端基线 154」——实测吻合（154/0/1 skipped）；
   `shots/w1-proto/RESULT.md` 写「140 tests」是 w1-proto 批次当时值，合并后主干为 154（其中
   1 条 skipped 是该批冒烟测试的 Assume 门控，非新增失败）。
5. **`gradle :composeApp:tasks` 不可用**：实测 BUILD FAILED（复现 ⑮）。任何文档若建议用它列
   任务都会踩坑——QUICKSTART 错误 ⑥ 记录并给了绕法。
6. **「桌面编译绿 ≠ 三端编译绿」**：AGP 9 把 SDK 检查推迟到第一个 Android 任务（复现 ⑩ 前的
   意外发现：无 SDK 时 `desktopMainClasses` 照样成功）。QUICKSTART §2 加了显式警告。
7. **Compose 插件 1.12.0 vs classpath `ui:1.12.2`**：`gradle :composeApp:run` 输出中的版本不一致
   警告（良性，实测窗口正常、测试全绿）。README/矩阵未提及——已写进 QUICKSTART 错误 ⑦ 与
   ENVIRONMENT.md 矩阵行，提醒别为消警告乱动矩阵。

## 五、约束遵守

- 只新建/修改：`docs/QUICKSTART.md`、`docs/ENVIRONMENT.md`、`README.md`（仅追加一节）、
  `shots/g-docs/**`。`git status` 核对过，无任何 `*.kt` / `build.gradle.kts` /
  `gradle.properties` / `libs.versions.toml` 改动。
- README 第三方许可四段（KernelSU / Miuix / Tabler Icons / HarmonyOS Sans）未动。
- 未删改任何测试与断言；未 push、未 merge。
- 文档中所有「可用」均有本文第二节的实测输出对应；未验证项全部显式标注。
