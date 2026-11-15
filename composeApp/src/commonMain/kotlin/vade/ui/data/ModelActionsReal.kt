package vade.ui.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import vade.ui.model.ModelActions
import vade.ui.protocol.ModelEntry
import vade.ui.protocol.ModelListParams
import vade.ui.protocol.SessionModelGetParams
import vade.ui.protocol.SessionModelSetParams
import vade.ui.protocol.VadeClient
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException
import vade.ui.protocol.isMethodMissing

/**
 * The real [ModelActions] (RFC-0005 / D101): `model/list` fills [models], `session/model.get`
 * fills the per-session override cache, `session/model.set` writes an override — always
 * the protocol `model_id`, never a display name. The caller injects the shared
 * [VadeClient]; the adapter owns no connection.
 *
 * ## Why the reads are pull-based
 * The interface exposes plain vals (the UI is synchronous), so [refreshModels] /
 * [refreshSessionOverride] are the explicit pulls (they suspend on the RPC); the vals
 * reflect the last pulled state afterwards. `null` means "no model surface observed" —
 * the initial state AND the FR-8.46 degradation below; the picker renders the
 * 「无可用模型」 empty state for it, never an empty list.
 *
 * ## The -32601 degradation (FR-8.46)
 * A core without `model/list` answers JSON-RPC -32601 per the contract notes; the
 * shipped core actually maps unregistered methods to `NOT_FOUND`/`unknown method`
 * (`session/rpc.py` dispatch). BOTH shapes are recognized and mapped to
 * `readOnly = true` + `models = null` — a distinguishable return value, not a swallowed
 * error. Every OTHER [VadeException] propagates untouched.
 *
 * ## Why writes are optimistic
 * [setSessionModel] is not a suspend function on the interface, so the RPC rides the
 * injected [writeScope]: the local override updates immediately, and a failed write
 * REVERTS it and records the error in [lastWriteError] (observable, never silently
 * dropped). [setSessionModelAwaiting] is the suspend variant that throws after the same
 * revert+record — use it from coroutines that want the failure directly.
 *
 * [setDefaultModel] refuses with UNSUPPORTED: contract 1.5.0 has NO global-default
 * write method among its 34 ui_to_core methods (`model/list` is read-only) — there is
 * nothing to call, so pretending success would be a lie.
 */
