package vade.ui.chat

import vade.ui.data.epochNowMs

/**
 * `client_msg_id` 的**唯一**铸造点。
 *
 * 形状：`ui-<epochMs>-<seq>` —— 毫秒时间戳 + 同毫秒内的自增序号。三条纪律：
 *
 *  - **绝不从行数 / 下标派生**。旧实现是 `"c-${items.count { … } + 1}"`：删一条排队行、
 *    重生成一次、或者快照行与 live 行交错，都会让两个不同的动作拿到同一个幂等键 ——
 *    core 侧 `ui/submit` 因此把第二次发送当成重放丢掉（或者反过来，两次发送被并成一条）。
 *  - **单调且唯一**：同一毫秒内连发两次，`seq` 自增（`ui-1700000000000-0` /
 *    `-1`）；时钟回拨时保留上一次的毫秒值继续自增，键仍然唯一。
 *  - **编辑 / 重生成 / 发送同源**：三者都只经 [ClientMsgIds.next]，没有第二条铸造路径。
 *
 * 线程模型：[ChatState] 的入口都在 Compose UI 线程（发送、编辑、重生成），本类不做加锁；
 * 若将来从后台线程铸造，需要自己加同步。
 */
class ClientMsgIds(
    /** 注入时钟（测试用固定毫秒钉住形状与递增）。 */
    private val nowMs: () -> Long = { epochNowMs() },
) {

    private var lastMs: Long = 0L
    private var seq: Int = 0

    /** 下一个幂等键；调用一次前进一次，保证任意两次调用返回值不相等。 */
    fun next(): String {
        val now = nowMs()
        if (now > lastMs) {
            lastMs = now
            seq = 0
        } else {
            // 同一毫秒（或时钟回拨）：保留较大的毫秒值继续递增，键仍然唯一且不倒退。
            seq += 1
        }
        return "ui-$lastMs-$seq"
    }
}
