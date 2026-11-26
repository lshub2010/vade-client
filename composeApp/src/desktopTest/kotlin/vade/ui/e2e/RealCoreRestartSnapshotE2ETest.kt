package vade.ui.e2e

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import vade.ui.chat.ChatMessage
import vade.ui.chat.ChatRole
import vade.ui.data.AdapterHost
import vade.ui.data.AutoConnectingSessionSource
import vade.ui.data.ConnectionStateHost
import vade.ui.data.PairingAttempt
import vade.ui.data.PairingRestoreOutcome
import vade.ui.data.PairingRuntime
import vade.ui.data.PairingStore
import vade.ui.data.PairingStoreCodec
import vade.ui.data.PairingStores
import vade.ui.data.RawSourceStrings
import vade.ui.data.RealSessionSource
import vade.ui.data.RealSessionState
import vade.ui.data.SessionAttachedEvent
import vade.ui.data.StringPairingStore
import vade.ui.data.TurnCompletedEvent
import vade.ui.data.VadeEvent
import vade.ui.data.firstRunPairingAttempt
import vade.ui.data.sessionSourceFrom
import vade.ui.protocol.KtorTransportFactory
import vade.ui.protocol.VadeClientConfig
import vade.ui.protocol.VadeTransport
import vade.ui.protocol.VadeTransportFactory
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T2 · 「重启 → 重连 → 快照恢复」的**真 core**整链 e2e（对应 ALPHA §三 A2-3 / §九 T2）。
 *
 * 这条测试只做一件事，但每一步都必须是生产路径：
 *
 *  1. 真 Python core 启动（真 uvicorn、真 SQLite、真配对码；脚本化 MockLLMProvider 只替代模型）；
 *  2. 第一个客户端：`firstRunPairingAttempt` 用一次性码换 `__Host-ac_ui` → `PairingRuntime.install
 *     (persist = true)` 把 `{host, port, tls, trust, cookie}` 写进**真文件**（生产
 *     [StringPairingStore] 编码器，不是内存字段）→ `sessionSourceFrom(real …)` 建真源 →
 *     新建会话 → `ui/submit` 发一条带唯一标记的消息 → 等到 `turn.completed`；
 *  3. 「进程重启」：断开并丢弃第一个 [RealSessionSource]（client / adapters / moduleCatalog
 *     全为 null）、取消它自己的 collector scope、dispose 旧 factory、清空 [PairingRuntime] 与
 *     [PairingStores] 的**全部内存态**；磁盘上的凭据文件一个字节都不动；core 进程与 data_dir
 *     原样保留（这确实是「客户端重启」，不是「core 重启」）；
 *  4. 第二个客户端：`PairingRuntime.restoreStoredCredential` **只用磁盘 cookie** 探活（配置里
 *     `browserSession == null`，绝不再兑换一次性码）→ `sessionSourceFrom(RawSourceStrings(null,
 *     null, null, null))`，即默认启动的生产组合根 → 同一会话 attach；
 *  5. 断言：那个唯一标记的消息仍在（走 UI 真正用的读通道 `adapters.turns(id).transcript()
 *     .history(id)`）；重启后第一次 attach 是**快照**且 `from_seq` 逐字等于重启前客户端追平到的
 *     seq；随后从该水位续订 core 答 **replay**（`snapshot == null`）并一路应用到 attach 点 ——
 *     last_seq 连续，不重置、不倒退、不留洞。
 *
 * ## 为什么「快照锚点 == 重启前 seq」是准确的
 *
 * core 的 `ui/attach` 对**每次** attach 都会发出并持久化一条 `session.attached`（snapshot 分支的
 * seq = `snapshot.from_seq + 1`，replay 分支就是返回值 `from_seq`）。所以测试在重启前先做一次
 * `attach`（`RealSessionSource.attach`，带客户端自己跟踪的水位），拿到返回的 `from_seq`，再等到
 * 客户端**正好**应用到那一条（[awaitAppliedThrough]）—— 这一刻「客户端已应用水位」与「core 的
 * durable last_seq」相等，且是 core 自己回包里的数字，不靠 sleep 猜。重启后第一次 attach 的
 * `snapshot.from_seq` 必须等于它：差值不为 0 就是丢事件/重置计数器。
 *
 * ## 边界（不伪造）
 *
 *  - 不与 MockTransport 混用：所有连接都是真 WS，所有数据都来自真 core 的 SQLite；
 *  - 模板化的 `MockLLMProvider` 是 core 自己的测试注入点（与 `RealCoreE2ETest` 同款），只让
 *    回合在无外部 API 的情况下完成；
 *  - 不重启 core 进程，也不删 data_dir —— 这正是 T2 的口径：客户端死了，core 与库都在。
 */
