# RESULT — 清掉字符图标守卫的最后 4 处符号 + 「汉字当图标」

Worktree `D:/HermesWorkspace/Vade-client-wt/g-glyph-clear` · 分支 `tmp/g-glyph-clear`
基线：main @ **`f8e991a`**（788 tests）
代码 commit：**`c9d68d9`**（本文件为后续 `docs(shots)` 提交）
守卫 commit：**`b1d4f6a`** = cherry-pick `tmp/g-icon-guard@88616a1`（三个代码文件，**没有拿它的 shots**）

未 push、未合并。只用了 `git add` + `git commit`，没有 `--amend`/`rebase`/`reset`。

---

## 1. 14 处逐个分类（先判性质，再动手）

行号以 `f8e991a` 的实际内容为准。

| # | 位置（文件 : 符号/行） | 字面量 | 它现在是什么 | 应该是什么 | 处置 |
|---|---|---|---|---|---|
| 1 | `shell/AppShell.kt:272` `TopBarButton(glyph = …)` | `▸` / `◂` | **图标**：右面板开合按钮，`if (open) "▸" else "◂"`，44dp 触控目标里 16sp 居中、旁边没有文字 | `ImageVector`（两态） | **换** → `ShellGlyphs.PanelCollapse` / `PanelExpand` |
| 2 | `shell/AppShell.kt:380` `skin.Button("×")` | `×` | **图标**：右面板关闭按钮（`BtnStyle.Text` 文本按钮里塞一个乘号） | `ImageVector` | **换** → `VadeIcons.Close` |
| 3 | `shell/FloatingBottomBar.kt:534` `text = item.glyph ?: "•"` | `•` | **图标兜底**：浮动胶囊图标槽在 `icon == null` 时画的圆点 | `ImageVector` | **换** → `bottomBarIcon()`（`navIconOrNull` → `Circle`） |
| 4 | `destinations/Destinations.kt:29` `Sessions(…, "话")` | `话` | **图标**：`Destination.glyph`，唯一读者 `shell/NavRail.kt:72` 把它画在 30dp 圆角框里（13sp 居中，下面才是文字标签） | 删除字段，画 `VadeIcons.Chat` | **换**（删字段） |
| 5 | `destinations/Destinations.kt:30` | `活` | 同上（`VadeIcons.Activity`） | 矢量 | **换** |
| 6 | `destinations/Destinations.kt:31` | `能` | 同上（`VadeIcons.Grid`） | 矢量 | **换** |
| 7 | `destinations/Destinations.kt:32` | `我` | 同上（`VadeIcons.Person`） | 矢量 | **换** |
| 8 | `skin/miuix/MiuixShowcase.kt:208` | `主` | **文字标签**：四种 BtnStyle 演示按钮**下方** 11sp 的样式名缩写（`MiuixTokens.tinySize`），不在图标槽 | 文字 | **留 + 白名单** |
| 9 | `skin/miuix/MiuixShowcase.kt:209` | `次` | 同上（次要样式） | 文字 | **留 + 白名单** |
| 10 | `skin/miuix/MiuixShowcase.kt:210` | `危` | 同上（危险样式） | 文字 | **留 + 白名单** |
| 11 | `skin/miuix/MiuixShowcase.kt:211` | `文` | 同上（文字样式） | 文字 | **留 + 白名单** |
| 12 | `skin/miuix/MiuixShowcase.kt:220` | `禁` | **文字标签**：同一行 disabled 按钮的说明文字 | 文字 | **留 + 白名单** |
| 13 | `demo/MotionProbe.kt:312` `RealRow("好", …)` | `好` | **文字标签**：探针夹具的一行**会话标题**（15sp 行首，隔壁行是「为什么」） | 文字 | **留 + 白名单** |
| 14 | （#4–#7 的调用点）`shell/NavRail.kt:72` | — | 它不是字面量，是**这 4 个汉字唯一的渲染点**：30dp 方块居中 = 图标 | 矢量 | 同 #4–#7，改写为 `VadeIcon` |

**怎么判的（拿不准的两处）**
- 「好」（#13）：不在导航/按钮里，是列表行的 `title`（`RealRow` 的参数名就是 `title`），15sp，和它相邻的 `RealRow("为什么", …)` 完全同形 → 会话标题，不是图标。
- 「主/次/危/文/禁」（#8–#12）：都在 `skin.Button(...)` **下面**的 `Text(...)` 里，字号 `MiuixTokens.tinySize`（11sp），且**按钮自身的文字**是「发送/添加/删除/更多」（2 字，不在任何图标位）。若它们是图标，按钮就没有可见文字了 —— 与 §3「图标即按钮」正好相反。
- 反面证据（确认 #1–#7 是图标）：#1 在 `TopBarButton` 的 `glyph` 形参上、44dp 见方居中、无相邻文字；#2 是 `BtnStyle.Text` 按钮的**全部内容**；#3 是图标槽的兜底分支；#4–#7 在 30dp 方块里居中、文字标签另有一行。

---

