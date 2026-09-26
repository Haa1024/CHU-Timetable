package com.seu.timetable.data.chu

import com.seu.timetable.data.NotLoggedInException
import com.seu.timetable.data.TimetableException
import com.seu.timetable.data.TimetableSource
import com.seu.timetable.domain.Timetable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

/**
 * 长安大学 EAMS 数据源。
 *
 * 与课表来源抽象层是同一角色，但接口形态按教务系统而定。
 * 拉取顺序（技能 `chu-eams-timetable-api` 第 4 章）：
 * ```
 * 1) GET  courseTableForStd.action             -> 骨架页：ids / 总周数 / 骨架所属学期
 * 2) POST dataQuery.action (semesterCalendar)   -> 学期列表 + 当前学期
 * 3) POST courseTableForStd!courseTable.action  -> 课表片段（课表 JS + 课程列表表）
 * ```
 * ★ **只有 2 次拿全量课表**（骨架 + 片段）：总周数与 `ids` 同属骨架页，
 *   不必再额外问学期、节次参数与课表行。
 *
 * 带一层内存缓存：同一学期重复调用不再请求接口，切学期自动失效。
 * 缓存的是**组装完的 [Timetable]**——课表数据在学期内不变，重解析只是白烧 CPU。
 */
class ChuTimetableSource(
    private val client: ChuClient = ChuClient(),
    /**
     * 学期开学日来源。EAMS 无此数据（见 [ChuSemesterStart]），必须由上层注入；
     * 未配置时走 [provisionalFirstMonday]，只影响"今天第几周"，不影响课表本身。
     */
    private val semesterStart: ChuSemesterStart = ChuSemesterStart.UNKNOWN,
) : TimetableSource {

    private val lock = Mutex()
    private var cached: Timetable? = null

    /**
     * 学期列表缓存。
     *
     * ★ 它把「缓存命中的一次加载」从 1 次网络请求降到 **0 次**：
     * 原本是先问一遍学期列表、再回头看课表缓存，于是每次 [load] 都会白打一次
     * `dataQuery.action`（哪怕课表本身早已缓存好）。另外 [currentSemester] 的调用方
     * 常常连着问「当前学期代码」和「当前学期名」，没有这层缓存就是两次一模一样的 POST。
     *
     * 学期列表变化很慢（一学期多一条），故在生命周期内缓存；万一陈旧到查不到目标学期，
     * [chuResolveSemester] 会抛出带学期数的明确错误，用 [invalidate]（下拉刷新、
     * 退出登录时都会调）强制重取即可。
     */
    @Volatile
    private var cachedCalendar: ChuSemesterCalendar? = null

    override suspend fun load(termCode: String?): Timetable = lock.withLock {
        // 走 semester()，让学期列表也吃缓存——缓存命中时整个 load 是零网络请求
        val semester = chuResolveSemester(semesters(), termCode)

        cached?.let { if (it.term.termCode == semester.termCode) return it }

        // ids 与总周数来自同一次 GET
        val skeleton = client.skeleton()
        val ids = skeleton.ids
            ?: throw NotLoggedInException("拿不到课表 ids —— 会话可能已失效（页面被换成了登录页）")

        val html = client.fetchCourseTable(semester.id, ids)
        val activities = ChuCourseTableParser.parseActivities(html)
        val rows = ChuCourseTableParser.parseCourseRows(html)

        val term = ChuDefaults.termContextOf(
            semester = semester,
            firstMonday = semesterStart.firstMondayOf(semester) ?: provisionalFirstMonday(),
            totalWeeks = chuTotalWeeksOf(skeleton, semester, activities),
        )

        val week = term.weekOf(LocalDate.now()).coerceIn(1, term.totalWeeks)

        ChuMapper.buildTimetable(
            activities = activities,
            rows = rows,
            term = term,
            currentWeek = week,
        ).also { cached = it }
    }

    /** 强制下一次 [load] 重新请求接口（下拉刷新、退出登录用） */
    fun invalidate() {
        cached = null
        cachedCalendar = null
    }

    /** 供 UI 判断是否需要弹登录页。注意它把异常压成了 false，只适合「有没有」这种粗判断。 */
    suspend fun hasSession(): Boolean = client.hasSession()

    /**
     * 学期列表（设置页的学期选择器、导入页的候选学期用）。
     *
     * 空结果**不进缓存**：拿不到学期多半是会话失效或数据异常，缓存下来会让
     * 「重新登录之后仍然没有学期」这种极难排查的假故障出现（同 [ChuClient.skeleton] 的处理）。
     *
     * @param force 绕过缓存重取。给下拉刷新用。
     */
    suspend fun semesters(force: Boolean = false): ChuSemesterCalendar {
        cachedCalendar?.let { if (!force) return it }
        return client.semesterCalendar().also { if (it.all.isNotEmpty()) cachedCalendar = it }
    }

    /**
     * 当前学期，以及**拿不到时的原因**。
     *
     * 走与 [chuResolveSemester] **完全相同**的兜底规则（服务端标记缺失时取列表末位），
     * 而不是自己再写一遍——两处规则一旦不一致，就会出现「导入页说当前是 A 学期、
     * 导入进去的却是 B 学期」这种界面看不出来的静默错误。
     *
     * ★ 为什么必须有它：这条链曾经是 `runCatching { … }.getOrNull()`，于是
     * 「未登录」「接口报错」「学期列表为空」「页面结构变了」四种毫不相干的故障
     * 到界面上全长成同一句「无法获取当前学期」。而它们的处置完全不同
     * （重新登录 / 稍后再试 / 手动填学期代码），合并成一个 null 等于把线索扔掉。
     */
    suspend fun probeSemester(): SemesterProbe = try {
        val cal = semesters()
        SemesterProbe.Ok(chuResolveSemester(cal, null), cal.all)
    } catch (e: NotLoggedInException) {
        SemesterProbe.Failed(e.message ?: "会话已失效")
    } catch (e: Exception) {
        SemesterProbe.Failed(e.message ?: (e::class.simpleName ?: "未知错误"))
    }

    /**
     * 当前学期；只关心「有没有」、不关心「为什么没有」时用它。
     *
     * 拿不到就返回 null（未登录 / 网络异常），由调用方按「不知道」处理。
     * 需要在界面上说明原因时改用 [probeSemester]。
     */
    suspend fun currentSemester(): ChuSemester? =
        (probeSemester() as? SemesterProbe.Ok)?.current

    fun cachedTermCode(): String? = cached?.term?.termCode

    /** 诊断用一行摘要，接入真实数据后优先核对（数值异常即说明分组键或位图规则有误） */
    fun describe(): String {
        val t = cached ?: return "未加载"
        return "学期 ${t.term.termCode}｜课程 ${t.courses.size} 门｜时间块 ${t.sessions.size} 个｜" +
            "未排课 ${t.unplaced.size} 门｜当前第 ${t.currentWeek} 周｜切换器到第 ${t.displayedWeeks} 周"
    }
}

