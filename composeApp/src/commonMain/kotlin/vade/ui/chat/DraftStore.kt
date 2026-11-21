package vade.ui.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import vade.ui.skin.PreferenceStore
import vade.ui.skin.Preferences
import kotlin.concurrent.Volatile

/**
 * **按会话**保存输入框草稿 —— 进程内表 + 可选落盘（[PreferenceStore]，与外观偏好同一个
 * 窄口）。
 *
 * [ChatState] 是「按需创建、离开即销毁」的派生状态（D98），会话页一销毁草稿就跟着没了 ——
 * 点底栏切到别的 tab 再回来，输入框是空的。用户明确要求草稿留住，所以这一小块状态必须
 * 活在 `ChatState` 之外：页面重建时按 `sessionId` 取回，编辑时写回，发送/清空时删掉。
 *
 * 四条规则：
 *  - **按 [save] 的 sessionId 严格分区**：A 会话的草稿绝不会出现在 B 会话的输入框里；
 *  - **空串等同清除**：`save(id, "")` 之后 [load] 返回 `""`，且表里不留空条目；
 *  - **写入防抖 500ms**：每次按键都落盘会把磁盘 / SharedPreferences 当键盘缓冲区用。
 *    编辑立刻更新内存（Compose 读的是内存），落盘等 500ms 没有新编辑才发生；离开输入框
 *    / 页面销毁时调用 [flush]，半句话不会停在防抖窗口里丢掉；
 *  - **读失败 / 写失败一律当「没存过 / 没存上」**（与 `Preferences` 同口径）：草稿丢了
 *    最多是空输入框，不该让页面崩掉。
 *
 * 可注入：`ChatState(drafts = DraftStore())` = 纯内存（测试默认，不摸真实偏好）；
 * `DraftStore(store = InMemoryPreferenceStore())` 可断言落盘；产品默认是进程内单例
 * [shared]，它经 [Preferences] 读当前生效的平台存储（桌面 `java.util.prefs`、Android
 * SharedPreferences、Web localStorage）。
 */
class DraftStore(
    /** `null` = 纯内存（测试 / 演示）；非 null 才落盘。 */
    private val store: PreferenceStore? = null,
    /** 落盘防抖窗口；测试可调小。 */
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    /** 防抖协程的作用域；默认一个进程级 Default 作用域（写盘不占 UI 线程）。 */
    private val scope: CoroutineScope = WriterScope,
) {

    private val drafts = HashMap<String, String>()

    /** 本实例见过的 sessionId —— [clearAll] 用它逐键删掉落盘值。 */
    private val known = HashSet<String>()

    private var pendingJob: Job? = null

    @Volatile
    private var pendingSessionId: String? = null

    @Volatile
    private var pendingText: String = ""

    /**
     * 防抖代际：每次 [save] / [flush] / [clear] 前进一次。延迟到点的协程只在代际仍是
     * 自己那一代时落盘 —— 后一次编辑（或一次 flush）永远赢，旧写入不会把新草稿盖回去。
     */
    @Volatile
    private var pendingGeneration: Long = 0L

    /** 该会话的草稿；没有就是空串（绝不返回 null，composer 不必判空）。 */
    fun load(sessionId: String): String {
        known += sessionId
        drafts[sessionId]?.let { return it }
        val persisted = store?.let { runCatching { it.read(key(sessionId)) }.getOrNull() }.orEmpty()
        if (persisted.isNotEmpty()) drafts[sessionId] = persisted
        return persisted
    }

    /** 写入草稿（内存立刻生效，落盘防抖）；`text` 为空串时等同 [clear]。 */
    fun save(sessionId: String, text: String) {
        if (text.isEmpty()) {
            clear(sessionId)
            return
        }
        known += sessionId
        drafts[sessionId] = text
        if (store == null) return
        pendingGeneration += 1
        val generation = pendingGeneration
        pendingSessionId = sessionId
        pendingText = text
        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(debounceMs)
            writeIfLatest(generation)
        }
    }

    /** 丢掉该会话的草稿（发送成功、用户清空输入框）。其余会话不受影响。 */
    fun clear(sessionId: String) {
        known += sessionId
        drafts.remove(sessionId)
        if (pendingSessionId == sessionId) {
            pendingGeneration += 1
            pendingJob?.cancel()
            pendingJob = null
            pendingSessionId = null
            pendingText = ""
        }
        store?.let { runCatching { it.write(key(sessionId), null) } }
    }

    /** 把防抖窗口里的写入**立刻**落盘（失焦 / 页面销毁）。没有待写内容时是 no-op。 */
    fun flush() {
        val sessionId = pendingSessionId ?: return
        val text = pendingText
        pendingGeneration += 1
        pendingJob?.cancel()
        pendingJob = null
        pendingSessionId = null
        pendingText = ""
        write(sessionId, text)
    }

    /** 只 flush [sessionId] 自己的待写内容（切会话时不必替别的会话写盘）。 */
    fun flush(sessionId: String) {
        if (pendingSessionId == sessionId) flush()
    }

    /** 清空全部会话的草稿（含落盘值）—— 只给测试 / 整机重置用。 */
    fun clearAll() {
        pendingGeneration += 1
        pendingJob?.cancel()
        pendingJob = null
        pendingSessionId = null
        pendingText = ""
        drafts.clear()
        store?.let { target ->
            known.forEach { sessionId -> runCatching { target.write(key(sessionId), null) } }
        }
        known.clear()
    }

    private fun writeIfLatest(generation: Long) {
        if (generation != pendingGeneration) return
        val sessionId = pendingSessionId ?: return
        val text = pendingText
        pendingSessionId = null
        pendingText = ""
        write(sessionId, text)
    }

    private fun write(sessionId: String, text: String) {
        val target = store ?: return
        if (text.isEmpty()) return
        runCatching { target.write(key(sessionId), text) }
    }

    private fun key(sessionId: String): String = "$KEY_PREFIX$sessionId"

    companion object {
        /** 500ms：连续打字时每秒最多一次落盘，停手半秒后草稿就安全了。 */
        const val DEFAULT_DEBOUNCE_MS: Long = 500L

        private const val KEY_PREFIX: String = "vade.draft."

        /**
         * 防抖协程的作用域：进程级、不占 UI 线程。写盘是 fire-and-forget，失败已经由
         * [write] 的 `runCatching` 吞成「没存上」。
         */
        private val WriterScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /**
         * 产品路径的存储：每次读写都经 [Preferences] 取**当前生效**的实现（测试可以换掉
         * `Preferences.store`，这里不缓存旧实例）。
         */
        private object PlatformDraftStore : PreferenceStore {
            override fun read(key: String): String? = Preferences.read(key)
            override fun write(key: String, value: String?) = Preferences.write(key, value)
        }

        /**
         * 进程内单例：`ChatState` 的默认草稿存放处。落盘走平台偏好存储 —— 切 tab 不丢，
         * 重启也不丢。
         */
        val shared: DraftStore = DraftStore(store = PlatformDraftStore)
    }
}
