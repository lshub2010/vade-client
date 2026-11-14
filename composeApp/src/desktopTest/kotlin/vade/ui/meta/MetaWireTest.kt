package vade.ui.meta

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import vade.ui.data.VadeErrorCategory
import vade.ui.protocol.FakeTransport
import vade.ui.protocol.FakeTransportFactory
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeClient
import vade.ui.protocol.errorFrame
import vade.ui.protocol.idOf
import vade.ui.protocol.requestOf
import vade.ui.protocol.responseFrame
import vade.ui.protocol.testClient
import vade.ui.protocol.waitUntil

/**
 * g-meta-wire：`SessionMetaState.forReal(...)` + 真适配器 [vade.ui.data.MetaActionsReal]
 * 在 [FakeTransport] 上跑**真帧** —— 每个断言都对着客户端真正发出去的 JSON 或真正收到的
 * 错误帧，没有一条是内存假实现的自证。
 *
 * 覆盖：接线一行化、九个写动作的成功/失败、乐观回滚、分类文案、列表失败不是空表且可重试、
 * 删组不删会话、显式 null 缺口被禁用且零帧。
 */
class MetaWireTest {

    // --- 夹具 -------------------------------------------------------------------------

    private val seedSessions = listOf(
        MetaSession("s-1", "旧标题", preview = "p", updatedLabel = "刚刚", pinned = false, groupId = "g-1"),
        MetaSession("s-2", "第二条", groupId = null),
    )

    private val seedGroups = listOf(
        MetaGroup("g-1", "项目", "briefcase", "#3B82F6"),
        MetaGroup("g-2", "灵感", "bulb", "#8B5CF6"),
    )

    /** 一条已连接的 FakeTransport 会话 + 由工厂接出来的状态机。 */
    private inner class Wire {
        val factory = FakeTransportFactory()
        lateinit var client: VadeClient
        lateinit var transport: FakeTransport
        lateinit var state: SessionMetaState

        suspend fun connect() {
            client = testClient(factory)
            client.connect()
            assertTrue(client.awaitConnected())
            transport = factory.transports[0]
            state = SessionMetaState.forReal(
                client = client,
                sessionId = "s-1",
                sessions = seedSessions,
                groups = seedGroups,
            )
        }

        fun request(index: Int): Pair<String, String> = requestOf(transport.record[index])

        fun respond(index: Int, result: String) {
            transport.push(responseFrame(idOf(transport.record[index]), result))
        }

        fun fail(index: Int, code: String, message: String = "core said no") {
            transport.push(errorFrame(idOf(transport.record[index]), code, message))
        }

        suspend fun close() = client.close()
    }

    private fun sessionRow(id: String, title: String, pinnedAt: Long? = null, archivedAt: Long? = null): String =
        """{"session_id":"$id","created_at":1700000000000,"updated_at":1700000000000,"title":"$title",""" +
            """"icon":null,"color":null,"group_id":null,""" +
            """"pinned_at":${pinnedAt?.toString() ?: "null"},""" +
            """"archived_at":${archivedAt?.toString() ?: "null"},"unread":0,"state":"open"}"""

    private fun groupRow(
        id: String,
        name: String,
        icon: String?,
        color: String?,
        sortOrder: Int = 0,
        sessionCount: Int = 0,
    ): String {
        val iconJson = icon?.let { "\"$it\"" } ?: "null"
        val colorJson = color?.let { "\"$it\"" } ?: "null"
        return """{"group_id":"$id","name":"$name","icon":$iconJson,"color":$colorJson,""" +
            """"sort_order":$sortOrder,"created_at":1700000000000,"session_count":$sessionCount}"""
    }

    // --- 接线 -------------------------------------------------------------------------

