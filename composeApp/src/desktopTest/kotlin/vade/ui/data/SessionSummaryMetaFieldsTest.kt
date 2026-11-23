package vade.ui.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import vade.ui.protocol.FakeTransportFactory
import vade.ui.protocol.VadeJson
import vade.ui.protocol.arr
import vade.ui.protocol.idOf
import vade.ui.protocol.obj
import vade.ui.protocol.requestOf
import vade.ui.protocol.responseFrame
import vade.ui.protocol.waitUntil
import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * g-summary-fields：`SessionSummary` 的两个元数据槽位（`archived` / `groupId`）与其
 * `session/list` 投影。
 *
 * 这里钉三类东西：
 *  1. **默认值不改行为** —— 不传新字段的构造与 `copy()` 仍是「未归档 / 未分组」，假数据的
 *     分组与桶序一条不变（现有 40+ 处构造不受影响）。
 *  2. **投影逐字对照契约** —— `archived_at: int|null` → `archived: Boolean`（不是 null），
 *     `group_id: string|null` 的「有值 / 显式 null / 缺键」三态。
 *  3. **反向守卫：契约没有的字段，投影不得自己编** —— 契约回包**没有**布尔 `archived`
 *     （那是 `session/archive` 的入参）、**没有** `groups[]`（单归属列），因此喂进去也不许
 *     被读成值；数据类字段集合必须恰好是契约投影 + UI 自有字段，多一个就红。
 *
 * 契约名与类型不写死在注释里：`contractRowShapeDeclaresArchivedAtAndGroupIdVerbatim`
 * 直接读 `contracts/vade/schema/rpc.json`，契约改了这条先红。
 */
class SessionSummaryMetaFieldsTest {

    // ------------------------------------------------------------ 1. 默认值不改行为

    @Test
    fun newFieldsDefaultToFalseAndNullSoExistingConstructionIsUnchanged() {
        // 六个必填参数的「老写法」原样可用（位置参数，不带新字段）。
        val old = SessionSummary(
            "s-1",
            "标题",
            "预览",
            TimeBucket.Today,
            NOW,
            "刚刚",
        )
        assertFalse(old.archived, "默认必须是未归档")
        assertNull(old.groupId, "默认必须是未分组")
        // 命名参数的既有写法同样不受影响。
        val named = SessionSummary(
            id = "s-2",
            title = "标题",
            preview = "",
            bucket = TimeBucket.Pinned,
            updatedAt = NOW,
            updatedLabel = "刚刚",
            pinned = true,
            unread = true,
            model = "local-qwen",
            isDraft = true,
        )
        assertFalse(named.archived)
        assertNull(named.groupId)
        assertTrue(named.isDraft && named.pinned && named.unread, "既有字段一个都不能被挤掉")
    }

    @Test
    fun copyKeepsNewFieldsAndDefaultsThemWhenTheCopyOmitsThem() {
        val tagged = FakeRows.session(archived = true, groupId = "g-1")
        val retitled = tagged.copy(title = "改个名")
        assertTrue(retitled.archived, "copy() 不传新字段时必须保留原值")
        assertEquals("g-1", retitled.groupId)
        // copy 单独改一个维度不得串味。
        val unarchived = tagged.copy(archived = false)
        assertFalse(unarchived.archived)
        assertEquals("g-1", unarchived.groupId, "取消归档不得顺手把分组清掉")
        // 从一个默认行 copy 出新值也只影响被点名的字段。
        val bare = FakeRows.session()
        val grouped = bare.copy(groupId = "g-2")
        assertEquals("g-2", grouped.groupId)
        assertFalse(grouped.archived)
        assertEquals(bare.id, grouped.id)
    }

