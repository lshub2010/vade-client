# 自救命令卡键名修复 · g-rescue-text —— 照抄即连：文案与解析器单一真相同源

- 仓库 / 基线：`vade-client` · `origin/main` = **53625dc**（worktree `tmp/g-rescue-text`）
- 本棒提交：**d1e0782**（代码 + 守卫测试 + PLAYBOOK/QUICKSTART 同步；本文件随后单独提交）
- 缺陷来源：审计 **G4**（中，两次复现）——core 不可达时，屏上那张「自救命令」卡教的命令是
  `--es vade.source real --es vade.pair_code <码>`，而客户端 Android 实际读的是**裸键**
  `source` / `host` / `port` / `pair_code`（`data/SourceConfig.kt` 的 `parse(source, host, port, pairCode)`
  与 androidMain 的 `intent.extras?.getString(SourceKeys.*)`）。照抄 → extras 里没有解析器要找的
  键 → **静默回落假数据**，用户还以为连上了。
- 三门禁：`desktopTest --rerun` / `assembleDebug` / `compileKotlinWasmJs` 全绿（数字见 §4）。

---

## 1. 根治方式：单一真相派生，不是再手写一份

**真相表**：`SourceKeys`（`commonMain/.../data/SourceConfig.kt`）新增导出

```kotlin
val acceptedKeys: List<String> = listOf(SOURCE, HOST, PORT, PAIR_CODE)
```

这张表**本来就是**两个解析通道的键源：Android actual 逐键读 `SourceKeys.*`
（`SourceConfig.android.kt`），wasm query 语法 `rawStringsFromQuery()` 用同一张表。修的是
第三个消费者——**写给用户看的文案**——此前它手写了一份键名并漂移。

**文案派生**（`shell/AppShell.kt` 的 `selfRescueRemedy`）：三条命令的键名取 [SourceKeys]、
模式词取 [SourceConfig.REAL]（parse 唯一放行的词），**无一处手写键名**：

- 桌面（不变）：`VADE_SOURCE=real VADE_HOST=<本运行host> VADE_PORT=<本运行port> VADE_PAIR_CODE=<配对码> gradle :composeApp:run`
- Android（**整行可照抄**，修前是残缺的 fragment + 错键名）：
  `adb shell am start -n vade.ui/.MainActivity --es source real --es host <本运行host> --es port <本运行port> --es pair_code <配对码>`
- Wasm（补全 host/port，修前缺）：`?source=real&host=<本运行host>&port=<本运行port>&pair_code=<配对码>`

生成文案的文件在 `shell/**`（禁区列表），但任务的允许清单明确含「生成文案的文件」——
即 `selfRescueRemedy` 所在处；本棒对 `AppShell.kt` 的改动**严格限于该函数 + 其 KDoc + 一行
import**，shell 其余部分零改动。`data/SourceConfig.kt` 仅导出键表，`parse` 行为零变化；
`androidMain` 两处只改 KDoc/注释（同样写着 `vade.source` 的旧描述）。

## 2. 守卫测试（`desktopTest/.../shell/SelfRescueRemedyGuardTest.kt`，10 条）

三层，缺一不可：

| 层 | 测试 | 钉住什么 |
|---|---|---|
| 键集合一致（双向） | `everyAndroidKeyInTheCopyIsAKeyTheParserAccepts` / `everyAcceptedKeyAppearsInTheAndroidCommand` / `wasmQueryKeysMatchTheAcceptedKeysExactly` | 文案里的 `--es` 键与 query 键 == `SourceKeys.acceptedKeys`（多一个键=照抄设进无效键，少一个键=照抄丢参数） |
| 照抄可连（**不是假源**） | `copyingTheAndroidCommandParsesToTheRealSourceOfThisRun` / `copyingTheWasmQueryParsesToTheRealSourceOfThisRun` / `copyingTheDesktopCommandLineParsesToTheRealSourceOfThisRun` | 从卡上**整行**提取命令 → 换掉 `<配对码>` 占位 → 按各端 actual 的读法（Android 逐键查 `SourceKeys` 常量、wasm 走真 `rawStringsFromQuery`、桌面取 `VADE_*=值`）→ `SourceConfig.parse` 必得 `SourceSpec.Real`，且 host/port/配对码与命令一致。键名一漂 → 该键查到 null → parse 落回 Fake → 红（正是审计里的静默回落，现在有测试拦着） |
| 形状不退化 | `theLegacyPrefixedKeyNamesNeverComeBack` / `theModeWordIsTheOneParseAccepts` / `theAndroidCommandIsCompleteEnoughToRunVerbatim` / `everyLineOfTheCopyStartsACopyableCommandOrExplainsWhereTheCodeComesFrom` | `vade.<key>` 前缀旧键名禁入；模式词=REAL；am start 命令整行可跑（含 `-n vade.ui/.MainActivity`）；命令独占整行、不与说明黏行 |

