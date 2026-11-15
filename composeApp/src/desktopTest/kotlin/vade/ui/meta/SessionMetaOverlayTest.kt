package vade.ui.meta

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import vade.ui.data.SessionGroup
import vade.ui.data.SessionSummary
import vade.ui.data.TimeBucket
import vade.ui.data.sessionGroups

/**
 * B2（s-meta-host）· 挂载面 `SessionMetaOverlay` 的接线语义（不含 UI，UI 流程在
 * `SessionMetaLongPressUiTest`）。全部走真 `SessionMetaState` + `FakeMetaActions`
 * （或注入失败的变体），不 mock 状态机：
 *
 *  1. 长按 → 菜单 → 重命名：适配器**恰一次** `session/rename` 且参数正确（任务测试①的状态层面）；
 *  2. 失败注入：标题回滚 + `actionError` 可展示（可见提示的状态层面）+ 不触发列表刷新；
 *  3. 乐观删除：墓碑挡住同步复活，权威确认后清除（任务测试③的挂载层前提）；
 *  4. 乐观归档：行立即消失；失败回滚行回来；
 *  5. 删组不删会话（经挂载面路径走一遍，任务测试③）；
 *  6. 草稿行不进菜单（无服务端实体，不许发假 RPC）；
 *  7. 权威同步只采纳「变更」，事件刷新不 clobber 在飞的乐观值。
 */
class SessionMetaOverlayTest {

    private companion object {
        /** 固定时钟：取消置顶重算桶时结果确定。 */
        const val FIXED_NOW = 1_700_000_000_000L
    }

    // ------------------------------------------------------------------ 测试数据

    /** 权威行（SessionSummary 面）：s-2 在 g-1，s-4 未分组。 */
    private fun rows(): List<SessionSummary> = listOf(
        SessionSummary(
            id = "s-2",
            title = "客户端 UI 拆棒",
            preview = "",
            bucket = TimeBucket.Today,
            updatedAt = FIXED_NOW - 60_000,
            updatedLabel = "1 分钟前",
            groupId = "g-1",
        ),
        SessionSummary(
            id = "s-4",
            title = "随手记",
            preview = "",
            bucket = TimeBucket.Today,
            updatedAt = FIXED_NOW - 120_000,
            updatedLabel = "2 分钟前",
            groupId = null,
        ),
    )

    /** 只有 rename 抛错，其余照常委托（FakeMetaActions 是 final：接口委托，不继承）。 */
    private class ExplodingRenameFake(
        val inner: FakeMetaActions = FakeMetaActions(),
    ) : SessionMetaActions by inner {
        override suspend fun renameSession(sessionId: String, title: String) {
            throw IllegalStateException("core 断开（注入）")
        }
    }

    /** 只有 archive 抛错，其余照常委托。 */
    private class ExplodingArchiveFake(
        val inner: FakeMetaActions = FakeMetaActions(),
    ) : SessionMetaActions by inner {
        override suspend fun setSessionArchived(sessionId: String, archived: Boolean) {
            throw IllegalStateException("core 断开（注入）")
        }
    }

    private fun newOverlay(
        actions: SessionMetaActions,
        refreshes: MutableList<Int> = mutableListOf(),
        seedGroups: List<MetaGroup> = FakeMetaActions.defaultGroups,
    ): SessionMetaOverlay = SessionMetaOverlay(
        // 组表直接给服务端种子（生产路径等价：forAdapters → loadGroups() → 服务端回执）。
        meta = SessionMetaState(emptyList(), seedGroups, actions),
        allowGroupLoad = false,
        onRefreshList = { refreshes += 1 },
        nowMs = { FIXED_NOW },
    )

    private fun projected(overlay: SessionMetaOverlay, rows: List<SessionSummary>): List<SessionGroup> =
        overlay.projectGroups(sessionGroups(rows))

    private fun titles(groups: List<SessionGroup>): List<String> =
        groups.flatMap { it.sessions }.map { it.title }

    // ------------------------------------------------- 1 · 长按 → 重命名恰一次

