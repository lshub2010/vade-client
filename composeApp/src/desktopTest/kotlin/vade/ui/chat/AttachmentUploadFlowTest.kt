package vade.ui.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import vade.ui.skin.AttachmentState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * v1.1 · 附件上传（可注入 [AttachmentUploader] 通道）的验收项 — 直接驱动 [ChatState]，
 * `runBlocking` 确定性泵（无时钟、无窗口）：
 * ⑦ 进度 0→100% ⑧ 失败重试不重复加 chip ⑨ 取消移除 chip 且迟到事件不复活
 * ⑩ 上传中点发送不带未完成附件。
 */
class AttachmentUploadFlowTest {

    private val image = PendingAttachment("IMG_0042.jpg", "image/jpeg", 1_834_211, width = 2048, height = 1536)
    private val spec = PendingAttachment("spec.pdf", "application/pdf", 902_144)

    // --- doubles -----------------------------------------------------------------

    /** Steps progress `1/N..1` with a suspension point between steps (pump = yield). */
    private class StepUploader(
        private val steps: Int = 4,
        private val pause: suspend () -> Unit = { yield() },
    ) : AttachmentUploader {
        val progressCalls = mutableListOf<Float>()
        override suspend fun upload(request: PendingAttachment, uploadId: String, onProgress: (Float) -> Unit): String {
            repeat(steps) { step ->
                val fraction = (step + 1f) / steps
                onProgress(fraction)
                progressCalls += fraction
                pause()
            }
            return "blb_test_$uploadId"
        }
    }

    /** Fails the first [failures] attempts with an [UploadFailedException], then succeeds. */
    private class FlakyUploader(
        private val failures: Int = 1,
        private val base: AttachmentUploader = StepUploader(steps = 2),
    ) : AttachmentUploader {
        var attempts = 0
        override suspend fun upload(request: PendingAttachment, uploadId: String, onProgress: (Float) -> Unit): String {
            attempts += 1
            if (attempts <= failures) throw UploadFailedException("网络中断")
            return base.upload(request, uploadId, onProgress)
        }
    }

    // ⑦ — the chip walks 0 → 1 through real intermediate values
    @Test
    fun uploadProgressRunsFromZeroToOne(): Unit = runBlocking {
        val uploader = StepUploader(steps = 4)
        val chat = ChatState("s-upload", uploader = uploader)
        val id = assertNotNull(chat.stageUpload(image))

        assertEquals(1, chat.staged.size)
        assertEquals(0f, chat.staged[0].attachment.progress)
        assertEquals(AttachmentState.Uploading, chat.staged[0].attachment.state)
        assertEquals(image.name, chat.staged[0].attachment.fileName)

        val seen = mutableListOf<Float>()
        val job = launch { chat.runUpload(id) }
        while (job.isActive) {
            chat.staged.firstOrNull()?.let { chip ->
                // Skip the initial 0f frame (the child has not started yet); the remaining
                // steps are observed because each fake step yields the pump back.
                if (chip.attachment.state == AttachmentState.Uploading && chip.attachment.progress > 0f) {
                    seen += chip.attachment.progress
                }
            }
            yield()
        }
        job.join()

        assertTrue(seen.isNotEmpty(), "⑦ intermediate progress was observable")
        assertEquals(seen, seen.sorted(), "progress is monotonic")
        assertTrue(seen.first() < 1f, "first observed step is mid-transfer: $seen")
        assertEquals(1f, seen.last(), "the chip reached 100% while still 上传中")
        assertEquals(listOf(0.25f, 0.5f, 0.75f, 1.0f), uploader.progressCalls, "0→100% in steps")
        assertEquals(AttachmentState.Uploaded, chat.staged[0].attachment.state)
        assertEquals(1f, chat.staged[0].attachment.progress)
    }

    @Test
    fun completedUploadBuildsTheBlobPartAndRidesOnSend(): Unit = runBlocking {
        val chat = ChatState("s-upload", uploader = StepUploader(steps = 2))
        val id = assertNotNull(chat.stageUpload(spec))

        chat.runUpload(id)

        val chip = chat.staged.single()
        assertEquals("blb_test_$id", chip.attachment.fileId, "the chip carries the blob id")
        assertEquals(AttachmentState.Uploaded, chip.attachment.state)
        val part = chip.part as ContentPart.File
        assertEquals("blb_test_$id", part.fileId)
        assertEquals(902_144, part.size)
        assertEquals("application/pdf", part.mime)

        chat.stop() // idle, so the submit itself starts the rerun turn
        val rowsBefore = chat.messages.size
        chat.updateDraft("带上 spec")
        assertTrue(chat.canSend)
        assertNotNull(chat.submit())
        val sent = chat.messages.last { it.role == ChatRole.User }
        assertEquals(listOf<ContentPart>(ContentPart.Text("带上 spec"), part), sent.parts)
        assertEquals(0, chat.staged.size, "uploaded chips leave the composer on send")
        assertEquals(rowsBefore + 2, chat.messages.size, "one user bubble + one streaming reply")
        assertTrue(chat.isStreaming)
    }

    @Test
    fun imagePendingBuildsAnImagePartWithTheCompressedDetail(): Unit = runBlocking {
        val chat = ChatState("s-upload", uploader = StepUploader(steps = 1))
        val id = assertNotNull(chat.stageUpload(image))
        assertEquals("1.7 MB · 2048×1536 · 已压缩，原图未保留", chat.staged[0].uploadedDetail)

        chat.runUpload(id)

        val chip = chat.staged.single()
        assertEquals("blb_test_$id", chip.attachment.fileId)
        val part = chip.part as ContentPart.Image
        assertEquals(2048, part.width)
        assertEquals(1536, part.height)
        assertEquals("image/jpeg", part.mime)
        assertEquals(chat.staged[0].uploadedDetail, chip.attachment.detail)
    }

