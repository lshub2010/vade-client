# §11 运动模糊 — 三端可行性验证（方向性版）

分支 `tmp/motion-probe`。只涉及 `vade/ui/motion/` + `vade/ui/demo/`，`chat/` `shell/` `skin/` 未动。

## 零、这一版改了什么

上一版有两个真缺陷，本版修掉：

| 缺陷 | 上一版 | 本版 |
|---|---|---|
| **各向同性失焦** | `makeBlur(sigma, sigma)`，两方向同半径，文字四周均匀发虚，像镜头失焦 | `makeBlur(radius×0.12, radius×0.55)`，**只沿 Y 拖影**（垂直滚动方向） |
| **半径过重 + 跳变** | 上限 10dp，过 1200dp/s 直接跳满，34sp 文字被抹成色块 | 上限 **6dp**，`1200→6000 dp/s` 连续线性映射，快时略糊、停下即清晰 |

核心证据在 §三：真实冻结帧的梯度特征与"人工合成方向性模糊"对照组在三位有效数字内重合，
与"各向同性模糊"对照组差一个数量级 —— 方向性是被**量**出来的，不是目测说的。

## 一、结论

| 端 | 编译 | 真窗口出图 | 方向性 | 判定 |
|---|---|---|---|---|
| Desktop (JVM/Skiko) | ✅ | ✅ 见 §三 | ✅ 已度量 | **真生效，且真方向性** |
| Wasm (CanvasKit) | ✅ `compileKotlinWasmJs` | ⚠️ 本版未重出图 | ✅ 同一份双 sigma 调用 | **代码路径正确，本版无运行时证据** |
| Android (API 31+) | ✅（上一版 assembleDebug 通过） | ❌ 未跑设备 | ❌ **各向同性**（平台限制） | **代码路径正确，未做运行时取证** |

三端都能实现运动模糊。桌面拿到了真实像素 + 方向性度量；Wasm 与 Android 只有编译证据。

## 二、构建与测试

```
export PATH="/d/Hermes/tools/gradle-9.6.1/bin:$PATH"
export GRADLE_USER_HOME=D:/Hermes/tools/gradle-home
```

| 命令 | 结果 |
|---|---|
| `:composeApp:desktopMainClasses` | BUILD SUCCESSFUL |
| `:composeApp:desktopTest --rerun-tasks` | BUILD SUCCESSFUL，**31 tests / 0 failures** |
| `:composeApp:compileKotlinWasmJs` | BUILD SUCCESSFUL（只编译，不跑 5 分钟的 webpack 打包） |

测试构成：`SearchFlowTest` 19 + `MaterialImportGuardTest` 2 + `MotionBlurMathTest` **10**
（上一版 6 条，本版 +4）。

`MotionBlurMathTest` 的 10 条：

| # | 用例 | 守的是什么 |
|---|---|---|
| 1 | `radiusIsZeroAtAndBelowTheTriggerVelocity` | v=0 / 1199 / 1200 都是 0 |
| 2 | `radiusIsContinuousAndLinearAboveTheTrigger` | **v=2400→1.5dp、v=3600→3dp、v=4800→4.5dp**（连续，不是跳满） |
| 3 | `radiusNeverExceedsTheDocumentedCap` | v=6000 / 50000 / 99999 / MAX 全钳在 6dp |
| 4 | `radiusGrowsMonotonicallyWithSpeed` | 0→8000 步进 50 单调不减且在 `[0,6]` 内 |
| 5 | `negativeVelocityIsTreatedAsSpeed` | 上/下滚同量；负速度不出负半径（Skia 拒收负 sigma） |
| 6 | `nonFiniteInputIsSafe` | NaN / ±Infinity → 0（NaN sigma 会把图层画成空白） |
| 7 | `blurIsDirectionalWithYAsTheDominantAxis` | **sigmaY > 4×sigmaX**，且两者都 > 0（本版新增） |
| 8 | `zeroRadiusIsFreeAndReturnsTheSameModifier` | `motionBlur(0f) === base`（`===` 断言，结构性保证零开销） |
| 9 | `sharedCurveIsTheSameFunctionTheActualUses` | 两个 actual 必须纯委托 commonMain 公式 |
| 10 | `documentedEndpointsMatchTheConstants` | KDoc 里的 1200→0、6000→6 与常量一致（本版新增） |

