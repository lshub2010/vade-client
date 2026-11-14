package vade.ui.chat

/**
 * U1-b · fake thread data. **No core, no RPC, no file bytes.**
 *
 * The fixtures exist to exercise every visual state the acceptance list names, and their
 * field names are the RFC ones (`parts[]`, `args_preview`, `duration_ms`, `batch_id`,
 * `partial_chars`, `queue_pos`) so that swapping in `ProtocolClient` is a source change, not
 * a view change. Nothing here claims a connection: there is no 「已连接」 badge and no fake
 * seq number.
 *
 * Thread layout (deliberately one of each):
 *   system notice → user text → assistant markdown reply (with a fenced block + list)
 *   → user message with an image + a PDF → two tool cards in one `batch_id` (one success,
 *   one failure → auto-expanded) → assistant text → a `risk=high` approval card → a
 *   streaming assistant message.
 */
object ChatFixtures {

    private const val IMG_ID = "blb_01H8ZK4Q7M3XW9V2"

    private val screenshot = ContentPart.Image(
        fileId = IMG_ID,
        name = "IMG_0042.jpg",
        size = 1_834_211,
        width = 2048,
        height = 1536,
        mime = "image/jpeg",
    )

    private val spec = ContentPart.File(
        fileId = "blb_01H8ZK5A1B7CD3EF",
        name = "spec.pdf",
        size = 902_144,
        mime = "application/pdf",
    )

    /** The markdown the subset must render: bold / italic / code / fence / list / link. */
    val markdownReply: String = """
        先说结论：**这张图里没有 OOM**，是 `AGP` 的构建缓存目录被锁住了。

        我按顺序查了三处：

        1. `~/.gradle/caches/modules-2` 的锁文件残留
        2. 守护进程持有 `build/` 句柄（*这次就是它*）
        3. `gradle.properties` 里的 `org.gradle.parallel` 与 daemon 争用

        复现命令：

        ```bash
        gradle --stop && rm -rf build .gradle
        gradle :composeApp:assembleDebug --no-build-cache
        ```

        细节见 [RFC-0004 §6.1](https://github.com/lshub2010/Vade/blob/debug/docs/rfc/RFC-0004-attachments-multimodal.md)，
        以及 `ui/README.md` 的构建矩阵。**不要**直接删 `~/.gradle`。
    """.trimIndent()

    /** Deliberately unfinished: the tail is mid-`**` and mid-fence, which must not break. */
    val streamingReply: String = """
        我再看一下 `:composeApp:desktopMainClasses` 的输出——刚才那一步
        **没有报错**，但产物时间戳没变，说明任务被 `UP-TO-DATE` 了。

        ```kotlin
        tasks.register("printRuntimeClasspath") {
            doLast { println("…") }
        ```
    """.trimIndent()

    val streamScript: List<String> = listOf(markdownReply, streamingReply)

    private val userAsk = "这张截图里的报错是什么意思？我改了 `gradle.properties` 还是不行。"

