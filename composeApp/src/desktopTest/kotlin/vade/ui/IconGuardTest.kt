package vade.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * w2-icons anti-regression guard (docs/visual-language.md §3: icons are vectors — never
 * glyph characters, never CJK-as-icon, never emoji).
 *
 * Scope of this stick: `shell/` and `skin/` are **excluded** from the scans — two parallel
 * w2 sticks are converting their call sites right now, and scanning them here would fail on
 * code this stick is not allowed to touch. The parent stick widens these scans back to the
 * whole `commonMain` tree when the merged state is in (the regexes below are already
 * correct for that: delete the exclusions, drop the allowlist entry).
 */
class IconGuardTest {

    /** Module dir: Gradle runs desktopTest with the composeApp module as working dir. */
    private fun uiDir() = File("src/commonMain/kotlin/vade/ui")

    /** Directories owned by parallel w2 sticks — excluded until the parent's merged scan. */
    private val excludedDirs = setOf("icon", "shell", "skin")

    /**
     * One deliberate leftover outside the excluded dirs: the search overlay still draws its
     * trigger with the `⌕` character (search/ was not part of any w2 stick). It is named
     * here so it cannot silently persist — when the parent pass converts it, delete the
     * entry and the guard immediately covers `search/` too.
     */
    private val allowedLiteralGlyphs = mapOf(
        "SearchOverlay.kt" to setOf("⌕"),
    )

    /** The banned single-glyph vocabulary (the shell's old char-icon alphabet, plus × ◂ ▸ ⧗). */
    private val glyphChars = """[↑↓←→▲▼■□●○◆＋…✕✓▾▸‹⌕✎•×◂▸⧗]"""

    private fun scannedFiles(): List<File> =
        uiDir().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                val relative = file.relativeTo(uiDir()).invariantSeparatorsPath
                excludedDirs.none { relative.startsWith("$it/") }
            }
            .toList()

    @Test
    fun noCharacterIconTextLiterals() {
        // `\b` keeps `UiText(` from matching the bare-`Text(` pattern; the second regex
        // scans `UiText(` itself, which is exactly how a glyph icon regresses in pages.
        // The glyph char itself is captured (group 1) so the allowlist can compare it.
        val bareText = Regex("""\bText\(\s*"($glyphChars)"""")
        val uiText = Regex("""UiText\(\s*"($glyphChars)"""")
        val offenders = mutableListOf<String>()
        for (file in scannedFiles()) {
            val text = file.readText()
            bareText.findAll(text).forEach { m ->
                if (!isAllowed(file, m.groupValues[1])) offenders += "${file.name}: Text(\"${m.groupValues[1]}\")"
            }
            uiText.findAll(text).forEach { m ->
                if (!isAllowed(file, m.groupValues[1])) offenders += "${file.name}: UiText(\"${m.groupValues[1]}\")"
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "字符当图标回退：$offenders（图标必须用 vade.ui.icon.VadeIcon + VadeIcons）",
        )
    }

    @Test
    fun businessCodeDoesNotImportTablerDirectly() {
        // The Tabler namespace may only appear inside vade/ui/icon (VadeIcons is the seam).
        val offenders = scannedFiles()
            .filter { it.readText().contains("com.composables.icons.tabler") }
            .map { it.name }
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

    private fun isAllowed(file: File, glyph: String): Boolean =
        allowedLiteralGlyphs[file.name]?.contains(glyph) == true
}