    @Test
    fun forRealSendsTheRenameFrameAndUpdatesTheRow() = runBlocking {
        val wire = Wire()
        wire.connect()
        val state = wire.state
        assertEquals("s-1", state.focusSessionId, "工厂把焦点会话记在状态上")

        state.onMenuItem("s-1", SessionMenuItem.Rename)
        assertTrue(state.editor is MetaEditor.RenameSession)

        val call = async(Dispatchers.Default) { state.commitRename("新标题") }
        waitUntil { wire.transport.record.size >= 1 }
        assertEquals("session/rename", wire.request(0).first)
        assertEquals("""{"session_id":"s-1","title":"新标题"}""", wire.request(0).second)
        wire.respond(0, """{"ok":true,"session":${sessionRow("s-1", "新标题")}}""")
        call.await()

        assertEquals("新标题", state.sessions.first { it.id == "s-1" }.title)
        assertNull(state.editor, "成功后弹层关闭")
        assertNull(state.actionError)
        wire.close()
    }

    // --- 列表读：成功 / 失败 / 重试 -----------------------------------------------------

    @Test
    fun loadGroupsMapsServerRowsInServerOrder() = runBlocking {
        val wire = Wire()
        wire.connect()
        // 本地先放一条「过期」的组，成功拉取后必须被服务端快照整体覆盖。
        wire.state.applyProjection(seedSessions, listOf(MetaGroup("g-old", "过期", "folder", "#111111")))

        val load = async(Dispatchers.Default) { wire.state.loadGroups() }
        waitUntil { wire.transport.record.size >= 1 }
        assertEquals("session/group.list", wire.request(0).first)
        assertEquals("{}", wire.request(0).second)
        wire.respond(
            0,
            """{"groups":[${groupRow("g-2", "灵感", "bulb", "#8B5CF6", 1, 0)},${groupRow("g-1", "项目", "briefcase", "#3B82F6", 0, 2)}]}""",
        )
        val ok = load.await()
        assertTrue(ok, "loadGroups 失败：${wire.state.groupsLoadError?.message}")

        assertEquals(listOf("g-2", "g-1"), wire.state.groups.map { it.id }, "服务端顺序原样保留")
        assertEquals(MetaGroupsLoad.Loaded, wire.state.groupsLoad)
        assertNull(wire.state.groupsLoadError)
        assertFalse(wire.state.canRetryGroupLoad)
        assertNull(wire.state.actionError)
        wire.close()
    }

    @Test
    fun loadGroupsFailureIsAFailedStateWithRetryEntry_notAnEmptyList() = runBlocking {
        val wire = Wire()
        wire.connect()

        val load = async(Dispatchers.Default) { wire.state.loadGroups() }
        waitUntil { wire.transport.record.size >= 1 }
        wire.fail(0, VadeErrorCodes.TIMEOUT, "core timed out")
        assertFalse(load.await(), "失败必须返回 false")

        val failed = wire.state.groupsLoad as? MetaGroupsLoad.Failed
            ?: fail("失败落在 Failed，不是 Loaded")
        assertEquals(VadeErrorCategory.Retryable, failed.error.category)
        assertTrue(failed.error.message.contains("加载分组失败"), failed.error.message)
        assertTrue(failed.error.message.contains("可重试"), "分类标签必须可展示：${failed.error.message}")
        assertEquals(VadeErrorCodes.TIMEOUT, failed.error.code)

        // 关键：失败不是空表 —— 旧快照还在，空态信号必须关掉，重试入口必须开。
        assertEquals(seedGroups, wire.state.groups, "读失败不得清空/替换分组列表")
        assertFalse(wire.state.hasNoGroups, "失败时不得渲染空态卡（那不是服务端事实）")
        assertTrue(wire.state.canRetryGroupLoad, "失败必须给重试入口")
        assertEquals(failed.error, wire.state.actionError)
        wire.close()
    }