## 2. 换掉的 7 处（4 处符号 + 4 处汉字，共 7 个渲染点）

| 位置 | 换前 | 换后 | 图标语义 |
|---|---|---|---|
| `AppShell` 右面板开合 | `"◂"` / `"▸"`（16sp 文字） | `ShellGlyphs.PanelExpand` / `PanelCollapse` | Tabler `LayoutSidebarRightExpand` / `LayoutSidebarRightCollapse` |
| `AppShell` 右面板关闭 | `skin.Button("×")` | `VadeIcons.Close`（44dp 按钮 + 20dp 矢量 + `contentDescription = "关闭右面板"`） | Tabler `X` |
| `FloatingBottomBar` 图标槽兜底 | `item.glyph ?: "•"` | `bottomBarIcon(item)` = `icon ?: navIconOrNull(id) ?: Circle` | Tabler `Circle`（兜底）/ 真导航矢量 |
| `NavRail` 四个导航图标 | `Destination.glyph`（话/活/能/我，13sp 文字） | `VadeIcon(destination.icon, 20dp)` | 会话=`Chat`(MessageCircle)、活动=`Activity`、能力=`Grid`(LayoutGrid)、我=`Person`(User) |

**视觉可辨性**
- 导航栏：图标位仍是「30dp 圆角框 + 居中图形」，图形从 13sp 汉字换成 20dp 矢量（与固定底栏/浮动胶囊的 20dp 一致）；`label` 行原样保留，「会话/活动/能力/我的」一个字没少。
- 两态语义：
  - 开合按钮 = **两枚不同的矢量**（`panelToggleIcon(open)` 纯函数，test #4 钉死）。
  - 四个导航项 = 颜色两态（`primary` vs `onSurfaceVariant`，与原来一致）+ **新增** `selected`/`Role.Tab` 语义，使「选中」从只能靠字重和 16% 底色变成可断言（test #2）。
- 关闭按钮：视觉仍是 X，只是从「文本按钮里的一个乘号」变成 20dp 矢量图标，触控目标 44dp 未变。

**没往 `VadeIcons.kt` 加 val**：`ShellGlyphs.kt` 是新建文件、同包 `vade/ui/icon`（Tabler 命名空间仍只在 `icon/` 内，`businessCodeDoesNotImportTablerDirectly` 照旧守着）。`VadeIcons.kt` 一字未改（只读约束）。

---

## 3. 保留的 6 处（白名单，每条带理由）

`IconGuardTest.allowed`（`path + line + glyph` 三者全匹配才放行；对不上 → stale → 强制删）：

| # | 条目 | 理由 |
|---|---|---|
| 1 | `MiuixShowcase.kt:208` `主` | 演示页按钮下方的样式名缩写（主按钮），11sp 文字标签；g-glyph-clear 已核，不渲染在任何图标槽 |
| 2 | `MiuixShowcase.kt:209` `次` | 同上（次按钮） |
| 3 | `MiuixShowcase.kt:210` `危` | 同上（危险按钮） |
| 4 | `MiuixShowcase.kt:211` `文` | 同上（文字按钮） |
| 5 | `MiuixShowcase.kt:220` `禁` | 禁用态演示按钮的说明文字，11sp 文字标签 |
| 6 | `MotionProbe.kt:312` `好` | 探针夹具的会话标题（15sp 行首文字，隔壁行是「为什么」），演示用假数据；不是图标槽 |

白名单是**穷尽**的：`GlyphClearTest.wholeTreeScanFindsOnlyTheAllowlistedDemoLabels` 不过白名单扫全仓，断言 offender 集合**恰好**等于这 6 条（多一条少一条都红）。

---

## 4. 守卫：变绿 + 补了「单汉字当图标」判类

- 取 `tmp/g-icon-guard@88616a1`：`GlyphLiteralScan.kt`（词法扫描：剔注释/字符字面量/raw/`\uXXXX`，`Regex("^["+VOCAB+"]{1,4}$")` **运行期**拼 pattern）+ 它的 11 条匹配器自测 + 新版 `IconGuardTest`。**没有拿 `shots/g-icon-guard/**`**。
- **补的判类**：`VOCAB` 只管符号，`"话"`/`"活"`/`"能"`/`"我"` 因此被守卫整条漏掉——「汉字当图标」比字符图标更难看见（它读起来像文案）。新增 `SINGLE_CJK = Regex("[\\u4e00-\\u9fff]")` 判类（**整串只有 1 个汉字**）+ `cjkHits()`，与符号 offender 合成一张表，共用同一份白名单与 stale 检测。
  只认 1 个字：两字以上标签（`"会话"`/`"重试"`…全仓 100+ 处）不算；commonMain 的单汉字字面量总共只有 10 处，本棒清掉 4 处、白名单 6 处，**噪声为 0**。
- `Destinations.glyph` 的守卫从「存在时必须带 @Deprecated」升级为「**必须不存在**」。
- 结果：`IconGuardTest` 4/4 绿，`GlyphLiteralScanTest` 11/11 绿。