    @Test
    fun fixtureGroupingAndBucketOrderAreUnchangedByTheNewFields() {
        // 假数据加了 archived/groupId 之后，桶序与桶内顺序必须逐条不变（默认值不改行为）。
        val groups = FakeSessions.groups()
        assertEquals(
            listOf(TimeBucket.Pinned, TimeBucket.Today, TimeBucket.Yesterday, TimeBucket.LastSevenDays),
            groups.map { it.bucket },
        )
        assertEquals(listOf("s-rfc0004", "s-matrix"), groups[0].sessions.map { it.id })
        assertEquals(listOf("s-u1a", "s-skin11"), groups[1].sessions.map { it.id })
        assertEquals(listOf("s-wasm"), groups[2].sessions.map { it.id })
        assertEquals(listOf("s-agp"), groups[3].sessions.map { it.id })
        // `sessionGroups` 对同一批行必须给出与 FakeSessions.groups() 相同的结构。
        assertEquals(groups, sessionGroups(FakeSessions.all))
        assertEquals(listOf("s-rfc0004", "s-matrix", "s-u1a", "s-skin11", "s-wasm", "s-agp"), FakeSessions.all.map { it.id })
    }

    // ------------------------------------------------------------ 2. archived 投影

    @Test
    fun archivedRowMapsTrueWhenTheArchivedViewAsksForIt() {
        val row = json(metaRow(archivedAt = "1700000000000"))
        val summary = sessionRowToSummary(row, NOW, includeArchived = true)
            ?: fail("归档视图下归档行必须能投影出来（否则「已归档」筛选永远空白）")
        assertTrue(summary.archived, "`archived_at` 有值 ⇒ archived = true")
    }

    @Test
    fun rowWithoutArchivedAtMapsToFalseNotNull() {
        // 契约写的是 `archived_at: int|null`：缺值就是「未归档」，不是「不知道」。
        // archived 是 Boolean（非 Boolean?），这条断言同时钉住「不是 null」的编译期事实。
        val explicitNull = sessionRowToSummary(json(metaRow(archivedAt = "null")), NOW)
            ?: fail("非归档行必须投影")
        assertFalse(explicitNull.archived)
        val absent = sessionRowToSummary(json(metaRow(archivedAt = ABSENT)), NOW)
            ?: fail("缺 archived_at 键的行必须仍能投影（契约外形态按未归档处理）")
        assertFalse(absent.archived, "缺键 ⇒ 未归档（false），不许降级成 null/true")
    }

    @Test
    fun defaultListStillDropsArchivedRows() {
        // 既有行为保护：`sessions()` 不传 archived（契约默认 false），所以缺省投影把归档行挡掉。
        // 现有用例 `RealSessionListTest.nullTitleFallsBackAndArchivedRowsAreSkipped` 依赖这条。
        val archived = json(metaRow(archivedAt = "1700000000000"))
        assertNull(sessionRowToSummary(archived, NOW), "缺省（archived=false 视图）必须丢掉归档行")
        assertNull(sessionRowToSummary(archived, NOW, includeArchived = false))
        // 反证：同一个回包在归档视图下不是 null —— 差别只来自 includeArchived，不是数据。
        assertTrue(sessionRowToSummary(archived, NOW, includeArchived = true)?.archived == true)
    }

    // ------------------------------------------------------------ 3. groupId 三态

    @Test
    fun groupedRowCopiesGroupIdVerbatim() {
        val summary = sessionRowToSummary(json(metaRow(groupId = "\"g-1\"")), NOW) ?: fail("行必须投影")
        assertEquals("g-1", summary.groupId, "`group_id` 必须逐字保留（不裁剪、不改大小写）")
        val weird = sessionRowToSummary(json(metaRow(groupId = "\"a b-c_d\"")), NOW) ?: fail("行必须投影")
        assertEquals("a b-c_d", weird.groupId)
    }

    @Test
    fun explicitNullGroupIdMapsToUngrouped() {
        val summary = sessionRowToSummary(json(metaRow(groupId = "null")), NOW) ?: fail("行必须投影")
        assertNull(summary.groupId, "显式 null = 未分组")
    }

