package vade.ui.subagent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * C-Activity · the desensitization surface of the 子代理 card (RFC-0012 §5.3): the client
 * only ever receives `result_digest` (sha-256) + `result_chars` + the ≤500-char
 * `result_preview` — never the full text. These tests pin how each of those renders,
 * including the empty cases, without ever fabricating content.
 */
class SubagentPreviewTest {

    /** A run shaped exactly like the `subagent/get` result documents it. */
    private fun run(
        state: SubagentState = SubagentState.COMPLETED,
        preview: String? = "子代理已检索 3 个文件并汇总要点。",
        chars: Int? = 4123,
        digest: String? = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
        errorCode: String? = null,
        durationMs: Long = 12_300,
    ) = SubagentRunView(
        runId = "run_0123456789abcdef0123456789abcdef",
        parentSessionId = "s-parent",
        parentTurnId = "t-1",
        childSessionId = "s-child",
        label = "检索助手",
        state = state,
        createdAt = 1_700_000_000_000,
        finishedAt = 1_700_000_012_300,
        resultDigest = digest,
        resultChars = chars,
        errorCode = errorCode,
        resultPreview = preview,
        durationMs = durationMs,
    )

    // ---------------------------------------------- ⑥ result_preview 为空时

    @Test
    fun nullAndBlankPreviewsBothRenderTheHonestEmpty() {
        for (empty in listOf<String?>(null, "", "   ")) {
            val cell = previewCell(empty, chars = null)
            assertIs<PreviewCell.NoPreview>(cell)
            assertTrue(cell.display().startsWith("无摘要"), "空预览要明说无摘要: ${cell.display()}")
            assertTrue(cell.display().contains("子会话"), "并指回全文所在处: ${cell.display()}")
        }
    }

    @Test
    fun realPreviewCarriesTheProjectionTextVerbatim() {
        val cell = previewCell("检索完成：3 个命中。", chars = 99)
        val text = assertIs<PreviewCell.Text>(cell)
        assertEquals("检索完成：3 个命中。", text.preview, "预览原文呈现，不改写")
        assertEquals(99, text.chars)
        assertEquals("检索完成：3 个命中。", cell.display())
    }

    // ---------------------------------------------- 脱敏说明必须常驻

    @Test
    fun previewOnlyNoteIsExactAndMandatory() {
        // The card renders this constant on EVERY summary card — wording is pinned so a
        // refactor cannot drop or soften the desensitization notice.
        assertEquals("仅预览，全文在子会话", PREVIEW_ONLY_NOTE)
        assertTrue(PREVIEW_ONLY_NOTE.isNotBlank())
    }

    // ---------------------------------------------- result_chars：缺失 ≠ 0

    @Test
    fun charsMissingIsNotShownAsZero() {
        assertEquals("暂无数据", charsCell(null), "长度缺失显示暂无数据，不是 0 字")
    }

    @Test
    fun charsZeroIsRealDataForAnEmptyResult() {
        // cancelled / failed-with-no-partial runs carry sha256("") and result_chars = 0
        // (events.json §subagent.result notes) — that zero is a REAL fact, not a gap.
        assertEquals("全文共 0 字", charsCell(0))
        assertEquals("全文共 1 字", charsCell(1))
        assertEquals("全文共 503 字", charsCell(503))
    }

    // ---------------------------------------------- result_digest：指纹展示

    @Test
    fun digestShowsShortFingerprintNeverTheWholeWall() {
        val full = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
        val shown = digestText(full)
        assertTrue(shown.startsWith("sha256:9f86d081"), shown)
        assertTrue(shown.endsWith("…"), shown)
        assertTrue(shown.length < full.length + 8, "短指纹，不整墙展示: $shown")
    }

    @Test
    fun digestMissingOrShortHandledVerbatim() {
        assertEquals("暂无数据", digestText(null))
        assertEquals("暂无数据", digestText(""))
        assertEquals("sha256:abcd", digestText("abcd"), "≤8 位原样展示，不截断")
    }

    // ---------------------------------------------- 耗时与错误码

    @Test
    fun durationPrefixFollowsTheState() {
        assertEquals("已运行 12.3s", durationText(run(state = SubagentState.RUNNING)))
        assertEquals("已运行 0ms", durationText(run(state = SubagentState.SPAWNED, durationMs = 0)))
        assertEquals("耗时 12.3s", durationText(run(state = SubagentState.COMPLETED)))
        assertEquals("耗时 2m05s", durationText(run(state = SubagentState.FAILED, durationMs = 125_000)))
        assertEquals("耗时 850ms", durationText(run(state = SubagentState.CANCELLED, durationMs = 850)))
    }

    @Test
    fun errorCodeSurfacesOnlyForFailedRunsThatCarryOne() {
        assertEquals("错误码 BUDGET_EXCEEDED", errorCell(run(state = SubagentState.FAILED, errorCode = "BUDGET_EXCEEDED")))
        assertNull(errorCell(run(state = SubagentState.FAILED, errorCode = null)), "failed 但无 code → 不渲染占位")
        assertNull(errorCell(run(state = SubagentState.COMPLETED, errorCode = "BUDGET_EXCEEDED")), "非 failed 状态即使带 code 也不渲染")
        assertNull(errorCell(run(state = SubagentState.CANCELLED, errorCode = null)))
    }

    // ---------------------------------------------- 快照字段逐字保留

    @Test
    fun snapshotKeepsContractFieldsVerbatim() {
        val r = run()
        assertEquals("run_0123456789abcdef0123456789abcdef", r.runId, "run_id 逐字保留，不归一化")
        assertEquals("检索助手", r.label)
        assertEquals(SubagentState.COMPLETED, r.state)
        assertEquals(4123, r.resultChars)
        assertEquals(12_300L, r.durationMs)
        assertEquals("s-child", r.childSessionId)
    }
}
