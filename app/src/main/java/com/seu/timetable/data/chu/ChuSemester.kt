package com.seu.timetable.data.chu

import com.seu.timetable.domain.TermContext
import java.time.LocalDate

/**
 * 一个学期。
 *
 * ★ [id] 是 EAMS 的**内部自增数字**（实测 72…262，且中间跳过了 2016-2019 学年），
 * **不能推算、不能跨学年复用**，必须从服务端列表现拉。
 * [schoolYear] + [termName] 才是跨系统可读的标识，因此 [termCode] 采用 `2026-2027-1` 这种形态。
 */
data class ChuSemester(
    val id: String,
    val schoolYear: String,
    val termName: String,
    /** 服务端原始文案，如 `2026-2027学年1学期` */
    val rawLabel: String = "",
) {
    val termCode: String get() = "$schoolYear-$termName"
    val displayName: String get() = rawLabel.ifBlank { "${schoolYear}学年${termName}学期" }
}

/** `semesterCalendar` 的解析结果：按学年分组（供学期选择器直接铺两级列表）+ 当前学期 id */
data class ChuSemesterCalendar(
    val groups: List<List<ChuSemester>>,
    val currentId: String?,
) {
    val all: List<ChuSemester> get() = groups.flatten()

    fun byId(id: String?): ChuSemester? = all.firstOrNull { it.id == id }

    val current: ChuSemester? get() = byId(currentId)

    companion object {
        val EMPTY = ChuSemesterCalendar(emptyList(), null)
    }
}

/**
 * 两个学期接口的文本解析。
 *
 * 优先用 [parseCalendar]：它返回结构化数据并直接给出**当前学期**，
 * 而 [parseOptionList] 只给 `<option>`（无当前学期概念），作为兜底。
 */
object ChuSemesterParser {

    /** `POST /eams/dataQuery.action  dataType=semesterCalendar&value=<id>&empty=false` */
    fun parseCalendar(body: String): ChuSemesterCalendar {
        val groups = YEAR_GROUP.findAll(body).map { g ->
            SEMESTER_ENTRY.findAll(g.groupValues[2]).map { e ->
                ChuSemester(
                    id = e.groupValues[1],
                    schoolYear = e.groupValues[2],
                    termName = e.groupValues[3],
                )
            }.toList()
        }.filter { it.isNotEmpty() }.toList()

        val current = CURRENT_ID.find(body)?.groupValues?.get(1)
        return ChuSemesterCalendar(groups, current)
    }

    /** `POST /eams/dataQuery.action  dataType=semester&projectId=1` */
    fun parseOptionList(body: String): List<ChuSemester> =
        OPTION.findAll(body).map {
            ChuSemester(
                id = it.groupValues[1],
                // 分组顺序即正则里左括号的顺序：1=id, 2=整串文案, 3=学年, 4=学期
                schoolYear = it.groupValues[3],
                termName = it.groupValues[4],
                rawLabel = it.groupValues[2],
            )
        }.toList()