    @Test
    fun absentGroupIdMapsToUngrouped() {
        // 回包行里的 group_id 没有「省略 = 全部」的语义（那是请求参数的三态），缺键按未分组。
        val summary = sessionRowToSummary(json(metaRow(groupId = ABSENT)), NOW) ?: fail("行必须投影")
        assertNull(summary.groupId)
    }

    @Test
    fun wrongShapedGroupIdIsNotInventedIntoAGroup() {
        // 类型漂移不许被「猜」成某个组：数字 / 布尔 / 对象 / 数组 一律落 null（未分组），
        // 与 `title` 在本文件里的宽松口径一致；绝不把 `3` 自动变成 "3"。
        for (bad in listOf("3", "true", "{\"id\":\"g-1\"}")) {
            val summary = sessionRowToSummary(json(metaRow(groupId = bad)), NOW)
                ?: fail("畸形 group_id 不应让整行不可投影（got $bad）")
            assertNull(summary.groupId, "畸形 group_id 必须落 null，不得编出组：$bad")
        }
    }

    // ------------------------------------------------------------ 4. 反向守卫

    @Test
    fun reverseGuardContractHasNoArchivedBooleanSoTheProjectionDoesNotReadOne() {
        // `archived` 是 `session/archive` 的入参，**不是**回包字段。一个把入参误当回包读的
        // 投影会在这里被抓住：行里塞 `"archived": true` 而没有 `archived_at` ⇒ 必须仍是 false。
        val row = json(metaRow(archivedAt = "null", extra = ",\"archived\":true"))
        val summary = sessionRowToSummary(row, NOW) ?: fail("行必须投影")
        assertTrue(row.containsKey("archived"), "夹具得真的带上这个诱饵键")
        assertFalse(summary.archived, "契约没有回包字段 `archived`；投影读它就是在编字段")
        // 反过来：只有 archived_at 算数。
        val real = sessionRowToSummary(json(metaRow(archivedAt = "1700000000000", extra = ",\"archived\":false")), NOW, includeArchived = true)
            ?: fail("归档行必须投影")
        assertTrue(real.archived, "`archived_at` 有值 ⇒ true，哪怕同行的 `archived:false` 在说谎")
    }

    @Test
    fun reverseGuardContractHasNoGroupsArraySoTheProjectionIgnoresIt() {
        // 单归属列（§1 #1 文件夹语义）：契约没有 `groups[]`。塞一个也不许被读成归属。
        val row = json(metaRow(groupId = "null", extra = ",\"groups\":[\"g-1\",\"g-2\"]"))
        val summary = sessionRowToSummary(row, NOW) ?: fail("行必须投影")
        assertTrue(row.containsKey("groups"), "夹具得真的带上这个诱饵键")
        assertNull(summary.groupId, "契约没有 `groups[]`；从数组里取第一个就是在编归属模型")
    }

    @Test
    fun reverseGuardSessionSummaryDeclaresExactlyTheDocumentedFields() {
        // 数据类字段集合 = 契约投影 + UI 自有字段。多一个字段（例如 archivedAt、groups）
        // 就意味着有人在模型层编契约没给的东西 —— 这条会先红。
        val fields = SessionSummary::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .filterNot { it.isSynthetic }
            .map { it.name }
            .toSet()
        assertEquals(
            setOf(
                // 契约 session/list 行投影
                "id", "title", "pinned", "unread", "archived", "groupId", "updatedAt",
                // UI 自有（派生/展示），不回写线上
                "preview", "bucket", "updatedLabel", "model", "isDraft", "isProvisional",
            ),
            fields,
            "SessionSummary 的字段集合被改动了：新槽位必须先在契约里存在（或来本文件把理由写清）",
        )
        assertTrue("archived" in fields && "groupId" in fields, "两个新槽位必须落成真实字段")
        assertFalse("archivedAt" in fields, "archivedAt 是线上形态；UI 侧只留布尔投影")
        assertFalse("groups" in fields, "单归属列，不存在 groups 模型")
    }

