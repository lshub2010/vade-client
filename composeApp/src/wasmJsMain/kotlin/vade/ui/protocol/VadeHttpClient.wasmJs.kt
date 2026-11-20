package vade.ui.protocol

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets

/**
 * wasmJs actual of [platformHttpClient] — **浏览器自己校验 TLS**。
 *
 * [allowInsecureTls] 被刻意忽略：`fetch` / `WebSocket` 不接受自签证书相关的任何开关
 * （浏览器没有 `hostnameVerifier` 这类东西），一个「信任此证书」的选项在这里只可能是
 * 假承诺。自签 core 的浏览器端用法是先在系统/浏览器里信任那张证书，或者挂一条真证书
 * 的反代。
 *
 * 引擎走自动发现（wasm 目标 classpath 上只有 `ktor-client-js` 一个引擎），所以两个分支
 * 本来就是同一个客户端 —— 这里只写一次，参数留着是为了满足 expect 的形状。
 */
actual fun platformHttpClient(allowInsecureTls: Boolean): HttpClient =
    HttpClient { install(WebSockets) }
