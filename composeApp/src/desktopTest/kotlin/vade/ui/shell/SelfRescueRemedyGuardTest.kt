package vade.ui.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import vade.ui.data.RawSourceStrings
import vade.ui.data.SourceConfig
import vade.ui.data.SourceKeys
import vade.ui.data.SourceSpec
import vade.ui.data.rawStringsFromQuery

/**
 * g-rescue-text · 守卫：自救卡文案里的键名必须与解析器**同源**，照抄必须真能连上。
 *
 * 缺陷（审计 G4，两次复现）：卡上教 `--es vade.source real --es vade.pair_code <码>`，
 * 而 Android actual 读的是裸键 `source` / `pair_code`（`SourceKeys` 表）。用户照抄 →
 * intent extras 里根本没有解析器要找的键 → **静默回落假数据**，还以为连上了。
 *
 * 根治分两层，这里都钉住：
 *  1. **同源**：文案（`selfRescueRemedy`）的键名/模式词在生成侧就直接取自
 *     [SourceKeys] / [SourceConfig.REAL]，不再手写；本测试反向验证「文案里出现的键集合
 *     == [SourceKeys.acceptedKeys]」——生成侧若哪天退回手写，键集合立刻对不上。
 *  2. **照抄可连**：把卡上整行命令按各端 actual 的读法逐键取出，交给
 *     [SourceConfig.parse]，必须得到 [SourceSpec.Real] 且 host/port/配对码与命令一致。
 *     「静默回落假数据」从此有测试拦着——键名一漂，这里先红。
 */
class SelfRescueRemedyGuardTest {

    /** 本次运行的端点（区别于默认 127.0.0.1:8443——防文案偷偷落回默认值）。 */
    private val host = "192.168.1.20"
    private val port = "18755"

    /** 一份「本次运行」的原始配置；不传配对码——文案必须用占位符，不回显真码。 */
    private val raw = RawSourceStrings(source = "real", host = host, port = port, pairCode = null)

    /** 模拟用户把占位符换成 core 给的真码（不回显约束在 ListStatusOfTest 钉）。 */
    private val testPairCode = "AB12-CD34"

    private fun remedy(): String = selfRescueRemedy(raw)

    /** 桌面命令行（`VADE_SOURCE=` 开头的整行）。 */
    private fun desktopLine(): String =
        remedy().lineSequence().firstOrNull { it.startsWith("VADE_") }
            ?: error("自救卡里没有 VADE_ 开头的整行命令——桌面通道的照抄入口丢了")

    /** Android 整行命令（`adb shell am start` 开头）。 */
    private fun androidCommand(): String =
        remedy().lineSequence().firstOrNull { it.startsWith("adb shell am start") }
            ?: error("自救卡里没有 `adb shell am start` 开头的整行命令——Android 通道的照抄入口丢了")

    /** Wasm 整行 query（`?source=` 开头）。 */
    private fun wasmQuery(): String =
        remedy().lineSequence().firstOrNull { it.startsWith("?${SourceKeys.SOURCE}=") }
            ?: error("自救卡里没有 `?source=` 开头的整行 query——Wasm 通道的照抄入口丢了")

    /** `--es key value` → key→value 表：adb 把它们逐个放进 intent extras。 */
    private fun esFlags(command: String): Map<String, String> =
        Regex("--es\\s+(\\S+)\\s+(\\S+)").findAll(command)
            .associate { it.groupValues[1] to it.groupValues[2] }

    // ----------------------------------------------- 1 · 键集合一致（防再次漂移）

    @Test
    fun everyAndroidKeyInTheCopyIsAKeyTheParserAccepts() {
        val unknown = esFlags(androidCommand()).keys - SourceKeys.acceptedKeys.toSet()
        assertTrue(
            unknown.isEmpty(),
            "文案里的 --es 键 $unknown 不被解析器接受（真相表=$SourceKeys.acceptedKeys）——照抄即静默回落假数据",
        )
    }

    @Test
    fun everyAcceptedKeyAppearsInTheAndroidCommand() {
        // 反向也要一致：解析器接受的每个键都必须在命令里——缺键 = 照抄丢参数。
        val missing = SourceKeys.acceptedKeys - esFlags(androidCommand()).keys
        assertTrue(missing.isEmpty(), "真相表的键 $missing 没出现在 Android 命令里——照抄会丢参数")
    }

    @Test
    fun wasmQueryKeysMatchTheAcceptedKeysExactly() {
        val params = wasmQuery().removePrefix("?").split('&')
            .map { it.substringBefore('=') }
            .toSet()
        assertEquals(
            SourceKeys.acceptedKeys.toSet(),
            params,
            "Wasm query 的键集合必须与真相表完全一致（rawStringsFromQuery 只认这张表）",
        )
    }

    @Test
    fun theLegacyPrefixedKeyNamesNeverComeBack() {
        // 漂移的原样：`--es vade.source real` 这类 `vade.` 前缀键，解析器根本不读。
        val text = remedy()
        for (key in SourceKeys.acceptedKeys) {
            assertFalse(text.contains("vade.$key"), "漂移的旧键名 `vade.$key` 不许再出现在自救卡里: $text")
        }
    }

