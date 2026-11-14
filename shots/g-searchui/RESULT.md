# g-searchui RESULT — 搜索状态接上 `session/search`（两档范围）

- 分支 `tmp/g-searchui`（基于 main `c95c558`），代码 commit **`ff781f6`**
- 本轮改动仅限：`composeApp/src/commonMain/kotlin/vade/ui/search/`（3 改 + 2 新）+ `composeApp/src/desktopTest/kotlin/vade/ui/search/`（2 新测试文件）+ 本 RESULT
- 未动：`shell/**`、`destinations/**`、`chat/**`、`data/**`、`protocol/**`、`skin/**`、`icon/**`、`build.gradle.kts`、`contracts/vade`（pin `320384a` 未动，worktree 子模块仅 checkout 到 pin，无提交）
- 未引入 kotlinx-serialization / @Serializable（接口层只有 UI 模型；protocol 类型只出现在 `RemoteSearchReal` 的 import）

## 0. 原始数字（不写"已通过"，只给输出）

| 门禁 | 原始输出 |
|---|---|
| `:composeApp:desktopTest --rerun` | **`tests=515 failures=0 errors=0 skipped=1`**（50 个 TEST-*.xml 聚合；基线 c95c558 为 492，本轮 +23 = 8 + 15） |
| `:composeApp:assembleDebug` | `BUILD SUCCESSFUL in 1m 23s`（packageDebug → assembleDebug 全链执行；APK 22,042,728 B，md5 `a15769741fb2b3ff1a2b89c8400ec80b`，`RemoteSearchReal`/`SearchFailure`/`runRemote`/`RemoteSearchException` 四个本轮类名均实查存在于 `classes3.dex`） |
| `:composeApp:compileKotlinWasmJs` | `BUILD SUCCESSFUL in 21s`（与本轮 commonMain 同源，无 JVM 专有 API 报错） |
| 新测试分布 | `RemoteSearchRealTest` 8 条（真 VadeClient + FakeTransport 走真帧）/ `SearchStateRemoteTest` 15 条（fake seam 驱动状态机，最后一条端到端真 client→adapter→state→label） |
| skipped=1 | 既有 `VadeClientSmokeTest` 的既有 skip（与 p-search RESULT 记录一致，非本轮） |

途中真报错（诚实记录）：首跑 desktopTest 515 中 7 失败 → ① worktree 未 checkout `contracts/vade`（守卫测试环境问题，`git submodule update --init` 到 pin 320384a 解决）；② 本轮 2 个真 bug（`failRemote` 漏设 scope → 失败态标签显示「已缓存」撒谎；`reset()` 漏清 scope → 重置后残留远端标签）+ 1 条测试自错（断言了 `session.id == message_id`，映射本身是对的）。修复后全量重跑见上表。

## 1. 窄接口（`search/RemoteSearch.kt`，新文件）

```kotlin
interface RemoteSearch {
    suspend fun search(query: String, limit: Int, offset: Int): SearchPage
}

@Immutable
data class SearchPage(
    val hits: List<SearchHit>,   // UI 模型（search/ 自有），非 protocol 类型
    val total: Int,              // 服务端计数，永远压过 hits.size 的本地重数
    val scope: SearchScope,      // 来源戳 —— scope.label 的赋值源头
)

class RemoteSearchException(
    val category: VadeErrorCategory,
    val code: String?,           // C-04 code（协议缝里来的才有）
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
```

- **接口里零 VadeClient 类型**（state 与测试只见 `SearchPage`/`SearchHit`，可测性成立）。
- `RemoteSearchException` 是 `RemoteSearchReal.search` 的唯一异常出口：所有 Throwable（除 CancellationException 原样上抛，它是控制流不是失败）在缝上包成带 `VadeErrorCategory` 的异常 —— 分类用 `data/VadeErrorCategory.kt` 的冻结表 `loadErrorCategoryOf`（VadeException → 表；其它 → 最温和的 Retryable），**原文 message 与 C-04 code 全保留，不吞**。

## 2. 真适配器（`search/RemoteSearchReal.kt`，新文件）

```kotlin
class RemoteSearchReal(
    private val client: VadeClient,
    private val nowMs: () -> Long = ::epochNowMs,   // 注入时钟，测试钉死 bucket/label 推导
) : RemoteSearch
```

