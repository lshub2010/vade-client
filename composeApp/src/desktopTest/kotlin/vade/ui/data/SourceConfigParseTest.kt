package vade.ui.data

import kotlinx.coroutines.runBlocking
import vade.ui.protocol.VadeClientConfig
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException
import kotlin.test.Test
import kotlin.test.BeforeTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * g-live · the unified three-target switch (`data/SourceConfig.kt`) under test.
 *
 * One grammar, one `SourceSpec` type, three raw channels (desktop env / Android
 * intent extras / wasm URL query). These tests pin the SHARED code; the platform
 * actuals are one-liners over these functions, so the three targets cannot give
 * different answers for the same input (pinned by the parameterised equality test).
 */
class SourceConfigParseTest {

    /**
     * `sessionSourceFrom` 的 Fake 分支会读运行期配对桥（已配对 = 按桥里的 endpoint 建真源）。
     * 桥是进程单例：别的用例留下 `Paired`，这里「默认启动 = 夹具」的断言就会凭空变红，
     * 所以每个用例前清回 `NotBooted`。
     */
    @BeforeTest
    fun resetPairingBridge() {
        PairingRuntime.clearForTests()
    }

    // ------------------------------------------------------------ mode / defaults

    @Test
    fun defaultRawStringsKeepTheFixturePath() {
        // The regression guard: with nothing set anywhere, the answer is Fake —
        // App()'s `source = FakeSessionSource()` default must stay the norm.
        assertEquals(SourceSpec.Fake, SourceConfig.parse(null, null, null))
        assertEquals(SourceSpec.Fake, SourceConfig.parse("", "", ""))
    }

    @Test
    fun anythingButTheExactRealWordKeepsTheFixturePath() {
        listOf("fake", "off", "true", " realx", "REALX", "re al").forEach { raw ->
            assertEquals(SourceSpec.Fake, SourceConfig.parse(raw, null, null), "source=$raw must stay fake")
        }
        // Leniency is deliberate: surrounding whitespace + case are trimmed away,
        // so ` REAL ` switches just like `real` (documented in parse()).
        assertEquals(SourceSpec.Real("127.0.0.1", 8443), SourceConfig.parse(" REAL ", null, null))
    }

    @Test
    fun realWithoutHostOrPortUsesTheCoreSchemaDefaults() {
        // core schema/config.schema.json: core.listen default = 127.0.0.1:8443 —
        // the client's defaults must mirror it exactly.
        assertEquals(SourceSpec.Real("127.0.0.1", 8443), SourceConfig.parse("real", null, null))
        assertEquals(SourceSpec.Real("127.0.0.1", 8443), SourceConfig.parse("real", "", ""))
    }

    @Test
    fun explicitHostAndPortOverrideTheDefaults() {
        assertEquals(
            SourceSpec.Real("10.0.0.5", 18744),
            SourceConfig.parse("real", "10.0.0.5", "18744"),
        )
    }

    @Test
    fun blankHostTrimsToTheDefaultButBlankPortIsAlsoTheDefault() {
        // Whitespace-only values count as "not provided" — not as garbage.
        assertEquals(SourceSpec.Real("127.0.0.1", 8443), SourceConfig.parse("real", "  ", " "))
        assertEquals(SourceSpec.Real("127.0.0.1", 8443), SourceConfig.parse(" real ", null, null))
    }

    // ------------------------------------------------------------ invalid port

    @Test
    fun illegalPortIsRejectedLoudlyNeverSilently() {
        // "8443x" must NOT quietly become 8443 — a typo'd port pointing at a
        // different service would look like a core failure, not a config one.
        for (bad in listOf("8443x", "84.43", "port", "0x2107")) {
            val err = assertFailsWith<IllegalArgumentException> {
                SourceConfig.parse("real", "127.0.0.1", bad)
            }
            assertTrue(
                "refusing to fall back" in err.message!!,
                "the rejection must name the no-silent-fallback rule: ${err.message}",
            )
        }
    }

    @Test
    fun outOfRangePortsAreRejectedLoudly() {
        for (bad in listOf("0", "-1", "65536", "70000", "99999999")) {
            assertFailsWith<IllegalArgumentException> {
                SourceConfig.parse("real", "127.0.0.1", bad)
            }
        }
    }

    @Test
    fun pairCodeOnlyTravelsInRealMode() {
        // Fake mode ignores the pair code entirely (fixtures know no core).
        assertEquals(SourceSpec.Fake, SourceConfig.parse(null, null, null, "AB12-CD34"))
        val real = SourceConfig.parse("real", null, null, " AB12-CD34 ")
        assertEquals(SourceSpec.Real("127.0.0.1", 8443, "AB12-CD34"), real)
        // A blank pair code is "absent", not an empty credential.
        assertEquals(SourceSpec.Real("127.0.0.1", 8443, null), SourceConfig.parse("real", null, null, "  "))
    }

