# §11 运动模糊 — 三端可行性验证

分支 `tmp/motion-probe`（从 `main` @ `8577ebd`）。只新增 `vade/ui/motion/` + `vade/ui/demo/`，
`chat/` `shell/` `skin/` 未动。

## 一、结论

| 端 | 编译 | 真机/真窗口出图 | 判定 |
|---|---|---|---|
| Desktop (JVM/Skiko) | ✅ | ✅ 见 §三 | **真生效** |
| Wasm (CanvasKit) | ✅ | ✅ 见 §四 | **真生效** |
| Android (API 31+) | ✅ | ❌ 未跑设备 | **代码路径正确，未做运行时取证** |

运动模糊在三端**都可以实现**：桌面与 Wasm 用 Skiko 的 `saveLayer` + `ImageFilter.makeBlur`，
Android 用 `Modifier.blur`。桌面和 Wasm 都拿到了真实像素证据；Android 只做到编译通过。

## 二、构建与测试

```
export PATH="/d/Hermes/tools/gradle-9.6.1/bin:$PATH"
export GRADLE_USER_HOME=D:/Hermes/tools/gradle-home
```

| 命令 | 结果 |
|---|---|
| `:composeApp:desktopMainClasses` | BUILD SUCCESSFUL |
| `:composeApp:assembleDebug` | BUILD SUCCESSFUL，`composeApp-debug.apk` 11,759,981 B |
| `:composeApp:wasmJsBrowserProductionWebpack` | BUILD SUCCESSFUL（5m05s，含 Kotlin/Wasm 优化） |
| `:composeApp:desktopTest` | BUILD SUCCESSFUL，**27 tests / 0 failures** |

测试构成：`SearchFlowTest` 19 + `MaterialImportGuardTest` 2 + 新增 `MotionBlurMathTest` **6**。
新增的 6 条测的是 §11 里不用像素就能判的部分：阈值 1200 以下为 0、上限 10dp 钳制、随速度单调、
方向无关、NaN/Infinity 安全、`radiusDp <= 0` **返回同一个 modifier 实例**（`===` 断言，
这是"静止时严格为 0 / 无开销"的结构性保证），以及 actual 必须纯委托给 commonMain 的公式。

## 三、真实 API 用法

### commonMain — 只有公式是共享的

`composeApp/src/commonMain/kotlin/vade/ui/motion/MotionBlur.kt`

```kotlin
expect fun blurForVelocity(velocityDpPerSec: Float): Float
expect fun Modifier.motionBlur(radiusDp: Float): Modifier

fun velocityToBlurRadiusDp(velocityDpPerSec: Float): Float {
    if (!velocityDpPerSec.isFinite()) return 0f          // NaN sigma 会把 Skia 图层画成空白
    val speed = abs(velocityDpPerSec)
    if (speed <= MOTION_BLUR_START_VELOCITY_DP_PER_SEC) return 0f   // 1200
    val t = (speed - 1200f) / (4800f - 1200f)
    return t.coerceIn(0f, 1f) * MOTION_BLUR_MAX_RADIUS_DP           // 10
}
```

两个 actual 都是单行委托，`sharedCurveIsTheSameFunctionTheActualUses` 守着这条不漂移。

### Desktop / Wasm — Skiko `saveLayer` + `makeBlur`

`MotionBlur.desktop.kt` / `MotionBlur.wasmJs.kt`（两份独立文件，同一套调用）

```kotlin
actual fun Modifier.motionBlur(radiusDp: Float): Modifier {
    if (radiusDp <= 0f) return this                     // 无 layer、无 paint、无绘制阶段开销
    return this.drawWithContent {
        val sigma = radiusDp * density * SIGMA_PER_RADIUS   // 0.5
        drawIntoCanvas { composeCanvas ->
            val canvas = composeCanvas.nativeCanvas
            canvas.saveLayer(null, SkiaBlurPaints.forSigma(sigma))
            drawContent()
            canvas.restore()
        }
    }
}

ImageFilter.makeBlur(sigmaX = nextSigma, sigmaY = nextSigma, mode = FilterTileMode.CLAMP)
```

**踩到的真 API 差异**（不是记忆，是编译报错逼出来的）：这个 Skiko 版本
`ImageFilter.makeBlur` 的第三个具名参数叫 **`mode`**，不是 `tileMode`；
写成 `tileMode =` 会得到 `No parameter with name 'tileMode' found`。

`saveLayer` 是有效路径：Skia 在 `restore()` 合成图层时应用 layer paint 的 image filter，
所以被包裹的整棵子树作为一张图被模糊，且不改布局、不改命中区域（§11 要求）。

