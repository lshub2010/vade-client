package vade.ui.skin.glass

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 审查 P1-1（Android 谎报模糊）与 P1-5（用假的 `hasBackdrop` 保像素）的回归闸门。
 *
 * 两条缺陷的共同点：**没有一行代码是"错误"的，但代码在说谎**。
 *
 *  - `GlassCapability.android.kt` 无条件返回 `Blur` —— minSdk 是 24，Android 7–11 上
 *    `Modifier.blur` / `RenderEffect` 根本不存在，玻璃层声称有模糊、实际什么都没有。
 *    这里用**可注入 SDK 的纯函数**把 21…35 全走一遍，不需要一台 Android 11 设备；
 *    再加一条源码守卫钉住 actual 必须走这个函数，而不是又写成一行常量。
 *  - `GlassSurface` 曾经给 `glassCapability` 传 `hasBackdrop = ambient != null ||
 *    !degradeWithoutBackdrop` —— 共享调用点为了保住像素，往"有没有背景"这个事实上塞了一个
 *    `true`。现在能力只看真实背景，绘制策略由 [GlassBackdropPolicy] 显式声明；
 *    源码守卫保证共享调用点**没有**任何人为自己选策略（它们靠默认值拿兼容像素）。
 */
class GlassCapabilityGateTest {

    /** Module dir: Gradle runs desktopTest with the composeApp module as working dir. */
    private fun src(path: String) = File("src/$path")

    /** 去掉注释后的源码 —— 守卫只匹配**调用**，不匹配文档（同 TopGlassGuardTest）。 */
    private fun codeOf(file: File): String {
        assertTrue(file.isFile, "guard target missing: ${file.path}")
        val noBlock = file.readText().replace(Regex("(?s)/\\*.*?\\*/"), "")
        return noBlock.split('\n').joinToString("\n") { it.substringBefore("//") }
    }

    private fun count(code: String, pattern: String): Int =
        Regex(pattern).findAll(code).count()

    // --- 1 · Android 的 API 31 门禁 -------------------------------------------------

    /**
     * 24–30 必须降级到 `TintStroke`，31+ 才是 `Blur`。
     *
     * 这不是"四舍五入的边界讨论"：`Modifier.blur` 在 31 以下直接 no-op（同仓
     * `MotionBlur.android.kt` 的 `SDK_INT < S` 门禁），haze 的 `hazeBlur` 也拿不到背景。
     * 返回 `Blur` 就等于在 Android 7–11 上画一个"声称模糊、实际只有一层薄色"的谎。
     */
    @Test
    fun androidLadderDegradesBelowApi31() {
        for (sdk in 21..30) {
            assertEquals(
                GlassCapability.TintStroke,
                androidGlassCapability(sdk),
                "API $sdk 没有 RenderEffect 模糊路径 —— 必须降级，不许谎报 Blur（审查 P1-1）",
            )
            assertFalse(
                androidGlassCapability(sdk).blurs,
                "API $sdk 的档位不许声称自己有模糊",
            )
        }
        for (sdk in 31..35) {
            assertEquals(
                GlassCapability.Blur,
                androidGlassCapability(sdk),
                "API $sdk 有真实的背景模糊（Android 12+），档位应是 Blur",
            )
            assertTrue(androidGlassCapability(sdk).blurs, "API $sdk 的 Blur 必须 blurs")
        }
        assertEquals(31, ANDROID_GLASS_BLUR_MIN_SDK, "门槛就是 API 31（Android 12）")
        assertFalse(
            androidGlassCapability(33).refracts,
            "API 33 有 AGSL，但折射的绘制管线属 T3；阶梯在拿到真机前不许返回 Refraction",
        )
    }

    /**
     * actual 必须把 `Build.VERSION.SDK_INT` 递给 [androidGlassCapability]。
     *
     * 守卫的是回退形态：`actual fun platformGlassCapability() = GlassCapability.Blur` 这行
     * 代码能编译、能过所有桌面测试，却在所有 Android 版本上撒谎 —— 这正是审查抓到的东西。
     */
    @Test
    fun androidActualGoesThroughTheSharedSdkLadder() {
        val android = codeOf(src("androidMain/kotlin/vade/ui/skin/glass/GlassCapability.android.kt"))
        assertEquals(
            1,
            count(android, """androidGlassCapability\(Build\.VERSION\.SDK_INT\)"""),
            "Android actual 必须走 androidGlassCapability(Build.VERSION.SDK_INT)，" +
                "否则「24–30 降级」这条纯函数单测覆盖不到真实平台路径",
        )
        assertEquals(
            0,
            count(android, """=\s*GlassCapability\.(Blur|Refraction)\s*$"""),
            "actual 不许再写成一行常量 —— 那就是审查 P1-1 的原文",
        )
    }

