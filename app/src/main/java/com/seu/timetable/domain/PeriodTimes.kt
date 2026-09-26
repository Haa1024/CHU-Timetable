package com.seu.timetable.domain

import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.LocalTime

/**
 * 一节课的起止时刻。
 *
 * 该表必须由人工确认，接口不提供——长安大学的 EAMS 前端与接口**都没有**「第 N 节几点几分」
 * 这类数据（全站搜索作息/上课时间/节次时间，0 命中）。因此今日页的倒计时、
 * 课内进度条与上课提醒，全部依赖这张本地配置表。
 *
 * 作息时间表现已随课表一同存储在本地（自建课表允许用户自行填写），
 * 故本层亦需可序列化。`LocalTime` 需要自定义序列化器（见 Serializers.kt）。
 */
@Serializable
data class PeriodTime(
    val index: Int,
    @Serializable(with = LocalTimeSerializer::class) val begin: LocalTime,
    @Serializable(with = LocalTimeSerializer::class) val end: LocalTime,
) {

    init {
        require(end > begin) {
            "第 $index 节的结束时间不能早于开始时间：$begin – $end"
        }
    }

    /** "08:00 – 08:45" */
    fun label(): String = "$begin – $end"

    /** "08:00" */
    fun beginLabel(): String = begin.toString()

    fun durationMinutes(): Long = Duration.between(begin, end).toMinutes()
}

/**
 * 作息时间表的各套预设。
 *
 * ## 为什么是「多套」而不是一套
 *
 * 长安大学的作息**逐校区不同**，而且差别不小——第一节课南校区 8:00、渭水校区 8:35，
 * 上午整体差 35 分钟。这不是可以忽略的误差：填错校区，今日页的「距上课还有 N 分钟」
 * 与上课提醒会整体偏半小时以上，而课表网格本身看起来完全正常（它只关心节次），
 * 于是用户很难意识到是设置错了。
 *
 * [Campus.DEFAULT] 目前是南校区。界面给用户选校区的入口见 [of]——
 * 之所以先把两套都编码进来，是因为一旦按错的那套显示过一次，用户没法自己判断对错。
 *
 * ## 共同的结构规律（可用于自查是否抄错）
 *
 * 上午 4 节 / 下午 4 节 / 晚上 3 节 = **11 节**，与 EAMS 网格的 11 行吻合
 * （`unitCount = 11`——实测第 11 节真的有课，它不是一行永远空着的填充行）。
 *
 * 节长：除南校区第 11 节为 50 分钟（20:45–21:35）外，其余各节一律 **45 分钟**。
 * 「每节都该是 45 分钟」是最省事的抄录自查点。
 *
 * 课间：**两校区都不是"一律 5 分钟"**。每个半天内分两个"大节"，
 * 大节内部各小节之间隔 5～10 分钟，两个大节之间隔 15～30 分钟。逐校区看：
 *
 *   - 南校区：小节间隔 10 分钟；第 2 节后 30 分钟（课间活动）、第 6 节后 20 分钟；
 *     例外是第 10 → 11 节之间只有 5 分钟。
 *   - 渭水：小节间隔 5 分钟；第 2 节后与第 6 节后各 15 分钟。
 *
 * 另有跨午休（第 4 → 5 节）与跨晚休（第 8 → 9 节）两个长间隔。单测把上述节律逐段钉住——
 * 抄错时单看每一行都正常，只有把相邻行连起来才看得出来。
 */
object PeriodTimes {

    /** 长安大学的校区。作息逐校区不同，故它必须是一个显式选择而不是"猜"。 */
    enum class Campus(val label: String) {
        /** 校本部 / 南校区。官网「作息时间」页现行版本 */
        NAN("南校区（本部）"),

        /** 渭水校区 */
        WEISHUI("渭水校区"),
        ;

        companion object {
            /** 未配置时用哪个。选它的理由：官网现行版本，且来源最新的页面就是南校区那个 */
            val DEFAULT = NAN

            /** 从持久化的名字还原；认不出就退回 [DEFAULT]，不要让一个脏值把界面弄崩。 */
            fun byName(name: String?): Campus = entries.firstOrNull { it.name == name } ?: DEFAULT
        }
    }