    @Test
    fun renameThroughTheMenuHitsTheAdapterExactlyOnce(): Unit = runBlocking {
        val fake = FakeMetaActions()
        val refreshes = mutableListOf<Int>()
        val overlay = newOverlay(fake, refreshes)
        val source = rows()
        overlay.syncFrom(source)
        assertEquals(source.size, overlay.meta.sessions.size, "权威行必须全部进入状态机")

        overlay.openSessionMenu(source.last()) // s-4
        assertEquals("s-4", overlay.meta.menuFor, "长按必须打开该行的菜单")

        overlay.dispatch("s-4", SessionMenuItem.Rename)
        assertTrue(overlay.meta.editor is MetaEditor.RenameSession, "重命名必须落在弹层")
        assertEquals(0, fake.calls.count { it.op == "session/rename" }, "打开弹层不算动作")

        overlay.commitRename("  新的随手记  ")
        assertEquals(
            listOf("session/rename:s-4:新的随手记"),
            fake.calls.filter { it.op == "session/rename" }.map { "${it.op}:${it.detail}" },
            "适配器恰一次、trim 后的参数正确",
        )
        assertEquals("新的随手记", overlay.meta.sessions.first { it.id == "s-4" }.title)
        assertEquals(
            listOf("客户端 UI 拆棒", "新的随手记"),
            titles(projected(overlay, source)).sorted(),
            "乐观标题必须出现在投影里（列表看得见）",
        )
        assertEquals(1, refreshes.size, "成功后恰触发一次权威列表刷新")
        assertNull(overlay.meta.actionError, "成功不留错误")
    }

    // ------------------------------------------- 2 · 失败注入：回滚 + 可见提示

    @Test
    fun failedRenameRollsBackTheTitleAndReports(): Unit = runBlocking {
        val fake = ExplodingRenameFake()
        val refreshes = mutableListOf<Int>()
        val overlay = newOverlay(fake, refreshes)
        val source = rows()
        overlay.syncFrom(source)
        overlay.openSessionMenu(source.last())
        overlay.dispatch("s-4", SessionMenuItem.Rename)
        overlay.commitRename("不该生效的名字")

        assertTrue(overlay.meta.actionError != null, "失败必须有可展示错误（绝不静默）")
        assertTrue(
            overlay.meta.actionError!!.message.startsWith("重命名失败"),
            "失败文案必须带动作名：${overlay.meta.actionError!!.message}",
        )
        assertTrue(
            overlay.meta.actionError!!.message.contains("core 断开（注入）"),
            "失败文案必须带真实原因：${overlay.meta.actionError!!.message}",
        )
        assertEquals("随手记", overlay.meta.sessions.first { it.id == "s-4" }.title, "标题必须回滚到原位")
        assertEquals(
            listOf("客户端 UI 拆棒", "随手记"),
            titles(projected(overlay, source)).sorted(),
            "投影必须跟着回滚（列表回到原样）",
        )
        assertTrue(overlay.meta.editor is MetaEditor.RenameSession, "失败后弹层留在原地带原因")
        assertEquals(0, refreshes.size, "失败不得触发权威刷新（什么都没改成）")
    }

    // ------------------------------- 3 · 乐观删除：墓碑挡复活，权威确认后清除

    @Test
    fun optimisticDeleteIsTombstonedUntilTheAuthorityConfirms(): Unit = runBlocking {
        val fake = FakeMetaActions()
        val refreshes = mutableListOf<Int>()
        val overlay = newOverlay(fake, refreshes)
        val source = rows()
        overlay.syncFrom(source)
        overlay.openSessionMenu(source.last())
        overlay.dispatch("s-4", SessionMenuItem.Delete)
        assertTrue(overlay.meta.editor is MetaEditor.ConfirmDeleteSession, "删除必经确认")
        assertEquals(0, fake.calls.count { it.op == "session/delete" }, "未确认不产生删除回调")

        overlay.confirmDelete("s-4")
        assertEquals(1, fake.calls.count { it.op == "session/delete" })
        assertNull(overlay.meta.sessions.firstOrNull { it.id == "s-4" }, "状态机里已经没有这一行")
        // 权威列表还没刷新（仍列出 s-4）：投影不许把已删的行画回来。
        assertTrue(
            titles(projected(overlay, source)).none { it == "随手记" },
            "乐观删除后行必须立即消失，不等权威刷新",
        )
        // 同步跑在权威仍旧值上：墓碑挡住「加回来」。
        overlay.syncFrom(source)
        assertNull(overlay.meta.sessions.firstOrNull { it.id == "s-4" }, "墓碑期间同步不许复活已删行")
        // 权威刷新确认了删除（不再列出）：墓碑清除；此刻若权威又列出（异常回摆），行才会回来。
        overlay.syncFrom(source.filterNot { it.id == "s-4" })
        assertTrue(titles(projected(overlay, emptyList())).isEmpty())
        assertEquals(1, refreshes.size, "成功后恰触发一次权威列表刷新")
    }

