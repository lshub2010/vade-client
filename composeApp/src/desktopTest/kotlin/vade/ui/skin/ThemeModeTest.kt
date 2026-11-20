package vade.ui.skin

import java.io.File
import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import vade.ui.skin.material.MaterialSkin
import vade.ui.skin.miuix.MiuixSkin

/**
 * g-dark-follow · 主题解析规则的桌面测试面。
 *
 * [ThemeController.resolveDark] 是纯函数（不碰组合、不碰平台探测），所以三态规则
 * 在 JVM 上就能确定性验证：系统暗 → 暗、系统亮 → 亮、手选优先（覆盖系统）。
 * 「接线真的生效」（override 变化 → 皮肤工厂 var 同步 → 注册表产出对应主题）
 * 在 [vade.ui.ThemeSwitchUiTest] 里走真组合验证；这里再加一条源码钉子，防止
 * App.kt 的接线被「简化」掉。
 *
 * 亮色 golden 表：把改动前（origin/main 53625dc）的亮色值逐槽钉死——接线处任何意外
 * 漏进 token 的改动都会红。值来源是 `MaterialTokens.lightColors` /
 * `MiuixTokens.lightColors` 原文，与 docs/visual-language.md §2 亮色表一致。
 *
 * r3-a11y（2026-11-19）是这张表的**一处**有意例外：material 亮色 `error` 由 #D93A3A
 * 提到 #C93030（原值压在输入框的 #F0F0F2 上只有 4.00:1，错误行是正文）。其余槽位仍然
 * 逐槽钉死，这一处也在断言旁写明了理由。
 */
class ThemeModeTest {

    // --- 三态解析规则：手选优先，未手选跟系统 ------------------------------------

    private inline fun withOverride(mode: ThemeMode, block: () -> Unit) {
        val savedOverride = ThemeController.override
        val savedSystem = ThemeController.systemDark
        ThemeController.override = mode
        try {
            block()
        } finally {
            ThemeController.override = savedOverride
            ThemeController.systemDark = savedSystem
        }
    }

    @Test
    fun followSystemResolvesLightWhenSystemIsLight() = withOverride(ThemeMode.FollowSystem) {
        ThemeController.systemDark = false
        assertFalse(ThemeController.resolveDark(), "系统亮 → 解析为亮")
    }

    @Test
    fun followSystemResolvesDarkWhenSystemIsDark() = withOverride(ThemeMode.FollowSystem) {
        ThemeController.systemDark = true
        assertTrue(ThemeController.resolveDark(), "系统暗 → 跟随系统解析为暗")
    }

    @Test
    fun manualDarkWinsOverLightSystem() = withOverride(ThemeMode.Dark) {
        ThemeController.systemDark = false
        assertTrue(ThemeController.resolveDark(), "手选暗覆盖系统亮 → 暗（覆盖优先）")
    }

    @Test
    fun manualLightWinsOverDarkSystem() = withOverride(ThemeMode.Light) {
        ThemeController.systemDark = true
        assertFalse(ThemeController.resolveDark(), "手选亮覆盖系统暗 → 亮（覆盖优先）")
    }

    // --- 接线钉子：App.kt 必须把主题放进 remember key（代码在 ≠ 接上了）------------

    @Test
    fun appWiresThemeResolutionIntoTheSkinRememberKey() {
        val src = File("src/commonMain/kotlin/vade/ui/App.kt").readText()
        // g-route-transition 之后 App.kt 用 import 的短形式（ThemeController.currentDark()），
        // 守卫意图是「remember key 里必须有 currentDark()」——FQN 与短形式都算接上（G5 批修）。
        val wired = Regex("remember\\((state\\.skinId|effectiveSkinId),\\s*(vade\\.ui\\.skin\\.)?ThemeController\\.currentDark\\(\\)")
        assertTrue(
            wired.containsMatchIn(src),
            "App.kt 的 skin remember 必须带 ThemeController.currentDark() 作 key——" +
                "主题变化才走「换 LocalSkin 的值」而不是别的重建路径",
        )
    }

    // --- 亮色 token golden（逐槽钉值；error 一处见 r3-a11y）------------------------

    @Test
    fun materialLightTokensStayPinned() {
        val c = MaterialSkin(dark = false).colors
        assertEquals(Color(0xFF5B5BD6), c.primary, "material 亮 primary")
        assertEquals(Color(0xFFFFFFFF), c.onPrimary)
        assertEquals(Color(0xFFFFFFFF), c.surface)
        assertEquals(Color(0xFFF0F0F2), c.surfaceVariant)
        assertEquals(Color(0xFF17171A), c.onSurface)
        assertEquals(Color(0xFF6B6B73), c.onSurfaceVariant)
        assertEquals(Color(0xFFE6E6E9), c.outline)
        // r3-a11y · 亮色 error #D93A3A → #C93030：原值压在实际用到的 surfaceVariant(#F0F0F2)
        // 上只有 4.00:1，错误行是正文文字。新值三档底（白/canvas/输入底）分别 5.32 / 4.97 / 4.67。
        assertEquals(Color(0xFFC93030), c.error)
        assertEquals(Color(0xFFFFFFFF), c.onError)
        assertEquals(Color(0xFFF7F7F8), c.canvas, "§2 亮色画布")
        assertEquals(Color(0xFFE6E6E9), c.hairline)
        assertEquals(Color(0xFF2F7D5A), c.success)
        assertEquals(Color(0xFFB26A00), c.warning)
        assertEquals(Color(0xFFFFFFFF).copy(alpha = 0.84f), c.surfaceGlass)
        assertEquals(Color(0x4D000000), c.shadowSoft)
        assertEquals(Color(0x80000000), c.shadowFloat)
        assertEquals(Color(0xFF5B5BD6), c.glowAccent)
        assertEquals(Color(0xFF17171A), c.textOnGlass)
        assertFalse(c.isDark)
        // §5 玻璃染色：亮色用探针默认值（本批不动 Skin.glass 的亮色分支）
        assertEquals(
            Color.White.copy(alpha = 0.26f),
            MaterialSkin(dark = false).glass.tintLight,
            "亮色玻璃染色 White@26%",
        )
    }

