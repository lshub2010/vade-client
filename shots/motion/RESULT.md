# §11 运动模糊 — box / 三角核 / 方向性高斯，三核 A/B 实测（两轮）

分支 `tmp/motion-probe`。第一轮（commit `01653c8`）确定 box 为默认；第二轮（本版）加入
**三角核（Bartlett）**、**真实内容夹具**与**拍频度量**，用数据复核了第一轮的默认选择。

## 〇、第二轮：三角核 + 真实内容 + 拍频（本版新增）

第一轮出图后，父代理在 `ab-box-6.png` 上发现：HUD 的 `REFERENCE` 实心色块平滑无条纹，
但 8 行 `MOTION BLUR` 大字全是**回音状**（每字母上下叠 2–3 道等距横带）。同核同参数、
只有内容不同 —— 怀疑是核长 13px 与字母横笔间距 4–7px 的**拍频**。本版三个动作：

1. 新增 `KernelShape.Triangle`（`w[i] ∝ half+1−|i−c|`，即 box ⊛ box），三端可切换；
2. 探针内容改为**真实形态**（中英混排 3–60 字、13/15/20sp 混排、一条代码块、一条图标行），
   同一真实内容同半径出三图 `real-box-6 / real-triangle-6 / real-gaussian-6`；
3. 新增 `tools/motion-beat-metric.py`（拖影区域**垂直自相关** → 主周期/强度 + 接缝深度）。

### 〇.1 逐张开图（全部开图确认过）

- **`ab-triangle-6.png`（病理夹具，三角核）** — `MOTION BLUR` 仍可读；字母仍有上下两道亮带，
  但中缝比 box **软**、两端渐削。6× 放大对比：box 的中缝是硬边（两道亮带对比清晰），
  三角核的中缝被斜坡部分填平。HUD：`kernel = triangle 1x13 / Bartlett w ∝ half+1-|i-c| (box⊛box)`。
  小号 `REFERENCE` 字比 box 下**略糊**（小笔画特征被削顶，见 〇.4 代价）。
- **`real-box-6.png` vs `real-triangle-6.png`（真实内容，同 6dp）— 目视几乎不可分**：
  20sp 标题（新会话/构建产物/为什么）两者都可读；15sp 正文都被抹成灰条；代码行
  （`gradle :composeApp:desktopTest`）两者都半可读；都读作"快速滚动"，无回音感。
- **`real-gaussian-6.png`（真实内容，高斯）** — 明显更"雾"：正文更糊、`REFERENCE` 更软，
  偏"失焦"而非"在动"。
- **`real-freeze-0.png`** — 真实内容锐利基线（度量对照）。

### 〇.2 拍频度量（`tools/motion-beat-metric.py`）

方法：逐文本行取行剖面（对"有点亮的列"求行均值），在**行内**做归一化垂直自相关。
三个数：`acf2`（滞后 2px 自相关 = 拖影平台平坦度）、`period/strength`（自相关最深负谷的
滞后与幅度 = 亮带结构的间距与强度，即"叠影"的周期）、`seam`（中缝深度：两翼峰与中央谷
之比，"两道亮带之间黑缝"的直接读数）。开图定位：那些"横带"就是**字形自身的
帽线/暗中腰/基线**结构被 13px 核部分合并的产物 —— 34sp 字的中腰高度 ≈ 核长，是最坏共振点。

病理夹具（34sp，6dp 冻结）：

| 文件 | acf2 | 主周期 px | 强度 | seam | 判定 |
|---|---|---|---|---|---|
| `ab-box-6.png` | +0.630 | **12.0** | **0.736** | **0.027** | FAINT |
| `ab-triangle-6.png` | +0.383 | 11.0 | **0.465** | 0.010 | NONE |
| `ab-gaussian-6.png` | +0.417 | 11.0 | 0.569 | 0.003 | NONE |
| （对照）`freeze-0.png` 锐利 | −0.033 | 5.5 | 0.275 | 0.147 | ECHO（带结构的源头） |

**三角核把叠影强度砍 37%（0.736→0.465）、中缝砍 63%（0.027→0.010），三项里两项目视相关
指标优于高斯** —— 与理论一致（旁瓣 −13.3dB→−25.3dB，§三.6）。

真实内容（13–20sp 混排，6dp 冻结）：

| 文件 | acf2 | 主周期 px | 强度 | seam | 判定 |
|---|---|---|---|---|---|
| `real-freeze-0.png`（锐利） | −0.067 | 5.5 | 0.358 | 0.062 | FAINT |
| `real-box-6.png` | +0.321 | 6.5 | 0.627 | **−0.009** | NONE |
| `real-triangle-6.png` | +0.388 | 6.5 | 0.626 | **−0.000** | NONE |
| `real-gaussian-6.png` | +0.550 | 9.0 | 0.761 | 0.005 | NONE |

**真实内容上三种核的 seam 全部 ≈ 0（NONE）** —— 13–20sp 的字形中腰在 13px 核下被完全填平，
**第一轮看到的"回音"是病理夹具（34sp 等宽、行内暗腰 ≈ 核长）独有的共振，不是真实内容的
问题**。gaussian 的强度 0.761 偏高是单峰剖面的自相关谷（钟形拖影在自身宽度处反相关），
不是叠影 —— 它的 seam 同样 ≈ 0。box 与 triangle 在真实内容上**三项指标全部持平**
（0.627 vs 0.626 / −0.009 vs −0.000）。

### 〇.3 拖影剖面与细节保留（真实内容）

`motion-streak-profile.py`（真实内容；`d(profile)/dy` = 核的形状）：

| 文件 | ramp px | slope_cv | end_ratio | curv | 读数 |
|---|---|---|---|---|---|
| `real-box-6.png` | **13** | **0.000** | **1.000** | **0.000** | **PLATEAU**（完美平台） |
| `real-triangle-6.png` | 12 | 0.414 | 0.517 | 0.211 | 钟形**差分**（= 三角核本身，见下） |
| `real-gaussian-6.png` | 12 | 0.421 | 0.519 | 0.221 | BELL |
| `ab-triangle-6.png`（病理） | 11 | 0.364 | 0.529 | 0.224 | 同上 |

