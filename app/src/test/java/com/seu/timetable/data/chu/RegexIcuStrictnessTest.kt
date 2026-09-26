package com.seu.timetable.data.chu

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 正则的 **ICU 严格性**自检。
 *
 * ## 为什么需要它
 *
 * JVM 与 Android 用的不是同一套正则引擎：前者是 `java.util.regex`，后者是 ICU
 * （`com.android.icu.util.regex`）。两者**严格度不一样**，而差别恰好落在花括号上——
 * 孤立的 `}` 在 JVM 里被当作字面量，在 ICU 里是**语法错误**：
 *
 * ```
 * java.util.regex.PatternSyntaxException: Syntax error in regexp pattern near index 83
 * \{\s*id\s*:\s*(\d+)…\s*}        ← 末尾这个裸 `}` 就是全部原因
 * ```
 *
 * 而 `Regex(...)` 是在 `object` 的 `<clinit>` 里编译的，于是症状是：
 * **单测全绿、装到真机上一点就崩，崩溃栈里看不到任何业务代码**
 * （只有 `ExceptionInInitializerError` + `PatternSyntaxException`）。实测赔掉一整轮排查。
 *
 * 本测试补上 JVM 测不出来的那一层：把所有正则串拿来做文本扫描。
 * **不要删它**——删掉等于重新失去这条唯一的线索。
 *
 * ⚠️ **新增正则时要同步登记**：本测试只能看见各 parser 主动暴露出来的
 * `internal val selfCheckPatterns`。新写一个 parser（或给已有的加正则）时，
 * 记得把正则一并加进那份清单，否则它就落在护栏之外了。
 * 可以直接跑 `grep -rn "Regex(" app/src/main --include=*.kt` 自查有没有漏登记的。
 *
 * ## 已核实的边界（别照着"顺手也查一下"去扩大）
 *
 * - 字符类内部的 `}` 合法（如 `[^}]`），ICU 亦然，故整体跳过字符类内容。
 * - 孤立的 `]` 实测**不**报错：同一批正则里 `\[(.*?)]` 在真机上是好的
 *   （它比出事的 `}` 先初始化却没报错）。
 */
class RegexIcuStrictnessTest {

    @Test
    fun `正则里没有未转义的花括号`() {
        val all = ChuSemesterParser.selfCheckPatterns +
            ChuCourseTableParser.selfCheckPatterns +
            ChuLoginParser.selfCheckPatterns

        assertTrue("一条正则都没扫到，说明 selfCheckPatterns 没接上", all.size >= 10)

        val offenders = all.mapNotNull { pattern ->
            val at = unescapedBraces(pattern).firstOrNull() ?: return@mapNotNull null
            val from = maxOf(0, at - 20)
            val to = minOf(pattern.length, at + 8)
            "  · 下标 $at 附近 …${pattern.substring(from, to)}…\n    整串：$pattern"
        }
        assertTrue(
            "有正则含未转义的 `}`：JVM 当字面量放过，Android ICU 判语法错误，" +
                "真机上崩在 object 的 <clinit>（表现为「点一下就闪退」，栈里没有业务代码）。" +
                "写成 `\\}` 即可。\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /**
     * 找出**字符类之外**、且不构成量词结尾的 `}`。
     *
     * @return 各 `}` 在串里的下标；空表示这份正则不吃 ICU 的亏
     */
    private fun unescapedBraces(pattern: String): List<Int> {
        val out = mutableListOf<Int>()
        var inClass = false
        var braceOpen = -1
        var i = 0
        while (i < pattern.length) {
            val ch = pattern[i]
            // 转义对整体跳过：`\{` `\}` 都是字面量，不该参与判断
            if (ch == '\\') {
                i += 2
                continue
            }
            if (inClass) {
                if (ch == ']') inClass = false
                i++
                continue
            }
            if (ch == '[') {
                inClass = true
                i++
                continue
            }
            if (ch == '{') {
                braceOpen = i
                i++
                continue
            }
            if (ch == '}') {
                val body = if (braceOpen >= 0) pattern.substring(braceOpen + 1, i) else null
                if (body == null || !QUANTIFIER.matches(body)) out += i
                braceOpen = -1
                i++
                continue
            }
            i++
        }
        return out
    }

    private companion object {
        /** 只有 `{2}` / `{2,}` / `{2,5}` 这三种构成量词，其余都是非法的裸 `}` */
        val QUANTIFIER = Regex("""\d+,?\d*""")
    }
}
