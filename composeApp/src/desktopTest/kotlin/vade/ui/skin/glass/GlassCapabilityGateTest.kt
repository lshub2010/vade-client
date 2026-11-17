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

    /**
     * glass 皮肤**全部 11 个**玻璃组件必须显式声明诚实策略，承载文字/图标的层必须真的垫内容底。
     *
     * 52-U3 · T2 起组件的画法搬到 `GlassParts.kt`（7 个）与 `GlassSkin.kt`（4 个）两个文件，
     * 所以这里按**两个文件合起来**数，而不是数某一个文件的 4 处：
     *
     *  - 11 处 `GlassSurface(` ↔ 11 处 `DegradeWithoutBackdrop`：少一处就退回「玻璃层采不到
     *    背景时什么都不画」；
     *  - 12 个 C-09 组件里 `TextField` 仍转发 material（玻璃版留给下一批），所以是 11 而不是 12；
     *  - 8 处 `GlassContentBase(`：Card / InputBar / 用户气泡 / 行 / 底栏 / 弹层 / 芯片 / 工具卡
     *    共用同一个内容底（审查 P1-4 的口径）。`SendOrb` 不在此列 —— 48dp 的球上放不下第二层
     *    1dp inset 的底，它的**染色本身**就是内容底，对比度由 `GlassComponentContrastTest`
     *    按 `contentBaseContrast` 单独钉住。
     */
    @Test
    fun glassComponentsUseTheHonestPolicyAndABase() {
        val root = src("commonMain/kotlin/vade/ui/skin/glass")
        val code = listOf("GlassSkin.kt", "GlassParts.kt")
            .joinToString("\n") { codeOf(File(root, it)) }

        assertEquals(
            11,
            count(code, """GlassSurface\("""),
            "玻璃皮肤的 11 个组件 = 12 个 C-09 组件 − 仍转发 material 的 TextField；" +
                "数字对不上说明有人新增/删掉了组件，或把某个组件退回给 material",
        )
        assertEquals(
            11,
            count(code, """backdropPolicy = GlassBackdropPolicy\.DegradeWithoutBackdrop"""),
            "每一处 GlassSurface 都要显式声明诚实降级 —— 少一处就退回「采不到背景时什么都不画」",
        )
        assertEquals(
            8,
            count(code, """(?m)^\s+GlassContentBase\("""),
            "8 个承载文字/图标的玻璃层必须垫同一个内容底（Card/InputBar/气泡/行/底栏/弹层/芯片/工具卡）；" +
                "少一处就是审查说的「没有内容底」",
        )
        // 7 个 T2 组件不许再转发 material（与 `GlassPartsTest` 的同名守卫互为交叉验证）。
        listOf("ListItem", "Switch", "BottomBar", "ApprovalDialog", "AttachmentChip", "ToolCallCard", "SendOrb")
            .forEach { name ->
                assertEquals(
                    0,
                    count(code, """= base\.$name\("""),
                    "$name 又变回 `= base.$name(...)` —— 玻璃皮肤转发 material 就等于没做（I2）",
                )
            }
    }
}
