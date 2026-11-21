package vade.ui.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import vade.ui.protocol.TlsTrustMode

/**
 * P0-1 · 一份**可以跨进程存活**的配对凭据。
 *
 * 它只装三样东西：上次成功的 endpoint、协议与信任档位、以及那张 `__Host-ac_ui`。
 * **配对码不在里面**（一次性、用完即焚，存它没有任何意义，还会把「码不落盘」的守卫
 * 变成一句空话）。
 *
 * 存在的理由：`POST /ui/session` 成功后 cookie 是唯一凭据，而 core 只在**它自己启动时**
 * 铸新码 —— 客户端重启后屏幕上不可能出现新码。不落盘 = 每次「关掉再打开」都要去 core
 * 的终端捞码，等于主路径不可用。
 *
 * ## 已知取舍（审查 P1-5，**本棒未做**）
 *
 * [TlsTrustMode.InsecureConfirmed] 目前是**对这个 endpoint 的空白授权**：恢复时只要
 * `trustMode == InsecureConfirmed` 就重新打开 trust-all，而这份记录里**没有**证书指纹。
 * 后果：同一 `host:port` 上以后出现的任何证书都被接受（自签轮换、DHCP 复用 IP、ARP/DNS
 * 劫持后的中间人），用户永远不会被告知「证书变了」。
 *
 * 修法（下一棒）：在确认成功那一刻持久化 SPKI 的 SHA-256（至少是整证书指纹），
 * `probeStoredLocked` 在 trust-all 连接建立后比对；不一致 → 不 install、凭据留着、桥退回
 * 「需要重新确认」并给出重新配对的入口（TOFU + pinning）。
 *
 * 为什么这次没做：指纹只能在 TLS 握手/trust-manager 一侧拿到，而本仓的 trust-all 在
 * **两个平台 actual**（桌面 OkHttp、Android OkHttp）里各自构造 —— 要先给
 * `expect fun platformHttpClient` 增加「观察到叶子证书」的出口、再把它穿过
 * `KtorTransportFactory` 带到 `PairingRuntime.install`，并新增一个「证书变了」的
 * `Unpaired` 原因与卡片文案/按钮。这是一整条跨平台改动的量级，塞进本棒会让 P1-1/2/3/4/6
 * 这些真正破坏凭据与信任边界的修复失去可验证性。**在它落地之前，用户点「仍然连接」时应被
 * 视为对本机的一次性授权，而不是永久 pin。**
 */
data class StoredPairing(
    val host: String,
    val port: Int,
    /** 协议：`true` = `https` / `wss`。 */
    val tls: Boolean,
    /** 信任档位（P0-6）：合法 CA 与「用户确认过的自签」是两件事，必须分开记住。 */
    val trustMode: TlsTrustMode,
    /** `__Host-ac_ui` 的**值**（不含头名）；发请求时由 factory 重新拼头。 */
    val cookie: String,
)

/**
 * 平台 KV 的唯一缝：三端各一份 actual（桌面 = 0600 文件、Android = SharedPreferences、
 * wasm = 进程内存）。
 *
 * 同步 API 是刻意的：启动恢复要在**建源之前**读到它（`sessionSourceFrom` 是同步函数），
 * 而三端最简单的实现本来就是同步的（文件读 / SharedPreferences / 内存）。
 */
interface PairingStore {

    /** 读回上次的凭据；没有 / 内容坏了（手改、版本不对）= `null`，绝不抛。 */
    fun load(): StoredPairing?

    /** 覆盖写入（配对成功后 / 恢复成功后刷新一次）。 */
    fun save(pairing: StoredPairing)

    /** 抹掉（401 / 握手失败 / 用户断开 / 换 core 成功之后）。 */
    fun clear()
}

/**
 * 「一个字符串 + 三个 lambda」的实现骨架：三端只提供**怎么读写那一个字符串**，
 * 编码/解码/校验只有这一份（[PairingStoreCodec]）。
 */
