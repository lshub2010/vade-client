package vade.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * g-first-run · P5：窗口标题不许再是脚手架期的「Vade · UI 骨架」。
 *
 * 首次真实验收的第 2c 步把 `capture-window.ps1` 的匹配行原样贴了出来：
 * `window matched … title='Vade · UI 骨架'`——Alpha 用户第一眼看到的产品名是「骨架」。
 * 产品名按仓库 `README.md` 第 3 行（Vade Agent Runtime，中文「偕行」），标题取 `Vade · 偕行`。
 *
 * 为什么用「常量 + 源码扫描」而不是去截一张窗口图：
 *  - 标题是 `Window(title = …)` 的入参，桌面 JVM 单测里没有真窗口可读（`runComposeUiTest`
 *    开的是离屏场景，没有 `java.awt.Window`），所以**取值**由常量这条唯一来源钉住；
 *  - 「Main.kt 真的把这个常量交给窗口」是结构事实，扫描可验证——扫描前先剥注释，否则
 *    `Main.kt` 里记录病历的那句「UI 骨架」会自己把自己判红（历史引用必须允许出现在注释里）。
 *
 * `tools/capture-window.ps1` 按窗口类（`SunAwt*`）+ 尺寸（>300×300）匹配，不按标题，
 * 所以改标题不会打断截图链路——这也是本棒能只改标题的原因。
 */
class WindowTitleTest {

    /** Module dir: Gradle runs desktopTest with the composeApp module as working dir. */
    private fun src(path: String) = File("src/$path")

    /** 与 `TopGlassGuardTest` 同一个理由：守卫匹配**代码**，不匹配病历里的引用。 */
    private fun stripComments(text: String): String =
        text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//.*"""), "")

    private val desktopMain by lazy { File("src/desktopMain") }

    // ------------------------------------------------------------------ 1 · 值

    @Test
    fun windowTitleIsTheProductNameNotTheSkeletonPlaceholder() {
        assertEquals("Vade · 偕行", WINDOW_TITLE, "窗口标题 = 产品名（README：Vade Agent Runtime，中文「偕行」）")
        assertTrue(WINDOW_TITLE.startsWith("Vade"), "产品名前缀不能丢：$WINDOW_TITLE")
        assertFalse(WINDOW_TITLE.contains("骨架"), "「骨架」是脚手架期的说法，不许出现在用户看到的地方")
        assertFalse(WINDOW_TITLE.contains("UI "), "标题里不该再有内部阶段名：$WINDOW_TITLE")
    }

    // ------------------------------------------------------------------ 2 · 接线

    @Test
    fun mainKtPutsTheConstantOnTheWindowInsteadOfALiteral() {
        val main = stripComments(src("desktopMain/kotlin/vade/ui/Main.kt").readText())
        val firstWindow = main.indexOf("Window(")
        assertTrue(firstWindow >= 0, "Main.kt 里找不到 Window( —— 扫描目标变了")
        // 产品窗口是文件里的第一个 Window(；它的 title 必须是常量，且第一个 title 赋值就是它
        // （`--motion-probe` 的探针窗口有自己的字面量标题，那是诊断面，不在本棒范围）。
        val titleAt = main.indexOf("title =", firstWindow)
        assertTrue(titleAt > firstWindow, "第一个 Window( 里没有 title 赋值 —— 扫描目标变了")
        val titleLine = main.substring(titleAt, minOf(main.length, titleAt + 28))
        assertTrue(
            titleLine.startsWith("title = WINDOW_TITLE"),
            "产品窗口的标题必须来自 WINDOW_TITLE 常量，实际：${titleLine.take(20)}",
        )
    }

    // ------------------------------------------------- 3 · 源码里再没有骨架标题

    @Test
    fun desktopMainNeverSaysUiSkeletonOutsideComments() {
        assertTrue(desktopMain.isDirectory, "missing ${desktopMain.absolutePath} — test must run from ui/composeApp")
        val offenders = desktopMain.walkTopDown()
            .filter { it.extension == "kt" }
            .filter { stripComments(it.readText()).contains("UI 骨架") }
            .map { it.relativeTo(desktopMain).path }
            .toList()
        assertTrue(
            offenders.isEmpty(),
            "desktopMain 里还有代码级的「UI 骨架」（注释里的病历引用不算）：$offenders",
        )
    }
}
