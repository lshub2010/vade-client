package vade.ui.data

/**
 * Desktop actual of the raw-source channel: environment variables, unchanged from
 * the pre-g-live desktop switch (`VADE_SOURCE=real`, `VADE_HOST`, `VADE_PORT`),
 * plus `VADE_PAIR_CODE` — the one-time browser pair code the real core requires
 * (`POST /ui/session` → `__Host-ac_ui` cookie; see shots/g-live/PLAYBOOK.md).
 */
actual fun rawSourceStrings(): RawSourceStrings = RawSourceStrings(
    source = System.getenv("VADE_SOURCE"),
    host = System.getenv("VADE_HOST"),
    port = System.getenv("VADE_PORT"),
    pairCode = System.getenv("VADE_PAIR_CODE"),
)

/**
 * H4 · 桌面（JVM）能读 `Set-Cookie`，可以兑换一次性码 —— 这是配对的正路。
 * 见 `SourceConfig.kt` 的 expect 声明。
 */
actual fun canBootstrapPairing(): Boolean = true

