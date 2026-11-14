# RESULT — w-g-icons 字符图标存量清理（30 处）

Worktree `D:/HermesWorkspace/Vade-client-wt/g-icons` · 分支 `tmp/g-icons`（基于 main @ f2dfad4）
代码 Commit：**2834b42**（+RESULT.md 为后续小提交）
用户硬要求：「选项的图标不要用文字，用图标」——图标一律 `ImageVector`，经 `vade/ui/icon/VadeIcons`
语义导出；禁 Unicode 符号/汉字/emoji 当图标（docs/visual-language.md §3）。

## 0. desktopTest 原始数字

```
tests=435  failures=0  errors=0  skipped=1     （gradle :composeApp:desktopTest --rerun）
基线 422 → +13（全部在新增套件 vade.ui.icon.VectorIconSwapTest）
skipped=1 是既有 VadeClientSmokeTest（协议冒烟，无服务端环境时跳过，基线内已有）
```

三目标门禁（Gradle 9.6.1，wrapper 不在仓内，用 /d/Hermes/tools/gradle-9.6.1）：

| 门禁 | 结果 |
|---|---|
| `:composeApp:desktopTest --rerun` | tests=435 failures=0 errors=0 skipped=1（自 TEST-*.xml 求和） |
| `:composeApp:assembleDebug` | BUILD SUCCESSFUL |
| `:composeApp:compileKotlinWasmJs` | BUILD SUCCESSFUL |

未写「已通过」判词 —— 以上是原始值。

环境注：本 worktree 初次跑全量时 `VadeContractCoverageGuardTest` 3 失败，报
`contracts/vade/schema/*.json not found — submodule 未检出`。与代码无关：先
`git submodule update --init contracts/vade` + `bash tools/setup-contracts.sh`（稀疏检出
schema / protocol/core/kotlin / docs/rfc）后复跑，435 全绿。子模块 SHA 与记录一致
（320384a），未改动任何 tracked 文件。

## 1. 逐行映射表（30 处 = 清单全部，另有 1 处清单外同病同修）

行号为 main @ f2dfad4 的旧行号；`(=)` 表示与我扫出的现行号一致，`(≈)` 表示行号有漂移但内容相同。

