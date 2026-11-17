# i2-bridge · wasm 历史桥：看门狗 / 后退后不 push / 帧后判定

分支 `tmp/i2-bridge`（基于 `tmp/c7-wasm`，仅本地提交，未 push）。本批**没有跑 Gradle**：
结论是「纯函数 + 静态类型检查 + 逻辑走查」，不是测试运行结果 —— 末尾「验证」一节写明
跑了什么、没跑什么。

改动面严格三项：`BackDispatch.kt`（commonMain，纯函数）、`BackDispatch.wasmJs.kt`（actual）、
`desktopTest/.../HistoryMirrorDecisionTest.kt`（新增）。

## 一、改了什么

### P0-1 · `pendingClose` 看门狗 + `go(-k)` 夹取 + 落地校验

`history.go` 不抛错，也可能**什么都不做**（越界 / 被嵌入策略吞掉 / 页面正在卸载）。
`pendingClose` 一直挂着时，`reconcile` 里「在途就别再动历史」的短路会让桥永久卡死：
之后开层不压条目、关层不收条目，后退静默退化。三处收口：

- **看门狗**：每次发 `go` 都装 `rAF×2`；两帧后 `pendingClose` 还在 → 清空 + `degrade("go 未落地")`
  （一次性 `console.warn`：历史账目不可信，退回「Esc 才是返回」的诚实状态）。
  令牌（`watchdog`）在落地 / 发新 go 时自增，过期回调直接丢。
- **夹取**：`clampSteps(k, history.length)` = `k.coerceIn(0, length-1)`。夹到 0 时**不发** `go`
  —— 注定落不了地的 go 只会招来看门狗。启动清理那条 `go(-stale)` 同样走这个夹取。
- **落地校验**：popstate 里读 `event.state` 与 `history.state` 两处，只有两处一致才认同。
  不一致 ⇒ `landed = -1` ⇒ `decide` 返回 `RESYNC`，按浏览器此刻说的值重建
  `historyDepth` 再收敛。

顺手修掉一个会把功能打死的坑：**基座条目本来就没有 state**，退到它上面 `vade` 读出来是
`-1`。若把「读不出」一律当作「不是我们的」，每次退到基座都会被吞（浮层永远关不掉）。
现在 `raw < 0 && actual < 0` 折算成深度 0。

### 后退之后不 push（back-trap）

Chromium 的 history intervention：同一文档里后退之后又 `pushState` 出来的条目会被打上
`should_skip_on_back_forward_ui_`，后退按钮整条跳过它、连 popstate 都不投递。

- `decide(...) == HOLD`（`landed < previous && desired >= previous`，即「后退了但栈一条没降」）
  ⇒ 不派发、不 push；
- 站在自有条目上（`landed > 0`）→ `replaceState({vade: desired})` 抬**当前**条目，
  模型继续按 `desired` 记账，物理条目欠一条；
- 站在基座条目上（`landed == 0`）→ **不抬**：基座下面没有条目，抬完下一次后退仍然直接
  离开页面，却把「还欠几条」的账抹平了。只记账；
- 欠的条目一律推迟到**下一次用户手势**（`pointerdown` / `keydown`）之后补 —— 监听常驻、
  行为一次性（`owedEntries` 归零后只剩 `reconcile`）。补的时候：抬过条目 → 补一条同深度
  的；没抬过 → 按台阶 `1..desired` 补回整摞。
- 收条目（`go`）不受这条限制：`go` 是后退，不踩启发式，所以欠账期间照样收（并等比减债）。

### P1-3 · 同一帧两次 popstate

`handlePopState` **只记账**（`pendingDispatch = landed` / `pendingPrevious` / `pendingExpected`），
派发留到 `rAF×2` 之后；`onDecisionFrame` 用**新的** `dispatcher.depth`（`depthNow` 读实时
`dispatcher.depth`，不是上次 `sync` 的旧值）问 `decide`：

- 首次派发不问 `decide`（一次后退一定先给栈顶一次机会）；
- 之后每帧复检：栈在缩但没到落点 → 再派发；一条没降 → `HOLD` 停手；已缩到落点 → 停。
- 于是「一帧两次 pop」= 关两层，而不是「只关一层」或「一次关光」。

### 启动清理 + `enabled` 语义

