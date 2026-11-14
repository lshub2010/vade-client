# g-module RESULT — C-19 模块页宿主 + 能力页入口（RFC-0009 客户端侧）

分支 `tmp/g-module`（基于 `f189de2`，未 push、未合并）。commit：**`9bbc81c`**（代码）+ 本报告的提交。

范围：`vade/ui/module/`（6 文件新增）、`vade/ui/destinations/CapabilitiesScreen.kt`（改造）、
`vade/ui/icon/VadeIcons.kt`（仅追加 +22/-0）、desktopTest `vade/ui/module/`（8 文件、70 条）。

## 构建与测试（原始数字，本 worktree 实跑）

| 检查 | 命令 | 原始结果 |
|---|---|---|
| 桌面全量测试 | `gradle :composeApp:desktopTest --rerun` | XML 逐文件求和：**224 tests / 0 failures / 0 errors / 1 skipped**（最后一次实跑 XML 时间戳 20:25:50） |
| 其中新增模块页测试 | 8 个文件 | **70**（ActionArgs 11 + BindingResolve 12 + EntryRule 9 + HostComponentReject 7 + OfflineAndSubmit 4 + PageLimits 7 + PrimitiveParse 14 + ScriptKeyReject 6），全绿 |
| 其中既有测试 | ChatFlow 28 / IconGuard 4 / Markdown 13 / MaterialImportGuard 2 / MaterialSkinV2 5 / MotionBlurBox 12 / MotionBlurMath 10 / MotionBlurTriangle 13 / SearchFlow 19 / VadeClientBehavior 23 / VadeClientContract 24 / Smoke 1 | **154**，与分支基线一致，无一改动 |
| skipped 1 条 | `VadeClientSmokeTest` | 既有 `org.junit.Assume` 门控（无 `VADE_SMOKE_PORT` 即 skip），非本批引入 |
| Wasm 编译（commonMain 无 JVM API 验证） | `gradle :composeApp:compileKotlinWasmJs` | **BUILD SUCCESSFUL in 44s** |
| 变异自验 | 删掉解析器的宿主组件拒渲块 → 只跑 HostComponentRejectTest | **恰好 4 条变红**（messageBubble/inputBar/approvalDialog/bottomBarIsRejected），另 3 条绿；还原后 `git diff` 为空，全量恢复 224/0/0/1 |

## 白名单表（渲染期校验，rev 1）

| `component` 值 | 渲染落点（C-09 Skin） | 允许 props（封闭集） | 事件 | 备注 |
|---|---|---|---|---|
| `Button` | `Skin.Button` | `text` / `style` ∈ primary·secondary·danger·text / `enabled` | `click` | 离线 → enabled=false；请求未返回前的防连点随协议接线 |
| `TextField` | `Skin.TextField` | `label` / `placeholder` / `value`（**只接受 `{"input": id}`**）/ `multiline` / `max_length` ≤4096 | `change`（可省） | 只写本地 `$input` 内存，永不回写模块状态（N2） |
| `Card` | `Skin.Card` | `title` / `subtitle` / `elevated` | `click`（可省） | **唯一允许 children 的组件** |
| `ListItem` | `Skin.ListItem` | `title` / `subtitle` / `leading_icon`（图标枚举）/ `trailing_text` | `click` | 叶节点；`list` 模板内最常用 |
| `Switch` | `Skin.Switch` | `label` / `enabled` | `change` | 主值 `checked` 只来自 `data_ref` 绑定，**宿主不本地翻转**，翻转只发动作 |
| `ToolCallCard` | `Skin.ToolCallCard`（只读形态） | `tool` / `state` ∈ running·ok·failed·waiting_approval / `args_preview` ≤512 / `result_summary` | `click`（可省） | 不批 / 不重试 / 不取消 |
| `AttachmentChip` | `Skin.AttachmentChip`（只读） | `file_id` / `mime` / `name` / `state` ∈ validating·uploading·uploaded·failed | `click`（可省，**只能 navigate**，Q4） | 不能上传 |

**宿主组件（出现即整页拒渲，不是忽略，穷举测试覆盖）**：`MessageBubble` / `InputBar` / `ApprovalDialog` / `BottomBar` —— 错误卡点名组件并注明宿主集合（变异自验：删掉该块恰好 4 条测试变红）。

**节点类型白名单**：`column` / `row` / `tabs` / `list`（4 布局原语）+ `text`（叶）+ `component`（上表引用）。未知 `type`（`grid` / `stack` / `iframe` / **`webview`** 等）→ 整页拒渲。页面无 WebView、无脚本、无求值器（`commonMain` 源码可 grep 验证）。