| 位置（旧行号） | 旧字符 | 新 | 语义依据 |
|---|---|---|---|
| demo/MotionProbe.kt:314 (=) | `●` | `VadeIcons.Running`（Tabler PlayerPlay） | 「运行中」状态行；C-Activity 已为子代理建立的同语义导出 |
| demo/MotionProbe.kt:326 (=) | `◆` | `VadeIcons.Archive` | 「已归档」= 归档动作/状态 |
| demo/MotionProbe.kt:296（**清单外**） | `★` | `VadeIcons.Star` | 「收藏」；同文件同病（新守卫的字集未含 ★ 所以没被列），顺手清掉 |
| destinations/ProfileScreen.kt:115 (≈167) | `●` | 删除（见 §3.1） | 选中态已由 BtnStyle.Primary 表达 |
| destinations/ProfileScreen.kt:129 (≈181) | `●` | 删除（见 §3.1） | 同上 |
| search/SearchOverlay.kt:132 (=) | `←` | `VadeIcons.Back` | 返回/关闭搜索面板的导航手势；Back 已有导出（ChevronLeft），未追加 ArrowLeft |
| search/SearchOverlay.kt:139 (=) | `✕` | `VadeIcons.Close` | 清空输入（dismiss） |
| search/SearchOverlay.kt:189 (=) | `✕` | `VadeIcons.Close` | 关闭命令面板 |
| shell/BottomBar.kt:198 (≈220) | `•` | `item.icon ?: VadeIcons.Circle` | 图标缺省落**中性圆矢量**，绝不回退字符 |
| shell/GlassNavBar.kt:81 (=) | `•` | `VadeIcons.navIconOrNull(item.id) ?: Circle` | NavItem 冻结无 icon 槽 → 按 id 映射四个目的地矢量 |
| skin/material/MaterialParts.kt:433 (=) | `↻` | `VadeIcons.Refresh`（contentDescription="重试"） | 附件失败重试 |
| skin/material/MaterialParts.kt:516 (=) | `▾`/`▸` | `ChevronDown`/`ChevronRight` | 工具卡展开/收起 affordance |
| skin/material/MaterialParts.kt:685 (=) | `◍` | `VadeIcons.Mic`（contentDescription="语音输入"） | D97 语音按钮的字形占位 → 真麦克风矢量 |
| skin/material/MaterialSkin.kt:374 (=) | `•` | `navIconOrNull(item.id) ?: Circle` | 同 GlassNavBar |
| skin/miuix/MiuixParts.kt:442 (=) | `↻` | `VadeIcons.Refresh` | 附件重试 |
| skin/miuix/MiuixParts.kt:446 (=) | `×` | `VadeIcons.Close` | 附件移除 |
| skin/miuix/MiuixParts.kt:521 (=) | `▾`/`▸` | `ChevronDown`/`ChevronRight` | 工具卡展开/收起 |
| skin/miuix/MiuixParts.kt:730 (=) | `■` | `VadeIcons.Stop`（Tabler.Filled.PlayerStop，v2 §3 唯一填充例外） | 生成中停止 |
| skin/miuix/MiuixParts.kt:730 (=) | `↑` | `VadeIcons.Send`（Outline.ArrowUp） | 发送 |
| skin/miuix/MiuixParts.kt:755 (=) | `◉` | `VadeIcons.Mic`（contentDescription="语音输入"） | D97 麦克风按钮（GlassIconButton） |
| skin/miuix/MiuixShowcase.kt:99 (=) | `⋯` | 真文字「更多」（见 §3.2） | skin.Button 冻结 String API 装不下矢量 |
| skin/miuix/MiuixShowcase.kt:101 (=) | `↑` | 真文字「发送」（见 §3.2） | 同上 |
| skin/miuix/MiuixShowcase.kt:195 (=) | `↑` | 真文字「发送」 | BtnStyle.Primary 矩阵 |
| skin/miuix/MiuixShowcase.kt:196 (=) | `＋` | 真文字「添加」 | BtnStyle.Secondary 矩阵 |
| skin/miuix/MiuixShowcase.kt:197 (=) | `✕` | 真文字「删除」 | BtnStyle.Danger 矩阵 |
| skin/miuix/MiuixShowcase.kt:198 (=) | `⋯` | 真文字「更多」 | BtnStyle.Text 矩阵 |
| skin/miuix/MiuixShowcase.kt:215 (=) | `＋` | 真文字「添加」 | 禁用态按钮 |
| skin/miuix/MiuixSkin.kt:473 (=) | `•` | `navIconOrNull(item.id) ?: Circle` | 同 GlassNavBar（20dp 槽内 18dp 矢量） |

MiuixShowcase 的 NavItem 夹具同步去掉了 CJK glyph 实参（`话/活/能/我`）——skin 渲染已按
id 映射矢量，glyph 成了死数据，喂 CJK 字形正是在为「汉字当图标」续命。

## 2. VadeIcons.kt 追加（仅追加，未改旧条目）

- `VadeIcons.Star = Tabler.Outline.Star`（收藏）
- `VadeIcons.Mic = Tabler.Outline.Microphone`（语音输入）
- `VadeIcons.Circle = Tabler.Outline.Circle`（中性占位圆，glyph 缺省的矢量兜底）
- `VadeIcons.navIconOrNull(id)`：FR-8.77 四目的地 id → 矢量映射（sessions→Chat、
  activity→Activity、capabilities→Grid、me→Person，未知 id → null）。NavItem 是 C-09
  冻结模型没有 icon 槽，skin 层又不许反向 import `destinations` 包（会成环），映射落在
  图标词表本身；三处导航渲染器共用。
- 三个 getter 全部在 `icons-tabler-outline-cmp-jvm-2.2.1.jar` 用 javap 核实
  （`CircleKt.getCircle` / `MicrophoneKt.getMicrophone` / `StarKt.getStar`，均挂 `Tabler$Outline`）。

## 3. 两处判断论证（+ 一处顺带）

### 3.1 ProfileScreen 的 `● $id` —— 选了方案 ①（拿掉 ●，靠样式表达）

- 三行选中态**已经**由 `BtnStyle.Primary`（accent 实底）与 `Secondary` 的对比表达，
  `●` 是冗余信号；删掉不损失任何状态信息。
- 这些 chip 是**文字按钮**（material/miuix/glass 真文案），不是图标位。给文字按钮塞
  图标反而把它变成图标按钮，偏离 §3「文字按钮只在例外允许」的原意。
- 若皮肤将来想加非颜色的选中线索，正解是给 `Skin.Button` 加 `selected` 参数/描边态
  （Skin.kt 不在本棒清单），而不是往字符串里拼字符。

