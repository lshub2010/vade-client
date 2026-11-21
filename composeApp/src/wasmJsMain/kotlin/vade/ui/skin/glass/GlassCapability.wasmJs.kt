package vade.ui.skin.glass

/**
 * Wasm actual — 模糊"尽力"，折射不做（D22）。
 *
 * 事实边界（写清楚，别让后来的人以为这是验过的）：
 *
 *  - Compose/Wasm 的一切都画在 canvas 里，**没有 DOM 叠加层**，所以 CSS `backdrop-filter`
 *    那条路走不通 —— 方案 §9.3 的"CSS backdrop-filter"在 Web 上不成立，这是与设计的已知
 *    差异（任务书 §T3「Web」要求写明，不许静默改设计）。
 *  - haze 2.1.0 有 `wasmJsApiElements` 变体，本模块在三个目标上都编译通过；但探针只对
 *    **Desktop 做过像素验证**（"desktop really blurs"），Wasm 没有。
 *
 * 因此这里报 [GlassCapability.Blur]：haze 的染色走 `colorEffects`，描边与顶部高光是
 * `GlassSurface` 自己画的（不经过 haze），**即使模糊在该目标上是 no-op，玻璃层也不会消失
 * 或白屏** —— 最坏情况是"没有模糊的染色层 + 描边 + 高光"。要拿到确定像素，需要在 wasm
 * 上截一次图（c7-wasm 的截图环）；若证实模糊 no-op，这里改成一个词
 * `GlassCapability.TintStroke` 即可，组件侧一个字都不用动 —— 这正是阶梯存在的意义。
 */
actual fun platformGlassCapability(): GlassCapability = GlassCapability.Blur

/**
 * 降级日志的平台指纹。Wasm 的模糊"尽力"（见上），所以这里同时标出候选档位：若某天证实
 * 模糊在该目标上是 no-op，把上面那行改成 [GlassCapability.TintStroke] 即可，日志会立即
 * 显示 `capability=TintStroke`。
 */
actual fun glassPlatformDiagnostic(): String = "wasmJs candidates=Blur,TintStroke"
