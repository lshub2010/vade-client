package vade.ui.destinations

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import vade.ui.buildconfig.VadeBuildConfig
import vade.ui.data.PairingRuntime
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin
import vade.ui.testutil.IsolatedPreferencesTest

/**
 * r3-a11y · 两条无障碍回归：
 *
 * 1. **子页压住的底层页不许留在无障碍树里**。`ProfileScreen` 的「关于」/「连接 / 更换 Core」
 *    子页是同一个 `Box` 里的同级覆盖层（D91 全屏子页，不是弹层）—— 视觉盖住了，语义树上
 *    底层滚动列还在，读屏和键盘能走到屏幕上根本没显示的那几行设置项（审计 A11Y-2）。
 *    修法只在 `ProfileScreen` 的底层列上加 `invisibleToUser()`（+ `canFocus = false`），
 *    这里断言「打开 → 底层 ListItem 从无障碍树消失；关闭 → 恢复」。
 *
 * 2. **host / port 输入框有名字**。两个框的值从默认配置起就非空，占位文案永远不渲染，
 *    语义树上只是「可输入文本」（审计 A11Y-1）。`Skin.TextField` 新增的 `label` 形参
 *    同时给出可视小字与 `contentDescription`，这里断言**输入节点本身**带着这个名字。
 *
 * 断言只读语义树，不读像素 —— 没有截图基线也能常驻跑。
 */
@OptIn(ExperimentalTestApi::class)
class SubpageSemanticsTest : IsolatedPreferencesTest() {

    /** 桥是全局单例，用例前后清回未配对态（与 `CoreSwitchUiTest` 同一条纪律）。 */
    @BeforeTest
    fun resetBridgeBefore() = PairingRuntime.clearForTests()

    @AfterTest
    fun resetBridgeAfter() = PairingRuntime.clearForTests()

    // ---------------------------------------------------------------- 1 · 字段名

