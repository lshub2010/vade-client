// g-model-chip · 会话页模型选择的合规形态验收（compose UI，桌面测试窗口）。
//
// 五个验收点（任务书【测试】节）：
//  1. chip 在 composer 区域内、输入行**下方**的第二行（g-composer-2row 后的几何断言，
//     不是「与发送球同一行」的旧形态）；
//  2. 会话内容区顶部不再出现 ModelPickerCard（回归：内联卡被消掉；且开浮层时
//     首条消息一个像素都不动 —— 不再把会话往下顶）；
//  3. 点 chip → 出现浮层卡片（有把手提示、有标题、有模型行）；点遮罩 / Esc 关闭；
//  4. 选中某个模型 → chip 文字立刻变，且仍经 seam 写入（session/model.set 的客户端侧）；
//  5. 长模型名整串不上屏（省略号 + 宽度上限 + 发送球不被挤走）；
//  6. 加载失败 / 无模型 / 只读三态在 chip 上有对应表现，且点了都有响应（浮层给出诚实正文）。
//
// Esc 的注入走焦点路径：浮层卡片打开时经 FocusRequester 持有焦点，注入的 Esc 沿
// 「根 Box 的 onPreviewKeyEvent」先于一切子节点到达 —— 与 shell 快捷键同一机制。

package vade.ui.model

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import vade.ui.data.SessionSummary
import vade.ui.data.TimeBucket
import vade.ui.destinations.SessionDetailScreen
import vade.ui.protocol.ModelEntry
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin

