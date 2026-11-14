package vade.ui.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import vade.ui.chat.ChatRole
import vade.ui.chat.ContentPart as UiContentPart
import vade.ui.chat.MessageState
import vade.ui.protocol.FakeTransportFactory
import vade.ui.protocol.VadeException
import vade.ui.protocol.errorFrame
import vade.ui.protocol.idOf
import vade.ui.protocol.lng
import vade.ui.protocol.requestOf
import vade.ui.protocol.responseFrame
import vade.ui.protocol.waitUntil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `SessionSource.sessions()` must page through `session/list` with the contract
 * params; the row → [SessionSummary] mapping (bucket / label / pinned / unread) is
 * pinned pure; `messages()` must attach and decode the snapshot thread.
 */
class RealSessionListTest {

    private suspend fun connectedSource(factory: FakeTransportFactory): Pair<RealSessionSource, vade.ui.protocol.FakeTransport> {
        val source = RealSessionSource(dataTestConfig(), factory)
        source.connect()
        waitUntil { source.state.value == RealSessionState.Connected }
        return source to factory.transports[0]
    }

    // ------------------------------------------------------------ session/list paging

    @Test
    fun initialLoadPagesThroughSessionListWithContractParams() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (source, transport) = connectedSource(factory)
            val deferred = async(Dispatchers.Default) { runCatching { source.sessions() } }
            waitUntil { transport.record.size >= 1 }
            val frame1 = transport.record[0]
            assertEquals("session/list", requestOf(frame1).first)
            val params1 = json(requestOf(frame1).second)
            assertEquals(50L, params1.lng("limit"))
            assertEquals(0L, params1.lng("cursor"))
            // Contract defaults are carried by omission, not by explicit keys.
            assertFalse(params1.containsKey("group_id"))
            assertFalse(params1.containsKey("archived"))
            // Page 1: two rows, one more page follows.
            transport.push(
                responseFrame(
                    idOf(frame1),
                    """{"sessions":[${sessionRow("s-1", NOW)},${sessionRow("s-2", NOW - 90_000)}],"next_cursor":50}""",
                ),
            )
            waitUntil { transport.record.size >= 2 }
            val frame2 = transport.record[1]
            assertEquals("session/list", requestOf(frame2).first)
            val params2 = json(requestOf(frame2).second)
            assertEquals(50L, params2.lng("limit"))
            assertEquals(50L, params2.lng("cursor"))
            // Page 2: one row, end of the list.
            transport.push(
                responseFrame(idOf(frame2), """{"sessions":[${sessionRow("s-3", NOW - 26 * 3_600_000)}],"next_cursor":null}"""),
            )
            val summaries = deferred.await().getOrThrow()
            assertEquals(listOf("s-1", "s-2", "s-3"), summaries.map { it.id })
            source.disconnect()
        }
    }

    @Test
    fun sessionListErrorReplySurfacesAsVadeExceptionWithTheWireCode() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (source, transport) = connectedSource(factory)
            // runCatching INSIDE the async child: a failing async would cancel the
            // runBlocking parent before the test could await the result.
            val deferred = async(Dispatchers.Default) { runCatching { source.sessions() } }
            waitUntil { transport.record.size >= 1 }
            transport.push(errorFrame(idOf(transport.record[0]), "NOT_FOUND", "no such thing"))
            val thrown = deferred.await().exceptionOrNull()
            assertIs<VadeException>(thrown)
            assertEquals("NOT_FOUND", thrown.code)
            source.disconnect()
        }
    }

    @Test
    fun sessionsBeforeConnectFailsFastWithUnavailable() {
        val factory = FakeTransportFactory()
        runBlocking {
            val source = RealSessionSource(dataTestConfig(), factory)
            val thrown = runCatching { source.sessions() }.exceptionOrNull()
            assertIs<VadeException>(thrown)
            assertEquals("UNAVAILABLE", thrown.code)
        }
    }

    @Test
    fun sessionsRawReplyTimeoutMapsOntoTimeout() {
        val factory = FakeTransportFactory()
        runBlocking {
            val source = RealSessionSource(dataTestConfig(timeoutMs = 250), factory)
            source.connect()
            waitUntil { source.state.value == RealSessionState.Connected }
            val thrown = runCatching { source.sessions() }.exceptionOrNull()
            assertIs<VadeException>(thrown)
            assertEquals("TIMEOUT", thrown.code)
            source.disconnect()
        }
    }

    // ------------------------------------------------------------ row mapping (pure)

    @Test
    fun rowMapsIntoSummaryWithFixtureCompatibleBucketAndLabel() {
        val row = json(sessionRow("s-9", NOW - 90_000, title = "评审", unread = 1))
        val summary = sessionRowToSummary(row, NOW) ?: fail("row must map")
        assertEquals("s-9", summary.id)
        assertEquals("评审", summary.title)
        assertEquals("", summary.preview)
        assertEquals(TimeBucket.Today, summary.bucket)
        assertEquals("1 分钟前", summary.updatedLabel)
        assertTrue(summary.unread)
        assertFalse(summary.pinned)
        assertNull(summary.model)
        assertFalse(summary.isDraft)
    }

    @Test
    fun pinnedRowBucketsToPinnedAndOverridesRecency() {
        val row = json(sessionRow("s-p", NOW, pinned = true))
        val summary = sessionRowToSummary(row, NOW) ?: fail("row must map")
        assertTrue(summary.pinned)
        assertEquals(TimeBucket.Pinned, summary.bucket)
    }

    @Test
    fun bucketFollowsTheAgeLadderExactlyLikeTheFixtures() {
        assertEquals(TimeBucket.Today, bucketFor(NOW, pinned = false, nowMs = NOW))
        assertEquals(TimeBucket.Yesterday, bucketFor(NOW - 26 * 3_600_000, pinned = false, nowMs = NOW))
        assertEquals(TimeBucket.LastSevenDays, bucketFor(NOW - 3 * 86_400_000, pinned = false, nowMs = NOW))
        assertEquals(TimeBucket.LastSevenDays, bucketFor(NOW - 7 * 86_400_000, pinned = false, nowMs = NOW))
        assertEquals(TimeBucket.Earlier, bucketFor(NOW - 8 * 86_400_000, pinned = false, nowMs = NOW))
    }

    @Test
    fun labelsMirrorTheFixtureVocabulary() {
        assertEquals("刚刚", relativeLabel(NOW - 30_000, NOW))
        assertEquals("5 分钟前", relativeLabel(NOW - 5 * 60_000, NOW))
        // Same UTC day → wall clock HH:mm.
        val hourLabel = relativeLabel(NOW - 90 * 60_000, NOW)
        assertTrue(hourLabel.contains(":") && !hourLabel.contains(" "))
        // Yesterday keeps the HH:mm suffix; older ages degrade to "N 天前".
        val yesterdayLabel = relativeLabel(NOW - 26 * 3_600_000, NOW)
        assertTrue(yesterdayLabel.startsWith("昨天 ") && yesterdayLabel.contains(":"))
        assertEquals("4 天前", relativeLabel(NOW - 4 * 86_400_000, NOW))
        assertEquals("45 天前", relativeLabel(NOW - 45L * 86_400_000, NOW))
    }

    @Test
    fun nullTitleFallsBackAndArchivedRowsAreSkipped() {
        val row = json(sessionRow("s-t", NOW, title = null))
        assertEquals("（无标题）", sessionRowToSummary(row, NOW)?.title)
        val archived = json(sessionRow("s-a", NOW, archived = true))
        assertNull(sessionRowToSummary(archived, NOW))
        assertNull(sessionRowToSummary(json("""{"session_id":null}"""), NOW))
    }

    // ------------------------------------------------------------ messages() via attach snapshot

    @Test
    fun messagesAttachesAndParsesTheSnapshotThread() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (source, transport) = connectedSource(factory)
            val deferred = async(Dispatchers.Default) { runCatching { source.messages("s-1") } }
            waitUntil { transport.record.size >= 1 }
            assertEquals("ui/attach", requestOf(transport.record[0]).first)
            val params = json(requestOf(transport.record[0]).second)
            assertEquals("s-1", (params["session_id"] as JsonPrimitive).content)
            // last_seq omitted = fresh/switched attach → core answers with the snapshot.
            assertFalse(params.containsKey("last_seq"))
            transport.push(
                responseFrame(
                    idOf(transport.record[0]),
                    """{"session_id":"s-1","from_seq":7,"snapshot":{"from_seq":7,"messages":[
                        {"id":"msg:1","role":"user","content":"看图","parts":[
                            {"type":"image","file_id":"f1","mime":"image/png","name":"a.png","size":10,"width":100,"height":50,"sha256":"abc"},
                            {"type":"text","text":"看图"}]},
                        {"id":"msg:2","role":"assistant","content":"已生成文本","partial":true,"parts":[{"type":"text","text":"已生成文本"}]},
                        {"id":"msg:3","role":"user","content":"legacy"},
                        {"id":"msg:4","role":"tool","content":"tool-row"}]}}""",
                ),
            )
            val messages = deferred.await().getOrThrow()
            assertEquals(3, messages.size) // the tool row is skipped on purpose
            assertEquals(ChatRole.User, messages[0].role)
            assertIs<UiContentPart.Image>(messages[0].parts[0])
            assertEquals("f1", (messages[0].parts[0] as UiContentPart.Image).fileId)
            assertEquals(MessageState.Completed, messages[0].state)
            assertEquals(ChatRole.Assistant, messages[1].role)
            assertEquals(MessageState.Interrupted, messages[1].state)
            assertEquals(5, messages[1].partialChars) // 已生成文本 = 5 code points
            assertEquals(listOf<UiContentPart>(UiContentPart.Text("legacy")), messages[2].parts)
            source.disconnect()
        }
    }

    @Test
    fun attachWithoutSnapshotReturnsAnEmptyThread() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (source, transport) = connectedSource(factory)
            val deferred = async(Dispatchers.Default) { runCatching { source.messages("s-1") } }
            waitUntil { transport.record.size >= 1 }
            transport.push(
                responseFrame(idOf(transport.record[0]), """{"session_id":"s-1","from_seq":3}"""),
            )
            assertTrue(deferred.await().getOrThrow().isEmpty())
            source.disconnect()
        }
    }

    private companion object {
        val NOW = 1_700_000_000_000L
    }
}