注意：三角核对阶跃的响应 `step ⊛ triangle` 的**一阶差分就是三角核本身**（先升后降的钟形），
所以分类器把它读成 "BELL" —— 这是**数学必然**，不是实现错；它与高斯的区别在
`MotionBlurTriangleKernelTest` 的频域断言（|三角| = |半长 box|²、旁瓣 −25dB）与拍频表，
不在 slope_cv。box 在真实内容上依然是唯一的完美平台（slope_cv 0.000）。

`motion-metrics.py`（lap_var，锐利基线 2129.25）：

| 文件 | lap_var | 相对锐利 | 说明 |
|---|---|---|---|
| `real-box-6.png` | 203.75 | 0.096 | 基准 |
| `real-triangle-6.png` | **243.37** | **0.114** | 比 box 多 19% 高频细节 |
| `real-gaussian-6.png` | 44.01 | 0.021 | **box 保留 4.6 倍细节于高斯** |
| `ab-triangle-6.png`（病理） | 364.48 | — | box 327.87 / 高斯 78.44（§三.2），三角最高 |

### 〇.4 帧率（四组，桌面 5 秒锯齿滚动）

```
FPS_RESULT blur=on  kernel=box      frames=493 avgMs=10.16 p95Ms=10.94 maxMs=65.09 fps= 98.4
FPS_RESULT blur=on  kernel=triangle frames=496 avgMs=10.10 p95Ms=11.03 maxMs=62.12 fps= 99.0
FPS_RESULT blur=on  kernel=gaussian frames=494 avgMs=10.13 p95Ms=11.04 maxMs=62.91 fps= 98.7
FPS_RESULT blur=off kernel=box      frames=501 avgMs= 9.99 p95Ms=10.91 maxMs=17.82 fps=100.1
```

| 组 | 平均帧时间 | 相对 off | 判定（≥80%） |
|---|---|---|---|
| **triangle** | **10.10 ms** | **98.9%** | ✅ 三者最快 |
| gaussian | 10.13 ms | 98.6% | ✅ |
| box | 10.16 ms | 98.3% | ✅ |
| off | 9.99 ms | 100%（基线） | — |

与预测一致：三角核与 box 是**同一个 1×13 卷积、只换权重**，成本相同（0.06ms 差异是噪声）。

### 〇.5 最终选择：**box 仍为默认；三角核保留为可选项**（任务分支 B）

1. **真实内容上没有任何核出现回音**（seam 全部 ≈ 0）—— 第一轮的"拍频"是病理夹具
   （34sp 等宽字、行内暗腰 ≈ 核长 13px）独有的共振。**"box 有回音"不成立。**
2. box 在真实内容上保留**完美平台剖面**（slope_cv 0.000）与 4.6 倍于高斯的细节，
   且帧率 98.3% of off —— 第一轮的结论全部维持。
3. 三角核**没有**在真实内容上明显好于 box（任务给"改默认"设的前提不触发）：
   三项拍频指标持平、目视不可分，且它放弃"精确物理积分"的语义、小字特征削顶
   （`REFERENCE` 变软）。**不设为默认。**
4. 但三角核在**大字/周期性内容**上真实减害（叠影强度 −37%、中缝 −63%、旁瓣 −12dB、
   成本 0）→ **保留为 `MotionBlurKernel.Triangle` 选项**，用于大号等宽/代码块/表格线
   等周期性内容占主导的列表（`--kernel=triangle` / `?kernel=triangle` 一行可切）。
5. 半径上限维持 6dp：真实内容上三个核都可读，无需降到 4dp。

## 零、第一轮改了什么（box vs 高斯）

上一版把**方向性**做对了，但**核的形状**是错的。本版修掉：

| 缺陷 | 上一版 | 本版 |
|---|---|---|
| **高斯不是运动模糊** | `makeBlur(0.12r, 0.55r)`：方向对了，但核是**钟形** —— 拖影两端渐隐，读作"垂直失焦" | `makeMatrixConvolution` 垂直 **1×N 矩形核**（权重全 `1/N`）：曝光时间内沿运动矢量的**积分**，拖影等强度、两端硬截断 |
| **box 被"未测即否"** | 上一版 RESULT 写"`makeMatrixConvolution` 才是诚实物理，但代价远高于两个一维高斯" —— **没测就否了** | 实测 box 帧率是 off 的 **98.4%**，高斯 97.7%。box **比高斯还略快**。原判断是错的 |

核心证据在 §三.1：拖影剖面的**一阶差分就是核本身**（`profile = step ⊛ kernel ⇒ d(profile)/dy = kernel`）。
box 的一阶差分是 **13 个 0.066~0.070 的常数**（平台），高斯是 **0.034→0.109→0.021 的钟**。
这不是"看起来像"，是从像素里把核反解出来了。

## 一、结论

| 端 | 编译 | 真窗口出图 | 核 | 判定 |
|---|---|---|---|---|
| Desktop (JVM/Skiko) | ✅ | ✅ 见 §三 | **box 1×N（默认）** | **真运动模糊，box 为默认** |
| Wasm (CanvasKit) | ✅ `compileKotlinWasmJs` | ⚠️ 本版未重出图 | 同一份 `makeMatrixConvolution` | **代码路径正确，无运行时证据** |
| Android (API 31+) | ✅（上一版 assembleDebug 通过） | ❌ 未跑设备 | **各向同性高斯（无核选择）** | **平台限制，见 §六.1** |

**最终选择：box 为默认（`DEFAULT_MOTION_BLUR_KERNEL = Box`），高斯保留为降级路径。**
（第二轮加入三角核后**维持不变**，复核证据见 §〇.5：真实内容上无回音、box 平台与细节
优势不变；三角核以可选项保留。）

理由不是"物理更诚实"这种形容词，是四条实测：

