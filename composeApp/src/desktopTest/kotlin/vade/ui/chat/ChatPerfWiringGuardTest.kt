package vade.ui.chat

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 第 2 轮性能热点的**接线钉**（H2 / H4 / H7 / H8）。
 *
 * 为什么要有这个类：那四条修的都是「读点被放错了作用域 / 每行重复算」这类**结构性**问题，
 * 运行时组件的断言看不到它们（值一个都不变，代码却退回 O(N²) 或每帧唤醒）。所以这里做两件事：
 *  1. **源码扫描**：谁把 `SnapshotStateList` 重新读回 `LaunchedEffect` 的键、谁让
 *     `canEditMessage` 再扫一遍 `messages`、谁把像素流换回 `scroll.value`、谁把 px 写回装箱的
 *     `mutableStateOf(0)` —— 都能在无窗口的 CI 上被看见。形态照抄
 *     `ChatSessionScrollResetTest` 末尾那条接线用例（先剥注释再数）。
 *  2. **[theBottomEdgeBooleanDecidesExactlyLikeThePixelStream]**：H7 把「逐像素」换成
 *     「布尔边沿」，这条用例在同一串位置上逐点比对新旧两种喂法对
 *     [FollowScrollGate.unseenBelow] 的判定 —— 语义等价的证明，不是「看起来一样」。
 */
class ChatPerfWiringGuardTest {

    // ------------------------------------------------------------ H2 · ticker 键

    @Test
    fun theTwoTickersBindToDerivedKeysOnly() {
        val thread = codeOf("commonMain/kotlin/vade/ui/chat/ChatThread.kt")

        assertEquals(
            0,
            count(thread, """LaunchedEffect\(animate, state\.isStreaming\)"""),
            "流式 ticker 的键不许再是 state.isStreaming：它在真源上要扫 items，每条 delta 失效一次",
        )
        assertEquals(
            0,
            count(thread, """LaunchedEffect\(animate, state\.staged\.size\)"""),
            "上传 ticker 的键不许再直接读 SnapshotStateList 的 size：进度写入会让整页失效",
        )
        assertEquals(
            2,
            count(thread, """state\.(streaming|upload)TickerKey"""),
            "两个 ticker 各读一次派生键（StreamTicker / UploadTicker）",
        )
        assertEquals(
            1,
            count(thread, """StreamTicker\(state, animate\)"""),
            "ChatThread 必须把流式 ticker 下沉成叶子（键的读点离开页面作用域）",
        )
        assertEquals(
            1,
            count(thread, """UploadTicker\(state, animate\)"""),
            "上传 ticker 同理",
        )

        // 叶子里面除了那个派生键，不许再读列表（`while (state.isStreaming)` 是循环条件，
        // 在协程里读、不进组合，允许）。
        val streamLeaf = bodyOf(thread, "private fun StreamTicker(", "private fun UploadTicker(")
        assertTrue(streamLeaf.contains("state.streamingTickerKey"), "StreamTicker 的键必须是派生布尔")
        assertFalse(streamLeaf.contains("state.items"), "叶子不许读 items")
        assertFalse(streamLeaf.contains("state.staged"), "叶子不许读 staged")

        val uploadLeaf = bodyOf(thread, "private fun UploadTicker(", "private fun ThreadPhaseRow(")
        assertTrue(uploadLeaf.contains("state.uploadTickerKey"), "UploadTicker 的键必须是派生 Int")
        assertFalse(uploadLeaf.contains("state.staged"), "叶子不许读 staged")
    }

    @Test
    fun theStreamingFlagItselfIsObservable() {
        val state = codeOf("commonMain/kotlin/vade/ui/chat/ChatState.kt")
        assertTrue(
            state.contains("""private var runningId by mutableStateOf<String?>(null)"""),
            "isStreaming 的唯一本地来源必须是 snapshot state —— 否则派生键算不出第二个 true，" +
                "第二条消息发出去以后 ticker 再也不会开",
        )
        assertTrue(state.contains("""val streamingTickerKey: Boolean get() = streamingKey.value"""))
        assertTrue(state.contains("""val uploadTickerKey: Int get() = uploadKey.value"""))
    }

    // ------------------------------------------------------- H4 · 每行标量不扫表

    @Test
    fun thePerRowFactsNoLongerScanTheThread() {
        val state = codeOf("commonMain/kotlin/vade/ui/chat/ChatState.kt")

        val canEdit = bodyOf(state, "fun canEditMessage(", "fun startEdit(")
        assertTrue(canEdit.contains("queuePositionsState.value"), "可编辑判据读派生 Map")
        assertTrue(canEdit.contains("lastUserMessageIdState.value"), "最后一条用户行读派生值")
        assertFalse(canEdit.contains("messages"), "不许再走 messages（每次访问重建整张 List）")
        assertFalse(canEdit.contains("lastOrNull"), "扫描只许在派生状态里做一次")

        val queuePosition = bodyOf(state, "fun queuePosition(", "fun stop(")
        assertTrue(queuePosition.contains("queuePositionsState.value"), "排队位读派生 Map")
        assertFalse(queuePosition.contains("firstOrNull"), "不许再扫第一遍 queue")
        assertFalse(queuePosition.contains("indexOfFirst"), "不许再扫第二遍 queue")
    }

    // -------------------------------------------------------------- H7 · 布尔边沿

