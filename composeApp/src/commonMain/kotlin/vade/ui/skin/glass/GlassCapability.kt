package vade.ui.skin.glass

/**
 * 玻璃层在当前平台**真正能画出来**的东西，从强到弱。
 *
 * 这张阶梯是 `glass` 皮肤的诚实性接口（任务书 52-U3 §四，D22 / FR-8.23）：皮肤不许在
 * Desktop/Web 假装折射，也不许在 Android 12- 上假装有折射；越界的效果在这里没有位置，
 * 而不是在某个组件里被悄悄画出来。
 *
 *  - [Refraction] —— 背景模糊 + 边缘折射。只有 Android 13+（API 33，AGSL `RuntimeShader`
 *    可用）配得上这个值。**本批没有任何平台返回它**：AGSL 的绘制路径属任务书 §五 T3，
 *    在拿到真机之前不写不可验证的 `RenderEffect` 管线（与本仓 `MotionBlur.android.kt`
 *    拒绝同一条路径的理由一致）。阶梯先留着这个值，是为了 T3 接线时只改 `actual`，
 *    不动调用方。
 *  - [Blur] —— 背景模糊 + 染色 + 描边 + 高光。Android 12-、Desktop、Wasm 的实际档位。
 *  - [TintStroke] —— 采不到背景（页面没接 `GlassScope`，或平台没有模糊）：只剩染色 +
 *    描边 + 高光。**这是"不白屏"的那一档** —— 它照样是一个完整的、可读的层。
 */
enum class GlassCapability {
    Refraction,
    Blur,
    TintStroke;

    /** 是否有真实的背景模糊。 */
    val blurs: Boolean get() = this != TintStroke

    /** 是否有边缘折射（Desktop/Web 永远是 false，D22）。 */
    val refracts: Boolean get() = this == Refraction
}

/**
 * 平台的**上限**能力（不看页面是否真的接了背景）。每个 actual 只回答一件事：这个目标上
 * 玻璃最多能做到哪一档。
 *
 * 命名为 `platformGlassCapability` 而不是直接 `expect fun glassPlatformCapability()`：
 * 公开的 [glassPlatformCapability] 需要一层薄包装，好让 [glassPlatformCapabilityProbe]
 * 能在测试里把"某个平台探测炸了 / 只到 Blur"模拟出来 —— 否则这段降级逻辑永远没有
 * 执行过的路径，只是"看起来写了 try/catch"。
 */
expect fun platformGlassCapability(): GlassCapability

/**
 * 测试缝（**internal**，生产代码不许写它）。
 *
 * 任务书 §T3 要求"降级路径必须有单测（可用 fake shader 编译失败模拟）"。真造一个
 * "shader 编译失败"的平台是不可能的（那要一台 API 33 真机 + 一个坏 shader），所以这里
 * 留一个可替换的探针：测试把它设成抛异常，就能走到 [glassCapability] 的 catch 分支。
 */
internal var glassPlatformCapabilityProbe: (() -> GlassCapability)? = null

/**
 * 平台能力（可能被测试缝替换）。
 *
 * 生产调用点只用这个。`expect/actual` 的实现在 `GlassCapability.<platform>.kt`。
 */
fun glassPlatformCapability(): GlassCapability =
    glassPlatformCapabilityProbe?.invoke() ?: platformGlassCapability()

/**
 * 实际能力 = 平台上限 ∩ 是否真的采得到背景。
 *
 * 纯函数（不读组合状态），所以 `GlassSkinTest` 能直接断言降级结果，不需要起一个组合。
 * `try/catch` 是硬要求（任务书 §七：禁止在能力探测失败时抛到 UI）：任何 actual 抛异常
 * 都收敛到 [GlassCapability.TintStroke]，把玻璃层降成"染色 + 描边"，不是白屏。
 *
 * @param hasBackdrop 页面是否提供了 `LocalGlassHazeState`（`GlassScope` 已接上）。
 */
fun glassCapability(hasBackdrop: Boolean): GlassCapability {
    if (!hasBackdrop) return GlassCapability.TintStroke
    return try {
        glassPlatformCapability()
    } catch (_: Throwable) {
        // 探测本身炸了：当作"平台上什么都采不到"，绝不把异常冒到组合里。
        GlassCapability.TintStroke
    }
}
