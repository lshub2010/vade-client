package vade.ui.skin.glass

/**
 * Android actual — 本批只到 [GlassCapability.Blur]。
 *
 * haze 2.1.0 的 `hazeBlur` 走 `RenderEffect` 的模糊路径，Android 上从 API 31 起就有真实的
 * 背景模糊，所以 `Blur` 是无条件成立的（不依赖 API 33）。
 *
 * **折射为什么还是 false**：AGSL 的边缘折射不是在 haze 上"再挂一个滤镜"就能做的 ——
 * 它要求背景**单独成层**（内容和背景分开），再对这个层挂
 * `graphicsLayer { renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content") }`。
 * 这条管线本批无法编译、无法上机（任务书要求本批禁 Gradle），而写一条没人跑过的 shader
 * 管线只会让 Android 构建在下一个批次炸掉。本仓对同类问题的既有决定就在
 * `skin/glass/MotionBlur.android.kt`：宁可少一个效果，也不提交"看起来对"的
 * `graphicsLayer { renderEffect }` 代码。
 *
 * T3 接线时这里要变成：
 *
 * ```
 * actual fun platformGlassCapability(): GlassCapability =
 *     if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && AgslRefraction.compiled()) {
 *         GlassCapability.Refraction
 *     } else {
 *         GlassCapability.Blur
 *     }
 * ```
 *
 * 其中 `compiled()` 必须在 `try/catch(Throwable)` 里构造 `RuntimeShader` 并返回 false 兜底
 * （任务书 §T3：shader 编译失败必须降级，不许抛到 UI）。**降级路径本身已经在本批落地**：
 * 阶梯不返回 `Refraction`，`GlassSurface` 就走 [GlassCapability.Blur] 那条已经存在的
 * "模糊 + 描边 + 高光" 路径，像素与今天就有的玻璃浮层一致。
 */
actual fun platformGlassCapability(): GlassCapability = GlassCapability.Blur
