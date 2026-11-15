package vade.ui.protocol

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「方法不存在」统一判定（[isMethodMissing]）——可选能力探测（模块页面 / 附件上传 /
 * 模型选择）共用的唯一规则，本文件把它的边界逐条钉死：
 *
 *  - 两种形态都算：`-32601`（JSON-RPC 标准）与 `NOT_FOUND` + message 含 `unknown method`
 *    （本仓 core 今天的真实形态，`session/rpc.py` dispatch 实跑钉住）；
 *  - **只认 NOT_FOUND 不够**：`NOT_FOUND` 也表示「资源不存在」——message 不带
 *    `unknown method` 指纹的（`blob not found` / unknown session / unknown turn）
 *    **必须**判 false，否则真故障被降级成「不支持」；
 *  - 真故障（TIMEOUT / UNAVAILABLE / INTERNAL / …）一律 false。
 */
class VadeMethodMissingTest {

    private fun e(code: String, message: String): VadeException = VadeException(code, message)

    // ------------------------------------------------------- 识别：两种形态

    @Test
    fun recognisesTheJsonRpcStandardCode() {
        assertTrue(
            e(METHOD_NOT_FOUND_CODE, "method not found: ui/blob.token").isMethodMissing(),
            "-32601 是 JSON-RPC 标准的「未注册方法」码，必须识别（老实现 / 将来收紧后的形态）",
        )
    }

    @Test
    fun recognisesTheShippedCoreShape() {
        // core/vade/session/rpc.py::SessionRPC.dispatch 的原话：unknown method '<name>'。
        assertTrue(
            e(VadeErrorCodes.NOT_FOUND, "unknown method 'ui/blob.token'").isMethodMissing(),
            "今天的 core 对未注册方法回 NOT_FOUND + unknown method（实跑钉住），必须识别",
        )
        assertTrue(
            e(VadeErrorCodes.NOT_FOUND, "unknown method 'ui/modules'").isMethodMissing(),
            "ui/modules 的探测失败与 ui/blob.token 同形",
        )
    }

    @Test
    fun recognisesTheMarkerAnywhereInTheMessage() {
        // 任务口径是「message 含 unknown method」：指纹不要求在开头。
        assertTrue(
            e(VadeErrorCodes.NOT_FOUND, "dispatch failed: unknown method 'x/y'").isMethodMissing(),
        )
    }

    // ------------------------------------------------------- 边界：NOT_FOUND ≠ 方法不存在

    @Test
    fun notFoundWithoutTheMarkerIsAResourceMissNotUnsupported() {
        // 专门的边界用例：blob not found 是「资源不存在」（真故障语义），绝不是「不支持」。
        assertFalse(
            e(VadeErrorCodes.NOT_FOUND, "blob not found").isMethodMissing(),
            "NOT_FOUND 但 message 不含 unknown method：资源缺失，不得当「不支持」",
        )
    }

    @Test
    fun notFoundForUnknownSessionOrTurnIsAResourceMiss() {
        assertFalse(
            e(VadeErrorCodes.NOT_FOUND, "session 's-1' does not exist").isMethodMissing(),
        )
        assertFalse(
            e(VadeErrorCodes.NOT_FOUND, "no running turn with that id").isMethodMissing(),
        )
    }

    @Test
    fun notFoundWithEmptyMessageIsAResourceMiss() {
        // VadeException 对空 message 会回填 code：e.message == "NOT_FOUND"，
        // 不含指纹 → false。这条钉住「空 message 不能被 marker 匹配放过去」。
        assertFalse(e(VadeErrorCodes.NOT_FOUND, "").isMethodMissing())
    }

    // ------------------------------------------------------- 真故障：绝不误判

    @Test
    fun realFaultsAreNeverMethodMissing() {
        for (code in listOf(
            VadeErrorCodes.TIMEOUT,
            VadeErrorCodes.UNAVAILABLE,
            VadeErrorCodes.INTERNAL,
            VadeErrorCodes.INVALID_ARGUMENT,
            VadeErrorCodes.PROTOCOL_MISMATCH,
            VadeErrorCodes.OVERLOADED,
        )) {
            assertFalse(
                e(code, "unknown method would be a lie here: $code").isMethodMissing(),
                "$code 即使 message 里出现 unknown method 字样也不是「方法不存在」——只对 NOT_FOUND 生效",
            )
            assertFalse(e(code, "any transport-level text").isMethodMissing(), "$code 不是「方法不存在」")
        }
    }

    @Test
    fun clientSideTransportCodesAreNeverMethodMissing() {
        assertFalse(e(VadeException.CLIENT_TIMEOUT_CODE, "no reply within 5000ms").isMethodMissing())
        assertFalse(e(VadeException.TRANSPORT_CLOSED_CODE, "send failed").isMethodMissing())
    }
}