// ------------------------------------------------------------------ 纯逻辑（可离线单测）

/**
 * 学期查询的结果。
 *
 * 与 `SessionProbe` 同一套三段式：**成功给数据，失败给原因**，绝不用 null 同时表示
 * 「没登录」「连不上」「解析不到」——这三者对用户下一步动作的指向完全不同。
 */
sealed interface SemesterProbe {

    /** @param all 该学生有数据的**全部**学期，供学期选择器铺列表 */
    data class Ok(val current: ChuSemester, val all: List<ChuSemester>) : SemesterProbe

    /** 失败原因。文案已可直接展示给用户，不必再包一层。 */
    data class Failed(val reason: String) : SemesterProbe
}

/**
 * `termCode` -> [ChuSemester]。null 表示「当前学期」。
 *
 * 查不到就报错，而不是悄悄退回当前学期：用户明确选了 2024-2025-2 却拿到别的学期，
 * 界面上完全看不出来（课程名一样、周次一样），是最难发现的一类静默错误。
 */
internal fun chuResolveSemester(cal: ChuSemesterCalendar, termCode: String?): ChuSemester {
    if (cal.all.isEmpty()) {
        throw TimetableException("学期列表为空 —— 会话已失效或该账号没有任何学期数据")
    }
    if (termCode == null) {
        // semesterCalendar 会直接标出当前学期；标记缺失时按列表末位（=最新学年）兜底
        return cal.current ?: cal.all.last()
    }
    return cal.all.firstOrNull { it.termCode == termCode }
        ?: throw TimetableException("学期列表里没有 $termCode（共 ${cal.all.size} 个学期）")
}

/**
 * 该学期总周数。**两个来源，优先服务端**：
 *
 * 1. 骨架页的 `startWeek` 下拉——但它跟着**骨架自己那个学期**走。
 *    只有骨架学期 == 目标学期时才采信（[ChuSkeleton.semesterId]）。
 *    否则拿 262 的 26 周去渲染 242，周次切换器会凭空多出一截。
 * 2. 位图里出现过的最大周次。位图**按学期重置、原点一致**（技能 §5.2），
 *    所以这个值一定属于目标学期，作为客观兜底。
 *
 * 两处都拿不到（例如空课表）才用 [ChuDefaults.FALLBACK_TOTAL_WEEKS]。
 */
internal fun chuTotalWeeksOf(
    skeleton: ChuSkeleton,
    semester: ChuSemester,
    activities: List<ChuCourseTableParser.Activity>,
): Int {
    val semesterMatches = skeleton.semesterId == null || skeleton.semesterId == semester.id
    return skeleton.totalWeeks?.takeIf { semesterMatches }
        ?: activities
            .flatMap { ChuCourseTableParser.decodeWeekBitmap(it.weekBitmap) }
            .maxOrNull()
        ?: ChuDefaults.FALLBACK_TOTAL_WEEKS
}

/**
 * 未配置开学日时的兜底：把**本周当作第 1 周**。
 *
 * 这不是真实答案，只是 [com.seu.timetable.domain.TermContext] 的 `firstMonday`
 * 强制非空，需要一个可用的日期。影响面被刻意压到最小：
 * **课表网格、周次、节次、教室全部不受影响**，只有「今天第几周 / 今日课程」会偏。
 * 用户在设置里填一次开学日，这条兜底即被替换（见技能 §9.2）。
 *
 * @param today 注入而非取 `LocalDate.now()`，好让测试可复现。
 */
internal fun provisionalFirstMonday(today: LocalDate = LocalDate.now()): LocalDate =
    // DayOfWeek: MONDAY=1 .. SUNDAY=7，减 (value-1) 天即本周一
    today.minusDays((today.dayOfWeek.value - 1).toLong())