1. **观感**：box 的字仍然认得出，是"在动"；高斯的字互相渗透，是"没对上焦"（§三.4）
2. **剖面**：一阶差分是平台还是钟，`slope_cv` 差 **14 倍**（§三.1）
3. **细节保留**：同半径下 `lap_var` **327.87 vs 78.44**，差 **4.2 倍**（§三.2）
4. **帧率**：box **98.4%** vs 高斯 97.7%（相对 off），不降反升（§四）

## 二、构建与测试

```
export PATH="/d/Hermes/tools/gradle-9.6.1/bin:$PATH"
export GRADLE_USER_HOME=D:/Hermes/tools/gradle-home
```

| 命令 | 结果 |
|---|---|
| `:composeApp:desktopMainClasses` | BUILD SUCCESSFUL |
| `:composeApp:desktopTest --rerun-tasks` | BUILD SUCCESSFUL，第一轮 **43 / 0**，第二轮（本版）**56 / 0** |
| `:composeApp:compileKotlinWasmJs` | BUILD SUCCESSFUL（box+triangle 核代码在 commonMain expect 下编到 wasm 通过） |

测试构成：`SearchFlowTest` 19 + `MaterialImportGuardTest` 2 + `MotionBlurMathTest` 10 +
`MotionBlurBoxKernelTest` 12 + **`MotionBlurTriangleKernelTest` 13（第二轮新增）** = **56**（第一轮 43，+13）。

`MotionBlurBoxKernelTest` 的 12 条：

| # | 用例 | 守的是什么 |
|---|---|---|
| 1 | `sixDpAtDensityOneIsThirteenTaps` | **6dp@1x → 13 taps**，且 `N−1 = 12px` 拖影跨度 |
| 2 | `tapsAreAlwaysOdd` | 3 个 density × 全半径扫描，**N 恒为奇数**（Skia 拒收偶数维；偶数无中心抽头会半像素位移） |
| 3 | `tapsGrowMonotonicallyWithRadius` | 半径升 N 不减，且能到 6dp 上限 |
| 4 | `densityScalesTheSmearLength` | 核在**设备像素**：6dp@2x = 25 taps |
| 5 | `zeroAndNonFiniteRadiusDegradeToIdentity` | 0 / 负 / NaN / Infinity → 1 tap（单位核，不炸 Skia） |
| 6 | `weightsSumToOneSoBrightnessIsPreserved` | 权重和 = 1（`gain=1` 才不改变亮度） |
| 7 | `weightsAreUniformNotTapered` | **每个抽头等权** —— 这是 box 的定义性质，渐削就是高斯 |
| 8 | `boxProfileHasAFlatTopAndGaussianProfileIsBellShaped` | box 冲激响应恰好 N 宽且全等；高斯更宽、尾部 <2% 峰值 |
| 9 | `boxIsFlatterThanGaussianAcrossItsWholeSpan` | **CV：box = 0，高斯 > 0.3** —— "平台形 vs 钟形"的数值形式 |
| 10 | `boxAndGaussianDifferByMoreThanAScaleFactor` | 两者 Pearson 相关 < 0.9：是**形状**不同，不是强度不同 |
| 11 | `shippedDefaultIsTheBoxKernel` | 默认必须是 Box（本版决策被测试钉住） |
| 12 | `gaussianFallbackConstantsAreStillTheDirectionalPair` | 降级路径不能悄悄变回各向同性（`sigmaY > 4×sigmaX`） |

`MotionBlurMathTest` 的 10 条（曲线 / 方向性 / 零半径免费）不变，仍全绿。

## 三、A/B：同一半径（6dp）、同一 build、唯一变量是核

| 文件 | 字节 | md5 | 触发 |
|---|---|---|---|
| `shots/motion/ab-box-6.png` | **88 282** | `d77f3aa03bfb1ba4c5005d4a27ac8362` | `--freeze-blur=6`（默认 box） |
| `shots/motion/ab-gaussian-6.png` | **128 390** | `2c446f6783adaaa63bcff547421e37bb` | `--freeze-blur=6 --kernel=gaussian` |
| `shots/motion/live-scroll-box.png` | 81 389 | — | `--autoscroll`（默认 box，真实滚动） |

box 的 PNG 小 **31%**（88KB vs 128KB）。不是玄学：box 保留了更多硬边缘与高频，PNG 的预测器在
**硬截断**的拖影上比在渐隐的钟形上更省比特。这本身就是"box 更锐"的旁证。

### 三.1 拖影剖面（本版新增的第三个度量）

`tools/motion-streak-profile.py`。方法：取字形的一根**竖笔画**（连续多行点亮的列），沿该列向下
穿过被抹开的**水平边缘**（笔画顶端）。那个边缘近似一个**阶跃**，而模糊是卷积，所以

```
profile = step ⊛ kernel        ⇒        d(profile)/dy = kernel
```

**剖面的第一差分就是核，从像素里反解出来。** 于是：

- **box** → 线性斜坡后**骤然截止**，一阶差分是**平台**（N 个几乎相同的值）
- **高斯** → erf 形 S 曲线（两端缓、中间陡），一阶差分是**钟形**

#### `ab-box-6.png`（列 x=82）

前 20 个采样（峰值归一化亮度）：

```
0.119 0.119 0.185 0.255 0.321 0.391 0.457 0.527 0.593 0.663
0.728 0.798 0.864 0.930 1.000 1.000 1.000 1.000 1.000 1.000
```

一阶差分（= 核）：

```
0.066 0.070 0.066 0.070 0.066 0.070 0.066 0.070 0.066 0.070 0.066 0.066 0.070
```

**13 个值，全部落在 0.066–0.070 —— 常数斜率，这就是矩形核。** 且 `13 = N`（6dp@1x），
拖影跨度实测 **13 px**，与 `boxKernelTaps` 的预测**逐像素吻合**。

ASCII 剖面（每行 1 px）：

