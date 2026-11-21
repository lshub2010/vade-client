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
 * 这条链上到底建不建 haze 节点 —— **只看能力**，没有第二个输入。
 *
 * 曾经这里还有一个 `GlassBackdropPolicy` 参数，[HazeAlwaysCompat] 可以在平台能力是
 * `TintStroke` 时把结论强行拉回 `true`（"保住 U3 之前的像素"）。后果就是真机反馈的那三条
 * 症状：API<31 的顶栏 / 搜索浮层 / 模型卡 / 图片预览仍然建 haze 节点，而 haze 在没有
 * `RenderEffect` 的平台上走 `ScrimBlurVisualEffectDelegate` —— **只画一层 tint，不画模糊**。
 * 于是"玻璃"只剩一块灰罩，`TintStroke` 那一档（染色 + 描边 + 高光）一次都没被执行过。
 *
 * 现在没有覆盖入口：能力说不能模糊，就不建节点、走 [GlassCapability.TintStroke] 的完整
 * 形状；像素基线要变，就更新一次基线，别让运行时策略承担。调用点也不需要再表态（少一个
 * 可以被写错的参数）。
 */
fun drawsHazeLayer(capability: GlassCapability): Boolean = capability.blurs

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
 * 单测就能把 21…35 全走一遍，不需要一台 Android 11 设备。
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
 * 一行平台指纹，**降级日志的正文**（审查 P1-10：真机上"变成灰罩"在日志里零痕迹）。
 *
 * 只写可从平台事实里直接读到的东西，不猜：SDK 号、模糊门槛、以及这条阶梯考虑过的候选
 * 档位。格式固定为 `key=value` 空格分隔、单行 —— 日志可以被 `adb logcat | grep vade.glass`
 * 直接读，也可以被单测断言。
 */
expect fun glassPlatformDiagnostic(): String

/**
 * 测试缝（**internal**，生产代码不许写它）。
 *
 * 任务书 §T3 要求"降级路径必须有单测（可用 fake shader 编译失败模拟）"。真造一个
 * "shader 编译失败"的平台是不可能的（那要一台 API 33 真机 + 一个坏 shader），所以这里
 * 留一个可替换的探针：测试把它设成抛异常，就能走到 [glassCapabilityDecision] 的 catch 分支。
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
 * 一次能力判定的**结论 + 理由**。
 *
 * 理由不是给人看的装饰：它是降级日志的正文（[GlassDegradeLog]），也是能力矩阵测试的
 * 断言对象 —— "为什么这层只画 tint"必须能从代码里读出来，而不是靠看截图猜。
 */
internal data class GlassCapabilityDecision(
    val capability: GlassCapability,
    val hasBackdrop: Boolean,
    val reason: String,
    /** 这条链上考虑过的档位（从强到弱），日志里原样打出。 */
    val candidates: List<GlassCapability>,
)

/**
 * 实际能力 = 平台上限 ∩ 是否真的采得到背景。
 *
 * 纯函数（不读组合状态），所以 `GlassSkinTest` 能直接断言降级结果，不需要起一个组合。
 * `try/catch` 是硬要求（任务书 §七：禁止在能力探测失败时抛到 UI）：任何 actual 抛异常
 * 都收敛到 [GlassCapability.TintStroke]，把玻璃层降成"染色 + 描边 + 高光"，不是白屏。
 *
 * @param hasBackdrop 页面是否**真的**提供了 `LocalGlassHazeState`（`GlassScope` 已接上）。
 *   这里只接受真实值 —— 想改画法只有一个入口：[drawsHazeLayer]（它只认能力）。
 */
internal fun glassCapabilityDecision(hasBackdrop: Boolean): GlassCapabilityDecision {
    val ladder = listOf(GlassCapability.Blur, GlassCapability.TintStroke)
    if (!hasBackdrop) {
        return GlassCapabilityDecision(
            capability = GlassCapability.TintStroke,
            hasBackdrop = false,
            reason = "no backdrop (GlassScope absent ⇒ LocalGlassHazeState is null)",
            candidates = ladder,
        )
    }
    val platform = try {
        glassPlatformCapability()
    } catch (t: Throwable) {
        // 探测本身炸了：当作"平台上什么都采不到"，绝不把异常冒到组合里。
        return GlassCapabilityDecision(
            capability = GlassCapability.TintStroke,
            hasBackdrop = true,
            reason = "capability probe threw ${t::class.simpleName}: ${t.message}",
            candidates = ladder,
        )
    }
    return GlassCapabilityDecision(
        capability = platform,
        hasBackdrop = true,
        reason = "platform cap=$platform (${glassPlatformDiagnostic()})",
        candidates = ladder,
    )
}

/**
 * 实际能力（[glassCapabilityDecision] 的结论），并在**降级时**记一行日志。
 *
 * 生产调用点用这个：玻璃层 → 灰罩这件事必须能在 `adb logcat` 里看到，而不是只能靠真机
 * 肉眼看。日志按 `(hasBackdrop, capability, reason)` 去重 —— 组合热路径上每帧都调用它，
 * 但同一结论只打一行。
 */
fun glassCapability(hasBackdrop: Boolean): GlassCapability =
    glassCapabilityDecision(hasBackdrop).let { decision ->
        if (!decision.capability.blurs) GlassDegradeLog.note(decision)
        decision.capability
    }

/**
 * 降级观测点（审查 P1-10）。
 *
 * 一行、单行、可 grep：
 *
 * ```text
 * vade.glass degrade: capability=TintStroke hasBackdrop=false platform=android sdk=30 blurMinSdk=31
 *   candidates=[Blur,TintStroke] reason=no backdrop (...)
 * ```
 *
 * `internal` 的读口只给测试用（`GlassCapabilityMatrixTest` 断言 SDK / hasBackdrop / 候选
 * 三件事都在行里），生产不读它。
 */
internal object GlassDegradeLog {

    /** 最近一行降级日志；测试读它，生产只写。 */
    var lastLine: String? = null
        private set

    /** 已经打过的结论键 —— 同一结论只打一行，组合热路径不刷屏。 */
    private var emitted: String? = null

    fun note(decision: GlassCapabilityDecision) {
        val candidates = decision.candidates.joinToString(",") { it.name }
        val line = "vade.glass degrade: capability=${decision.capability.name} " +
            "hasBackdrop=${decision.hasBackdrop} " +
            "platform=${glassPlatformDiagnostic()} " +
            "candidates=[$candidates] reason=${decision.reason}"
        val key = "${decision.capability.name}|${decision.hasBackdrop}|${decision.reason}|$candidates"
        if (key == emitted) return
        emitted = key
        lastLine = line
        println(line)
    }

    /** 测试缝：清空去重与记录，让下一条日志一定会打出来。 */
    internal fun reset() {
        emitted = null
        lastLine = null
    }
}