    @Test
    fun retryAfterAFailedLoadSucceedsAndClearsTheFailure() = runBlocking {
        val wire = Wire()
        wire.connect()

        val first = async(Dispatchers.Default) { wire.state.loadGroups() }
        waitUntil { wire.transport.record.size >= 1 }
        wire.fail(0, VadeErrorCodes.UNAVAILABLE, "socket gone")
        assertFalse(first.await())
        assertTrue(wire.state.canRetryGroupLoad)

        val retry = async(Dispatchers.Default) { wire.state.retryLoadGroups() }
        waitUntil { wire.transport.record.size >= 2 }
        assertEquals("session/group.list", wire.request(1).first, "重试真的再发一次 RPC")
        wire.respond(1, """{"groups":[${groupRow("g-9", "新组", "rocket", "#FF8800")}]}""")
        val retried = retry.await()
        assertTrue(retried, "重试失败：${wire.state.groupsLoadError?.message}")

        assertEquals(listOf("g-9"), wire.state.groups.map { it.id })
        assertEquals(MetaGroupsLoad.Loaded, wire.state.groupsLoad)
        assertFalse(wire.state.canRetryGroupLoad)
        assertNull(wire.state.actionError, "重试成功后旧错误清掉")
        wire.close()
    }

    // --- 重命名：成功 / 失败回滚 ---------------------------------------------------------

    @Test
    fun renameFailureRollsBackTheTitleAndKeepsTheSheetWithACategorizedError() = runBlocking {
        val wire = Wire()
        wire.connect()
        val state = wire.state
        state.onMenuItem("s-1", SessionMenuItem.Rename)

        val call = async(Dispatchers.Default) { state.commitRename("新标题") }
        waitUntil { wire.transport.record.size >= 1 }
        wire.fail(0, VadeErrorCodes.NOT_FOUND, "no such session")
        call.await()

        assertEquals("旧标题", state.sessions.first { it.id == "s-1" }.title, "乐观标题必须回滚")
        val editor = state.editor as? MetaEditor.RenameSession ?: fail("失败后弹层不关（用户要看到原因）")
        val message = editor.error ?: fail("失败后弹层必须带可展示的错误文案")
        assertTrue(message.contains("需要重连"), "错误按分类展示：$message")
        assertEquals(VadeErrorCategory.NeedsReconnect, state.actionError?.category)
        assertEquals(MetaAction.Rename, state.actionError?.action)
        wire.close()
    }

    // --- 置顶 / 归档 -------------------------------------------------------------------

    @Test
    fun pinSendsTheExactFrameAndKeepsTheOptimisticValue() = runBlocking {
        val wire = Wire()
        wire.connect()
        val call = async(Dispatchers.Default) { wire.state.togglePin("s-1") }
        waitUntil { wire.transport.record.size >= 1 }
        assertEquals("session/pin", wire.request(0).first)
        assertEquals("""{"session_id":"s-1","pinned":true}""", wire.request(0).second)
        wire.respond(0, """{"ok":true,"pinned_at":1700000000000}""")
        call.await()

        assertTrue(wire.state.sessions.first { it.id == "s-1" }.pinned)
        assertNull(wire.state.actionError)
        wire.close()
    }

    @Test
    fun pinFailureRollsBackToThePreviousValue() = runBlocking {
        val wire = Wire()
        wire.connect()
        val call = async(Dispatchers.Default) { wire.state.togglePin("s-1") }
        waitUntil { wire.transport.record.size >= 1 }
        wire.fail(0, VadeErrorCodes.TIMEOUT)
        call.await()

        assertFalse(wire.state.sessions.first { it.id == "s-1" }.pinned, "pin 失败必须回到旧值")
        assertEquals(VadeErrorCategory.Retryable, wire.state.actionError?.category)
        assertTrue(wire.state.actionError!!.retryable)
        wire.close()
    }

    @Test
    fun archiveSendsTheExactFrameAndUpdatesTheRow() = runBlocking {
        val wire = Wire()
        wire.connect()
        val call = async(Dispatchers.Default) { wire.state.toggleArchive("s-2") }
        waitUntil { wire.transport.record.size >= 1 }
        assertEquals("session/archive", wire.request(0).first)
        assertEquals("""{"session_id":"s-2","archived":true}""", wire.request(0).second)
        wire.respond(0, """{"ok":true,"archived_at":1700000000000}""")
        call.await()

        assertTrue(wire.state.sessions.first { it.id == "s-2" }.archived)
        wire.close()
    }

