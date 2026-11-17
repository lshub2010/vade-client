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
 *    拒绝同一条路径的理由一致）。
 *  - [Blur] —— 背景模糊 + 染色 + 描边 + 高光。Android 12+（API 31）、Desktop、Wasm 的实际档位。
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
 * 玻璃层**是否建 haze 节点**的策略 —— 与「平台能做到哪一档」正交。
 *
 * 这两件事被混在一起过一次：让共享调用点给 `glassCapability` 传一个假的
 * `hasBackdrop = true` 去保住像素，结果是「能力」这个词在代码里不再可信（审查 P1-5）。
 * 现在能力只看真实背景，绘制策略由调用点显式声明：
 *
 *  - [HazeAlwaysCompat] —— **兼容模式**：无论有没有背景都建 haze 节点。U3 之前所有
 *    共享调用点（material 的 FloatingBlur 底栏、TopBar、GlassNavBar、SearchOverlay、
 *    ChatThread 的各种浮层、模型选择卡）就是这个行为，CI 的像素钉钉的是它。名字里带
 *    `Compat` 是为了让读的人知道：这里不是"真的能模糊"，而是历史像素的显式保留。
 *  - [DegradeWithoutBackdrop] —— glass 皮肤自己的组件：采不到背景就不建 haze 节点，
 *    直接染色 + 描边 + 高光（`TintStroke`），因为一条采不到背景的 haze 链什么都不画。
 */
enum class GlassBackdropPolicy {
    HazeAlwaysCompat,
    DegradeWithoutBackdrop,
}

/**
 * 这条链上到底建不建 haze 节点。
 *
 * 抽成纯函数只为一件事：让「共享调用点像素不变」这条约束可断言，而不是靠人读 diff。
 * `capability.blurs` 是"平台 + 背景"的结论；[GlassBackdropPolicy.HazeAlwaysCompat]
 * 是一个**覆盖**：它保留 U3 之前那个「私有空 state 也硬跑 hazeBlur」的像素，哪怕
 * 能力档位是 [GlassCapability.TintStroke]。
 */
fun drawsHazeLayer(capability: GlassCapability, policy: GlassBackdropPolicy): Boolean =
    capability.blurs || policy == GlassBackdropPolicy.HazeAlwaysCompat

/**
 * Android 的模糊门槛：API 31（Android 12）。
 *
 * `Modifier.blur` / `RenderEffect` 的模糊路径从 API 31 起才是真的可用（见
 * `MotionBlur.android.kt` 的同一条门禁与同一段理由）。低于它，haze 的 `hazeBlur`
 * 拿不到任何背景 —— 返回 [GlassCapability.Blur] 就是在**谎报**，正是审查 P1-1。
 */
const val ANDROID_GLASS_BLUR_MIN_SDK: Int = 31

/**
 * Android 的档位阶梯，**SDK 号可注入**所以是纯函数。
 *
 * 为什么放在 commonMain 而不是 androidMain：下游的 `platformGlassCapability()` 是
 * `expect/actual`，desktopTest 看不到 androidMain，于是「24–30 必须降级」这条最容易
 * 回归（有人把它改回无条件 Blur）的断言就没有地方写。把判定与 `Build.VERSION` 分开，
 * 单测就能把 23…34 全走一遍，不需要一台 Android 11 设备。
 */
fun androidGlassCapability(sdkInt: Int): GlassCapability =
    if (sdkInt >= ANDROID_GLASS_BLUR_MIN_SDK) GlassCapability.Blur else GlassCapability.TintStroke

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
 * @param hasBackdrop 页面是否**真的**提供了 `LocalGlassHazeState`（`GlassScope` 已接上）。
 *   这里只接受真实值：想保留「没背景也建 haze 节点」的历史像素，用
 *   [GlassBackdropPolicy.HazeAlwaysCompat] / [drawsHazeLayer]，不要在这个参数上说谎。
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
