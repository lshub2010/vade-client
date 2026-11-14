package vade.ui.chat

import kotlinx.coroutines.yield

/**
 * A file the user picked for the composer. Bytes stay abstract — this slice reads **no**
 * file IO (no picker, no compression): the real picker and byte source are the shell's
 * seam (RESULT.md · 未接线清单). Name / mime / size are what the chip shows; non-null
 * `width`/`height` ⇒ the message carries an [ContentPart.Image], otherwise a
 * [ContentPart.File].
 */
data class PendingAttachment(
    val name: String,
    val mime: String,
    val sizeBytes: Long,
    val width: Int? = null,
    val height: Int? = null,
) {
    /** File identity for de-dup: the same pick staged twice adds nothing (不重复加 chip). */
    val identity: String get() = "$name\n$sizeBytes"
}

/** The only failure shape `ChatState.runUpload` turns into a retryable chip. */
class UploadFailedException(message: String) : Exception(message)

/**
 * 附件上传的**可注入窄接口** — THE seam the real blob channel implements
 * (RESULT.md · 未接线清单): `POST /ui/blob` + `POST /ui/blob/{id}/complete` become one
 * implementation of this interface passed into `ChatState(uploader = …)`; no chat view or
 * state file changes.
 *
 * Contract:
 *  - report progress `0f..1f` on [onProgress] as bytes move (monotonic; `1f` only right
 *    before a successful return);
 *  - return the assigned blob id on success — `ChatState` builds the `ContentPart` from
 *    the pending + blob id, the channel never touches view models;
 *  - throw [UploadFailedException] on failure — the chip becomes 可重试;
 *  - be cancellation-cooperative: the caller cancels the coroutine to abort
 *    (上传中可取消 = cancel the job, not a flag).
 */
fun interface AttachmentUploader {
    suspend fun upload(
        request: PendingAttachment,
        uploadId: String,
        onProgress: (Float) -> Unit,
    ): String
}

/**
 * 本轮的内存假实现 (in-memory fake, no blob store). Walks [steps] progress ticks
 * separated by [pause] — a suspension point so the live composer animates between ticks
 * (`yield()`) and the desktopTest pumps the same code deterministically from `runBlocking`.
 * The "blob id" is a stable fake (`blb_mem_<uploadId>`); it satisfies the field shape, not
 * a store.
 */
class InMemoryAttachmentUploader(
    private val steps: Int = 5,
    private val pause: suspend () -> Unit = { yield() },
) : AttachmentUploader {

    override suspend fun upload(
        request: PendingAttachment,
        uploadId: String,
        onProgress: (Float) -> Unit,
    ): String {
        repeat(steps) { step ->
            onProgress((step + 1f) / steps)
            pause()
        }
        return "blb_mem_$uploadId"
    }
}