@OptIn(ExperimentalTestApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class ModelChipSheetUiTest {

    private val sessionId = "s-chip-1"

    private fun session(model: String? = null): SessionSummary = SessionSummary(
        id = sessionId,
        title = "模型芯片测试会话",
        preview = "g-model-chip",
        bucket = TimeBucket.Today,
        updatedAt = 1_700_000_000_000L,
        updatedLabel = "刚刚",
        model = model,
    )

    /** InMemoryModelActions + 一支记录笔：断言「写入真的经过 seam（session/model.set 客户端侧）」。 */
    private class RecordingActions(
        models: List<ModelEntry>? = ModelFixtures.catalog,
        initialSessionOverrides: Map<String, String?> = emptyMap(),
    ) : ModelActions by InMemoryModelActions(
        models = models,
        initialSessionOverrides = initialSessionOverrides,
    ) {
        val writes = mutableListOf<Pair<String, String?>>()
        override fun setSessionModel(sessionId: String, modelId: String?) {
            writes += sessionId to modelId
        }
    }

    /** 手机宽度的确定性画布：411×760dp（桌面测试窗口根边界 768，留 8dp 余量保证可点）。 */
    private fun runScreenTest(
        recording: RecordingActions = RecordingActions(
            // 显式传入的 actions 不走 seedModel 播种（三段回退优先级）——这里手动播种
            // 会话覆盖，让 header/chip 显示 Claude Sonnet。
            initialSessionOverrides = mapOf(sessionId to ModelFixtures.claudeSonnet.id),
        ),
        block: ComposeUiTest.() -> Unit,
    ) = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                Box(Modifier.requiredSize(411.dp, 760.dp)) {
                    SessionDetailScreen(
                        session = session(model = ModelFixtures.claudeSonnet.id),
                        animate = false,
                        showTitle = true,
                        modelActions = recording,
                    )
                }
            }
        }
        waitForIdle()
        block()
    }

    /**
     * 发送球：fixture 末条消息处于 Running ⇒ 球是「停止生成」态（§2 三态）；空闲会话才是
     * 「发送消息」。几何断言不关心具体状态，两个描述都认。
     */
    private fun ComposeUiTest.orbBounds() =
        (onAllNodesWithContentDescription("发送消息").fetchSemanticsNodes() +
            onAllNodesWithContentDescription("停止生成").fetchSemanticsNodes())
            .first().boundsInRoot

    /**
     * g-composer-2row · 模型 chip 默认**收起**：先点输入框把第二行展开。
     *
     * 用真手势（点输入区的占位文案）而不是去改页面内部状态 —— 这条路径正是用户的那一下，
     * 也是「展开」唯一的产品入口（页面自己持有 `composerExpanded`）。
     */
    private fun ComposeUiTest.expandComposer() {
        onNodeWithText("输入消息…").performClick()
        waitForIdle()
    }

    // --- 1 · chip 在 composer 区域内、输入行下方的第二行 --------------------------------

    @Test
    fun chipSitsInTheComposerSecondRowUnderTheInput() = runScreenTest {
        expandComposer()
        val chip = onNodeWithContentDescription("更改会话模型").fetchSemanticsNode().boundsInRoot
        val orb = orbBounds()
        val headerLine = onNodeWithText("模型 Claude Sonnet", substring = true)
            .fetchSemanticsNode().boundsInRoot

        // g-composer-2row：chip 不再与发送球同一行 —— 它在输入行**下方**的第二行。
        assertTrue(
            chip.top >= orb.bottom,
            "chip 必须在输入行之下（chip.top=${chip.top} < orb.bottom=${orb.bottom}）",
        )

        // chip 在 header 之下 = composer 区域内，不在会话内容区顶部。
        assertTrue(chip.top > headerLine.bottom, "chip 必须在 header 之下（composer 内）")
    }

    // --- 2 · 回归：内容区顶部不再出现内联 ModelPickerCard；消息不被往下顶 --------------

    @Test
    fun headerColumnCarriesNoInlinePickerCard() = runScreenTest {
        // ModelPickerCard 的折叠头 chevron 是它唯一的语义签名 —— 会话页上绝不允许出现。
        assertTrue(
            onAllNodesWithContentDescription("展开模型列表").fetchSemanticsNodes().isEmpty(),
            "内联 ModelPickerCard 必须从会话页消失（§334：模型选择是 floating sheet）",
        )
        assertTrue(
            onAllNodesWithContentDescription("收起模型列表").fetchSemanticsNodes().isEmpty(),
            "内联 ModelPickerCard 必须从会话页消失",
        )
        // header 模型行退化为纯信息行：文本仍在，但不再承载切换入口。
        onNodeWithText("模型 Claude Sonnet", substring = true).assertExists()
    }

    @Test
    fun firstMessageDoesNotMoveWhenTheSheetOpens() = runScreenTest {
        expandComposer()
        val firstMessageBefore = onNodeWithText("本会话使用假数据", substring = true)
            .fetchSemanticsNode().boundsInRoot.top

        onNodeWithContentDescription("更改会话模型").performClick()
        onNodeWithContentDescription("关闭会话模型").assertExists()

        val firstMessageAfter = onNodeWithText("本会话使用假数据", substring = true)
            .fetchSemanticsNode().boundsInRoot.top
        assertEquals(firstMessageBefore, firstMessageAfter, "开浮层不许把会话往下顶一个像素")
    }

    // --- 3 · 点 chip → 浮层卡片（把手提示 / 标题 / 模型行）；遮罩与 Esc 关闭 -------------

    @Test
    fun chipOpensTheBottomAnchoredSheetWithHonestBody() = runScreenTest {
        expandComposer()
        onNodeWithContentDescription("更改会话模型").performClick()

        // 卡片结构：标题行（图标+标题+✕）→ 「跟随全局默认」行 → 模型行 → 出处脚注。
        // chip 与浮层模型行同名（都是当前模型），用 count ≥1 断言而不是 onNodeWithText。
        onNodeWithText("会话模型").assertExists()
        onNodeWithContentDescription("关闭会话模型").assertExists()
        assertTrue(onAllNodesWithText("Claude Sonnet").fetchSemanticsNodes().isNotEmpty())
        onNodeWithText("跟随全局默认").assertExists()
        onNodeWithText("仅本会话生效", substring = true).assertExists()

        // §334 的形态是 bottom sheet：标题在 header 区域之下很远（绝不是插在 header 列里）。
        val title = onNodeWithText("会话模型").fetchSemanticsNode().boundsInRoot
        val headerLine = onNodeWithText("模型 Claude Sonnet", substring = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(title.top > headerLine.bottom, "浮层必须浮在整屏之上，不贴在会话顶部")
    }

    @Test
    fun maskTapClosesTheSheet() = runScreenTest {
        expandComposer()
        onNodeWithContentDescription("更改会话模型").performClick()
        onNodeWithText("会话模型").assertExists()

        // 遮罩节点中心会被底部的卡片盖住 —— 点卡片上方的真遮罩区域（与真人操作一致）。
        onNodeWithContentDescription("模型选择遮罩").performTouchInput { click(Offset(205f, 60f)) }
        onNodeWithText("会话模型").assertDoesNotExist()
        // 关掉后 chip 还在（触发点没有消失）：浮层打开期间的点击不算「点 composer 外部」，
        // 所以第二行仍是展开态。
        onNodeWithContentDescription("更改会话模型").assertExists()
    }

    @Test
    fun escapeClosesTheSheet() = runScreenTest {
        expandComposer()
        onNodeWithContentDescription("更改会话模型").performClick()
        onNodeWithText("会话模型").assertExists()

        // 纯谓词先钉住（Key-up / 其他键不关）。
        val keyDown = KeyEvent(key = Key.Escape, type = KeyEventType.KeyDown)
        val keyUp = KeyEvent(key = Key.Escape, type = KeyEventType.KeyUp)
        assertTrue(isSheetEscape(keyDown), "Esc KeyDown 必须命中关闭谓词")
        assertTrue(!isSheetEscape(keyUp), "Esc KeyUp 不触发关闭")

        // 行为层：注入 Esc（浮层卡持有焦点 → 事件沿焦点路径进根 Box 的 onPreviewKeyEvent）。
        onNodeWithText("会话模型").performKeyInput { pressKey(Key.Escape) }
        onNodeWithText("会话模型").assertDoesNotExist()
    }

    // --- 4 · 选中模型 → chip 立刻变 + seam 写入 -----------------------------------------

    @Test
    fun selectingModelUpdatesChipLabelAndWritesTheSeam() {
        val actions = RecordingActions(
            initialSessionOverrides = mapOf(sessionId to ModelFixtures.claudeSonnet.id),
        )
        runScreenTest(recording = actions) {
            expandComposer()
            onNodeWithContentDescription("更改会话模型").performClick()
            // 选中行展开 11 行 detail，Qwen 行可能被压出 360dp 滚动视口 —— 先滚到可见再点。
            onNodeWithText("Qwen2.5 32B").performScrollTo()
            onNodeWithText("Qwen2.5 32B").performClick()

            // 写入真的经过 seam（客户端侧 session/model.set 的落点），且带的是协议 id 不是名字。
            assertEquals(
                listOf<Pair<String, String?>>(sessionId to "local-qwen"),
                actions.writes,
                "选中必须走 ModelActions.setSessionModel（session/model.set），值 = 协议 model_id",
            )

            // 选完不自动关（写入结果必须留在屏幕上可见，§8.5）。
            onNodeWithText("模型已切换", substring = true).assertExists()
            // 关闭后 chip 文字立刻是新模型（乐观选择，不等回包）。
            onNodeWithContentDescription("关闭会话模型").performClick()
            onNodeWithText("Qwen2.5 32B").assertExists()
        }
    }

    // --- 5 · 长模型名：整串不上屏，chip 有宽度上限，发送球不被挤走 ------------------------

    @Test
    fun longModelNameEllipsizesAndNeverDisplacesTheSendOrb() {
        val longName = "Anthropic-Claude-Opus-4.6-Extended-Thinking-Supernova-Ultra-Max-Pro-Elite-Premium-Edition"
        val entry = ModelEntry(
            id = "long-model",
            provider = "anthropic",
            displayName = longName,
            inputModalities = listOf("text"),
            maxImageBytes = null,
            maxImagePx = null,
            maxImagesPerTurn = null,
            contextWindow = null,
            supportsTools = null,
            priority = 5,
            available = true,
        )
        runComposeUiTest {
            var density = Density(1f)
            setContent {
                density = LocalDensity.current
                CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                    Box(Modifier.requiredSize(411.dp, 760.dp)) {
                        SessionDetailScreen(
                            session = session(model = "long-model"),
                            animate = false,
                            modelActions = RecordingActions(
                                models = listOf(entry),
                                initialSessionOverrides = mapOf(sessionId to "long-model"),
                            ),
                        )
                    }
                }
            }
            waitForIdle()

            expandComposer()
            val chip = onNodeWithContentDescription("更改会话模型").fetchSemanticsNode().boundsInRoot
            val orb = orbBounds()
            val capPx = with(density) { 148.dp.toPx() }
            assertTrue(chip.width <= ceil(capPx), "chip 宽度必须压在上限内（${chip.width} > $capPx）")
            assertTrue(chip.height < with(density) { 44.dp.toPx() }, "chip 必须单行（不换行撑高）")
            // 发送球仍在最右（chip 在面板内，永不外挤）。
            assertTrue(orb.right > chip.right, "发送球必须仍在 chip 右侧、不被挤走")
        }
    }

    // --- 6 · 加载失败 / 无模型 / 只读三态：chip 有对应表现，点了必响应 ---------------------

    @Test
    fun chipShowsTheHonestDegradedStates() {
        // 无模型（seam 报 null）：chip 文案 + 浮层正文都是「无可用模型」，不是空列表。
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                    Box(Modifier.requiredSize(411.dp, 760.dp)) {
                        SessionDetailScreen(
                            session = session(),
                            animate = false,
                            modelActions = InMemoryModelActions(models = null),
                        )
                    }
                }
            }
            waitForIdle()
            expandComposer()
            onNodeWithText("无可用模型").assertExists() // chip 文案
            onNodeWithContentDescription("更改会话模型").performClick()
            onNodeWithText("当前没有可路由的模型 provider", substring = true).assertExists() // 浮层诚实正文
            onNodeWithContentDescription("模型选择遮罩").performClick()
        }

        // 只读（FR-8.46 退化）：chip 直接说「只读」，浮层给出原因 —— 点了必有响应。
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                    Box(Modifier.requiredSize(411.dp, 760.dp)) {
                        SessionDetailScreen(
                            session = session(),
                            animate = false,
                            modelActions = InMemoryModelActions(models = null, readOnly = true),
                        )
                    }
                }
            }
            waitForIdle()
            expandComposer()
            onNodeWithText("只读").assertExists()
            onNodeWithContentDescription("更改会话模型").performClick()
            onNodeWithText("此 core 不支持模型选择").assertExists()
            onNodeWithContentDescription("关闭会话模型").assertExists()
        }
    }

    // --- 7 · 关态：浮层不存在、遮罩不存在 ------------------------------------------------

    @Test
    fun closedStateCarriesNoSheetNodes() = runScreenTest {
        // 关态：浮层不存在、遮罩不存在 —— 屏幕只有一个 chip 触发点（展开后）。
        assertTrue(onAllNodesWithText("会话模型").fetchSemanticsNodes().isEmpty())
        assertTrue(onAllNodesWithContentDescription("模型选择遮罩").fetchSemanticsNodes().isEmpty())
        // g-composer-2row：chip 默认收起（见 ComposerTwoRowTest），展开后才是唯一触发点。
        onNodeWithContentDescription("更改会话模型").assertDoesNotExist()
        expandComposer()
        onNodeWithContentDescription("更改会话模型").assertExists()
    }
}