```
  0 |######..............................................| 0.119
  2 |##########..........................................| 0.185
  4 |#################...................................| 0.321
  6 |########################............................| 0.457
  8 |###############################.....................| 0.593
 10 |######################################..............| 0.728
 12 |#############################################.......| 0.864
 14 |####################################################| 1.000
 18 |####################################################| 1.000
 22 |####################################################| 1.000
 23 |####################################################| 1.000
 24 |################################################....| 0.930
 26 |##########################################..........| 0.798
```

等宽台阶（每行 +0.066），第 14 行**撞到 1.000 并平顶**，第 24 行开始**对称下降**。硬拐角，无渐隐。

#### `ab-gaussian-6.png`（列 x=95）

前 20 个采样：

```
0.130 0.143 0.160 0.193 0.244 0.315 0.408 0.508 0.618 0.723
0.811 0.882 0.933 0.966 0.987 0.996 1.000 1.000 1.000 1.000
```

一阶差分（= 核）：

```
0.034 0.050 0.071 0.092 0.101 0.109 0.105 0.088 0.071 0.050 0.034 0.021
```

**0.034 起，爬到 0.109 的峰，再落回 0.021 —— 标准钟形。** 两端小、中间大，就是"渐隐"。

ASCII 剖面：

```
  0 |#######.............................................| 0.130
  2 |########............................................| 0.160
  4 |#############.......................................| 0.244
  6 |#####################...............................| 0.408
  8 |################################....................| 0.618
 10 |##########################################..........| 0.811
 12 |#################################################...| 0.933
 14 |###################################################.| 0.987
 16 |####################################################| 1.000
 20 |####################################################| 0.996
 24 |#################################################...| 0.945
 26 |#############################################.......| 0.861
 28 |####################################................| 0.702
```

第 20 行起**缓慢回落**（0.996 → 0.945 → 0.861 → 0.702）；box 在同样位置是**直接掉**
（1.000 → 0.930 → 0.798）。这就是"硬截断 vs 渐隐"。

#### 剖面汇总

| 文件 | 列 x | peak | ramp（px） | **slope_cv** | **end_ratio** | **curvature** | 形状 |
|---|---|---|---|---|---|---|---|
| `ab-box-6.png` | 82 | 243.0 | **13** | **0.030** | **1.006** | **0.056** | **PLATEAU（box）** |
| `ab-gaussian-6.png` | 95 | 238.0 | 12 | **0.426** | **0.526** | **0.216** | **BELL（gaussian）** |

- `slope_cv`（斜坡上一阶差分的 stdev/mean）：**0.030 vs 0.426，差 14 倍**。常数斜率 ⇒ 0。
- `end_ratio`（斜坡两端 / 中段的一阶差分）：**1.006 vs 0.526**。box 的斜率一路保持到拐角（比值 1），
  高斯两端只有中段的 53% —— 这就是"两端渐隐"的量化。
- `curvature`（|二阶差分| / |一阶差分|）：**0.056 vs 0.216，差 3.9 倍**。直线斜坡的曲率 ≈ 0。

三个统计量同向、量级分明。**box 是平台，高斯是钟，不是主观判断。**

### 三.2 梯度能量（沿用上一版度量）

`tools/motion-metrics.py`，列表区域自动检测（678×720，排除标题栏与 HUD）：

| 文件 | lap_var | gx（水平梯度） | gy（垂直梯度） | gy/gx |
|---|---|---|---|---|
| `freeze-0.png`（锐利基线） | **1169.72** | 191.90 | 110.54 | 0.576 |
| `ab-box-6.png` | **327.87** | 125.61 | 9.61 | **0.076** |
| `ab-gaussian-6.png` | 78.44 | 87.15 | 10.56 | 0.121 |
| `live-scroll-box.png`（真实滚动） | 306.61 | 117.39 | 8.98 | 0.076 |

相对锐利基线：

| 文件 | lap | gx | gy | (gy/gx) 相对 |
|---|---|---|---|---|
| `freeze-0.png` | 1.0000 | 1.0000 | 1.0000 | 1.000 |
| `ab-box-6.png` | **0.2803** | **0.6546** | 0.0869 | **0.133** |
| `ab-gaussian-6.png` | 0.0671 | 0.4542 | 0.0955 | 0.210 |
| `live-scroll-box.png` | 0.2621 | 0.6117 | 0.0812 | 0.133 |

**这张表是 box 更好的关键量化。** 两者的 `gy` 都在 9~11（垂直边缘都被摧毁，方向性相同），
但 **`lap_var` 是 327.87 vs 78.44 —— 差 4.2 倍**，`gx` 是 125.61 vs 87.15（差 1.44 倍）。

`lap_var` 衡量整体锐利度（Laplacian 方差）。**同样的半径、同样摧毁横边缘，box 保留了 4.2 倍的高频细节。**
这正是"运动模糊"与"失焦"的物理差别：运动模糊只在一个方向积分，垂直方向仍保留结构；
高斯在垂直方向也削掉了所有梯度，所以整体糊。

`live-scroll-box.png` 与 `ab-box-6.png` 几乎一致（lap 306.61 vs 327.87，gy/gx 都是 0.076）——
真实滚动帧与冻结帧重合，说明 `velocity → radius → kernel` 整条链是活的。

### 三.3 box 实现是"真 box"的验证（重要）

"看起来像 box"不够。取锐利基线 `freeze-0.png` 做**理想 13 抽头垂直 box 卷积**（numpy），
与真实 `ab-box-6.png` 逐像素比：

| 指标 | 值 |
|---|---|
| 全图 RMSE | **0.741** / 255 |
| 内部（去掉 20px 边）RMSE | **0.262** / 255 |
| 内部 p99 误差 | **0.85** / 255 |
| 内部最大误差 | **0.8** / 255 |

**内部最大误差 0.8/255 —— 与理想 box 卷积实质逐像素相同。** 差异只在图层边界
（第 716–719 行，`CLAMP` 的延伸区）。

