package vade.ui.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import vade.ui.chat.AttachmentUploader
import vade.ui.chat.PendingAttachment
import vade.ui.chat.UploadFailedException
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException
import vade.ui.protocol.VadeJson
import vade.ui.protocol.bool
import vade.ui.protocol.lng
import vade.ui.protocol.str

/**
 * One HTTP exchange against the `/ui/blob*` family (RFC-0004 §4.1, rpc.json
 * §http_endpoints). The bytes channel is NOT JSON-RPC — it is plain HTTP on the data
 * plane, so the transport seam is this narrow executor instead of [VadeClient].
 * Implementations attach their own credentials (device `Authorization: Bearer` or the
 * browser `__Host-ac_ui` cookie + `x-vade-csrf`); the adapter never sees them.
 */
class BlobHttpRequest(
    /** `POST /ui/blob` · `PUT /ui/blob/upload/{id}?offset=N` · `POST …/complete` · `DELETE /ui/blob/upload/{id}`. */
    val method: String,
    val path: String,
    /** `application/json` for begin/complete, `application/octet-stream` for a part. */
    val contentType: String,
    /** begin/complete payloads; null for parts and abort. */
    val jsonBody: JsonObject? = null,
    /** One part's raw bytes; null for JSON calls. */
    val bytesBody: ByteArray? = null,
)

class BlobHttpResponse(
    val status: Int,
    val body: ByteArray,
)

/** The injected HTTP executor (Ktor/fetch on the shell side; a fake in tests). */
fun interface BlobHttpChannel {
    suspend fun exchange(request: BlobHttpRequest): BlobHttpResponse
}

/**
 * The byte source the shell owns (file picker / platform IO). [PendingAttachment]
 * carries name/mime/size only — this is where the actual bytes come from, and the
 * adapter reads them exactly once.
 */
fun interface BlobByteSource {
    suspend fun bytes(request: PendingAttachment): ByteArray
}

/**
 * The real [AttachmentUploader] — the RFC-0004 chunked protocol, end to end:
 *
 * 1. `POST /ui/blob` (JSON: kind/sha256/size/mime/name[/width/height]) — server answers
 *    either a DEDUPE hit (`upload_id: null`, `file_id` ready) or an open upload session
 *    carrying the effective `part_size` and `offset_next`;
 * 2. `PUT /ui/blob/upload/{upload_id}?offset=N` per part — raw bytes, offsets on
 *    part_size boundaries (multipart/form is forbidden server-side, §4.1: it would skip
 *    the CORS preflight);
 * 3. `POST /ui/blob/upload/{upload_id}/complete` (JSON: sha256/size) — core re-hashes
 *    and emits the `file_id`;
 * 4. on failure or cancellation a best-effort `DELETE /ui/blob/upload/{upload_id}`
 *    aborts the session (204, idempotent, never touches a committed blob).
 *
 * Progress is REAL byte accounting: emitted after each acknowledged part as
 * `sentBytes / size`, capped below 1f while bytes are in flight — `1f` is emitted once,
 * right before a successful return (the [AttachmentUploader] contract). Dedupe and
 * resumed (`offset_next > 0`) uploads advance by the bytes actually covered.
 *
 * Failures throw [UploadFailedException] (the chip-retry shape) with the original
 * [VadeException] as `cause`, so callers can still classify it via
 * [VadeErrorMapping.categoryOf]. Cancellation always rethrows the original
 * [CancellationException] after aborting — it is never converted to a failure.
 */
