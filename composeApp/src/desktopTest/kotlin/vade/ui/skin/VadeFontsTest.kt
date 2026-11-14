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
 * installed *before* the first composition and every typography token must pick it up —
 * otherwise every `UiText` silently keeps resolving to `Default` and the CDN fallback comes
 * back. These assertions fail the moment the seam is unplugged:
 *
 *  1. the defaults are `FontFamily.Default` when nobody installs anything (desktop/Android
 *     byte-for-byte unchanged, no re-flow);
 *  2. after `installSans`, title/body/caption carry the installed family — but `mono` keeps
 *     `FontFamily.Monospace` (replacing it would break the probe's column alignment);
 *  3. `generation` moves on every install, because CMP does not repaint when glyph bytes
 *     merely arrive — the entry point keys the tree on it;
 *  4. **the optional `miuix` skin is on the seam too** — its tokens are explicit
 *     `TextStyle`s (they do not inherit `SkinTypography`'s defaults) plus Miuix's own
 *     `TextStyles` bag, so it was the last surface that could still draw tofu on Web. It is
 *     no longer a pinned gap: both family sources now read `VadeFonts.sans`, and a family
 *     installed *after* the tokens are built still wins (that last case is what separates a
 *     real seam from a constant copied in at the call site).
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

    // --- 4 · the optional `miuix` skin (was the pinned gap, now fixed) -----------------

    /**
     * 桌面/Android 零变化的**断言**：没人装字体时，miuix 交出去的就是 `FontFamily.Default`
     * —— 与改动前「不写 fontFamily、由 CMP 解析成平台默认族」在绘制上等价。同时把
     * 20/15/13 的字号阶梯钉住，防止这次改动顺手改了 typography。
     */
    @Test
    fun miuix_typography_is_default_family_when_nothing_is_installed() {
        assertEquals(FontFamily.Default, VadeFonts.sans, "前置条件：进程内不应有已装字体")

        val typography = MiuixTokens.typography
        assertEquals(FontFamily.Default, typography.title.fontFamily, "桌面/Android：title 必须仍是 Default")
        assertEquals(FontFamily.Default, typography.body.fontFamily, "桌面/Android：body 必须仍是 Default")
        assertEquals(FontFamily.Default, typography.caption.fontFamily, "桌面/Android：caption 必须仍是 Default")
        assertEquals(FontFamily.Monospace, typography.mono.fontFamily, "mono 必须保留等宽")
        assertEquals(20.sp, typography.title.fontSize)
        assertEquals(15.sp, typography.body.fontSize)
        assertEquals(13.sp, typography.caption.fontSize)
    }

    /**
     * miuix 的 typography 现在走 `VadeFonts.sans`：装什么族，token 就是什么族
     * （`mono` 例外，见上一条）。
     */
    @Test
    fun miuix_typography_inherits_the_seam() {
        VadeFonts.installSans(FontFamily.Serif)

        val typography = MiuixTokens.typography
        val sans = listOf("title" to typography.title, "body" to typography.body, "caption" to typography.caption)
        for ((name, style) in sans) {
            assertEquals(VadeFonts.sans, style.fontFamily, "miuix.$name 必须取自 VadeFonts.sans")
        }
        assertEquals(FontFamily.Monospace, typography.mono.fontFamily, "miuix.mono 仍必须是 Monospace")
    }

    /**
     * 这条才是真正防「只是把常量抄过来」的用例：装进去的是 skin 源码里**根本不存在**的族
     * （桌面 `FontFamily("name")` 系统族工厂），所以断言只可能因为 token 在**读取时**读了
     * `VadeFonts.sans` 才通过。`MiuixTokens` 是个 object —— 若 typography 写成 `val`（在
     * object 初始化时把 Default 捕获下来），本用例必红。
     */
    @Test
    fun install_after_the_fact_reaches_miuix_tokens() {
        // 先把 tokens 摸一遍：object 初始化发生在这一刻（此时 sans 还是 Default）。
        assertEquals(FontFamily.Default, MiuixTokens.typography.body.fontFamily)

        VadeFonts.installSans(FAKE_SANS)

        assertEquals(FAKE_SANS, VadeFonts.sans)
        assertEquals(FAKE_SANS, MiuixTokens.typography.title.fontFamily, "object 已初始化过，token 仍必须跟着换")
        assertEquals(FAKE_SANS, MiuixTokens.typography.body.fontFamily)
        assertEquals(FAKE_SANS, MiuixTokens.typography.caption.fontFamily)
        assertEquals(FontFamily.Monospace, MiuixTokens.typography.mono.fontFamily)
    }

    /**
     * 第二个字形来源：`MiuixTheme(textStyles = …)`。Miuix 的 `Text` 不带 style 时合并的是
     * `textStyles.main`（`Button` 是 `textStyles.button`），`MiuixParts.kt` 里大量
     * `Text("…", fontSize = …)` 正走这条路 —— 不接缝的话 Web 依旧豆腐。
     */
    @Test
    fun miuix_own_text_styles_follow_the_seam() {
        VadeFonts.installSans(FAKE_SANS)

        val styles = MiuixTokens.miuixTextStyles()
        val slots = listOf(
            "main" to styles.main,
            "paragraph" to styles.paragraph,
            "body1" to styles.body1,
            "body2" to styles.body2,
            "button" to styles.button,
            "footnote1" to styles.footnote1,
            "footnote2" to styles.footnote2,
            "headline1" to styles.headline1,
            "headline2" to styles.headline2,
            "subtitle" to styles.subtitle,
            "title1" to styles.title1,
            "title2" to styles.title2,
            "title3" to styles.title3,
            "title4" to styles.title4,
        )
        for ((name, style) in slots) {
            assertEquals(FAKE_SANS, style.fontFamily, "Miuix TextStyles.$name 必须取自 VadeFonts.sans")
        }
    }

    /**
     * 桌面/Android 零变化，第二半：没有装字体时 `miuixTextStyles()` 交出的就是 Miuix 自己的
     * `defaultTextStyles()` —— **同一个对象**，不是「把 fontFamily 写成 Default 的副本」。
     * 逐槽断言，等于断言「miuix 皮肤给 Miuix 主题的输入与接缝前逐字节相同」。
     */
    @Test
    fun miuix_own_text_styles_are_untouched_on_desktop() {
        val styles = MiuixTokens.miuixTextStyles()
        val defaults = MiuixTokens.miuixTextStyles(FontFamily.Default)

        assertEquals(defaults.main, styles.main)
        assertEquals(defaults.paragraph, styles.paragraph)
        assertEquals(defaults.body1, styles.body1)
        assertEquals(defaults.body2, styles.body2)
        assertEquals(defaults.button, styles.button)
        assertEquals(defaults.footnote1, styles.footnote1)
        assertEquals(defaults.footnote2, styles.footnote2)
        assertEquals(defaults.headline1, styles.headline1)
        assertEquals(defaults.headline2, styles.headline2)
        assertEquals(defaults.subtitle, styles.subtitle)
        assertEquals(defaults.title1, styles.title1)
        assertEquals(defaults.title2, styles.title2)
        assertEquals(defaults.title3, styles.title3)
        assertEquals(defaults.title4, styles.title4)
        // 并且这些槽位里没有一个是「写成 Default 的显式族」被顺手塞进去的。
        assertEquals(null, styles.main.fontFamily, "main 的 fontFamily 必须保持未设置（= CMP 平台默认）")
    }
}

/**
 * A family that exists nowhere in the skin sources: the desktop-only system-family factory
 * (`FontFamily("name")`), so no constant copied into the tokens can equal it by accident.
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private val FAKE_SANS: FontFamily = FontFamily("Vade Fake Sans")
