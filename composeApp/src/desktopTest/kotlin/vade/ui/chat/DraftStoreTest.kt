package vade.ui.chat

import vade.ui.destinations.resolveChatState
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 草稿持久层 + [ChatState] 接线的验收项 —— 全部直接驱动 [DraftStore] / [ChatState]，
 * 无窗口、无时钟（与 `ChatFlowTest` 同一模式）。
 *
 * 用户要的是「点底栏切到别的 tab 再回会话页，输入框里的半句话还在」。页面在这条路径上被
 * 销毁重建，所以断言的是**重建后的 [ChatState]** 而不是某个 state 实例内部的字段：
 * ① 保存/读取原样往返 ② 空串等同清除 ③ 会话之间不串味
 * ④ 重建后草稿仍在 ⑤ 发送成功后草稿（和 store）都清空。
 *
 * 大多数用例注入自己的一份 [DraftStore]（隔离）；只有「默认参数＝进程内单例」那条按产品
 * 路径跑真的 [DraftStore.shared]，并在每个用例后清干净，绝不给别的测试留残留。
 */
class DraftStoreTest {

    @AfterTest
    fun cleanTheProcessWideStore() {
        // 共享单例是进程级的：任何一条用例写过它，都要把表收干净。
        DraftStore.shared.clearAll()
    }

    // --- the store on its own -------------------------------------------------

    // ① save → load 原样往返（多行、中英文、首尾空格都不许被规整）
    @Test
    fun saveThenLoadReturnsExactlyWhatWasTyped() {
        val store = DraftStore()
        val draft = "第一行：把 132dp 的高度上限再核一遍。\n  第二行：缩进和尾部空格要原样回来  "

        store.save("s-draft", draft)

        assertEquals(draft, store.load("s-draft"))
    }

    // ② 空串等同清除：load 回到空串，且不留一个「存在但为空」的条目
    @Test
    fun savingAnEmptyStringClearsTheEntry() {
        val store = DraftStore()
        store.save("s-draft", "半句话")

        store.save("s-draft", "")

        assertEquals("", store.load("s-draft"), "空串就是清除，load 不该再返回旧草稿")
        assertEquals("", store.load("s-draft-no-such-session"), "没存过的会话同样返回空串，不是 null")
    }

    // ③ 会话隔离：A 的草稿不出现在 B 里，清除 A 也不动 B
    @Test
    fun sessionsDoNotBleedIntoEachOther() {
        val store = DraftStore()
        store.save("s-a", "A 会话写到一半")
        store.save("s-b", "B 会话写到一半")

        assertEquals("A 会话写到一半", store.load("s-a"))
        assertEquals("B 会话写到一半", store.load("s-b"))

        store.clear("s-a")

        assertEquals("", store.load("s-a"))
        assertEquals("B 会话写到一半", store.load("s-b"), "清 A 不能顺手把 B 也清了")
        assertNotEquals(store.load("s-a"), store.load("s-b"))
    }

    // --- ChatState wiring -----------------------------------------------------

    // ④ 页面重建（同一个 sessionId 上的第二个 ChatState）把草稿恢复回来 —— 注入的隔离 store
    @Test
    fun aRebuiltChatStateRestoresItsDraft() {
        val store = DraftStore()
        val beforeLeaving = ChatState("s-draft-rebuild", drafts = store)
        beforeLeaving.updateDraft("先别改 gradle.properties，等我贴一下 daemon 日志。")

        // 离开会话页：旧实例被丢掉，屏幕上新建一个（就是 resolveChatState 做的事）
        val afterComingBack = ChatState("s-draft-rebuild", drafts = store)

        assertEquals(
            "先别改 gradle.properties，等我贴一下 daemon 日志。",
            afterComingBack.draft,
            "重建后草稿必须从 store 里回来",
        )
    }

    // ④' 产品路径：不注入 store，默认参数就是进程内单例 [DraftStore.shared]
    @Test
    fun theDefaultStoreIsTheProcessWideOne() {
        val beforeLeaving = ChatState("s-draft-shared")
        beforeLeaving.updateDraft("切走再回来这条要还在")

        val afterComingBack = ChatState("s-draft-shared")

        assertEquals("切走再回来这条要还在", afterComingBack.draft)
    }

    // ④''' 产品入口：会话页「离开再回来」重建时走的就是 resolveChatState（同一个 session.id）
    @Test
    fun comingBackThroughTheScreenSeamRestoresTheDraft() {
        val beforeLeaving = resolveChatState("s-draft-seam", ChatPreset.Live, adapters = null).value
        beforeLeaving.updateDraft("从搜索页点回来，草稿该还在")

        val afterComingBack = resolveChatState("s-draft-seam", ChatPreset.Live, adapters = null).value

        assertEquals("从搜索页点回来，草稿该还在", afterComingBack.draft)
    }

    // ④'' 会话隔离在 ChatState 这一层同样成立
    @Test
    fun anotherSessionsStateStartsEmpty() {
        val store = DraftStore()
        ChatState("s-draft-one", drafts = store).updateDraft("只属于 one")

        val other = ChatState("s-draft-two", drafts = store)

        assertEquals("", other.draft, "别的会话重建时不该看到 one 的草稿")
        assertTrue(!other.canSend, "空草稿不可发送")
    }

    // ⑤ 发送成功 → 输入框清空，store 里也清空；再重建仍然是空的
    @Test
    fun sendingClearsBothTheComposerAndTheStore() {
        val store = DraftStore()
        val chat = ChatState("s-draft-send", drafts = store)
        chat.stop() // fixtures 开局在流式中，先回到 idle，走「空闲发送」这条路
        chat.updateDraft("把 §3 的分片写细一点")
        assertEquals("把 §3 的分片写细一点", store.load("s-draft-send"), "编辑时就该写回 store")

        assertNotNull(chat.submit(), "非空草稿必须能发出去")

        assertEquals("", chat.draft, "发送成功清空输入框")
        assertEquals("", store.load("s-draft-send"), "发送成功同样要把 store 里的草稿删掉")
        assertEquals("", ChatState("s-draft-send", drafts = store).draft, "重建时不该再冒出已发送的草稿")
    }

    // ⑤' 用户手动清空输入框 → store 清空（否则切回来会「复活」一段删掉的文字）
    @Test
    fun clearingTheComposerByHandClearsTheStore() {
        val store = DraftStore()
        val chat = ChatState("s-draft-clear", drafts = store)
        chat.updateDraft("写了一半又不要了")

        chat.updateDraft("")

        assertEquals("", store.load("s-draft-clear"))
        assertEquals("", ChatState("s-draft-clear", drafts = store).draft)
    }

    // ⑤'' 只剩空白也按清除处理：切回来不该看到一串只有空格/换行的输入
    @Test
    fun whitespaceOnlyDraftIsNotPersisted() {
        val store = DraftStore()
        val chat = ChatState("s-draft-blank", drafts = store)

        chat.updateDraft("   \n  ")

        assertEquals("", store.load("s-draft-blank"))
    }
}