abstract class StringPairingStore(
    private val read: () -> String?,
    private val write: (String) -> Unit,
    private val erase: () -> Unit,
) : PairingStore {

    override fun load(): StoredPairing? = PairingStoreCodec.decode(runCatching { read() }.getOrNull())

    override fun save(pairing: StoredPairing) = write(PairingStoreCodec.encode(pairing))

    override fun clear() = erase()
}

/**
 * 凭据的线上形状（`data/pairing.json` 那一个字符串）。
 *
 * 严格解码是安全边界的一部分：任何字段缺失/类型不对/端口越界/档位不认识 → `null`
 * （当作「没有凭据」回到配对卡），而不是带着半份配置去连一个来路不明的 endpoint。
 */
object PairingStoreCodec {

    /** 格式版本。字段变了就 +1，旧文件会被当成「没有凭据」。 */
    const val VERSION: Int = 1

    fun encode(pairing: StoredPairing): String = buildJsonObject {
        put("version", VERSION)
        put("host", pairing.host)
        put("port", pairing.port)
        put("tls", pairing.tls)
        put("trust", pairing.trustMode.name)
        put("cookie", pairing.cookie)
    }.toString()

    fun decode(raw: String?): StoredPairing? {
        if (raw.isNullOrBlank()) return null
        val obj = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        if (obj.int("version") != VERSION) return null
        val host = obj.str("host")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val port = obj.int("port")?.takeIf { it in 1..65535 } ?: return null
        val tls = obj.bool("tls") ?: return null
        val trust = obj.str("trust")
            ?.let { name -> TlsTrustMode.entries.firstOrNull { it.name == name } }
            ?: return null
        val cookie = obj.str("cookie")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // 协议是明文时不存在「确认过自签证书」这回事：读到这种组合一律降回 SystemChain，
        // 免得一份坏文件把 trust-all 带进一次明文连接（P0-6 的边界）。
        val resolvedTrust = if (tls) trust else TlsTrustMode.SystemChain
        return StoredPairing(host, port, tls, resolvedTrust, cookie)
    }

    private fun JsonObject.str(key: String): String? {
        val p = this[key] as? JsonPrimitive ?: return null
        return if (p.isString) p.content else null
    }

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
}

/** 进程内存实现：wasm 的 actual、以及**所有测试**（不碰真磁盘）。 */
class InMemoryPairingStore(private var value: String? = null) : PairingStore {

    override fun load(): StoredPairing? = PairingStoreCodec.decode(value)

    override fun save(pairing: StoredPairing) {
        value = PairingStoreCodec.encode(pairing)
    }

    override fun clear() {
        value = null
    }
}

/** 三端各一份：桌面 = 0600 文件、Android = SharedPreferences、wasm = 进程内存。 */
expect fun platformPairingStore(): PairingStore

/**
 * 凭据存储的进程级入口 + 上一次读到的值。
 *
 * 为什么单独一个对象：`PairingRuntime` 所在文件被守卫钉住「唯一的可变字段是桥状态」
 * （`PairingFirstRunTest.enteredPairCodeHasNoPersistenceSink`）。存储的注入点与缓存放在
 * 这里，桥那边一个 `var` 都不多。
 *
 * [cached] 是 compose state：引导卡读它来决定要不要显示「用已保存的配对重连」。
 */
object PairingStores {

    /**
     * 测试注入：非 null 时所有读写都落在它上面（真磁盘一个字节都不碰）。
     * `PairingRuntime.clearForTests()` 会把它换成新的 [InMemoryPairingStore]。
     */
    var storeOverride: PairingStore? = null

    /** 上一次读到的凭据（`null` = 没有 / 已被清除）。 */
    var cached: StoredPairing? by mutableStateOf(null)
        private set

    fun current(): PairingStore = storeOverride ?: platformPairingStore()

    fun load(): StoredPairing? = current().load()?.also { cached = it }

    fun save(pairing: StoredPairing) {
        cached = pairing
        current().save(pairing)
    }

    fun clear() {
        cached = null
        runCatching { current().clear() }
    }

    internal fun resetForTests() {
        storeOverride = InMemoryPairingStore()
        cached = null
    }
}
