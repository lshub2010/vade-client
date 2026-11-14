package vade.ui.data

import vade.ui.protocol.VadeConnectionState

/**
 * Connection lifecycle of [RealSessionSource], observable as a `StateFlow` (the same
 * container the protocol layer uses for `VadeConnectionState` — no new framework).
 *
 * It mirrors the protocol state 1:1 so UI code can bind to connection truth without
 * importing `vade.ui.protocol` (the seam between UI and protocol stays in
 * `vade.ui.data`). `Closed` (the protocol's "client is done") surfaces as
 * [Disconnected] because that is the only user-visible meaning of an explicitly
 * disconnected source.
 */
sealed class RealSessionState {

    /** No client exists (never connected, or after [RealSessionSource.disconnect]). */
    data object Disconnected : RealSessionState()

    /** First transport is being established. */
    data object Connecting : RealSessionState()

    /** A transport is live; requests may be sent. */
    data object Connected : RealSessionState()

    /**
     * A connect attempt failed or the transport died. [attempt] is 1-based;
     * [delayMs] is the exponential backoff wait before the next try
     * (`base * 2^(attempt-1)`, capped — implemented by the protocol client).
     */
    data class Reconnecting(val attempt: Int, val delayMs: Long) : RealSessionState()
}

internal fun VadeConnectionState.toRealState(): RealSessionState = when (this) {
    is VadeConnectionState.Idle -> RealSessionState.Disconnected
    is VadeConnectionState.Connecting -> RealSessionState.Connecting
    is VadeConnectionState.Connected -> RealSessionState.Connected
    is VadeConnectionState.Reconnecting -> RealSessionState.Reconnecting(attempt, delayMs)
    is VadeConnectionState.Closed -> RealSessionState.Disconnected
}