class RealCoreRestartSnapshotE2ETest {

    private var core: CoreHandle? = null
    private var workDir: File? = null

    @BeforeTest
    fun freshBridge() {
        PairingRuntime.clearForTests()
    }

    @AfterTest
    fun cleanup() {
        runCatching { core?.kill() }
        core = null
        runBlocking { runCatching { PairingRuntime.pairedFactory?.dispose() } }
        PairingRuntime.clearForTests()
        workDir?.let { root -> runCatching { root.deleteRecursively() } }
        workDir = null
    }

    @Test
    fun aRestartedClientRestoresTheSameSessionFromDiskAndContinuesTheSeq(): Unit = runBlocking {
        val marker = "T2-重启后仍在-${System.nanoTime()}-唯一消息"
        val dir = Files.createTempDirectory("vade-t2-restart-").toFile()
        workDir = dir
        val pairingFile = File(dir, "pairing.json")
        val launcher = File(dir, "t2_restart_launcher.py")
        launcher.writeText(
            LAUNCHER_TEMPLATE
                .replace("@CORE_DIR@", RealCoreE2ETest.CORE_PKG_DIR)
                .replace("@PROTOCOL_DIR@", RealCoreE2ETest.PROTOCOL_PY_DIR)
                .replace("@REPLY@", SCRIPTED_REPLY),
        )
        val handle = CoreHandle(
            python = RealCoreE2ETest.PYTHON,
            launcher = launcher,
            dataDir = File(dir, "data"),
        )
        core = handle
        handle.start()
        val ready = handle.awaitReady(timeoutMs = CoreBootBudget.READY_TIMEOUT_MS)
        println(
            "E2E-T2-CORE-READY spawnMs=${handle.spawnMillis} waitMs=${handle.waitedMillis} " +
                "port=${ready.port} data_dir=${handle.dataDir.absolutePath}",
        )

        var firstReal: RealSessionSource? = null
        var secondReal: RealSessionSource? = null
        var restoredFactory: CountingFactory? = null
        var collector: Job? = null

        try {
            // ---------------------------------------------------------- 第一个客户端：真配对
            // 磁盘凭据走生产 StringPairingStore（编码/严格解码/文件读写都是生产代码）。
            // 文件放在本用例自己的临时目录：不碰开发者本机的真凭据。
            val firstStore = pairingStoreAt(pairingFile)
            PairingStores.storeOverride = firstStore
            val pairAttempt = firstRunPairingAttempt(
                raw = RawSourceStrings("real", "127.0.0.1", ready.port.toString(), ready.pairCode),
                enteredCode = ready.pairCode,
            )
            val paired = assertIs<PairingAttempt.Connected>(
                pairAttempt,
                "真 core 必须接受一次性配对码（拒绝原因：${(pairAttempt as? PairingAttempt.Rejected)?.cause}）",
            )
            PairingRuntime.install(
                factory = paired.factory,
                host = "127.0.0.1",
                port = ready.port,
                tls = paired.tls,
                trustMode = paired.trustMode,
                persist = true,
            )
            assertTrue(pairingFile.isFile, "配对成功后凭据必须真的落盘：${pairingFile.absolutePath}")
            val onDisk = assertNotNull(
                PairingStoreCodec.decode(pairingFile.readText()),
                "磁盘上的凭据必须能被生产解码器读回（不是半份 JSON）",
            )
            val cookie = assertNotNull(paired.factory.sessionCookie, "配对成功后 factory 必须持有 __Host-ac_ui")
            assertTrue(cookie.isNotBlank(), "cookie 不得为空")
            assertEquals(cookie, onDisk.cookie, "落盘的就是这次配对的 cookie")
            assertEquals("127.0.0.1", onDisk.host)
            assertEquals(ready.port, onDisk.port)
            assertFalse(onDisk.tls, "本用例是明文回环")
            assertFalse(
                pairingFile.readText().contains(ready.pairCode),
                "一次性配对码绝不许落盘 —— 落盘的只能是 cookie",
            )
            assertTrue(cookie != ready.pairCode, "cookie 不得等于配对码")
            println("E2E-T2-PAIRED pairing_file=${pairingFile.absolutePath} cookie_bytes=${cookie.length}")

            // ---------------------------------------------------------- 第一个客户端：发消息
            val first = assertIs<AutoConnectingSessionSource>(
                sessionSourceFrom(RawSourceStrings("real", "127.0.0.1", ready.port.toString(), null)),
                "第一个客户端必须由生产组合根（sessionSourceFrom）装配",
            )
            val real1 = first.delegate
            firstReal = real1
            assertEquals(ready.port, real1.config.port)
            real1.connect()
            withTimeout(CoreBootBudget.WAIT_TIMEOUT_MS) {
                real1.state.first { it is RealSessionState.Connected }
            }
            assertNotNull(first.adapters, "连接后必须建出真适配器包（组合根的生产装配）")

            val events = Channel<VadeEvent>(Channel.UNLIMITED)
            val subscribed = CompletableDeferred<Unit>()
            collector = CoroutineScope(Dispatchers.Default).launch {
                first.observeEvents()
                    .onStart { subscribed.complete(Unit) }
                    .collect { events.trySend(it) }
            }
            subscribed.await()

            val sessionId = first.createSession()
            assertTrue(sessionId.isNotBlank(), "core 必须铸出一个会话 id")
            val submit = real1.submit(sessionId, marker)
            val completed = awaitTurnCompleted(events, submit.turnId, CoreBootBudget.WAIT_TIMEOUT_MS)
            println("E2E-T2-TURN session=$sessionId turn=${completed.turnId} completed_seq=${completed.seq}")

            // 落库证据（重启前）：走 UI 真正用的读通道（RealAdapters → turns().transcript()）。
            val historyBefore = historyViaAdapters(first, sessionId)
            assertUserMessage(historyBefore, marker, "重启前")
            assertAssistantReply(historyBefore, "重启前")

            // 重启前的「断点」：从客户端自己跟踪的水位续订。core 必须答 replay ——
            // 这同时证明这个水位对 core 来说是真实且连续的（covers 的下界与上界都成立）。
            // P1-2：光看 resume.fromSeq 不够——客户端即使送 0，core 也会回 durable+1。
            // 这里同时钉住「客户端水位非零」和「本次 attach 的 session.attached.replay_count == 0」，
            // 两者合起来才能区分「按水位续订」与「全量重放」。
            val before = assertNotNull(real1.client, "重启前客户端必须还在").lastSeqFor(sessionId)
            assertTrue(before > 0, "重启前客户端必须已经应用过事件（水位非零），实际=$before")
            val resume = real1.attach(sessionId)
            assertNull(resume.snapshot, "同一客户端从自己的水位续订必须是 replay（snapshot = null）")
            assertTrue(resume.fromSeq > 0, "replay 的 attach 点必须是非零 seq")
            val attached = awaitSessionAttached(events, sessionId, CoreBootBudget.WAIT_TIMEOUT_MS)
            assertEquals(0, attached.replayCount, "按水位续订的 replay_count 必须为 0（不是全量重放）")
            assertEquals(before + 1, resume.fromSeq, "replay 必须从客户端水位 +1 开始")
            awaitAppliedThrough(real1, sessionId, resume.fromSeq, CoreBootBudget.WAIT_TIMEOUT_MS)
            val anchor = resume.fromSeq
            println("E2E-T2-FIRST-ANCHOR last_seq=$anchor")

            // ---------------------------------------------------------- 模拟客户端进程重启
            collector?.cancel()
            collector = null
            real1.disconnect()
            assertNull(real1.client, "重启后第一个客户端不得再持有任何 client")
            assertNull(real1.adapters, "重启后第一个客户端不得再持有适配器包")
            assertNull(real1.moduleCatalog, "重启后模块清单也必须随 client 一起消失")
            cancelOwnScope(real1)
            // 旧 factory 随进程死掉：dispose 之后 isUsable=false，任何迟到的重连都不可能复活它。
            paired.factory.dispose()
            assertFalse(paired.factory.isUsable, "旧 factory 必须随「进程」一起退休")

            PairingRuntime.clearForTests()
            assertNull(PairingRuntime.pairedFactory, "重启 = 桥里没有任何内存 factory")
            assertNull(PairingStores.cached, "重启 = 内存里没有任何缓存凭据")
            // 新的 store 实例只能从同一路径的**文件**读 —— 上一次实例已随 clearForTests 丢弃。
            PairingStores.storeOverride = pairingStoreAt(pairingFile)
            assertTrue(firstStore !== PairingStores.current(), "重启必须换一个新的 store 实例（内存态丢弃）")
            assertEquals(
                cookie,
                assertNotNull(PairingStores.load(), "重启后必须能从磁盘读回凭据").cookie,
                "磁盘上的 cookie 必须与重启前一致",
            )

            assertTrue(handle.isAlive, "本测试只重启客户端：core 必须一直活着")
            assertTrue(handle.dataDir.isDirectory, "data_dir 必须保留（不是新 core、不是新库）")
            assertTrue(pairingFile.isFile, "凭据文件必须保留（重启不删凭据）")
            println(
                "E2E-T2-RESTART client=${real1.client} adapters=${real1.adapters} " +
                    "bridge=${PairingRuntime.pairedFactory} disk_cookie=kept core_alive=${handle.isAlive}",
            )

            // ---------------------------------------------------------- 第二个客户端：只用磁盘 cookie
            val probedConfigs = mutableListOf<VadeClientConfig>()
            val outcome = PairingRuntime.restoreStoredCredential(
                raw = RawSourceStrings(null, null, null, null),
                probeTimeoutMs = RESTORE_PROBE_TIMEOUT_MS,
                transportFactory = { config ->
                    probedConfigs += config
                    CountingFactory(KtorTransportFactory(config))
                },
            )
            val restored = assertIs<PairingRestoreOutcome.Restored>(
                outcome,
                "磁盘上的好 cookie 必须恢复成功（实际：$outcome）",
            )
            val restoredConfig = probedConfigs.single()
            val probedCookie = assertNotNull(restoredConfig.sessionCookie, "恢复探测必须带 cookie 配置")
            assertEquals(cookie, probedCookie.value, "恢复探测带的就是磁盘上那张 cookie")
            assertNull(restoredConfig.browserSession, "恢复路径没有一次性码 —— 也绝不许再 POST /ui/session")
            assertEquals("127.0.0.1", restored.host)
            assertEquals(ready.port, restored.port)
            val bridgeFactory = assertIs<CountingFactory>(
                PairingRuntime.pairedFactory,
                "桥里装的必须是这次恢复出来的 cookie factory",
            )
            assertEquals(cookie, assertNotNull(bridgeFactory.sessionCookie), "恢复出来的 factory 仍持有那张 cookie")
            restoredFactory = bridgeFactory
            println("E2E-T2-RESTORED host=${restored.host} port=${restored.port} cookie_bytes=${cookie.length}")

            // 生产组合根（默认启动参数 = source/host/port/code 全部为空）。
            val bootRaw = RawSourceStrings(null, null, null, null)
            val second = assertIs<AutoConnectingSessionSource>(
                sessionSourceFrom(bootRaw),
                "重启后的默认启动必须由组合根装配成真源（不许手写 fake host）",
            )
            // 编译期钉住两条生产接口：少了 AdapterHost，真源下五个屏幕会静默退回 fixtures；
            // 少了 ConnectionStateHost，顶栏/中段连接态在生产形态恒为 null（T1）。
            val adapterHost: AdapterHost = second
            val connectionHost: ConnectionStateHost = second
            val real2 = second.delegate
            secondReal = real2
            assertTrue(
                connectionHost.connectionState === real2.state,
                "连接态必须是同一个 StateFlow（不是副本）",
            )
            assertEquals("127.0.0.1", real2.config.host)
            assertEquals(ready.port, real2.config.port)
            assertNull(real2.config.browserSession, "重启后没有码可带：认证只能来自磁盘 cookie")

            real2.connect()
            withTimeout(CoreBootBudget.WAIT_TIMEOUT_MS) {
                real2.state.first { it is RealSessionState.Connected }
            }
            assertNotNull(adapterHost.adapters, "重启后必须重新建出真适配器包")
            val rowsAfterRestart = second.sessions(includeArchived = false)
            assertTrue(
                rowsAfterRestart.any { it.id == sessionId },
                "重启后 session/list 必须仍列出那个会话（${rowsAfterRestart.map { it.id }}）",
            )
            assertTrue(
                (restoredFactory?.connectCalls ?: 0) >= 2,
                "第二个客户端必须经过「恢复出来的 cookie factory」建连接（1 次探活 + 1 次真连接），" +
                    "实际 connectCalls=${restoredFactory?.connectCalls}",
            )

            // ---------------------------------------------------------- 同一会话：快照 + 消息 + seq
            val attachAfterRestart = real2.attach(sessionId)
            val snapshot = assertNotNull(
                attachAfterRestart.snapshot,
                "重启后第一次 attach 必须是快照（这条连接没有可续订的游标）",
            )
            assertEquals(sessionId, attachAfterRestart.sessionId, "attach 的必须是重启前那个会话")
            assertEquals(
                anchor,
                attachAfterRestart.fromSeq,
                "快照锚点必须逐字等于重启前客户端追平到的 seq（last_seq 连续：不重置、不倒退）",
            )
            println(
                "E2E-T2-SNAPSHOT session=$sessionId from_seq=${attachAfterRestart.fromSeq} " +
                    "seq_counter=${snapshot.session?.seqCounter} snapshots=${attachAfterRestart.snapshot != null}",
            )

            val historyAfter = historyViaAdapters(second, sessionId)
            assertUserMessage(historyAfter, marker, "重启后")
            assertAssistantReply(historyAfter, "重启后")
            println("E2E-T2-HISTORY-AFTER-RESTART messages=${historyAfter.size} marker_found=true")

            // 重启后的续订：core 对「客户端已应用水位」必须答 replay，attach 点继续前进。
            val resumedAfterRestart = real2.attach(sessionId)
            assertNull(resumedAfterRestart.snapshot, "重启后的续订也必须是 replay（不是再来一份快照）")
            assertTrue(
                resumedAfterRestart.fromSeq > attachAfterRestart.fromSeq,
                "replay 的 attach 点必须继续前进：${resumedAfterRestart.fromSeq} 应 > ${attachAfterRestart.fromSeq}",
            )
            awaitAppliedThrough(real2, sessionId, resumedAfterRestart.fromSeq, CoreBootBudget.WAIT_TIMEOUT_MS)
            println(
                "E2E-T2-SEQ-CHAIN anchor=$anchor replay_from=${resumedAfterRestart.fromSeq} " +
                    "applied=${real2.client?.lastSeqFor(sessionId)}",
            )
        } finally {
            collector?.cancel()
            runCatching { secondReal?.disconnect() }
            runCatching { firstReal?.disconnect() }
            runCatching { restoredFactory?.dispose() }
            runCatching { handle.kill() }
            assertTrue(!handle.isAlive, "core 进程在测试结束后必须被干净回收（不留孤儿）")
        }
    }

