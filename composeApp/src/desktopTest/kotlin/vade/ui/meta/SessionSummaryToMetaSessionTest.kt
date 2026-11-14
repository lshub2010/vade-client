package vade.ui.meta

import java.lang.reflect.Modifier
import vade.ui.data.FakeSessions
import vade.ui.data.SessionSummary
import vade.ui.data.TimeBucket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * g-archive-ui · `SessionSummary → MetaSession` 投影（`SessionMetaReal.kt` 的 KDoc 早就
 * 承诺了它，但函数一直不存在）。
 *
 * 钉四件事：
 *  1. **逐字段映射** —— `MetaSession` 的 7 个字段全部有来源，一个不落、一个不编；
 *  2. `archived` / `groupId` 真的是**搬过去**的，不是默认值兜出来的；
 *  3. **反向守卫** —— `MetaSession` 多出一个 `SessionSummary` 里没有的字段就红（那意味着
 *     有人为了让编译过而往模型里编了一个没有读路径的槽位）；`SessionSummary` 里多出的
 *     展示态字段（bucket/updatedAt/unread/model/isDraft）不许被偷偷塞进来；
 *  4. 投影结果**真的能进状态机** —— 这是这层投影存在的唯一理由。
 */
class SessionSummaryToMetaSessionTest {

    // ------------------------------------------------------------ 1. 逐字段

    @Test
    fun everyMetaSessionFieldIsMappedFromTheSummary() {
        val summary = SessionSummary(
            id = "s-42",
            title = "RFC-0010 元数据评审",
            preview = "图标白名单 39 个",
            bucket = TimeBucket.Pinned,
            updatedAt = 1_700_000_000_000L,
            updatedLabel = "2 小时前",
            pinned = true,
            unread = true,
            model = "local-qwen",
            isDraft = true,
            archived = true,
            groupId = "g-2",
        )
        assertEquals(
            MetaSession(
                id = "s-42",
                title = "RFC-0010 元数据评审",
                preview = "图标白名单 39 个",
                updatedLabel = "2 小时前",
                pinned = true,
                archived = true,
                groupId = "g-2",
            ),
            summary.toMetaSession(),
            "七个字段必须逐字搬过去（缺哪个字段这条就会显示差异）",
        )
    }

    @Test
    fun archivedAndGroupAreCarriedOverNotDefaulted() {
        // 正样本：两个维度都是 true / 非 null —— 只有真的读了字段才可能相等。
        val tagged = base().copy(archived = true, groupId = "g-1").toMetaSession()
        assertTrue(tagged.archived, "archived 必须从 SessionSummary 搬过来，不能被默认 false 盖掉")
        assertEquals("g-1", tagged.groupId)
        // 负样本：未归档未分组 ⇒ false / null（不是 null 布尔、不是空串分组）。
        val bare = base().toMetaSession()
        assertFalse(bare.archived)
        assertNull(bare.groupId)
        // 只归档不分组 / 只分组不归档：两条轴独立，不许互相带跑。
        val archivedUngrouped = base().copy(archived = true).toMetaSession()
        assertTrue(archivedUngrouped.archived)
        assertNull(archivedUngrouped.groupId)
        val groupedLive = base().copy(groupId = "g-2").toMetaSession()
        assertFalse(groupedLive.archived)
        assertEquals("g-2", groupedLive.groupId)
    }

    @Test
    fun emptySummaryValuesStayEmptyTheyAreNotFilledWithPlaceholders() {
        // preview / updatedLabel 的默认就是空串：投影不许为了「好看」编「（无预览）」之类的文案，
        // 否则 UI 会把占位符当成真内容渲染出来。
        val projected = base().copy(preview = "", updatedLabel = "").toMetaSession()
        assertEquals("", projected.preview)
        assertEquals("", projected.updatedLabel)
    }