    @Test
    fun archiveFailureRollsBackToThePreviousValue() = runBlocking {
        val wire = Wire()
        wire.connect()
        val call = async(Dispatchers.Default) { wire.state.toggleArchive("s-2") }
        waitUntil { wire.transport.record.size >= 1 }
        wire.fail(0, VadeErrorCodes.INTERNAL, "core exploded")
        call.await()

        assertFalse(wire.state.sessions.first { it.id == "s-2" }.archived)
        assertEquals(VadeErrorCategory.NeedsReconnect, wire.state.actionError?.category)
        wire.close()
    }

    // --- 删除会话 ---------------------------------------------------------------------

    @Test
    fun deleteRemovesTheRowOnlyAfterTheCallSucceeds() = runBlocking {
        val wire = Wire()
        wire.connect()
        val state = wire.state
        state.requestDelete("s-1")
        assertTrue(state.editor is MetaEditor.ConfirmDeleteSession)
        assertEquals(2, state.sessions.size, "确认层打开时一行都不能少")

        val call = async(Dispatchers.Default) { state.confirmDelete() }
        waitUntil { wire.transport.record.size >= 1 }
        assertEquals("session/delete", wire.request(0).first)
        assertEquals("""{"session_id":"s-1"}""", wire.request(0).second)
        wire.respond(0, """{"ok":true}""")
        call.await()

        assertFalse(state.sessions.any { it.id == "s-1" }, "成功后从列表移除")
        assertEquals(1, state.sessions.size)
        assertNull(state.editor)
        wire.close()
    }

    @Test
    fun deleteFailureRestoresTheRowAndKeepsTheConfirmSheet() = runBlocking {
        val wire = Wire()
        wire.connect()
        val state = wire.state
        state.requestDelete("s-1")

        val call = async(Dispatchers.Default) { state.confirmDelete() }
        waitUntil { wire.transport.record.size >= 1 }
        wire.fail(0, VadeErrorCodes.INVALID_ARGUMENT, "turn still running")
        call.await()

        assertEquals(2, state.sessions.size, "删除失败那一行必须回到列表")
        assertTrue(state.sessions.any { it.id == "s-1" })
        val editor = state.editor as? MetaEditor.ConfirmDeleteSession ?: fail("确认层保持打开")
        val message = editor.error ?: fail("删除失败必须给出可展示的错误")
        assertTrue(message.contains("需要用户操作"), "分类文案：$message")
        assertEquals(VadeErrorCategory.NeedsUserAction, state.actionError?.category)
        wire.close()
    }

    // --- 分组：移动 / 删除 / 更新 --------------------------------------------------------

    @Test
    fun moveToUngroupedSendsExplicitNullOnTheWire() = runBlocking {
        val wire = Wire()
        wire.connect()
        val state = wire.state
        state.openSessionMenu("s-1")
        state.onMenuItem("s-1", SessionMenuItem.MoveToGroup)

        val call = async(Dispatchers.Default) { state.moveToGroup(null) }
        waitUntil { wire.transport.record.size >= 1 }
        assertEquals("session/group.assign", wire.request(0).first)
        assertEquals("""{"session_id":"s-1","group_id":null}""", wire.request(0).second, "「未分组」= 显式 null")
        wire.respond(0, """{"ok":true,"group_id":null}""")
        call.await()

        assertNull(state.sessions.first { it.id == "s-1" }.groupId)
        wire.close()
    }

