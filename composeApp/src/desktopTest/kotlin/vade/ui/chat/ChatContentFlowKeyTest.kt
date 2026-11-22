package vade.ui.chat

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * f9-audit P1-2 · 「内容流」收集器的 key 必须带上它捕获的 `ChatState` 实例。
 *
 * 事故形态：`ChatThread` 里那条
 * `snapshotFlow { state.items.size to state.lastMessageBodyLength }` 的键原来只有
 * `(animate, viewport)`。`viewport` 的身份是 `(sessionId, nearBottomPx)` —— **不含 state**；
 * 而 `ChatState` 可以在 sessionId 不变的情况下被换成新实例（`SessionDetailScreen` 的
 * remember key 是 `(session.id, preset, adapters)`，`adapters` 从 null 变真/真变 null
 * 就会重建 ChatState）。那时组合槽位没变、viewport 键没变 ⇒ 收集器不重启，
 * `snapshotFlow` 一直读**旧**列表；旧列表此后不再变化，`onContentChanged` 的基线永久停住：
 * 上滚时「新内容」胶囊再也不出来，只有再切一次会话（viewport 换实例）才自愈。
 *
 * 为什么这条是源码扫描而不是像素用例：可见后果（上滚 → 新内容 → 胶囊）本身已由
 * [ChatFollowScrollGateTest] 逐条钉住；缺的那一半是**接线** —— 哪个 effect 被哪个键重启，
 * 只有组合槽位外的读者才看得见。同仓 `ChatSessionScrollResetTest` /
 * `EntrySourceRememberPinTest` 是同一手法（注释先剥掉，再数调用点）。
 */
class ChatContentFlowKeyTest {

    @Test
    fun theContentCollectorIsKeyedOnTheChatStateInstanceItCaptures() {
        val lines = codeOf("commonMain/kotlin/vade/ui/chat/ChatThread.kt").lines()

        // 读点：只有这一条 effect 的闭包读 `state.items`。
        val body = lines.indexOfFirst { it.contains("state.items.size to state.lastMessageBodyLength") }
        assertTrue(body > 0, "内容流收集器的读点不见了：本用例已与 ChatThread 脱节，先确认实现")

        val keyLine = lines.take(body).lastOrNull { it.contains("LaunchedEffect(") }
        assertTrue(keyLine != null, "内容流收集器不在 LaunchedEffect 里？")
        val keys = requireNotNull(keyLine)
            .substringAfter("LaunchedEffect(")
            .substringBeforeLast(")")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        assertTrue(
            keys.any { it == "state" },
            "key 必须含 `state` 本身（不是 `state.sessionId` 之类的派生值）：" +
                "ChatState 可以在同一 sessionId 下换实例，漏了这个键收集器会继续读旧列表；当前 key = $keys",
        )
        assertTrue(
            keys.any { it == "viewport" },
            "切会话时仍必须跟着 viewport 换（P2-2）：当前 key = $keys",
        )
        assertTrue(
            keys.any { it == "animate" },
            "截图模式（animate=false）仍在键里：它决定这条 effect 要不要跑；当前 key = $keys",
        )
    }

    /** Module dir: Gradle runs desktopTest with the composeApp module as working dir. */
    private fun codeOf(path: String): String {
        val file = File("src/$path")
        assertTrue(file.isFile, "guard target missing: ${file.absolutePath}")
        return file.readText()
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .split('\n')
            .joinToString("\n") { it.substringBefore("//") }
    }
}
