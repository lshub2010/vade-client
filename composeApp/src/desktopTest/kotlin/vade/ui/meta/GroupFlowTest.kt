package vade.ui.meta

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import vade.ui.icon.MetaIcons

/**
 * 分组全流程（测试⑥⑦⑧）：
 *  - 新建（默认图标/颜色都在白名单与调色盘内）；
 *  - 图标与颜色**同一次提交**且互不覆盖（⑧）；
 *  - 删除组**不删除会话** —— 组内会话 groupId 置 null（⑥，文案同步钉住）；
 *  - 空分组列表的空态信号（⑦）。
 */
class GroupFlowTest {

    private fun state(fake: FakeMetaActions = FakeMetaActions()) =
        SessionMetaState(fake.sessions.value, fake.groups.value, fake)

    @Test
    fun createGroupAppendsWithServerShapeAndWhitelistedDefaults(): Unit = runBlocking {
        val fake = FakeMetaActions()
        val s = state(fake)
        val before = fake.groups.value.size
        s.openNewGroupSheet()
        s.setGroupDraftName("工作台")
        s.commitGroupSheet()
        assertEquals(before + 1, fake.groups.value.size, "fake（服务端面）新增一档")
        val created = fake.groups.value.last()
        assertEquals("工作台", created.name)
        assertTrue(MetaIcons[created.iconName] != null, "新建组的图标必须在白名单内")
        assertTrue(MetaColors.isValid(created.colorHex), "新建组的颜色必须是 #RRGGBB")
        assertEquals(created.id, s.groups.last().id, "本地也出现新组")
        assertNull(s.editor, "创建成功后表单关闭")
    }

    @Test
    fun createWithBlankNameIsRejectedWithoutCallback(): Unit = runBlocking {
        val fake = FakeMetaActions()
        val s = state(fake)
        s.openNewGroupSheet()
        s.setGroupDraftName("  ")
        s.commitGroupSheet()
        val sheet = s.editor
        assertTrue(sheet is MetaEditor.GroupSheet && sheet.error == MetaCopy.TITLE_EMPTY_ERROR)
        assertEquals(0, fake.calls.count { it.op == "session/group.create" })
    }

    /** 测试⑧：图标与颜色能同时设置，一次提交里两个键都在、且不互相覆盖。 */
    @Test
    fun iconAndColorCanBeSetTogetherWithoutClobbering(): Unit = runBlocking {
        val fake = FakeMetaActions()
        val s = state(fake)
        s.openGroupEditor("g-1") // 种子：briefcase / #3B82F6
        s.setGroupDraftIcon("rocket")
        s.setGroupDraftColor("#ff8800")
        s.commitGroupSheet()

        // 恰好一条 update，detail 同时带 icon 与 color 两个键。
        val updates = fake.calls.filter { it.op == "session/group.update" }
        assertEquals(1, updates.size, "图标与颜色必须合成一次提交")
        val detail = updates.single().detail
        assertTrue(detail.startsWith("g-1:"), "更新必须指向 g-1：$detail")
        assertTrue("icon=rocket" in detail, "同一提交必须带图标：$detail")
        assertTrue("color=#FF8800" in detail, "同一提交必须带归一后的颜色：$detail")

        // 两侧最终态：两个新值都在，且谁也没覆盖谁。
        val local = s.groups.first { it.id == "g-1" }
        assertEquals("rocket", local.iconName)
        assertEquals("#FF8800", local.colorHex)
        val serverFace = fake.groups.value.first { it.id == "g-1" }
        assertEquals("rocket", serverFace.iconName)
        assertEquals("#FF8800", serverFace.colorHex)
        // 名字没动：diff 提交不带 name 键（原样字段不重发）。
        assertFalse("name=" in detail)
        assertNull(s.editor)
    }

    /** 测试⑥：删除分组不删会话 —— 组内会话变未分组，文案钉住「不会删除会话」。 */
    @Test
    fun deletingGroupKeepsSessionsAndUntagsThem(): Unit = runBlocking {
        val fake = FakeMetaActions()
        val s = state(fake)
        val sessionCountBefore = s.sessions.size
        val inGroup = s.sessionsOf("g-1").map { it.id }
        assertTrue(inGroup.isNotEmpty(), "种子必须真的有组内会话，否则本测试空转")

        s.openGroupEditor("g-1")
        s.deleteGroupFromEditor()

        assertFalse(s.groups.any { it.id == "g-1" }, "组本身消失")
        assertEquals(sessionCountBefore, s.sessions.size, "会话一条不少")
        inGroup.forEach { id ->
            assertNull(s.sessions.first { it.id == id }.groupId, "$id 必须变为未分组")
        }
        assertEquals(1, fake.calls.count { it.op == "session/group.delete" })
        // 文案（⑥的钉面）：明确「不会删除会话」。
        assertTrue(MetaCopy.GROUP_DELETE_NOTE.contains("不会删除会话"))
        assertTrue(MetaCopy.GROUP_DELETE_NOTE.contains("未分组"))
    }

    /** 测试⑦：空分组列表 → hasNoGroups 信号 + 空态文案 + 全部会话落未分组面。 */
    @Test
    fun emptyGroupListExposesEmptyStateAndUngroupedFacet() {
        val fake = FakeMetaActions(seedGroups = emptyList())
        val s = SessionMetaState(fake.sessions.value, emptyList(), fake)
        assertTrue(s.hasNoGroups, "分组为空必须给出空态信号")
        assertEquals(fake.sessions.value.size, s.ungroupedSessions.size, "全部会话都在未分组面")
        assertTrue(MetaCopy.GROUPS_EMPTY_TITLE.isNotEmpty())
        assertTrue(MetaCopy.GROUPS_EMPTY_BODY.contains("新建分组"), "空态必须指向唯一主行动：新建分组")
    }

    @Test
    fun nonWhitelistedDraftIconIsIgnored(): Unit = runBlocking {
        val fake = FakeMetaActions()
        val s = state(fake)
        s.openGroupEditor("g-1")
        s.setGroupDraftIcon("lightsaber") // 白名单外：静默忽略
        val sheet = s.editor
        assertTrue(sheet is MetaEditor.GroupSheet && sheet.draftIcon == "briefcase", "白名单外图标不得进入草稿")
    }
}
