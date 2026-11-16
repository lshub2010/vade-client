package vade.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import vade.ui.data.SourceKeys

/**
 * P1-4 · 错误屏的**文案**守卫：配置读不出来时，屏上必须说清楚是哪一句、哪个值、
 * 以及可以照抄的修法 —— 一个「出错了」的黑屏正是这条要修的东西。
 */
class ConfigErrorScreenTest {

    @Test
    fun reasonKeepsTheParsersOwnMessage() {
        val err = IllegalArgumentException(
            "source.port: '8443x' is not an integer in 1..65535 — refusing to fall back to the default",
        )
        val text = configErrorReason(err)
        assertTrue(text.startsWith("IllegalArgumentException: "), "类型名要上屏：$text")
        assertTrue("8443x" in text, "非法值要原样出现（用户要看到自己填错的那个值）：$text")
        assertTrue("1..65535" in text, "合法区间要出现（错误屏 = 可照抄的修法）：$text")
    }

    @Test
    fun reasonNeverRendersAnEmptyMessage() {
        // message 为 null 时退回类型名 —— 绝不显示一张「出错了」却不说为什么的屏。
        assertEquals("RuntimeException", configErrorReason(RuntimeException()))
        assertEquals("RuntimeException", configErrorReason(RuntimeException("   ")))
    }

    @Test
    fun keysInTheCopyComeFromTheParserTable() {
        // g-rescue-text 的教训：写给用户看的键名必须与解析器读的键名同源。
        for (key in SourceKeys.acceptedKeys) {
            assertTrue(key in CONFIG_ERROR_KEYS, "错误屏必须列出键 `$key`（来自 SourceKeys.acceptedKeys）")
        }
        assertTrue("8443" in CONFIG_ERROR_EXAMPLES, "示例里要有默认端口 8443")
    }
}
