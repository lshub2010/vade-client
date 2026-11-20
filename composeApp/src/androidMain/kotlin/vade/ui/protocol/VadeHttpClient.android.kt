package vade.ui.protocol

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Android actual of [platformHttpClient]：OkHttp（与 r5-tls 之前同一个引擎，只是从
 * `HttpClient {}` 的自动发现改成显式 `HttpClient(OkHttp)`）。
 *
 * `allowInsecureTls = true` 时装上 trust-all 的 `X509TrustManager` **和**
 * `hostnameVerifier { _, _ -> true }`：自签证书没有公共 CA 链，CN/SAN 也几乎不会等于
 * 用户输入的地址（`adb reverse` 后是 127.0.0.1，局域网里是 192.168.x.x）。
 * 两个旋钮缺一不可 —— 只关证书链的话 OkHttp 仍会用 `OkHostnameVerifier` 拒掉名字。
 *
 * 明文（`http`）那条路不受影响：Android 的 `network_security_config.xml` 仍然只放行
 * 回环/模拟器别名的 cleartext，本文件不改任何网络安全配置。
 */
actual fun platformHttpClient(allowInsecureTls: Boolean): HttpClient =
    if (allowInsecureTls) {
        HttpClient(OkHttp) {
            install(WebSockets)
            engine {
                config {
                    sslSocketFactory(trustAllContext().socketFactory, trustAllManager)
                    hostnameVerifier { _, _ -> true }
                }
            }
        }
    } else {
        HttpClient(OkHttp) { install(WebSockets) }
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