    @Test
    fun contractRowShapeDeclaresArchivedAtAndGroupIdVerbatim() {
        // 契约是唯一权威：名字与类型逐字读出来，不从注释/记忆里抄。
        val rpc = VadeJson.default.parseToJsonElement(
            contractSchemaFile("rpc.json").readText(Charsets.UTF_8),
        ) as JsonObject
        val method = rpc.obj("directions")?.obj("ui_to_core")?.obj("session/list")
            ?: error("rpc.json 里没有 session/list — 契约结构变了，守卫需要跟着改")
        val row = method.obj("result")?.arr("sessions")?.firstOrNull() as? JsonObject
            ?: error("session/list.result.sessions 不是对象数组 — 契约结构变了")

        assertEquals("int|null", row.typeToken("archived_at"), "归档字段的契约名是 archived_at，类型 int|null")
        assertEquals("string|null", row.typeToken("group_id"), "分组字段的契约名是 group_id，类型 string|null")
        assertTrue(row.containsKey("archived_at") && row.containsKey("group_id"))
        assertFalse(row.containsKey("archived"), "行里没有布尔 `archived` —— 那是入参")
        assertFalse(row.containsKey("groups"), "行里没有 `groups[]` —— 单归属列")

        // 参数侧才叫 `archived`（布尔过滤器），和回包字段不是一回事；投影只读回包。
        val params = method.obj("params") ?: error("session/list 没有 params — 契约结构变了")
        assertEquals("bool, default false", params.typeToken("archived"), "`archived` 只作为过滤参数存在")
        val groupParam = (params["group_id"] as? JsonPrimitive)?.content
            ?: error("session/list.params 没有 group_id — 契约结构变了")
        assertTrue(
            groupParam.startsWith("string|null") && groupParam.contains("omitted"),
            "请求侧 group_id 是三态（省略=全部 / null=仅未分组 / 值=该组），实际：$groupParam",
        )
    }

    // ------------------------------------------------------------ 5. 假数据覆盖

    @Test
    fun fixturesCarryArchivedAndLiveRows() {
        val archived = FakeSessions.all.filter { it.archived }
        val live = FakeSessions.all.filterNot { it.archived }
        assertTrue(archived.size >= 2, "至少两条归档行，否则「已归档」视图没东西可看（实得 ${archived.size}）")
        assertTrue(live.size >= 2, "至少两条未归档行，否则看不出筛选确实在区分（实得 ${live.size}）")
        assertEquals(listOf("s-wasm", "s-agp"), archived.map { it.id }, "归档行的具体选择被钉住，便于截图对照")
        assertEquals(emptySet(), archived.map { it.id }.intersect(live.map { it.id }.toSet()))
    }

    @Test
    fun fixturesCarryGroupedUngroupedAndBothAtOnce() {
        val grouped = FakeSessions.all.filter { it.groupId != null }
        val ungrouped = FakeSessions.all.filter { it.groupId == null }
        assertTrue(grouped.size >= 2, "至少两条带分组（实得 ${grouped.size}）")
        assertTrue(ungrouped.size >= 2, "至少两条未分组（实得 ${ungrouped.size}）")
        assertTrue(
            FakeSessions.all.any { it.archived && it.groupId != null },
            "要有一条同时「归档 + 分组」，两个维度独立才看得出来",
        )
        assertTrue(
            FakeSessions.all.any { !it.archived && it.groupId == null },
            "也要有一条既未归档又未分组，作为负样本",
        )
        // 组 id 必须是 g-1/g-2 —— 与 FakeMetaActions.defaultGroups（项目 / 灵感）对得上，
        // 否则 UI 拿组名会落空，截图里只能看到裸 id。
        assertEquals(setOf("g-1", "g-2"), grouped.mapNotNull { it.groupId }.toSet())
        assertTrue(grouped.all { it.groupId!!.isNotBlank() }, "非空组 id，不许用空串表达「有组」")
    }