## 三、方向性度量（本版核心证据）

### 方法

对**列表区域**计算三个量（自动剔除标题栏与右侧 HUD 列；边界 `y0,y1,x0,x1 = 39,759,8,686`，
678×720）：

- `lap_var` — 4 邻域 Laplacian 方差。锐利边缘多则高，糊则趋近 0。
- `gx` — 水平梯度 `∂I/∂x`（Sobel）均方。**由竖笔画驱动**：字形竖笔的左右两侧。
- `gy` — 垂直梯度 `∂I/∂y` 均方。**由横边缘驱动**：笔画上下端与横笔。

沿 Y 的方向性模糊必然摧毁横边缘（`gy` 塌），竖笔侧面相对保留（`gx` 保持）。
各向同性模糊两者一起塌。所以判据是**比值 `gy/gx`**：与总能量损失无关，
方向性模糊让它显著下降，各向同性让它上升。

工具：`tools/motion-metrics.py`（度量）、`tools/motion-metrics-control.py`（合成对照组）。

### 三张图的数字

| 文件 | 触发 | HUD 读数 | lap_var | gx | gy | **gy/gx** |
|---|---|---|---|---|---|---|
| `freeze-0.png` | `--freeze-blur=0` | `0.0 dp` / `0 dp/s` / FROZEN | **1169.72** | 191.90 | 110.54 | 0.576 |
| `freeze-6.png` | `--freeze-blur=6` | `6.0 dp` / `0 dp/s` / FROZEN | **78.44** | 87.15 | 10.56 | **0.121** |
| `live-scroll.png` | `--autoscroll` | `5.9 dp` / **`5947 dp/s`** / SCROLLING | **83.29** | 92.75 | 10.88 | **0.117** |

相对锐利基线（`freeze-0` = 1.0000）：

| 文件 | lap | gx | gy | (gy/gx) 相对 |
|---|---|---|---|---|
| `freeze-0.png` | 1.0000 | 1.0000 | 1.0000 | 1.000 |
| `freeze-6.png` | 0.0671 | **0.4542** | **0.0955** | **0.210** |
| `live-scroll.png` | 0.0712 | **0.4833** | **0.0985** | **0.204** |

**读法**：垂直梯度能量只剩 **9.6%**，水平梯度能量还留着 **45.4%** —— 相差 **4.8 倍**。
各向同性模糊下这两个数应该接近。

### 对照组（让"方向性"可被推翻）

只看上面的比值还不够：文字本来就竖结构多于横结构（基线 `gy/gx = 0.576` 而非 1），
所以"gy 掉得多"本身不能证明什么。于是拿锐利基线 `freeze-0` 做**人工合成高斯模糊**，
只改一个变量 `sigmaX`：

| 用例 | lap_var | gx | gy | gy/gx | gx/gx0 | gy/gy0 |
|---|---|---|---|---|---|---|
| 锐利基线 | 1169.72 | 191.90 | 110.54 | 0.576 | 1.000 | 1.000 |
| **对照组·各向同性** (3.25, 3.25) | 1.53 | 5.69 | 6.62 | **1.163** | 0.030 | 0.060 |
| **对照组·方向性** (0.75, 3.25) | 77.74 | 87.08 | 10.50 | **0.121** | 0.454 | 0.095 |
| **真实 `freeze-6`** | 78.44 | 87.15 | 10.56 | **0.121** | 0.454 | 0.096 |

**真实帧落在方向性对照组上，不在各向同性对照组上**：

- `lap_var`：真实 78.44 vs 方向性 77.74（差 0.9%），vs 各向同性 1.53（差 51 倍）
- `gx`：真实 87.15 vs 方向性 87.08（差 0.08%）
- `gy`：真实 10.56 vs 方向性 10.50（差 0.6%）
- `gy/gx`：真实 0.121 vs 方向性 0.121（一致），vs 各向同性 1.163

不是"看起来像"，是**实测梯度特征与合成方向性模糊重合**。各向同性会给出 `gy/gx = 1.163`
（横竖能量相当，即失焦），实测是 0.121。

> 两组图内容相同（都是 item 01 起、同一滚动位置），所以"用 freeze-0 合成后比对 freeze-6"
> 是有效的等价检验，不是巧合。
>
> 注：本版 `lap_var` 与上一版 RESULT 的 1368.3 / 0.8 **不可直接比较** —— 上一版用固定裁剪（含标题栏），
> 本版按背景色自动检测列表区域并排除标题栏与 HUD。同一脚本下锐利 1169.72 → 模糊 78.44 仍有 **14.9 倍**差距。

