# g-shadow-real：Android 阴影 — 负结果存档（未修成）

**状态：失败，未修复。** 本棒（deepseek）撞了迭代上限，代码全部留在
`Vade-client-wt/g-shadow-real` 的未提交工作树里，**没有进主干**。这份文件只归档
它**已验证的负结果**与测量工具，避免下一棒把同样的路再走一遍。

归档人：父代（2026-11-15）。以下每条都标了「谁验的」。

---

## 一、已经证实的机理（这条最重要）

D1（composer 零阴影）**不是 `outsideOnly` 一处的问题**。

| 实验 | 结果 | 谁验的 |
|---|---|---|
去掉 `outsideOnly`、去掉 mask、只留三层高斯 | Android 仍然 **0 像素** | 本棒 |
同一构建下顶栏浮卡 / 静态卡 | **有阴影**（3/255 梯度） | 本棒 |
⇒ `BlurMaskFilter` 在硬件画布上**能画** | 成立 | 本棒 |

结论：能画，但**「clamp 住这次模糊绘制」的手法在 Android 上把像素全吃掉**。

## 二、四条被证伪的「打洞」路线（逐条实测都是 0 像素）

1. Compose `clipPath(ClipOp.Difference)` ← 原来 `outsideOnly` 的写法
2. Compose clip + 外层 `saveLayer`
3. 原生 `Canvas.clipPath(hole, Region.Op.DIFFERENCE)`
4. 原生 `saveLayer` + 原生 `PorterDuff.CLEAR` 打洞

**更糟的两条**：Compose 级 `BlendMode.Clear` / `DstOut` 打洞会**整层被清空**——
面板内部倒是干净了（254），但外侧一个像素不剩。

## 三、40 层 fallback 也不成立（量化吃掉）

每层 α = `s/40` ≈ 0.0004–0.0018，在 **8-bit surface 上量化为 0**，40 层全空。
实测：强制走 fallback 后，静态卡那条 3/255 的阴影**消失**。

⇒ 若要走多层退路，**每层 α 必须 ≥ 1/255**（即总层数与 α 的乘积要落在 8-bit
可表示范围内），否则是自欺。

## 四、一个测量陷阱（差点导致误判）

本棒最后一次打印面板几何（`attach` 中心 2205 → 面板 [2116,2294]）后发现：
此前判读为「无阴影」的某些帧里，**取样行 x=350 恰好落在面板内部/发丝线上**。
所以：**测量坐标必须从 UI 树取，不能靠猜像素行**。
工具已归档：`measure_composer.py`（从 UI 树取上下沿，按 68dp 反推）。

但**修正测量之后，Android 上 composer 依然是零像素**——这条结论没被推翻。

## 五、下一棒该怎么做（父代给的顺序，不是本棒的猜测）

按**成本递增**逐级验证，每级都要先证明「像素出现了」再往下走：

1. **最小证明**：一张测试屏，`Box` + `drawBehind { drawRect(实色) }` → 截图有像素（排除环境问题）
2. **无裁剪模糊**：同位置改用 `BlurMaskFilter` 画一个圆角矩形，**不做任何裁剪** → 有像素？
3. **矩形裁剪**（关键假设）：`clipRect(y = cardBottom - eps .. cardBottom + spread)` 之后再画模糊 ——
   **矩形裁剪与路径裁剪在硬件画布上是两条路**，前三条失败的都是**路径**裁剪
4. **仅当 3 失败**才考虑 `DstIn` 反向遮罩；再失败才考虑多层退路（每层 α ≥ 1/255）

同时注意：**D6（圆角外第二道 23/255 硬线）与 D10（上方 10–11/255 暗带）的来源是
Material 的 `Modifier.shadow(16.dp)` 对称 tessellated silhouette**，与 D1 不是同一个 bug。
把 `GlassSurface` 的 `.shadow(16.dp)` 去掉就能同时解决 D6+D10（桌面上已验证）；
**但若同时把 Android 的替代阴影接成零像素，就是拿一个缺陷换另一个缺陷**——两件事要分开验收。

## 六、工作树里的东西（可读，勿直接合）

`Vade-client-wt/g-shadow-real` 未提交：`DownwardShadow.kt`（新，共享下行阴影引擎 +
可测试的数学：`shadowLeakAbove` / `shadowPeakBelow` / `haloRampLayers`）、
`HaloBlur.kt` + 三个 actual 的 clip-out 参数组、`GlassSurface.kt` 的 shadow 替换。
其中 **Android actual 是「原生 saveLayer + CLEAR 打洞」，已实测零像素，不可提交**；
其余部分（尤其纯数学与桌面分支）可作参考。

## 七、证据清单

- `before-*.png` / `after-*.png`：修前/修后同名帧（注意：部分取样行有第四节那个陷阱）
- `expA-composer.png`：强制 fallback 后静态卡阴影消失
- `crop-expI-*.png` / `crop-expJ-*.png`：打洞路线的裁切放大
- `driver.py` / `measure.py` / `measure_composer.py` / `ui.py`：取帧与测量工具