    // ------------------------------------------------------------------ 断言helper

    /** UI 真正用的读通道：组合根 → RealAdapters → turns(id).transcript().history(id)。 */
    private suspend fun historyViaAdapters(
        source: AutoConnectingSessionSource,
        sessionId: String,
    ): List<ChatMessage> {
        val adapters = assertNotNull(source.adapters, "生产适配器包必须已建立")
        val transcript = assertNotNull(
            adapters.turns(sessionId).transcript(),
            "真 turns 窄缝必须交出聊天读通道（transcript）",
        )
        return transcript.history(sessionId)
    }

    private fun assertUserMessage(history: List<ChatMessage>, marker: String, phase: String) {
        assertTrue(
            history.any { it.role == ChatRole.User && it.bodyText.contains(marker) },
            "$phase 的历史里必须还有那条唯一消息（$marker）；实际：${history.map { it.role to it.bodyText }}",
        )
    }

    private fun assertAssistantReply(history: List<ChatMessage>, phase: String) {
        assertTrue(
            history.any { it.role == ChatRole.Assistant && it.bodyText.contains(SCRIPTED_REPLY) },
            "$phase 的历史里必须有脚本化 provider 的回复（$SCRIPTED_REPLY）；实际：" +
                "${history.map { it.role to it.bodyText }}",
        )
    }

