package vade.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import vade.ui.skin.material.MaterialSkin

/**
 * w1-material · v2 token anti-regression（docs/visual-language.md §10/§2/§5/§8）。
 *
 * v2 观感大部分是视觉的，机器可验的下限钉在这里：
 *  - 六档圆角阶梯与 v1 别名（MaterialParts / MiuixParts / 页面都在读）；
 *  - 石墨中性色 + 单一强调色 —— 绝不是 Material 默认紫；
 *  - 玻璃 / 分层阴影 / 辉光 token 存在且确实是半透明的；
 *  - material 源码保持干净：无 M3 主题组件、无紫色字面量、无文字形态的发送/停止按钮。
 */
class MaterialSkinV2Test {

    private val skin = MaterialSkin()

    // --- §10 六档圆角 ---------------------------------------------------------

    @Test
    fun sixStepRadiusLadder() {
        val s = skin.shapes
        assertEquals(12.dp, s.xs)
        assertEquals(16.dp, s.sm)
        assertEquals(20.dp, s.md)
        assertEquals(24.dp, s.lg)
        assertEquals(28.dp, s.xl)
        assertEquals(32.dp, s.sheet)
        assertEquals(999.dp, s.pill)
        // v1 别名骑在抬升后的档位上（Skin.kt 文档即契约）。
        assertEquals(s.sm, s.small)
        assertEquals(s.md, s.medium)
        assertEquals(s.lg, s.large)
    }

    @Test
    fun adjacentStepsDifferByAtLeast4dp() {
        val steps = listOf(
            skin.shapes.xs,
            skin.shapes.sm,
            skin.shapes.md,
            skin.shapes.lg,
            skin.shapes.xl,
            skin.shapes.sheet,
        ).map { it.value }
        steps.zipWithNext().forEach { (a, b) ->
            assertTrue(b - a >= 4f, "相邻档差 ≥4dp（§10），否则像没对齐: $steps")
        }
    }

    // --- §2 石墨中性色，不是 Material 紫 --------------------------------------

    @Test
    fun paletteIsGraphiteIndigoNotMaterialPurple() {
        val c = skin.colors
        assertEquals(Color(0xFF5B5BD6), c.primary, "强调色 = 石墨靛（§2）")
        assertTrue(c.primary != Color(0xFF6750A4), "Material 默认紫被禁（§七.5 / 任务 §8）")
        assertEquals(Color(0xFFF7F7F8), c.canvas)
        assertEquals(Color(0xFFE6E6E9), c.hairline)
        assertEquals(Color(0xFFF0F0F2), c.surfaceVariant, "surfaceSunken 代码块底")
        assertEquals(Color(0xFF2F7D5A), c.success)
        assertEquals(Color(0xFFB26A00), c.warning)
    }

    // --- §5/§8 玻璃 + 分层阴影 -------------------------------------------------

    @Test
    fun glassAndShadowLayersAreTranslucentAndTiered() {
        val c = skin.colors
        assertTrue(c.surfaceGlass.alpha < 1f, "玻璃底必须透（§5），不是不透明色块")
        assertTrue(c.shadowFloat.alpha > c.shadowSoft.alpha, "浮层阴影比静态卡阴影更深（§8 分层）")
        assertTrue(c.shadowSoft.alpha < 1f && c.shadowFloat.alpha < 1f)
        assertEquals(c.primary, c.glowAccent, "主行动辉光 = accent（§8 row 3）")
    }

    // --- 源码级防回退 -----------------------------------------------------------

    @Test
    fun materialSourcesStayV2Clean() {
        val dir = File("src/commonMain/kotlin/vade/ui/skin/material")
        assertTrue(dir.isDirectory, "test must run from ui/composeApp: ${dir.path}")
        val offenders = mutableListOf<String>()
        dir.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            if ("0xFF6750A4" in text || "#6750A4" in text) offenders += "${file.name}: Material default purple"
            for (banned in listOf(
                "import androidx.compose.material3.OutlinedTextField",
                "import androidx.compose.material3.Switch",
                "import androidx.compose.material3.Button",
            )) {
                if (banned in text) offenders += "${file.name}: ${banned.removePrefix("import ")}（M3 默认紫态，改 foundation 原语）"
            }
            for (wordLabel in listOf("\"发送\"", "\"停止\"")) {
                if (wordLabel in text) offenders += "${file.name}: 文字按钮 $wordLabel（v2 §3 图标即按钮）"
            }
        }
        assertTrue(offenders.isEmpty(), "material 皮肤必须保持 v2 观感: $offenders")
    }
}
