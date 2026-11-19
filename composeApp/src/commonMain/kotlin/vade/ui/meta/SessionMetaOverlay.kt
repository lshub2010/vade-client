package vade.ui.meta

import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import vade.ui.data.AdapterProvider
import vade.ui.data.SessionGroup
import vade.ui.data.SessionSummary
import vade.ui.data.bucketFor
import vade.ui.data.epochNowMs
import vade.ui.data.sessionGroups
import kotlin.math.roundToInt

/**
 * B2（s-meta-host）· 把 `SessionMetaState` 的菜单/弹层/失败横条**真正挂到壳层的会话列表上**。
 *
 * ## 为什么不是把 `SessionMetaHost` 整个换上去
 * `SessionMetaHost` 自带一套列表渲染（分组卡 + 行），换上去等于重画列表 —— 而
 * V3 拍板的行形状（标题 + 组色点、无预览、无分隔线）、D8 的选中左条都在
 * `SessionsScreen` 的行里，且 `tmp/g-route-transition` 正在改那个文件。所以本棒
 * **只挂菜单与弹层，不动列表本体**：行还是 `SessionsScreen` 画，长按通过一个
 * `onLongPress` 回调交到这里，动作走 [meta]（真适配器 = `adapters.meta(id)` 的
 * `MetaActionsReal`；假源 = `FakeMetaActions`）。
 *
 * ## 乐观更新为什么能「看得见」
 * 权威行（`UiState.groups()` 的 `SessionSummary`）与元数据状态机（`MetaSession`）
 * 是两份数据。挂接靠**投影**（[projectGroups]）：把状态机里的乐观值
 * （title / pinned / archived / groupId）覆盖到权威行上再交给 `SessionsScreen`，
 * 时间桶在 pinned 变化时按 `bucketFor` 重算。于是：
 *  - 动作先改状态机（乐观）→ 下一帧列表就变 —— 「点了没反应」消失；
 *  - RPC 失败 → 状态机整表回滚 → 投影跟着回原位，[meta] 的 `actionError`
 *    顶出可见横条 —— 不静默、不假成功；
 *  - 成功 → [onRefreshList]（壳层的 `retrySessions`）拉权威列表收敛。
 *
 * ## 权威同步只走「变更采纳」（不 clobber 乐观值）
 * [syncFrom] 对状态机里**已知**的行：只有当权威行与「上次同步快照」不同
 * （外部写入 / 我方动作成功后的确认回读）才采纳进状态机；权威没变的行一律不动
 * —— 事件刷新落在 RPC 在飞窗口里时权威还是旧值，采纳会把乐观值打回去（闪烁），
 * 按 last-synced 比较就不会。**未知**的行（新会话）直接补进状态机。
 * 乐观删除另有墓碑（[removedIds]）：同步不许把刚删的行加回来，权威刷新不再
 * 列出该行时墓碑清除。
 *
 * ## 构造与「什么时候不拉组表」（自 `shell/AppShell.kt` 的 `metaGroupsOf` 平移，D5）
 * 三段回退工厂 [SessionMetaState.forAdapters] + 拉表门槛（[allowGroupLoad]）：
 *  1. `adapters == null`（假源）：组表就是 fixture 自己的（`forAdapters(null)` 种子），
 *     **不**发 `session/group.list` —— 行与组名同源，屏上不出裸 `g-1`；
 *  2. `forAdapters` 退化（`wiringDegradation != null`）：跑在内存假实现上，
 *     **不**拉表 —— fixture 的「项目/灵感」绝不能冒到真会话上；
 *  3. 其余（真源 + 没退化）：`bootstrap()` 里 `loadGroups()`；读失败不是空表，
 *     行上退化成裸 id，重试入口在分组面板那条失败卡。
 */