    @Test
    fun moveToGroupFailureRollsBackTheMembership() = runBlocking {
        val wire = Wire()
        wire.connect()
        val state = wire.state
        state.onMenuItem("s-1", SessionMenuItem.MoveToGroup)

        val call = async(Dispatchers.Default) { state.moveToGroup("g-2") }
        waitUntil { wire.transport.record.size >= 1 }
        wire.fail(0, VadeErrorCodes.TIMEOUT)
        call.await()

        assertEquals("g-1", state.sessions.first { it.id == "s-1" }.groupId, "归属回滚到旧组")
        val editor = state.editor
        assertTrue(editor is MetaEditor.MoveToGroup && editor.error != null)
        wire.close()
    }

    @Test
    fun deletingAGroupSendsTheFrameAndKeepsEverySession() = runBlocking {
        val wire = Wire()
        wire.connect()
        val state = wire.state
        val before = state.sessions.size
        state.openGroupEditor("g-1")
        assertTrue(state.editor is MetaEditor.GroupSheet)

        val call = async(Dispatchers.Default) { state.deleteGroupFromEditor() }
        waitUntil { wire.transport.record.size >= 1 }
        assertEquals("session/group.delete", wire.request(0).first)
        assertEquals("""{"group_id":"g-1"}""", wire.request(0).second)
        wire.respond(0, """{"ok":true,"unassigned":1}""")
        call.await()

        assertFalse(state.groups.any { it.id == "g-1" }, "组本身消失")
        assertEquals(before, state.sessions.size, "会话一条不少")
        assertNull(state.sessions.first { it.id == "s-1" }.groupId, "组内会话变未分组")
        assertNull(state.editor)
        wire.close()
    }

    @Test
    fun deleteGroupFailureRestoresBothTheGroupAndTheMembership() = runBlocking {
        val wire = Wire()
        wire.connect()
        val state = wire.state
        state.openGroupEditor("g-1")

        val call = async(Dispatchers.Default) { state.deleteGroupFromEditor() }
        waitUntil { wire.transport.record.size >= 1 }
        wire.fail(0, VadeErrorCodes.PERMISSION_DENIED, "read-only")
        call.await()

        assertTrue(state.groups.any { it.id == "g-1" }, "组回来")
        assertEquals("g-1", state.sessions.first { it.id == "s-1" }.groupId, "归属也回来")
        val editor = state.editor
        assertTrue(editor is MetaEditor.GroupSheet && editor.error != null)
        assertEquals(VadeErrorCategory.NeedsUserAction, state.actionError?.category)
        wire.close()
    }

    @Test
    fun updateGroupSendsOnlyChangedKeysAndFailureLeavesTheGroupUntouched() = runBlocking {
        val wire = Wire()
        wire.connect()
        val state = wire.state
        state.openGroupEditor("g-2")
        state.setGroupDraftIcon("rocket")

        val call = async(Dispatchers.Default) { state.commitGroupSheet() }
        waitUntil { wire.transport.record.size >= 1 }
        assertEquals("session/group.update", wire.request(0).first)
        assertEquals(
            """{"group_id":"g-2","icon":"rocket"}""",
            wire.request(0).second,
            "只发改动的键（name/color 缺省 = 保持）",
        )
        wire.fail(0, VadeErrorCodes.UNAVAILABLE, "socket gone")
        call.await()

        assertEquals("bulb", state.groups.first { it.id == "g-2" }.iconName, "保存失败不得改本地组")
        val editor = state.editor
        assertTrue(editor is MetaEditor.GroupSheet && editor.error != null, "表单留在原地带错误")
        assertEquals(VadeErrorCategory.NeedsReconnect, state.actionError?.category)
        wire.close()
    }

    @Test
    fun createGroupFailureDoesNotFabricateALocalRow() = runBlocking {
        val wire = Wire()
        wire.connect()
        val state = wire.state
        val before = state.groups
        state.openNewGroupSheet()
        state.setGroupDraftName("工作台")

        val call = async(Dispatchers.Default) { state.commitGroupSheet() }
        waitUntil { wire.transport.record.size >= 1 }
        assertEquals("session/group.create", wire.request(0).first)
        wire.fail(0, VadeErrorCodes.INVALID_ARGUMENT, "name reserved")
        call.await()

        assertEquals(before, state.groups, "创建失败不得先塞一条假组")
        val editor = state.editor
        assertTrue(editor is MetaEditor.GroupSheet && editor.error != null)
        wire.close()
    }

