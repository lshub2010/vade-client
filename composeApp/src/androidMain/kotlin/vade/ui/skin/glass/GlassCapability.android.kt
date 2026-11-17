package vade.ui.skin.glass

import android.os.Build

/**
 * Android actual — 本批只到 [GlassCapability.Blur]，且**带 API 31 门禁**。
 *
 * haze 2.1.0 的 `hazeBlur` 走 `RenderEffect` 的模糊路径，Android 上从 **API 31（Android 12）**
 * 起才有真实的背景模糊；`Modifier.blur` 在 31 以下直接 no-op（同仓
 * `MotionBlur.android.kt` 的 `SDK_INT < S` 门禁，理由与写法都一致）。所以这里不能无条件
 * 返回 `Blur`：minSdk 是 24，Android 7–11 的设备会拿到一个"声称有模糊、实际什么都没有"
 * 的层 —— 审查 P1-1 说的正是这个谎报。低于 31 返回 [GlassCapability.TintStroke]：
 * 染色 + 描边 + 高光，一个完整的、可读的层。
 *
 * 判定本身在 commonMain 的 [androidGlassCapability]（纯函数、SDK 号可注入），这里只负责
 * 把 `Build.VERSION.SDK_INT` 递进去 —— 这样「24–30 必须降级」这条有单测可钉，不需要一台
 * Android 11 设备。
 *
 * **折射为什么还是 false**：AGSL 的边缘折射不是在 haze 上"再挂一个滤镜"就能做的 ——
 * 它要求背景**单独成层**（内容和背景分开），再对这个层挂
 * `graphicsLayer { renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content") }`。
 * 这条管线本批无法编译、无法上机，而写一条没人跑过的 shader 管线只会让 Android 构建在
 * 下一个批次炸掉。本仓对同类问题的既有决定就在 `skin/glass/MotionBlur.android.kt`：
 * 宁可少一个效果，也不提交"看起来对"的 `graphicsLayer { renderEffect }` 代码。
 *
 * T3 接线时这里要变成：
 *
 * ```
 * actual fun platformGlassCapability(): GlassCapability =
 *     if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && AgslRefraction.compiled()) {
 *         GlassCapability.Refraction
 *     } else {
 *         androidGlassCapability(Build.VERSION.SDK_INT)
 *     }
 * ```
 *
 * 其中 `compiled()` 必须在 `try/catch(Throwable)` 里构造 `RuntimeShader` 并返回 false 兜底
 * （任务书 §T3：shader 编译失败必须降级，不许抛到 UI）。**降级路径本身已经在本批落地**：
 * 阶梯不返回 `Refraction`，`GlassSurface` 就走 [GlassCapability.Blur] 那条已经存在的
 * "模糊 + 描边 + 高光" 路径；31 以下则走 `TintStroke`。
 */
actual fun platformGlassCapability(): GlassCapability =
    androidGlassCapability(Build.VERSION.SDK_INT)
