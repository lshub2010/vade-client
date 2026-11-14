package vade.ui.meta

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import vade.ui.icon.MetaIcons

/**
 * 测试③（菜单 6 项完整）+ 测试⑤（置顶互斥）+ 菜单分派路由 / 重命名校验 / 移动分组。
 * 全部走真 [SessionMetaState] + [FakeMetaActions]，不 mock 状态机本身。
 */
class SessionMenuTest {

    private fun state(fake: FakeMetaActions = FakeMetaActions()) =
        SessionMetaState(fake.sessions.value, fake.groups.value, fake)

    @Test
    fun menuHasExactlyTheSixContractedItemsInOrder() {
        val s = state()
        assertEquals(
            expected = listOf(
                SessionMenuItem.Rename,
                SessionMenuItem.TogglePin,
                SessionMenuItem.ToggleArchive,
                SessionMenuItem.MoveToGroup,
                SessionMenuItem.NewGroup,
                SessionMenuItem.Delete,
            ),
            actual = s.menuItems(),
            message = "上下文菜单固定 6 项，顺序也是契约",
        )
        assertEquals(6, s.menuItems().size)
        // 破坏性标记只落在删除上：其余项一律非 destructive。
        assertTrue(SessionMenuItem.Delete.destructive)
        s.menuItems().filter { it != SessionMenuItem.Delete }.forEach { assertFalse(it.destructive) }
    }

    @Test
    fun pinLabelsAreMutuallyExclusiveAndFollowState() = runBlocking {
        val fake = FakeMetaActions()
        val s = state(fake)
        // s-1 种子即置顶：标签必须是「取消置顶」。
        assertEquals(MetaCopy.MENU_UNPIN, s.menuLabel(SessionMenuItem.TogglePin, "s-1"))
        s.onMenuItem("s-1", SessionMenuItem.TogglePin)
        assertFalse(
            s.sessions.first { it.id == "s-1" }.pinned,
            "置顶态点一次必须变成未置顶（互斥布尔，不是叠加）",
        )
        assertEquals(MetaCopy.MENU_PIN, s.menuLabel(SessionMenuItem.TogglePin, "s-1"))
        // 再点一次回到置顶：恰好一条 pin 回调对应一次翻转。
        s.onMenuItem("s-1", SessionMenuItem.TogglePin)
        assertTrue(s.sessions.first { it.id == "s-1" }.pinned)
        assertEquals(
            listOf("session/pin:s-1:false", "session/pin:s-1:true"),
            fake.calls.filter { it.op == "session/pin" }.map { "${it.op}:${it.detail}" },
        )
    }

    @Test
    fun archiveLabelsToggleTheSameWay() = runBlocking {
        val fake = FakeMetaActions()
        val s = state(fake)
        assertEquals(MetaCopy.MENU_ARCHIVE, s.menuLabel(SessionMenuItem.ToggleArchive, "s-2"))
        s.onMenuItem("s-2", SessionMenuItem.ToggleArchive)
        assertTrue(s.sessions.first { it.id == "s-2" }.archived)
        assertEquals(MetaCopy.MENU_UNARCHIVE, s.menuLabel(SessionMenuItem.ToggleArchive, "s-2"))
    }

    @Test
    fun deleteMenuItemOnlyOpensConfirm_neverDeletesDirectly() = runBlocking {
        val fake = FakeMetaActions()
        val s = state(fake)
        s.onMenuItem("s-1", SessionMenuItem.Delete)
        assertTrue(s.editor is MetaEditor.ConfirmDeleteSession, "菜单里的删除必须落在确认弹层")
        assertEquals(0, fake.calls.count { it.op == "session/delete" }, "未经确认不得产生删除回调")
        assertFalse(s.sessions.isEmpty())
    }

    @Test
    fun newGroupMenuItemOpensGroupSheetWithWhitelistedDefaults() = runBlocking {
        val s = state()
        s.onMenuItem("s-2", SessionMenuItem.NewGroup)
        val sheet = s.editor as? MetaEditor.GroupSheet
        assertTrue(sheet != null && sheet.isNew)
        assertTrue(MetaIcons[sheet.draftIcon] != null, "新建表单默认图标必须在白名单内")
        assertTrue(MetaColors.isValid(sheet.draftColor), "新建表单默认颜色必须是合法 #RRGGBB")
    }

    @Test
    fun renameTrimsAndCommitsOnce_blankIsRejectedWithoutCallback() = runBlocking {
        val fake = FakeMetaActions()
        val s = state(fake)
        s.onMenuItem("s-2", SessionMenuItem.Rename)
        // 空白名：留在弹层 + 错误文案，零回调。
        s.commitRename("   ")
        val rejected = s.editor
        assertTrue(rejected is MetaEditor.RenameSession && rejected.error == MetaCopy.TITLE_EMPTY_ERROR)
        assertEquals(0, fake.calls.count { it.op == "session/rename" })
        // 正常提交：trim 后生效，本地与 fake（服务端面）一致，弹层关闭。
        s.commitRename("  新标题  ")
        assertEquals("新标题", s.sessions.first { it.id == "s-2" }.title)
        assertEquals("新标题", fake.sessions.value.first { it.id == "s-2" }.title)
        assertNull(s.editor)
        assertEquals(1, fake.calls.count { it.op == "session/rename" })
    }

    @Test
    fun moveToGroupIncludesUngroupedNullTarget() = runBlocking {
        val fake = FakeMetaActions()
        val s = state(fake)
        s.onMenuItem("s-4", SessionMenuItem.MoveToGroup) // s-4 种子即未分组
        s.moveToGroup("g-2")
        assertEquals("g-2", s.sessions.first { it.id == "s-4" }.groupId)
        // 再移回未分组：null 语义与服务端 group.assign 一致。
        s.onMenuItem("s-4", SessionMenuItem.MoveToGroup)
        s.moveToGroup(null)
        assertNull(s.sessions.first { it.id == "s-4" }.groupId)
        assertEquals(
            listOf("s-4:g-2", "s-4:null"),
            fake.calls.filter { it.op == "session/group.assign" }.map { it.detail },
        )
    }
}
