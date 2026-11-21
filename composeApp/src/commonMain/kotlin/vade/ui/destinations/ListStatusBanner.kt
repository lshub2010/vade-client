package vade.ui.destinations

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vade.ui.UiText
import vade.ui.skin.LocalSkin

/**
 * 会话列表顶部那一行「现在看到的是什么数据」——唯一允许说「假数据」的地方，也是连不上
 * core 时**唯一**的可见反馈。
 *
 * ## 为什么需要它（g-first-run）
 *
 * 首次真实验收（`shots/g-desktop-live/RESULT.md`，P1/P2）抓到两件事，它们其实是同一个
 * 缺陷的两半：
 *
 *  - 列表头**硬编码**「假数据 · 未连接 core」，真源连着真 core 时照显 → 界面在**骗人**；
 *  - `UiState.sessionsPhase/sessionsError` 有完整的 Loading/Ready/Failed 与分类错误，但
 *    全仓没有任何 composable 读它 → 连不上时界面零反馈（`04-real-no-paircode.png`：
 *    `0 个会话` + 一行假数据提示，22 秒内没有任何错误）。
 *
 * 所以这里只做一件事：把「源是什么 / 读到哪一步 / 失败成什么样」投影成一条横幅。
 * 判定（含文案）在 `vade.ui.shell.listStatusOf`，本文件只负责**画**——屏幕不知道
 * `SessionSource`，也不解析错误码。
 *
 * ## 每条分支的诚实性要求
 *
 *  - [Fixtures]：只有 `FakeSessionSource` 能走到这里。「假数据」在假源下是**真的**，
 *    所以保留原文（[FIXTURE_NOTE] 与本棒之前的字符串逐字相同）。
 *  - [Connected]：真源 + 最近一次 `sessions()` 成功 → **不画任何东西**。连上了还挂一条
 *    横幅是噪音，而挂着「假数据」则是谎言。
 *  - [Connecting]：真源 + 首读在飞（`AutoConnectingSessionSource` 最多等 15 s）。中性
 *    文案 + 真实 endpoint，用户知道它在等谁、不是在空转。
 *  - [Failed]：真源 + 读失败 → 分类文案 + 传输原话 + **可照抄的自救命令**。这是本棒唯一
 *    的「错误入口」：重试按钮留给 `g-error-retry` 那一棒（`UiState.retrySessions()` 已存在，
 *    这里不另造一套重试体系）。
 */
sealed interface ListStatus {

    /**
     * 假源（`FakeSessionSource`）：fixtures 在屏上，横幅必须说「假数据」。这是**唯一**允许
     * 出现「假数据」字样的分支。
     */
    data object Fixtures : ListStatus

    /**
     * 真源，首次读取还在飞。`endpoint` 是点分形式 `host:port`，由调用方从**本次运行**的
     * 原始配置里取（不是猜的默认值），因为「正在连接谁」正是用户要的信息。
     */
    data class Connecting(val endpoint: String) : ListStatus

    /**
     * 真源，最近一次读取成功（可能 0 行——空列表是真答案）。**不渲染任何横幅**：
     * 连上了就不该有「未连接」的字样。
     */
    data object Connected : ListStatus

    /**
     * P1-6 · 现在**没有任何真源**：桥从 `Paired` 退回了 `Unpaired`（凭据被拒 / 用户断开 /
     * 磁盘凭据这次连不上），列表由 `UnpairedSessionSource` 供给，必然是空的。
     *
     * 为什么单独一个分支而不是 `Connected`（空列表就安静）：这里的空**不是**「core 上没有
     * 会话」，也不是「还没读到」—— 不说清楚就会被当成数据丢了。也不能用 `Failed`：读取
     * 根本不该发生（重试对「没有凭据」没有意义），可操作的出口是上方的配对卡。
     */
    data object Unpaired : ListStatus

    /**
     * 真源，读取失败。[headline] 是用户视角的一句结论，[detail] 是分类文案 + 传输原话，
     * [remedy] 是那条可以照抄的启动命令（含 `VADE_SOURCE` / `VADE_PAIR_CODE` 与配对码从哪取）。
     * 三者都可能被单独断言，所以它们是字段而不是拼在渲染里的字符串。
     */
    data class Failed(
        val headline: String,
        val detail: String,
        val remedy: String,
    ) : ListStatus
}

/**
 * 假源横幅的原文。**逐字**保留本棒之前 `SessionsScreen` 里那行硬编码：假源下它说的是真话，
 * 本棒要修的是「真源下也照显」，不是这句话本身。改成别的措辞会让「假数据仍然被标注」这条
 * 回归失去唯一的对照物。
 */
const val FIXTURE_NOTE: String =
    "假数据 · 未连接 core（U1-a 只做骨架，U1-b 接线）"

/**
 * P1-6 · 没有任何真源（桥退回 Unpaired）时列表顶部那一行。
 *
 * 必须同时说清两件事：**列表为什么是空的**（不是「core 上没有会话」）与**下一步在哪**
 * （上方配对卡）。这句话只在 [ListStatus.Unpaired] 下出现 —— 真源连上时它会是谎。
 */
const val UNPAIRED_NOTE: String =
    "未连接 core · 列表为空（不是「没有会话」，而是当前没有数据源）；配对成功后会加载真数据。"

/** [UNPAIRED_NOTE] 那条横幅的测试 tag（`App` 级用例断言「空列表 + 分类横幅」）。 */
const val UNPAIRED_NOTE_TAG: String = "list-status-unpaired"