class ModelActionsReal(
    private val client: VadeClient,
    private val writeScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : ModelActions {

    private val _models = MutableStateFlow<List<ModelEntry>?>(null)

    /** `model/list` entries in server order; null until the first successful pull (or after degradation). */
    override val models: List<ModelEntry>? get() = _models.value

    private val _readOnly = MutableStateFlow(false)

    /** FR-8.46 degrade switch: true only after a method-missing answer from `model/list`. */
    override val readOnly: Boolean get() = _readOnly.value

    private val _defaultModelId = MutableStateFlow<String?>(null)

    /** `model/list.default.model_id` (or `session/model.get.global_default`); null = priority fallback. */
    override val defaultModelId: String? get() = _defaultModelId.value

    private val overrides = MutableStateFlow<Map<String, String?>>(emptyMap())

    /** Last failed write (setSessionModel via [writeScope]); null when the last write succeeded. */
    val lastWriteError: StateFlow<Throwable?> get() = _lastWriteError.asStateFlow()
    private val _lastWriteError = MutableStateFlow<Throwable?>(null)

    /**
     * Contract 1.5.0 has NO global-default write method (all 34 ui_to_core methods
     * enumerated; `model/list` is read-only, `session/model.set` is per-session) —
     * refusing here beats pretending the write landed.
     */
    override fun setDefaultModel(modelId: String?) {
        throw VadeException(
            VadeErrorCodes.UNSUPPORTED,
            "no global-default model write exists in contract 1.5.0 (ui_to_core has no default_model.set); " +
                "the global default follows core configuration",
        )
    }

    override fun sessionOverride(sessionId: String): String? = overrides.value[sessionId]

    override fun setSessionModel(sessionId: String, modelId: String?) {
        validateSessionModelArgs(sessionId, modelId)
        writeScope.launch {
            try {
                applySessionModel(sessionId, modelId)
            } catch (_: Throwable) {
                // already reverted + recorded in lastWriteError by applySessionModel —
                // this fire-and-forget path surfaces failures through that StateFlow.
            }
        }
    }

    /** Suspend variant of [setSessionModel]: revert+record, then RETHROW the failure. */
    suspend fun setSessionModelAwaiting(sessionId: String, modelId: String?) {
        validateSessionModelArgs(sessionId, modelId)
        applySessionModel(sessionId, modelId)
    }

    /**
     * 方法缺失判定**已收编**到协议层的统一实现
     * [vade.ui.protocol.isMethodMissing]（`protocol/VadeMethodMissing.kt`）：它同时识别
     * `-32601`（契约注记承诺的形态）与 `NOT_FOUND` + message 含 `unknown method`
     * （本仓 core 今天的真实形态，`session/rpc.py` dispatch），并对 NOT_FOUND 但
     * message 不同（unknown session / unknown group / blob not found）的**真资源缺失**
     * 返回 false —— 那不是「方法不存在」，绝不能降级选择器。
     *
     * 历史注记：本类曾自带一份私有判定（`startsWith("unknown method")`）；g-capability-detect
     * 起它与 `ModuleCatalog` / `BlobUploadCapability` 共用同一份，规则只有一处实现。
     */
    suspend fun refreshModels() {
        try {
            val result = client.modelList(ModelListParams(ifRevision = null))
            if (result.notModified) {
                // We sent no if_revision, so not_modified cannot honestly be true — and
                // with no cached list there is nothing to keep. Refuse, don't zero out.
                throw VadeException(
                    VadeErrorCodes.PROTOCOL_MISMATCH,
                    "model/list answered not_modified=true without an if_revision request and no cached list",
                )
            }
            val list = result.models
                ?: throw VadeException(
                    VadeErrorCodes.PROTOCOL_MISMATCH,
                    "model/list answered not_modified=false with models:null (a required array)",
                )
            _models.value = list
            _defaultModelId.value = result.default?.modelId
            _readOnly.value = false
        } catch (e: VadeException) {
            if (e.isMethodMissing()) {
                _readOnly.value = true
                _models.value = null
                return
            }
            throw e
        }
    }
    /** `session/model.get` pull for one session's explicit override (null = follows default). */
    suspend fun refreshSessionOverride(sessionId: String) {
        if (sessionId.isBlank()) {
            throw VadeException(
                VadeErrorCodes.INVALID_ARGUMENT,
                "params.session_id is required and must be non-blank",
            )
        }
        val result = client.sessionModelGet(SessionModelGetParams(sessionId))
        overrides.value = overrides.value + (sessionId to result.modelId)
        if (result.globalDefault != null) {
            _defaultModelId.value = result.globalDefault
        }
    }

    // ------------------------------------------------------------------ internals

    private suspend fun applySessionModel(sessionId: String, modelId: String?) {
        val previous = overrides.value[sessionId]
        overrides.value = overrides.value + (sessionId to modelId)
        try {
            val result = client.sessionModelSet(SessionModelSetParams(sessionId, modelId))
            // result.model_id is the override AFTER the write (null after a clear).
            overrides.value = overrides.value + (sessionId to result.modelId)
            _lastWriteError.value = null
        } catch (failure: Throwable) {
            overrides.value = overrides.value + (sessionId to previous)
            _lastWriteError.value = failure
            throw failure
        }
    }

    private fun validateSessionModelArgs(sessionId: String, modelId: String?) {
        if (sessionId.isBlank()) {
            throw VadeException(
                VadeErrorCodes.INVALID_ARGUMENT,
                "params.session_id is required and must be non-blank",
            )
        }
        if (modelId != null && modelId.isBlank()) {
            throw VadeException(
                VadeErrorCodes.INVALID_ARGUMENT,
                "params.model_id must be null (clear) or a non-blank model_id, got: \"$modelId\"",
            )
        }
    }
}
