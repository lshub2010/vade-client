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
 */
data class PendingInput(
    val request: TurnInputRequiredPayload,
    /** free_text 的输入框内容。 */
    val draft: String = "",
    /** 已选中的 `option.id`（按点击顺序；上帧时归一成选项显示顺序）。 */
    val selected: List<String> = emptyList(),
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
     * `expires_at` 还剩几秒（向上取整；`0` = 已到期）。[nowMs] 由页面时钟传入，测试可钉死。
     * 契约里没有 `expires_at` 时返回 null —— 那就不画倒计时，也**不**本地超时关闭。
     */
    fun remainingSeconds(nowMs: Long): Int? {
        val expiresAt = request.expiresAt ?: return null
        val left = expiresAt - nowMs
        if (left <= 0L) return 0
        return ((left + 999L) / 1000L).toInt()
    }

    /** 到期判定与 [remainingSeconds] 同一份 `expires_at`（超时 = 未作答，不是默认值）。 */
    fun isExpired(nowMs: Long): Boolean =
        request.expiresAt?.let { nowMs >= it } ?: false
}