    @Test
    fun miuixLightTokensAreUnchanged() {
        val c = MiuixSkin(dark = false).colors
        assertEquals(Color(0xFF2A62D4), c.primary, "miuix 亮 primary（#2E6BE6 压暗后的 4.9:1）")
        assertEquals(Color(0xFFFFFFFF), c.onPrimary)
        assertEquals(Color(0xFFFFFFFF), c.surface)
        assertEquals(Color(0xFFF2F1EF), c.surfaceVariant)
        assertEquals(Color(0xFF141416), c.onSurface)
        assertEquals(Color(0xFF66666C), c.onSurfaceVariant)
        assertEquals(Color(0xFFDCDAD5), c.outline)
        assertEquals(Color(0xFFC03328), c.error)
        assertEquals(Color(0xFFFFFFFF), c.onError)
        assertEquals(Color(0xFFF2F1EF), c.canvas)
        assertEquals(Color(0xFFDCDAD5), c.hairline)
        // success/warning 两个槽位在 miuix 里**有意保持默认回落**（success→primary、
        // warning→error）：MiuixParts 的状态条读的是 MiuixTokens.success()/warn()
        // 两个函数（#2F7D5A / #B26A00），不读 SkinColors 槽位——这是改前现状，照钉。
        assertEquals(Color(0xFF2A62D4), c.success, "miuix success = 默认回落 primary（状态条走 MiuixTokens）")
        assertEquals(Color(0xFFC03328), c.warning, "miuix warning = 默认回落 error（状态条走 MiuixTokens）")
        assertEquals(Color.White.copy(alpha = 0.78f), c.surfaceGlass)
        assertEquals(Color.Black.copy(alpha = 0.06f), c.shadowSoft)
        assertEquals(Color.Black.copy(alpha = 0.18f), c.shadowFloat)
        assertEquals(Color(0xFF2A62D4), c.glowAccent)
        assertEquals(Color(0xFF141416), c.textOnGlass)
        assertFalse(c.isDark)
    }

    // --- r3-a11y（P3）· miuix 亮色 error 的 WCAG 对比度（公式断言） ---------------------

    /**
     * WCAG 2.x 相对亮度 / 对比度，纯 Kotlin（与 `MaterialSkinDarkTest` 同一套公式，
     * 不引依赖）。miuix 此前的 error 只有「钉值」断言：值抄对了不代表压在每种亮色底上
     * 都过 4.5:1 —— 审计要求补一条与 material 同型的**公式**断言。
     */
    private fun channel(c: Double): Double =
        if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun relLum(color: Color): Double =
        0.2126 * channel(color.red.toDouble()) +
            0.7152 * channel(color.green.toDouble()) +
            0.0722 * channel(color.blue.toDouble())

    private fun contrast(a: Color, b: Color): Double {
        val la = relLum(a)
        val lb = relLum(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /**
     * r3-a11y（P3）· miuix 亮色 `error`（#C03328）是正文文字（输入框错误行、状态条），
     * 亮色下它会压在 surface（#FFFFFF）与 canvas/surfaceVariant（#F2F1EF，也是
     * `MiuixSkin.TextField` 的输入底）上。两档都必须 ≥4.5:1（WCAG AA），
     * 外加白字 `onError` 压 error 实底（危险按钮）同样 ≥4.5。
     *
     * 实算：白 5.607:1、#F2F1EF 4.967:1。
     */
    @Test
    fun miuixLightErrorTextClearsWcagAaOnEveryLightBackground() {
        val c = MiuixSkin(dark = false).colors
        assertEquals(Color(0xFFC03328), c.error, "被测 token 先钉住（改配色必须同时看对比度）")
        val backgrounds = listOf(
            "surface #FFFFFF" to c.surface,
            "canvas/surfaceVariant #F2F1EF" to c.surfaceVariant,
        )
        backgrounds.forEach { (name, bg) ->
            val ratio = contrast(c.error, bg)
            assertTrue(
                ratio >= 4.5,
                "miuix 亮色 error 文字压在 $name 上只有 $ratio:1 —— 错误行是正文，要求 ≥4.5:1（WCAG AA）",
            )
        }
        assertTrue(
            contrast(c.onError, c.error) >= 4.5,
            "miuix 亮色 onError/error（危险按钮上的白字）只有 ${contrast(c.onError, c.error)}:1",
        )
    }

    // --- 三态枚举与用户可读名（设置页 chips 的文案面）-------------------------------

    @Test
    fun themeModesCarryTheirUserFacingLabels() {
        assertEquals(listOf("跟随系统", "亮色", "暗色"), ThemeMode.entries.map { it.label })
        assertEquals(3, ThemeMode.entries.size, "三态：跟随系统 / 亮 / 暗——加态先改这条")
        assertEquals(ThemeMode.FollowSystem, ThemeMode.entries.first(), "跟随系统是第一态（默认）")
    }
}