class SessionMetaOverlay(
    val meta: SessionMetaState,
    /** 是否允许向 core 拉 `session/group.list`（见类 KDoc 的三条门槛）。 */
    private val allowGroupLoad: Boolean,
    /** 一个写动作成功后让壳层重拉权威列表（`UiState.retrySessions`；假源是 no-op）。 */
    private val onRefreshList: () -> Unit,
    /** 时间桶重算的时钟；测试注入固定值。 */
    private val nowMs: () -> Long = { epochNowMs() },
) {

    /**
     * 乐观删除的墓碑：状态机里已经没有、但权威列表还没刷新掉的会话 id。
     * 墓碑存在期间 [syncFrom] 不许把它加回状态机（否则已删的行会复活一帧）；
     * 权威列表不再列出时清除。
     */
    private var removedIds by mutableStateOf(emptySet<String>())

    /**
     * 每个会话**上次从权威源同步**到的投影。权威行与它不同 ⇒ 外部变更/确认回读
     * ⇒ 采纳；相同 ⇒ 什么都不做（保住乐观值，见类 KDoc「变更采纳」）。
     */
    private var lastSynced by mutableStateOf(emptyMap<String, MetaSession>())

    /**
     * 长按那一刻**该行在窗口里的矩形**（根坐标，px）。菜单 Popup 靠它贴在行下方
     * （alpha 审查 J3）。null = 没有锚点（旧调用点 / 状态机被直接打开），宿主退回
     * 旧行为（窗格右上角）—— 位置退步但不崩。
     */
    var menuAnchor by mutableStateOf<IntRect?>(null)
        private set

    /**
     * 是否**至少同步过一次**权威行。没同步过时 [projectGroups] 原样返回 ——
     * 投影的唯一职责是「覆盖乐观值 / 藏掉乐观归档的行」，不是「过滤掉所有我不认识的行」。
     * 少了这道闸，没接适配器的壳（测试、或首帧还没回包）会投影出一份**空列表**，
     * 表现得像「一个会话都没有」—— 这是假空态，比显示裸 id 糟得多。
     */
    private var hasSynced by mutableStateOf(false)

    // --- H5 · [projectGroups] 的缓存槽（普通字段：不参与组合，只有身份比较用） ----------
    /** 上次投影用的分组列表实例（调用方命中 `UiState.groups()` 缓存时它保持不变）。 */
    private var projectionGroups: List<SessionGroup>? = null
    /** 上次投影时 `meta.sessions` 的**逐元素快照**（比较用引用，不用值）。 */
    private var projectionSessions: List<MetaSession> = emptyList()
    /** 上次投影时的墓碑集合实例。 */
    private var projectionRemoved: Set<String> = emptySet()
    /** 上次投影的结果；三个输入身份都没变时原样返回它。 */
    private var projectionValue: List<SessionGroup>? = null

    /** 挂载后拉一次组表（门槛见类 KDoc）。由 [SessionMetaOverlayHost] 的 LaunchedEffect 调。 */
    suspend fun bootstrap() {
        if (allowGroupLoad) meta.loadGroups()
    }

    /**
     * 权威行 → 状态机（只补缺失、只采纳变更，见类 KDoc）。由宿主在权威列表
     * 变化时调用；幂等。
     */
    fun syncFrom(rows: List<SessionSummary>) {
        if (rows.isNotEmpty()) hasSynced = true
        val known = meta.sessions.associateBy { it.id }
        val removed = removedIds
        val adopt = mutableListOf<MetaSession>()
        for (row in rows) {
            if (row.isDraft || row.id in removed) continue
            val projection = row.toMetaSession()
            val current = known[row.id]
            val last = lastSynced[row.id]
            when {
                current == null -> adopt += projection // 新会话：补进状态机
                last == null -> Unit // 来源不明的已知行（状态机自种）：不动
                projection != last -> adopt += projection // 权威变了：采纳（外部写/确认回读）
            }
        }
        if (adopt.isNotEmpty()) {
            meta.applyProjection(
                (known + adopt.associateBy { it.id }).values.toList(),
                newGroups = null,
            )
            lastSynced = lastSynced + adopt.associateBy { it.id }
        }
        // 权威列表不再列出的墓碑：刷新已经确认了删除，清掉。
        // 只有**真的少了**墓碑才写状态 —— 每次都 `removedIds = …` 会平白多一次快照写入，
        // 而那一次写入会连带打掉 [projectGroups] 的缓存（键里含墓碑集合实例）。
        if (removed.isNotEmpty()) {
            val live = rows.mapTo(mutableSetOf()) { it.id }
            val kept = removed.filterNot { it in live }.toSet()
            if (kept != removed) removedIds = kept
        }
    }

    /**
     * 行长按 → 打开上下文菜单。草稿行没有服务端实体（不会真调适配器），
     * 直接忽略 —— 这是「不出现任何菜单入口」规则的一半，另一半（零会话）
     * 由「没有行就没有长按目标」保证。
     *
     * [anchor] = 该行此刻在窗口里的矩形（`SessionsScreen` 的 `onGloballyPositioned`）；
     * 菜单贴在它下方而不是列表窗格右上角。不传 = 没有锚点（测试 / 旧调用点）。
     */
    fun openSessionMenu(summary: SessionSummary, anchor: IntRect? = null) {
        if (summary.isDraft) return
        if (summary.id !in removedIds && meta.sessions.none { it.id == summary.id }) {
            meta.applyProjection(meta.sessions + summary.toMetaSession(), newGroups = null)
            lastSynced = lastSynced + (summary.id to summary.toMetaSession())
        }
        menuAnchor = anchor
        meta.openSessionMenu(summary.id)
    }

    /**
     * 权威行 + 状态机乐观值 → 交给 `SessionsScreen` 的分组列表（纯投影，见类 KDoc）。
     * 规则：草稿原样；状态机不认识的行先不画（同步下一帧补上）；状态机里
     * `archived = true` 且权威没标归档的行 = 乐观归档 → 行立即消失（与真源
     * `session/list` 的归档过滤一致）；其余覆盖 title/pinned/groupId，pinned
     * 变化时时间桶按 [bucketFor] 重算（取消置顶不能留在「置顶」桶）。
     *
     * ## H5 · 为什么这里有一份缓存
     *
     * 调用方是**每次重组**都会执行的两个壳层分支（`AppShell` 的抽屉分支与
     * `DestinationHost` 的会话分支）。一次投影的旧代价是：
     * `flatMap`（N 元素分配）→ 每行 `meta.sessions.firstOrNull { it.id == row.id }`
     * （**O(N·M) 线性查**）→ 每行再读一次时钟 `nowMs()` → 最后 `sessionGroups(...)`
     * = 5 桶 × `filter` + `sortedByDescending`（5N log N）。上游 `UiState.groups()`
     * 第 1 轮已经记忆化（`UiState.kt` 的 `groupedState`），下游这一层没记忆化就等于
     * 「记忆化只保住了第一次」。
     *
     * 所以结果按三个**输入身份**缓存，而不是按值比较（避免每次重算一遍再做 O(N) equals）：
     *  1. [groups] 的实例（`UiState.groups()` 命中缓存时是同一个 List）；
     *  2. `meta.sessions` 的**逐元素引用**（快照列表每次写入都会换元素实例 ——
     *     `MetaSession` 是 data class，`applyProjection` 换的是整张 List）；
     *  3. 墓碑集合 [removedIds] 的实例。
     * 三者都没变 ⇒ 直接返回上次那份 List（连实例都相同，下游 `remember(groups)` /
     * `LaunchedEffect(rows)` 的键因此稳定）。任何一个变了 ⇒ 重算一次。
     *
     * 注意 ①：逐元素引用比较是**精确**的（不做 hash），所以不存在「哈希撞了就漏一次
     * 同步」的坑；代价是每次调用 O(M) 次指针比较，远小于原来的 O(N·M) + 排序。
     * 注意 ②：`bucketFor` 依赖**当前时钟**，缓存命中意味着「乐观行的时间桶」会停在上次
     * 投影的那一刻。这只会影响「相对权威被改过的行」（乐观值本来就是临时的，收敛由
     * `retrySessions` 负责），不会影响权威行自己的桶（桶来自 `UiState.computeGroups()`）。
     * 注意 ③：读 `meta.sessions` 与 [removedIds] 仍然是**快照读** —— 调用方的组合作用域
     * 照旧会被写入失效，可观察语义一点没变（缓存只省算力，不改触发条件）。
     */
    fun projectGroups(groups: List<SessionGroup>): List<SessionGroup> {
        if (!hasSynced) return groups
        val sessions = meta.sessions
        val removed = removedIds
        val cached = projectionValue
        if (
            cached != null &&
            groups === projectionGroups &&
            removed === projectionRemoved &&
            sameInstances(sessions, projectionSessions)
        ) {
            return cached
        }
        val known = sessions.associateBy { it.id } // O(M) 一次，不再是每行一次线性查
        val now = nowMs() // 一个投影一个时钟值，不再是每行一次
        val value = sessionGroups(
            groups.flatMap { group ->
                group.sessions.mapNotNull { project(row = it, known = known, removed = removed, now = now) }
            },
        )
        projectionGroups = groups
        projectionSessions = sessions.toList()
        projectionRemoved = removed
        projectionValue = value
        return value
    }

    /** 逐元素**引用**比较（精确，不做 hash）：状态机列表是否还是上次投影时那一批实例。 */
    private fun sameInstances(current: List<MetaSession>, snapshot: List<MetaSession>): Boolean {
        if (current.size != snapshot.size) return false
        for (i in current.indices) {
            if (current[i] !== snapshot[i]) return false
        }
        return true
    }

    private fun project(
        row: SessionSummary,
        known: Map<String, MetaSession>,
        removed: Set<String>,
        now: Long,
    ): SessionSummary? {
        if (row.isDraft) return row
        // 墓碑：乐观删除/已确认删除的行必须消失（这一条与「不认识」是两回事）。
        if (row.id in removed) return null
        // 同步过之后仍不认识 = 同步后才出现的新行：原样画，不许丢。
        val m = known[row.id] ?: return row
        if (m.archived && !row.archived) return null
        if (m.title == row.title && m.pinned == row.pinned && m.groupId == row.groupId) return row
        return row.copy(
            title = m.title,
            pinned = m.pinned,
            archived = row.archived,
            groupId = m.groupId,
            bucket = bucketFor(updatedAtMs = row.updatedAt, pinned = m.pinned, nowMs = now),
        )
    }

    // --- 动作分派（菜单与弹层都走这里：成功后拉权威列表收敛） ------------------------

    /** 菜单项分派（唯一入口，删除必经确认）。置顶/归档两项当场产生写回调。 */
    suspend fun dispatch(sessionId: String, item: SessionMenuItem) {
        meta.onMenuItem(sessionId, item)
        if (item == SessionMenuItem.TogglePin || item == SessionMenuItem.ToggleArchive) {
            afterWrite()
        }
    }

    /** 重命名提交（弹层「保存」）。失败：状态机回滚 + 弹层留在原地带原因。 */
    suspend fun commitRename(title: String) = afterWrite { meta.commitRename(title) }

    /**
     * 删除确认（弹层「删除」）。成功才立墓碑 —— 失败时状态机已经把行滚回列表，
     * 立墓碑会让它凭空消失（假成功）。
     */
    suspend fun confirmDelete(sessionId: String) {
        meta.confirmDelete()
        if (meta.actionError == null) {
            removedIds = removedIds + sessionId
            afterWrite()
        }
    }

    /** 移动到分组（含 null = 未分组）。失败：`groupId` 回滚。 */
    suspend fun moveToGroup(groupId: String?) = afterWrite { meta.moveToGroup(groupId) }

    /** 分组表单提交（新建 / 编辑）。失败：表单留在原地带原因，组表不动。 */
    suspend fun commitGroupSheet() = afterWrite { meta.commitGroupSheet() }

    /** 删组（删组不删会话，服务端把组内 `group_id` 置 null）。失败：组与归属一起回滚。 */
    suspend fun deleteGroupFromEditor() = afterWrite { meta.deleteGroupFromEditor() }

    /** 每个写动作的公共尾：没失败就拉权威列表收敛（假源 no-op，见 [onRefreshList]）。 */
    private suspend fun afterWrite(block: suspend () -> Unit = {}) {
        block()
        if (meta.actionError == null) onRefreshList()
    }
}