    // --- 三态 update：清除（显式 null）vs 保持（键缺省） --------------------------------

    /**
     * 关键两帧：清除 = `"icon": null`；不传 = 这个键根本不在 params 里。
     * 两帧必须不同 —— 这正是「清除」和「保持不变」在线上唯一的区别。
     */
    @Test
    fun clearAndKeepProduceTwoDifferentFrames() = runBlocking {
        val wire = Wire()
        wire.connect()
        val state = wire.state
        assertTrue(state.canClearGroupIconColor, "真适配器提供三态写能力，UI 不该禁用清除")

        // ① 只改颜色：icon 没有意图 → 键不出现。
        state.openGroupEditor("g-2")
        state.setGroupDraftColor("#ff8800")
        val keepCall = async(Dispatchers.Default) { state.commitGroupSheet() }
        waitUntil { wire.transport.record.size >= 1 }
        val keepParams = wire.request(0).second
        assertEquals("session/group.update", wire.request(0).first)
        assertEquals("""{"group_id":"g-2","color":"#FF8800"}""", keepParams)
        assertFalse("\"icon\"" in keepParams, "保持不动的字段绝不能出现在 params 里：$keepParams")
        wire.respond(0, """{"group":${groupRow("g-2", "灵感", "bulb", "#FF8800")}}""")
        keepCall.await()
        assertEquals("#FF8800", state.groups.first { it.id == "g-2" }.colorHex)
        assertEquals("bulb", state.groups.first { it.id == "g-2" }.iconName)

        // ② 清除图标：键存在且值为 null。
        state.openGroupEditor("g-2")
        assertTrue(state.clearGroupDraftIcon(), "编辑态允许清除图标")
        val clearCall = async(Dispatchers.Default) { state.commitGroupSheet() }
        waitUntil { wire.transport.record.size >= 2 }
        val clearParams = wire.request(1).second
        assertEquals("""{"group_id":"g-2","icon":null}""", clearParams)
        assertTrue("\"icon\":null" in clearParams, "清除必须是显式 JSON null：$clearParams")
        wire.respond(1, """{"group":${groupRow("g-2", "灵感", null, "#FF8800")}}""")
        clearCall.await()

        assertTrue(keepParams != clearParams, "两帧必须不同（保持 vs 清除）")
        assertNull(state.groups.first { it.id == "g-2" }.iconName, "清除后本地投影就是 null")
        assertNull(state.editor)
        wire.close()
    }

    /** 图标与颜色可以在同一条 update 里一起清除（两个 null 键），不是两条 RPC。 */
    @Test
    fun clearingBothFieldsIsOneFrameWithTwoNullKeys() = runBlocking {
        val wire = Wire()
        wire.connect()
        val state = wire.state
        state.openGroupEditor("g-1")
        assertTrue(state.clearGroupDraftIcon())
        assertTrue(state.clearGroupDraftColor())

        val call = async(Dispatchers.Default) { state.commitGroupSheet() }
        waitUntil { wire.transport.record.size >= 1 }
        assertEquals("session/group.update", wire.request(0).first)
        assertEquals("""{"group_id":"g-1","icon":null,"color":null}""", wire.request(0).second)
        wire.respond(0, """{"group":${groupRow("g-1", "项目", null, null)}}""")
        call.await()

        val group = state.groups.first { it.id == "g-1" }
        assertNull(group.iconName)
        assertNull(group.colorHex)
        wire.close()
    }

