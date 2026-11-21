package vade.ui.data

/**
 * Browser (wasmJs) actual of [platformPairingStore]: **进程内存**，刷新即失。
 *
 * 浏览器运行时读不到 `POST /ui/session` 的 `Set-Cookie`（[canBootstrapPairing] 为 false），
 * 也**不该**把 `__Host-ac_ui` 写进 `localStorage`：那是一个 HttpOnly、`__Host-` 前缀的
 * 会话 cookie，能被 JS 读到的副本等于把唯一的长期凭据交给同源的任何脚本（XSS 直接变现）。
 * 浏览器能做的只有「让浏览器自己带着 cookie」—— 那要页面壳与 core 同源，不在本端范围内。
 *
 * 所以这里就是一个普通的进程内字符串：恢复路径在浏览器上是「什么都没有」，配对卡照常
 * 给出「请用桌面/手机配对」的可行动文案（H4）。
 */
private val wasmPairingStore: PairingStore = InMemoryPairingStore()

actual fun platformPairingStore(): PairingStore = wasmPairingStore
