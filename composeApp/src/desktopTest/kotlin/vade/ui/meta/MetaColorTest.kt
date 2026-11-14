package vade.ui.meta

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 测试②：颜色输出格式钉死为**大写 `#RRGGBB`** —— 与服务端
 * `COLOR_PATTERN = ^#[0-9a-fA-F]{6}$`（manager.py L89）同一判定面，
 * 客户端统一归一成大写，避免同一颜色两种写法来回触发 update。
 */
class MetaColorTest {

    @Test
    fun normalizeOutputsUppercaseRrggbb() {
        assertEquals("#FF00AA", MetaColors.normalize("#ff00aa"))
        assertEquals("#FF00AA", MetaColors.normalize("FF00AA")) // 无 # 也可
        assertEquals("#ABCDEF", MetaColors.normalize(" #aBcDeF ")) // 大小写混排 + 空白
        // 归一是幂等的：#RRGGBB 进 → #RRGGBB 出。
        assertEquals("#3B82F6", MetaColors.normalize("#3B82F6"))
    }

    @Test
    fun normalizePreservesEachChannel() {
        // 位域重拼不能丢前导零：#010203 不是 #10203。
        assertEquals("#010203", MetaColors.normalize("010203"))
        assertEquals("#000000", MetaColors.normalize("#000000"))
        assertEquals("#FFFFFF", MetaColors.normalize("ffffff"))
    }

    @Test
    fun normalizeRejectsAnythingOutsideServerPattern() {
        listOf(
            "", // 空
            "#", // 只有井号
            "fff", // 3 位缩写不支持
            "#fff",
            "ff00a", // 5 位
            "#ff00aa1", // 7 位
            "ff00aa1", // 7 位（无 #）
            "#gg0000", // 非十六进制
            "#ff00aa;", // 尾部垃圾
            "红红红红红红", // 非 ASCII
        ).forEach { bad ->
            assertEquals(null, MetaColors.normalize(bad), "非法输入必须被拒：$bad")
            assertFalse(MetaColors.isValid(bad), "isValid 与 normalize 同一判定面：$bad")
        }
    }

    @Test
    fun isValidAcceptsEveryLegalWriting() {
        assertTrue(MetaColors.isValid("#ef4444"))
        assertTrue(MetaColors.isValid("#EF4444"))
        assertTrue(MetaColors.isValid("EF4444"))
        assertTrue(MetaColors.isValid("#0000Ab"))
    }

    @Test
    fun paletteHas10PresetColorsAllValidAndDistinct() {
        assertTrue(
            MetaColors.presets.size in 8..12,
            "调色盘必须是 8–12 个预设（本轮 10），不做任意取色器",
        )
        assertEquals(MetaColors.presets.size, MetaColors.presets.toSet().size, "预设色不得重复")
        MetaColors.presets.forEach { hex ->
            assertTrue(MetaColors.isValid(hex), "预设色必须是合法 #RRGGBB：$hex")
            // 预设本身就以大写规范形书写，输出格式与归一结果一致（测试②的钉面）。
            assertEquals(hex, MetaColors.normalize(hex), "预设应以大写 #RRGGBB 书写：$hex")
        }
    }

    @Test
    fun parseGivesChannelAccurateColorAndFallsBackGrayForGarbage() {
        val red = MetaColors.parse("#FF0000")
        assertEquals(1f, red.red)
        assertEquals(0f, red.green)
        assertEquals(0f, red.blue)
        // 大小写不同写法解析到同一颜色。
        assertEquals(MetaColors.parse("#ef4444"), MetaColors.parse("#EF4444"))
        // 非法输入不崩溃：落到中性灰 #64748B（预设最后一档）。
        assertEquals(MetaColors.parse("#64748B"), MetaColors.parse("garbage"))
    }
}
