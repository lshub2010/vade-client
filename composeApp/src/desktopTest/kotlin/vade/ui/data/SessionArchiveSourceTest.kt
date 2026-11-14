package vade.ui.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import vade.ui.chat.ChatMessage
import vade.ui.protocol.FakeTransport
import vade.ui.protocol.FakeTransportFactory
import vade.ui.protocol.idOf
import vade.ui.protocol.requestOf
import vade.ui.protocol.responseFrame
import vade.ui.protocol.waitUntil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * g-archive-ui · `SessionSource.sessions(includeArchived)`：
 * **接口开口子**而不改默认行为。
 *
 * 这一层钉四件事：
 *  1. **默认形参不改行为** —— `sessions()` 与 `sessions(false)` 返回逐字段相同的行，且发出去的
 *     请求体逐字节相同（原本就是「省略 `archived` 靠契约默认 false」，不许长出显式键）。
 *  2. **`includeArchived = true` 真的把归档行放进来** —— 同一份回包（一活一归档）在 true 下
 *     两行都在，归档行 `archived = true`、非归档行 `archived = false`。
 *  3. **默认值写在接口上** —— 调用点不传实参时取的是接口声明的 `false`，实现里不重复写默认值
 *     （Kotlin 的规则：默认值只能出现在基声明处）。用一个最小实现直接验。
 *  4. **反向守卫** —— `includeArchived` 是**本地投影**开关，不许变成线上过滤器：true 时请求体
 *     依旧没有 `archived` 键（契约里那个 `archived` 参数是「只回归档行」的筛选反转，混用会让
 *     同一个 `sessions()` 悄悄换掉数据集）；夹具源的默认列表也不许被这个形参改掉。
 */
class SessionArchiveSourceTest {

    // ------------------------------------------------------------ 1. 默认形参不改行为

    @Test
    fun defaultCallAndExplicitFalseReturnTheSameRows() {
        val factory = FakeTransportFactory()
        runBlocking {
            val source = connected(factory)
            val transport = factory.transports[0]
            val (implicit, _) = call(transport, source, flag = null)
            val (explicit, _) = call(transport, source, flag = false)
            // 数据类 equals：逐字段比，不是只比 id。少一个字段被这个形参动过就会红。
            assertEquals(implicit, explicit, "默认调用与显式 false 必须是同一份结果")
            assertEquals(listOf("s-live"), implicit.map { it.id }, "默认视图只留未归档行")
            assertFalse(implicit.single().archived)
            source.disconnect()
        }
    }

    @Test
    fun defaultCallSendsExactlyTheSameRequestAsExplicitFalse() {
        val factory = FakeTransportFactory()
        runBlocking {
            val source = connected(factory)
            val transport = factory.transports[0]
            val (_, implicitParams) = call(transport, source, flag = null)
            val (_, explicitParams) = call(transport, source, flag = false)
            assertEquals(implicitParams, explicitParams, "请求体必须逐键相同（JsonObject 比较与键序无关）")
            assertFalse(implicitParams.containsKey("archived"), "默认视图靠省略表达，不长显式键")
            assertFalse(implicitParams.containsKey("group_id"), "分组维度本棒不碰请求体")
            source.disconnect()
        }
    }

    // ------------------------------------------------------------ 2. includeArchived 真生效

    @Test
    fun includeArchivedTrueKeepsArchivedRowsNextToLiveOnes() {
        val factory = FakeTransportFactory()
        runBlocking {
            val source = connected(factory)
            val transport = factory.transports[0]
            val (rows, _) = call(transport, source, flag = true)
            assertEquals(
                listOf("s-live", "s-archived"),
                rows.map { it.id },
                "归档视图必须同时拿到归档行与非归档行（筛掉活行就是筛选反转，不是这个形参的语义）",
            )
            val archived = rows.first { it.id == "s-archived" }
            val live = rows.first { it.id == "s-live" }
            assertTrue(archived.archived, "`archived_at` 有值 ⇒ archived = true")
            assertFalse(live.archived)
            // 归档行上的分组字段同样不许被顺手抹掉（两个维度独立）。
            assertEquals("g-1", archived.groupId)
            source.disconnect()
        }
    }

    @Test
    fun includeArchivedTrueDoesNotTurnIntoAServerSideFilter() {
        // 反向守卫：契约 `session/list.params.archived = true` 的意思是「只回归档行」。
        // 把本地开关接到那个参数上，`sessions(true)` 就会变成一个纯归档视图 —— 那是另一个功能。
        val factory = FakeTransportFactory()
        runBlocking {
            val source = connected(factory)
            val transport = factory.transports[0]
            val (_, defaultParams) = call(transport, source, flag = null)
            val (_, widenedParams) = call(transport, source, flag = true)
            assertFalse(widenedParams.containsKey("archived"), "true 只放宽本地投影，不许写线上过滤参数")
            assertEquals(defaultParams, widenedParams, "两档请求体必须一模一样（差异只在投影）")
            source.disconnect()
        }
    }

    // ------------------------------------------------------------ 3. 默认值在接口声明处

    @Test
    fun theDefaultFalseComesFromTheInterfaceNotFromEachImplementation() {
        // 一个「只写形参、不写默认值」的最小实现：调用点 `sessions()` 能编过、能拿到 false，
        // 说明默认值确实挂在接口上（实现里重复写默认值是编译错误，所以这条也是接口契约的证据）。
        val source = MinimalSource()
        runBlocking {
            assertEquals(listOf("s-live"), source.sessions().map { it.id }, "不传实参 ⇒ 默认 false")
            assertEquals(false, source.lastFlag, "默认值必须真的落到 false")
            assertEquals(listOf("s-live", "s-archived"), source.sessions(true).map { it.id })
            assertEquals(true, source.lastFlag)
        }
    }