- **参数先行校验（发帧前，全部 INVALID_ARGUMENT）**：空白 query（trim 后空）、`limit !in 1..100`、`offset < 0`。测试 `blankQueryIsRefusedBeforeAnyFrame` / `limitAndOffsetBoundsAreRefusedBeforeAnyFrame` 同时断言 `transport.record` 为空 —— 帧根本没发。
- **调真方法**：`client.sessionSearch(SessionSearchParams(query, limit, offset))`（codec 在 `VadeModels.kt`，34/34 守卫不动）。
- **snippet / snippet_offset 原样透传**：wire `snippet` → `SearchHit.snippet`（原字符串，60 码点 astral 片段测试钉死不被重截）；wire `snippet_offset` → `SearchHit.snippetOffset`（0 与 null 是两个值）。客户端只做**高亮定位**（`matchRanges` 在 verbatim 文本内找 query），文本零改写。`query` 上线也原样（校验用 trim 副本，帧里带 `"  模型  "` 原文，测试钉死）。
- **hit → SearchHit 映射**：`session = SessionSummary(id, title ?: "（无标题）", preview="", bucket=bucketFor(…), updatedLabel=relativeLabel(…))` —— 复用 data 层同模块 internal 纯函数，与 `sessionRowToSummary` 同一约定；`titleMatched` 取服务端 `matched_in == "title"`（不是客户端重数）；`archived` 原样带上（行 UI 暂不渲染，见 §6 已知边界）。
- `total` 原样透传（服务端计数权威，1 行命中 total=7 的测试双向钉死）。

## 3. SearchState 两档范围（`search/SearchState.kt`）

```kotlin
class SearchState(
    private val remote: RemoteSearch? = null,   // ← 注入点；默认 null
) {
    val remoteAvailable: Boolean get() = remote != null
    var remoteEnabled by mutableStateOf(remote != null); private set   // 档位
    var remoteFailure by mutableStateOf<SearchFailure?>(null); private set
    var remoteInFlight by mutableStateOf(false); private set
    private var remoteGeneration = 0

    suspend fun runRemote(query: String)   // 远端档执行（带 generation 作废）
    suspend fun retryRemote()              // 失败态专属重试入口
    fun useLocal() / fun useRemote()       // 切档（清对侧结果 + 失败 + 在途作废）
}
```

**怎么切**：
1. **不注入（默认）**：`SearchState()` → `remoteEnabled=false`，overlay 的防抖 runner 走本地 `applyResults(LocalSessionIndex…)`，**与注入前字节级同行为**（`SearchFlowTest` 全量原样跑绿即证）。
2. **注入后**：`remoteEnabled` 起始为 true，查询直接走 `runRemote(trimmedQuery)`（overlay `SearchHost` 的 `LaunchedEffect` 按 `search.remoteEnabled` 分流，key 也加了它，切档即重跑）。
3. **手动切档**：范围 pill 在 `remoteAvailable` 时可点（`useLocal()`/`useRemote()`），双向都清掉对侧来源的结果行（本地行不清远端档，反之亦然）+ 清失败态 + `remoteGeneration++` 作废在途 run。
4. **失败 ≠ 空结果**：`runRemote` 失败 → `failRemote` 写 `remoteFailure`（类别 + 原文 + code），`executed=false`、行清空、**scope 钉在 CrossSession**（失败不是降级，标签不许说「已缓存」）；overlay 的 `SearchFailureNotice` 在 Ready 分支**最优先**渲染（类别 label + 原文 message + 44dp 图标重试钮，重试钮 `remoteInFlight` 时禁用）。重试成功 → `applyPage` 回到结果态。
5. **generation 作废**：`runRemote` 入口 `++remoteGeneration`，完成时只在 `generation == remoteGeneration` 才 apply；慢的旧 run（或被切档/重置作废的 run）的回包直接丢弃。测试 `aSupersededRemoteRunNeverOverwritesTheNewerResults`（门控 fake：新 run 先落地、旧 run 迟到不得覆盖）+ `switchingToLocalCancelsAnInFlightRemoteRun`（切本地后迟到页不得落地）。

## 4. `scope.label` 赋值链路（硬规则：状态驱动，禁止硬编码）