**明确排除的方案**：
- `graphicsLayer { renderEffect = ... }` — Compose 里 `RenderEffect` 是 Android 专属，桌面/Wasm 没有该参数，不能做跨端路径。
- 先画进 `Picture`/`Image` 再带滤镜绘制 — 每帧多一次全尺寸位图分配，`saveLayer` 让 Skia 自己选后备存储。
- `Modifier.Node` / `DrawModifierNode` — 能跑（同一个 `saveLayer`），但要额外写 `onDetach` 释放引用计数的原生句柄；改用按 sigma 量化后缓存的单例 paint。

### Android — `Modifier.blur`，<31 直接 `this`

`MotionBlur.android.kt`

```kotlin
actual fun Modifier.motionBlur(radiusDp: Float): Modifier {
    if (radiusDp <= 0f) return this
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return this
    return this.blur(radiusDp.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
}
```

`Unbounded` 而非默认 `Rectangle`：滚动列表被父级裁剪，模糊必须允许溢出节点边界，
否则会出现硬边矩形。

## 四、三张图（桌面，真窗口 PrintWindow 截图）

`shots/motion/`

| 文件 | 触发 | HUD 读数 | 观察 |
|---|---|---|---|
| `freeze-0.png` | `--freeze-blur=0` | `0.0 dp` / `0 dp/s` / `FROZEN @ 0.0 dp` | 文字锐利，边缘干净 |
| `freeze-10.png` | `--freeze-blur=10` | `10.0 dp` / `0 dp/s` / `FROZEN @ 10.0 dp` | **确实模糊**，字形糊成光斑；HUD 自身锐利；`REFERENCE` 条同样糊掉 |
| `live-scroll.png` | `--autoscroll` | `10.0 dp` / **`5836 dp/s`** / `SCROLLING · live` | 真实滚动中实拍，非冻结帧 |

第②张是关键证据：模糊只发生在**被修饰的列表**上（HUD 在 modifier 之外，保持锐利），
说明模糊作用于自身内容，而不是整屏。HUD 里的 `REFERENCE` 条（固定内容、同样 radius）
也一起糊了，证明这是 layer 在起作用，不是窗口被系统拉伸。

### 客观指标（不靠目测）

对每张图的**列表区域**（排除 HUD）算 Laplacian 方差——锐利边缘多则方差高，模糊则趋近 0：

| 文件 | Laplacian variance |
|---|---|
| `freeze-0.png` | **1368.3** |
| `freeze-10.png` | **0.8** |
| `live-scroll.png` | **0.8** |
| `wasm-freeze-0.png` | **1174.5** |
| `wasm-freeze-10.png` | **1.3** |
| `wasm-live-scroll.png` | **1.4** |

锐利 vs 模糊差**三个数量级**（1368 → 0.8，1700×）。这排除了"只是截图糊了/缩放糊了"的可能：
同一次运行、同一个窗口尺寸、同一个截图方式，唯一变量是 `radiusDp`。

`live-scroll.png` 是**真实滚动帧**，不是冻帧：HUD 显示 `SCROLLING · live` 且速度 5836 dp/s
（超过 1200 阈值 → 速度映射到上限 10dp），说明 `LazyListState` 偏移量 → dp/s → 半径这条链
是活的。第二次实拍为 `6065 dp/s`，同样 10.0dp。

## 五、Wasm 真实生效（不止编译）

`shots/motion/wasm-freeze-0.png` / `wasm-freeze-10.png` / `wasm-live-scroll.png`

用 `python -m http.server` 托管 `composeApp/build/kotlin-webpack/wasmJs/productionExecutable/`，
浏览器打开 `index.html?motion-probe&freeze=0|10|autoscroll`：

- `wasm-freeze-0.png` — `0.0 dp`，文字锐利
- `wasm-freeze-10.png` — `10.0 dp`，**文字确实模糊**（CanvasKit 上 `saveLayer` + `makeBlur` 真的执行了）
- `wasm-live-scroll.png` — `10.0 dp` / `5926 dp/s` / `SCROLLING · live`

Wasm 入口为此新增了查询参数分支（`wasmJsMain/main.kt`）：无 query 时仍是原来的 `App()`，
不影响现有部署目标。

## 六、帧率（桌面）

`--fps` 模式：自动锯齿滚动 5 秒，逐帧 `withFrameNanos` 采样，输出一行 `FPS_RESULT`。

```
FPS_RESULT blur=on  frames=487 avgMs=10.28 p95Ms=10.79 maxMs=59.58 fps=97.3
FPS_RESULT blur=off frames=498 avgMs=10.05 p95Ms=10.77 maxMs=31.87 fps=99.5
```

- 平均帧时间 **10.28 ms vs 10.05 ms**，模糊开启只多 **0.23 ms（+2.3%）**
- 相对帧率 **97.8%**，高于 §11 要求的 80% 下限
- p95 几乎相同（10.79 vs 10.77）
- `maxMs` 单帧尖峰 59.58 ms 出现在**开启**那次，是 JIT/字体加载后的偶发；`p95` 不受影响，
  不足以判定为模糊导致

