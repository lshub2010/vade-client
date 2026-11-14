package vade.ui.skin

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import vade.ui.skin.material.MaterialSkin
import vade.ui.skin.miuix.MiuixTokens

/**
 * The `VadeFonts` seam that keeps Web from needing `fonts.gstatic.com`
 * (`shots/g-font-bundle2/RESULT.md`, root cause in `shots/g-font-recon/RECON.md`).
 *
 * Web has no system fonts, so `FontFamily.Default` draws nothing; the bundled subset is
 * installed *before* the first composition and the typography defaults must pick it up —
 * otherwise every `UiText` silently keeps resolving to `Default` and the CDN fallback comes
 * back. These assertions fail the moment the seam is unplugged:
 *
 *  1. the defaults are `FontFamily.Default` when nobody installs anything (desktop/Android
 *     byte-for-byte unchanged, no re-flow);
 *  2. after `installSans`, title/body/caption carry the installed family — but `mono` keeps
 *     `FontFamily.Monospace` (replacing it would break the probe's column alignment);
 *  3. `generation` moves on every install, because CMP does not repaint when glyph bytes
 *     merely arrive — the entry point keys the tree on it.
 */
class VadeFontsTest {

    @AfterTest
    fun restore() {
        // Global by design (one process-wide font); every test must leave Default behind.
        if (VadeFonts.sans != FontFamily.Default) VadeFonts.installSans(FontFamily.Default)
    }

    @Test
    fun typography_defaults_are_untouched_before_install() {
        val typography = SkinTypography()

        assertEquals(FontFamily.Default, typography.title.fontFamily, "title 默认字体必须仍是 Default")
        assertEquals(FontFamily.Default, typography.body.fontFamily, "body 默认字体必须仍是 Default")
        assertEquals(FontFamily.Default, typography.caption.fontFamily, "caption 默认字体必须仍是 Default")
        assertEquals(FontFamily.Monospace, typography.mono.fontFamily, "mono 必须保留等宽")
        // 尺寸/字重这些既有 token 不许被这次改动顺手改掉。
        assertEquals(18.sp, typography.title.fontSize)
        assertEquals(FontWeight.SemiBold, typography.title.fontWeight)
        assertEquals(14.sp, typography.body.fontSize)
        assertEquals(11.sp, typography.caption.fontSize)
    }

    @Test
    fun install_reaches_every_typography_default_and_bumps_generation() {
        val before = VadeFonts.generation
        // FontFamily.Serif stands in for the wasm-loaded family: the seam must not care which
        // family it is handed (no byte parsing needed here — that is the wasm loader's job).
        VadeFonts.installSans(FontFamily.Serif)

        assertEquals(FontFamily.Serif, VadeFonts.sans)
        assertTrue(VadeFonts.generation > before, "install 必须 bump generation（否则界面不会失效）")

        val typography = SkinTypography()
        assertEquals(FontFamily.Serif, typography.title.fontFamily)
        assertEquals(FontFamily.Serif, typography.body.fontFamily)
        assertEquals(FontFamily.Serif, typography.caption.fontFamily)
        assertEquals(FontFamily.Monospace, typography.mono.fontFamily, "mono 不受 sans 安装影响")

        // Skins that build on the defaults (material = the shipped one) inherit the family.
        assertEquals(FontFamily.Serif, MaterialSkin(dark = false).typography.body.fontFamily)
    }

    @Test
    fun uninstalling_returns_to_default() {
        VadeFonts.installSans(FontFamily.Serif)
        val afterInstall = VadeFonts.generation
        VadeFonts.installSans(FontFamily.Default)

        assertEquals(FontFamily.Default, VadeFonts.sans)
        assertTrue(VadeFonts.generation > afterInstall)
        assertEquals(FontFamily.Default, SkinTypography().body.fontFamily)
    }

    /**
     * Pinned *gap*, not a feature: `MiuixTokens` builds explicit `TextStyle`s without a
     * family, so it does not inherit the seam — under the optional `miuix` skin the Web build
     * still falls back to gstatic / tofu. The fix lives in `skin/miuix/` , which this batch
     * may not touch; this assertion makes the hole visible instead of silent.
     */
    @Test
    fun miuix_typography_does_not_inherit_the_seam_yet() {
        VadeFonts.installSans(FontFamily.Serif)

        assertEquals(
            null,
            MiuixTokens.typography.body.fontFamily,
            "若这里变成非 null，说明 miuix 也接上了 VadeFonts——请把本测试改成断言 Serif 并更新报告",
        )
    }
}