    @Test
    fun theModeWordIsTheOneParseAccepts() {
        // 模式词也是单一真相（SourceConfig.REAL）：`--es source` 与 `?source=` 的值、
        // `VADE_SOURCE=` 的值都必须是 parse 唯一放行的那个词。
        val real = SourceConfig.REAL
        assertEquals(real, esFlags(androidCommand())[SourceKeys.SOURCE], "Android 命令的模式词")
        assertTrue(wasmQuery().contains("=${SourceConfig.REAL}&"), "Wasm query 的模式词")
        assertTrue(desktopLine().startsWith("VADE_SOURCE=$real "), "桌面命令的模式词")
    }

    // ------------------------------- 2 · 照抄可连（文案中提取的参数 → parse → 真源）

    @Test
    fun copyingTheAndroidCommandParsesToTheRealSourceOfThisRun() {
        // 用户操作：整行复制 → 把 <配对码> 换成真码 → 粘进终端。adb 把 `--es key value`
        // 逐个放进 extras；Android actual 用 SourceKeys 常量逐键 getString——文案键名一漂，
        // 这里查到 null，parse 落回 Fake，本测试变红（即审计里那个静默回落）。
        val extras = esFlags(androidCommand().replace("<配对码>", testPairCode))
        val spec = SourceConfig.parse(
            source = extras[SourceKeys.SOURCE],
            host = extras[SourceKeys.HOST],
            port = extras[SourceKeys.PORT],
            pairCode = extras[SourceKeys.PAIR_CODE],
        )
        val real = assertIs<SourceSpec.Real>(spec, "照抄卡上命令必须得到真源；得到 $spec = 键名又漂了")
        assertEquals(host, real.host, "host 必须与命令一致（不是默认 ${SourceConfig.DEFAULT_HOST}）")
        assertEquals(port.toInt(), real.port, "port 必须与命令一致（不是默认 ${SourceConfig.DEFAULT_PORT}）")
        assertEquals(testPairCode, real.pairCode, "配对码必须原样进入 Real")
        assertTrue(spec != SourceSpec.Fake, "绝不许是假源")
    }

    @Test
    fun copyingTheWasmQueryParsesToTheRealSourceOfThisRun() {
        val query = wasmQuery().replace("<配对码>", testPairCode)
        val real = assertIs<SourceSpec.Real>(
            SourceConfig.parse(rawStringsFromQuery(query)),
            "照抄 Wasm query 必须得到真源；得到假源 = 键名又漂了",
        )
        assertEquals(host, real.host, "host 必须与命令一致")
        assertEquals(port.toInt(), real.port, "port 必须与命令一致")
        assertEquals(testPairCode, real.pairCode, "配对码必须原样进入 Real")
    }

    @Test
    fun copyingTheDesktopCommandLineParsesToTheRealSourceOfThisRun() {
        // 桌面 actual 读 System.getenv 同名变量；这里验证文案给的**值**流过 parse 得真源。
        // 环境变量名与 actual 的绑定由 ListStatusOfTest 的字面断言钉（actual 不可改）。
        val env = Regex("(VADE_[A-Z_]+)=([^ ]+)").findAll(desktopLine().replace("<配对码>", testPairCode))
            .associate { it.groupValues[1] to it.groupValues[2] }
        val real = assertIs<SourceSpec.Real>(
            SourceConfig.parse(
                source = env["VADE_SOURCE"],
                host = env["VADE_HOST"],
                port = env["VADE_PORT"],
                pairCode = env["VADE_PAIR_CODE"],
            ),
            "照抄桌面命令必须得到真源；得到假源 = 文案漂了",
        )
        assertEquals(host, real.host, "host 必须与命令一致")
        assertEquals(port.toInt(), real.port, "port 必须与命令一致")
    }

    // --------------------------------------------- 3 · 命令完整性（可整行照抄）

    @Test
    fun theAndroidCommandIsCompleteEnoughToRunVerbatim() {
        val command = androidCommand()
        assertTrue(command.startsWith("adb shell am start -n "), "必须整行可跑（含 am start 与组件）: $command")
        assertTrue(command.contains("-n vade.ui/.MainActivity"), "要点名 MainActivity 的组件: $command")
    }

    @Test
    fun everyLineOfTheCopyStartsACopyableCommandOrExplainsWhereTheCodeComesFrom() {
        // 卡是整块文本节点（用户一次选中），但照抄必须能**逐行**进行：命令行不许与
        // 说明文字黏在同一行（黏行 = 用户复制出一条跑不通的命令）。
        for (line in remedy().lineSequence().filter { it.contains("--es") || it.startsWith("?") || it.startsWith("VADE_") }) {
            assertTrue(
                line.startsWith("adb shell") || line.startsWith("?") || line.startsWith("VADE_"),
                "命令必须独占一行、可整行复制，不许与说明黏行: `$line`",
            )
        }
    }
}