```
远端: wire total/results ──codec──▶ SessionSearchResult ──adapter──▶ SearchPage(scope=CrossSession)
                                                                            │ applyPage()
本地: LocalSessionIndex.search() ────────────────────────────────▶ SearchResult(scope=Cached)
                                                                            │ applyResults()
                                    SearchState.scope ◀──── 唯一被赋值处（上面两条 + 切档）
                                                                              │
                                    overlay 只渲染 state.scope.label（enum 常量，非字符串字面量）
```

- `applyResults(result)`：`scope = result.scope`（本地源 → 「范围：已缓存」，原逻辑一字未改）。
- `applyPage(page)`：`scope = page.scope`（远端源 → 「范围：全部会话」）。
- `useLocal()/useRemote()`：切档瞬间把 scope 置为**即将回答下一次 run 的档**（清空间隙里标签不撒谎）；失败态 `failRemote` 钉 CrossSession（不许显示为降级）。
- overlay 里**没有任何 scope 标签字面量**：pill 渲染 `search.scope.label`；范围行详情文本 `scopeDetail(search)` 全部按 `remoteFailure` / `scope` / `remoteAvailable` 分支取数（数字来自 `indexedCount`：本地 = 缓存会话数，远端 = 服务端 total）；空态/无命中文案同样按档分支。双向翻转测试：`injectedSeamRunsRemoteAndTheLabelFollowsTheRealSource`（远端命中 → 「范围：全部会话」）与 `switchingBackToLocalDropsRemoteRowsAndRelabels`（切回本地 → 「范围：已缓存」）。

## 5. 未接线清单（本轮一行未动 shell/，接线是一行改动 + 一个来源）

| 文件:行号 | 现状 | 接线动作 |
|---|---|---|
| `composeApp/src/commonMain/kotlin/vade/ui/shell/UiState.kt:111` | `val search = SearchState()` | 给 `UiState` 构造器（42–63 行）加可选参 `val remoteSearch: RemoteSearch? = null`（默认 null 不动任何现有调用方），111 行改 `val search = SearchState(remoteSearch)` |
| `composeApp/src/desktopMain/kotlin/vade/ui/Main.kt:137` | `VADE_SOURCE=real` 分支构造 `RealSessionSource(host, port)`，client 藏在 source 内部 | 构造共享 VadeClient：`RealSessionSource.kt:104` 已有 `internal val client: VadeClient?`（连接前为 null，需在 Connected 后注入，或给 RealSessionSource 加「外部传入 client」构造重载，避免双连接）。`SearchState(RemoteSearchReal(client, …))` 随 UiState 传入 |
| `composeApp/src/commonMain/kotlin/vade/ui/search/SearchOverlay.kt:93` | 已就绪：`LaunchedEffect(search.query, search.remoteEnabled, sessions)` 内按 `remoteEnabled` 调 `runRemote` / 本地索引 | 无需改 —— 注入后自动生效 |

另：`shell/UiState.kt` 是并发禁区（两个棒在改），接线批次落地前先与其当前版本 rebase 对齐行号。

## 6. 已知边界（如实列出，未标注完成）

- **`archived` 命中行暂无标记**：wire 的 `archived: Boolean` 已透传到 `SearchHit.archived`（测试钉死），但行 UI 不渲染徽标；会话列表过滤 archived 行，搜索**不许静默丢命中**（服务端已判 relevance），渲染归后续批次。
- **wire `message_id` 不进 UI 模型**：`SessionSummary` 无此字段（data/ 所有，本轮禁改），正文命中的消息定位留给「点进去」批次；snippet 文本本身已逐字可见。
- **远端行高亮是 query 在 verbatim 文本内的 indexOf 重定位**：与 SQL LIKE 的 ASCII 大小写折叠在极端 Unicode 大小写上有差 —— 只影响高亮样式范围，不影响哪些行出现（行集由服务端定）。
- **UI 未接线前远端路径是死代码**（§5）：所有远端行为只有测试触达；guard 是 `SearchState()` 默认参的零行为差。

## 7. commonMain 可移植性自查

`grep -rn "Math\.|\.format(|java\.|@Volatile" composeApp/src/commonMain/kotlin/vade/ui/search/` = 0 命中；`compileKotlinWasmJs` 全绿（含本轮 5 文件）。`kotlin.concurrent.Volatile` 未使用（SearchState 单 UI 线程契约，generation 不需要跨线程可见）。
