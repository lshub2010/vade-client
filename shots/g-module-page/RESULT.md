# g-module-page · RESULT — 模块页面「点得开、渲染得出」的第一份证据

分支 `tmp/g-module-page`（本地，**未 push、未合并**）。

## 一句话结论

「一帧真 `ui/modules` 回包 → 能力页出现「回声」入口 → 点开 → 三行文字在语义树里」这条链
**已跑通两次**：一次用真回包形状 + 假传输（A 段，纯 JVM），一次用**真 core + 真 echo 模块 +
真 WS/HTTP**（B 段）。渲染器对 `props.text` / `icon` / `entry` 的处理与契约一致，**没有发现
需要修的渲染器缺陷，`commonMain` 一行未改**。

同时钉出两条**生产路径的真实缺口**（都在别的包里，本棒红线外，未修）：清单的第一次拉取
没人发起、页面体没人拉取。它们让「core 明明给了页、屏幕上却什么都没有」在今天必然发生——
详见 [缺口](#缺口三条都未修红线外)。

## 交付物

| 文件 | 作用 |
|---|---|
| `composeApp/src/desktopTest/kotlin/vade/ui/destinations/CapabilitiesModulePageOpenTest.kt` | **A 段** 3 条测试：正例（入口→点开→三行）+ 两条 fail-closed 反例 |
| `composeApp/src/desktopTest/kotlin/vade/ui/e2e/RealCoreModulePageE2ETest.kt` | **B 段** 1 条测试：真 core + 真 echo 模块 + 真 `ui/modules` + 真 `ui/page.get` |
| `shots/g-module-page/core_echo_launcher.py` | B 段启动器（真 core + 密钥带外信任 + echo 模块子进程），也可人工复跑 |

## A 段：真回包形状驱动（无网络）

装置三段，各自对应生产上的一个真对象：

| 测试里的替身 | 生产对应物 |
|---|---|
| `FrameSource`（返回一段常量 JSON） | `WireModuleCatalogSource { client.uiModulesRaw() }`（`data/RealSessionSource.kt:190`） |
| `ModulesLoader` | **同一个生产类**（`internal`，desktopTest 与 commonMain 同模块） |
| `WirePageSource` | `ui/page.get` → `RealModulePageSource.loadPage` 的缓存读法 |

关键点：`WirePageSource.declarations()` **不自己编**，直接读 loader 解码出来的那份快照，
于是「入口来自清单、正文来自页面通道」这条分工与生产一致。

输入是一帧与 core 实测回包逐字段同形的 JSON（`pages[]` 是 `ModulePageService.pages()` 的投影，
**不含 `schema`**，键是 `id` 不是 `page_id`）：

```json
{"revision":7,"modules":[{"module_id":"com.example.echo","instance_id":"main",
 "display_name":"回声模块","version":"1.0.0","online":true,
 "pages":[{"id":"echo","title":"回声","icon":"list","entry":"auto","version":1,
           "requires":[],"refresh_s":0}]}]}
```

页面体就是真 echo 模块 `manifest.json` 的 `ui.pages[0].schema`（`column` 包三个 `text`，
文字在 `props.text`，`column` 自己没有 `props`）。

### 断言清单

1. 首次拉取前是 `Loading`（不是「没有模块」）→ `loader.load()` → `Ready`，
   `moduleCount == 1`、**`pageCount == 1`**、`pages[0].title == "回声"`、`icon == PageIcon.List`；
2. 能力页上出现「回声」入口：模块名恰好两处（模块卡片 + 入口分组头），**可点节点恰好 1 个**
   （多出来就说明混进了示例树或占位行）；
3. 点**之前**三行文字 `assertDoesNotExist()`（证明是「点开」带来的，不是同屏巧合）；
   `performClick()` 后面包屑 `回声模块 › 回声` 出现，三行文字逐行 `assertExists()`，
   且 `页面无法渲染` / `页面不可用` 都不存在（「渲染成功」与「渲染一张错误卡」分得开）；
4. 反例（模块无 `ui` 块，`pages: []`）：`Ready`、`pageCount == 0`；模块卡片照常出现
   （`模块（1）· revision 3`、`com.example.echo · main · v1.0.0 · 0 页`），但
   `模块页面` 区块、入口、**整屏零可点节点**、占位/说明文案全部不存在（FR-8.79 fail-closed）；
5. 反例 2（两模块一页 + 一模块 0 页）：入口仍只有 1 个，`icon: "none"` → `PageIcon.None`
   （不降级成默认图标、不崩），`requires: ["session"]` → `requiresSession = true`，
   点开后页面顶部出现「需要先打开会话」一行说明（不是把页面藏起来）。

### 语义树原文（点开之后，`useUnmergedTree = true` 的裁剪）

```
Node #57 ... Text = '[回声模块 › 回声]'
Node #60 ... Text = '[这个页面由模块的 manifest 声明（C-03 ui.pages），不是客户端硬编码的。]'
Node #61 ... Text = '[模块没有 ui 块时，ui/modules 里一个入口都不会出现（FR-8.79）。]'
Node #62 ... Text = '[工具：echo / add / sleep / boom]'
```

反例那棵树上只有 `[模块]` / `[模块（1）· revision 3]` / `[回声模块]` / `[com.example.echo · main ·
v1.0.0 · 0 页]` / `[在线]` / `[技能]` / `[记忆]` —— **没有 `模块页面` 这一段**。

## B 段：真 core + 真 echo 模块（`RunComposeUiTest`）

链路每一节都是真的：

```
真 core（vade.app.App + uvicorn，随机端口，临时数据目录）
  → 模块密钥带外写进 whitelist（fingerprint = SHA-256(pub)[:16]，whitelist.add 自己校验）
  → 真 echo 模块子进程走 Noise IK 注册（module/register）
  → 浏览器 POST /ui/session 拿 cookie（pair_code 由启动器 mint）
  → WS ui/modules → ModulesLoader 解码 → Ready(pageCount=1)
  → 真 ui/page.get 取回 schema（4 节点 / 0 动作）
  → 组合的 CapabilitiesScreen 上出现「回声」入口 → 点开 → 三行文字
```

本次实跑日志（测试 stdout，进 JUnit XML 的 `system-out`）：

```
LAUNCHER> {"stage": "ready", "port": 49938, "pair_code": "…"}
LAUNCHER> {"stage": "module", "module_id": "com.example.echo", "instance_id": "main",
           "online": true, "display_name": "回声模块", "pages": 1,
           "page_ids": ["echo"], "ui_warnings": []}
g-module-page · catalog state before kick = Loading
g-module-page · ui/page.get → nodes=4 actions=0
```

点开后的语义树与 A 段同形（`回声模块 › 回声` + 三行原文），**外加一条横幅**：
`模块离线——显示最后一次成功渲染的结构，动作已禁用`。这条横幅是缺口 3 的直接症状（下面）。

启动器先把 **core 侧的事实**打出来（`pages` / `page_ids` / `online` / `ui_warnings`），
所以失败时「模块没注册」「注册了但 0 页（页面被校验剔掉）」「有页但客户端没显示」三种病
在日志里长得不一样，不用从画面猜。

### 一个真实踩到的坑（已修进启动器）

环境里残留 `VADE_PAIR_CODE`（本机 shell 就有）时，模块的 `run()` 会
`args.pair_code or os.environ["VADE_PAIR_CODE"]` → 走配对分支 → **exit 2**，报
`pairing is not available in vade-sdk 0.1`。看起来像「模块连不上」，实际是「它根本没走 IK」。
启动器现在显式清掉 `VADE_PAIR_CODE` / `VADE_CORE_URL` 再 spawn。

## 缺口（三条，都未修，红线外）

| # | 缺口 | 证据 | 后果 |
|---|---|---|---|
| 1 | **清单的第一次拉取没人发起** | `data/RealSessionSource.kt:190` 只 `ModulesLoader(...)`；`commonMain` 里 `.load()` 调用点数 = **0**（只在 desktopTest 里） | 能力页永远停在「正在拉取模块清单」，入口一个都不出（B 段实测 `catalog state before kick = Loading`） |
| 2 | **页面体没人拉** | `module/RealModulePageSource.kt:102` 的 `fetchPage(...)` 是 `suspend`；`commonMain` 里调用点数 = **0**；`ModulePageHost` 是同步值语义，自己不会发 `ui/page.get` | 即使入口出现，点开也是「页面不可用」卡 |
| 3 | **模块在线态没接到页面源** | `module/RealModulePageSource.kt:68` 恒返回 `ModuleStatus(online=false, version=null)`（注释说事件接线是宿主的事） | 页面顶部恒画「模块离线」横幅——**模块其实在线**（B 段语义树可见）。正确数据已经有了：`ModulesLoader.registry.status(moduleId)` 就是这次回包聚合出来的在线态 |

B 段用接口上的**公开**动作把 1、2 各补了一脚（`catalog.retry()`、
`pageSource.fetchPage(...)`），所以「剩下的只是传输」这句话现在**成立**——但它成立的
前提是有人去踩这两脚，而今天没人踩。3 未补（那会改生产代码）。

## 渲染器 ↔ 契约：逐项核对结果（无需修）

| 关注点 | 契约 | 实现 | 判定 |
|---|---|---|---|
| 文字位置 | `text` 节点用 `props.text` | `ModulePageParser.parseText`：`data_ref` 或 `props.text` 二选一，其它键按封闭集拒渲 | 一致（A/B 段三行原文即证） |
| `icon` 枚举 | `list\|chart\|settings\|tool\|file\|none`，默认 `none` | 入口侧 `ModuleRegistry.pageIcon`：六值映射，未知 → `None`；渲染侧 `PageIcon.None.vectorOrNull() == null` → 不画图标 | 一致，`none` 不崩（A 段反例 2 显式断言） |
| `entry` 字段 | `auto\|panel\|capabilities`，默认 `auto` | 解码器忽略该键；入口按 manifest 顺序全渲染 | 一致——RFC-0015 **Q4 明写**「v1 客户端先按 manifest 顺序全渲染，`entry` 的桌面/手机差异留 U2」 |
| 页 id 键名 | `id`（不是 `page_id`） | 解码器 `mustStr("id")` | 一致 |

**契约自身的一处不一致（记录，未改 contracts）**：`schema/manifest.schema.json` 的
`$defs.page.refresh_s` 是 `minimum: 1, maximum: 3600`，而 `schema/rpc.json` 的 `ui/modules`
写 `refresh_s: int >= 0 (default 0)`，真 core 对 echo 页实际给的是 **0**。客户端不消费该字段
（前向兼容），所以不影响渲染；但两个契约文件对同一字段的下界相反，值得契约 owner 收一下。

## 门禁（三目标，全绿）

```
GRADLE_USER_HOME=D:/Hermes/tools/gradle-home
gradle :composeApp:desktopTest --rerun            → BUILD SUCCESSFUL (1m58s)
gradle :composeApp:assembleDebug :composeApp:compileKotlinWasmJs → BUILD SUCCESSFUL (46s)
```

以 `build/test-results/desktopTest/TEST-*.xml` 的 `tests=` 求和为准：

```
files=107  tests=1122  skipped=1  failures=0  errors=0
```

- 新增测试全在其中：`CapabilitiesModulePageOpenTest` = 3/0/0，`RealCoreModulePageE2ETest` = 1/0/0；
- 唯一的 `skipped=1` 是既有 `vade.ui.protocol.VadeClientSmokeTest`（需要活服务，本棒未动）；
- `assembleDebug` 产出 `composeApp/build/outputs/apk/debug/composeApp-debug.apk`（22.3 MB）；
- `compileKotlinWasmJs` 真的执行了（`> Task :composeApp:compileKotlinWasmJs`），不是 SKIPPED。

## 复跑

```bash
# A 段（纯 JVM，无网络）
gradle :composeApp:desktopTest --tests 'vade.ui.destinations.CapabilitiesModulePageOpenTest'

# B 段（真 core + 真模块；路径可用 VADE_PYTHON / VADE_CORE_PKG_DIR / VADE_PROTOCOL_PY_DIR
#        / VADE_SDK_PY_DIR / VADE_ECHO_MODULE_SRC 覆盖）
gradle :composeApp:desktopTest --tests 'vade.ui.e2e.RealCoreModulePageE2ETest'

# 人工起一套（打印 READY / MODULE 两行后常驻，Ctrl-C 退出）
python shots/g-module-page/core_echo_launcher.py --data-dir /tmp/gmp/data \
  --core-dir <Vade>/core --protocol-dir <Vade>/protocol/core/python \
  --sdk-dir <Vade>/sdk/python --module-src <Vade-module-echo>/src
```

## 边界声明

- 只碰了 `composeApp/src/desktopTest/**` 与 `shots/g-module-page/**`；**`commonMain` 零改动**
  （没有渲染器缺陷可修）、`build.gradle.kts` / `contracts` / `skin` 未动；
- 未 push、未合并；无 `--amend` / `--rebase` / `--reset`；没有删测、skip 或弱化断言；
- 未用真机、未用模拟器。
