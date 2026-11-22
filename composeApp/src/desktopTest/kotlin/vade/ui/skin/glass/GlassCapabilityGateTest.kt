package vade.ui.skin.glass

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 审查 P1-1（Android 谎报模糊）与 P0-1（`HazeAlwaysCompat` 短路能力阶梯）的回归闸门。
 *
 * 两条缺陷的共同点：**没有一行代码是"错误"的，但代码在说谎**。
 *
 *  - `GlassCapability.android.kt` 曾无条件返回 `Blur` —— minSdk 是 24，Android 7–11 上
 *    `Modifier.blur` / `RenderEffect` 根本不存在，玻璃层声称有模糊、实际什么都没有。
 *    这里用**可注入 SDK 的纯函数**把 21…35 全走一遍，不需要一台 Android 11 设备。
 *  - `GlassSurface` 曾有一个 `GlassBackdropPolicy.HazeAlwaysCompat` 覆盖入口：平台能力是
 *    `TintStroke` 时它仍把结论拉回"建 haze 节点"。haze 在没有 `RenderEffect` 的平台上只画
 *    一层 tint ⇒ 真机上的顶栏 / 搜索浮层 / 模型卡 / 图片预览全变成灰罩，`TintStroke` 那一档
 *    （染色 + 描边 + 高光）一次都没执行过。现在**没有覆盖入口**：`drawsHazeLayer` 只认
 *    `capability.blurs`，源码守卫保证这两个词都不会再出现在调用点。
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
     * actual 必须把 `Build.VERSION.SDK_INT` 递给 [androidGlassCapability]，并且平台的
     * 降级日志指纹里必须有那个 SDK 号（审查 P1-10：真机上"变成灰罩"必须能在 logcat 里看到）。
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
        assertTrue(
            android.contains("glassPlatformDiagnostic") && android.contains("SDK_INT"),
            "降级日志必须带 SDK 号：一台 Android 11 的 logcat 要能区分「能力不足」与「背景没接上」",
        )
    }

    // --- 2 · 能力是唯一门禁 ----------------------------------------------------------

    /**
     * 「建不建 haze 节点」**只**由能力决定。
     *
     * 旧契约是 `drawsHazeLayer(capability, policy)`，其中 `HazeAlwaysCompat` 在
     * `TintStroke` 下也返回 true —— 真机上的灰罩就是这么来的。现在函数只收一个参数：
     * 想覆盖它就必须改签名，而签名被这条测试（与调用点源码守卫）同时钉住。
     */
    @Test
    fun hazeIsDrawnOnlyWhenThePlatformReallyBlurs() {
        assertFalse(
            drawsHazeLayer(GlassCapability.TintStroke),
            "TintStroke ⇒ 不建 haze 节点（采不到背景的 haze 链什么都不画，只剩一层灰罩）",
        )
        for (capability in listOf(GlassCapability.Blur, GlassCapability.Refraction)) {
            assertTrue(drawsHazeLayer(capability), "$capability 真的能模糊，必须照常建节点")
        }
        // 能力本身仍然只看真实背景 —— 这条是 P1-5 的核心。
        assertEquals(
            GlassCapability.TintStroke,
            glassCapability(hasBackdrop = false),
            "没有背景就是没有背景：glassCapability 只接受真实值",
        )
    }

    /**
     * 全仓扫描：`GlassBackdropPolicy` / `backdropPolicy` **一个字都不许剩**。
     *
     * 这条替代了上一版的"每个调用点必须显式声明兼容策略"。旧守卫把缺陷制度化了：
     * 它要求 12 处调用点写 `HazeAlwaysCompat`，于是"API<31 仍建节点"变成**不可修** ——
     * 任何修复都会让守卫变红。现在守卫守的是反面：没有覆盖入口，也没有人参保。
     */
    @Test
    fun noCallSiteCanOverrideTheCapability() {
        val root = src("commonMain/kotlin")
        val definition = src("commonMain/kotlin/vade/ui/skin/glass/GlassSurface.kt").invariantSeparatorsPath
        val offenders = mutableListOf<String>()
        var callSites = 0
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val code = codeOf(file)
            if (count(code, """GlassBackdropPolicy|backdropPolicy""") > 0) {
                offenders += "${file.path}: 能力覆盖入口又回来了（HazeAlwaysCompat 就是 P0-1 的灰罩）"
            }
            // 定义点不算调用点（`fun GlassSurface(`）。
            if (file.invariantSeparatorsPath != definition) {
                callSites += count(code, """GlassSurface\(""")
            }
        }
        assertTrue(offenders.isEmpty(), offenders.joinToString("\n"))
        assertTrue(
            callSites >= 18,
            "全仓 GlassSurface 调用点应当 ≥18（shell 6 + glass 皮肤 11 + material/miuix 3），" +
                "实际 $callSites —— 扫描路径或调用点被改坏了",
        )
        // `drawsHazeLayer` 只能有一个参数（调用点无法再塞策略）。
        val gate = codeOf(src("commonMain/kotlin/vade/ui/skin/glass/GlassCapability.kt"))
        assertEquals(
            1,
            count(gate, """fun drawsHazeLayer\(capability: GlassCapability\): Boolean"""),
            "drawsHazeLayer 必须只吃能力：多一个参数就是 P0-1 的覆盖入口回来了",
        )
    }

    /**
     * glass 皮肤**全部 11 个**玻璃组件必须走同一条门禁，承载文字/图标的层必须真的垫内容底。
     *
     * 52-U3 · T2 起组件的画法搬到 `GlassParts.kt`（7 个）与 `GlassSkin.kt`（4 个）两个文件，
     * 所以这里按**两个文件合起来**数，而不是数某一个文件的 4 处：
     *
     *  - 11 处 `GlassSurface(`：12 个 C-09 组件里 `TextField` 仍转发 material（玻璃版留给下一批），
     *    所以是 11 而不是 12；
     *  - 8 处 `GlassContentBase(`：Card / InputBar / 用户气泡 / 行 / 底栏 / 弹层 / 芯片 / 工具卡
     *    共用同一个内容底（审查 P1-4 的口径）。`SendOrb` 不在此列 —— 48dp 的球上放不下第二层
     *    1dp inset 的底，它的**染色本身**就是内容底，对比度由 `GlassComponentContrastTest`
     *    按 `contentBaseContrast` 单独钉住。
     */
    @Test
    fun glassComponentsUseTheSharedGateAndABase() {
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