    // ------------------------------------------------------------ wasm query grammar

    @Test
    fun queryGrammarParsesBareKeysEmptyQueryAndNoQuery() {
        assertEquals(
            RawSourceStrings(null, null, null, null),
            rawStringsFromQuery(""),
            "no query at all must look exactly like 'nothing set'",
        )
        assertEquals(RawSourceStrings(null, null, null, null), rawStringsFromQuery("?"))
        // A bare key (`?source` with no `=`) is the EMPTY string — empty is never real.
        assertEquals(RawSourceStrings("", null, null, null), rawStringsFromQuery("?source"))
        assertEquals(
            RawSourceStrings("real", "10.0.0.5", "9911", null),
            rawStringsFromQuery("?source=real&host=10.0.0.5&port=9911"),
        )
        assertEquals(
            RawSourceStrings("real", "127.0.0.1", "18744", "AB12-CD34"),
            rawStringsFromQuery("?source=real&host=127.0.0.1&port=18744&pair_code=AB12-CD34"),
        )
    }

    @Test
    fun queryKeysOtherThanTheSourceKeysNeverSwitchTheMode() {
        // The probe params already carried by the wasm entry (`motion-probe` etc.)
        // must not leak into the switch.
        assertEquals(SourceSpec.Fake, SourceConfig.parse(rawStringsFromQuery("?motion-probe&freeze=10")))
    }

    // ------------------------------------------------------------ three channels agree

    @Test
    fun threeRawChannelsAgreeOnTheSameSpec() {
        // Same logical input through all three channels → same SourceSpec. The
        // actuals differ ONLY in where the strings come from; if one target ever
        // grows its own grammar, this parameterised equality goes red.
        data class Case(val label: String, val env: RawSourceStrings, val query: String)

        listOf(
            Case("off", RawSourceStrings(null, null, null), ""),
            Case("real+defaults", RawSourceStrings("real", null, null), "?source=real"),
            Case(
                "real+endpoint",
                RawSourceStrings("real", "10.0.0.5", "9911"),
                "?source=real&host=10.0.0.5&port=9911",
            ),
        ).forEach { case ->
            val viaEnv = SourceConfig.parse(case.env)
            val viaQuery = SourceConfig.parse(rawStringsFromQuery(case.query))
            assertEquals(viaEnv, viaQuery, "${case.label}: env vs query disagree")
        }
    }

    @Test
    fun androidPrecedenceExtrasBeatEnvPerKey() {
        // Extras carry source+host; only env carries the port — the merged answer
        // takes each key from the channel that HAS it (extras win ties).
        val merged = mergeRawStrings(
            primary = RawSourceStrings("real", "192.168.1.20", null, null),
            fallback = RawSourceStrings(null, "10.0.0.1", "18744", null),
        )
        assertEquals(SourceSpec.Real("192.168.1.20", 18744), SourceConfig.parse(merged))
        // No extras at all → pure env.
        assertEquals(
            SourceConfig.parse(RawSourceStrings(null, null, "18744")),
            SourceConfig.parse(mergeRawStrings(null, RawSourceStrings(null, null, "18744"))),
        )
    }

    // ------------------------------------------------------------ construction probes

    @Test
    fun realModeBuildsTheProtocolBackedSourceNotTheFixtures() {
        assertIs<FakeSessionSource>(sessionSourceFrom(RawSourceStrings(null, null, null)))
        assertTrue(
            sessionSourceFrom(RawSourceStrings("real", "127.0.0.1", "1")) !is FakeSessionSource,
            "real mode must never return the fixture source",
        )
    }

    @Test
    fun realSourceAgainstADeadCoreSurfacesAClassifiedFailureNotFixtures() {
        // Port 1 on loopback is closed: the auto-connecting source must fail with a
        // classified VadeException (the loader's Failed banner), never return rows
        // and never hang past its budget (short budget here — the production one
        // is 15s and only shows up as the banner, not a crash).
        val real = RealSessionSource(VadeClientConfig(host = "127.0.0.1", port = 1, keepaliveIntervalMs = null))
        val source = AutoConnectingSessionSource(real, "127.0.0.1", 1, connectTimeoutMs = 800)
        val err = runCatching {
            runBlocking { source.sessions() }
        }.exceptionOrNull()
        assertTrue(err is VadeException, "a dead core must surface as VadeException, got $err")
        assertEquals(VadeErrorCodes.UNAVAILABLE, err.code)
        assertTrue("not reachable" in err.message!!, "the error names the failure: ${err.message}")
    }

    @Test
    fun autoConnectingWrapperDelegatesTheEventStream() {
        val config = VadeClientConfig(host = "127.0.0.1", port = 1, keepaliveIntervalMs = null)
        val real = RealSessionSource(config)
        val wrapper = AutoConnectingSessionSource(real, "127.0.0.1", 1)
        assertSame(real.observeEvents(), wrapper.observeEvents())
    }
}
