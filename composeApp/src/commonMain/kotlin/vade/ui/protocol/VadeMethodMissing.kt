package vade.ui.protocol

/**
 * 「这个方法在 core 上不存在」的**统一判定** —— 可选能力探测（模块页面 / 附件上传）
 * 的唯一信号源。两处消费方：`data/ModuleCatalog`（`ui/modules`）与
 * `data/BlobUploadCapability`（`ui/blob.token`）；`data/ModelActionsReal`（`model/list`）
 * 也走这一份，不再各养一套。
 *
 * ## 两种形态，都算「方法不存在」（[VadeException.isMethodMissing]）
 *
 * 1. JSON-RPC 标准码 **`-32601`**：老/其它实现，或将来 core 收紧后可能出现。
 *    `schema/rpc.json` 里五处 notes 注记写的就是这个形态（**与今天的 core 不符**——见下）。
 * 2. **`NOT_FOUND` + message 含 `unknown method`**：本仓 core 今天的真实形态。
 *    `core/vade/session/rpc.py::SessionRPC.dispatch` 对未注册方法回的是
 *    `RpcError("NOT_FOUND", "unknown method '<name>'")` —— 已实跑钉住：
 *    `SessionRPC.dispatch(conn, 'ui/blob.token', {})` → `{'code': 'NOT_FOUND',
 *    'message': "unknown method 'ui/blob.token'"}`。文档（rpc.json notes 与 core 注释）
 *    写的 `-32601` 与这个实现**不一致**，以实跑为准。
 *
 * ## 为什么「只认 NOT_FOUND」不够（这条边界有专门用例钉着）
 *
 * `NOT_FOUND` 同时表示「**资源**不存在」：`session 'x' does not exist` /
 * `no running turn with that id` / `blob not found` 都是它。只看 code 会把真故障
 * 降级成「不支持」—— 所以 NOT_FOUND 形态必须**同时**要求 message 特征。
 *
 * 其余一切（TIMEOUT / UNAVAILABLE / INVALID_ARGUMENT / INTERNAL / …）都**不是**
 * 「方法不存在」：调用方把它当普通故障处理（重试 / 重连 / 报错），绝不降级。
 */
fun VadeException.isMethodMissing(): Boolean =
    code == METHOD_NOT_FOUND_CODE ||
        (code == VadeErrorCodes.NOT_FOUND && message.orEmpty().contains(METHOD_MISSING_MARKER))

/**
 * JSON-RPC 保留码：未注册方法。core 会把它**原样**作为错误 `code` 名携带过来
 * （不是 C-04 的 34 个名字之一，`schema/errors.json` 没有 `-32601`）。
 */
const val METHOD_NOT_FOUND_CODE: String = "-32601"

/**
 * core 未注册方法的 message 指纹（`session/rpc.py` dispatch 的原话开头是
 * `unknown method '<method>'`）。NOT_FOUND 形态用它区分「方法不存在」与
 * 「资源不存在」（见 [isMethodMissing] 的 KDoc）。
 */
private const val METHOD_MISSING_MARKER: String = "unknown method"