    /** 收集事件直到本会话的 `session.attached`（attach 回包与事件谁先到都行）。 */
    private suspend fun awaitSessionAttached(
        channel: Channel<VadeEvent>,
        sessionId: String,
        timeoutMs: Long,
    ): SessionAttachedEvent {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val event = withTimeoutOrNull((deadline - System.currentTimeMillis()).coerceAtLeast(1)) {
                channel.receive()
            } ?: break
            if (event is SessionAttachedEvent && event.sessionId == sessionId) return event
        }
        fail("session $sessionId 在 ${timeoutMs}ms 内没有等到 session.attached")
    }

    /** 收集事件直到本回合的 `turn.completed`；超时把已收到的类型写进消息（不静默）。 */
    private suspend fun awaitTurnCompleted(
        channel: Channel<VadeEvent>,
        turnId: String,
        timeoutMs: Long,
    ): TurnCompletedEvent {
        val seen = mutableListOf<String>()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val event = withTimeoutOrNull((deadline - System.currentTimeMillis()).coerceAtLeast(1)) {
                channel.receive()
            } ?: break
            seen += event::class.simpleName ?: "?"
            if (event is TurnCompletedEvent && event.turnId == turnId) return event
        }
        fail("turn $turnId 在 ${timeoutMs}ms 内没有等到 turn.completed（收到：$seen）")
    }

    /**
     * 等到客户端**正好**应用到 [seq]。
     *
     * 先 `>=` 轮询再断言相等：多出来说明测试期间有未预期的事件（那会让 seq 链的断言失效，
     * 必须红），不是「等一下就好」。
     */
    private suspend fun awaitAppliedThrough(
        source: RealSessionSource,
        sessionId: String,
        seq: Long,
        timeoutMs: Long,
    ) {
        withTimeout(timeoutMs) {
            while ((source.client?.lastSeqFor(sessionId) ?: 0L) < seq) delay(10)
        }
        val applied = source.client?.lastSeqFor(sessionId)
            ?: fail("客户端已不在：读不到 seq=$seq 的应用水位")
        assertEquals(seq, applied, "客户端必须正好追平到 seq=$seq（多出来 = 有未预期的后续事件）")
    }

    /**
     * 重启模拟里唯一一处伸手到生产对象内部：取消 [RealSessionSource] 自己的 collector scope。
     *
     * `disconnect()` 已逐个 cancel 了 `collectorJobs`，但 scope 本身没有公开句柄（生产代码不为
     * 测试开口子）。这里用反射把它也 cancel，语义是「这个进程里属于该源的协程全部终止」。
     * 字段读不到就**直接红**（不静默跳过、不放宽断言）。
     */
    private fun cancelOwnScope(source: RealSessionSource) {
        val field = runCatching { RealSessionSource::class.java.getDeclaredField("scope") }
            .getOrElse { fail("RealSessionSource.scope 不可读，无法模拟进程内协程一起终止：$it") }
        field.isAccessible = true
        val scope = runCatching { field.get(source) as? CoroutineScope }
            .getOrNull()
            ?: fail("RealSessionSource.scope 为 null：restart 模拟拿不到可取消的 scope")
        scope.cancel()
        assertFalse(scope.isActive, "第一个客户端的 collector scope 必须已被取消")
    }

    /**
     * 生产 [StringPairingStore] + 一个真实文件。
     *
     * 编码/严格解码/损坏即 `null` 全部走生产代码；这里只提供「文件放在哪、怎么写」——
     * 与桌面 actual 同一形状（`PairingStore.desktop.kt` 也是 `StringPairingStore` + 文件）。
     */
    private fun pairingStoreAt(file: File): PairingStore = object : StringPairingStore(
        read = { if (file.isFile) file.readText() else null },
        write = { text -> file.writeText(text) },
        erase = { file.delete() },
    ) {}

    /**
     * 恢复探针的真 factory 外面套一层计数：证明第二个客户端**确实用它**建了连接
     * （探活 1 次 + 真连接 ≥1 次），而不是组合根偷偷自建了一个没有 cookie 的 factory。
     * 传输本身仍是 [KtorTransportFactory]（真 WS、真 cookie），没有任何 MockTransport。
     */
    private class CountingFactory(private val inner: VadeTransportFactory) : VadeTransportFactory {

        @Volatile
        var connectCalls: Int = 0
            private set

        override val isUsable: Boolean get() = inner.isUsable

        override val sessionCookie: String? get() = inner.sessionCookie

        override suspend fun connect(): VadeTransport {
            connectCalls += 1
            return inner.connect()
        }

        override suspend fun revokeSession() = inner.revokeSession()

        override suspend fun dispose() {
            inner.dispose()
        }
    }

    private companion object {

        /**
         * 恢复探针的上限（生产值是 [vade.ui.data.PAIRING_PROBE_TIMEOUT_MS] = 5 s）。
         *
         * 这里放宽到 30 s 是**等待预算**而不是断言放宽：本机在 12 路并发下连一次真 core 曾被
         * 放大到十几秒（见 [CoreBootBudget] 的实测）。要证的是「磁盘 cookie 能恢复」这条路径，
         * 不是「探针一定在 5 s 内往返」。条件驱动：探针一成功立刻返回。
         */
        const val RESTORE_PROBE_TIMEOUT_MS: Long = 30_000L

        /** 与 launcher 里的 `REPLY` 逐字对应 —— 断言依赖这种镜像关系。 */
        const val SCRIPTED_REPLY: String = "T2 重启快照：脚本化回复已落库。"

        /**
         * 真 core 启动器（与 `RealCoreE2ETest` / `RealCoreUiE2ETest` 同款协议）：
         *   {"stage":"boot","data_dir":…}
         *   {"stage":"ready","port":N,"pair_code":"…"}
         *   {"stage":"fatal","error":"…"}
         *
         * 不预置会话：本测试的会话必须由客户端 `ui/attach(session_id = null)` 铸出，那才是
         * A4-1 的生产路径。模型用 core 自己的 `_build_llm` 注入点换脚本化 Mock（绝不调外部 API）。
         */
        private val LAUNCHER_TEMPLATE = """
            import argparse, asyncio, json, sys, threading, time, traceback

            CORE_DIR = r"@CORE_DIR@"
            PROTOCOL_DIR = r"@PROTOCOL_DIR@"
            sys.path.insert(0, PROTOCOL_DIR)
            sys.path.insert(0, CORE_DIR)

            REPLY = "@REPLY@"
            USAGE = {"prompt_tokens": 7, "completion_tokens": 11, "total_tokens": 18}

            def emit(obj):
                print(json.dumps(obj, ensure_ascii=False), flush=True)

            def main():
                ap = argparse.ArgumentParser()
                ap.add_argument("--data-dir", required=True)
                ap.add_argument("--port", type=int, default=0)
                args = ap.parse_args()
                emit({"stage": "boot", "data_dir": args.data_dir})

                import vade.session as session_pkg
                from vade.llm.base import Delta
                from vade.llm.mock import MockLLMProvider

                def _build_llm(cfg, **kwargs):
                    return MockLLMProvider([[
                        Delta(text=REPLY),
                        Delta(text=" -- done.", usage=USAGE, finish_reason="stop"),
                    ]])

                session_pkg._build_llm = _build_llm

                import uvicorn
                from vade import __main__ as vade_main
                from vade.app import App, install_components, make_rpc_handler
                from vade.config import load_config
                from vade.identity.pairing import Purpose
                from vade.transport.server import CoreApp

                config = load_config(cli={
                    "core.data_dir": args.data_dir,
                    "core.listen": "127.0.0.1:" + str(args.port),
                    "dev.allow_all": True,
                })
                app = App(config)
                asyncio.run(app.startup())
                installed = install_components(app, strict=False)
                if "session" not in installed:
                    raise RuntimeError("session component did not install: " + str(sorted(installed)))
                pair_code = app.pairing.create(Purpose.BROWSER, ttl=600)

                core_app = CoreApp(
                    config=config,
                    identity=app.identity,
                    registry=app.registry,
                    pairing=app.pairing,
                    whitelist=app.whitelist,
                    make_crypto=vade_main.build_module_crypto(app),
                    on_rpc=make_rpc_handler(app),
                    policy_mode="allow_all",
                )
                server = uvicorn.Server(uvicorn.Config(
                    core_app, host="127.0.0.1", port=args.port, log_level="warning",
                    lifespan="off", proxy_headers=False,
                ))
                thread_ = threading.Thread(target=server.run, daemon=True)
                thread_.start()
                deadline = time.time() + 20
                while not server.started:
                    if time.time() > deadline or not thread_.is_alive():
                        raise RuntimeError("uvicorn did not start within 20s")
                    time.sleep(0.02)
                port = server.servers[0].sockets[0].getsockname()[1]
                emit({"stage": "ready", "port": port, "pair_code": pair_code})
                while thread_.is_alive():
                    time.sleep(0.2)
                return 0

            if __name__ == "__main__":
                try:
                    sys.exit(main())
                except SystemExit:
                    raise
                except BaseException:
                    emit({"stage": "fatal", "error": traceback.format_exc(limit=8)})
                    sys.exit(1)
        """.trimIndent()
    }
}
