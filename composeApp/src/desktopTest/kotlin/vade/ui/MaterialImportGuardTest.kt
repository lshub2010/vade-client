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
 * 2. `Skin.kt` keeps exactly twelve components — eleven frozen by AMEND-22 / D93 plus
 *    the twelfth (`SendOrb`) added by RFC-0011; a thirteenth one breaks the parallel
 *    boundary again and must go through its own RFC first.
 */
class MaterialImportGuardTest {

    private fun moduleDir() = File(".").absoluteFile

    @Test
    fun businessPagesDoNotImportMaterial3() {
        val root = File(moduleDir(), "src/commonMain/kotlin/vade/ui")
        val offenders = mutableListOf<String>()
        for (dir in listOf("destinations", "shell", "search", "chat")) {
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
    fun skinContractHasExactlyTwelveComponents() {
        val skinFile = File(moduleDir(), "src/commonMain/kotlin/vade/ui/skin/Skin.kt")
        assertTrue(skinFile.isFile, "Skin.kt not found at ${skinFile.path}")
        val count = Regex("@Composable\\s+fun ").findAll(skinFile.readText()).count()
        assertEquals(
            expected = 12,
            actual = count,
            message = "C-09 has twelve components (11 frozen by D93 + SendOrb by RFC-0011); " +
                "a thirteenth needs an RFC, not an edit",
        )
    }
}