    /**
     * 南校区（本部）作息，11 节。
     *
     * 前 10 节取自学校官网「作息时间」页（南校区管理办公室 `nxqb.chd.edu.cn/xqfw/zxsj.htm`）；
     * 第 11 节官网未列，由用户补全（20:45–21:35）。
     *
     * 课间：第 2 节后 30 分钟（课间活动）、第 6 节后 20 分钟（课间休息）；
     * 午休 11:50–14:00，晚休 17:40–19:00。
     */
    val CHU_NAN: List<PeriodTime> = listOf(
        PeriodTime(1, LocalTime.of(8, 0), LocalTime.of(8, 45)),
        PeriodTime(2, LocalTime.of(8, 55), LocalTime.of(9, 40)),
        PeriodTime(3, LocalTime.of(10, 10), LocalTime.of(10, 55)),
        PeriodTime(4, LocalTime.of(11, 5), LocalTime.of(11, 50)),
        PeriodTime(5, LocalTime.of(14, 0), LocalTime.of(14, 45)),
        PeriodTime(6, LocalTime.of(14, 55), LocalTime.of(15, 40)),
        PeriodTime(7, LocalTime.of(16, 0), LocalTime.of(16, 45)),
        PeriodTime(8, LocalTime.of(16, 55), LocalTime.of(17, 40)),
        PeriodTime(9, LocalTime.of(19, 0), LocalTime.of(19, 45)),
        PeriodTime(10, LocalTime.of(19, 55), LocalTime.of(20, 40)),
        PeriodTime(11, LocalTime.of(20, 45), LocalTime.of(21, 35)),
    )

    /**
     * 渭水校区作息，11 节。
     *
     * 前 10 节取自校区作息表文档；第 11 节由用户补全（20:40–21:25）。
     * 全天每节都是 45 分钟、节间 5 分钟，规律性比南校区还好，可作为抄录是否出错的对照。
     */
    val CHU_WEISHUI: List<PeriodTime> = listOf(
        PeriodTime(1, LocalTime.of(8, 35), LocalTime.of(9, 20)),
        PeriodTime(2, LocalTime.of(9, 25), LocalTime.of(10, 10)),
        PeriodTime(3, LocalTime.of(10, 25), LocalTime.of(11, 10)),
        PeriodTime(4, LocalTime.of(11, 15), LocalTime.of(12, 0)),
        PeriodTime(5, LocalTime.of(14, 25), LocalTime.of(15, 10)),
        PeriodTime(6, LocalTime.of(15, 15), LocalTime.of(16, 0)),
        PeriodTime(7, LocalTime.of(16, 15), LocalTime.of(17, 0)),
        PeriodTime(8, LocalTime.of(17, 5), LocalTime.of(17, 50)),
        PeriodTime(9, LocalTime.of(19, 0), LocalTime.of(19, 45)),
        PeriodTime(10, LocalTime.of(19, 50), LocalTime.of(20, 35)),
        PeriodTime(11, LocalTime.of(20, 40), LocalTime.of(21, 25)),
    )

    /** 某个校区的作息。界面上的「选校区」即调它。 */
    fun of(campus: Campus): List<PeriodTime> = when (campus) {
        Campus.NAN -> CHU_NAN
        Campus.WEISHUI -> CHU_WEISHUI
    }

    /** 学校默认作息。空 schedule 的课表都跟随它（见 `BoardMeta.periodSchedule`）。 */
    val default: List<PeriodTime> get() = of(Campus.DEFAULT)
}

/** 课程当前状态。今日页的文案与配色都按它分档。 */
enum class SessionStatus { UPCOMING, ONGOING, FINISHED }

/**
 * 作息表的查询封装。用户可以改（设置页），所以做成类而不是直接读 object。
 */
class PeriodSchedule(val times: List<PeriodTime> = PeriodTimes.default) {

    private val byPeriod: Map<Int, PeriodTime> = times.associateBy { it.index }

    val maxPeriod: Int get() = byPeriod.keys.maxOrNull() ?: 0

    fun timeOf(period: Int): PeriodTime? = byPeriod[period]

    fun beginOf(period: Int): LocalTime? = byPeriod[period]?.begin

    fun endOf(period: Int): LocalTime? = byPeriod[period]?.end

    /** "08:00 – 08:45"；缺该节配置时返回 null */
    fun labelOf(period: Int): String? = byPeriod[period]?.label()

    /** 一个时间块的起止："10:00 – 11:40"。缺配置返回 null。 */
    fun timeRangeLabel(fromPeriod: Int, toPeriod: Int): String? {
        val begin = beginOf(fromPeriod) ?: return null
        val end = endOf(toPeriod) ?: return null
        return "$begin – $end"
    }

    // ---------- 今日页需要的三个判断 ----------

    fun statusOf(session: CourseSession, now: LocalTime): SessionStatus {
        val begin = beginOf(session.startPeriod)
        val end = endOf(session.endPeriod)
        return when {
            begin == null || end == null -> SessionStatus.UPCOMING
            now < begin -> SessionStatus.UPCOMING
            now > end -> SessionStatus.FINISHED
            else -> SessionStatus.ONGOING
        }
    }

    /** 距开始还有几分钟；已开始或拿不到时间返回 null */
    fun minutesUntilStart(session: CourseSession, now: LocalTime): Long? {
        val begin = beginOf(session.startPeriod) ?: return null
        if (now >= begin) return null
        return Duration.between(now, begin).toMinutes()
    }