/**
 * 首帧之后再算一遍的 [ListStatus]。
 *
 * ## 为什么需要「再算一遍」（本棒实测出来的）
 *
 * 组合是一次 MVCC 快照：**在首次组合执行期间**才落盘的 `mutableStateOf` 写入，那次组合
 * 看不见，而且（本环境实测）也不会再收到一次失效通知。loader 的协程恰好会落在这个窗口里
 * ——`sessions()` 一次本地握手就能答完（成功）或抛错（无码/拒绝），都比整棵 shell 的首帧
 * 快。后果就是我们最不想要的两种：
 *
 *  - 成功得快：屏上是真会话，横幅却永远停在「正在连接 core …」（换了个说法的谎）；
 *  - 失败得快：空列表 + 零反馈（P2 原样复发）。
 *
 * 注意 `snapshotFlow` / `produceState` **解决不了**这个问题：它们在这种组合里跑到协程上时
 * 仍然带着同一份旧快照，永远读到旧值（实测 `emission=Connecting` 之后再也没有第二次）。
 * 能解决的是「换一次组合」——新组合拿的是新快照。
 *
 * ## 为什么不用 `currentRecomposeScope.invalidate()`（g-first-run-fix 实测）
 *
 * 上一版就是那么做的，代价是一个**比原问题严重得多的回归**：目的地切换后内容区停在
 * 上一屏。原因有两层，都是实测的：
 *
 *  1. 本函数是**有返回值的 composable**，没有自己的 restart scope —— `currentRecomposeScope`
 *     拿到的就是**调用方** [vade.ui.shell.DestinationHost] 的作用域（探针实测两者
 *     identityHashCode 相同）。所以那句 `invalidate()` 失效的不是「这一条横幅」，
 *     而是**整个目的地分发器**。
 *  2. 分发器的作用域被自己 effect 里的 `invalidate()` 失效过一次之后，就**再也不接受
 *     失效**了：探针在一次六跳的目的地序列里看到 `DestinationHost` 只进入过一次
 *     （首帧），之后 `state.dest` 的每次写入都只重组了 `ShellBody`（导航栏/top bar），
 *     内容区一个节点都没重画 —— 也就是「导航说『能力』选中了，屏上还是设置页」。
 *
 * 所以这里改成**普通 state 写入**（`settled = true`）：它是受支持的失效路径，只会让
 * 本作用域重组一次；再由 `key(settled)` 换分组，让 `read()` 在新快照里算第二遍。
 * 回归钉子见 `AppShellDestinationDispatchTest`（连跳五次，每一跳都断言内容跟着导航走）。
 *
 * `Connecting` 的 `hasRows` 判据仍是第二道保险（见 [vade.ui.shell.listStatusOf]）：
 * 万一第二遍也赶在写入之前，有行时也不会说错话。
 */
@Composable
internal fun settledListStatus(read: () -> ListStatus): ListStatus {
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // 首帧已上屏：合上闩锁。这是一次**普通 state 写入**，只会让本作用域再组合一次。
        settled = true
    }
    // 合上闩锁时 `key(settled)` 换一个分组，里面的 `read()` 因此在**新快照**里重算一遍
    // （首帧那遍可能落在 loader 的写入窗口里，见上文 KDoc）。
    return key(settled) { read() }
}

/**
 * 画一条列表状态横幅（`Connected` 画 0 个节点）。
 *
 * 只读 skin token，不引入任何 toolkit（C-09 · `MaterialImportGuardTest`）。
 */
@Composable
internal fun ListStatusBanner(
    status: ListStatus,
    modifier: Modifier = Modifier,
    /**
     * 宿主已经用**自己那条带重试的失败横幅**说过分类与传输原话了 —— 此时只留标题与
     * 自救命令，别把同一句话在同一屏上说两遍（重复的后果是可测的：`需要重连` 会数到 2）。
     */
    compact: Boolean = false,
) {
    val skin = LocalSkin.current
    when (status) {
        // 连上了：没有横幅。不是「隐藏文案」，是真的不组合这个节点——空节点会留下空白
        // 行高，列表顶部会凭空多一段间距。
        ListStatus.Connected -> Unit

        ListStatus.Fixtures -> UiText(
            text = FIXTURE_NOTE,
            size = 10,
            color = skin.colors.onSurfaceVariant,
            modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        )

        // P1-6 · 没有数据源：列表是空的，而且这不是「0 个会话」。用错误色会读成「读失败」
        // （有重试的含义），这里用普通说明色 —— 下一步（重新配对）在上方的配对卡里。
        ListStatus.Unpaired -> UiText(
            text = UNPAIRED_NOTE,
            size = 11,
            color = skin.colors.onSurfaceVariant,
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .testTag(UNPAIRED_NOTE_TAG),
        )

        is ListStatus.Connecting -> UiText(
            text = "正在连接 core ${status.endpoint} …",
            size = 10,
            color = skin.colors.onSurfaceVariant,
            modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        )

        is ListStatus.Failed -> Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(skin.shapes.sm))
                .background(skin.colors.error.copy(alpha = if (skin.colors.isDark) 0.16f else 0.08f))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            UiText(status.headline, size = 12, weight = FontWeight.SemiBold, color = skin.colors.error)
            // compact = 宿主已经说过分类/传输原话（那条卡还带重试），这里不再重复。
            if (!compact) {
                UiText(status.detail, size = 11, color = skin.colors.onSurfaceVariant)
            }
            // 自救命令：一行可照抄的启动命令。必须整体是一个文本节点——用户要能一次选中
            // 整行复制，测试也按这一个节点断言 `VADE_SOURCE` / `VADE_PAIR_CODE`。
            UiText(status.remedy, size = 10, color = skin.colors.onSurfaceVariant)
        }
    }
}
