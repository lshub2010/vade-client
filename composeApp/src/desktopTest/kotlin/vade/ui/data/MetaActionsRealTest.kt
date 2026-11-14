package vade.ui.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import vade.ui.meta.MetaGroup
import vade.ui.protocol.FakeTransportFactory
import vade.ui.protocol.errorFrame
import vade.ui.protocol.idOf
import vade.ui.protocol.requestOf
import vade.ui.protocol.responseFrame
import vade.ui.protocol.testClient
import vade.ui.protocol.waitUntil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [MetaActionsReal] against the frozen wire: every method proves its exact method name
 * + params JSON (逐字), the refusal paths prove ZERO frames, and the required-field
 * discipline proves a missing `icon`/`color` on a group row is an error, never a zero
 * value. Uses the same harness as the frozen protocol tests ([FakeTransportFactory]) —
 * no socket is ever opened.
 */
class MetaActionsRealTest {

    private suspend fun connectedAdapter(
        factory: FakeTransportFactory,
    ): Pair<vade.ui.protocol.VadeClient, vade.ui.protocol.FakeTransport> {
        val client = testClient(factory)
        client.connect()
        assertTrue(client.awaitConnected())
        return client to factory.transports[0]
    }

    // ---------------------------------------------------------------- session verbs

    @Test
    fun renameSendsExactFrameAndChecksOk() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (client, transport) = connectedAdapter(factory)
            val adapter = MetaActionsReal(client)
            val deferred = async(Dispatchers.Default) { adapter.renameSession("s-1", "新标题") }
            waitUntil { transport.record.size >= 1 }
            assertEquals("session/rename", requestOf(transport.record[0]).first)
            assertEquals("""{"session_id":"s-1","title":"新标题"}""", requestOf(transport.record[0]).second)
            transport.push(
                responseFrame(idOf(transport.record[0]), """{"ok":true,"session":${sessionRow("s-1", 1_700_000_000_000)}}"""),
            )
            deferred.await()
            client.close()
        }
    }

    @Test
    fun renameRefusesBlankTitleWithoutSending() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (client, transport) = connectedAdapter(factory)
            val adapter = MetaActionsReal(client)
            assertFailsWith<vade.ui.protocol.VadeException> {
                adapter.renameSession("s-1", "   ")
            }.let { assertEquals(vade.ui.protocol.VadeErrorCodes.INVALID_ARGUMENT, it.code) }
            assertEquals(0, transport.record.size, "a blank title must never produce a frame")
            client.close()
        }
    }

    @Test
    fun pinAndArchiveSendExactFrames() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (client, transport) = connectedAdapter(factory)
            val adapter = MetaActionsReal(client)
            val pin = async(Dispatchers.Default) { adapter.setSessionPinned("s-1", true) }
            waitUntil { transport.record.size >= 1 }
            assertEquals("session/pin", requestOf(transport.record[0]).first)
            assertEquals("""{"session_id":"s-1","pinned":true}""", requestOf(transport.record[0]).second)
            transport.push(responseFrame(idOf(transport.record[0]), """{"ok":true,"pinned_at":1700000000000}"""))
            pin.await()

            val unarchive = async(Dispatchers.Default) { adapter.setSessionArchived("s-1", false) }
            waitUntil { transport.record.size >= 2 }
            assertEquals("session/archive", requestOf(transport.record[1]).first)
            assertEquals("""{"session_id":"s-1","archived":false}""", requestOf(transport.record[1]).second)
            transport.push(responseFrame(idOf(transport.record[1]), """{"ok":true}"""))
            unarchive.await()
            client.close()
        }
    }

    @Test
    fun deleteSendsExactFrame() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (client, transport) = connectedAdapter(factory)
            val adapter = MetaActionsReal(client)
            val deferred = async(Dispatchers.Default) { adapter.deleteSession("s-4") }
            waitUntil { transport.record.size >= 1 }
            assertEquals("session/delete", requestOf(transport.record[0]).first)
            assertEquals("""{"session_id":"s-4"}""", requestOf(transport.record[0]).second)
            transport.push(responseFrame(idOf(transport.record[0]), """{"ok":true}"""))
            deferred.await()
            client.close()
        }
    }

    @Test
    fun assignWritesGroupIdOrNullVerbatim() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (client, transport) = connectedAdapter(factory)
            val adapter = MetaActionsReal(client)
            val assign = async(Dispatchers.Default) { adapter.assignSessionToGroup("s-2", "g-1") }
            waitUntil { transport.record.size >= 1 }
            assertEquals("session/group.assign", requestOf(transport.record[0]).first)
            assertEquals("""{"session_id":"s-2","group_id":"g-1"}""", requestOf(transport.record[0]).second)
            transport.push(responseFrame(idOf(transport.record[0]), """{"ok":true,"group_id":"g-1"}"""))
            assign.await()

            val unassign = async(Dispatchers.Default) { adapter.assignSessionToGroup("s-2", null) }
            waitUntil { transport.record.size >= 2 }
            // The un-group case is REQUIRED-with-null: the key must be present as null.
            assertEquals("""{"session_id":"s-2","group_id":null}""", requestOf(transport.record[1]).second)
            transport.push(responseFrame(idOf(transport.record[1]), """{"ok":true,"group_id":null}"""))
            unassign.await()
            client.close()
        }
    }

    @Test
    fun assignRefusesBlankGroupIdWithoutSending() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (client, transport) = connectedAdapter(factory)
            val adapter = MetaActionsReal(client)
            assertFailsWith<vade.ui.protocol.VadeException> {
                adapter.assignSessionToGroup("s-2", " ")
            }.let { assertEquals(vade.ui.protocol.VadeErrorCodes.INVALID_ARGUMENT, it.code) }
            assertEquals(0, transport.record.size, "a blank group_id is a bug, not ungrouped — zero frames")
            client.close()
        }
    }

    // ---------------------------------------------------------------- group CRUD

    @Test
    fun listGroupsSendsEmptyParamsAndMapsContractRows() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (client, transport) = connectedAdapter(factory)
            val adapter = MetaActionsReal(client)
            val deferred = async(Dispatchers.Default) { adapter.listGroups() }
            waitUntil { transport.record.size >= 1 }
            assertEquals("session/group.list", requestOf(transport.record[0]).first)
            assertEquals("""{}""", requestOf(transport.record[0]).second)
            transport.push(
                responseFrame(
                    idOf(transport.record[0]),
                    """{"groups":[""" +
                        """{"group_id":"g-1","name":"项目","icon":"briefcase","color":"#3B82F6","sort_order":0,"created_at":1700000000000,"session_count":2},""" +
                        """{"group_id":"g-2","name":"灵感","icon":"bulb","color":"#8B5CF6","sort_order":1,"created_at":1700000001000,"session_count":0}]}""",
                ),
            )
            val groups = deferred.await()
            assertEquals(
                listOf(
                    MetaGroup("g-1", "项目", "briefcase", "#3B82F6"),
                    MetaGroup("g-2", "灵感", "bulb", "#8B5CF6"),
                ),
                groups,
                "server order (sort_order, created_at) must be preserved",
            )
            client.close()
        }
    }

    @Test
    fun createGroupSendsExactFrameAndReturnsServerId() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (client, transport) = connectedAdapter(factory)
            val adapter = MetaActionsReal(client)
            val deferred = async(Dispatchers.Default) { adapter.createGroup("项目", "briefcase", "#3B82F6") }
            waitUntil { transport.record.size >= 1 }
            assertEquals("session/group.create", requestOf(transport.record[0]).first)
            assertEquals("""{"name":"项目","icon":"briefcase","color":"#3B82F6"}""", requestOf(transport.record[0]).second)
            transport.push(
                responseFrame(
                    idOf(transport.record[0]),
                    """{"group":{"group_id":"g-uuid-9","name":"项目","icon":"briefcase","color":"#3B82F6","sort_order":0,"created_at":1700000000000}}""",
                ),
            )
            val group = deferred.await()
            assertEquals(MetaGroup("g-uuid-9", "项目", "briefcase", "#3B82F6"), group, "the SERVER id, not a local guess")
            client.close()
        }
    }

    @Test
    fun createGroupRefusesBlankNameAndBadColorWithoutSending() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (client, transport) = connectedAdapter(factory)
            val adapter = MetaActionsReal(client)
            assertFailsWith<vade.ui.protocol.VadeException> {
                adapter.createGroup("  ", "briefcase", "#3B82F6")
            }
            assertFailsWith<vade.ui.protocol.VadeException> {
                adapter.createGroup("项目", "briefcase", "blue")
            }
            assertEquals(0, transport.record.size, "invalid group drafts must never produce a frame")
            client.close()
        }
    }

    @Test
    fun groupRowMissingRequiredIconOrColorThrowsInsteadOfZeroValue() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (client, transport) = connectedAdapter(factory)
            val adapter = MetaActionsReal(client)
            val deferred = async(Dispatchers.Default) {
                runCatching { adapter.listGroups() }
            }
            waitUntil { transport.record.size >= 1 }
            transport.push(
                responseFrame(
                    idOf(transport.record[0]),
                    """{"groups":[{"group_id":"g-1","name":"无图标","icon":null,"color":null,"sort_order":0,"created_at":1700000000000,"session_count":0}]}""",
                ),
            )
            val failure = deferred.await().exceptionOrNull()
            assertTrue(failure is vade.ui.protocol.VadeException, "missing required icon/color must throw")
            assertEquals(vade.ui.protocol.VadeErrorCodes.PROTOCOL_MISMATCH, failure.code)
            client.close()
        }
    }

    @Test
    fun updateGroupSendsOnlyPresentKeys() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (client, transport) = connectedAdapter(factory)
            val adapter = MetaActionsReal(client)
            // Icon and color in ONE submission — the UI's 同一次提交 case.
            val update = async(Dispatchers.Default) { adapter.updateGroup("g-1", iconName = "bulb", colorHex = "#8B5CF6") }
            waitUntil { transport.record.size >= 1 }
            assertEquals("session/group.update", requestOf(transport.record[0]).first)
            assertEquals("""{"group_id":"g-1","icon":"bulb","color":"#8B5CF6"}""", requestOf(transport.record[0]).second)
            transport.push(
                responseFrame(
                    idOf(transport.record[0]),
                    """{"group":{"group_id":"g-1","name":"项目","icon":"bulb","color":"#8B5CF6","sort_order":0,"created_at":1700000000000}}""",
                ),
            )
            update.await()
            client.close()
        }
    }

    @Test
    fun updateGroupRefusesNoChangeAndBlankNameWithoutSending() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (client, transport) = connectedAdapter(factory)
            val adapter = MetaActionsReal(client)
            assertFailsWith<vade.ui.protocol.VadeException> {
                adapter.updateGroup("g-1")
            }
            assertFailsWith<vade.ui.protocol.VadeException> {
                adapter.updateGroup("g-1", name = "  ")
            }
            assertEquals(0, transport.record.size, "an empty update is refused client-side, zero frames")
            client.close()
        }
    }

    @Test
    fun deleteGroupSendsExactFrameAndRefusesOkFalse() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (client, transport) = connectedAdapter(factory)
            val adapter = MetaActionsReal(client)
            val deferred = async(Dispatchers.Default) { adapter.deleteGroup("g-2") }
            waitUntil { transport.record.size >= 1 }
            assertEquals("session/group.delete", requestOf(transport.record[0]).first)
            assertEquals("""{"group_id":"g-2"}""", requestOf(transport.record[0]).second)
            transport.push(responseFrame(idOf(transport.record[0]), """{"ok":true,"unassigned":2}"""))
            deferred.await()

            val second = async(Dispatchers.Default) { runCatching { adapter.deleteGroup("g-2") } }
            waitUntil { transport.record.size >= 2 }
            transport.push(responseFrame(idOf(transport.record[1]), """{"ok":false,"unassigned":0}"""))
            val failure = second.await().exceptionOrNull()
            assertTrue(failure is vade.ui.protocol.VadeException, "ok=false is a protocol violation, never accepted")
            client.close()
        }
    }

    // ---------------------------------------------------------------- error mapping

    @Test
    fun errorsPropagateWithCodeIntactAndCategoryMapped() {
        // 8 of the 34 C-04 codes through ONE adapter call: the code must survive
        // verbatim and VadeErrorMapping must land it in the right bucket.
        val samples = listOf(
            vade.ui.protocol.VadeErrorCodes.TIMEOUT to vade.ui.data.VadeErrorCategory.Retryable,
            vade.ui.protocol.VadeErrorCodes.OVERLOADED to vade.ui.data.VadeErrorCategory.Retryable,
            vade.ui.protocol.VadeErrorCodes.UNAVAILABLE to vade.ui.data.VadeErrorCategory.NeedsReconnect,
            vade.ui.protocol.VadeErrorCodes.NOT_FOUND to vade.ui.data.VadeErrorCategory.NeedsReconnect,
            vade.ui.protocol.VadeErrorCodes.INVALID_ARGUMENT to vade.ui.data.VadeErrorCategory.NeedsUserAction,
            vade.ui.protocol.VadeErrorCodes.PERMISSION_DENIED to vade.ui.data.VadeErrorCategory.NeedsUserAction,
            vade.ui.protocol.VadeErrorCodes.PAYLOAD_TOO_LARGE to vade.ui.data.VadeErrorCategory.NeedsUserAction,
            vade.ui.protocol.VadeErrorCodes.PROTOCOL_MISMATCH to vade.ui.data.VadeErrorCategory.Fatal,
        )
        runBlocking {
            for ((code, expectedCategory) in samples) {
                val factory = FakeTransportFactory()
                val (client, transport) = connectedAdapter(factory)
                val adapter = MetaActionsReal(client)
                val deferred = async(Dispatchers.Default) { runCatching { adapter.deleteSession("s-1") } }
                waitUntil { transport.record.size >= 1 }
                transport.push(errorFrame(idOf(transport.record[0]), code, "core says $code"))
                val failure = deferred.await().exceptionOrNull()
                assertTrue(failure is vade.ui.protocol.VadeException, "$code must surface as VadeException")
                assertEquals(code, failure.code, "the C-04 code must survive the adapter verbatim")
                assertEquals(expectedCategory, VadeErrorMapping.categoryOf(failure.code), "category for $code")
                client.close()
            }
        }
    }
}
