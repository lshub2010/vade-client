package vade.ui.skin.glass

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import vade.ui.skin.BtnStyle
import vade.ui.skin.BubbleRole
import vade.ui.skin.InputMode
import vade.ui.skin.LocalSkin
import vade.ui.skin.MessageView
import vade.ui.skin.Skin
import vade.ui.skin.SkinRegistry
import vade.ui.skin.Skins

/**
 * 52-U3 · T1 的验收：玻璃 token、能力阶梯、四个组件，以及"降级路径不抛错"。
 *
 * 这里刻意分成两类断言：
 *
 *  - **纯函数**（[glassCapability]、[contrastOnCanvas]、[worstCaseCardContrast]）——
 *    不需要起组合，直接算，失败信息里带数，改 token 的人立刻知道越了哪条线；
 *  - **屏上**（`runComposeUiTest` + 语义树）—— 组件真的画出了文字，而不是"能编译"。
 *    一个皮肤最容易坏的方式是看起来实现了、实际什么都没画（空白面板）。
 *
 * **注册表是全进程单例**：本类 `@BeforeTest` 把 `glass` 注册进去，`@AfterTest` 必须把
 * 注册表还原成出厂状态（`clear()` + `resetInstallFlagForTest()` + `installBuiltIns()`），
 * 否则 `SkinsBootTest.staleGlassPreferenceConvergesInsteadOfCrashing`（"存过 glass → 收敛
 * 到 material"）会在本类之后变红 —— 而那条测试守的是 I2 的真实缺陷。
 */
@OptIn(ExperimentalTestApi::class)
class GlassSkinTest {

    @BeforeTest
    fun registerGlass() {
        GlassSkinEntry.dark = false
        GlassSkinEntry.register()
    }

    @AfterTest
    fun restoreRegistry() {
        SkinRegistry.clear()
        Skins.resetInstallFlagForTest()
        Skins.installBuiltIns()
        GlassSkinEntry.dark = false
    }

    // --- 注册 -------------------------------------------------------------------

    @Test
    fun glassResolvesFromTheRegistry() {
        assertTrue(
            "glass" in SkinRegistry.installed(),
            "register() 之后 glass 必须在 installed() 里；实际：${SkinRegistry.installed()}",
        )
        val skin = SkinRegistry.get("glass")
        assertEquals("glass", skin.id, "注册主键必须等于 skin.id")
        assertFalse(skin.colors.isDark, "默认注册 = 亮色")
    }

    @Test
    fun darkEntryFlipsTheTokenSet() {
        GlassSkinEntry.dark = true
        GlassSkinEntry.register()
        val skin = SkinRegistry.get("glass")
        assertTrue(skin.colors.isDark, "--dark 后注册的工厂必须产暗色")
        assertEquals(
            GlassTokens.tintDark,
            skin.glass.tintDark,
            "SkinGlass 必须是 GlassTokens 的投影：两处 tint 一旦分叉，玻璃在暗色下就换了一个颜色",
        )
    }

    /**
     * 「不自动启用」是交付条件之一：`Skins.installBuiltIns()` 不许替宿主做主。
     *
     * 这条同时钉住 I2 —— 上一版把 `glass` 注册成 `MaterialSkin` 的占位实现，选「玻璃」
     * 像素不变。现在连占位项都不许存在。
     */
    @Test
    fun builtInsDoNotEnableGlass() {
        SkinRegistry.clear()
        Skins.resetInstallFlagForTest()
        Skins.installBuiltIns()

        assertFalse("glass" in SkinRegistry.installed(), "register() 必须由宿主显式调用")
        assertFalse("glass" in SkinRegistry.known(), "也不许留一个冒充的占位项（I2）")
        assertTrue("material" in SkinRegistry.installed(), "出厂皮肤必须仍在")
    }

    // --- 四个组件 -----------------------------------------------------------------