    @Test
    fun sessionsEndToEndDefaultViewSkipsArchivedRowsButKeepsTheirGroupField() {
        // 端到端（不含真 socket）：`sessions()` 走默认参数 ⇒ 归档行不进列表，
        // 而留下来那行的 groupId 必须真从线上回包上来了（不是靠默认值兜出来的 null）。
        val factory = FakeTransportFactory()
        runBlocking {
            val source = RealSessionSource(dataTestConfig(), factory)
            source.connect()
            waitUntil { source.state.value == RealSessionState.Connected }
            val transport = factory.transports[0]
            val deferred = async(Dispatchers.Default) { runCatching { source.sessions() } }
            waitUntil { transport.record.size >= 1 }
            val frame = transport.record[0]
            assertEquals("session/list", requestOf(frame).first)
            val params = json(requestOf(frame).second)
            assertFalse(params.containsKey("archived"), "默认视图靠省略 archived（契约默认 false）表达")
            transport.push(
                responseFrame(
                    idOf(frame),
                    """{"sessions":[
                        ${metaRow(id = "s-live", groupId = "\"g-1\"")},
                        ${metaRow(id = "s-gone", groupId = "\"g-2\"", archivedAt = "1700000000000")}
                    ],"next_cursor":null}""",
                ),
            )
            val rows = deferred.await().getOrThrow()
            assertEquals(listOf("s-live"), rows.map { it.id }, "默认视图只剩未归档行")
            assertEquals("g-1", rows.single().groupId, "groupId 必须来自回包，不是模型默认值")
            assertFalse(rows.single().archived)
            source.disconnect()
        }
    }

    // ------------------------------------------------------------ helpers

    /** 一条契约形态的 `session/list` 行；[archivedAt] / [groupId] 收原始 JSON token，便于造三态。 */
    private fun metaRow(
        id: String = "s-1",
        updatedAt: Long = NOW,
        archivedAt: String = "null",
        groupId: String = "null",
        extra: String = "",
    ): String = buildString {
        append("{\"session_id\":\"$id\",\"created_at\":${updatedAt - 1000},\"updated_at\":$updatedAt,")
        append("\"title\":\"标题\",\"icon\":null,\"color\":null,")
        if (groupId != ABSENT) append("\"group_id\":$groupId,")
        append("\"pinned_at\":null,")
        if (archivedAt != ABSENT) append("\"archived_at\":$archivedAt,")
        append("\"unread\":0,\"state\":\"open\"")
        append(extra)
        append("}")
    }

    /** Contract type token, e.g. `"int|null (unix ms)"` → `"int|null"`. */
    private fun JsonObject.typeToken(key: String): String =
        (this[key] as? JsonPrimitive)?.content?.substringBefore(" (")
            ?: error("contract row has no '$key'")

    /** 与 `VadeContractCoverageGuardTest` 相同的定位方式：从测试工作目录向上找 submodule。 */
    private fun contractSchemaFile(name: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            val candidate = File(dir, "contracts/vade/schema/$name")
            if (candidate.isFile) return candidate
            dir = dir?.parentFile ?: return@repeat
        }
        error(
            "contracts/vade/schema/$name not found walking up from ${System.getProperty("user.dir")} — " +
                "the contracts/vade submodule is not checked out (bash tools/setup-contracts.sh)",
        )
    }

    private object FakeRows {
        fun session(archived: Boolean = false, groupId: String? = null): SessionSummary =
            SessionSummary(
                id = "s-fake",
                title = "标题",
                preview = "",
                bucket = TimeBucket.Today,
                updatedAt = NOW,
                updatedLabel = "刚刚",
                archived = archived,
                groupId = groupId,
            )
    }

    private companion object {
        val NOW = 1_700_000_000_000L

        /** 哨兵：让夹具真的**省掉**这个键，而不是写 null。 */
        const val ABSENT = "\u0000ABSENT"
    }
}
