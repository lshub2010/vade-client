package vade.ui.protocol

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Desktop (JVM) actual of [platformHttpClient].
 *
 * 默认 = **CIO**（与 r5-tls 之前完全一致，只是从 `HttpClient {}` 的引擎自动发现改成显式
 * `HttpClient(CIO)` —— 桌面 classpath 现在有第二个引擎（OkHttp，见下），自动发现在多引擎时
 * 会直接抛异常）。`allowInsecureTls = false` 时一个字节的行为都没变。
 *
 * ## 为什么 `allowInsecureTls = true` 时换成 OkHttp 而不是给 CIO 配 trust-all
 * CIO 的手握**自己**校验主机名：`io.ktor.network.tls.TLSClientHandshake` 在
 * `checkServerTrusted(...)` 之后执行 `HostnameUtilsKt.verifyHostnameInCertificate`，
 * 而 `Endpoint.connect` 又会把 `serverName` 补成请求的 host（`serverName ?: host`）。
 * 于是「trust-all 的 X509TrustManager」只能放过**证书链**，放过不了**名字**。
 *
 * 实测（r5-tls 收尾，对着本机 core 的 `CN=lenshld.dns.navy` 自签证书、用 /etc/hosts 把
 * `mismatch.local` 指向同一台机器）：
 *  - `CIO` + trust-all → `TLSException: No server host: mismatch.local in the server
 *    certificate. Provided in certificate: lenshld.dns.navy`（请求没发出去）；
 *  - `OkHttp` + trust-all + `hostnameVerifier` → HTTP 405（core 的回答，TLS 过了）。
 *
 * 自签 core 最常见的形态就是「证书写了一个名字、用户填的是 IP」，CIO 这条路因此不可用。
 * OkHttp 两个旋钮都在：`sslSocketFactory(trustAllContext().socketFactory, trustAllManager)`
 * 关证书链，`hostnameVerifier { _, _ -> true }` 关主机名（同一份 OkHttp 引擎本仓 Android
 * 端已在用，Ktor 3.6.1 的 okhttp 引擎把 okhttp 作为 api 依赖暴露）。
 *
 * 只在**用户确认过**时才走到这一支（见 `VadeClientConfig.allowInsecureTls` 的 KDoc）。
 */
actual fun platformHttpClient(allowInsecureTls: Boolean): HttpClient =
    if (allowInsecureTls) {
        HttpClient(OkHttp) {
            install(WebSockets)
            engine {
                // `config { }` 的 receiver 是 OkHttpClient.Builder（ktor-client-okhttp 把
                // okhttp 作为 api 依赖暴露，这里不需要额外声明）。
                config {
                    sslSocketFactory(trustAllContext().socketFactory, trustAllManager)
                    // 自签证书的 CN/SAN 通常与用户输入的 IP 不一致 —— 这正是确认弹层
                    // 警告「无法验证 core 身份」的那部分。
                    hostnameVerifier { _, _ -> true }
                }
            }
        }
    } else {
        HttpClient(CIO) { install(WebSockets) }
    }

/** 接受一切的 X509 信任链校验（只用于用户确认过的自签端点）。 */
private val trustAllManager: X509TrustManager = object : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

private fun trustAllContext(): SSLContext =
    SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
    }