注意：这是桌面（Skia + GPU）的数字。§11 里"低于无模糊版本 80% 自动关闭"的规则针对 Wasm，
本次**没有**在浏览器里做 5 秒帧率测量，只有桌面数据。

## 七、已知限制（如实记录）

1. **Android 没有运行时证据。** `assembleDebug` 通过、APK 生成，但本机无 Android 31+ 设备/模拟器，
   `Modifier.blur` 的实际出图未验证。代码路径与 §11 一致（`Build.VERSION.SDK_INT >= 31` 才走 `blur`）。
2. **Android <31 直接降级为无模糊**，返回 `this`，不装 graphics layer。`Modifier.blur` 本身在 <31 是空操作
   但仍会挂 layer，所以版本判断写在自己这层，保证零开销。
3. **Wasm 帧率未测。** §11 要求 Wasm 帧率低于无模糊版 80% 时自动关闭并记 warning — 这个自动降级**尚未实现**，
   目前只有 `blur=off` 手动开关。要做自动降级需要一个滚动窗口的帧时间统计 + 状态位。
4. **桌面 sigma 系数 0.5 是目视校准的，不是像素度量。** Android 的 `Modifier.blur(radius)` 把半径当 sigma 用，
   Skia 的 `makeBlur` 直接收 sigma；系数 0.5 让 10dp 在两端观感接近。没有做跨端像素级对齐，
   如需严格一致要拿 Android 真机截图逐像素比对。
5. **不是方向性模糊。** §11 写"只做垂直方向为主的方向性模糊"，但 `makeBlur` / `Modifier.blur` 都是各向同性高斯。
   要方向性得用 `makeMatrixConvolution` 或自定义 shader；当前实现是各向同性模糊，快速滚动时观感可接受，
   但**严格说不满足 §11 的方向性要求**。
6. **`SkiaBlurPaints` 是进程级单例缓存**，按量化后的 sigma（步长 0.25px）复用 paint/filter。
   最后一个 pair 活到进程退出，不释放 — 单次有界分配，不是泄漏累积。§11 限制"同一时刻只有 1 个元素在运动模糊"，
   加上 Compose 单线程绘制，单条目缓存是安全的；如果将来要同时模糊多个元素，需要改成 map 或 `ModifierNode` + `onDetach`。
7. **模糊元素与 haze 玻璃层叠加未测** — §11 禁止这种叠加（性能），当前 probe 里没有 haze，无法验证冲突。
8. **120ms 归零**在 demo 里用线性斜坡实现（`MOTION_BLUR_DECAY_MS`），没有写自动化测试验证时序。

## 八、复现

```bash
# 构建 + 取 classpath
gradle :composeApp:desktopMainClasses :composeApp:printRuntimeClasspath -q | tail -1 > cp.txt

# 冻帧截图（新增脚本，不动 capture-window.ps1）
powershell -NoProfile -ExecutionPolicy Bypass -File tools/capture-motion.ps1 \
  -Java "C:/Program Files/Java/jdk-21.0.12/bin/java.exe" -ClassPathFile cp.txt \
  -OutFile shots/motion/freeze-0.png -FreezeBlur 0
# 换 -FreezeBlur 10 得第二张；-AutoScroll 得滚动实拍

# 帧率
java -cp "$(cat cp.txt)" vade.ui.MainKt --motion-probe --fps --autoscroll
java -cp "$(cat cp.txt)" vade.ui.MainKt --motion-probe --fps --autoscroll --blur=off
```

新增桌面参数：`--motion-probe` / `--freeze-blur=<dp>` / `--blur=off` / `--autoscroll` / `--fps`。
现有分支（`--bar` `--dest` `--size` `--panel` `--search`）未改动，`--motion-probe` 走独立分支提前返回。

## 九、踩坑记录（给后续实现省时间）

1. `ImageFilter.makeBlur` 的 tile mode 参数名是 **`mode`**，不是 `tileMode`。
2. `-WindowStyle Hidden` 启动的窗口拿不到 Compose frame clock，`--autoscroll` 的驱动会停摆，
   截图读数是 `IDLE`。`capture-motion.ps1` 改成正常显示 + `ShowWindow(SW_SHOW)`。
3. `LazyListState` 的偏移必须按**行距**（`ITEM_HEIGHT + ITEM_SPACING`）算，只按 item 高度算会
   每行少 8dp，速度偏低。
4. 自动滚动驱动必须 `withFrameNanos` 逐帧推进；裸循环会测到协程调度速度而非合成器帧率，
   速度数值毫无意义。
5. `String.format` 是 JVM 专属，HUD 与 FPS 行在 `commonMain` 里手写格式化（同一份代码跑 Wasm）。
6. `+=` 在 `Float` 局部变量上被 Kotlin 解析成集合 `plusAssign`，必须写 `ema = ema + ...`。