    /**
     * 清除之后的 group.list 仍能解析：三态适配器的投影把 null 原样保留，
     * 而不是像两态那份实现一样把缺失的 icon/color 当协议违约。
     */
    @Test
    fun reloadAfterClearKeepsTheNullProjection() = runBlocking {
        val wire = Wire()
        wire.connect()
        val state = wire.state
        val load = async(Dispatchers.Default) { state.loadGroups() }
        waitUntil { wire.transport.record.size >= 1 }
        wire.respond(
            0,
            """{"groups":[${groupRow("g-1", "项目", null, null, 0)},${groupRow("g-2", "灵感", "bulb", "#8B5CF6", 1)}]}""",
        )
        val reloaded = load.await()
        assertTrue(reloaded, "清除过字段的分组行必须能读回来：${wire.state.groupsLoadError?.message}")

        assertEquals(MetaGroup("g-1", "项目", null, null), state.groups.first { it.id == "g-1" })
        assertEquals("bulb", state.groups.first { it.id == "g-2" }.iconName)
        wire.close()
    }

    /** 清除在新建态不可用：`group.create` 的两个字段是必填（UI 不显示清除按钮）。 */
    @Test
    fun clearingIsRefusedWhileCreating() = runBlocking {
        val wire = Wire()
        wire.connect()
        val state = wire.state
        state.openNewGroupSheet()
        assertFalse(state.clearGroupDraftIcon(), "新建态不能清空图标")
        assertFalse(state.clearGroupDraftColor(), "新建态不能清空颜色")
        assertTrue(state.groupClearHint.isNotBlank())
        assertEquals(0, wire.transport.record.size, "被拒的清除不能产生任何帧")
        wire.close()
    }

    /** 只实现两态的外来数据层：能力为 false，UI 不显示清除；强行清除会被明确拒收。 */
    @Test
    fun twoStateImplementationAdvertisesNoClearCapability() = runBlocking {
        val twoState = object : SessionMetaActions {
            override suspend fun renameSession(sessionId: String, title: String) = Unit
            override suspend fun setSessionPinned(sessionId: String, pinned: Boolean) = Unit
            override suspend fun setSessionArchived(sessionId: String, archived: Boolean) = Unit
            override suspend fun deleteSession(sessionId: String) = Unit
            override suspend fun assignSessionToGroup(sessionId: String, groupId: String?) = Unit
            override suspend fun listGroups(): List<MetaGroup> = seedGroups
            override suspend fun createGroup(name: String, iconName: String, colorHex: String): MetaGroup =
                MetaGroup("g-new", name, iconName, colorHex)
            override suspend fun updateGroup(groupId: String, name: String?, iconName: String?, colorHex: String?) = Unit
            override suspend fun deleteGroup(groupId: String) = Unit
        }
        val state = SessionMetaState(seedSessions, seedGroups, twoState)
        assertFalse(state.canClearGroupIconColor, "拿不到三态能力就不该宣称能清除")
        state.openGroupEditor("g-1")
        assertFalse(state.clearGroupDraftIcon())
        assertTrue(MetaCopy.GROUP_CLEAR_UNSUPPORTED.isNotBlank())
    }

    // --- 分类文案的另外两个桶 ------------------------------------------------------------

    @Test
    fun errorBannerIsDismissableWithoutLosingTheUnderlyingFailure() = runBlocking {
        val wire = Wire()
        wire.connect()
        val state = wire.state
        val call = async(Dispatchers.Default) { state.togglePin("s-1") }
        waitUntil { wire.transport.record.size >= 1 }
        wire.fail(0, VadeErrorCodes.PROTOCOL_MISMATCH, "bad frame")
        call.await()

        assertEquals(VadeErrorCategory.Fatal, state.actionError?.category, "PROTOCOL_MISMATCH 是致命桶")
        assertFalse(state.actionError!!.retryable)
        state.dismissActionError()
        assertNull(state.actionError, "用户可关掉横条")
        assertFalse(state.sessions.first { it.id == "s-1" }.pinned, "关掉横条不改回滚结果")
        wire.close()
    }
}