    @Test
    fun fixturesProjectWithTheirArchivedAndGroupIntact() {
        // 夹具里 s-agp 同时「已归档 + 在 g-1」，s-u1a 既未归档也未分组：真数据形状上的对照。
        val byId = FakeSessions.all.associate { it.id to it.toMetaSession() }
        val agp = byId.getValue("s-agp")
        assertTrue(agp.archived, "s-agp 在夹具里是归档行")
        assertEquals("g-1", agp.groupId)
        val u1a = byId.getValue("s-u1a")
        assertFalse(u1a.archived)
        assertNull(u1a.groupId)
        // 逐条比对：投影只丢 MetaSession 没有的槽位，标题/预览/时间标签一个字都不许变。
        val source = FakeSessions.all.first { it.id == "s-agp" }
        assertEquals(source.title, agp.title)
        assertEquals(source.preview, agp.preview)
        assertEquals(source.updatedLabel, agp.updatedLabel)
        assertEquals(source.pinned, agp.pinned)
    }

    // ------------------------------------------------------------ 2. 反向守卫

    @Test
    fun metaSessionDeclaresExactlyTheSevenMappedFields() {
        // 字段集合 = 契约里 session 的元数据 + 列表展示项。多一个字段就意味着「编了一个
        // 没有读路径的槽位」——届时投影无从填它，只能塞默认值假装有数据。
        val fields = declaredFieldNames(MetaSession::class.java)
        assertEquals(
            setOf("id", "title", "preview", "updatedLabel", "pinned", "archived", "groupId"),
            fields,
            "MetaSession 的字段集合被改动了：新槽位必须先在契约里有读路径（或来本文件把理由写清）",
        )
        assertFalse("model" in fields, "model 走 session/model.set 的另一个模型，不是元数据槽位")
        assertFalse("unread" in fields, "未读点是列表展示态，元数据面板不消费")
    }

    @Test
    fun everyMetaSessionFieldHasASessionSummarySource() {
        // 反向投影检查：MetaSession 的每个字段都必须在 SessionSummary 里找得到同名来源。
        // 找不到 ⇒ 投影只能靠默认值凑数 ⇒ 这条先红（缺口不许被默认值掩盖）。
        val summaryFields = declaredFieldNames(SessionSummary::class.java)
        val missing = declaredFieldNames(MetaSession::class.java) - summaryFields
        assertTrue(missing.isEmpty(), "这些 MetaSession 字段在 SessionSummary 里没有来源：$missing")
    }

    // ------------------------------------------------------------ 3. 真能进状态机

    @Test
    fun projectedRowsAreConsumableByTheMetaStateMachine() {
        // 这层投影存在的唯一理由：把真数据喂进 `SessionMetaState`。构造失败或字段丢失，
        // 说明「数据有了但用不上」这个洞其实还在。
        val rows = FakeSessions.all.map { it.toMetaSession() }
        val state = SessionMetaState(
            initialSessions = rows,
            initialGroups = emptyList(),
            actions = FakeMetaActions(seedSessions = emptyList(), seedGroups = emptyList()),
        )
        assertEquals(rows, state.sessions, "状态机必须原样接受投影结果（顺序与字段都不许变）")
        val agp = state.sessions.first { it.id == "s-agp" }
        assertTrue(agp.archived, "归档维度必须活着抵达状态机 —— 编辑菜单的「取消归档」靠它")
        assertEquals("g-1", agp.groupId, "分组维度必须活着抵达状态机 —— 移动/筛选靠它")
    }

    // ------------------------------------------------------------ harness

    private fun base(): SessionSummary = SessionSummary(
        id = "s-base",
        title = "标题",
        preview = "预览",
        bucket = TimeBucket.Today,
        updatedAt = 1_700_000_000_000L,
        updatedLabel = "刚刚",
    )

    private fun declaredFieldNames(type: Class<*>): Set<String> = type.declaredFields
        .filterNot { Modifier.isStatic(it.modifiers) }
        .filterNot { it.isSynthetic }
        .map { it.name }
        .toSet()
}
