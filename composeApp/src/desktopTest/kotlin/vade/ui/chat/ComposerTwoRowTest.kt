// g-composer-2row · composer 双行的验收（compose UI，桌面测试窗口）。
//
// 用户拍板的行为：模型控件**默认隐藏**；点输入区 → composer 变双行（输入行在上、页面控件
// 在下）；点 composer 外部收起；发送后保持展开；每次进入会话 / 切会话都收起。
//
// 这一组同时钉两件事：
//  1. `ChatThread` 的形参契约 —— `modelRow != null && expanded` 才组成第二行；不传
//     `modelRow` 时（AppShell 那些老调用点）composer 仍是原来那一行，一个节点都不多；
//  2. `SessionDetailScreen` 的页面行为 —— 展开态由页面持有（`remember(session.id)`），
//     指针信号从哪儿来（点输入区 / 点外部）与切会话后的重置。
//
// 已知的近似边界（本文件也顺带钉住）：这是**指针**近似，不是真焦点 —— 键盘 Tab 聚焦
// 不会展开（没有任何指针点击），真焦点要等 `Skin.InputBar` 的接口解冻。见
// `ChatThread.kt` 的 `observePointerTap`。

package vade.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue
import vade.ui.UiText
import vade.ui.data.SessionSummary
import vade.ui.data.TimeBucket
import vade.ui.destinations.SessionDetailScreen
import vade.ui.model.InMemoryModelActions
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin

@OptIn(ExperimentalTestApi::class)
class ComposerTwoRowTest {

    // --- ChatThread 的形参契约 ------------------------------------------------------

