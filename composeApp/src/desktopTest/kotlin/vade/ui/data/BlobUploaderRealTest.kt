package vade.ui.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import vade.ui.chat.PendingAttachment
import vade.ui.chat.UploadFailedException
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException
import vade.ui.protocol.waitUntil
import java.security.MessageDigest
import java.util.Collections
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [BlobUploaderReal] against the RFC-0004 chunked protocol: real bytes, real digests,
 * real byte-counted progress, abort-on-failure/cancel, and pre-flight validation that
 * never touches the channel. The [BlobHttpChannel] is an in-memory recording fake —
 * the SHA-256 checks run against `java.security.MessageDigest` (JVM test side only;
 * the commonMain digest itself is pure Kotlin).
 */
class BlobUploaderRealTest {

    /** Recording fake: every exchange is kept in [calls]; replies come from [handler]. */
    private class RecordingChannel : BlobHttpChannel {
        val calls = mutableListOf<BlobHttpRequest>()

        var handler: suspend (BlobHttpRequest) -> BlobHttpResponse = {
            BlobHttpResponse(500, """{"code":"INTERNAL","message":"no handler configured"}""".toByteArray())
        }

        override suspend fun exchange(request: BlobHttpRequest): BlobHttpResponse {
            calls.add(request)
            return handler(request)
        }

        fun beginReply(uploadId: String, partSize: Long, size: Long, offsetNext: Long = 0): BlobHttpResponse =
            BlobHttpResponse(
                201,
                ("""{"file_id":null,"upload_id":"$uploadId","dedupe":false,"size":$size,""" +
                    """"mime":"text/plain","pinned":false,"expires_at":1700000000000,"part_size":$partSize,""" +
                    """"received":[],"offset_next":$offsetNext,"confirmed_bytes":$offsetNext,"max_bytes":104857600}""")
                    .toByteArray(),
            )

        fun dedupeReply(fileId: String): BlobHttpResponse =
            BlobHttpResponse(
                200,
                ("""{"file_id":"$fileId","upload_id":null,"dedupe":true,"size":40,"mime":"text/plain",""" +
                    """"pinned":false,"expires_at":1700000000000,"part_size":65536}""").toByteArray(),
            )

        fun partReply(): BlobHttpResponse = BlobHttpResponse(202, """{"received":1,"offset_next":40}""".toByteArray())

        fun completeReply(fileId: String): BlobHttpResponse =
            BlobHttpResponse(201, ("""{"file_id":"$fileId","sha256":"aa","size":40}""").toByteArray())

        fun error(code: String, message: String): BlobHttpResponse =
            BlobHttpResponse(400, ("""{"code":"$code","message":"$message"}""").toByteArray())
    }

    private fun attachment(
        name: String = "notes.txt",
        mime: String = "text/plain",
        size: Long = 40L,
        width: Int? = null,
        height: Int? = null,
    ): PendingAttachment = PendingAttachment(name = name, mime = mime, sizeBytes = size, width = width, height = height)

    // ---------------------------------------------------------------- sha256

