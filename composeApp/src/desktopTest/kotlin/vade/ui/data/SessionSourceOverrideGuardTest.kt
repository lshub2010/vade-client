package vade.ui.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 守卫：**任何** [SessionSource] 实现都必须声明 `sessions(includeArchived: Boolean)`。
 *
 * ## 为什么需要它
 *
 * `g-archive-ui` 给 `SessionSource.sessions` 加了 `includeArchived: Boolean = false`。
 * Kotlin 的默认值**只写在接口上**，所以每个实现（含测试替身）都得把这个形参写出来，
 * 否则报 `'sessions' overrides nothing`。而 git 的文本合并**看不见**这种语义冲突：
 * 两个分支改的是不同文件，合并干净通过，直到 `compileTestKotlinDesktop` 才红。
 *
 * 这个坑已经踩了两次（`LoadErrorBannerTest.RecordingSource`，然后
 * `ListStatusBannerTest.OneRowSource/FailingSource/GatedSource`）——都是「测试替身来自
 * 加参之前的旧分支」。编译器的报错一次只出现在它编到的那一处，而且必须先修掉前面的
 * 才能看到后面的；这条扫描把发现时机提前，并且**一次列出全部**。
 *
 * ## 它守不住什么
 *
 * 只认「无参 override」这一种写法。若有人写成 `sessions(includeArchived: Boolean = false)`
 * 这种在**实现**上重复默认值的形态（Kotlin 不允许在 override 上写默认值，所以实际上不会），
 * 或者把方法名换成别名再桥接，这条守卫看不见——那属于编译器该管的部分。
 */
class SessionSourceOverrideGuardTest {

    @Test
    fun everySessionSourceImplementationDeclaresIncludeArchived() {
        val root = File("src")
        assertTrue(root.isDirectory, "找不到 ${root.absolutePath}（测试的工作目录应是 composeApp/）")

        val noArg = Regex("""override\s+suspend\s+fun\s+sessions\s*\(\s*\)""")

        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readText(Charsets.UTF_8).lines().withIndex()
                    .filter { (_, line) -> noArg.containsMatchIn(line) }
                    // 跳过注释行：本文件的 KDoc 与说明里就有这个形状的字样，
                    // 不筛的话守卫会把自己写成 offender。
                    .filter { (_, line) -> !line.trimStart().startsWith("//") && !line.trimStart().startsWith("*") }
                    .map { (index, line) -> "${file.path}:${index + 1}  ${line.trim()}" }
            }
            .toList()

        assertTrue(
            offenders.isEmpty(),
            buildString {
                appendLine("这些 SessionSource 实现漏了 includeArchived 形参（合并后才会在编译期炸）：")
                offenders.forEach { appendLine("  $it") }
                append("改法：`override suspend fun sessions(includeArchived: Boolean): List<SessionSummary>`")
            },
        )
    }
}