    /**
     * 默认路径：不传 [ChatThread.modelRow] —— 即使 `expanded = true` 也**不组成**第二行。
     * 老调用点（AppShell）就是这一路：布局与改造前逐字节一致。
     */
    @Test
    fun chatThreadWithoutAModelRowKeepsTheFrozenSingleRowComposer() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                Box(Modifier.requiredSize(411.dp, 760.dp)) {
                    ChatThread(
                        state = ChatState("s-2row-thread-default", preset = ChatPreset.Interrupted),
                        animate = false,
                        expanded = true,
                        modelRow = null,
                    )
                }
            }
        }
        waitForIdle()

        onNodeWithTag(ComposerModelRowTag).assertDoesNotExist()
        // 原来的一行还在：输入框（占位文案）与发送球。
        onNodeWithText("输入消息…").assertExists()
        onNodeWithContentDescription("发送消息").assertExists()
    }

    /**
     * `modelRow` 与 `expanded` **同时**成立才组成第二行：收起态不组成，展开态组成，
     * 且第二行在输入行**下方**（不是与发送球同一行）。
     */
    @Test
    fun chatThreadAddsTheSecondRowOnlyWhenExpandedAndGivenAModelRow() {
        val modelRow: @Composable () -> Unit = { UiText("模型行标记", size = 12) }

        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                    Box(Modifier.requiredSize(411.dp, 760.dp)) {
                        ChatThread(
                            state = ChatState("s-2row-thread-collapsed", preset = ChatPreset.Interrupted),
                            animate = false,
                            expanded = false,
                            modelRow = modelRow,
                        )
                    }
                }
            }
            waitForIdle()
            onNodeWithTag(ComposerModelRowTag).assertDoesNotExist()
            onNodeWithText("模型行标记").assertDoesNotExist()
        }

        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                    Box(Modifier.requiredSize(411.dp, 760.dp)) {
                        ChatThread(
                            state = ChatState("s-2row-thread-expanded", preset = ChatPreset.Interrupted),
                            animate = false,
                            expanded = true,
                            modelRow = modelRow,
                        )
                    }
                }
            }
            waitForIdle()
            onNodeWithTag(ComposerModelRowTag).assertExists()
            onNodeWithText("模型行标记").assertExists()

            // 第二行在输入行之下：发送球垂直居中于输入行，所以它的下沿必在模型行上沿之上。
            val orb = onNodeWithContentDescription("发送消息").fetchSemanticsNode().boundsInRoot
            val row = onNodeWithTag(ComposerModelRowTag).fetchSemanticsNode().boundsInRoot
            assertTrue(
                row.top >= orb.bottom,
                "模型行必须在输入行之下（row.top=${row.top} < orb.bottom=${orb.bottom}）",
            )
        }
    }

    // --- 会话页的页面行为 ------------------------------------------------------------

    /**
     * ① 默认收起 ② 点输入区展开 ③ 模型行在输入行下方。
     */
    @Test
    fun sessionPageHidesTheModelRowUntilTheComposerIsTouched() = runPage { _ ->
        // ① 进会话：composer 只有一行，模型 chip 不在语义树里。
        onNodeWithTag(ComposerModelRowTag).assertDoesNotExist()
        onNodeWithContentDescription("更改会话模型").assertDoesNotExist()

        // ② 点输入框（占位文案就是输入区的中心）→ 展开。
        onNodeWithText("输入消息…").performClick()
        waitForIdle()

        onNodeWithTag(ComposerModelRowTag).assertExists()
        onNodeWithContentDescription("更改会话模型").assertExists()

        // ③ 第二行在输入行之下 —— 不是「与发送球同一行」的旧形态。
        val orb = onNodeWithContentDescription("发送消息").fetchSemanticsNode().boundsInRoot
        val chip = onNodeWithContentDescription("更改会话模型").fetchSemanticsNode().boundsInRoot
        assertTrue(
            chip.top >= orb.bottom,
            "模型 chip 必须在输入行之下（chip.top=${chip.top} < orb.bottom=${orb.bottom}）",
        )
    }

    /**
     * 点 composer 外部 → 收起。点的是会话内容区（页面根的观察者），不是标题栏的某个按钮。
     */
    @Test
    fun tappingOutsideTheComposerCollapsesTheModelRow() = runPage { _ ->
        onNodeWithText("输入消息…").performClick()
        waitForIdle()
        onNodeWithTag(ComposerModelRowTag).assertExists()

        // 会话内容区（页面根坐标，远离底部浮层）—— 一次真实的「点别处」。
        onRoot().performTouchInput { click(Offset(200f, 200f)) }
        waitForIdle()

        onNodeWithTag(ComposerModelRowTag).assertDoesNotExist()
        onNodeWithContentDescription("更改会话模型").assertDoesNotExist()
    }

    /**
     * 切会话 → 收起：展开态挂在 `remember(session.id)` 上，不是一条全局记忆。
     */
    @Test
    fun switchingSessionResetsTheComposerToCollapsed() = runPage { switchTo ->
        onNodeWithText("输入消息…").performClick()
        waitForIdle()
        onNodeWithTag(ComposerModelRowTag).assertExists()

        switchTo(session("s-2row-b", "双行 B"))
        waitForIdle()

        // 换会话真的发生了（标题换了），且新会话的 composer 是收起态。
        onNodeWithText("双行 B").assertExists()
        onNodeWithTag(ComposerModelRowTag).assertDoesNotExist()
        onNodeWithContentDescription("更改会话模型").assertDoesNotExist()
    }

    /**
     * 输入（onValueChange）与发送都不许收起：打字后仍在，发送后仍在。
     *
     * 发送走真实的发送球（`state.submit()` 的同一入口），因此这条同时钉住「submit 不写
     * 展开态」—— 收起只有两个来源：点外部、切会话。
     */
    @Test
    fun typingAndSendingKeepTheComposerExpanded() = runPage { _ ->
        onNodeWithText("输入消息…").performClick()
        waitForIdle()
        onNodeWithTag(ComposerModelRowTag).assertExists()

        onNode(hasSetTextAction()).performTextInput("发一条试试")
        waitForIdle()
        onNodeWithTag(ComposerModelRowTag).assertExists()

        onNodeWithContentDescription("发送消息").performClick()
        waitForIdle()

        // 发送真的发生了（占位文案回到空输入框），而第二行还在。
        onNodeWithText("输入消息…").assertExists()
        onNodeWithTag(ComposerModelRowTag).assertExists()
        onNodeWithContentDescription("更改会话模型").assertExists()
    }

    // --- 夹具 ----------------------------------------------------------------------

    private fun session(id: String, title: String) = SessionSummary(
        id = id,
        title = title,
        preview = "g-composer-2row",
        bucket = TimeBucket.Today,
        updatedAt = 1_700_000_000_000L,
        updatedLabel = "刚刚",
    )

    /**
     * 手机宽度的确定性画布（411×760dp，与 `ModelChipSheetUiTest` 同一套）。
     *
     * `preset = Interrupted`：夹具默认停在一个流式回合上（发送球是 ■ 停止），发送路径的
     * 测试要的是**发送球**。Interrupted 把那一轮正常收尾 —— 不编状态、不改页面逻辑。
     */
    private fun runPage(block: ComposeUiTest.(switchTo: (SessionSummary) -> Unit) -> Unit) =
        runComposeUiTest {
            val current = mutableStateOf(session("s-2row-a", "双行 A"))
            setContent {
                CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                    Box(Modifier.requiredSize(411.dp, 760.dp)) {
                        SessionDetailScreen(
                            session = current.value,
                            animate = false,
                            preset = ChatPreset.Interrupted,
                            modelActions = InMemoryModelActions(),
                        )
                    }
                }
            }
            waitForIdle()
            block { next -> runOnIdle { current.value = next } }
        }
}