顺带证伪了"box 核会引入梳状/阶梯伪影"的担心：二阶差分能量比（comb score）
锐利帧 **1.6998**、box 帧 **0.4957** —— box 帧比锐利帧**更平滑**，没有引入新的像素级振荡。
6dp 下看到的那种"横笔被复制成多条"的观感，是**字形自身的横笔在 12px 拖影内重叠**的必然结果
（34sp 等宽字的横笔间距只有约 4px），不是核的缺陷。

### 三.4 亮度守恒与零位移

| 检查 | 结果 | 说明 |
|---|---|---|
| 平均亮度 | `freeze-0` 34.420 → `ab-box-6` **34.390**（−0.09%） | `gain = 1` + 权重和 = 1，图层不暗不爆 |
| 过曝像素 | `sat_frac = 0.00000`（三张都是） | 没有截断到 255 |
| 垂直位移 | 最佳对齐位移 **0 px**（相关 0.915） | `offsetY = taps/2` 是真正的中心，无半像素偏移 |
| 高斯对照 | 平均 34.391，位移 0 px（相关 0.942） | 两条路径都不偏 |

### 三.5 逐张目视（四张都开图确认过）

- **`ab-box-6.png`** — `MOTION BLUR` **仍然认得出**。每根竖笔画变成一条**等宽的竖向拖影**，
  顶端/底端**硬截断**，笔画之间仍分离。观感是"这个列表正在飞快地滚"。
  HUD 自证：`kernel = box 1x13` / `N = 2*round(r*density)+1, w=1/N`，半径 `6.0 dp`，`FROZEN`。
  右侧 `REFERENCE` 条也竖向拖开且两端硬，证明是 layer 在起作用。
- **`ab-gaussian-6.png`** — 同一个半径，**字形互相渗透**：`MOTION` 的 `O` 和 `T` 开始连在一起，
  整体像一层柔光/雾。观感是"这张照片没对上焦"，不是"在动"。
  HUD：`kernel = gaussian (fallback)` / `sigmaY = r*density*0.55` / `sigmaX = r*density*0.12 (token)`。
- **`live-scroll-box.png`** — 真实滚动（非冻结）：`5.7 dp` / **`5763 dp/s`** / `SCROLLING · live`，
  `kernel = box 1x13`。字形与 box 冻帧一致，证明速度→半径→核这条链在动画里成立。
- **`freeze-0.png`** — 完全锐利，`0.0 dp`，`REFERENCE` 条同样锐利（对照）。

**目视判断：box 更像"快速滚动"，高斯更像"失焦"。** 与 §三.1/§三.2 的数字同向。

### 三.6 三角核的频域证据（`MotionBlurTriangleKernelTest`，本版新增）

"三角核 = box ⊛ box"不是比喻，是逐抽头恒等式：`triangleKernelWeights(13)` 与
`boxKernelWeights(7)` 自卷积**逐项相等**（1e-6 内）。由此 `|三角响应| = |半长 box 响应|²`，
直接推出旁瓣抑制：

| 量 | box 13 抽头 | triangle 13 抽头 | 来源 |
|---|---|---|---|
| 最差旁瓣 | **−13.27 dB** | **−25.31 dB** | 测试直接算 |DTFT| |
| 增益 @ 4px 周期（f=1/4） | 0.0769 | 0.0204（3.8×↓） | 同上 |
| 增益 @ 5px 周期 | 0.1245 | 0.0534（2.3×↓） | 同上 |
| 增益 @ 6/7px 周期 | 0.0769 | **0.0000（零点）** | 同上 |
| 周期图案上的输出纹波（4px） | 0.0769 | 0.0204 | `echoDepth` |
| 周期图案上的输出纹波（5px） | 0.1538 | 0.0612 | 同上 |
| 周期图案上的输出纹波（7px） | 0.0769 | 0.0000 | 同上 |

即：**矩形核的 −13dB 旁瓣把 4–7px 的字形横笔间距原样带回图像（叠影）；三角核平方响应后
同周期增益砍 2.3–3.8×、6/7px 处归零。** 这是"为什么三角核能减叠影"的机制层证明，
〇.2 的图像度量是它的现象层确认。

精确性说明：Bartlett(2h+1) = box(h+1) ⊛ box(h+1)，所以"响应平方"里的 box 是**半长 box**，
不是 13 抽头 box 自己 —— 低频段两者都 ≈ 1，行文说"box 响应的平方"在操作上成立，
测试里则逐点断言 `|triangle(f)| = |box7(f)|²`（f ∈ 0.005…0.5，误差 < 2e-3）。

## 四、帧率（第一轮三组 + 第二轮四组，桌面，5 秒锯齿滚动）

第一轮（三组）：

`--fps` 模式，逐帧 `withFrameNanos` 采样，同一 build、同一窗口尺寸：

```
FPS_RESULT blur=on  kernel=box      frames=493 avgMs=10.15 p95Ms=12.72 maxMs=63.98 fps= 98.5
FPS_RESULT blur=on  kernel=gaussian frames=490 avgMs=10.22 p95Ms=11.00 maxMs=63.36 fps= 97.8
FPS_RESULT blur=off kernel=box      frames=501 avgMs= 9.99 p95Ms=10.82 maxMs=19.58 fps=100.1
```

| 组 | 平均帧时间 | 相对 off 帧率 | 判定（§11 要求 ≥ off×80%） |
|---|---|---|---|
| **box** | 10.15 ms | **98.4%** | ✅ 远高于下限 |
| gaussian | 10.22 ms | 97.7% | ✅ |
| off | 9.99 ms | 100%（基线） | — |

**box 比高斯还快 0.07 ms。** 上一版 RESULT 断言"`makeMatrixConvolution` 代价远高于两个一维高斯" ——
**实测证伪**。原因很直接：`makeMatrixConvolution` 每个像素只求值 `kernelW × kernelH` = `1 × 13`
个系数，**这已经就是可分离快路径**；上一版的判断错在把"矩阵卷积"想成了二维 N×N。

`maxMs` 的 63~64 ms 尖峰出现在两组 blur=on 上，off 只有 19.58 —— 是 JIT/图层分配的偶发，
p95 不受影响（12.72 / 11.00 vs 10.82）。不构成降级理由。