### 逐张目视自查（三张都开图确认过）

- **`freeze-6.png`** — 字形**沿 Y 拉伸**成竖条：横笔被抹开、竖笔轮廓仍在，`MOTION BLUR` 依然认得出。
  右侧 HUD 全程锐利（它在 modifier 之外），证明模糊只作用于列表自身。
  HUD 里 `sigmaY = r×density×0.55` / `sigmaX = r×density×0.12 (token)` 两行自证系数；
  `REFERENCE` 条（固定内容、同 radius）也竖向糊掉，证明是 layer 在起作用。
- **`live-scroll.png`** — 真实滚动帧（非冻结）：`SCROLLING · live`，`5947 dp/s` → 映射到 5.9dp，
  说明 `LazyListState` 偏移 → dp/s → 半径 → 像素这条链是活的。
- **`freeze-0.png`** — 完全锐利，`0.0 dp`，`REFERENCE` 条同样锐利（对照）。

## 四、映射曲线与方向性系数

### 速度 → 半径（`commonMain` 纯函数）

```kotlin
fun velocityToBlurRadiusDp(velocityDpPerSec: Float): Float {
    if (!velocityDpPerSec.isFinite()) return 0f              // NaN sigma 会把 Skia 图层画成空白
    val speed = abs(velocityDpPerSec)
    if (speed <= 1200f) return 0f                            // MOTION_BLUR_START_VELOCITY
    val t = (speed - 1200f) / (6000f - 1200f)                // MOTION_BLUR_FULL_VELOCITY
    return t.coerceIn(0f, 1f) * 6f                           // MOTION_BLUR_MAX_RADIUS
}
```

即 `radius = 6 × clamp((v − 1200) / 4800, 0, 1)`：

| v (dp/s) | 0 | 1199 | 1200 | 2400 | 3600 | 4800 | 6000 | 99999 |
|---|---|---|---|---|---|---|---|---|
| radius (dp) | 0 | 0 | 0 | 1.5 | 3 | 4.5 | 6 | 6 |

**为什么连续而不跳变**：一次 fling 会反复穿过 1200 dp/s。若过阈值就跳满，采样速度在阈值附近
抖动时列表会在"锐利/全糊"之间频闪。线性连续映射让半径跟着速度走。
**为什么线性而不缓动**：§11 要求"静止时严格为 0"；缓动曲线会在手指已停后仍留软像素。

### 方向性系数（`commonMain` 常量，两个 Skiko actual 共用）

```kotlin
const val MOTION_BLUR_SIGMA_X_PER_RADIUS = 0.12f   // token 轴
const val MOTION_BLUR_SIGMA_Y_PER_RADIUS = 0.55f   // 主轴
```

- **`0.55`**：6dp 上限时 `sigmaY ≈ 3.3px`（density 1）。物理上运动模糊的边缘是一条
  `speed × exposure` 长的盒子，高斯取 `box/3.5` 是常用的视觉替代，所以这是**观感值，不是物理量**。
  按 `freeze-6.png` 目视校准：34sp 等宽字变成竖条但轮廓仍可读；旧的各向同性 `sigma = 5px` 会抹平。
- **`0.12`（不取 0 的理由）**：`makeBlur` 把 0 sigma 当"该轴不模糊"，配合 `FilterTileMode.CLAMP`
  会在图层边界留下硬接缝，竖笔看起来像被刀切。0.12 只够给那条边抗锯齿（6dp 时 `sigmaX ≈ 0.7px`），
  远不足以读成失焦。
- 比值 `0.55 / 0.12 = 4.6×`，由 `blurIsDirectionalWithYAsTheDominantAxis` 断言 `> 4×` 守住 ——
  这两个数一旦趋同，效果就退回各向同性失焦，也就是这次要修的缺陷。

### 实现路径（三端）

| 端 | 路径 | 方向性 |
|---|---|---|
| Desktop / Wasm | `drawWithContent` + `drawIntoCanvas { it.nativeCanvas }` + `canvas.saveLayer(null, paint)`，paint 带 `ImageFilter.makeBlur(sigmaX, sigmaY, FilterTileMode.CLAMP)` | ✅ 双 sigma |
| Android 31+ | `Modifier.blur(radiusDp.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)` | ❌ 单半径，见 §六 |

