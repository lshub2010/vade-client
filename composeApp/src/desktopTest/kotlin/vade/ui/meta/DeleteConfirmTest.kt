package vade.ui.meta

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 测试④：删除会话**必须经过二次确认** —— 三条路径：
 *  1. 菜单点「删除」→ 只开确认层，零回调；
 *  2. 用户取消 → 层关掉，零回调；
 *  3. 用户确认 → 恰好一条 `session/delete`，会话从本地与"服务端面"（fake）同时消失。
 */
class DeleteConfirmTest {

    private fun state(fake: FakeMetaActions = FakeMetaActions()) =
        SessionMetaState(fake.sessions.value, fake.groups.value, fake)

    @Test
    fun requestDeleteOpensConfirmWithoutAnyDeleteCallback() {
        val fake = FakeMetaActions()
        val s = state(fake)
        val before = s.sessions.size
        s.requestDelete("s-1")
        val editor = s.editor
        assertTrue(editor is MetaEditor.ConfirmDeleteSession, "必须先进入确认层")
        assertEquals("s-1", editor.session.id)
        assertEquals(before, s.sessions.size, "确认前会话一条不动")
        assertEquals(0, fake.calls.count { it.op == "session/delete" }, "未确认时绝不回调删除")
    }

    @Test
    fun cancelDeleteClosesAndStillNeverCalls() = runBlocking {
        val fake = FakeMetaActions()
        val s = state(fake)
        s.requestDelete("s-1")
        s.cancelDelete()
        assertNull(s.editor, "取消后确认层必须关闭")
        assertTrue(s.sessions.any { it.id == "s-1" }, "取消不会删")
        assertEquals(0, fake.calls.count { it.op == "session/delete" })
    }

    @Test
    fun confirmDeletesExactlyOnceLocallyAndOnServerFace() = runBlocking {
        val fake = FakeMetaActions()
        val s = state(fake)
        s.requestDelete("s-1")
        s.confirmDelete()
        val deletes = fake.calls.filter { it.op == "session/delete" }
        assertEquals(1, deletes.size, "一次确认恰好一条删除回调")
        assertEquals("s-1", deletes.single().detail)
        assertFalse(s.sessions.any { it.id == "s-1" }, "本地列表同步移除")
        assertFalse(fake.sessions.value.any { it.id == "s-1" }, "服务端面（fake）同步移除")
        assertNull(s.editor, "确认后弹层关闭")
    }

    @Test
    fun confirmWithoutRequestIsANoOp() = runBlocking {
        val fake = FakeMetaActions()
        val s = state(fake)
        s.confirmDelete() // 没有打开确认层时不得凭空删除
        assertEquals(0, fake.calls.count { it.op == "session/delete" })
        assertEquals(fake.sessions.value.size, s.sessions.size)
    }

    @Test
    fun deleteCopyStatesIrrecoverability() {
        // 文案必须向用户说明不可恢复（删除是服务端真删）。
        assertTrue(MetaCopy.DELETE_BODY.contains("不可恢复"))
        assertTrue(MetaCopy.DELETE_BODY.contains("无法撤销"))
        assertTrue(MetaCopy.DELETE_CONFIRM.isNotEmpty())
    }
}