> 注：这是桌面（Skia + GPU）的数字。§11 里"Wasm 低于无模糊版 80% 自动关闭"的规则**仍未实现**，
> 本次也没在浏览器里做 5 秒测量（见 §六.6）。

## 五、实现

### 核参数（`commonMain`，三端共享）

```kotlin
enum class MotionBlurKernel { Box, Triangle, Gaussian }
val DEFAULT_MOTION_BLUR_KERNEL = MotionBlurKernel.Box

fun boxKernelTaps(radiusDp: Float, density: Float): Int {
    if (!radiusDp.isFinite() || !density.isFinite()) return 1
    val radiusPx = radiusDp * density
    if (radiusPx <= 0f) return 1
    val half = radiusPx.roundToInt().coerceAtLeast(1)
    return 2 * half + 1                      // 6dp@1x → 13
}

fun boxKernelWeights(taps: Int): FloatArray {
    val weight = 1f / taps
    return FloatArray(taps) { weight }       // 和 = 1，配 gain = 1
}

// 第二轮新增：三角核（Bartlett），与 box 同一 tap 网格，只换权重
fun triangleKernelWeights(taps: Int): FloatArray {
    require(taps >= 1) { "kernel needs at least one tap, got $taps" }
    val centre = taps / 2
    var sum = 0f
    val raw = FloatArray(taps) { i ->
        val w = (centre + 1 - abs(i - centre)).toFloat()   // taper 到最小权重（非截断形）
        sum += w
        w
    }
    for (i in raw.indices) raw[i] = raw[i] / sum          // 归一化，gain 仍传 1
    return raw
}
```

`N = 2 × max(1, round(radiusDp × density)) + 1`。**奇数由结构保证**，不是碰巧：
朴素写法 `round(2r)+1` 在半径 1.5dp@1x 时给出偶数 4，Skia 直接拒收；写成 `2×round(r)+1`
后半个曲线都不需要特判。两个式子在要紧处一致：6dp@1x → 13，且 `N−1` = 拖影跨度 px。

### 平台路径

| 端 | box | triangle（第二轮新增） | gaussian（降级） |
|---|---|---|---|
| Desktop / Wasm | `saveLayer` + `ImageFilter.makeMatrixConvolution(kernelW=1, kernelH=N, kernel=1/N…, gain=1f, bias=0f, offsetX=0, offsetY=N/2, tileMode=CLAMP, convolveAlpha=true, input=null, crop=null)` | **同一调用，只把 `kernel` 换成 `triangleKernelWeights(taps)`**（成本相同） | `ImageFilter.makeBlur(sigmaX, sigmaY, FilterTileMode.CLAMP)` |
| Android 31+ | **无** —— `Modifier.blur` 不收核 | **无**（同左） | `Modifier.blur(radiusDp.dp, Unbounded)`（各向同性） |

`saveLayer` 是有效路径：Skia 在 `restore()` 合成图层时应用 layer paint 的 image filter，
被包裹的整棵子树作为一张图被滤波，且不改布局、不改命中区域（§11 要求）。

**`offsetY = taps / 2` 而不是 `0`**：卷积核的中心必须在 `N/2`，否则内容会整体位移半个核长。
§三.4 的"最佳对齐位移 0 px"就是对这一行的验证。

**`tileMode = CLAMP` 而不是 `DECAL`**：图层正好是节点边界，DECAL 会把最外几行淡成透明，
CLAMP 则延伸边缘像素。

### 缓存

`SkiaMotionPaints` 单例，一键缓存，**键包含核种类**：

```kotlin
private data class BoxKey(val taps: Int)
private data class TriangleKey(val taps: Int)   // 第二轮新增，同因：不能与 box 共槽
private data class GaussianKey(val sigmaX: Float, val sigmaY: Float)
```

上一版的键只有 `(sigmaX, sigmaY)`。加了 box 之后**必须**区分核种类 ——
否则同一半径下 box 会被高斯的缓存槽命中，静默画错效果。`data class` 保证按值相等
（每帧新建 key 对象也能命中）。

### 切换入口

- 桌面：`--kernel=box|triangle|gaussian`（默认 box；只认这三个值，打字错不会静默换核）
- Wasm：`?kernel=triangle` 等三个值
- 内容夹具：桌面 `--content=real|pathological`，Wasm `?content=`（默认 `real`）
- `MotionProbeConfig.kernel`，HUD 显示 `kernel = box 1x13` / `kernel = triangle 1x13` /
  `kernel = gaussian (fallback)`
- `FPS_RESULT` 行带 `kernel=` 字段，各组数据不会混淆

### 明确排除的方案

- `graphicsLayer { renderEffect = ... }` — Compose 里 `RenderEffect` 是 Android 专属，桌面/Wasm 无此参数。
- 先画进 `Picture`/`Image` 再带滤镜绘制 — 每帧多一次全尺寸位图分配。
- `Modifier.Node` / `DrawModifierNode` — 能跑（同一个 `saveLayer`），但要写 `onDetach` 释放原生句柄。
- **可分离两趟 box** — 不必要。`makeMatrixConvolution` 只碰 `1 × N` 个系数，1×N 核**本身就是**快路径。

## 六、已知限制（如实记录）

1. **Android 无法做 box 核，只能各向同性高斯 —— 与桌面有观感差异。** `Modifier.blur` 只收一个半径，
   Compose 在任何 API level 都没有按轴分开、更没有按核选择的版本
   （`RenderEffect.createBlurEffect(sx, sy, CLAMP)` 有双 sigma，但只能经 graphicsLayer 的
   `RenderEffect` 使用，`Modifier.blur` 不提供这个入口）。所以桌面是硬截断的竖向拖影，
   Android 是轻微失焦。`motionBlur(radiusDp, kernel)` 的 `kernel` 参数在该端被**显式忽略**
   （KDoc 写明），不是静默假装支持。补齐需要一条
   `graphicsLayer { renderEffect = RenderEffect.createBlurEffect(sx, sy, CLAMP) }` 的 API 31 分支 ——
   **本版没写**，因为无设备可验证，写了也只是猜。