    /** 进行中的进度 0f..1f；未开始或已结束返回 null */
    fun progressOf(session: CourseSession, now: LocalTime): Float? {
        val begin = beginOf(session.startPeriod) ?: return null
        val end = endOf(session.endPeriod) ?: return null
        if (now <= begin || now >= end) return null
        val total = Duration.between(begin, end).toMinutes().toFloat()
        if (total <= 0f) return null
        val done = Duration.between(begin, now).toMinutes().toFloat()
        return (done / total).coerceIn(0f, 1f)
    }

    /** 今天里 begin > now 的最早一节课（"下一节课"） */
    fun nextSessionOf(
        sessions: List<CourseSession>,
        now: LocalTime,
    ): CourseSession? = sessions
        .filter { (beginOf(it.startPeriod) ?: return@filter false) > now }
        .minByOrNull { beginOf(it.startPeriod)!! }

    /**
     * 自查作息表是否录入错误。人工抄录 11 行时间极易出现"结束早于开始"之类的笔误。
     * 单元测试会执行该函数，将笔误拦截在发布之前。
     *
     * ## 这里**刻意不校验**「每节 45 分钟」「第 N 节后休息 M 分钟」
     *
     * 这两条都是**逐校、甚至逐校区而定**的：长安大学的南校区与渭水校区作息就不同，
     * 节间间隔也不同（南校区第 2 节后 30 分钟、第 6 节后 20 分钟；渭水则一律 5 分钟），
     * 南校区第 11 节还是 50 分钟。写死任何一套规则，用户一改作息就会被误报"有问题"。
     *
     * 所以只保留**与学校无关的结构性检查**：节次连续、时间不倒流、不重叠，
     * 以及一个宽松的时长上限（用来抓"某一行的结束时刻填到了另一行"这种量级的笔误）。
     */
    fun problems(): List<String> {
        val out = ArrayList<String>()
        val sorted = times.sortedBy { it.index }

        sorted.forEachIndexed { i, t ->
            if (t.index != i + 1) out += "节次不连续：第 ${i + 1} 个位置的 index 是 ${t.index}"
            if (t.end <= t.begin) out += "第 ${t.index} 节结束不晚于开始：${t.begin} – ${t.end}"
            if (t.durationMinutes() > MAX_PERIOD_MINUTES) {
                out += "第 ${t.index} 节时长 ${t.durationMinutes()} 分钟，" +
                    "超过 $MAX_PERIOD_MINUTES 分钟 —— 多半是把结束时刻填错了"
            }
        }
        sorted.zipWithNext { a, b ->
            if (b.begin < a.end) out += "第 ${a.index} 节与第 ${b.index} 节时间重叠"
        }
        return out
    }

    companion object {
        /**
         * 单节时长的合理上限。取 180 分钟而非"等于 45"：不同校区、不同学校的节长本就不同，
         * 这条只负责拦住明显不合理的值。
         */
        private const val MAX_PERIOD_MINUTES = 180L

        /**
         * 安全构造入口，供用户输入/设置使用。
         * 不合法时返回 null，而非像 [PeriodTime] 的 init 那样抛出异常——
         * 硬编码常量应 fail fast，但用户输入不应导致 App 崩溃。
         */
        fun ofOrNull(index: Int, begin: String, end: String): PeriodTime? = try {
            PeriodTime(index, LocalTime.parse(begin.trim()), LocalTime.parse(end.trim()))
        } catch (_: Exception) {
            null
        }

        /**
         * 作息时间编辑页的整表校验入口。
         *
         * 返回 `Result` 而非 `null` 的原因：用户修改的是 11 行 × 2 个输入框，
         * 一旦某处有误，必须能说明"是哪一行、错在何处"，否则用户只能逐行猜测。
         * 此处将首个出错的行转换为可读信息并置入异常消息。
         */
        fun fromInputs(inputs: List<Triple<Int, String, String>>): Result<PeriodSchedule> {
            val times = ArrayList<PeriodTime>(inputs.size)
            inputs.forEach { (index, beginText, endText) ->
                val begin = runCatching { LocalTime.parse(beginText.trim()) }.getOrNull()
                    ?: return Result.failure(
                        IllegalArgumentException("第 $index 节的开始时间「${beginText.trim()}」不是合法时刻，要写成 08:00 这样")
                    )
                val end = runCatching { LocalTime.parse(endText.trim()) }.getOrNull()
                    ?: return Result.failure(
                        IllegalArgumentException("第 $index 节的结束时间「${endText.trim()}」不是合法时刻，要写成 08:45 这样")
                    )
                if (end <= begin) {
                    return Result.failure(
                        IllegalArgumentException("第 $index 节的结束时间（$end）要晚于开始时间（$begin）")
                    )
                }
                times += PeriodTime(index, begin, end)
            }
            if (times.isEmpty()) return Result.failure(IllegalArgumentException("作息时间不能为空"))
            return Result.success(PeriodSchedule(times))
        }
    }
}
