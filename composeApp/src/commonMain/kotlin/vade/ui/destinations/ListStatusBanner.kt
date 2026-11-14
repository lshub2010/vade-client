package vade.ui.destinations

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.currentRecomposeScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 * 所以这里在首帧之后用 [currentRecomposeScope] 让本作用域失效一次：第二遍组合读到的就是
 * 最新状态。`Connecting` 的 `hasRows` 判据是第二道保险（见 [vade.ui.shell.listStatusOf]），
 * 万一第二遍也赶在写入之前，有行时也不会说错话。
 */
@Composable
internal fun settledListStatus(read: () -> ListStatus): ListStatus {
    val scope = currentRecomposeScope
    LaunchedEffect(scope) {
        // 首帧已上屏；让下一帧在新快照里重算一次。
        scope.invalidate()
    }
    return read()
}

/**
 * 画一条列表状态横幅（`Connected` 画 0 个节点）。
 *
 * 只读 skin token，不引入任何 toolkit（C-09 · `MaterialImportGuardTest`）。
 */
@Composable
internal fun ListStatusBanner(status: ListStatus, modifier: Modifier = Modifier) {
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
            UiText(status.detail, size = 11, color = skin.colors.onSurfaceVariant)
            // 自救命令：一行可照抄的启动命令。必须整体是一个文本节点——用户要能一次选中
            // 整行复制，测试也按这一个节点断言 `VADE_SOURCE` / `VADE_PAIR_CODE`。
            UiText(status.remedy, size = 10, color = skin.colors.onSurfaceVariant)
        }
    }
}
