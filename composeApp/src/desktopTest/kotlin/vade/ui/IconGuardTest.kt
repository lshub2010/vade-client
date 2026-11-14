package vade.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import vade.ui.icon.GlyphLiteralScan

/**
 * w2-icons 防回退守卫（docs/visual-language.md §3：图标必须是矢量 —— 不许字符字形、
 * 不许汉字当图标、不许 emoji）。
 *
 * 这条守卫**两次失效，两次都还是绿的**，所以现在的写法刻意做了两件事：
 *  - **扫描域**：整个 `commonMain/kotlin`（当前只有 `vade/ui` 一棵子树），不排除任何子目录。
 *    第一次失效就是把 `icon|shell|skin` 排除掉了 —— 被排除的目录等于没有保护。
 *  - **匹配形态**：不再手写「一条正则匹配 `Text("X")`」。字面量由
 *    [GlyphLiteralScan] 词法扫描摘出（剔注释、按行号定位），再由运行期拼出来的
 *    `^[<词表>]{1,4}$` 判类，于是 `skin.Button("×")`、
 *    `glyph = if (..) "▸" else "◂"`、`text = item.glyph ?: "•"`、跨行 `Text(\n "←"\n)`
 *    一视同仁。匹配器自身有独立自测（`GlyphLiteralScanTest`），把它改弱必红。
 *
 * 白名单（[allowed]）只在「本棒无权改、且另一棒正在改该文件」时使用，**必须写理由**；
 * 条目若对不上任何 offender 会被报成 stale，强制删除（白名单不许永久静默）。
 */
class IconGuardTest {

    /** 扫描域根：全部 commonMain 源码。 */
    private fun commonMainDir() = File("src/commonMain/kotlin")

    /** Module dir: Gradle runs desktopTest with the composeApp module as working dir. */
    private fun uiDir() = File(commonMainDir(), "vade/ui")

    /**
     * 行内白名单。当前**为空**。
     *
     * 本轮实扫出 4 处，全部在 `shell` 目录（`AppShell.kt` 的 `▸` / `◂` / `×`，
     * `FloatingBottomBar.kt` 的 `•`），归 g-glass 尾款与 g-float；按母棒规则
     * 「>3 处不放白名单、留红待处置」处理，清单见 shots/g-icon-guard/RESULT.md。
     */
    private val allowed: List<GlyphLiteralScan.Allow> = emptyList()

    @Test
    fun noCharacterIconTextLiterals() {
        val root = commonMainDir()
        assertTrue(root.isDirectory, "commonMain 不在预期位置：${root.absolutePath}")
        val result = GlyphLiteralScan.scan(root, allowed)
        assertTrue(
            result.staleAllows.isEmpty(),
            "白名单已失效（字面量改掉或行号漂移），必须删掉这些条目：${result.staleAllows}",
        )
        assertTrue(
            result.offenders.isEmpty(),
            buildString {
                appendLine("字符当图标回退（图标必须用 vade.ui.icon.VadeIcons + VadeIcon，docs/visual-language.md §3）：")
                result.offenders.forEach { appendLine("  " + it.render()) }
                appendLine("共 ${result.offenders.size} 处。")
                appendLine("若确需放行（仅限本棒无权改的文件），在 IconGuardTest.allowed 追加：")
                result.offenders.forEach {
                    appendLine("    GlyphLiteralScan.Allow(\"${it.path}\", ${it.line}, \"${it.glyph}\", \"谁在改 / 何时删\"),")
                }
            },
        )
    }

    @Test
    fun businessCodeDoesNotImportTablerDirectly() {
        // The Tabler namespace may only appear inside vade/ui/icon (VadeIcons is the seam).
        // 扫描域同上面一样是整个 commonMain；这里排除 icon/ 是有意的落点，不是漏扫。
        val root = commonMainDir()
        val iconDir = File(uiDir(), "icon")
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.startsWith(iconDir) }
            .filter { it.readText().contains("com.composables.icons.tabler") }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .sorted()
            .toList()
        assertTrue(
            offenders.isEmpty(),
            "业务代码不得直接 import Tabler：$offenders（必须经 vade.ui.icon.VadeIcons）",
        )
    }

    @Test
    fun destinationsCarryTablerIcons() {
        val file = File(uiDir(), "destinations/Destinations.kt")
        assertTrue(file.isFile, "Destinations.kt 不在预期位置：${file.path}")
        val text = file.readText()

        assertTrue(
            text.contains("val icon: ImageVector"),
            "Destination 必须声明 icon: ImageVector（w2-icons 把 glyph 换成矢量图标）",
        )

        // The four entries, in FR-8.77 order, mapped to the agreed semantic names.
        val expected = listOf(
            "Sessions" to "VadeIcons.Chat",
            "Activity" to "VadeIcons.Activity",
            "Capabilities" to "VadeIcons.Grid",
            "Me" to "VadeIcons.Person",
        )
        val missing = expected.filter { (entry, icon) ->
            !Regex("""$entry\s*\([^)]*$icon""").containsMatchIn(text)
        }
        assertTrue(
            missing.isEmpty(),
            "目的地图标映射缺失或不符：$missing（Chat/Activity/Grid/Person，FR-8.77 顺序）",
        )

        // The deprecated CJK glyph field is transitional: allowed to linger ONLY with its
        // @Deprecated marker. When the parent stick deletes the field this test still
        // passes; re-adding it without the marker fails here.
        val glyphDecl = Regex("""@Deprecated\([^)]*\)\s*val glyph""")
        if (text.contains(Regex("""\bval glyph\b""")) && !glyphDecl.containsMatchIn(text)) {
            assertTrue(false, "Destinations.glyph 若仍存在必须带 @Deprecated（过渡字段，随 shell 合入删除）")
        }
    }

    @Test
    fun vadeIconsExposesAtLeast20Vectors() {
        val file = File(uiDir(), "icon/VadeIcons.kt")
        assertTrue(file.isFile, "VadeIcons.kt 不在预期位置：${file.path}")
        val count = Regex("""val \w+: ImageVector""").findAll(file.readText()).count()
        assertTrue(
            count >= 20,
            "VadeIcons 公开矢量不足 20（当前 $count）——图标词表被删减了",
        )
    }
}