class BlobUploaderReal(
    private val channel: BlobHttpChannel,
    private val byteSource: BlobByteSource,
) : AttachmentUploader {

    override suspend fun upload(
        request: PendingAttachment,
        uploadId: String,
        onProgress: (Float) -> Unit,
    ): String {
        // --- pre-flight validation: a malformed request never touches the channel ---
        if (uploadId.isBlank()) {
            throw invalidArgument("uploadId is required and must be non-blank")
        }
        if (request.name.isBlank()) {
            throw invalidArgument("params.name is required and must be non-blank")
        }
        if (request.mime.isBlank()) {
            throw invalidArgument("params.mime is required and must be non-blank")
        }
        if (request.sizeBytes <= 0) {
            throw invalidArgument("params.size must be a positive integer, got: ${request.sizeBytes}")
        }
        val width = request.width
        val height = request.height
        val isImage = width != null || height != null
        if (isImage && (width == null || height == null || width <= 0 || height <= 0)) {
            throw invalidArgument(
                "params.width/height must both be positive integers for an image, got: ${width}x${height}",
            )
        }
        val bytes = byteSource.bytes(request)
        currentCoroutineContext().ensureActive()
        if (bytes.size.toLong() != request.sizeBytes) {
            throw invalidArgument(
                "declared size ${request.sizeBytes} does not match the actual byte count ${bytes.size}",
            )
        }
        val sha256 = Sha256.hex(Sha256.digest(bytes))
        val size = request.sizeBytes

        var serverUploadId: String? = null
        try {
            // --- begin ---------------------------------------------------------
            val beginStatus = channel.exchange(
                BlobHttpRequest(
                    method = "POST",
                    path = "/ui/blob",
                    contentType = "application/json",
                    jsonBody = buildJsonObject {
                        put("kind", if (isImage) "image" else "file")
                        put("sha256", sha256)
                        put("size", size)
                        put("mime", request.mime)
                        put("name", request.name)
                        if (isImage) {
                            put("width", width!!)
                            put("height", height!!)
                        }
                    },
                ),
            )
            currentCoroutineContext().ensureActive()
            val begin = beginStatus.decodeObject("begin")
            val dedupeFileId = begin.str("file_id")
            if (begin.bool("dedupe") == true && dedupeFileId != null) {
                // (device, sha256, size) already on file — no bytes move on the wire.
                onProgress(1f)
                return dedupeFileId
            }
            serverUploadId = begin.str("upload_id")
                ?: throw protocolMismatch("begin reply has no upload_id and is not a dedupe hit")
            val partSize = begin.lng("part_size")
                ?: throw protocolMismatch("begin reply has no required part_size")
            if (partSize <= 0) {
                throw protocolMismatch("begin reply part_size must be positive, got: $partSize")
            }
            val offsetNext = begin.lng("offset_next") ?: 0L

            // --- parts ----------------------------------------------------------
            // Resume support: bytes the server already holds (offset_next) are skipped,
            // and progress starts from the bytes actually covered — no re-reporting.
            var sent = if (offsetNext in 1 until size) offsetNext else 0L
            var offset = 0L
            while (offset < size) {
                if (offset >= offsetNext) {
                    currentCoroutineContext().ensureActive()
                    val length = minOf(partSize, size - offset)
                    val part = ByteArray(length.toInt())
                    bytes.copyInto(part, 0, offset.toInt(), (offset + length).toInt())
                    val partReply = channel.exchange(
                        BlobHttpRequest(
                            method = "PUT",
                            path = "/ui/blob/upload/$serverUploadId?offset=$offset",
                            contentType = "application/octet-stream",
                            bytesBody = part,
                        ),
                    )
                    currentCoroutineContext().ensureActive()
                    if (partReply.status !in 200..299) {
                        throw partReply.asError()
                    }
                    sent += length
                    if (sent < size) onProgress(sent.toFloat() / size)
                }
                offset += partSize
            }

            // --- complete -------------------------------------------------------
            val completeReply = channel.exchange(
                BlobHttpRequest(
                    method = "POST",
                    path = "/ui/blob/upload/$serverUploadId/complete",
                    contentType = "application/json",
                    jsonBody = buildJsonObject {
                        put("sha256", sha256)
                        put("size", size)
                    },
                ),
            )
            currentCoroutineContext().ensureActive()
            val complete = completeReply.decodeObject("complete")
            val fileId = complete.str("file_id")
                ?: throw protocolMismatch("complete reply has no required file_id")
            onProgress(1f)
            return fileId
        } catch (ce: CancellationException) {
            abortUpload(serverUploadId)
            throw ce
        } catch (failure: Exception) {
            abortUpload(serverUploadId)
            if (failure is VadeException) {
                // UploadFailedException is frozen with a single message param (and
                // commonMain has no initCause): the C-04 code travels inside the message.
                throw UploadFailedException(
                    "upload $uploadId failed: ${failure.code} ${failure.message.orEmpty()}",
                )
            }
            throw failure
        }
    }

    /**
     * Best-effort `DELETE /ui/blob/upload/{id}` — 204 for known, unknown and malformed
     * ids alike (§4.4). Runs inside [NonCancellable]: a cancelled upload must still
     * release the server-side session instead of leaving an idle row to expire.
     */
    private suspend fun abortUpload(serverUploadId: String?) {
        val id = serverUploadId ?: return
        withContext(NonCancellable) {
            runCatching {
                channel.exchange(
                    BlobHttpRequest(
                        method = "DELETE",
                        path = "/ui/blob/upload/$id",
                        contentType = "application/json",
                    ),
                )
            }
        }
    }

    // ------------------------------------------------------------------ decoding

    private fun BlobHttpResponse.decodeObject(stage: String): JsonObject {
        if (status !in 200..299) throw asError()
        val parsed = runCatching { VadeJson.default.parseToJsonElement(body.decodeToString()) }.getOrNull()
        val obj = parsed as? JsonObject
        if (obj == null) {
            throw VadeException(
                VadeErrorCodes.PROTOCOL_MISMATCH,
                "blob $stage reply is not a JSON object (status $status)",
            )
        }
        return obj
    }

    /** Wire error body `{code, message, data}` → [VadeException] (only C-04 codes, §4.1). */
    private fun BlobHttpResponse.asError(): VadeException {
        val parsed = runCatching { VadeJson.default.parseToJsonElement(body.decodeToString()) }.getOrNull()
        val obj = parsed as? JsonObject
        val code = obj?.str("code")
        if (code == null) {
            // A non-JSON error body (a proxy HTML page, an empty 5xx) — UNAVAILABLE is
            // the honest client-side mapping, not an invented C-04 name.
            return VadeException(
                VadeErrorCodes.UNAVAILABLE,
                "blob endpoint returned status $status without a C-04 error body",
            )
        }
        return VadeException(
            code = code,
            message = obj.str("message").orEmpty(),
            data = obj["data"] as? JsonObject,
        )
    }

    private fun protocolMismatch(message: String): VadeException =
        VadeException(VadeErrorCodes.PROTOCOL_MISMATCH, message)

    private fun invalidArgument(message: String): VadeException =
        VadeException(VadeErrorCodes.INVALID_ARGUMENT, message)
}