/**
 * 组合根：构造 [SessionMetaOverlay]（状态机 + 门槛 + 刷新回调），`adapters` 变化才重建。
 * 原 `shell/AppShell.kt` 的 `metaGroupsOf` 由此替代 —— 一个状态机同时供
 * 「行上的组名」（`overlay.meta.groups`）与长按菜单/分组管理（本文件宿主），
 * 不再有两份组表各自漂移的口子。
 */
@Composable
fun rememberSessionMetaOverlay(
    adapters: AdapterProvider?,
    sessionId: String,
    onRefreshList: () -> Unit,
): SessionMetaOverlay = remember(adapters) {
    val meta = SessionMetaState.forAdapters(adapters, sessionId)
    SessionMetaOverlay(
        meta = meta,
        allowGroupLoad = adapters != null && meta.wiringDegradation == null,
        onRefreshList = onRefreshList,
    )
}

/**
 * H6 · 权威分组 → 浮层要的**行列表**，实例随 [groups] 的**实例**走（且值相等时不换实例）。
 *
 * `SessionMetaOverlayHost` 里有 `LaunchedEffect(rows) { overlay.syncFrom(rows) }`。
 * 这里要把「键的语义」说清，因为审查报告 H6 的原话（「每次新 List ⇒ 每次重组重启协程」）
 * 只对**内容也变了**的那种新 List 成立：`remember` / `LaunchedEffect` 的键走
 * `Composer.changed`（`equals` 比较，不是 `changedInstance` 的引用比较），所以内容相同的
 * 新 List 不会重启协程。但旧写法每次重组都白烧这些东西：
 *
 *  - `groups.flatMap { it.sessions }` 每次重组分配一个 O(N) 的新 List；
 *  - 键比较本身要对那个新 List 做一次 O(N) 的逐元素 `equals`（`SessionSummary` 是 data
 *    class，逐字段比）；
 *  - `rows` 的实例逐帧变化，任何**下游**想按实例做记忆化的地方（`remember(rows)`、
 *    `key(rows)`、把 rows 传进别的 effect）都会失效 —— 报告里 H6 建议的「remember 后的
 *    rows」正是这一条。
 *
 * 把 `flatMap` 提进 `remember(groups)`：`UiState.groups()` 命中 `derivedStateOf` 缓存时
 * 返回**同一个 List 实例**，于是这里连值比较都省了 —— 逐帧同一个 `rows` 实例，
 * 内容变了（`groups` 换实例且不相等）才重建。三处生产调用点（`AppShell` 的抽屉分支 /
 * 双栏与 rail 单栏的列表分支 / 活动页）都用它；`ShellPerfGuardsTest` 钉住
 * 「AppShell 里不再有裸 `flatMap { it.sessions }`」。
 */