- `install()` 先把 `desired` 同步成 0，再 `discardStaleEntries()`：读 `vade`，`go(-steps)`
  带夹取、`pendingClose = 0` 认领、同样受看门狗与落地校验保护；读不出 / 夹到 0 时按 0 起算
  并 `warnOnce`。
- `enabled` 对齐 common 契约：**孤立子树（没有 `LocalBackDispatcher`）depth = 0**。
  此前 `dispatcher?.depth ?: if (enabled) 1 else 0` 会在预览 / 独立测试里凭空压一条历史
  条目，`dispatch` 又没人接 —— 后退被吞且看不出原因。现在 `dispatcher == null ⇒ 不镜像、
  不拦截`。

### 决策抽成纯函数（commonMain）

```kotlin
internal enum class BackAction { RESYNC, ADOPT, IGNORE, DISPATCH, HOLD }
internal fun decide(previous: Int, landed: Int, expected: Int?, desired: Int): BackAction
internal fun clampSteps(steps: Int, historyLength: Int): Int
```

`decide` 的参数是四个**独立**的量（`previous` = 上一轮派发时的栈深；`landed` = 落点，-1 =
模型不可信；`expected` = 在途 `go` 的期望；`desired` = **新的**栈深）。wasm actual 只剩读事件、
记账、执行 `pushState` / `go` / `replaceState`。

## 二、验证

**跑了什么**（不经过 Gradle）：

1. 用缓存里的 `kotlin-compiler-embeddable 2.4.20` 直接编译 commonMain 的
   `decide` / `clampSteps` + 新增的 `HistoryMirrorDecisionTest.kt`（`kotlin.test` 用一个
   20 行的最小 stub 顶替），再反射调用每个测试方法：**7/7 PASS**，其中
   `everyRowOfTheStateMachineHolds` 断言 14 行逐条命中，另外三条不变量是**铺开区间**
   （`previous 1..6 × landed × desired`）而不是抽样。
2. 用 Compose / DOM 的最小 stub 编译 `BackDispatch.kt` + `BackDispatch.wasmJs.kt`：
   除 stub 自身导致的 4 条（`@Composable` 缺 TYPE target、`remember` 重载歧义）与 2 条
   KMP `expect/actual`（单模块编译固有）外，**没有其它错误** —— 新增代码通过类型检查。

**没跑什么**（重要）：

- 没跑 Gradle（本批禁用）⇒ 没有 `desktopTest` 全量、没有 wasm 产物编译。
- 没有浏览器实跑 ⇒ popstate / `history.go` / back-trap 的真实时序**未在 Chromium 里验证**；
  `jsHistoryLength` 等 `@JsFun` 的互操作只能靠类型检查与既有写法（`window.requestAnimationFrame`
  在本文件外已有先例）。

## 三、已知边界

1. **抽屉在基座上不关层**：深度 1 → 后退落基座 → 抽屉只收自己、层还在 → `HOLD`，欠一条。
   补条目要等用户手势；若用户**紧接着再按浏览器后退**（页面上没有任何 pointerdown/keydown），
   仍可能离开页面。这是「后退后不许 push」的固有代价，不是本批能消掉的（唯一能消的办法是
   立刻 push，而那正是要修的 back-trap）。
2. **中段 `HOLD` 后有一次「空退」**：`replaceState` 把当前条目抬到 `desired` 后，
   补的那条同深度条目被按下时落点与 `previous` 相等 ⇒ `IGNORE`（什么都不关）。
   代价换来的是「历史条目数不再一边倒」，比踩 back-trap 好。
3. **后台标签页不跑 rAF**：帧决策与看门狗都挂在 `requestAnimationFrame` 上，标签页不可见时
   它们不推进；回到前台会补上（状态没有被破坏，只是延后）。
4. **`history.length` 的口径**：它是整个会话的条目数（含前进栈），不是「当前能退几步」。
   夹取只防「退到会话起点之前」；退过头时 `go` 静默无效 —— 由看门狗兜底降级。
5. 与终审措辞的一处**有意偏离**：`landed == 0` 时不 `replaceState`（见上文「站在基座条目上」）。
   原因是照字面抬基座会把「还欠着」的账抹平，反而让下一次后退直接离开页面。