    // --------------------------- 4 · 乐观归档：行立即消失；失败回滚行回来

    @Test
    fun optimisticArchiveHidesTheRowWhenItSucceeds() = runBlocking {
        val fake = FakeMetaActions()
        val refreshes = mutableListOf<Int>()
        val overlay = newOverlay(fake, refreshes)
        val source = rows()
        overlay.syncFrom(source)
        overlay.openSessionMenu(source.last())

        overlay.dispatch("s-4", SessionMenuItem.ToggleArchive)
        assertTrue(overlay.meta.sessions.first { it.id == "s-4" }.archived, "归档翻转生效")
        assertTrue(
            titles(projected(overlay, source)).none { it == "随手记" },
            "归档的行立即消失（与真源 session/list 的归档过滤一致），不等权威刷新",
        )
        assertNull(overlay.meta.actionError)
        assertEquals(1, refreshes.size, "成功后恰触发一次权威列表刷新")
    }

    @Test
    fun failingArchiveRollsBackAndTheRowStays(): Unit = runBlocking {
        val fake = ExplodingArchiveFake()
        val refreshes = mutableListOf<Int>()
        val overlay = newOverlay(fake, refreshes)
        val source = rows()
        overlay.syncFrom(source)
        overlay.openSessionMenu(source.last())

        // dispatch 同步跑完整写路径：乐观翻转 → RPC 抛 → 回滚，返回时已滚回。
        overlay.dispatch("s-4", SessionMenuItem.ToggleArchive)
        assertFalse(overlay.meta.sessions.first { it.id == "s-4" }.archived, "乐观值必须滚回")
        assertTrue(
            titles(projected(overlay, source)).contains("随手记"),
            "回滚后行必须回到列表原位（用户没有丢数据）",
        )
        assertTrue(overlay.meta.actionError != null, "注入失败必须可展示")
        assertTrue(
            overlay.meta.actionError!!.message.startsWith("归档失败"),
            "失败文案必须带动作名：${overlay.meta.actionError!!.message}",
        )
        assertEquals(0, refreshes.size, "失败不触发刷新")
    }

    // ----------------------------------- 5 · 删组不删会话（经挂载面的完整路径）

    @Test
    fun deletingAGroupThroughTheOverlayKeepsEverySession(): Unit = runBlocking {
        val fake = FakeMetaActions()
        val overlay = newOverlay(fake)
        val source = rows()
        overlay.syncFrom(source)
        overlay.openSessionMenu(source.first()) // s-2，在 g-1
        overlay.dispatch("s-2", SessionMenuItem.MoveToGroup)
        assertTrue(overlay.meta.editor is MetaEditor.MoveToGroup)

        // 表单里的「编辑分组」入口 → 分组编辑器 → 删除分组（删组不删会话）。
        overlay.meta.openGroupEditor("g-1")
        overlay.deleteGroupFromEditor()

        assertFalse(overlay.meta.groups.any { it.id == "g-1" }, "组消失")
        assertEquals(source.size, overlay.meta.sessions.size, "会话一条不少")
        assertNull(overlay.meta.sessions.first { it.id == "s-2" }.groupId, "组内会话变未分组")
        val projectedRows = projected(overlay, source).flatMap { it.sessions }
        assertNull(
            projectedRows.first { it.id == "s-2" }.groupId,
            "投影里的行也必须显示为未分组（列表与状态机一致）",
        )
        assertEquals(1, fake.calls.count { it.op == "session/group.delete" })
        assertEquals(1, fake.calls.count { it.op == "session/group.delete" }, "恰一次删组回调")
    }

