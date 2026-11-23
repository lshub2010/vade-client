package vade.ui.data

import kotlinx.browser.window

/**
 * Wasm actual of the raw-source channel: the URL query
 * (`index.html?source=real&host=127.0.0.1&port=18744&pair_code=...`). The grammar
 * lives in the shared [rawStringsFromQuery]; a browser never sends env vars, so the
 * query is the whole channel.
 */
actual fun rawSourceStrings(): RawSourceStrings =
    rawStringsFromQuery(window.location.search)

/**
 * T5 · 浏览器**可以**完成首次配对了，但仍然要求「页面与 core 同源」。
 *
 * JS 读不到 `Set-Cookie`（fetch 的 forbidden response header），所以请求显式带
 * `x-vade-session-token: 1`：core 在 Origin 通过闸门时把**同一个** `__Host-ac_ui` 放进
 * `POST /ui/session` 的响应体（`core/vade/transport/server.py`）。这条通道让
 * `KtorTransportFactory` 拿到值；而在旧 core（不回 token）与任何读不到响应的场合，
 * 工厂退回「浏览器托管 cookie」模式 —— cookie 由浏览器自己保存并随 `/ws` 升级发送，
 * 升级成功即证明配对成功。
 *
 * 同源是硬条件：`SameSite=Strict` 的 cookie 不会被跨站页面带上，跨站的 WS 也拿不到它。
 * 那种失败现在归类为 [PairingFailureKind.BrowserCookieMissing]（「浏览器没保存会话」），
 * 不再是「码错」。部署方式：反向代理把静态站点与 core 放在同一个 scheme/host/port 下，
 * 或把页面 Origin 加进 `[server].ui_origins`（仅同 host 不同端口时 cookie 仍会发送）。
 */
actual fun canBootstrapPairing(): Boolean = true