2. **box 核抽头在整数像素网格上，没有子像素采样。** `makeMatrixConvolution` 的
   `offsetX/offsetY` 是 **Int**，13 个抽头严格落在 1px 网格上。§三.3 已证明这与理想 box 卷积
   逐像素一致（内部最大误差 0.8/255），所以**不是实现缺陷**；但在 34sp 大字上，
   12px 拖影会把字形内的亮带结构（帽线/暗中腰/基线，间距 4–7px）部分保留成
   "上下叠 2–3 道横带"的观感（第一轮误判为"横笔被复制"，第二轮 §〇.2 用拍频度量定位：
   是**字形自身带结构被 13px 核部分合并**的产物，行内暗腰 ≈ 核长时最明显）。
   真实相机拍高速滚动的等宽字体也是这个样子。**第二轮实测：13–20sp 真实内容上该结构
   完全被填平（seam ≈ 0，三核皆然），且三角核可把 34sp 上的叠影强度再砍 37%、
   中缝砍 63%（§〇.2/§三.6）** —— 大字/周期性内容为主的列表可切 `--kernel=triangle`。
   更平滑需要子像素抽头（本 API 做不到）或 2 抽头/px 的加宽核（`N = 4r+1`，只能把复制
   频率提高一倍）。
3. **Android 没有运行时证据。** 上一版 `assembleDebug` 通过、APK 生成，但本机无 Android 31+
   设备/模拟器，`Modifier.blur` 的实际出图未验证；本版未重跑 `assembleDebug`（矩阵不变）。
4. **Android <31 直接降级为无模糊**，返回 `this`，不装 graphics layer。
5. **Wasm 本版未重出图、未测帧率。** `compileKotlinWasmJs` 通过，`MotionBlur.wasmJs.kt` 与 desktop
   是同一套 `makeMatrixConvolution` 调用（含同样的 `input=null, crop=null`），但按任务范围没有
   重跑 webpack 打包与浏览器截图。`shots/motion/legacy-isotropic-10dp/` 里的 3 张 wasm 图是
   **更早一版各向同性 10dp 构建**的产物，**不能**当作本版证据。
6. **Wasm 帧率自动降级尚未实现。** §11 要求 Wasm 帧率低于无模糊版 80% 时自动关闭并记 warning ——
   目前只有 `blur=off` 手动开关。
7. **`SkiaMotionPaints` 是进程级单例缓存**，最后一个 `(核种类, 参数)` 活到进程退出，不释放 ——
   单次有界分配，不是累积泄漏。
8. **模糊元素与 haze 玻璃层叠加未测** — §11 禁止这种叠加（性能），probe 里没有 haze，无法验证冲突。
9. **120ms 归零**在 demo 里用线性斜坡实现（`MOTION_BLUR_DECAY_MS`），没有自动化测试验证时序。
10. **`live-scroll-box.png` 抓到的是 `5763 dp/s`（超过 6000 的 96%），半径已接近上限。**
    该帧能证明"速度→半径→核"链条是活的，但**不能**单独证明中间档位（如 1.5dp / 3dp）在真实
    滚动中出现；中间档位由 `MotionBlurBoxKernelTest` 的 taps 扫描覆盖，不是像素证据。
11. **6dp 上限是在高斯下校准的。** box 在同一半径保留 4.2 倍高频（`lap_var` 328 vs 78），
    理论上可以承受更大半径而不失可读性。本版**没有**重新标定上限 ——
    任务要求同一半径 A/B，换上限会让对比失去意义。是否提到 8dp 需要单独一轮目视校准。

## 七、复现

```bash
export PATH="/d/Hermes/tools/gradle-9.6.1/bin:$PATH"
export GRADLE_USER_HOME=D:/Hermes/tools/gradle-home

# 构建 + 测试
gradle :composeApp:desktopMainClasses :composeApp:desktopTest --rerun-tasks
gradle :composeApp:compileKotlinWasmJs

# 取 classpath
gradle :composeApp:desktopMainClasses :composeApp:printRuntimeClasspath -q | tail -1 > cp.txt

# A/B 两张图（唯一变量是 --kernel）+ 第二轮的三角核与真实内容四张
powershell -NoProfile -ExecutionPolicy Bypass -File tools/capture-motion.ps1 \
  -Java "D:/Hermes/tools/jdk21/bin/java.exe" -ClassPathFile cp.txt \
  -OutFile shots/motion/ab-box-6.png -FreezeBlur 6 -Content pathological

powershell -NoProfile -ExecutionPolicy Bypass -File tools/capture-motion.ps1 \
  -Java "D:/Hermes/tools/jdk21/bin/java.exe" -ClassPathFile cp.txt \
  -OutFile shots/motion/ab-triangle-6.png -FreezeBlur 6 -Kernel triangle -Content pathological

powershell -NoProfile -ExecutionPolicy Bypass -File tools/capture-motion.ps1 \
  -Java "D:/Hermes/tools/jdk21/bin/java.exe" -ClassPathFile cp.txt \
  -OutFile shots/motion/ab-gaussian-6.png -FreezeBlur 6 -Kernel gaussian -Content pathological

powershell -NoProfile -ExecutionPolicy Bypass -File tools/capture-motion.ps1 \
  -Java "D:/Hermes/tools/jdk21/bin/java.exe" -ClassPathFile cp.txt \
  -OutFile shots/motion/real-box-6.png -FreezeBlur 6 -Kernel box -Content real
# real-triangle-6 / real-gaussian-6 / real-freeze-0 同理换 -Kernel / -FreezeBlur 0

# 真实滚动（默认 box）
powershell -NoProfile -ExecutionPolicy Bypass -File tools/capture-motion.ps1 \
  -Java "D:/Hermes/tools/jdk21/bin/java.exe" -ClassPathFile cp.txt \
  -OutFile shots/motion/live-scroll-box.png -AutoScroll

# 四组帧率（第二轮）
java -cp "$(cat cp.txt)" vade.ui.MainKt --motion-probe --fps --autoscroll
java -cp "$(cat cp.txt)" vade.ui.MainKt --motion-probe --fps --autoscroll --kernel=triangle
java -cp "$(cat cp.txt)" vade.ui.MainKt --motion-probe --fps --autoscroll --kernel=gaussian
java -cp "$(cat cp.txt)" vade.ui.MainKt --motion-probe --fps --autoscroll --blur=off

# 度量（需要 numpy + pillow；.probe-venv 里有）
python tools/motion-metrics.py shots/motion/real-freeze-0.png \
  shots/motion/real-box-6.png shots/motion/real-triangle-6.png shots/motion/real-gaussian-6.png
python tools/motion-streak-profile.py shots/motion/real-box-6.png \
  shots/motion/real-triangle-6.png shots/motion/real-gaussian-6.png
python tools/motion-beat-metric.py shots/motion/ab-box-6.png shots/motion/ab-triangle-6.png \
  shots/motion/ab-gaussian-6.png shots/motion/real-freeze-0.png \
  shots/motion/real-box-6.png shots/motion/real-triangle-6.png shots/motion/real-gaussian-6.png
```

