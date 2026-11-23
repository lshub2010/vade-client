package vade.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collect
import vade.ui.UiText
import vade.ui.data.ConnectionStateHost
import vade.ui.data.RealSessionState
import vade.ui.data.SessionSource
import vade.ui.skin.LocalSkin
import vade.ui.skin.SkinColors

/**
 * T1 · 冷启动/断线时用户能看见的**连接真相**，由 [RealSessionState] 投影而来。
 *
 * 这里刻意只有「呈现层」的三态 + 一个瞬态：
 *
 *  - [Connected] — 传输活着，请求能发（绿点 / 已连接）；
 *  - [Reconnecting] — 传输断了，协议 runner 正在按指数退避重试。文案**必须**带
 *    次数与下一次等待（`500ms → 30s`），否则用户只会看到界面「停住」；
 *  - [Offline] — 没有 client（从未连接或已主动断开），红色离线；
 *  - [Connecting] — 首帧建连中（中性灰），不冒充「已连接」，也不夸大成「失败」。
 *
 * 映射是纯函数（[connectionStatusOf]），UI 只消费它，测试直接钉文案与语义；
 * **不存在第二套连接状态** —— 输入就是 `RealSessionSource` 已经在发布的
 * [RealSessionState]（见 `data/RealSessionState.kt`）。
 */
enum class ConnectionPhase { Connected, Connecting, Reconnecting, Offline }

/**
 * One presentation snapshot of the connection.
 *
 * @param label 顶栏用的短文案；重连态把退避写进这一行（`重连中 1.5s`），保证窄屏只有
 *   圆点时读屏仍能从 [description] 拿到同一事实。
 * @param detail 中段横幅的第二行（重连次数 / 未连接原因）；健康态为 `null`。
 * @param description 读屏语义的完整句子（不含「已连接」以外的状态词，重连/离线永不带
 *   「已连接」，否则读屏会把断线念成正常）。
 */
@Immutable
data class ConnectionStatus(
    val phase: ConnectionPhase,
    val label: String,
    val detail: String? = null,
    val description: String,
) {
    /** True only for the real healthy state — the one predicate UI may treat as "no banner". */
    val isConnected: Boolean get() = phase == ConnectionPhase.Connected
}

/**
 * Protocol lifecycle → presentation state. Pure, exhaustive, and the only place the
 * three visible states are named.
 *
 * `attempt` is 1-based in [RealSessionState.Reconnecting]; it is clamped at 1 for display
 * because a malformed 0 would read as "第 0 次尝试" (still not "已连接" — the negative
 * contract holds regardless).
 */
internal fun connectionStatusOf(state: RealSessionState): ConnectionStatus = when (state) {
    RealSessionState.Connected -> ConnectionStatus(
        phase = ConnectionPhase.Connected,
        label = "已连接",
        detail = null,
        description = "已连接 core",
    )

    RealSessionState.Connecting -> ConnectionStatus(
        phase = ConnectionPhase.Connecting,
        label = "连接中",
        detail = null,
        description = "正在连接 core",
    )

    RealSessionState.Disconnected -> ConnectionStatus(
        phase = ConnectionPhase.Offline,
        label = "离线",
        detail = "未连接 core",
        description = "离线：未连接 core",
    )

    is RealSessionState.Reconnecting -> {
        val attempt = state.attempt.coerceAtLeast(1)
        val wait = backoffLabel(state.delayMs)
        ConnectionStatus(
            phase = ConnectionPhase.Reconnecting,
            label = "重连中 $wait",
            detail = "第 $attempt 次尝试失败，$wait 后自动重试",
            description = "重连中：第 $attempt 次尝试失败，$wait 后自动重试",
        )
    }
}

/**
 * Backoff wait in human form: `500ms` below one second, then one decimal second
 * (`1s`, `1.5s`, `30s`). Uses integer arithmetic — `String.format` is JVM-only and this
 * file is commonMain.
 */