既有测试的一处**反向修正**：`ListStatusOfTest.remedyNamesTheOtherTwoTargetsChannels` 曾断言
`remedy.contains("vade.pair_code")`——测试自己在守护缺陷，已改为断言整行 adb 命令 + 占位符；
键名断言全部移进守卫测试、从 `SourceKeys` 派生。其余 5 条自救卡断言（不回显真码、host/port
取本运行配置、不塞默认端口等）原样全绿。

## 3. 文档同步（历史删除线保留）

| 文件 | 改动 |
|---|---|
| `shots/g-live/PLAYBOOK.md` §2b | 旧命令以删除线原样保留（5 行 ~~...~~）+ 修正说明；正确命令换裸键 |
| `shots/g-live/PLAYBOOK.md` 卡点 1 / §5 对照表 | `vade.pair_code` → 裸键 `pair_code`；Android extra 列修正 + 修前误写标注 |
| `docs/QUICKSTART.md` UI 数据行 | `vade.source=real` → 裸键 `source=real`（键表 `SourceKeys`）+ 修前误写标注 |
| androidMain KDoc/注释（`SourceConfig.android.kt`、`MainActivity.kt`） | 同样写着 `vade.` 前缀键的旧描述改裸键，并注明键源 = `SourceKeys` |

历史记录类文件（`shots/*/RESULT.md` 各轮审计原文）未动。全仓 grep 复核：活文案中
`vade.source/vade.host/vade.port/vade.pair_code` 仅存于删除线、KDoc 里的「修前误写」叙述与
守卫测试的缺陷描述。

## 4. 门禁（三目标，全绿）

| 目标 | 结果 |
|---|---|
| `:composeApp:desktopTest --rerun` | **BUILD SUCCESSFUL**；XML 求和：**tests=1220**、failures=0、errors=0、skipped=1（`VadeClientSmokeTest.attachSubmitAndLiveEventsAgainstARealCore`，**改动前就 skip**：需 `VADE_SMOKE_PORT` 真 core） |
| `:composeApp:assembleDebug` | **BUILD SUCCESSFUL** |
| `:composeApp:compileKotlinWasmJs` | **BUILD SUCCESSFUL**（与上一行同一次调用跑过） |

新增 10 条守卫测试全绿（`SelfRescueRemedyGuardTest[desktop] tests=10 failures=0`）；
`ListStatusOfTest[desktop]` 17 条全绿。未删除/未跳过/未弱化任何既有断言（仅修正上述钉缺陷的
两条字面断言）。

## 5. 未覆盖 / 局限（如实）

- **Android 通道按 actual 同款键读法模拟，未起真模拟器照抄实跑**：desktopTest 没有真
  `Bundle`，守卫以「与 `SourceConfig.android.kt` 完全相同的 `SourceKeys` 常量逐键取值」模拟
  extras 通道；actual↔表的绑定是代码同源（actual 里就用 `SourceKeys` 常量，无手写键名），
  但没有独立运行期测试。旁证：g-topbar-band 的采集脚本早已用裸键 `--es source fake` 在
  emulator-5554 实跑成功（同一通道）。
- **桌面 env 变量名未派生**：`VADE_*` 四个名字在 desktopMain actual 里硬编码，actual 不在本棒
  允许清单内；其名与文案的绑定由既有字面断言钉住，是**字面钉**不是派生。审计的漂移发生在
  裸键侧（Android/wasm），那两路是真派生。
- **组件名 `vade.ui/.MainActivity` 是命令里仅剩的手写常量**：commonMain 拿不到
  `applicationId`；与 build.gradle.kts / capture.sh / PLAYBOOK 的一致性由
  `theAndroidCommandIsCompleteEnoughToRunVerbatim` 字面钉住。
- **Wasm 浏览器内照抄未实跑**：`compileKotlinWasmJs` 只证明编译；query 解析路径由
  `copyingTheWasmQueryParsesToTheRealSourceOfThisRun`（走真 `rawStringsFromQuery`）覆盖。

## 6. 文件清单

- 新增：`composeApp/src/desktopTest/kotlin/vade/ui/shell/SelfRescueRemedyGuardTest.kt`、`shots/g-rescue-text/**`（本文件）
- 修改：`commonMain/.../data/SourceConfig.kt`（+`acceptedKeys`、文件 KDoc 裸键）、
  `commonMain/.../shell/AppShell.kt`（仅 `selfRescueRemedy` + KDoc + import）、
  `androidMain/.../data/SourceConfig.android.kt` 与 `androidMain/.../MainActivity.kt`（KDoc/注释）、
  `desktopTest/.../shell/ListStatusOfTest.kt`（修正钉缺陷的两条断言）、
  `docs/QUICKSTART.md`、`shots/g-live/PLAYBOOK.md`
- 未改：`shell/**` 其余、`chat/**`、`module/**`、`skin/**`、`protocol/**`、`build.gradle.kts`、`contracts/**`
