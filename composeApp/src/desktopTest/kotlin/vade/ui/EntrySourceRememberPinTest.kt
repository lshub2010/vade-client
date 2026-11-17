package vade.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P0 · 入口的源必须**组合期只建一次**（remember），三端同一形状。
 *
 * 事故形态：f4 合并把 androidMain 的 `remember { … }` 丢了，留下
 * `val booted = runCatching { sessionSourceFromEnvironment() }`。那一行在
 * `setContent { }` 里，于是**任何**重组（暗色切换、IME/inset 变化、任一 state 读取）
 * 都会新建一个 `RealSessionSource`：`App()` 的 `remember(effectiveSource)` 换 key →
 * 新 `UiState` → 新 `VadeClient`。旧 client 的 runner 没有任何人关，按 500 ms→30 s
 * 退避无限重连，新 client 又从头连一次 —— 用户看到的是「界面自己把连接掐了又接」，
 * core 侧则是一串重复的连接。
 *
 * 为什么用源码扫描而不是组合测试：这段代码在 `androidMain` 的 `ComponentActivity`
 * 里，desktop/wasm 既编不到也跑不到它；而「重组是否重建了源」在桌面上根本不会发生
 * （桌面入口已经写着 `remember`，所以桌面测试对这一行永远是绿的 —— 正是它让 P0
 * 溜进了合并）。可测的那一半是**结构**：与 `TopGlassGuardTest` 的 androidMain 扫描
 * 同一形态，注释先剥掉，免得 KDoc 里的解释文字把钉子自己钉绿。
 */
class EntrySourceRememberPinTest {

    /** Module dir: Gradle runs desktopTest with the composeApp module as working dir. */
    private fun codeOf(path: String): String {
        val file = File("src/$path")
        assertTrue(file.isFile, "guard target missing: ${file.absolutePath}")
        val noBlock = file.readText().replace(Regex("(?s)/\\*.*?\\*/"), "")
        return noBlock.split('\n').joinToString("\n") { it.substringBefore("//") }
    }

    private fun count(code: String, pattern: String): Int =
        Regex(pattern).findAll(code).count()

    @Test
    fun androidEntryRemembersTheBootedSource() {
        val main = codeOf("androidMain/kotlin/vade/ui/MainActivity.kt")
        assertEquals(
            1,
            count(main, """val booted = remember\s*\{"""),
            "Android 入口的源必须 `remember`：少了它，每一次重组都新建一个 RealSessionSource " +
                "→ 新 UiState → 新 VadeClient，旧 client 的 runner 无人关闭、按退避无限重连（P0）",
        )
        assertEquals(
            0,
            count(main, """val booted\s*=\s*runCatching"""),
            "回归形态（丢 remember）不允许再出现 —— 解析失败的错误屏语义不变，" +
                "但解析动作本身必须只在组合期发生一次",
        )
        assertEquals(
            1,
            count(main, """sessionSourceFromEnvironment\(\)"""),
            "入口只允许一处建源：多出来的第二处就是「谁都能重建一个 client」",
        )
    }

    /**
     * 桌面入口是这条约定的参照实现（它一直是对的）：两端必须同形，否则「Android 又少了
     * 一个 remember」不会被任何东西看见。
     */
    @Test
    fun desktopEntryKeepsTheSameRememberShape() {
        val main = codeOf("desktopMain/kotlin/vade/ui/Main.kt")
        assertEquals(1, count(main, """val booted = remember\s*\{"""))
        assertEquals(1, count(main, """sessionSourceFromEnvironment\(\)"""))
    }

    /**
     * wasm 入口不在组合期建源（`main()` 里算一次，组合只读捕获的局部值），所以它不需要
     * remember —— 但同样只允许一处调用点，免得有人把解析搬进 `ComposeViewport { }` 里。
     */
    @Test
    fun wasmEntryBuildsTheSourceExactlyOnce() {
        val main = codeOf("wasmJsMain/kotlin/vade/ui/main.kt")
        assertEquals(1, count(main, """sessionSourceFromEnvironment\(\)"""))
    }
}
