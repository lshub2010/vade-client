package vade.ui.chat

/**
 * 进程内 · **按会话**保存输入框草稿。
 *
 * [ChatState] 是「按需创建、离开即销毁」的派生状态（D98），会话页一销毁草稿就跟着没了 ——
 * 点底栏切到别的 tab 再回来，输入框是空的。用户明确要求草稿留住，所以这一小块状态必须
 * 活在 `ChatState` 之外：页面重建时按 `sessionId` 取回，编辑时写回，发送/清空时删掉。
 *
 * 三条规则：
 *  - **进程内，不落盘**。这是「切 tab 不丢」，不是「重启不丢」—— 关掉应用草稿就没了，
 *    与 D98「客户端状态可丢」的口径一致（真要跨重启，换一个实现即可，接口不变）。
 *  - **按 [save] 的 sessionId 严格分区**：A 会话的草稿绝不会出现在 B 会话的输入框里。
 *  - **空串等同清除**：`save(id, "")` 之后 [load] 返回 `""`，且表里不留空条目。
 *
 * 可注入：`ChatState(drafts = DraftStore())` 给测试一份隔离的表；默认参数是进程内单例
 * [shared]，也就是产品路径（会话页重建后草稿仍在）。
 *
 * 线程模型：Compose UI 单线程访问（编辑/发送都在主线程），所以内部就是普通 [HashMap]；
 * 若将来有后台线程写草稿，这里要换成并发容器。
 */
class DraftStore {

    private val drafts = HashMap<String, String>()

    /** 该会话的草稿；没有就是空串（绝不返回 null，composer 不必判空）。 */
    fun load(sessionId: String): String = drafts[sessionId] ?: ""

    /** 写入草稿；`text` 为空串时等同 [clear]（不留空条目）。 */
    fun save(sessionId: String, text: String) {
        if (text.isEmpty()) {
            drafts.remove(sessionId)
        } else {
            drafts[sessionId] = text
        }
    }

    /** 丢掉该会话的草稿（发送成功、用户清空输入框）。其余会话不受影响。 */
    fun clear(sessionId: String) {
        drafts.remove(sessionId)
    }

    /** 清空全部会话的草稿 —— 只给测试/整机重置用，正常流程走 [clear]。 */
    fun clearAll() {
        drafts.clear()
    }

    companion object {
        /** 进程内单例：`ChatState` 的默认草稿存放处。 */
        val shared: DraftStore = DraftStore()
    }
}
