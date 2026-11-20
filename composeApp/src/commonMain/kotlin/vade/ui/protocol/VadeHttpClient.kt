package vade.ui.protocol

import io.ktor.client.HttpClient

/**
 * r5-tls · 本平台的 HTTP 客户端（已装 `WebSockets`），**唯一**的 client 构造点。
 *
 * `KtorTransportFactory` 的默认 client 从这里来（`KtorTransportFactory` 的 KDoc 与
 * `VadeTransport.kt` 的 `httpUrl`/`websocketUrl` 决定拨 `http(s)` 还是 `ws(s)`）。
 *
 * ## 为什么必须是 `expect`
 * 「接受自签证书」在三个端上是**三种不同的机制**，没有任何共享 API：
 *  - Android（OkHttp）：`sslSocketFactory(trustAll, tm)` + `hostnameVerifier { _, _ -> true }`；
 *  - Desktop（JVM，CIO）：`https { trustManager = trustAll }` —— 注意 CIO 的**主机名校验
 *    是它自己做的**（`TLSClientHandshake` → `verifyHostnameInCertificate`，而且引擎总会把
 *    `serverName` 填成请求的 host），trust-all 也绕不过去；所以桌面在 `allowInsecureTls`
 *    时改用 OkHttp 引擎（同一份 trust-all + hostnameVerifier），见
 *    `VadeHttpClient.desktop.kt` 的 KDoc；
 *  - 浏览器（wasmJs）：TLS 由浏览器自己校验，**没有**可关的开关 —— 参数被忽略
 *    （自签证书要先在系统/浏览器里被信任）。
 *
 * ## 安全边界
 * [allowInsecureTls] = `true` 等于「不验证对端身份」。它只能来自
 * `VadeClientConfig.allowInsecureTls`，而那个字段只有用户在配对表单的警告弹层里点过
 * 「仍然连接」之后才会为真（`destinations/PairingCard.kt`）。默认 `false` = 今天的系统
 * 信任链，一个字节都没变。
 */
expect fun platformHttpClient(allowInsecureTls: Boolean = false): HttpClient
