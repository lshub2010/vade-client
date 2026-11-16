package vade.ui.about

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import vade.ui.buildconfig.VadeBuildConfig
import vade.ui.destinations.AboutPage
import vade.ui.destinations.ProfileScreen
import vade.ui.destinations.VadeLicenseEntries
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin

/**
 * A5 · 可分发 — version single source + 关于 subpage.
 *
 * The version's ONLY home is `vadeVersion` in the repo-root gradle.properties; the
 * `generateVadeBuildConfig` task stamps it into the generated
 * `vade.ui.buildconfig.VadeBuildConfig` (commonMain srcDir), which the 关于 page renders.
 * These tests pin the whole chain: property → generated constant → rendered page — any
 * second hardcoded version string in that chain turns one of them red.
 */
class AboutPageTest {

    /** Module dir: Gradle runs desktopTest with the composeApp module as working dir. */
    private fun repoRoot(): File = File("..")

    private fun gradleVadeVersion(): String =
        repoRoot().resolve("gradle.properties").readLines()
            .firstOrNull { it.startsWith("vadeVersion=") }
            ?.substringAfter("vadeVersion=")
            ?.trim()
            .orEmpty()

    // --- 1 · the constant itself -------------------------------------------------

    @Test
    fun versionConstantIsNonEmptySemverWithAlpha() {
        assertTrue(VadeBuildConfig.VERSION.isNotBlank(), "版本常量为空——generateVadeBuildConfig 没跑或被改")
        assertTrue(
            Regex("""\d+\.\d+\.\d+-alpha(\.\d+)?""").matches(VadeBuildConfig.VERSION),
            "版本必须形如 0.1.0-alpha.1（semver 且带 -alpha 后缀），实际：${VadeBuildConfig.VERSION}",
        )
    }

    // --- 2 · build time ----------------------------------------------------------

    @Test
    fun buildTimeIsUtcIsoFormat() {
        assertTrue(
            Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z""").matches(VadeBuildConfig.BUILD_TIME),
            "构建时间必须是 UTC ISO 格式 yyyy-MM-dd'T'HH:mm:ss'Z'，实际：${VadeBuildConfig.BUILD_TIME}",
        )
    }

    // --- 3 · single source: gradle.properties ↔ generated constant ---------------

    @Test
    fun gradlePropertiesIsTheSingleVersionSource() {
        val declared = gradleVadeVersion()
        assertTrue(
            declared.isNotEmpty(),
            "gradle.properties 缺少 vadeVersion —— 版本单一来源被移走或改名",
        )
        assertEquals(
            declared,
            VadeBuildConfig.VERSION,
            "gradle.properties 的 vadeVersion 与注入常量不一致——出现了第二个版本来源",
        )
    }

    @Test
    fun generatedFileCarriesTheSameVersion() {
        val generated = repoRoot()
            .resolve("composeApp/build/generated/vadeBuildConfig/vade/ui/buildconfig/VadeBuildConfig.kt")
        assertTrue(generated.isFile, "生成文件不存在：${generated.path} —— generateVadeBuildConfig 未执行")
        val text = generated.readText()
        val declared = gradleVadeVersion()
        assertTrue(
            text.contains("""const val VERSION: String = "$declared""""),
            "生成文件里的版本与 gradle.properties 不一致：期望 $declared，文件内容见 ${generated.path}",
        )
    }

    // --- 4 · licence list --------------------------------------------------------

    @Test
    fun licenseListHasAtLeastThreeEntries() {
        assertTrue(VadeLicenseEntries.size >= 3, "许可证列表不足 3 条：${VadeLicenseEntries.size}")
        assertTrue(
            VadeLicenseEntries.all { it.name.isNotBlank() && it.license.isNotBlank() },
            "许可证条目必须带名称与许可名称",
        )
        val names = VadeLicenseEntries.map { it.name }
        // README「第三方许可与致谢」的四段必须全部在列。
        listOf("KernelSU", "Miuix", "Tabler Icons", "HarmonyOS Sans").forEach { expected ->
            assertTrue(expected in names, "README「第三方许可与致谢」的 $expected 缺失")
        }
        // HarmonyOS Sans 是预留位——不得伪造成已采用。
        val harmony = VadeLicenseEntries.first { it.name == "HarmonyOS Sans" }
        assertTrue(!harmony.adopted, "HarmonyOS Sans 是预留位（README 标注待定），不得写成已采用")
    }

    // --- 5 · the page really renders the injected facts --------------------------

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aboutPageRendersVersionBuildTimeAndLicences() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                AboutPage(onBack = {})
            }
        }
        onNodeWithText("版本 ${VadeBuildConfig.VERSION}").assertExists()
        onNodeWithText("构建时间 ${VadeBuildConfig.BUILD_TIME}").assertExists()
        listOf("KernelSU", "Miuix", "Tabler Icons", "HarmonyOS Sans").forEach { name ->
            onNodeWithText(name).assertExists()
        }
    }

    // --- 6 · the 关于 row opens the full-screen subpage (D91) ---------------------

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun profileAboutRowOpensTheFullSubpage() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                ProfileScreen(
                    skinId = "material",
                    barStyle = BottomBarStyle.Fixed,
                    containerLabel = "test",
                    onSkinChange = {},
                    onBarChange = {},
                )
            }
        }
        // The row used to be a disabled placeholder (enabled = false); it must now be
        // tappable and must open the 关于 subpage in place — no dialog, no shell change.
        onNodeWithText("关于").performScrollTo().performClick()
        onNodeWithText("版本 ${VadeBuildConfig.VERSION}").assertExists()
    }
}