    private fun thread(sessionId: String): List<ChatItem> = listOf(
        ChatItem.Message(
            ChatMessage(
                id = "$sessionId-sys",
                role = ChatRole.System,
                parts = listOf(ContentPart.Text("本会话使用假数据（U1-b 未接 core）。工具卡与流式为本地模拟。")),
                state = MessageState.Completed,
            ),
        ),
        ChatItem.Message(
            ChatMessage(
                id = "$sessionId-u1",
                role = ChatRole.User,
                parts = listOf(ContentPart.Text(userAsk)),
                clientMsgId = "c-1",
            ),
        ),
        ChatItem.Message(
            ChatMessage(
                id = "$sessionId-a1",
                role = ChatRole.Assistant,
                parts = listOf(ContentPart.Text(markdownReply)),
            ),
        ),
        ChatItem.Message(
            ChatMessage(
                id = "$sessionId-u2",
                role = ChatRole.User,
                parts = listOf(ContentPart.Text("顺便看下这张图和附件里的 spec。"), screenshot, spec),
                badges = listOf(COMPRESSED_NOTE),
                clientMsgId = "c-2",
            ),
        ),
        // Same `batch_id`, same `started_at` — RFC-0006 §5.3: that means "one model step",
        // NOT "parallel". The card only says 「同批 2 个」.
        ChatItem.Tool(
            ToolCall(
                id = "tc-1",
                turnId = "t-2",
                name = "fs.read",
                argsPreview = "{\"path\":\"ui/README.md\",\"lines\":40}",
                argsPreviewMode = "redacted",
                startedAt = "2026-11-14T10:04:11.482Z",
                batchId = "bat_7f3a9c01d2b45e88",
                risk = "low",
                state = ToolRunState.Succeeded,
                result = ToolResult(
                    ok = true,
                    summary = "读取 40 行，1.8 KB",
                    durationMs = 42,
                ),
            ),
        ),
        ChatItem.Tool(
            ToolCall(
                id = "tc-2",
                turnId = "t-2",
                name = "shell.exec",
                argsPreview = "{\"cmd\":\"gradle --stop\",\"cwd\":\"D:/HermesWorkspace/Vade-client\"}",
                argsPreviewMode = "redacted",
                argsPreviewRedacted = listOf("cmd"),
                startedAt = "2026-11-14T10:04:11.482Z",
                batchId = "bat_7f3a9c01d2b45e88",
                risk = "medium",
                state = ToolRunState.Failed,
                result = ToolResult(
                    ok = false,
                    summary = "exit 1 · No daemon running in this directory",
                    durationMs = 812,
                    code = "EXIT_NONZERO",
                ),
            ),
        ),
        ChatItem.Message(
            ChatMessage(
                id = "$sessionId-a2",
                role = ChatRole.Assistant,
                parts = listOf(
                    ContentPart.Text(
                        "第二条命令失败了（守护进程本来就没在跑），这不影响结论。" +
                            "图片只保留压缩版：长边 2048、1.7 MB，**原图未保留**。",
                    ),
                ),
            ),
        ),
        // `risk=high` → PendingApproval → D94: the card is always expanded, never collapsible.
        ChatItem.Tool(
            ToolCall(
                id = "tc-3",
                turnId = "t-2",
                name = "fs.write",
                argsPreview = "{\"path\":\"gradle.properties\",\"bytes\":214}",
                argsPreviewMode = "typed",
                argsPreviewRedacted = listOf("content"),
                argsPreviewTruncated = true,
                startedAt = "2026-11-14T10:04:12.901Z",
                batchId = "bat_8a1b2c3d4e5f6071",
                risk = "high",
                state = ToolRunState.PendingApproval,
                result = null,
            ),
        ),
        ChatItem.Message(
            ChatMessage(
                id = "$sessionId-a3",
                role = ChatRole.Assistant,
                // Mid-stream: `ChatState` adopts this row as the live turn and continues it, so
                // the first frame already shows a partially written (and deliberately broken)
                // markdown body — the state a real reply spends most of its life in.
                parts = listOf(ContentPart.Text(streamingReply.take(86))),
                state = MessageState.Running,
            ),
        ),
    )

    private val cache = mutableMapOf<String, ChatThread>()

    fun threadFor(sessionId: String): ChatThread =
        cache.getOrPut(sessionId) { ChatThread(items = thread(sessionId)) }

    /** Fixtures the composer's 「+ 附件」 button cycles through (no picker, no bytes). */
    private val stagedFixtures = listOf(
        StagedAttachment(
            attachment = vade.ui.skin.AttachmentView(
                fileId = IMG_ID,
                fileName = "IMG_0042.jpg",
                mime = "image/jpeg",
                state = vade.ui.skin.AttachmentState.Uploading,
                progress = 0f,
            ),
            part = screenshot,
            uploadedDetail = "1.7 MB · 2048×1536 · $COMPRESSED_NOTE",
        ),
        StagedAttachment(
            attachment = vade.ui.skin.AttachmentView(
                fileId = spec.fileId,
                fileName = spec.name,
                mime = spec.mime,
                state = vade.ui.skin.AttachmentState.Uploading,
                progress = 0f,
            ),
            part = spec,
            uploadedDetail = "881 KB",
        ),
        StagedAttachment(
            attachment = vade.ui.skin.AttachmentView(
                fileId = "blb_01H8ZK6B2C8DE4FG",
                fileName = "trace.txt",
                mime = "text/plain",
                state = vade.ui.skin.AttachmentState.Failed,
                detail = "上传中断 · 可重试（同 sha256 不重复上传）",
            ),
            part = ContentPart.File(
                fileId = "blb_01H8ZK6B2C8DE4FG",
                name = "trace.txt",
                size = 14_336,
                mime = "text/plain",
            ),
            uploadedDetail = "14 KB",
        ),
    )

    fun nextStaged(index: Int): StagedAttachment = stagedFixtures[index % stagedFixtures.size]

    /** The image the fullscreen preview opens — same object the chip and the bubble use. */
    val previewImage: ContentPart.Image get() = screenshot
}

/** Seed for one session: rows + the text the fake turn streams back. */
data class ChatThread(val items: List<ChatItem>)
