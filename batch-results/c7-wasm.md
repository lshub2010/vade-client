# c7-wasm · Wasm 三修（canvas 焦点 / 浏览器后退 / 页面缩放）

分支 `tmp/c7-wasm`，只改 3 个文件，未 push，未跑 Gradle。

## 一、改了什么

| 文件 | 改动 |
|---|---|
| `composeApp/src/wasmJsMain/resources/index.html` | viewport 去掉 `maximum-scale=1`（保留 `width=device-width, initial-scale=1`），双指缩放交还浏览器 |
| `composeApp/src/wasmJsMain/kotlin/vade/ui/main.kt` | 新增 `findComposeCanvas/focusComposeCanvas/installCanvasFocus`，入口建完 viewport 后把键盘焦点交给 canvas |
| `composeApp/src/wasmJsMain/kotlin/vade/ui/shell/BackDispatch.wasmJs.kt` | `SystemBackHandler` 从空实现改为「浏览器后退 = 系统返回」：自有历史条目 + popstate → `dispatch()` |

## 二、根因与修法

### 1. 快捷键全哑（canvas 在 shadow root 内，没人 focus）

CMP 1.12 的 `ComposeViewport` 不把 canvas 放 body 下，而是：

```
body > div > div.shadowRoot > div > canvas
```

`document.querySelector('canvas')` 跨不过 shadow 边界，恒为 null；键盘监听挂在 canvas 上、
canvas 又带 `tabindex=0`，但加载后没有任何代码 focus 它 → 焦点停在 `document.body`，
keydown 到不了 Compose 焦点树，`⌘K` / `Esc` / `Tab` 全部无效，必须先点一下画布。

修法（`main.kt`）：

- `findComposeCanvas()`：先 `body.querySelector("canvas")`（CMP 若改回 light DOM 仍然对），
  再遍历 `body` 下的 `div`，取 `element.shadowRoot?.querySelector("canvas")`；
- `focusComposeCanvas()`：补 `tabIndex = 0`（防宿主页面/CMP 版本改掉）、`focus()`，
  判定成功用 `document.activeElement == canvas || canvas.matches(":focus")`
  —— shadow root 内的元素不会成为 `document.activeElement`（那是宿主 div）；
- `installCanvasFocus()`：canvas 是首帧才 attach 的，所以用 `requestAnimationFrame` 最多追 60 帧；
  另挂**一次性** `pointerdown` 兜底（部分环境下无手势的 `focus()` 被忽略），
  且只在 `activeElement` 是 null/body/html 时动手 —— 绝不从 Compose 自己的隐藏输入元素手里抢焦点
  （否则「第一次点输入框打不了字」）。

### 2. 浏览器后退直接退出应用

wasm 端原本没有返回挂点，唯一入口是 Esc。于是开了浮层按浏览器后退 = 离开整个应用。

修法（`BackDispatch.wasmJs.kt`）：把「有没有浮层」映射成**历史里恰好一条自有条目**。

- 栈深 0 → 1：`history.pushState(null, "", null)`（URL 不变，同文档条目）；
- 用户按后退 → popstate 落到这条 → `dispatch()` 关最上层，**不离开页面**；若还有层，立刻补一条；
- 栈空 → 把自有条目 `history.back()` 收回，用户的下一次后退仍然是离开页面（= Android `enabled=false`）。

**为什么不按栈深 push N 条**（与任务书的字面写法不同，理由已写进代码注释）：

1. 一次关多层（从深层浮层直接回首页）需要 `history.go(-N)`，而 `go(-N)` 只投递**一个** popstate，
   按深度记账的「自发 pop」计数器会永久多记 N-1 次，把用户之后的真实后退吞掉；
2. 浏览器对同一帧内连发的 `back()` 会合并/丢弃。

恒定一条 + 每次用户后退补一条，用户可见语义相同（三层浮层 = 按三次各关一层），但账目是 0/1，不会错乱。

**双重消费**：UI 自己关层时会 `history.back()` 收回条目，随后到达的 popstate 属于我们自己，
用 `selfPops` 计数屏蔽（`back()` 抛错则回滚计数，避免吞掉下一次真实后退）。
派发后还会 `resync++` 逼一次重同步 —— 层的 `onBack` 完全可能不关层（只退一层页面），
「还有层就还有条目可拦」必须自洽。

`file://` 等不透明来源下 `pushState` 会抛 SecurityError：整段 `runCatching` 兜住，
失败就当这一步没发生（Esc 照旧、后退照旧离开页面），不让异常把首帧打白。

## 三、验证

### 已做（真实证据）

1. **Kotlin/Wasm 编译**：本机禁止 Gradle，改用 Gradle 缓存里的
   `kotlin-compiler-embeddable 2.4.20` + `KotlinWasmCompiler`，以仓内同版本
   `kotlin-stdlib-wasm-js 2.4.20` 与 `kotlinx-browser-wasm-js 0.5.0` 为 `-libraries`，
   把两个文件里新增的 DOM/历史表达式逐字拷进探针编译：**0 error / 0 warning**。
   通过的点：`Element.shadowRoot`、`ShadowRoot.querySelector`、`Element.matches(":focus")`、
   `HTMLElement.focus()/tabIndex`、`document.activeElement/documentElement`、
   `NodeList.length/item(i)`、`as? HTMLElement`（这端不需要 `unsafeCast`）、
   `window.addEventListener("popstate", (Event) -> Unit)`、`history.pushState/back/length`、
   `requestAnimationFrame`。唯一需要 opt-in 的是 `pushState`（`ExperimentalWasmJsInterop`），
   已只给 `BrowserBack.sync` 加 `@OptIn`。
2. **浏览器跑返回栈状态机**：把 `BrowserBack` 逐行移植成 JS，在真实 Chromium 里用真历史跑场景
   （`harness.html`，仓外 scratch）：
   - 开 3 层 → 历史只多 1 条；连按 3 次后退 → `dispatch` 恰好 +3（每次一层），历史回到基线；
   - UI 主动关层 → 自发 popstate 被吃掉，`dispatch` 增量 **0**（无双重消费）；
   - 开层后立刻 UI 关层的竞态 → `dispatch` 增量 **0**；
   - 各场景结束时 `ownsEntry=false`、`selfPops=0`，账目无残留。

### 未验证（本机不可得，勿当成已验证）

- **没有跑 Gradle / 没有 wasm 产物**：`compileKotlinWasmJs` 与整包编译未在本机执行；
  编译结论只覆盖上面列出的表达式，不覆盖 `@Composable` ABI（`LaunchedEffect` 以
  `depth`/`resync` 为 key、`rememberUpdatedState` 的读取时机等按仓内既有写法对齐）。
- **canvas 焦点与 `⌘K` 端到端未在真实 wasm 构建里验证**（需要构建产物 + 浏览器）。
- `matches(":focus")`、`shadowRoot` 的**运行时**存在性未在本机实测（只做了编译验证 +
  上一轮的浏览器实查 `element.shadowRoot` 存在）。
- 移动端 Safari 的 `pointerdown` + `focus()` 组合未实测。

## 四、给下一棒 / 父代理

- CI 第一跑只需看 `compileKotlinWasmJs`；若红，最可能是 `@OptIn(ExperimentalWasmJsInterop::class)`
  在该 Kotlin 版本上的注解路径（本机以 2.4.20 stdlib 实测存在）。
- 前端真验（人工）：开搜索浮层 → 浏览器后退 → 只关浮层不离开页面；再后退 → 离开；
  加载后不点鼠标直接按 `⌘K` / `Ctrl+K` 应打开搜索；两指缩放应可用。
