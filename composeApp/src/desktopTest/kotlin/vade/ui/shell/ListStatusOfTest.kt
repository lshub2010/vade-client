package vade.ui.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import vade.ui.data.RawSourceStrings
import vade.ui.data.SourceConfig
import vade.ui.data.VadeErrorCategory
import vade.ui.destinations.ListStatus
import vade.ui.protocol.VadeErrorCodes

/**
 * g-first-run · P1 + P2 的判定层：`listStatusOf` 把「源 / 读阶段 / 分类错误」投影成列表头
 * 那条横幅。
 *
 * 这些测试针对的是**首次真实验收抓到的谎话与沉默**（`shots/g-desktop-live/RESULT.md`）：
 *
 *  - P1：横幅硬编码「假数据 · 未连接 core」，真源连着真 core 时照显；
 *  - P2：`sessionsError` 没有任何 composable 消费，连不上时界面零反馈（22 秒无错误）。
 *
 * 所以这里的每一条都同时钉两件事：**真源成功时不许出现「假数据」**，**失败时必须留下可读的
 * 文字**（包括 loader 分两次发布 phase/error 造成的半发布窗口）。
 *
 * 显示文本本身（渲染成什么节点）在 `destinations/ListStatusBannerTest` 里钉。
 */
class ListStatusOfTest {

    // ------------------------------------------------------------------ 测试替身

    /** 一次真实无码启动会拿到的失败：`AutoConnectingSessionSource` 的 15 s 超时。 */
    private val unreachable = LoadError(
        category = VadeErrorCategory.NeedsReconnect,
        code = VadeErrorCodes.UNAVAILABLE,
        detail = "core at 127.0.0.1:18755 not reachable within 15000ms (auto-connect)",
    )

    private fun raw(
        host: String? = null,
        port: String? = null,
        pairCode: String? = null,
    ): RawSourceStrings = RawSourceStrings(
        source = "real",
        host = host,
        port = port,
        pairCode = pairCode,
    )

    /** 判定一次的简写；默认给一份「本次运行」的原始配置。 */
    private fun statusOf(
        phase: LoadPhase,
        error: LoadError? = null,
        fixture: Boolean = false,
        hasRows: Boolean = false,
        raw: () -> RawSourceStrings = { raw(host = "127.0.0.1", port = "18755") },
    ): ListStatus =
        listStatusOf(fixture = fixture, phase = phase, error = error, hasRows = hasRows, raw = raw)

    // ------------------------------------------------------- 1 · 只有假源能说「假数据」

    @Test
    fun fakeSourceAlwaysSaysFixtures() {
        // 假源下 sessionsPhase 恒为 Ready（UiState 里 loader == null），但三个阶段都要落在
        // Fixtures：这条横幅的前提是「源是假的」，不是「读到了哪一步」。
        for (phase in LoadPhase.entries) {
            assertEquals(
                ListStatus.Fixtures,
                statusOf(phase = phase, fixture = true),
                "假源 + $phase 必须是 Fixtures（这是唯一允许说「假数据」的分支）",
            )
        }
    }

    @Test
    fun fakeSourceNeverReadsTheEnvironment() {
        // fixtures 路径必须保持逐字节无副作用：连原始配置都不许去读（桌面 actual 是
        // System.getenv，Android 是 intent extras 快照）。`raw` 是惰性的唯一原因。
        val status = listStatusOf(
            fixture = true,
            phase = LoadPhase.Ready,
            error = null,
            hasRows = false,
            raw = { throw AssertionError("假源路径读了原始配置——fixtures 必须无副作用") },
        )
        assertEquals(ListStatus.Fixtures, status)
    }

    // ------------------------------------------------------- 2 · 真源成功 ⇒ 没有横幅

    @Test
    fun realReadyIsConnectedSoNoBannerIsDrawn() {
        val status = statusOf(phase = LoadPhase.Ready)
        assertEquals(
            ListStatus.Connected,
            status,
            "真源读到过答案（哪怕 0 行）就是已连接——已连接还挂「未连接」的横幅正是 P1 的谎话",
        )
    }

    @Test
    fun aStrayErrorWithAReadyPhaseIsStillAFailure() {
        // SessionsLoader 先写 phase 再写 error；反过来读到「Ready + error」不该发生，但
        // 一旦发生，宽容的方向必须是**显示错误**，不是假装成功。
        val status = statusOf(phase = LoadPhase.Ready, error = unreachable)
        assertIs<ListStatus.Failed>(status, "phase=Ready 但带着分类错误，仍必须当失败处理")
    }