    @Test
    fun fakeSourceKeepsItsPinnedFixtureListInBothModes() {
        // 夹具源的默认列表就是那 6 行（含 s-wasm / s-agp 两条归档行）——「默认不改行为」包括它。
        // 而且这两行**必须留着**：行上的「已归档」标记与截图都要靠它们，滤掉就什么都看不见了。
        val fake = FakeSessionSource()
        runBlocking {
            assertEquals(FakeSessions.all, fake.sessions())
            assertEquals(FakeSessions.all, fake.sessions(false))
            assertEquals(FakeSessions.all, fake.sessions(true))
        }
        assertTrue(FakeSessions.all.any { it.archived }, "夹具必须留着归档行，否则标记无从渲染")
    }

    @Test
    fun autoConnectingWrapperForwardsTheParameter() {
        // 线上入口是 AutoConnectingSessionSource（SourceConfig 包的那层）；它要是把形参吞掉，
        // 真源上的归档视图永远走不到。这里用假 transport 走完整一跳，只看结果。
        val factory = FakeTransportFactory()
        runBlocking {
            val real = RealSessionSource(dataTestConfig(), factory)
            val auto = AutoConnectingSessionSource(
                real = real,
                host = "127.0.0.1",
                port = 1,
                connectTimeoutMs = 3_000,
            )
            val deferred = async(Dispatchers.Default) { runCatching { auto.sessions(true) } }
            waitUntil { factory.transports.isNotEmpty() && factory.transports[0].record.isNotEmpty() }
            val transport = factory.transports[0]
            transport.push(responseFrame(idOf(transport.record[0]), ROWS))
            val rows = deferred.await().getOrThrow()
            assertEquals(
                listOf("s-live", "s-archived"),
                rows.map { it.id },
                "包装层必须把 includeArchived = true 透传到真源",
            )
            real.disconnect()
        }
    }

    // ------------------------------------------------------------ harness

    /** 一个最小 [SessionSource]：只为证明默认值来自接口声明。 */
    private class MinimalSource : SessionSource {
        var lastFlag: Boolean? = null

        override suspend fun sessions(includeArchived: Boolean): List<SessionSummary> {
            lastFlag = includeArchived
            val rows = listOf(live(), archived())
            return if (includeArchived) rows else rows.filterNot { it.archived }
        }

        override suspend fun messages(sessionId: String): List<ChatMessage> = emptyList()

        override fun observeEvents() = emptyFlow<VadeEvent>()
    }

    private suspend fun connected(factory: FakeTransportFactory): RealSessionSource {
        val source = RealSessionSource(dataTestConfig(), factory)
        source.connect()
        waitUntil { source.state.value == RealSessionState.Connected }
        return source
    }

    /** 发起一次 `sessions()`（[flag] 为 null = 不传实参），推同一份回包，回「行 + 请求参数」。 */
    private suspend fun call(
        transport: FakeTransport,
        source: RealSessionSource,
        flag: Boolean?,
    ): Pair<List<SessionSummary>, JsonObject> = coroutineScope {
        val before = transport.record.size
        val deferred = async(Dispatchers.Default) {
            runCatching { if (flag == null) source.sessions() else source.sessions(flag) }
        }
        waitUntil { transport.record.size > before }
        val frame = transport.record[before]
        assertEquals("session/list", requestOf(frame).first)
        transport.push(responseFrame(idOf(frame), ROWS))
        val rows = deferred.await().getOrElse { fail("sessions(${flag ?: "默认"}) 抛了：${it.message}") }
        rows to json(requestOf(frame).second)
    }

    private companion object {
        /** 十天的年龄：两次调用的 `updatedLabel` / `bucket` 都能稳定复现（不会跨「刚刚」边界）。 */
        const val TEN_DAYS_MS = 10L * 24 * 60 * 60 * 1000

        val ARCHIVED_AT_MS = 1_700_000_000_000L

        /** 一活一归档，且归档行带分组：两个维度都在同一份回包里，便于一次看清。 */
        val ROWS: String = run {
            val updatedAt = System.currentTimeMillis() - TEN_DAYS_MS
            """
            {"sessions":[
              ${row("s-live", updatedAt, archivedAt = "null", groupId = "null")},
              ${row("s-archived", updatedAt, archivedAt = ARCHIVED_AT_MS.toString(), groupId = "\"g-1\"")}
            ],"next_cursor":null}
            """.trimIndent()
        }

        fun row(id: String, updatedAt: Long, archivedAt: String, groupId: String): String =
            """{"session_id":"$id","created_at":${updatedAt - 1_000},"updated_at":$updatedAt,""" +
                """"title":"标题 $id","icon":null,"color":null,"group_id":$groupId,""" +
                """"pinned_at":null,"archived_at":$archivedAt,"unread":0,"state":"open"}"""

        fun live(): SessionSummary = SessionSummary(
            id = "s-live",
            title = "标题 s-live",
            preview = "",
            bucket = TimeBucket.Earlier,
            updatedAt = 0L,
            updatedLabel = "10 天前",
        )

        fun archived(): SessionSummary = live().copy(
            id = "s-archived",
            title = "标题 s-archived",
            archived = true,
            groupId = "g-1",
        )
    }
}