    @Test
    fun hostAndPortFieldsCarryTheirAccessibleName() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                PairingForm(
                    initialHost = "127.0.0.1",
                    initialPort = "8443",
                    submitLabel = PAIRING_BUTTON_LABEL,
                    submittingLabel = PAIRING_SUBMITTING,
                    // 本用例只读语义树，不提交：真跑连接链要 core。
                    onSubmit = { _, _, _ -> error("本用例不提交") },
                )
            }
        }
        waitForIdle()

        // 可访问名落在**输入节点**上：读屏聚焦进框里念的是「地址，编辑框」，不是裸的「编辑框」。
        onNode(hasSetTextAction() and hasContentDescription(PAIRING_HOST_LABEL)).assertExists()
        onNode(hasSetTextAction() and hasContentDescription(PAIRING_PORT_LABEL)).assertExists()

        // 同时是**看得见**的标签，不是只给读屏的隐藏文案。
        onNodeWithText(PAIRING_HOST_LABEL).assertExists()
        onNodeWithText(PAIRING_PORT_LABEL).assertExists()

        // 三个输入框仍是三个（label 只是加名字，没有拆/并任何节点）。
        assertTrue(
            onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 3,
            "host / port / 码三个输入框都要还在",
        )
    }

    // ---------------------------------------------------------------- 2 · 子页

    @Test
    fun aboutSubpageTakesTheSettingsColumnOutOfTheA11yTreeAndRestoresIt() = runComposeUiTest {
        PairingRuntime.noteBooted()
        setProfile()

        // 关着的时候：列与行都在无障碍树里，而且没有被隐藏（否则下面的断言等于白测）。
        assertInA11yTree(settingsColumn(), "底层设置列")
        assertInA11yTree(hasText(SETTINGS_ROW_UNDER_TEST), "设置行「$SETTINGS_ROW_UNDER_TEST」")
        assertInA11yTree(hasText(CORE_SWITCH_TITLE), "可点的「$CORE_SWITCH_TITLE」行")

        onNodeWithText("关于").performScrollTo().performClick()
        waitForIdle()

        // 子页真的开了，而且它自己**没有**被隐藏。
        assertInA11yTree(hasText("版本 ${VadeBuildConfig.VERSION}"), "关于子页正文")
        // 底层列整棵子树从无障碍树消失 —— 连**可点**的那一行也不再暴露给读屏/键盘。
        assertNotInA11yTree(settingsColumn(), "底层设置列")
        assertNotInA11yTree(hasText(SETTINGS_ROW_UNDER_TEST), "设置行「$SETTINGS_ROW_UNDER_TEST」")
        assertNotInA11yTree(hasText(CORE_SWITCH_TITLE), "可点的「$CORE_SWITCH_TITLE」行")

        // 返回 → 恢复（条件只读 showAbout，没有第二份状态）。
        onNodeWithContentDescription("返回").performClick()
        waitForIdle()
        assertInA11yTree(settingsColumn(), "底层设置列")
        assertInA11yTree(hasText(SETTINGS_ROW_UNDER_TEST), "设置行「$SETTINGS_ROW_UNDER_TEST」")
    }

    @Test
    fun coreSwitchSubpageTakesTheSettingsColumnOutOfTheA11yTreeAndRestoresIt() = runComposeUiTest {
        PairingRuntime.noteBooted()
        setProfile()

        onAllNodesWithText(CORE_SWITCH_TITLE).onFirst().performScrollTo().performClick()
        waitForIdle()

        // 子页在，而且它自己**没有**被隐藏。
        assertInA11yTree(hasTestTag(CORE_SWITCH_PANEL_TAG), "换 core 子页")
        // 底层设置列（含 ListItem）不可见。
        assertNotInA11yTree(settingsColumn(), "底层设置列")
        assertNotInA11yTree(hasText(SETTINGS_ROW_UNDER_TEST), "设置行「$SETTINGS_ROW_UNDER_TEST」")

        onNodeWithContentDescription("返回").performClick()
        waitForIdle()
        assertInA11yTree(settingsColumn(), "底层设置列")
        assertInA11yTree(hasText(SETTINGS_ROW_UNDER_TEST), "设置行「$SETTINGS_ROW_UNDER_TEST」")
    }

    // ------------------------------------------------------------- harness

    /**
     * 底层的设置滚动列。**关于子页的滚动列用的是同一个 tag**（`GlassSourceProfileTag`，
     * 两个列互斥组合、共用 tag 是有意的），子页打开时树里会有两个 —— 所以这里再用
     * 「包含某条设置行」把底层那一列钉住，不靠遍历顺序。
     */
    private fun settingsColumn(): SemanticsMatcher =
        hasTestTag(GlassSourceProfileTag) and hasAnyDescendant(hasText(SETTINGS_ROW_UNDER_TEST))

    private fun ComposeUiTest.setProfile() {
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
        waitForIdle()
    }

    /** 节点在无障碍树里，且自身/祖先都没有 `InvisibleToUser`。 */
    private fun ComposeUiTest.assertInA11yTree(matcher: SemanticsMatcher, what: String) {
        val nodes = onAllNodes(matcher).fetchSemanticsNodes()
        assertTrue(nodes.isNotEmpty(), "$what 不在语义树里 —— 用例的前提没了")
        assertTrue(
            nodes.none { it.isHiddenFromA11y() },
            "$what 被标成不可见（当前是打开状态？），但它应该对读屏可见",
        )
    }

    /**
     * 节点**已从无障碍树消失**。两种实现都算数（`invisibleToUser()` 的语义就是这一条）：
     * 测试框架把被隐藏的子树整个滤掉（找不到节点），或者节点还在但自身/祖先带
     * `InvisibleToUser`。两者都会让读屏/键盘拿不到这一行 —— 只要"还在且没标隐藏"就红。
     */
    private fun ComposeUiTest.assertNotInA11yTree(matcher: SemanticsMatcher, what: String) {
        val nodes = onAllNodes(matcher).fetchSemanticsNodes()
        val visible = nodes.filterNot { it.isHiddenFromA11y() }
        assertTrue(
            visible.isEmpty(),
            "$what 在子页打开时仍留在无障碍树里（匹配 ${nodes.size} 个节点，其中 ${visible.size} 个可见）",
        )
    }

    /**
     * 自身或任一祖先带「对无障碍隐藏」标记 —— 语义层的隐藏按子树生效。
     *
     * 两个 key 都算：`invisibleToUser()` 写 `InvisibleToUser`（Compose 1.12 已把函数名改成
     * `hideFromAccessibility()` → `HideFromAccessibility`，框架的 `SemanticsOwner.isHidden`
     * 两个 key 都认）。这样不论以后谁把生产代码换成新名字，这条断言仍然成立。
     */
    private fun SemanticsNode.isHiddenFromA11y(): Boolean =
        ancestry().any { node ->
            node.config.contains(SemanticsProperties.InvisibleToUser) ||
                node.config.contains(SemanticsProperties.HideFromAccessibility)
        }

    private fun SemanticsNode.ancestry(): Sequence<SemanticsNode> =
        generateSequence(this) { it.parent }

    private companion object {
        /** 「其它」卡里那一行 —— 只在底层设置页出现，子页上没有同名文案。 */
        const val SETTINGS_ROW_UNDER_TEST = "设备与通知"
    }
}