    @Test
    fun failureWithRowsStillOnScreenStillShowsTheError() {
        // stale-while-revalidate：刷新失败时 loader **保留**上一次的行（这是对的），所以
        // 「有行 + Failed」是正常组合。错误必须照显——有行不是沉默的理由，那一屏的行已经
        // 停止更新了，用户得知道。
        val failed = assertIs<ListStatus.Failed>(
            statusOf(phase = LoadPhase.Failed, error = unreachable, hasRows = true),
        )
        assertEquals("无法连接 core", failed.headline)
    }

    // ------------------------------------------------------- 3 · 连接中要说在等谁

    @Test
    fun realLoadingNamesTheEndpointFromThisRunsConfiguration() {
        val status = statusOf(
            phase = LoadPhase.Loading,
            raw = { raw(host = "10.0.0.5", port = "18755") },
        )
        val connecting = assertIs<ListStatus.Connecting>(status)
        assertEquals("10.0.0.5:18755", connecting.endpoint, "连接中必须点名本次运行的 core，不是默认值")
    }

    @Test
    fun realLoadingFallsBackToTheDocumentedDefaultsWhenHostAndPortAreMissing() {
        val status = statusOf(phase = LoadPhase.Loading, raw = { raw(host = "  ", port = null) })
        val connecting = assertIs<ListStatus.Connecting>(status)
        assertEquals(
            "${SourceConfig.DEFAULT_HOST}:${SourceConfig.DEFAULT_PORT}",
            connecting.endpoint,
            "缺省值必须与 SourceConfig 的默认值同源（127.0.0.1:8443），不能另写一份",
        )
    }

    @Test
    fun rowsAlreadyOnScreenSilenceTheBannerEvenWhileThePhaseSaysLoading() {
        // 本棒实测的时序（见 listStatusOf 的 KDoc）：组合是一次 MVCC 快照，loader 在**首帧
        // 组合进行中**落盘的写入那次组合看不见，也不再失效——`rows` 先落盘（看得到）、
        // `phase=Ready` 后落盘（看不到）。于是「屏上有真行 + phase 还写着 Loading」是一个
        // 组合真的会读到的组合。
        //
        // 这时**必须静默**：列表已经是真的，再顶一句「正在连接 core …」就是把刚修掉的谎话
        // 换了个说法。这条同时是 fail-safe：任何由 phase 单独推出的字都不可能长期驻留。
        val status = statusOf(phase = LoadPhase.Loading, hasRows = true)
        assertEquals(ListStatus.Connected, status, "有行就静默：这条判据不依赖任何后续写入")
    }

    // ------------------------------------------------------- 4 · 失败必须可读

    @Test
    fun realFailedCarriesTheClassifiedErrorVerbatim() {
        val failed = assertIs<ListStatus.Failed>(statusOf(phase = LoadPhase.Failed, error = unreachable))
        assertEquals("无法连接 core", failed.headline, "需要重连 = 用户视角的「连不上」")
        assertTrue(failed.detail.contains(VadeErrorCategory.NeedsReconnect.label), "分类文案要在: ${failed.detail}")
        assertTrue(failed.detail.contains(VadeErrorCodes.UNAVAILABLE), "C-04 code 要在: ${failed.detail}")
        assertTrue(
            failed.detail.contains("not reachable within 15000ms"),
            "传输原话要在（不许被替换成一句泛泛的「出错了」）: ${failed.detail}",
        )
    }

    @Test
    fun realFailedWithAHalfPublishedErrorPairIsStillReadable() {
        // 半发布窗口：phase 已经是 Failed，error 还没写。这是组合真的能读到的状态
        // （UiStateSourceWiringTest 的 file doc 记过同一对写）。此时**不许空白**。
        val failed = assertIs<ListStatus.Failed>(statusOf(phase = LoadPhase.Failed, error = null))
        assertTrue(failed.headline.isNotBlank(), "headline 不许为空")
        assertTrue(failed.detail.isNotBlank(), "detail 不许为空——空白的失败态正是 P2")
        assertTrue(failed.remedy.isNotBlank(), "自救命令不许为空")
        assertFalse(failed.detail.contains("假数据"), "失败态不许拿「假数据」当解释")
    }

    @Test
    fun fatalErrorIsNotReportedAsAConnectionProblem() {
        val fatal = LoadError(
            category = VadeErrorCategory.Fatal,
            code = VadeErrorCodes.PROTOCOL_MISMATCH,
            detail = "client 0.1.0-alpha.1 speaks protocol 2, core speaks 3",
        )
        val failed = assertIs<ListStatus.Failed>(statusOf(phase = LoadPhase.Failed, error = fatal))
        assertEquals(
            "无法读取会话列表",
            failed.headline,
            "版本不匹配不是「连不上」——把 Fatal 说成连不上会误导用户去重连一个能连的 core",
        )
        assertTrue(failed.detail.contains("protocol 2"), "致命原因也要原样可见: ${failed.detail}")
    }

    // ------------------------------------------- 5 · 自救命令（配对入口的替代品）