    @Test
    fun sha256MatchesStandardKnownAnswers() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256.hex(Sha256.digest(ByteArray(0))),
            "empty input",
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.hex(Sha256.digest("abc".toByteArray())),
            "abc",
        )
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.hex(Sha256.digest("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".toByteArray())),
            "two-block message",
        )
    }

    @Test
    fun sha256AgreesWithJvmMessageDigestOnRandomData() {
        val rng = Random(42)
        val hexOf: (ByteArray) -> String = { it.joinToString("") { b -> "%02x".format(b) } }
        for (length in listOf(1, 55, 56, 63, 64, 65, 127, 128, 1000, 4097)) {
            val bytes = ByteArray(length) { rng.nextInt(0, 256).toByte() }
            val expected = hexOf(MessageDigest.getInstance("SHA-256").digest(bytes))
            assertEquals(expected, Sha256.hex(Sha256.digest(bytes)), "length $length")
        }
    }

    // ---------------------------------------------------------------- happy path

    @Test
    fun singleChunkUploadSendsBeginPutCompleteWithRealDigest() {
        runBlocking {
            val channel = RecordingChannel()
            val bytes = ByteArray(40) { it.toByte() }
            channel.handler = { request ->
                when {
                    request.method == "POST" && request.path == "/ui/blob" ->
                        channel.beginReply("upl_1", partSize = 65_536, size = 40)
                    request.method == "PUT" -> channel.partReply()
                    request.method == "POST" -> channel.completeReply("blb_1")
                    else -> BlobHttpResponse(204, ByteArray(0))
                }
            }
            val uploader = BlobUploaderReal(channel, { bytes })
            val progress = mutableListOf<Float>()
            val fileId = uploader.upload(attachment(size = 40L), "u-1") { progress.add(it) }

            assertEquals("blb_1", fileId)
            assertEquals(3, channel.calls.size, "begin, one part, complete")
            val begin = channel.calls[0]
            assertEquals("POST", begin.method)
            assertEquals("/ui/blob", begin.path)
            assertEquals("application/json", begin.contentType)
            // 逐字: the declared digest IS the digest of the staged bytes (real, not a placeholder).
            assertEquals(
                """{"kind":"file","sha256":"${Sha256.hex(Sha256.digest(bytes))}","size":40,""" +
                    """"mime":"text/plain","name":"notes.txt"}""",
                begin.jsonBody.toString(),
            )
            val part = channel.calls[1]
            assertEquals("PUT", part.method)
            assertEquals("/ui/blob/upload/upl_1?offset=0", part.path)
            assertEquals("application/octet-stream", part.contentType)
            assertTrue(part.bytesBody.contentEquals(bytes), "the part carries the real bytes")
            val complete = channel.calls[2]
            assertEquals("POST", complete.method)
            assertEquals("/ui/blob/upload/upl_1/complete", complete.path)
            assertEquals(
                """{"sha256":"${Sha256.hex(Sha256.digest(bytes))}","size":40}""",
                complete.jsonBody.toString(),
            )
            assertEquals(listOf(1.0f), progress, "single chunk: no intermediate tick, terminal 1f before return")
        }
    }

    @Test
    fun multiChunkUsesServerPartSizeAndProgressIsRealBytes() {
        runBlocking {
            val channel = RecordingChannel()
            val bytes = ByteArray(40) { (it * 3).toByte() }
            channel.handler = { request ->
                when {
                    request.method == "POST" && request.path == "/ui/blob" ->
                        channel.beginReply("upl_m", partSize = 16, size = 40)
                    request.method == "PUT" -> channel.partReply()
                    request.method == "POST" -> channel.completeReply("blb_m")
                    else -> BlobHttpResponse(204, ByteArray(0))
                }
            }
            val uploader = BlobUploaderReal(channel, { bytes })
            val progress = mutableListOf<Float>()
            val fileId = uploader.upload(attachment(size = 40L), "u-2") { progress.add(it) }

            assertEquals("blb_m", fileId)
            val puts = channel.calls.filter { it.method == "PUT" }
            assertEquals(3, puts.size, "part_size 16 over 40 bytes → 3 parts")
            assertEquals("/ui/blob/upload/upl_m?offset=0", puts[0].path)
            assertEquals("/ui/blob/upload/upl_m?offset=16", puts[1].path)
            assertEquals("/ui/blob/upload/upl_m?offset=32", puts[2].path)
            assertTrue(puts[0].bytesBody.contentEquals(bytes.copyOfRange(0, 16)))
            assertTrue(puts[1].bytesBody.contentEquals(bytes.copyOfRange(16, 32)))
            assertTrue(puts[2].bytesBody.contentEquals(bytes.copyOfRange(32, 40)), "the short tail part")
            // Real byte accounting: 16/40 and 32/40 while in flight (both < 1f), then 1f.
            assertEquals(listOf(16 / 40f, 32 / 40f, 1.0f), progress)
            assertTrue(progress.zipWithNext().all { (a, b) -> b >= a }, "monotonic non-decreasing")
        }
    }

    @Test
    fun dedupeShortCircuitsWithoutParts() {
        runBlocking {
            val channel = RecordingChannel()
            channel.handler = { _ -> channel.dedupeReply("blb_existing") }
            val uploader = BlobUploaderReal(channel, { ByteArray(40) })
            val progress = mutableListOf<Float>()
            val fileId = uploader.upload(attachment(size = 40L), "u-3") { progress.add(it) }
            assertEquals("blb_existing", fileId, "the server-assigned id, not a locally minted one")
            assertEquals(1, channel.calls.size, "begin only — no parts, no complete")
            assertEquals(listOf(1.0f), progress)
        }
    }

    @Test
    fun resumeSkipsConfirmedBytesAndProgressStartsThere() {
        runBlocking {
            val channel = RecordingChannel()
            val bytes = ByteArray(56) { it.toByte() }
            channel.handler = { request ->
                when {
                    request.method == "POST" && request.path == "/ui/blob" ->
                        channel.beginReply("upl_r", partSize = 16, size = 56, offsetNext = 32)
                    request.method == "PUT" -> channel.partReply()
                    request.method == "POST" -> channel.completeReply("blb_r")
                    else -> BlobHttpResponse(204, ByteArray(0))
                }
            }
            val uploader = BlobUploaderReal(channel, { bytes })
            val progress = mutableListOf<Float>()
            val fileId = uploader.upload(attachment(size = 56L), "u-4") { progress.add(it) }

            assertEquals("blb_r", fileId)
            val puts = channel.calls.filter { it.method == "PUT" }
            assertEquals(2, puts.size, "offsets 0 and 16 are already on the server — only 32 and 48 are sent")
            assertEquals("/ui/blob/upload/upl_r?offset=32", puts[0].path)
            assertEquals("/ui/blob/upload/upl_r?offset=48", puts[1].path)
            assertEquals(listOf(48 / 56f, 1.0f), progress, "progress resumes from the bytes actually covered")
        }
    }

    // ---------------------------------------------------------------- failures & abort

    @Test
    fun beginFailureThrowsUploadFailedWithoutAbort() {
        runBlocking {
            val channel = RecordingChannel()
            channel.handler = { _ ->
                channel.error(VadeErrorCodes.PAYLOAD_TOO_LARGE, "file exceeds the configured hard limit")
            }
            val uploader = BlobUploaderReal(channel, { ByteArray(40) })
            val progress = mutableListOf<Float>()
            val failure = runCatching { uploader.upload(attachment(size = 40L), "u-5") { progress.add(it) } }
                .exceptionOrNull()
            assertTrue(failure is UploadFailedException, "the chip-retry shape")
            assertTrue(
                failure.message.orEmpty().contains("PAYLOAD_TOO_LARGE"),
                "the C-04 code travels inside the message (commonMain has no initCause, " +
                    "and UploadFailedException is frozen to a single param): ${failure.message}",
            )
            assertTrue(channel.calls.none { it.method == "DELETE" }, "no server upload id yet → no abort")
            assertTrue(progress.isEmpty(), "no bytes moved, no progress, no 1f without success")
        }
    }

    @Test
    fun completeFailureAbortsAndThrowsUploadFailed() {
        runBlocking {
            val channel = RecordingChannel()
            channel.handler = { request ->
                when {
                    request.method == "POST" && request.path == "/ui/blob" ->
                        channel.beginReply("upl_f", partSize = 65_536, size = 40)
                    request.method == "PUT" -> channel.partReply()
                    request.method == "POST" -> channel.error("PAYLOAD_TOO_LARGE", "recomputed size over the limit")
                    else -> BlobHttpResponse(204, ByteArray(0))
                }
            }
            val uploader = BlobUploaderReal(channel, { ByteArray(40) })
            val progress = mutableListOf<Float>()
            val failure = runCatching { uploader.upload(attachment(size = 40L), "u-6") { progress.add(it) } }
                .exceptionOrNull()
            assertTrue(failure is UploadFailedException)
            assertEquals(
                listOf("POST", "PUT", "POST", "DELETE"),
                channel.calls.map { it.method },
                "the failed upload session is aborted best-effort",
            )
            assertEquals("/ui/blob/upload/upl_f", channel.calls[3].path, "abort targets the server upload id")
            assertTrue(progress.none { it >= 1.0f }, "no 1f without a successful return")
        }
    }

    @Test
    fun cancellationMidPartsAbortsAndRethrowsCancellation() {
        runBlocking {
            val channel = RecordingChannel()
            val gate = CompletableDeferred<Unit>()
            channel.handler = { request ->
                when {
                    request.method == "POST" && request.path == "/ui/blob" ->
                        channel.beginReply("upl_c", partSize = 16, size = 40)
                    request.method == "PUT" -> {
                        gate.await() // the part never completes
                        channel.partReply()
                    }
                    request.method == "POST" -> channel.completeReply("blb_c")
                    else -> BlobHttpResponse(204, ByteArray(0))
                }
            }
            val uploader = BlobUploaderReal(channel, { ByteArray(40) })
            val job = launch(Dispatchers.Default) { uploader.upload(attachment(size = 40L), "u-7") { } }
            waitUntil { channel.calls.any { it.method == "PUT" } }
            job.cancel()
            job.join()
            assertTrue(job.isCancelled)
            assertTrue(
                channel.calls.any { it.method == "DELETE" && it.path == "/ui/blob/upload/upl_c" },
                "cancel must abort the server-side session",
            )
            assertEquals(
                0,
                channel.calls.count { it.method == "POST" && it.path.endsWith("/complete") },
                "a cancelled upload never completes",
            )
        }
    }

    @Test
    fun preflightValidationNeverTouchesChannel() {
        runBlocking {
            val channel = RecordingChannel()
            val uploader = BlobUploaderReal(channel, { ByteArray(0) })
            assertFailsWith<VadeException> { uploader.upload(attachment(name = " ", size = 40L), "u-8") { } }
                .let { assertEquals(VadeErrorCodes.INVALID_ARGUMENT, it.code) }
            assertFailsWith<VadeException> { uploader.upload(attachment(mime = " ", size = 40L), "u-8") { } }
            assertFailsWith<VadeException> { uploader.upload(attachment(size = 0L), "u-8") { } }
            assertFailsWith<VadeException> { uploader.upload(attachment(size = 40L, width = 8, height = 0), "u-8") { } }
            // Declared size vs the actual staged bytes: no packet may leave.
            assertFailsWith<VadeException> { uploader.upload(attachment(size = 41L), "u-8") { } }
                .let { assertEquals(VadeErrorCodes.INVALID_ARGUMENT, it.code) }
            assertEquals(0, channel.calls.size, "a malformed request must never produce a channel call")
        }
    }

    @Test
    fun failedUploadLeavesNoResidueForTheNextAttempt() {
        runBlocking {
            val channel = RecordingChannel()
            var failComplete = true
            channel.handler = { request ->
                when {
                    request.method == "POST" && request.path == "/ui/blob" ->
                        channel.beginReply("upl_x", partSize = 65_536, size = 40)
                    request.method == "PUT" -> channel.partReply()
                    request.method == "POST" ->
                        if (failComplete) {
                            channel.error("PAYLOAD_TOO_LARGE", "over the limit")
                        } else {
                            channel.completeReply("blb_x")
                        }
                    else -> BlobHttpResponse(204, ByteArray(0))
                }
            }
            val uploader = BlobUploaderReal(channel, { ByteArray(40) })
            runCatching { uploader.upload(attachment(size = 40L), "u-9") { } }
            failComplete = false
            val fileId = uploader.upload(attachment(size = 40L), "u-9") { }
            assertEquals("blb_x", fileId, "the retry starts a FRESH session and succeeds")
            val begins = channel.calls.filter { it.method == "POST" && it.path == "/ui/blob" }
            assertEquals(2, begins.size, "no state from the failed attempt leaks into the retry")
        }
    }

    @Test
    fun concurrentUploadsOnOneAdapterDoNotInterfere() {
        runBlocking {
            val channel = RecordingChannel()
            channel.handler = { request ->
                when {
                    request.method == "POST" && request.path == "/ui/blob" -> {
                        val name = (request.jsonBody?.get("name") as? JsonPrimitive)?.content.orEmpty()
                        channel.beginReply("upl_$name", partSize = 65_536, size = 40)
                    }
                    request.method == "PUT" -> channel.partReply()
                    request.method == "POST" -> {
                        val id = request.path.removePrefix("/ui/blob/upload/").removeSuffix("/complete")
                        channel.completeReply("blb_$id")
                    }
                    else -> BlobHttpResponse(204, ByteArray(0))
                }
            }
            val uploader = BlobUploaderReal(channel, { ByteArray(40) { 'x'.code.toByte() } })
            val progressA = Collections.synchronizedList(mutableListOf<Float>())
            val progressB = Collections.synchronizedList(mutableListOf<Float>())
            val a = async(Dispatchers.Default) {
                uploader.upload(attachment(name = "a.txt", size = 40L), "u-a") { progressA.add(it) }
            }
            val b = async(Dispatchers.Default) {
                uploader.upload(attachment(name = "b.txt", size = 40L), "u-b") { progressB.add(it) }
            }
            val fileIdA = a.await()
            val fileIdB = b.await()
            assertEquals("blb_upl_a.txt", fileIdA, "each caller gets its OWN file id")
            assertEquals("blb_upl_b.txt", fileIdB)
            assertEquals(6, channel.calls.size, "2 begins + 2 parts + 2 completes, no cross-talk")
            assertEquals(listOf(1.0f), progressA.toList(), "each progress stream terminates at 1f exactly once")
            assertEquals(listOf(1.0f), progressB.toList())
        }
    }
}
