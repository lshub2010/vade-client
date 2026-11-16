// The model seam (RFC-0005 / D101).
//
// Both picker entries — session detail (session override) and profile (global default) —
// render from and write through THIS interface only. Nothing in the UI knows where the
// list comes from: this round ships an in-memory fixture implementation, and wiring the
// real core later means exactly one thing — implement [ModelActions] with `VadeClient`
// calls (`model/list` / `session/model.get` / `session/model.set`) and pass the object in.
// No composable, no state object, no test changes.

package vade.ui.model

import kotlin.concurrent.Volatile
import vade.ui.protocol.ModelEntry

/**
 * The narrow actions surface the model picker composes against.
 *
 * Scope separation follows D101 (explicit session choice > `[llm].default_model` > manifest
 * priority): the session layer is per-`session_id`, the default layer is the global one.
 * A `modelId = null` argument always means "clear the override / fall back down a layer",
 * mirroring `session/model.set`.
 */
interface ModelActions {

    /**
     * `model/list` result entries in server order. `null` = this core has no model surface
     * (method missing on an old core, or nothing configured) — the picker then shows the
     * 「无可用模型」 empty state, never an empty list (RFC-0005 §7).
     */
    val models: List<ModelEntry>?

    /**
     * FR-8.46 degrade switch: the core cannot take model writes (old core, -32601, or a
     * read-only mode). Rows (if any) render but refuse selection.
     */
    val readOnly: Boolean get() = false

    /** Current global default (`DefaultModelInfo.model_id`); null = 按 priority 兜底. */
    val defaultModelId: String?

    /** Explicit override for [sessionId] (`session/model.get.model_id`); null = follows default. */
    fun sessionOverride(sessionId: String): String?

    /**
     * `session/model.set` — [modelId] is the protocol `model_id` (the entry's [ModelEntry.id]),
     * never a display name; null clears the override.
     */
    fun setSessionModel(sessionId: String, modelId: String?)

    /** Global default write. `null` would mean "no default, priority fallback" — not offered in v1 UI. */
    fun setDefaultModel(modelId: String?)
}

/**
 * This round's in-memory implementation — the picker is fully interactive against fixture
 * data, and every screen labels it 「假数据 · 未接 core」 in its footer (§8.5: labelled,
 * not faked). Selections land in plain maps so screens / previews can read them back.
 */
class InMemoryModelActions(
    override val models: List<ModelEntry>? = ModelFixtures.catalog,
    override val readOnly: Boolean = false,
    initialDefault: String? = ModelFixtures.claudeSonnet.id,
    initialSessionOverrides: Map<String, String?> = emptyMap(),
) : ModelActions {

    override var defaultModelId: String? = initialDefault

    private val sessionOverrides = initialSessionOverrides.toMutableMap()

    override fun sessionOverride(sessionId: String): String? = sessionOverrides[sessionId]

    override fun setSessionModel(sessionId: String, modelId: String?) {
        sessionOverrides[sessionId] = modelId
    }

    override fun setDefaultModel(modelId: String?) {
        defaultModelId = modelId
    }
}

/**
 * I3 · 设置页「默认模型」的进程级 seam。
 *
 * 症状：`ProfileScreen` 的回退分支原来**每次进页都 new 一个** [InMemoryModelActions]，
 * 切一次 tab 回来就重置成出厂默认（用户以为选上了，其实只是那一帧的局部变量）。
 * 默认模型是**全局**层（D101），它的内存承载必须比任何一屏活得久 —— 所以提到模块级：
 * 一屏销毁、另一屏重建，读到的是同一份选择。
 *
 * 为什么不是 `by lazy` 的 `val`：测试需要把进程内状态丢回出厂值（`resetForTest`），
 * 而 `lazy` 一旦算过就再也回不去，那会让测试互相污染。语义上就是「有就拿、没有就建」。
 *
 * 真适配器（[vade.ui.data.ModelActionsReal]）不走这里：接线后 `resolveProfileModelActions`
 * 返回的是 adapter，本单例只在假数据 / 预览 / 未接线的路径上当值。
 */
object DefaultModelActions {

    @Volatile
    private var instance: InMemoryModelActions? = null

    /** 进程内唯一的内存假实现（首次访问时创建）。 */
    fun inMemory(): InMemoryModelActions =
        instance ?: InMemoryModelActions().also { created -> instance = created }

    /** 测试钩子：丢掉进程内状态，下一次 [inMemory] 回到出厂 fixture。 */
    internal fun resetForTest() {
        instance = null
    }
}

/**
 * Fixture entries mirroring what a real `model/list` returns (RFC-0005 §2/§3), aligned with
 * the fake sessions' `model` values so the picker and the header line agree:
 *
 *  - [claudeSonnet]   — everything declared: all three badges 支持, image limits set, online.
 *  - [localQwen]      — text-only (vision 不支持), tools 未声明 → the unknown badge state.
 *  - [undeclared]     — the §2 case: a chat module with no `capabilities.models[]` —
 *                       id in the "m6-echo/..." slash form, every capability field null.
 *  - [offlineVision]  — capable but `available=false`: stays listed, greys out, refuses
 *                       selection (RFC: offline entries stay with available:false).
 */
object ModelFixtures {

    val claudeSonnet: ModelEntry = ModelEntry(
        id = "claude-sonnet",
        provider = "anthropic",
        displayName = "Claude Sonnet",
        inputModalities = listOf("text", "image"),
        maxImageBytes = 20_971_520L,
        maxImagePx = 8_388_608L,
        maxImagesPerTurn = 4,
        contextWindow = 200_000L,
        supportsTools = true,
        priority = 10,
        available = true,
    )

    val localQwen: ModelEntry = ModelEntry(
        id = "local-qwen",
        provider = "ollama",
        displayName = "Qwen2.5 32B",
        inputModalities = listOf("text"),
        maxImageBytes = null,
        maxImagePx = null,
        maxImagesPerTurn = null,
        contextWindow = 32_768L,
        supportsTools = null,
        priority = 20,
        available = true,
    )

    val undeclared: ModelEntry = ModelEntry(
        id = "m6-echo/*",
        provider = null,
        displayName = null,
        inputModalities = null,
        maxImageBytes = null,
        maxImagePx = null,
        maxImagesPerTurn = null,
        contextWindow = null,
        supportsTools = null,
        priority = null,
        available = true,
    )

    val offlineVision: ModelEntry = ModelEntry(
        id = "gpt-4o",
        provider = "openai",
        displayName = "GPT-4o",
        inputModalities = listOf("text", "image"),
        maxImageBytes = 20_971_520L,
        maxImagePx = 8_388_608L,
        maxImagesPerTurn = 4,
        contextWindow = 128_000L,
        supportsTools = true,
        priority = 15,
        available = false,
    )

    /** Server order — the picker must not re-sort by name (priority decides fallback only). */
    val catalog: List<ModelEntry> = listOf(claudeSonnet, localQwen, undeclared, offlineVision)
}