    @Test
    fun remedyCarriesACopyableCommandWithBothEnvironmentVariables() {
        val failed = assertIs<ListStatus.Failed>(statusOf(phase = LoadPhase.Failed, error = unreachable))
        assertTrue(failed.remedy.contains("VADE_SOURCE=real"), "必须给出 VADE_SOURCE: ${failed.remedy}")
        assertTrue(failed.remedy.contains("VADE_PAIR_CODE="), "必须给出 VADE_PAIR_CODE: ${failed.remedy}")
        assertTrue(failed.remedy.contains("gradle :composeApp:run"), "必须是能照抄的一整行: ${failed.remedy}")
        assertTrue(
            failed.remedy.contains("pair_code="),
            "必须说明配对码从 core 的哪一行取: ${failed.remedy}",
        )
    }

    @Test
    fun remedyUsesThisRunsEndpointNotTheDefaultOne() {
        // 验收实跑用的是 18755，不是文档默认的 8443。照抄一条指向 8443 的命令 =
        // 把同一个失败重演一遍，所以命令里的 host/port 只能来自本次运行的配置。
        val failed = assertIs<ListStatus.Failed>(
            statusOf(
                phase = LoadPhase.Failed,
                error = unreachable,
                raw = { raw(host = "192.168.1.20", port = "18755", pairCode = "abc123456") },
            ),
        )
        assertTrue(failed.remedy.contains("VADE_HOST=192.168.1.20"), "host 要取本次配置: ${failed.remedy}")
        assertTrue(failed.remedy.contains("VADE_PORT=18755"), "port 要取本次配置: ${failed.remedy}")
        assertFalse(failed.remedy.contains("VADE_PORT=8443"), "不许把默认端口塞进自救命令: ${failed.remedy}")
    }

    @Test
    fun remedyFallsBackToTheDocumentedDefaults() {
        val failed = assertIs<ListStatus.Failed>(
            statusOf(phase = LoadPhase.Failed, error = unreachable, raw = { raw() }),
        )
        assertTrue(failed.remedy.contains("VADE_HOST=127.0.0.1"), "缺省 host: ${failed.remedy}")
        assertTrue(failed.remedy.contains("VADE_PORT=8443"), "缺省 port: ${failed.remedy}")
    }

    @Test
    fun remedyNeverEchoesThePairCodeItself() {
        // 配对码是一次性凭证，界面/截图/测试日志都不许回显它——验收报告自己也只写了
        // 「前 2 字符 + 长度」。命令里永远是 `<配对码>` 占位。
        val secret = "袋谦-Zx9Qw2"
        val failed = assertIs<ListStatus.Failed>(
            statusOf(phase = LoadPhase.Failed, error = unreachable, raw = { raw(pairCode = secret) }),
        )
        assertFalse(failed.remedy.contains(secret), "配对码被回显了: ${failed.remedy}")
        assertFalse(failed.detail.contains(secret), "配对码被写进错误详情了: ${failed.detail}")
        assertTrue(failed.remedy.contains("<配对码>"), "占位符要在: ${failed.remedy}")
    }

    @Test
    fun remedyExplainsThatAnAlreadySuppliedCodeMayHaveExpired() {
        // 已经给了码仍失败 → 一次性码过期是用环境变量这条路最常见的死法，必须点名。
        val withCode = assertIs<ListStatus.Failed>(
            statusOf(phase = LoadPhase.Failed, error = unreachable, raw = { raw(pairCode = "abc123456") }),
        )
        assertTrue(withCode.remedy.contains("过期"), "已给码的场景要点明「可能过期」: ${withCode.remedy}")

        // 没给码的场景不必说「过期」——那句话会让用户去怀疑一个他还没做过的事。
        val withoutCode = assertIs<ListStatus.Failed>(
            statusOf(phase = LoadPhase.Failed, error = unreachable, raw = { raw() }),
        )
        assertFalse(withoutCode.remedy.contains("过期"), "没给过码就别提过期: ${withoutCode.remedy}")
    }

    @Test
    fun remedyNamesTheOtherTwoTargetsChannels() {
        // 三个目标的通道不一样（desktop env / Android intent extra / wasm URL query，见
        // g-live PLAYBOOK §2）。桌面命令 + 一句「另两端怎么传」是诚实的写法；
        // 假装一条命令通吃会在 Android/wasm 上把用户带沟里。
        val failed = assertIs<ListStatus.Failed>(statusOf(phase = LoadPhase.Failed, error = unreachable))
        assertTrue(failed.remedy.contains("vade.pair_code"), "Android 的 intent extra 要提: ${failed.remedy}")
        assertTrue(failed.remedy.contains("pair_code=<码>"), "Wasm 的 query 要提: ${failed.remedy}")
    }
}