### 3.2 MiuixShowcase —— 真文字标签（代码里零字符图标）

事实依据：`Skin.Button(text: String, ...)` 的签名在 Skin.kt（不在本棒允许清单），
**无法携带 ImageVector**；而字符图标被 §3 明令禁止。两条路都死，唯一不违反规则的走法
是真文字标签。showcase 的目的（五种 BtnStyle 的视觉差异对比）不依赖字符：Primary/Secondary/
Danger/Text 的差异在底色与描边，不在文案。顶栏两枚（原 `⋯`/`↑`）同理改「更多」「发送」。
MotionProbe 则不同——`RealRow` 是本棒文件里的私有模型，直接把 `icon: String?` 改成
`ImageVector?` 走 `VadeIcon` 渲染，字符上移为矢量，标题保持真文字。

## 4. 没换的几处及理由

| 位置 | 内容 | 不换理由 |
|---|---|---|
| search/SearchOverlay.kt:206 (现 209) | `"↑ ↓ 选择 · Enter 进入 · Esc 关闭 · ⌘K / Ctrl+K 再按一次也关"` | 键帽名。↑↓ 与 Enter/Esc 同类，是**文本**不是图标；父棒的新守卫清单也没列它 |
| demo/MotionProbe.kt:320 | `RealRow("σ", "0.55 × radius")` | σ 是数学符号作为**值标签**（标准差），× 是乘号——数据文本，不是图标 |
| shell/AppShell.kt ×5、shell/FloatingBottomBar.kt ×1 | `←`/`▸◂`/`×`/`⌕`/`✎`/`•` | **不归本棒**（指令明确），留待 AppShell/FloatingBottomBar 两个棒自己修 |
| BottomBarItem.glyph 字段本身 | legacy 字段保留 | FloatingBottomBar（别的棒在改）还在读它，删字段会编译碰到禁改文件；本棒所有渲染路径已不再读 glyph |
| 现分支 `IconGuardTest.kt` 的正则 | raw string `"""…"""")` 尾引号并入 pattern | **发现**：该写法使 pattern 末尾多出一个字面 `"`，正则实际只匹配**单行** `Text("X")`，跨行字形（如上面那条键位提示）全部漏扫。该文件禁改，此事实供父棒合新守卫时参考；本棒的源码扫描测试用运行期拼接的 pattern 避开了这个坑 |

## 5. 新增测试（13 条，`desktopTest/kotlin/vade/ui/icon/VectorIconSwapTest.kt`）

纯函数映射 3 条：navIconOrNull 四目的地映射与互异、未知 id 返 null、Star/Mic/Circle 非 null 且 Circle 不撞导航图标。

渲染节点树断言（runComposeUiTest，逐个禁字符断言 count=0）9 条：GlassNavBar、
MaterialSkin.BottomBar、MiuixSkin.BottomBar、VadeBottomBar（icon=null 走 Circle 兜底）、
SearchHost（覆盖层顶栏图标按钮存在 + 禁字符不出现，↑ 因键位提示豁免）、双皮肤
InputBar 语音按钮（语音输入 contentDescription ×2，◉/◍ 不出现）、MiuixShowcase
（真文案存在 + 禁字符不出现）、ProfileScreen chips（● 不出现）、MotionProbe
（矢量行渲染 + ●/◆/★ 不出现）。

源码级扫描 1 条：fixedFilesCarryNoGlyphTextLiterals——对本棒 11 个文件用运行期拼接的
pattern 扫 `Text("` / `UiText("` 起始的禁字符字面量（↑↓ 键帽、× 乘号、… 省略号不扫），
兜住懒列表未合成行。

调试注：MotionProbe 的节点树断言不能加 swipe+waitForIdle——探针常驻帧循环永不静止，
会把测试体卡满 1m 超时（UncompletedCoroutinesError，首轮跑过一次真实失败后移除）。

## 6. 边界自检

- `git status` 只含清单内文件：11 个 commonMain 文件 + 新测试目录；`AppShell.kt`、
  `FloatingBottomBar.kt`、`IconGuardTest.kt`、`chat/**`、`data/**`、`protocol/**`、
  `meta/**`、`model/**`、`usage/**`、`subagent/**`、`module/**`、`input/**`、
  `build.gradle.kts` 均未动。
- commonMain 无 JVM 专有 API（`java.io.File` 只出现在 desktopTest 源码集）。
- 未删任何既有测试/断言，未加 skip。
- 未 push、未合并。