@Composable
internal fun rememberSessionRows(groups: List<SessionGroup>): List<SessionSummary> =
    remember(groups) { groups.flatMap { it.sessions } }

/**
 * 菜单锚点 Box 的 testTag（alpha J3）。
 *
 * 宿主在行下方放一个**零尺寸** Box，Popup 以它为父布局。Popup 自身渲染在独立窗口里，
 * 语义 bounds 量不到「贴行」这件事；测试量这个锚点节点的位置（主窗口坐标）即可证明
 * 菜单跟行，而不是钉在列表窗格右上角。
 */
internal const val MenuAnchorTag = "vade.meta.menuAnchor"

/**
 * 菜单 Popup 的**父坐标**落点（px，alpha J3）：
 *  - `y = 行底 + [gapPx]` —— 贴在行下方，用户一眼看出改的是哪一条；
 *  - `x = 行右缘 - 菜单宽`（右缘对齐行右缘），且不小于 0（窄窗格时贴左缘，交给
 *    Popup 自己的窗口内钳制）；
 *  - 减去 [hostOrigin]：宿主不在根原点时（抽屉/双栏 pane）换成相对宿主的坐标，仍然贴行。
 *
 * 纯函数 —— `SessionMetaMenuAnchorTest` 直接断言，不需要弹层真的出现在窗口里。
 */