`saveLayer` 是有效路径：Skia 在 `restore()` 合成图层时应用 layer paint 的 image filter，
被包裹的整棵子树作为一张图被模糊，且不改布局、不改命中区域（§11 要求）。
`Unbounded` 而非默认 `Rectangle`：滚动列表被父级裁剪，模糊必须允许溢出节点边界。

**明确排除的方案**：
- `graphicsLayer { renderEffect = ... }` — Compose 里 `RenderEffect` 是 Android 专属，桌面/Wasm 无此参数。
- 先画进 `Picture`/`Image` 再带滤镜绘制 — 每帧多一次全尺寸位图分配。
- `Modifier.Node` / `DrawModifierNode` — 能跑（同一个 `saveLayer`），但要写 `onDetach` 释放原生句柄。
- `ImageFilter.makeMatrixConvolution`（真正的方向性卷积核）— 物理上更诚实，但它是
  `width × height` 个系数逐像素求值、没有可分离快路径，代价远高于两个一维高斯，而 6dp 下看不出差别。

## 五、帧率（桌面，本版重测）

`--fps` 模式：自动锯齿滚动 5 秒，逐帧 `withFrameNanos` 采样：

```
FPS_RESULT blur=on  frames=490 avgMs=10.21 p95Ms=11.05 maxMs=76.78 fps=97.9
FPS_RESULT blur=off frames=501 avgMs=9.99  p95Ms=10.65 maxMs=23.03 fps=100.1
```

- 平均帧时间 **10.21 ms vs 9.99 ms**，模糊开启只多 **0.22 ms（+2.2%）**
- 相对帧率 **97.8%**，高于 §11 要求的 80% 下限
- p95 几乎相同（11.05 vs 10.65）
- `maxMs` 尖峰 76.78 ms 出现在**开启**那次，是 JIT/字体加载后的偶发；p95 不受影响，不足以判定为模糊导致

比上一版（97.3 / 99.5）略好，与"上限 10dp→6dp、sigma 变小"一致 —— 更小的核更便宜。
注意：这是桌面（Skia + GPU）的数字。§11 里"低于无模糊版 80% 自动关闭"的规则针对 Wasm，
本次**没有**在浏览器里做 5 秒帧率测量。

## 六、已知限制（如实记录）

1. **Android 是各向同性，不满足 §11 的方向性要求。** `Modifier.blur` 只收一个半径，
   Compose 在任何 API level 都没有按轴分开的版本（`RenderEffect.createBlurEffect(sx, sy, CLAMP)`
   有双 sigma，但只能经 graphicsLayer 的 `RenderEffect` 使用，`Modifier.blur` 不提供这个入口）。
   所以 Android 上观感更接近轻微失焦而非竖向拖影。补齐需要一条
   `graphicsLayer { renderEffect = RenderEffect.createBlurEffect(sx, sy, CLAMP) }` 的 API 31 分支 ——
   **本版没写**，因为无设备可验证，写了也只是猜。
2. **Android 没有运行时证据。** 上一版 `assembleDebug` 通过、APK 生成，但本机无 Android 31+
   设备/模拟器，`Modifier.blur` 的实际出图未验证；本版未重跑 `assembleDebug`（矩阵不变）。
3. **Android <31 直接降级为无模糊**，返回 `this`，不装 graphics layer。
4. **Wasm 本版未重出图。** `compileKotlinWasmJs` 通过，`MotionBlur.wasmJs.kt` 与 desktop 是同一套
   双 sigma 调用，但按任务范围没有重跑 webpack 打包与浏览器截图。
   `shots/motion/legacy-isotropic-10dp/` 里的 3 张 wasm 图是**上一版各向同性 10dp 构建**的产物，
   已移出主目录并改名标注，**不能**当作本版证据。
5. **Wasm 帧率未测。** §11 要求 Wasm 帧率低于无模糊版 80% 时自动关闭并记 warning ——
   该自动降级**尚未实现**，目前只有 `blur=off` 手动开关。
6. **`0.55` / `0.12` 是目视 + 度量校准的观感值，不是物理推导。** 真实运动模糊的核是
   `speed × exposure` 长的盒子；高斯只是替代。跨端（Android vs Skiko）没有做像素级对齐。