**脚本键（任意层级、大小写不敏感，命中即整页拒渲）**：`script` / `on_load` / `onclick` / `eval` / `js` / `html` / `innerhtml` / `expression` / `lambda` / `template` 含 `${}`。`x-*` 前缀键完全忽略（不渲不求值不转发，§9.2）。

**动作白名单**：`navigate`（`module:<声明页>` / `session:current` / `session:<id>` / `settings:appearance|models|devices|about`）、`tools/call`、`ui/submit`（part 只允许 `type:"text"`）。其他 kind（`http.request` / `shell` / `state.set`）→ 拒渲；参数来源只允许字面量 / `{"bind"}` / `{"input", "as"?}`（`{"bind":"$env"}` / 嵌套对象 → 拒渲）。

## 限额表（§7.3；恰好达标渲染，超一即拒，不做截断渲染）

| 限 | 值 | 测试 |
|---|---|---|
| 嵌套深度 | ≤8（9 拒） | PageLimitsTest depthEight/depthNine |
| 节点总数 | ≤256（257 拒） | PageLimitsTest nodeCount256/257（1+15×17=256 vs 1+16×16=257 构造树） |
| 单节点 children | ≤32（33 拒） | PageLimitsTest children33 |
| 单段 text 字面量 | ≤4096 字符（4097 拒） | PageLimitsTest text4096/4097 |
| `args_preview` | ≤512 | 解析期校验 |
| `max_length`（TextField） | ≤4096 | 解析期校验 + 输入期不接受超长 |
| `list` 数据 | ≤512 项（超出按上限处理 + 提示），渲染窗口 ≤50 项/屏 | resolver |
| 动作数 | 每节点 ≤4、每页 ≤64 | 解析期校验 |
| 绑定路径 | ≤4 段、每段 `[a-z0-9_]{1,32}`；形式非法=解析期拒渲，根越界=渲染期空值+一次性提示 | BindingResolveTest |

## 接口设计（接真数据只需实现该接口）

```kotlin
interface ModulePageSource {                       // vade/ui/module/ModulePageSource.kt
    fun declarations(): List<ModuleDeclaration>    // manifest.ui.pages[] 快照（module.status 维护 online）
    fun status(moduleId: String): ModuleStatus     // online + version（module.status 事件）
    fun loadPage(moduleId: String, pageId: String): JsonObject?      // ui/page.get → result.schema（原样节点树）
    fun loadState(moduleId: String, instanceId: String, pageId: String): JsonObject? // ui/state.get → result.state
}
```

- 入口集合由 `ModulePageEntryRules.build(declarations)` 纯函数推导（FR-8.79 / 版本门控，A13），与 UI 解耦、直接可测。
- 协议层（w1-proto `VadeClient` 已有 `page.get` / `state.get` 方法与 `module.status` 事件）只需包一层该接口；接口是同步值语义，真实实现可在内部做缓存/挂起，调用方不关心。
- 三层渲染管线：**Parse**（JsonObject→类型化树，fail-closed）→ **Resolve**（树+PageEnv→ResolvedNode，绑定落地、visible 裁剪、item 作用域展开、动作可用性折叠进模型）→ **Render**（ResolvedNode→Skin 组件的机械映射，无决策）。Parse/Resolve 两层纯 Kotlin，桌面测试直接覆盖，不需要 Compose 测试装置。
- 能力页集成：`CapabilitiesScreen(contentPadding, pageSource = ModulePageSources.demo())` — 页内 `remember` 持有打开的入口，点开就地渲染 `ModulePageHost`（页内导航，不新增一级目的地，FR-8.77 四 tab 不变）。AppShell/Destinations 零改动。

## 入口规则（FR-8.79 / §8.2，EntryRuleTest 覆盖）

- 声明了 `ui.pages[]` 的模块 → 「模块页面」区块一张 `lg` 模块卡，卡内每页一行（页面标题 + 图标 + chevron）。
- 未声明 / 声明为空的模块 → **无分组、无行、无占位**；整库无页面时整个区块不渲染。
- `version > 1`（客户端支持集）→ 入口不出现 + 一行「模块 › 页面：此页面需要更新的客户端（vN）」。
- demo 夹具：`download`（2 页）/ `charts`（2 页）/ `notes`（无 pages → 无入口）/ `legacy`（version=2 → 隐藏说明）。

## 未接线清单（本轮内存示例树驱动，协议层由另一棒在改）