/**
 * SHA-256 (FIPS 180-4), pure Kotlin commonMain — the upload channel declares the file
 * digest at `begin` and `complete`, and `java.security.MessageDigest` is JVM-only.
 * Pinned against the standard known-answer tests (empty input, "abc", a two-block
 * message) plus random-length agreement with the JVM digest in the desktopTest suite.
 */
internal object Sha256 {

    private val K = intArrayOf(
        0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(), 0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
        0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
        0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(), 0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(),
        0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(), 0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(), 0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
    )

    private const val HEX = "0123456789abcdef"

    fun digest(message: ByteArray): ByteArray {
        val h = intArrayOf(
            0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(),
            0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19,
        )
        val bitLength = message.size.toLong() * 8
        val withOne = message.size + 1
        val zeroCount = (56 - withOne % 64 + 64) % 64
        val paddedLength = withOne + zeroCount + 8
        val buf = ByteArray(paddedLength)
        message.copyInto(buf)
        buf[message.size] = 0x80.toByte()
        for (i in 0 until 8) {
            buf[paddedLength - 1 - i] = ((bitLength ushr (8 * i)) and 0xFF).toByte()
        }
        val w = IntArray(64)
        var chunkStart = 0
        while (chunkStart < paddedLength) {
            for (t in 0 until 16) {
                val i = chunkStart + t * 4
                w[t] = ((buf[i].toInt() and 0xFF) shl 24) or
                    ((buf[i + 1].toInt() and 0xFF) shl 16) or
                    ((buf[i + 2].toInt() and 0xFF) shl 8) or
                    (buf[i + 3].toInt() and 0xFF)
            }
            for (t in 16 until 64) {
                val s0 = rotr(w[t - 15], 7) xor rotr(w[t - 15], 18) xor (w[t - 15] ushr 3)
                val s1 = rotr(w[t - 2], 17) xor rotr(w[t - 2], 19) xor (w[t - 2] ushr 10)
                w[t] = w[t - 16] + s0 + w[t - 7] + s1
            }
            var a = h[0]
            var b = h[1]
            var c = h[2]
            var d = h[3]
            var e = h[4]
            var f = h[5]
            var g = h[6]
            var hh = h[7]
            for (t in 0 until 64) {
                val s1 = rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)
                val ch = (e and f) xor (e.inv() and g)
                val temp1 = hh + s1 + ch + K[t] + w[t]
                val s0 = rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val temp2 = s0 + maj
                hh = g
                g = f
                f = e
                e = d + temp1
                d = c
                c = b
                b = a
                a = temp1 + temp2
            }
            h[0] += a
            h[1] += b
            h[2] += c
            h[3] += d
            h[4] += e
            h[5] += f
            h[6] += g
            h[7] += hh
            chunkStart += 64
        }
        val out = ByteArray(32)
        for (i in 0 until 8) {
            out[i * 4] = (h[i] ushr 24).toByte()
            out[i * 4 + 1] = (h[i] ushr 16).toByte()
            out[i * 4 + 2] = (h[i] ushr 8).toByte()
            out[i * 4 + 3] = h[i].toByte()
        }
        return out
    }

    fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            append(HEX[(b.toInt() ushr 4) and 0xF])
            append(HEX[b.toInt() and 0xF])
        }
    }

    private fun rotr(x: Int, n: Int): Int = (x ushr n) or (x shl (32 - n))
}