internal fun backoffLabel(delayMs: Long): String {
    val ms = delayMs.coerceAtLeast(0L)
    if (ms < 1_000L) return "${ms}ms"
    val tenths = (ms + 50L) / 100L
    return if (tenths % 10L == 0L) "${tenths / 10L}s" else "${tenths / 10L}.${tenths % 10L}s"
}

/**
 * T1 · Bind the shell to a source's connection truth without inventing a second state.
 *
 * Returns `null` for sources that do not own a transport (fixtures / unpaired), so the
 * top bar and the chat notice simply do not compose — a fake source must never be
 * labelled 「已连接」. The flow is the source's own `StateFlow`; [produceState] makes the
 * read a Compose observation, so reconnects push a recomposition instead of needing a
 * manual refresh.
 */
@Composable
internal fun rememberConnectionStatus(source: SessionSource): ConnectionStatus? {
    val host = source as? ConnectionStateHost ?: return null
    val state by produceState(host.connectionState.value, host) {
        host.connectionState.collect { value = it }
    }
    return connectionStatusOf(state)
}

/** Top-bar badge testTag (T1). */
internal const val TopBarConnectionTag = "vade.shell.topbar.connection"

/** Mid-chat notice testTag (T1). */
const val ConnectionNoticeTag = "vade.shell.connection.notice"

private fun tintOf(skin: SkinColors, phase: ConnectionPhase): Color = when (phase) {
    ConnectionPhase.Connected -> skin.success
    ConnectionPhase.Connecting -> skin.onSurfaceVariant
    ConnectionPhase.Reconnecting -> skin.warning
    ConnectionPhase.Offline -> skin.error
}

/**
 * 顶栏上的连接徽标：固定 8dp 圆点 + （宽屏才有的）短文案。
 *
 * [compact] 由 `TopBar` 按**胶囊自身宽度**决定：窄屏只画圆点，标题因此最多让出
 * `8 + 8dp` 的横向空间（不会因为「重连中 30s」这种长文案把标题挤成省略号）。
 * 两种形态都带完整 [ConnectionStatus.description]，读屏不受视觉压缩影响。
 */
@Composable
internal fun ConnectionBadge(
    status: ConnectionStatus,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val skin = LocalSkin.current
    val tint = tintOf(skin.colors, status.phase)
    Row(
        modifier = modifier
            .testTag(TopBarConnectionTag)
            .semantics(mergeDescendants = true) { contentDescription = status.description }
            .height(20.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(tint),
        )
        if (!compact) {
            Spacer(Modifier.width(4.dp))
            UiText(
                text = status.label,
                size = 10,
                color = skin.colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 会话中段的连接横幅（T1）—— 断线/重连时 ChatThread 顶部那条可见提示。
 *
 * 复用同一份 [ConnectionStatus]：这里只负责画，不解析错误码、不另存状态。健康态
 * **不组合任何节点**（不是隐藏文案），所以正常会话的布局与动画逐字节不变。
 */
@Composable
fun ConnectionNotice(status: ConnectionStatus, modifier: Modifier = Modifier) {
    if (status.isConnected) return
    val skin = LocalSkin.current
    val tint = tintOf(skin.colors, status.phase)
    Row(
        modifier = modifier
            .testTag(ConnectionNoticeTag)
            .semantics(mergeDescendants = true) { contentDescription = status.description }
            .clip(RoundedCornerShape(skin.shapes.sm))
            .background(tint.copy(alpha = if (skin.colors.isDark) 0.16f else 0.08f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(tint),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            UiText(
                text = status.label,
                size = 11,
                weight = FontWeight.SemiBold,
                color = tint,
                maxLines = 1,
            )
            if (status.detail != null) {
                UiText(
                    text = status.detail,
                    size = 10,
                    color = skin.colors.onSurfaceVariant,
                )
            }
        }
    }
}