| 项 | 现状 | 接线点 |
|---|---|---|
| `loadPage` / `loadState` / `declarations` / `status` | 内存示例树（`ModulePageSources.demo()`） | 实现 `ModulePageSource`，包 `VadeClient.page.get/state.get` + `module.status` 事件 |
| `tools/call` 两阶段审批 | 动作解析+参数落地完成，点击经 `onToolCall` 回调出去（默认 no-op） | 宿主已留 `onToolCall(action, resolvedArgs)`；审批 UI 走 C-09 `ApprovalDialog`（§4 明文允许页面用） |
| `ui/submit` | part 解析 + 文本落地完成，点击经 `onSubmit(texts)` 回调出去 | 接既有 `ui/submit` 写路径（宿主填 session_id、生成 client_msg_id）；`requires:["session"]` 时按 `hasActiveSession` 禁用 |
| `navigate` 出页目标（`session:*` / `settings:*`） | 解析+枚举校验完成，经 `onNavigateOut` 回调出去 | 接 shell 路由（本轮不许改 shell/） |
| `navigate` 页内 `module:<page_id>` | **已接线**：宿主面包屑栈（≤3 层，第 4 次替换栈顶 §8.3） | — |
| 模块离线 / 状态不可用 | banner + 动作禁用 + 重试已实现；离线时保留结构、绑定置空（§5.4） | 数据源如实上报 `status()` |
| Switch 翻转后状态刷新 | 只发动作、不本地翻转（C-19 正确行为）；`ui/state.get` 轮询/事件刷新未接 | 随协议接线（进入页面 / online 变真 / 用户重试时取 state） |
| 深链 `vade://module/<m>/<p>` | 未做（§8.3 建议形态，不在 C-19 冻结范围） | U1 后续 |
| `page.state` 数组形态 | demo 的 list 数据用数组（RFC §4.6 示例即 `page.state.items` 数组，与 §5.1「扁平标量」表述有张力） | 以服务端 `modulepage/auth.py` / state 校验的实际放行形态为准，客户端 resolver 两种都兼容（非数组 → 空列表 + 空态文案） |

## 视觉与红线自查

- 圆角只用 token：`xs` 12 / `sm` 16 / `md` 20 / `lg` 24（模块卡）/ `xl` 28（页面外壳）/ pill（返回钮）；无 4–8dp。
- 阴影全部 `Modifier.layeredShadow(VadeShadows.card, shape, dark=…)`，未用 `Modifier.shadow`。
- 图标全部 `VadeIcons` 矢量（新增 PageList/PageChart/PageSettings/PageTool/PageFile，getter 经 jar `javap` 核实：`getList`/`getChartBar`/`getSettings`/`getTool`/`getFile`）；无字符图标、无 emoji（IconGuardTest 4 条全绿）。
- 业务代码零 `material3` import（MaterialImportGuardTest 全绿；模块页只走 `Skin` + foundation + `UiText`）。
- commonMain 无 JVM 专有 API（`String.format` / `Math.*` / `System.*` / `@Volatile` / `java.*` 零命中，日期格式化手工 padStart）；`compileKotlinWasmJs` 实跑绿。
- 未拍截图（并行批窗口禁令）；本批无 `Destinations.kt` / shell / build.gradle.kts 改动，未删任何既有测试或断言。

## 途中修掉的真 bug（防后人重踩）

1. `ParseContext.fail` 原返回 `Nothing?`：在表达式位置（如 `props[key]?.let { parsePropValue(...) }`）**静默返回 null 而不中止**，把 fail-closed 打穿。改为 `Nothing` + 抛 `PageRejectSignal`，`parse()` 捕获后出 `Rejected(reasons)`。
2. `Unit?` 返回类型的解析辅助函数被 JVM 校验器拒载（`VerifyError: Bad return type … java/lang/Void is not assignable to kotlin/Unit`，53 条测试连带崩）——三个辅助函数改返 `Boolean`。
3. `item.*` 是两段路径，字段在 `segments[1]`；按 `page.state` 的三段习惯写成 `[2]` 导致列表行全空。
4. `tabs` 子节点 `props.tab_title` 与子节点自身 props（如 column 的 `gap`）同居一个 props 对象——先剥 tab_title 再解析子节点，封闭集校验交给子节点自己的类型。
5. 节点的 `id` / `visible` 可缺省，缺失≠非法——`?: return null` 把「缺省」当成了「失败」，全树静默空拒；改为显式区分缺省与非法。
6. RFC §4.6 示例的绑定带同级 `format`（`{"bind":"item.progress","format":"percent"}`）——`PropValue.Bound` 补 format 字段并在 resolve 落地。
