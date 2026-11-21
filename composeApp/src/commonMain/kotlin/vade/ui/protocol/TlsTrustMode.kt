package vade.ui.protocol

/**
 * P0-6 · 「走 HTTPS」与「接受任意证书」是**两个**决定，这里把它们分级。
 *
 * 以前两者被绑成一个布尔：任何一次 `tls = true` 的配对（**包括证书由正规 CA 签发**）
 * 都会关掉证书链校验与主机名校验 —— 交付了 HTTPS，却没有交付任何安全属性。现在：
 *
 *  - [SystemChain]（默认）：只走系统信任链，合法 CA / 公共证书正常校验。**没有**任何
 *    trust-all 开关被打开；主机名不匹配、自签、过期都照常失败。
 *  - [InsecureConfirmed]：用户对「这台 core 的证书无法验证」这件事**显式确认过**之后才
 *    允许的一档；它关掉链校验与主机名校验（自签 core 的唯一可用路径），因此必须被
 *    记住（持久化 / 桥里都是这个值），下次重建连接时**照旧**是这一档，不能悄悄升级成
 *    默认，也不能把 SystemChain 悄悄降级成它。
 *
 * [of] 是唯一的推导点：协议为真且用户确认过才是 [InsecureConfirmed]；其余一律
 * [SystemChain]。默认值只能从「没有确认」的那一侧来 —— 数据层不替用户做这个决定。
 */
enum class TlsTrustMode {

    /** 系统信任链（默认）：合法 CA 正常校验，自签/主机名不匹配会失败。 */
    SystemChain,

    /** 仅在用户对警告弹层点过「仍然连接」之后：接受自签证书与主机名不匹配（trust-all）。 */
    InsecureConfirmed,
    ;

    companion object {
        /** 协议 + 用户确认 → 档位。`tls = false` 时永远是 [SystemChain]（明文没有证书可验）。 */
        fun of(tls: Boolean, userConfirmedSelfSigned: Boolean): TlsTrustMode =
            if (tls && userConfirmedSelfSigned) InsecureConfirmed else SystemChain
    }
}
