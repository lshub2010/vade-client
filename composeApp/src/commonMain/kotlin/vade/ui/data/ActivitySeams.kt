package vade.ui.data

import vade.ui.protocol.VadeClient
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException
import vade.ui.subagent.SubagentPage
import vade.ui.subagent.SubagentSource
import vade.ui.subagent.SubagentSourceReal
import vade.ui.protocol.SessionUsageGetResult
import vade.ui.usage.UsageSource
import vade.ui.usage.UsageSourceReal

/**
 * A5 · 活动页（用量 / 子代理）的两个读取窄口，由组合根交给 UI。
 *
 * 为什么单独一个能力接口，而不是给 [AdapterProvider] 加两个方法：`AdapterProvider` 的
 * 方法集合被 `ScreenAdapterWiringTest.adapterProviderStillHandsOutOnlyTheSixNarrowInterfaces`
 * 逐名钉住（「窄接口集合不得悄悄增宽」），而且它交出的五件都是**写/页**通道；活动页的
 * 两个是**读**通道，生命周期语义不同（见下），混进同一个包里只会让「哪几个窗口该在
 * 什么时候非空」变成两套规则。
 *
 * 与 [AdapterHost] 同一条纪律：只有真数据源实现它，假源不实现 ⇒ 取不到就是 `null`
 * （调用方退回夹具/快照，绝不拿到假读取口）。
 *
 * ## 生命周期（与适配器包不同，这里是**恒非空**）
 * [RealSessionSource.activitySeams] 在构造期建一次、全程复用，内部每次调用才去取当前
 * client（[LateBoundUsageSource]）。理由：
 *  - 协议客户端断线重连**换 client 不换 source**（重连循环在 client 内部），晚绑定让窄口
 *    对象身份稳定，页面 `remember(source)` 持有的 loader 不会被重建；
 *  - 断开期间窄口仍在，读取以 `UNAVAILABLE` **明确失败**（可重试），而不是从"没有窄口"
 *    退回夹具数字 —— 后者正是 A3（顶栏显示编造的 fixture token 数）的根因。
 */
data class ActivitySeams(
    val usage: UsageSource?,
    val subagents: SubagentSource?,
) {
    companion object {
        /**
         * 一个"真源但没有接上读取口"的诚实占位：任何读取都以 `UNAVAILABLE` 失败。
         * 组合根用它兜住「实现了 [AdapterHost] 却没实现 [ActivitySeamHost]」的源，
         * 于是 UI 永远不会把「没有 seam」当成「夹具模式」。
         */
        fun unavailable(reason: String = ACTIVITY_SEAMS_UNWIRED): ActivitySeams = ActivitySeams(
            usage = UnavailableUsageSource(reason),
            subagents = UnavailableSubagentSource(reason),
        )
    }
}

/** 「我能交出活动页读取窄口」的能力接口 —— 只由真数据源实现。 */
interface ActivitySeamHost {
    val activitySeams: ActivitySeams?
}

/** 未接线时读取失败的统一说法（不编数字、不假装空列表）。 */
const val ACTIVITY_SEAMS_UNWIRED: String =
    "no activity seam is wired into this source: 用量 / 子代理读取口由组合根" +
        "（RealSessionSource）提供，未连接时以 UNAVAILABLE 明确失败，绝不返回占位数字"

/** 未接线时读取失败的统一说法（与 [ACTIVITY_SEAMS_UNWIRED] 同一条纪律）。 */
private const val NOT_CONNECTED: String =
    "活动页未连接：真源还没有 client（连接建立后才能读），不显示任何占位数字。请恢复连接后重试。"

/**
 * [UsageSource] 的晚绑定实现：每次 `load` 取当前 client，走带强校验的 [UsageSourceReal]。
 *
 * `clientProvider` 而不是 client 本身 —— 断线重连换 client 不换对象（见 [ActivitySeams]）。
 */
internal class LateBoundUsageSource(
    private val clientProvider: () -> VadeClient?,
) : UsageSource {
    override suspend fun load(sessionId: String): SessionUsageGetResult {
        val client = clientProvider()
            ?: throw VadeException(VadeErrorCodes.UNAVAILABLE, NOT_CONNECTED)
        return UsageSourceReal(client).load(sessionId)
    }
}

/** [SubagentSource] 的晚绑定实现；`sessionId = null` 就是契约的跨会话列出。 */
internal class LateBoundSubagentSource(
    private val clientProvider: () -> VadeClient?,
) : SubagentSource {
    override suspend fun list(sessionId: String?): SubagentPage {
        val client = clientProvider()
            ?: throw VadeException(VadeErrorCodes.UNAVAILABLE, NOT_CONNECTED)
        return SubagentSourceReal(client).list(sessionId)
    }
}

/** [ActivitySeams.unavailable] 的用量半边。 */
private class UnavailableUsageSource(private val reason: String) : UsageSource {
    override suspend fun load(sessionId: String): SessionUsageGetResult =
        throw VadeException(VadeErrorCodes.UNAVAILABLE, reason)
}

/** [ActivitySeams.unavailable] 的子代理半边。 */
private class UnavailableSubagentSource(private val reason: String) : SubagentSource {
    override suspend fun list(sessionId: String?): SubagentPage =
        throw VadeException(VadeErrorCodes.UNAVAILABLE, reason)
}