    // ----------------------------------------- 6 · 草稿行不进菜单、不发假 RPC

    @Test
    fun draftRowsNeverOpenTheMenu(): Unit = runBlocking {
        val fake = FakeMetaActions()
        val overlay = newOverlay(fake)
        val draft = SessionSummary(
            id = "draft-1",
            title = "本地草稿",
            preview = "",
            bucket = TimeBucket.Today,
            updatedAt = 1L,
            updatedLabel = "刚刚",
            isDraft = true,
        )
        overlay.openSessionMenu(draft)
        assertNull(overlay.meta.menuFor, "草稿行没有服务端实体，长按必须是 no-op")
        overlay.syncFrom(listOf(draft))
        assertTrue(overlay.meta.sessions.isEmpty(), "草稿也不许进状态机（投影原样画草稿）")
        assertEquals(0, fake.calls.size, "全程零适配器回调")
    }

    // ------------------- 7 · 权威同步只采纳变更：事件刷新不打回在飞的乐观值

    @Test
    fun authoritySyncAdoptsChangesButNeverClobbersOptimisticValues(): Unit = runBlocking {
        val fake = FakeMetaActions()
        val overlay = newOverlay(fake)
        val source = rows()
        overlay.syncFrom(source)

        // 乐观重命名（用户刚点了保存，RPC 在飞；权威还是旧值）。
        overlay.openSessionMenu(source.last())
        overlay.dispatch("s-4", SessionMenuItem.Rename)
        overlay.commitRename("在飞的新名字")
        assertEquals("在飞的新名字", overlay.meta.sessions.first { it.id == "s-4" }.title)

        // 事件刷新落在窗口里：权威没变 → 采纳规则不许把乐观值打回去。
        overlay.syncFrom(source)
        assertEquals(
            "在飞的新名字",
            overlay.meta.sessions.first { it.id == "s-4" }.title,
            "权威未变时同步必须保留乐观值（不闪烁）",
        )

        // RPC 成功后的权威刷新带来确认值：采纳（值一致，无感收敛）。
        val confirmed = source.map { if (it.id == "s-4") it.copy(title = "在飞的新名字") else it }
        overlay.syncFrom(confirmed)
        assertEquals("在飞的新名字", overlay.meta.sessions.first { it.id == "s-4" }.title)

        // 别的客户端改了名字（权威出现第三方值）：采纳外部变更。
        val external = confirmed.map { if (it.id == "s-4") it.copy(title = "别处改的名字") else it }
        overlay.syncFrom(external)
        assertEquals(
            "别处改的名字",
            overlay.meta.sessions.first { it.id == "s-4" }.title,
            "权威出现新值（外部写入）必须采纳",
        )
    }

    // ------------------------------------------ 8 · 置顶/取消置顶的时间桶重算

    @Test
    fun unpinningRecomputesTheBucketInsteadOfStayingInPinned(): Unit = runBlocking {
        val fake = FakeMetaActions()
        val overlay = newOverlay(fake)
        val pinnedRow = SessionSummary(
            id = "s-9",
            title = "置顶行",
            preview = "",
            bucket = TimeBucket.Pinned,
            updatedAt = FIXED_NOW - 90_000,
            updatedLabel = "1 分钟前",
            pinned = true,
            groupId = null,
        )
        overlay.syncFrom(listOf(pinnedRow))
        overlay.openSessionMenu(pinnedRow)
        overlay.dispatch("s-9", SessionMenuItem.TogglePin)

        val projectedRows = projected(overlay, listOf(pinnedRow)).flatMap { it.sessions }
        val unpinned = projectedRows.first { it.id == "s-9" }
        assertFalse(unpinned.pinned, "乐观取消置顶生效")
        assertEquals(
            TimeBucket.Today,
            unpinned.bucket,
            "取消置顶必须按 bucketFor 重算时间桶，不能留在「置顶」桶里装死",
        )
    }
}