    // --- 2 · 策略与能力正交 ----------------------------------------------------------

    /**
     * 「建不建 haze 节点」由策略决定，而不是靠谎报能力。
     *
     * - 共享调用点（兼容策略）：**永远**建节点 —— 它们的历史像素就是这么来的，哪怕能力是
     *   `TintStroke`（没有背景可采）；
     * - glass 组件（诚实策略）：能力说不模糊就不建节点，直接染色 + 描边 + 高光。
     */
    @Test
    fun hazeIsDrawnByPolicyNotByFakedBackdrop() {
        assertTrue(
            drawsHazeLayer(GlassCapability.TintStroke, GlassBackdropPolicy.HazeAlwaysCompat),
            "兼容策略必须保留 haze 节点 —— 共享调用点的像素钉钉的就是它",
        )
        assertFalse(
            drawsHazeLayer(GlassCapability.TintStroke, GlassBackdropPolicy.DegradeWithoutBackdrop),
            "诚实策略在 TintStroke 下不许建 haze 节点（采不到背景的 haze 链什么都不画）",
        )
        for (capability in listOf(GlassCapability.Blur, GlassCapability.Refraction)) {
            for (policy in GlassBackdropPolicy.entries) {
                assertTrue(
                    drawsHazeLayer(capability, policy),
                    "$capability 真的能模糊，$policy 下也必须照常建节点",
                )
            }
        }
        // 能力本身仍然只看真实背景 —— 这条是 P1-5 的核心，不能被策略绕过。
        assertEquals(
            GlassCapability.TintStroke,
            glassCapability(hasBackdrop = false),
            "没有背景就是没有背景：glassCapability 只接受真实值",
        )
    }

    /**
     * 共享调用点必须**显式**声明兼容模式，且不许重新出现 `degradeWithoutBackdrop` 那个假旗标。
     *
     * 「显式」是这条 P1 的另一半：默认值只是兜底，不能让读 TopBar 的人以为这里"真的能模糊"。
     * 全仓扫描（不是列举文件）：新加一个 `GlassSurface(` 调用点就会自动被这条守住 ——
     * 它必须自己表态是兼容像素，还是诚实降级。
     */
    @Test
    fun sharedCallSitesDeclareTheCompatPolicyExplicitly() {
        val root = src("commonMain/kotlin")
        val glassDir = File(root, "vade/ui/skin/glass").invariantSeparatorsPath
        val offenders = mutableListOf<String>()
        var declared = 0
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            if (file.invariantSeparatorsPath.startsWith(glassDir)) return@forEach
            val code = codeOf(file)
            val calls = count(code, """GlassSurface\(""")
            val compat = count(code, """backdropPolicy = GlassBackdropPolicy\.HazeAlwaysCompat""")
            val honest = count(code, """backdropPolicy = GlassBackdropPolicy\.DegradeWithoutBackdrop""")
            declared += compat
            if (calls != compat + honest) {
                offenders += "${file.path}: $calls 处 GlassSurface，但只有 $compat 处声明兼容模式" +
                    "/$honest 处声明诚实降级 —— 共享调用点必须显式声明 HazeAlwaysCompat"
            }
            if (count(code, """degradeWithoutBackdrop""") > 0) {
                offenders += "${file.path}: 旧的假旗标 degradeWithoutBackdrop 又回来了"
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "共享调用点的像素链被改动了 —— 它们必须显式声明 HazeAlwaysCompat：\n" +
                offenders.joinToString("\n"),
        )
        assertTrue(
            declared >= 8,
            "共享调用点至少 8 处（MaterialSkin 底栏 / TopBar / GlassNavBar / SearchOverlay ×2 / " +
                "ModelPickerSheet ×2 / ChatThread ×2），实际只找到 $declared 处声明 —— " +
                "扫描或接线漏了",
        )
    }

    /** glass 皮肤自己的四个组件必须显式声明诚实策略，三处长文本层必须真的垫内容底。 */
    @Test
    fun glassComponentsUseTheHonestPolicyAndABase() {
        val glass = codeOf(src("commonMain/kotlin/vade/ui/skin/glass/GlassSkin.kt"))
        assertEquals(
            4,
            count(glass, """backdropPolicy = GlassBackdropPolicy\.DegradeWithoutBackdrop"""),
            "Button / Card / InputBar / 用户气泡四处都要显式声明诚实策略 —— " +
                "少一处就退回到「玻璃层采不到背景时什么都不画」",
        )
        assertEquals(
            3,
            count(glass, """(?m)^\s+ContentBase\("""),
            "Card / InputBar / 用户气泡三处必须共用同一个内容底（审查 P1-4）；" +
                "只给 Card 垫、让输入栏与气泡直接压在玻璃上，就是审查说的「没有内容底」",
        )
    }
}