    /** 四个组件各来一遍，含玻璃 Button 的三种 intent 与三个气泡角色。 */
    @Composable
    private fun FourComponents(skin: Skin) {
        Column(Modifier.width(520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            skin.Button(text = "发送", onClick = {})
            skin.Button(text = "删除", onClick = {}, style = BtnStyle.Danger)
            skin.Button(text = "禁用", onClick = {}, enabled = false)
            skin.Button(text = "次要", onClick = {}, style = BtnStyle.Secondary)
            skin.Card(Modifier.fillMaxWidth()) { BasicText("卡片正文") }
            skin.InputBar(value = "", onValueChange = {}, mode = InputMode.Idle)
            skin.MessageBubble(message = MessageView(id = "m1", text = "用户消息"), role = BubbleRole.User)
            skin.MessageBubble(message = MessageView(id = "m2", text = "助手消息"), role = BubbleRole.Assistant)
            skin.MessageBubble(message = MessageView(id = "m3", text = "系统提示"), role = BubbleRole.System)
        }
    }

    @Test
    fun fourComponentsComposeWithoutABackdrop() {
        // 没有 GlassScope = 采不到背景 = capability 降到 TintStroke。这既是"三端可组合"
        // 的最小证明，也是降级路径的正面用例：玻璃层仍然画出内容。
        val skin: Skin = GlassSkin()
        var seen: List<String> = emptyList()
        runComposeUiTest {
            setContent { CompositionLocalProvider(LocalSkin provides skin) { FourComponents(skin) } }
            waitForIdle()
            seen = screenTexts()
        }

        listOf(
            "发送",
            "删除",
            "禁用",
            "次要",
            "卡片正文",
            "输入消息…",
            "用户消息",
            "助手消息",
            "系统提示",
        ).forEach { expected ->
            assertTrue(
                seen.any { it == expected },
                "玻璃组件没把「$expected」画上屏 —— 空面板比不实现更糟。整屏文本：\n${seen.report()}",
            )
        }
    }

    @Test
    fun darkSkinComposesTheSameComponents() {
        val skin: Skin = GlassSkin(dark = true)
        var seen: List<String> = emptyList()
        runComposeUiTest {
            setContent { CompositionLocalProvider(LocalSkin provides skin) { FourComponents(skin) } }
            waitForIdle()
            seen = screenTexts()
        }
        assertTrue(seen.any { it == "卡片正文" }, "暗色 token 下卡片正文丢了：\n${seen.report()}")
        assertTrue(seen.any { it == "用户消息" }, "暗色 token 下用户气泡丢了：\n${seen.report()}")
    }

    /** [vade.ui.skin.MessageView.badges] 与 actions 两个附属行也必须上屏（D92/D96）。 */
    @Test
    fun bubblesKeepBadgesAndActions() {
        val skin: Skin = GlassSkin()
        val message = MessageView(
            id = "m1",
            text = "原图已压缩",
            badges = listOf("已压缩"),
        )
        var seen: List<String> = emptyList()
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalSkin provides skin) {
                    skin.MessageBubble(
                        message = message,
                        role = BubbleRole.User,
                        actions = listOf(vade.ui.skin.MessageAction(id = "copy", label = "复制")),
                        onAction = {},
                    )
                }
            }
            waitForIdle()
            seen = screenTexts()
        }
        assertTrue(seen.any { it == "已压缩" }, "徽标行丢了：\n${seen.report()}")
        assertTrue(seen.any { it == "复制" }, "动作行丢了：\n${seen.report()}")
    }

    // --- 降级 ---------------------------------------------------------------------

    /**
     * 能力阶梯：插件的每一次"做不到"都必须落到一个**能画东西**的档位。
     *
     * - 没接背景 → `TintStroke`（不建 haze 节点，只染色 + 描边）；
     * - 接了背景 → 至少 `Blur`；
     * - 任何平台返回 `Refraction` 都必须同时 `blurs`（折射是模糊之上的一层，不可能单独存在）。
     */
    @Test
    fun capabilityLadderNeverFallsIntoAGap() {
        assertEquals(
            GlassCapability.TintStroke,
            glassCapability(hasBackdrop = false),
            "没有背景可采时必须降到 TintStroke，而不是让 haze 采一片空白",
        )
        val platform = glassCapability(hasBackdrop = true)
        assertTrue(platform.blurs, "接上背景后至少要有模糊；实际：$platform")
        assertFalse(platform.refracts, "D22：本批没有任何平台允许折射（Android AGSL 属 T3）")
        assertTrue(
            GlassCapability.Refraction.blurs,
            "Refraction 档必须自带模糊 —— 否则它是「只有折射」的不可能状态",
        )
    }

    /**
     * 探测抛异常时收敛到 TintStroke，绝不冒到组合（任务书 §七 / §T3 的 fake 降级用例）。
     *
     * 用 [glassPlatformCapabilityProbe] 把"平台探测炸了"注入进来 —— 真造一台 shader 编译
     * 失败的设备是不可能的，但这条 catch 分支必须被真的走过一次。
     */
    @Test
    fun platformProbeFailureDegradesInsteadOfThrowing() {
        val original = glassPlatformCapabilityProbe
        try {
            glassPlatformCapabilityProbe = { throw IllegalStateException("AGSL 编译失败（fake）") }
            val capability = glassCapability(hasBackdrop = true)
            assertEquals(
                GlassCapability.TintStroke,
                capability,
                "平台探测抛异常必须收敛到 TintStroke（染色 + 描边），而不是把异常抛到组合里",
            )
            assertFalse(capability.blurs, "降级档不许声称自己有模糊")

            // 反向：探测说"能折射"时阶梯必须原样透传，否则 T3 接线时会发现阶梯在吞值。
            glassPlatformCapabilityProbe = { GlassCapability.Refraction }
            assertEquals(GlassCapability.Refraction, glassCapability(hasBackdrop = true))
        } finally {
            glassPlatformCapabilityProbe = original
        }
    }

    // --- token 与对比度 ---------------------------------------------------------------

    @Test
    fun tokensMatchTheTaskBookNumbers() {
        assertEquals(24f, GlassTokens.blurRadius.value, "§T1：背景模糊 24dp")
        assertEquals(1f, GlassTokens.strokeWidth.value, "§T1：描边 1dp")
        assertEquals(
            GlassTokens.tintLight,
            GlassTokens.skinGlass().tintLight,
            "SkinGlass 必须是 GlassTokens 的投影，不是第二份会漂移的数",
        )
        assertEquals(
            GlassTokens.stroke(dark = false),
            GlassTokens.skinGlass(dark = false).hairlineOnGlass,
            "亮色实例必须投影亮色描边（黑 26%）—— 白 35% 在亮玻璃上不可见（审查 P1-3）",
        )
        assertEquals(
            GlassTokens.stroke(dark = true),
            GlassTokens.skinGlass(dark = true).hairlineOnGlass,
            "暗色实例必须投影暗色描边 —— SkinGlass 只有单值字段，所以只能按实例取一支",
        )
        assertEquals(
            GlassTokens.stroke(dark = true),
            GlassSkin(dark = true).glass.hairlineOnGlass,
            "Skin.glass 是 SkinGlass 的投影：暗色玻璃皮肤的描边必须是暗色那一支",
        )
        assertTrue(
            GlassTokens.tintLight.alpha <= 0.45f && GlassTokens.tintDark.alpha <= 0.45f,
            "§5 上限 45%：tint 再厚就不是玻璃了",
        )
    }

    /**
     * 正文对比度 ≥ 4.5:1（§T2 硬指标）—— 按 WCAG 公式算，含顶部高光的峰值。
     *
     * 起算点：`高光 ⊕ tint ⊕ 画布 ⊕ 正文`。用高光峰值而不是均值，是为了让"标题正好压在
     * 光带上"那一行也被覆盖。
     */
    @Test
    fun bodyTextClearsTheContrastFloorOnItsOwnCanvas() {
        for (dark in listOf(false, true)) {
            val theme = if (dark) "暗色" else "亮色"
            val colors = GlassSkin(dark = dark).colors
            val ratio = contrastOnCanvas(colors)
            assertTrue(
                ratio >= 4.5,
                "$theme 玻璃面上的正文只有 ${ratio.rounded()}:1 —— " +
                    "低于 §T2 的 4.5:1（tint alpha ${GlassTokens.tint(dark).alpha}）",
            )
        }
    }

    /**
     * 卡片内容底在**任意**背景下都达标：算的是最坏情况（背后纯黑）。
     *
     * 这一条比"在自家画布上达标"强得多 —— 玻璃背后可以是图片、红字工具卡、视频帧，
     * 只有把内容底做成近不透明，正文才与背景无关。
     */
    @Test
    fun cardContentBaseSurvivesAnyBackdrop() {
        for (dark in listOf(false, true)) {
            val theme = if (dark) "暗色" else "亮色"
            val ratio = worstCaseCardContrast(GlassSkin(dark = dark).colors)
            assertTrue(
                ratio >= 4.5,
                "$theme 卡片在纯黑背景下正文只有 ${ratio.rounded()}:1" +
                    "（内容底 alpha ${GlassTokens.CARD_CONTENT_ALPHA}）",
            )
        }
    }

    /** WCAG 帮助函数本身的锚点：纯黑白必须正好 21:1，否则后面所有数字都不可信。 */
    @Test
    fun wcagHelperMatchesTheKnownAnchor() {
        val blackOnWhite = contrastRatio(Color(0xFF000000), Color(0xFFFFFFFF))
        assertTrue(
            abs(blackOnWhite - 21.0) < 0.01,
            "WCAG 黑/白必须是 21:1，实际 $blackOnWhite —— 帮助函数自己错了",
        )
    }

    // --- harness ------------------------------------------------------------------

    /** 语义树里所有文本，按遍历顺序。 */
    private fun ComposeUiTest.screenTexts(): List<String> {
        val out = mutableListOf<String>()

        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            node.children.forEach { child -> walk(child) }
        }

        walk(onRoot().fetchSemanticsNode())
        return out
    }

    private fun List<String>.report(): String = joinToString("\n") { "    · $it" }

    private fun Double.rounded(): String = "%.2f".format(this)
}