    @Test
    fun theScrollPositionIsObservedAsABottomEdgeNotAsPixels() {
        val thread = codeOf("commonMain/kotlin/vade/ui/chat/ChatThread.kt")

        assertEquals(
            0,
            count(thread, """snapshotFlow \{ scroll\.value \}"""),
            "像素流每像素唤醒一次协程，而 onScroll 只认「在不在贴底区间」一个 bit",
        )
        assertEquals(
            1,
            count(thread, """snapshotFlow \{ follower\.isAtBottom\(scroll\.value\) \}"""),
            "观测点必须是贴底布尔（判据仍由 FollowScrollGate 说了算，不在页面里另抄一份阈值）",
        )
        assertEquals(1, count(thread, """distinctUntilChanged\(\)"""), "一次跨越只许醒一次")
        assertEquals(
            1,
            count(thread, """follower\.onScroll\(if \(atBottom\) 0 else nearBottomPx \+ 1\)"""),
            "边界内给 0、边界外给阈值 + 1 —— 与逐像素调用对 onScroll 的判据完全等价",
        )
    }

    /**
     * 新旧两种喂法在**同一串位置**上必须给出同一个 `unseenBelow`：
     *  - 旧：[FollowScrollGate.onScroll] 每个像素调一次；
     *  - 新：只在「进 / 出贴底区间」的边沿调一次（0 / 阈值 + 1 编码）。
     *
     * 位置串里包含跨进、跨出、边界值本身（96 仍算贴底）、以及上滚途中又有新内容落地
     * （胶囊重新举起）—— 旧实现里这一步是「像素流不参与」的，新版也必须不参与。
     */
    @Test
    fun theBottomEdgeBooleanDecidesExactlyLikeThePixelStream() {
        val nearBottom = 96

        fun gate(): FollowScrollGate = FollowScrollGate(nearBottom).apply {
            onContentChanged(items = 4, lastBodyLength = 100, valuePx = 0) // 首帧只记账
        }

        /** 上滚途中的新内容：items 与末条正文都在长（`grew` 成立）。 */
        fun grow(g: FollowScrollGate, round: Int, valuePx: Int) {
            g.onContentChanged(items = 4 + round * 4, lastBodyLength = 100 + round * 140, valuePx = valuePx)
        }

        val pixel = gate()
        val edge = gate()
        grow(pixel, round = 1, valuePx = 400)
        grow(edge, round = 1, valuePx = 400)
        assertTrue(pixel.unseenBelow && edge.unseenBelow, "前置条件：上滚 + 新内容 = 胶囊举着")

        var edgeWasAtBottom = edge.isAtBottom(400)
        listOf(400, 300, 96, 120, 97, 40, 0, 500).forEachIndexed { index, value ->
            // 旧：每个位置都喂一次。
            pixel.onScroll(value)
            // 新：只有跨越贴底边界的那一次才喂。
            val atBottom = edge.isAtBottom(value)
            if (atBottom != edgeWasAtBottom) edge.onScroll(if (atBottom) 0 else nearBottom + 1)
            edgeWasAtBottom = atBottom

            assertEquals(
                pixel.unseenBelow,
                edge.unseenBelow,
                "位置 $value 上两种喂法必须给出同一个 unseenBelow",
            )

            // 回到贴底、再上滚（第 4 个位置 120，不在贴底区间）之后又有新内容落地：
            // 两边的胶囊都要重新举起 —— 这一步旧实现靠「内容流」、新版靠「只认边沿」，
            // 判定必须一致。
            if (index == 3) {
                grow(pixel, round = 2, valuePx = value)
                grow(edge, round = 2, valuePx = value)
                assertTrue(pixel.unseenBelow && edge.unseenBelow, "上滚 + 新内容 = 胶囊重新举起")
                assertEquals(pixel.unseenBelow, edge.unseenBelow, "新内容落地后两边仍要一致")
            }
        }
    }

    // ---------------------------------------------------------------- H8 · 不装箱

    @Test
    fun theTwoMeasuredHeightsAreUnboxedIntStates() {
        val thread = codeOf("commonMain/kotlin/vade/ui/chat/ChatThread.kt")

        assertEquals(
            2,
            count(thread, """by mutableIntStateOf\(0\)"""),
            "messagesHeightPx / composerHeightPx 必须是 IntState（IME 动画每帧写一次）",
        )
        assertEquals(
            0,
            count(thread, """by mutableStateOf\(0\)"""),
            "箱装的 Int state 一个都不许留在这两个 px 上",
        )
        assertTrue(
            thread.contains("var messagesHeightPx by mutableIntStateOf(0)"),
            "消息列高度仍是 Int、默认 0（行为不变）",
        )
        assertTrue(
            thread.contains("var composerHeightPx by mutableIntStateOf(0)"),
            "composer 高度仍是 Int、默认 0（行为不变）",
        )
    }

    // ------------------------------------------------------------------- 工具

    /** Gradle 以 composeApp 为工作目录跑 desktopTest。 */
    private fun codeOf(path: String): String {
        val file = File("src/$path")
        assertTrue(file.isFile, "guard target missing: ${file.absolutePath}")
        val noBlock = file.readText().replace(Regex("(?s)/\\*.*?\\*/"), "")
        return noBlock.split('\n').joinToString("\n") { it.substringBefore("//") }
    }

    private fun count(code: String, pattern: String): Int = Regex(pattern).findAll(code).count()

    /** 剥注释后的源码里，从某个签名到下一个签名的切片（够用来钉一个函数体）。 */
    private fun bodyOf(code: String, signature: String, nextSignature: String): String {
        val start = code.indexOf(signature)
        assertTrue(start >= 0, "找不到签名：$signature")
        val rest = code.substring(start + signature.length)
        val end = rest.indexOf(nextSignature)
        assertTrue(end >= 0, "找不到下一个签名：$nextSignature")
        return rest.substring(0, end)
    }
}