    // ⑧ — the SAME chip retries in place; no second chip ever appears
    @Test
    fun failedUploadRetriesWithoutDuplicatingTheChip(): Unit = runBlocking {
        val uploader = FlakyUploader(failures = 1)
        val chat = ChatState("s-upload", uploader = uploader)
        val id = assertNotNull(chat.stageUpload(spec))

        chat.runUpload(id) // attempt 1 → 失败

        assertEquals(1, chat.staged.size)
        assertEquals(AttachmentState.Failed, chat.staged[0].attachment.state)
        assertEquals("网络中断", chat.staged[0].attachment.detail)

        assertTrue(chat.retryUpload(id))
        assertEquals(1, chat.staged.size, "⑧ 重试复用同一个 chip，不加第二条")
        assertEquals(AttachmentState.Uploading, chat.staged[0].attachment.state)
        assertEquals(0f, chat.staged[0].attachment.progress)
        assertNull(chat.staged[0].attachment.detail)

        chat.runUpload(id) // attempt 2 → 成功
        assertEquals(AttachmentState.Uploaded, chat.staged.single().attachment.state)
        assertEquals(2, uploader.attempts, "exactly two channel attempts")
    }

    // ⑨ — cancel removes the chip at once; the in-flight channel's late events are dropped
    @Test
    fun cancelUploadRemovesTheChipAndLateEventsAreIgnored(): Unit = runBlocking {
        var pauses = 0
        val uploader = StepUploader(steps = 6, pause = { pauses += 1; yield() })
        val chat = ChatState("s-upload", uploader = uploader)
        val id = assertNotNull(chat.stageUpload(image))

        val job = launch { chat.runUpload(id) }
        while (job.isActive && pauses < 2) yield()

        assertTrue(chat.cancelUpload(id), "⑨ 取消上传移除 chip")
        assertEquals(0, chat.staged.size)
        assertFalse(chat.cancelUpload(id), "an already-removed upload cannot be cancelled twice")

        job.join() // the fake keeps stepping; every event must be a no-op
        assertEquals(0, chat.staged.size, "迟到的进度/完成事件不得复活 chip")

        // The pick is free again — a fresh upload of the same file stages cleanly.
        val second = assertNotNull(chat.stageUpload(image))
        assertEquals(1, chat.staged.size)
        chat.runUpload(second)
        assertEquals(AttachmentState.Uploaded, chat.staged.single().attachment.state)
    }

    // ⑩ — sending mid-upload carries no unfinished attachment
    @Test
    fun sendingDuringUploadExcludesTheUnfinishedAttachment(): Unit = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val uploader = StepUploader(steps = 4, pause = { gate.await() })
        val chat = ChatState("s-upload", uploader = uploader)
        val id = assertNotNull(chat.stageUpload(image))

        val job = launch { chat.runUpload(id) }
        yield(); yield() // first progress lands, then the fake blocks on the gate

        assertEquals(AttachmentState.Uploading, chat.staged.single().attachment.state)
        assertFalse(chat.canSend, "an in-flight upload alone is not sendable")
        assertNull(chat.submit(), "upload-only composer sends nothing")

        chat.updateDraft("先发文字")
        assertTrue(chat.canSend)
        assertNotNull(chat.submit())
        val sent = chat.messages.last { it.role == ChatRole.User }
        assertEquals(listOf(ContentPart.Text("先发文字")), sent.parts, "⑩ 未完成附件不随消息发出")
        assertEquals(1, chat.staged.size, "the unfinished chip stays in the composer")
        assertEquals(AttachmentState.Uploading, chat.staged.single().attachment.state)
        assertFalse(chat.canSend, "still nothing sendable after the text went out")

        gate.complete(Unit)
        job.join()
        assertEquals(AttachmentState.Uploaded, chat.staged.single().attachment.state)
        assertTrue(chat.canSend, "once finished, the attachment becomes sendable")
    }

    @Test
    fun duplicatePendingIsNotStagedTwice(): Unit = runBlocking {
        val chat = ChatState("s-upload", uploader = StepUploader(steps = 1))
        val first = assertNotNull(chat.stageUpload(image))

        assertNull(chat.stageUpload(image), "the same unfinished pick stages only once")
        assertEquals(1, chat.staged.size)
        assertNull(chat.stageUpload(image.copy(height = 999)), "identity is name+size, not every field")

        chat.runUpload(first) // finish it
        val again = assertNotNull(chat.stageUpload(image), "a finished chip does not block a fresh pick")
        assertEquals(2, chat.staged.size)
        assertTrue(chat.cancelUpload(again))
        assertTrue(chat.cancelUpload(first))
        assertEquals(0, chat.staged.size)
    }

    @Test
    fun uploadRespectsThePerTurnCap(): Unit = runBlocking {
        val chat = ChatState("s-upload", uploader = StepUploader(steps = 1, pause = {}))
        repeat(ChatState.MAX_PER_TURN) { index ->
            assertNotNull(chat.stageUpload(PendingAttachment("f$index.txt", "text/plain", index.toLong())))
        }
        assertNull(chat.stageUpload(PendingAttachment("overflow.txt", "text/plain", 1)))
        assertTrue(chat.lastNotice!!.contains("最多 ${ChatState.MAX_PER_TURN}"))
    }
}