7. **`SkiaBlurPaints` 是进程级单例缓存**，按量化后的 `(sigmaX, sigmaY)` 对复用 paint/filter
   （步长 0.25px）。最后一个 pair 活到进程退出，不释放 —— 单次有界分配，不是累积泄漏。
8. **模糊元素与 haze 玻璃层叠加未测** — §11 禁止这种叠加（性能），probe 里没有 haze，无法验证冲突。
9. **120ms 归零**在 demo 里用线性斜坡实现（`MOTION_BLUR_DECAY_MS`），没有自动化测试验证时序。
10. **`live-scroll.png` 抓到的是 `5947 dp/s`（超过 6000 的 99%），所以半径已接近上限。**
    该帧能证明"速度→半径"链条是活的，但**不能**单独证明中间档位（如 1.5dp / 3dp）在真实滚动中出现；
    中间档位由 `MotionBlurMathTest` 的连续性用例覆盖，不是像素证据。

## 七、复现

```bash
export PATH="/d/Hermes/tools/gradle-9.6.1/bin:$PATH"
export GRADLE_USER_HOME=D:/Hermes/tools/gradle-home

# 构建 + 测试
gradle :composeApp:desktopMainClasses :composeApp:desktopTest --rerun-tasks

# 取 classpath
gradle :composeApp:desktopMainClasses :composeApp:printRuntimeClasspath -q | tail -1 > cp.txt

# 冻帧截图
powershell -NoProfile -ExecutionPolicy Bypass -File tools/capture-motion.ps1 \
  -Java "C:/Program Files/Java/jdk-21.0.12/bin/java.exe" -ClassPathFile cp.txt \
  -OutFile shots/motion/freeze-0.png -FreezeBlur 0
# 换 -FreezeBlur 6 得方向性上限；-AutoScroll 得滚动实拍

# 帧率
java -cp "$(cat cp.txt)" vade.ui.MainKt --motion-probe --fps --autoscroll
java -cp "$(cat cp.txt)" vade.ui.MainKt --motion-probe --fps --autoscroll --blur=off

# 度量（需要 numpy + pillow）
python tools/motion-metrics.py shots/motion/freeze-0.png shots/motion/freeze-6.png shots/motion/live-scroll.png
python tools/motion-metrics-control.py shots/motion/freeze-0.png
```

桌面参数：`--motion-probe` / `--freeze-blur=<dp>` / `--blur=off` / `--autoscroll` / `--fps`。
现有分支（`--bar` `--dest` `--size` `--panel` / `--search`）未改动。

## 八、踩坑记录（给后续实现省时间）

1. `ImageFilter.makeBlur` 的 tile mode 参数名是 **`mode`**，不是 `tileMode`。
2. `-WindowStyle Hidden` 启动的窗口拿不到 Compose frame clock，`--autoscroll` 的驱动会停摆，
   截图读数变 `IDLE`。`capture-motion.ps1` 必须正常显示 + `ShowWindow(SW_SHOW)`。
3. `LazyListState` 的偏移必须按**行距**（`ITEM_HEIGHT + ITEM_SPACING`）算，只按 item 高度算会
   每行少 8dp，速度偏低。
4. 自动滚动驱动必须 `withFrameNanos` 逐帧推进；裸循环会测到协程调度速度而非合成器帧率。
5. `String.format` 是 JVM 专属，HUD 与 FPS 行在 `commonMain` 里手写格式化（同一份代码跑 Wasm）。
6. `+=` 在 `Float` 局部变量上被 Kotlin 解析成集合 `plusAssign`，必须写 `ema = ema + ...`。
7. **度量区域必须排除标题栏。** PrintWindow 抓的是整个窗口，标题栏里是**锐利**的系统文字；
   把它算进 Laplacian/梯度会把模糊图的数字抬高，甚至让"方向性"结论反转。本版改为按背景色
   （列表 `0xFF101114` / HUD `0xFF17191E`）自动检测边界。
8. **单看 `gy` 下降不能证明方向性。** 文字本身竖结构多于横结构（基线 `gy/gx = 0.576`），
   任何模糊都会让 `gy` 掉更多。必须做合成对照组（只改 `sigmaX`）才能把"方向性"和"文字内容特性"分开。
