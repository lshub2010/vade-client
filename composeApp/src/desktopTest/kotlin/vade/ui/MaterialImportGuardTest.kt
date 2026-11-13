package vade.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two C-09 guard rails from the task book §T2, in a form that actually fails:
 *
 * 1. business pages (`destinations/`, `shell/`) never import a concrete toolkit — they
 *    only talk to `Skin`, so a `miuix`/`glass` skin can replace `material` without
 *    touching them;
 * 2. `Skin.kt` keeps exactly eleven components — a twelfth one breaks U2/U3's parallel
 *    boundary (D93).
 */
class MaterialImportGuardTest {

    private fun moduleDir() = File(".").absoluteFile

    @Test
    fun businessPagesDoNotImportMaterial3() {
        val root = File(moduleDir(), "src/commonMain/kotlin/vade/ui")
        val offenders = mutableListOf<String>()
        for (dir in listOf("destinations", "shell")) {
            val packageDir = File(root, dir)
            assertTrue(packageDir.isDirectory, "missing ${packageDir.path} — test must run from ui/composeApp")
            packageDir.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
                val text = file.readText()
                for (banned in listOf("androidx.compose.material3", "top.yukonga.miuix", "dev.chrisbanes.haze")) {
                    if (text.contains(banned)) offenders += "${file.name} imports $banned"
                }
            }
        }
        assertTrue(offenders.isEmpty(), "pages must only use Skin: $offenders")
    }

    @Test
    fun skinContractHasExactlyElevenComponents() {
        val skinFile = File(moduleDir(), "src/commonMain/kotlin/vade/ui/skin/Skin.kt")
        assertTrue(skinFile.isFile, "Skin.kt not found at ${skinFile.path}")
        val count = Regex("@Composable\\s+fun ").findAll(skinFile.readText()).count()
        assertEquals(
            expected = 11,
            actual = count,
            message = "C-09 is frozen at eleven components (D93); a twelfth needs an RFC, not an edit",
        )
    }
}