internal fun menuPlacementPx(
    anchor: IntRect,
    menuWidthPx: Int,
    gapPx: Int,
    hostOrigin: IntOffset = IntOffset.Zero,
): IntOffset = IntOffset(
    x = (anchor.right - menuWidthPx).coerceAtLeast(0) - hostOrigin.x,
    y = anchor.bottom + gapPx - hostOrigin.y,
)

/**
 * 元数据宿主的**悬浮面**：菜单（长按触发）、编辑弹层、失败横条。渲染在
 * 会话列表 pane 的上层（调用方把列表与宿主放进同一个 `Box`），自身不消费
 * 点击 —— 没有菜单/弹层/横条时对列表零干扰。
 *
 * 横条位置取「玻璃顶栏区（72dp）+ 8dp 呼吸」：与 `SessionsScreen` 的 contentPadding
 * 顶区对齐，横条永远压在列表内容上方而不是钻进玻璃条里。
 */
@Composable
fun SessionMetaOverlayHost(
    overlay: SessionMetaOverlay,
    rows: List<SessionSummary>,
    modifier: Modifier = Modifier,
) {
    val meta = overlay.meta
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    LaunchedEffect(overlay) { overlay.bootstrap() }
    // H6：`rows` 必须是**实例稳定**的列表（生产调用点一律走 [rememberSessionRows]）。
    // 键的比较是 `equals`，所以「内容相同的新 List」不会重启本协程；但旧写法每次重组都要
    // 分配一个 O(N) 的新 List、再对这个新 List 做一次 O(N) 的逐元素 `equals`，而且
    // `syncFrom` 是幂等的（不报错，只是白跑：`associateBy` 建 map + 逐行投影 + 再建一个 map）。
    LaunchedEffect(rows) { overlay.syncFrom(rows) }

    // 宿主自己在根窗口里的原点。行矩形也是根坐标：两者相减 = 相对父布局的落点，
    // 抽屉/双栏里宿主不贴在 (0,0) 时也不会偏。
    var hostOrigin by remember { mutableStateOf(IntOffset.Zero) }

    Box(modifier.fillMaxSize().onGloballyPositioned {
        val o = it.localToRoot(Offset.Zero)
        hostOrigin = IntOffset(o.x.roundToInt(), o.y.roundToInt())
    }) {
        // 行上下文菜单（alpha J3）：**跟行**，不再钉在列表窗格右上角。
        // 做法 = 在行下方的落点放一个零尺寸锚点 Box，Popup 以它为父布局（TopStart + 0）。
        meta.menuFor?.let { sessionId ->
            val anchor = overlay.menuAnchor
            val placement = anchor?.let {
                menuPlacementPx(
                    anchor = it,
                    menuWidthPx = with(density) { MenuWidth.roundToPx() },
                    gapPx = with(density) { MenuRowGap.roundToPx() },
                    hostOrigin = hostOrigin,
                )
            }
            if (placement != null) {
                Box(
                    Modifier
                        .offset { placement }
                        // 1dp 而非 0dp：零尺寸节点在部分平台上没有可量的 bounds，
                        // 测试要靠这个节点的位置证明「菜单跟行」。无背景、无手势，像素上不可见。
                        .size(1.dp)
                        .testTag(MenuAnchorTag),
                ) {
                    SessionContextMenu(
                        state = meta,
                        sessionId = sessionId,
                        onDispatch = { item -> scope.launch { overlay.dispatch(sessionId, item) } },
                        placement = IntOffset.Zero,
                    )
                }
            } else {
                // 没有行矩形（旧调用点）：退回本 Box 右上角 + 48dp，与本棒之前一致。
                SessionContextMenu(
                    state = meta,
                    sessionId = sessionId,
                    onDispatch = { item -> scope.launch { overlay.dispatch(sessionId, item) } },
                )
            }
        }

        // 编辑弹层（同一时刻至多一个，editor 互斥保证）。
        when (val editor = meta.editor) {
            is MetaEditor.RenameSession -> RenameSheet(
                editor = editor,
                onCommit = { text -> scope.launch { overlay.commitRename(text) } },
                onClose = { meta.closeEditor() },
            )
            is MetaEditor.ConfirmDeleteSession -> DeleteConfirmSheet(
                editor = editor,
                onConfirm = { scope.launch { overlay.confirmDelete(editor.session.id) } },
                onCancel = { meta.cancelDelete() },
            )
            is MetaEditor.MoveToGroup -> MoveToGroupSheet(
                editor = editor,
                groups = meta.groups,
                onPick = { groupId -> scope.launch { overlay.moveToGroup(groupId) } },
                // 分组管理入口（任务 2）：表单里直接进编辑器；null = 新建。
                onEditGroup = { groupId ->
                    if (groupId == null) meta.openNewGroupSheet() else meta.openGroupEditor(groupId)
                },
                onClose = { meta.closeEditor() },
            )
            is MetaEditor.GroupSheet -> GroupEditorSheet(
                sheet = editor,
                onName = meta::setGroupDraftName,
                onIcon = meta::setGroupDraftIcon,
                onColor = meta::setGroupDraftColor,
                onClearIcon = { meta.clearGroupDraftIcon() },
                onClearColor = { meta.clearGroupDraftColor() },
                canClear = meta.canClearGroupIconColor,
                onCommit = { scope.launch { overlay.commitGroupSheet() } },
                onDelete = { scope.launch { overlay.deleteGroupFromEditor() } },
                onClose = { meta.closeEditor() },
            )
            null -> Unit
        }

        // 写动作失败横条（最近一条，可关闭）—— 分类文案来自 MetaActionError。
        meta.actionError?.let { failure ->
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = ActionBannerTop)
                    .padding(horizontal = 12.dp),
            ) {
                MetaErrorBanner(
                    text = failure.message,
                    onDismiss = meta::dismissActionError,
                )
            }
        }
    }
}

/** 失败横条的顶距 = 玻璃顶栏区（shell 的 `TOP_BAR_ZONE` 72dp）+ 8dp 呼吸。 */
private val ActionBannerTop = 80.dp