    private val YEAR_GROUP = Regex("""y(\d+)\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)

    /**
     * ★ 结尾那个 `}` **必须写成 `\}`**，不能图省事写裸的。
     *
     * 孤立的花括号在 Java 正则里被当作字面量（所以 JVM 单测全绿），
     * 但 **Android 的 ICU 正则判它是语法错误**：`PatternSyntaxException near index 83`。
     * 而 `Regex(...)` 是在 `object` 的 `<clinit>` 里编译的，于是错误形态极其难认——
     * **单测全绿、真机一点就崩，崩溃栈里看不到任何业务代码**（实测赔掉一整轮）。
     *
     * 对照：同一批里孤立的 `]`（如上面 [YEAR_GROUP] 的 `\[(.*?)]`）**没有**报错，
     * 可见 ICU 只管 `}`。字符类里的 `[^}]` 也是合法的（见 [ChuCourseTableParser]）。
     *
     * 这条由 `RegexIcuStrictnessTest` 兜底——JVM 测不出来，只能自己扫。
     */
    private val SEMESTER_ENTRY =
        Regex("""\{\s*id\s*:\s*(\d+)\s*,\s*schoolYear\s*:\s*"([^"]*)"\s*,\s*name\s*:\s*"([^"]*)"\s*\}""")

    private val CURRENT_ID = Regex("""semesterId\s*:\s*"(\d+)"""")

    /**
     * 本对象全部正则的原始串，**只给单测做语法自检**（见 `RegexIcuStrictnessTest`）。
     *
     * 存在的理由：JVM 与 Android ICU 对正则的严格度不同，前者容忍的写法在真机上会
     * 直接抛异常，而这类异常单测一条都拦不住。把正则串暴露出来是唯一可测的口子。
     */
    internal val selfCheckPatterns: List<String>
        get() = listOf(YEAR_GROUP.pattern, SEMESTER_ENTRY.pattern, CURRENT_ID.pattern, OPTION.pattern)

    /**
     * `<option value="72">2015-2016学年1学期</option>`
     *
     * 最外层那对括号是**整体文案**（`rawLabel`）。曾经漏了它，
     * 于是 `groupValues[4]` 越界抛 IndexOutOfBounds —— 是「加个字段没同步正则」的典型。
     */
    private val OPTION = Regex(
        """<option[^>]*value="(\d+)"[^>]*>((\d{4}-\d{4})学年(\d)学期)\s*</option>"""
    )
}

/**
 * CHU 侧无法从接口拿到的常量，全部集中在这里，便于日后一处校准。
 */
object ChuDefaults {

    /** 每天节数：来自 `fillTable(table0,7,11,0)` 与 `var unitCount = 11`（实测恒为 11） */
    const val UNITS_PER_DAY = 11

    /**
     * 上午 / 下午 / 晚上 的分界。
     *
     * TODO 待校准：EAMS 前端**不提供**课时分组（全站 grep「作息/上课时间/节次时间」为 0 命中），
     *   4 / 4 / 3 是按 11 节制给出的推测，仅影响 UI 上上午下午之间的视觉分隔，
     *   不影响任何时间与周次数据。确认后改这三行即可。
     */
    const val MORNING_PERIODS = 4
    const val AFTERNOON_PERIODS = 4
    const val EVENING_PERIODS = 3

    /** 从骨架页拿不到 `startWeek` 选项时的兜底总周数 */
    const val FALLBACK_TOTAL_WEEKS = 26

    fun termContextOf(
        semester: ChuSemester,
        firstMonday: LocalDate,
        totalWeeks: Int = FALLBACK_TOTAL_WEEKS,
        lastTeachingWeek: Int = totalWeeks,
    ): TermContext = TermContext(
        termCode = semester.termCode,
        termName = semester.displayName,
        firstMonday = firstMonday,
        totalWeeks = totalWeeks,
        lastTeachingWeek = lastTeachingWeek,
        morningPeriods = MORNING_PERIODS,
        afternoonPeriods = AFTERNOON_PERIODS,
        eveningPeriods = EVENING_PERIODS,
    )
}

/**
 * 「某学期第一周周一是哪天」的来源。
 *
 * ★ 为什么需要它：EAMS **没有任何接口**给出学期起止日期。已验证 `dataQuery.action`
 * 的各 `dataType`（含 `semesterCalendar`）都不含日期字段（见技能 §9.2）。
 * 所以这个值只能由外部提供——用户手填一次，或从学校官网校历抓取。
 * **不能从学期 id 或学年推算，也不该猜一个像样的日期。**
 *
 * 做成函数式接口而不是直接给字段：学期的开学日逐个不同，且只有拿到 [ChuSemester]
 * 之后才知道该问哪一学期；同时上层（设置页 / 本地存储）实现它即可，数据层不持有设置。
 */
fun interface ChuSemesterStart {

    /** 返回该学期第一周周一；null = 尚未配置（上层可据此提示用户补充） */
    fun firstMondayOf(semester: ChuSemester): LocalDate?

    companion object {
        /** 默认实现：永远「未配置」。数据层据此走兜底，不阻塞课表加载 */
        val UNKNOWN = ChuSemesterStart { null }

        /** 固定值，测试与单学期场景用 */
        fun fixed(date: LocalDate): ChuSemesterStart = ChuSemesterStart { date }
    }
}
