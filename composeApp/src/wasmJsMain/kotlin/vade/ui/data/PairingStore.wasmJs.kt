package vade.ui.data

/**
 * Browser (wasmJs) actual of [platformPairingStore]: **进程内存**，刷新即失。
 *
 * 浏览器运行时读不到 `POST /ui/session` 的 `Set-Cookie`（fetch 的 forbidden response
 * header），所以 [canBootstrapPairing] 走 body token 通道（`x-vade-session-token: 1`）；
 * 但**不该**把 `__Host-ac_ui`（或它的 token 拷贝）写进 `localStorage`：那是一个
 * HttpOnly、`__Host-` 前缀的会话 cookie，能被 JS 读到的副本等于把唯一的长期凭据交给
 * 同源的任何脚本（XSS 直接变现）。
 *
 * 所以这里就是一个普通的进程内字符串：token 只活到这次页面会话结束。刷新后浏览器
 * 自己仍带着 cookie，但本进程没有凭据可恢复 —— 配对卡会重新出现。这是刻意的取舍：
 * 「刷新后可能要多配一次」比「把 30 天凭据交给任何同源脚本」便宜得多。
 */
private val wasmPairingStore: PairingStore = InMemoryPairingStore()

actual fun platformPairingStore(): PairingStore = wasmPairingStore