### 变异自验（都在真树上做，做完已还原）

| 变异 | 预期 | 实测 |
|---|---|---|
| 新增 `val symbol = "▸"` + `val cjk = "我"`（临时文件） | 两处都报 | **红**：`TempMutationProbe.kt:5 "▸"`、`:6 "我"`，并打印可直接粘贴的 `Allow(...)` 行 |
| 白名单 `MotionProbe.kt` 行号 312 → 313（现有清单） | 报 stale | **红**：`白名单已失效（字面量改掉或行号漂移）…[Allow(… line=313 …)]` |
| 还原后重跑 | 绿 | **绿** |

---

## 5. 测试（+11，共 810）

`composeApp/src/desktopTest/kotlin/vade/ui/icon/GlyphClearTest.kt`（11 条）：

| # | 断言 | 证据类型 |
|---|---|---|
| 1 | `navRailDrawsAVectorForEachDestination`：四项都有带 testTag 的 `VadeIcon` 节点（Image），且 `话/活/能/我` 一个都不作为文字出现 | 真渲染 / semantics |
| 2 | `navRailKeepsTheSelectedStateAfterTheVectorSwap`：恰好 1 项 `selected=true`（=活动）+ 3 项 `false` | 真渲染 / semantics |
| 3 | `destinationIconsAreFourDistinctVectors`：Chat/Activity/Grid/Person 四枚互不相同、非空、非兜底圆、与 `navIconOrNull` 一致 | 纯函数 |
| 4 | `panelToggleIsOneVectorPerState`：`open=false→PanelExpand`、`true→PanelCollapse`、两者不同 | 纯函数 |
| 5 | `panelToggleButtonRendersVectorsNotChevronCharacters`：两个按钮的内容描述都在，屏上无 `◂`/`▸` | 真渲染 |
| 6 | `bottomBarIconResolutionNeverReturnsACharacter`：icon → navIconOrNull → Circle 三条分支 | 纯函数 |
| 7 | `floatingBarItemRendersTheFallbackAsAVector`：`icon=null` 的条目渲出 `contentDescription=label` 的 Image，无 `•` | 真渲染 |
| 8 | `floatingBarItemUnknownIdDrawsTheCircleVector`：未知 id 仍渲 Image（Circle），无 `•` | 真渲染 |
| 9 | `clearedShellFilesCarryNoCharacterOrCjkIconLiterals`：AppShell/FloatingBottomBar/NavRail/Destinations 四个文件零字形字面量 | 源码扫描 |
| 10 | `wholeTreeScanFindsOnlyTheAllowlistedDemoLabels`：全仓 offender 恰为 6 条白名单，套白名单后 0 残余 0 stale | 源码扫描 |
| 11 | `singleCjkRuleOnlyFiresOnOneCharacterLiterals`：`"话"`/`"好"` 命中；`"会话"` 与注释里的单汉字不命中；符号判类未被污染 | 匹配器边界 |

---

## 6. 三目标门禁（原始数字）

```
desktopTest --rerun       810 tests, 0 failed, 1 skipped   BUILD SUCCESSFUL in 1m 24s
assembleDebug                                            BUILD SUCCESSFUL in 1m 31s
compileKotlinWasmJs                                       BUILD SUCCESSFUL in 48s
```

- 788 → 810 = **+22**：本棒 +11，守卫自测 +11（`88616a1` 带来的那 11 条）。
- 1 skipped = `VadeClientSmokeTest.attachSubmitAndLiveEventsAgainstARealCore`，**既有**环境门（`VADE_SMOKE_PORT`/`PAIR_CODE` 未设即 skip），本棒没加任何 skip。
- **已知抖动**：同一份代码的第一次全量跑出现过 1 红 —— `RealCoreUiE2ETest.realCoreSessionTitlesRenderInAppAndFixturesStayAway`，`ComposeTimeoutException: Condition still not satisfied after 45000 ms`（composition 的 `waitUntil`，loader 断言已在它之前通过）。单独跑该测试类 **57s 全绿**，紧接着的第二次全量 **810/810 绿** → 满负载超时抖动（与 `g-flake-gen` RESULT 记录的 RealCore E2E 族一致：空载 boot 8–11s，12 并发中位 26–32s）。**未改断言、未 skip、未调大超时。**

---

## 7. 需要下游接手的两件事（本棒无权改，故意留）

1. `shell/TopBar.kt:136` 的 `TopBarButton(glyph: String, …)` **过渡重载现在没有任何调用方**（原先的 `AppShell` 与 `SearchOverlay` 都已改成矢量）。删掉它属于 `TopBar.kt`（不在本棒可改清单）——留给母棒在合并时清。
2. `skin/BottomBar.kt:73` 的 `BottomBarItem.glyph` 与 `skin/Skin.kt:457` 的 `NavItem.glyph` 两个 `String?` 字段现在**无人读取**（渲染器全部走矢量）。它们是冻结模型的一部分，本棒不改；删字段是 C-09 模型的事。