桌面参数：`--motion-probe` / `--freeze-blur=<dp>` / `--blur=off` / `--kernel=box|triangle|gaussian` /
`--content=real|pathological` / `--autoscroll` / `--fps`。现有分支（`--bar` `--dest` `--size` `--panel` /
`--search`）未改动。
Wasm query：`?motion-probe&freeze=6&kernel=triangle&content=real`。

## 八、踩坑记录（给后续实现省时间）

1. `ImageFilter.makeMatrixConvolution` 的 **`input` 和 `crop` 没有默认值**（Skiko 0.150.2），
   必须显式传 `null`，否则编译报 `No value passed for parameter 'input'`。
2. 它的参数顺序是 `kernelW, kernelH, kernel, gain, bias, offsetX, offsetY, tileMode,
   convolveAlpha, input, crop` —— `tileMode` 在 `convolveAlpha` **之前**，和 `makeBlur` 不同。
3. `makeBlur` 的 tile mode 参数名是 **`mode`**，不是 `tileMode`。
4. **`offsetY` 必须是 `taps/2`**，不是 0。写 0 会让整层内容上移 `(N-1)/2` px。
5. **拖影剖面要看一阶差分，不是剖面本身。** 第一版度量拿"平台宽度"和"顶部平坦度"去判别，
   结果两张图都被判成 BELL —— 因为字形竖笔是**阶跃**，box 卷积阶跃得到的是**线性斜坡**，
   不是平台。平台是 box 对**冲激**（1px 细线）的响应。`d(step ⊛ kernel)/dy = kernel` 才是正解。
6. **斜坡检测要在第一个连续区段就停。** 第一版取 `first…last` 的连续段，跨到了字形底部的
   第二条边，斜坡不再单调，被自己的单调性检查判成 degenerate。改成从第一个超阈值点开始、
   遇到第一个低于阈值就停。
7. **`1f/13f` 求和不是精确 1.0。** `Float` 权重展开成 `Double` 后和是 1.000000037，
   断言要用 `1e-6` 容差，不是 `1e-9`。这也是实际代码传 `gain = 1f`（而不是 `1.0`）的原因。
8. `-WindowStyle Hidden` 启动的窗口拿不到 Compose frame clock，`--autoscroll` 的驱动会停摆，
   截图读数变 `IDLE`。`capture-motion.ps1` 必须正常显示 + `ShowWindow(SW_SHOW)`。
9. `LazyListState` 的偏移必须按**行距**（`ITEM_HEIGHT + ITEM_SPACING`）算，只按 item 高度算会
   每行少 8dp，速度偏低。
10. 自动滚动驱动必须 `withFrameNanos` 逐帧推进；裸循环会测到协程调度速度而非合成器帧率。
11. `String.format` 是 JVM 专属，HUD 与 FPS 行在 `commonMain` 里手写格式化（同一份代码跑 Wasm）。
12. `+=` 在 `Float` 局部变量上被 Kotlin 解析成集合 `plusAssign`，必须写 `ema = ema + ...`。
13. **度量区域必须排除标题栏。** PrintWindow 抓的是整个窗口，标题栏里是**锐利**的系统文字；
    把它算进 Laplacian/梯度会把模糊图的数字抬高，甚至让"方向性"结论反转。按背景色
    （列表 `0xFF101114` / HUD `0xFF17191E`）自动检测边界。
14. **单看 `gy` 下降不能证明方向性。** 文字本身竖结构多于横结构（基线 `gy/gx = 0.576`），
    任何模糊都会让 `gy` 掉更多。必须做合成对照组（只改 `sigmaX`）才能把"方向性"和
    "文字内容特性"分开 —— 这一步上一版做了，本版结论仍成立（两组的 `gy` 都在 9~11）。
15. **"实现对了"要用理想卷积验证，不要靠目测。** 把锐利帧做理想 13 抽头 box 卷积，与真实帧
    逐像素比，内部最大误差 0.8/255 —— 这才排除了"看起来像但其实是别的东西"，
    也顺手证伪了"box 会引入梳状伪影"的猜测（comb score 反而更低）。
16. **区域级自相关测不出拍频。** 拍频度量第一版对整个列表区域做垂直 ACF，三张图全是
    ~0.007 无区分 —— 13px 核的平滑拖影自己就在 lag 2–5 强自相关，把带结构的信号埋掉。
    改成**逐文本行**（行剖面 = 对有字列求行均值；行内归一化 ACF；行界外用真实暗背景，
    不做合成 padding）才分开（强度 box 0.736 / tri 0.465 / gauss 0.569）。还要意识到
    "回音横带"是**字形自身的帽线/暗中腰/基线**被核部分合并的产物，不是核引入的振荡 ——
    box 无负瓣，数学上不可能振铃；换核改变的是"这些带被填平的程度"。
