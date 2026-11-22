package vade.ui.chat

import vade.ui.protocol.InputKind
import vade.ui.protocol.InputOption
import vade.ui.protocol.TurnInputRequiredPayload

/**
 * 一次**尚未作答**的 ask-user 请求（RFC-0008 §6.1）+ 读者在本机填了一半的答案。
 *
 * [request] 是 core 事件原样（`turn.input_required` 的 payload）；[draft] / [selected] 是
 * 纯客户端状态（输入框文字、勾选的 `option.id`），**从不上帧**，真正的答复值由
 * [ChatState.submitInput] 按 `kind` 组装成 `turn/input.value`。
 *
 * 为什么答案分两种形状而不是一个 `Any`：free_text / single_choice 是「一个字符串」，
 * multi_choice 是「一组 option.id」，confirm 是「一个布尔且走两键、不需要选中态」——
 * 三种交互在 UI 上长得不一样，硬塞进一个可空字符串只会让三处各自 if。
 *
 * ## 到期时刻（[expiresAtMs]）为什么在构造时算一次
 *
 * core 的 `expires_at` 是**core 墙钟**的 epoch ms。客户端拿本机墙钟直接比较，设备时钟一快
 * 就会「卡一到就被判超时并摘掉」—— 而 core 那边这张卡还开着（要等它自己的 60–600s 超时），
 * 期间这个回合在 UI 上完全不可答。所以：
 *  - **实时事件**（[fromEvent]）：core 期限若已落在本机时钟的过去，判定为「时钟不可信」，
 *    改用**相对预算** `本机 now + timeout_s`（[relativeDeadline]）。偏差只会让卡在本机多留
 *    一会儿，不会让问题瞬间消失；core 的 `turn.input_resolved` 仍然先到并收尾。
 *  - **快照重建**（[fromSnapshot]）：卡可能是断线期间真的过期了，照 core 的期限判 ——
 *    过期就立刻关（并补一发 `turn/input.cancel`，见 [ChatState.timeoutInput]）。
 *  - `expires_at` 缺失时退到 `created_at + timeout_s`（两者都是可选字段）；两者都没有 =
 *    这张卡不本地超时，只等 `turn.input_resolved`。
 */
data class PendingInput(
    val request: TurnInputRequiredPayload,
    /** free_text 的输入框内容。 */
    val draft: String = "",
    /** 已选中的 `option.id`（按点击顺序；上帧时归一成选项显示顺序）。 */
    val selected: List<String> = emptyList(),
    /**
     * 本机认可的到期时刻（页面时钟 ms）；null = 不本地超时。
     *
     * 由 [fromEvent] / [fromSnapshot] 在**卡出现的那一刻**算定 —— 之后每次读都再拿
     * `expires_at` 跟本机时钟比，就是本文开头那条错误。
     */
    val expiresAtMs: Long? = null,
) {
    val inputId: String get() = request.inputId

    val kind: InputKind get() = request.kindEnum

    val options: List<InputOption> get() = request.options

    fun isSelected(optionId: String): Boolean = optionId in selected

    /**
     * 选项 id 按**显示顺序**（= core 给的 `options[]` 顺序）归一。多选的 wire 值是
     * `string[]`，它的顺序不该由读者点击先后决定 —— 那会让同一组勾选产生两种帧。
     */
    fun selectedInOptionOrder(): List<String> =
        options.map { it.id }.filter { it in selected }

    /**
     * 还剩几秒（向上取整；`0` = 已到期）。[nowMs] 由页面时钟传入，测试可钉死。
     * 没有可用期限时返回 null —— 那就不画倒计时，也**不**本地超时关闭。
     */
    fun remainingSeconds(nowMs: Long): Int? {
        val deadline = expiresAtMs ?: return null
        val left = deadline - nowMs
        if (left <= 0L) return 0
        return ((left + 999L) / 1000L).toInt()
    }

    /** 到期判定与 [remainingSeconds] 同一份 [expiresAtMs]（超时 = 未作答，不是默认值）。 */
    fun isExpired(nowMs: Long): Boolean = expiresAtMs?.let { nowMs >= it } ?: false

    companion object {
        /**
         * 实时 `turn.input_required` → 一张卡。core 期限已在本机时钟的过去 ⇒ 视为时钟偏差，
         * 改用相对预算（见类 KDoc）。
         */
        fun fromEvent(payload: TurnInputRequiredPayload, nowMs: Long): PendingInput = PendingInput(
            request = payload,
            expiresAtMs = resolveDeadline(payload, nowMs, clampToRelative = true),
        )

        /**
         * `ui/attach` 快照里的 `pending_inputs` → 一张卡。**不**做时钟偏差兜底：这张卡可能
         * 真的在断线期间过期了，过期就该立刻关（[ChatState] 会补一发幂等 cancel）。
         */
        fun fromSnapshot(payload: TurnInputRequiredPayload, nowMs: Long): PendingInput = PendingInput(
            request = payload,
            expiresAtMs = resolveDeadline(payload, nowMs, clampToRelative = false),
        )

        /**
         * core 期限的两种来源：`expires_at` 优先，缺失时用 `created_at + timeout_s`。
         * 两者都没有 ⇒ null（不本地超时）。
         */
        internal fun coreDeadlineMs(payload: TurnInputRequiredPayload): Long? =
            payload.expiresAt
                ?: payload.createdAt?.let { created ->
                    payload.timeoutS?.let { seconds -> created + seconds * 1_000L }
                }

        /** `timeout_s` 的相对预算（没有该字段 / 非正数 ⇒ null）。 */
        internal fun relativeDeadline(payload: TurnInputRequiredPayload, nowMs: Long): Long? =
            payload.timeoutS?.takeIf { it > 0 }?.let { seconds -> nowMs + seconds * 1_000L }

        internal fun resolveDeadline(
            payload: TurnInputRequiredPayload,
            nowMs: Long,
            clampToRelative: Boolean,
        ): Long? {
            val core = coreDeadlineMs(payload) ?: return null
            if (!clampToRelative || core > nowMs) return core
            // 时钟不可信：从本机看到它的这一刻起算一整段 timeout_s，而不是立刻判死。
            return relativeDeadline(payload, nowMs) ?: core
        }
    }
}
