package vade.ui.destinations

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin
import vade.ui.testutil.IsolatedPreferencesTest

/**
 * g-miuix-slots · D9 —— 设置页「外观」块的文案必须与代码同真。
 *
 * 旧文案说「miuix / glass 是占位实现（返回 material token + 未安装角标）」—— 这句话在 miuix
 * 落地真 token、真字体缝（`VadeFonts`）和 12 个 C-09 组件之后就变成假的；真假由注册表决定，
 * 不由文案作者记得。所以这里钉两件事：
 *  1. 皮肤选择器给**用户可读名**（经典 / Miuix / 玻璃），内部 id 退到次级位置仍然可见；
 *  2. 点名字仍然按内部 id 回调 —— 改名不许把 `SkinRegistry` 的主键改掉。
 */
@OptIn(ExperimentalTestApi::class)
class ProfileSkinCopyTest : IsolatedPreferencesTest() {

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.setProfile(onSkinChange: (String) -> Unit = {}) {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                ProfileScreen(
                    skinId = "material",
                    barStyle = BottomBarStyle.Fixed,
                    containerLabel = "test",
                    onSkinChange = onSkinChange,
                    onBarChange = {},
                )
            }
        }
    }

    private fun ComposeUiTest.collectTexts(): List<String> {
        val out = mutableListOf<String>()

        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            node.children.forEach { child -> walk(child) }
        }

        walk(onRoot().fetchSemanticsNode())
        return out
    }

    @Test
    fun stalePlaceholderCopyIsGoneAndMiuixIsDescribedAsReal() {
        var texts: List<String> = emptyList()
        runComposeUiTest {
            setProfile()
            waitForIdle()
            texts = collectTexts()
        }
        val joined = texts.joinToString("\n")

        assertTrue(
            texts.none { "miuix / glass 是占位实现" in it },
            "设置页还在说 miuix 是占位实现（D9 过期文案）：\n$joined",
        )
        assertTrue(
            texts.any { "Miuix 更圆更重" in it },
            "设置页没有用人话说清 Miuix 的观感（I4：内部实现词不是用户文案）：\n$joined",
        )
        // glass 本轮不可选，但必须说清楚原因 —— 删掉这句同样是撒谎。
        assertTrue(
            texts.any { "玻璃" in it && ("还没做完" in it || "不能选" in it) },
            "glass 不可选，文案必须给出原因：\n$joined",
        )
    }

    @Test
    fun skinListShowsReadableNamesWithoutInternalIds() {
        var texts: List<String> = emptyList()
        runComposeUiTest {
            setProfile()
            waitForIdle()
            texts = collectTexts()
        }
        listOf("经典", "Miuix").forEach { name ->
            assertTrue(
                texts.any { it.trim() == name },
                "皮肤选择器没有用户可读名「$name」：\n" + texts.joinToString("\n") { "    · $it" },
            )
        }
        assertTrue(
            texts.any { "玻璃（待安装）" in it },
            "未安装的 glass 必须把状态写进标签：\n" + texts.joinToString("\n") { "    · $it" },
        )
        assertTrue(
            texts.none { "内部 id" in it },
            "I4：内部 id 不是用户文案，不应再出现在设置页：\n" +
                texts.joinToString("\n") { "    · $it" },
        )
    }

    @Test
    fun readableChipStillSelectsTheInternalId() {
        var picked: String? = null
        runComposeUiTest {
            setProfile(onSkinChange = { picked = it })
            waitForIdle()
            onNodeWithText("Miuix").performClick()
            waitForIdle()
        }
        assertEquals("miuix", picked, "可读名按钮必须仍按 SkinRegistry 主键回调")
    }
}
