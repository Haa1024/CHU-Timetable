package com.seu.timetable.domain

import kotlinx.serialization.Serializable

/**
 * 一门课程（课程级）。
 *
 * 「同一门课」的判据是**教学班号**（长安大学为 `lessonNo`，形如 `29ZY1608.01`）：
 * 实测「水质分析」有 3 个时间块、教室各不相同，但 lessonNo 一致，故 3 块 = 1 门课。
 *
 * 拆成两层的原因：详情页与编辑页属课级操作，改颜色 / 备注必须只改一处，
 * 否则同一门课会渲染出两种颜色。拆分后「同一门课必然同色」在结构上不可能出错。
 */
@Serializable
data class Course(
    val id: String,                    // 教学班号 lessonNo
    val name: String,                  // 课程名
    val teacher: String = "",          // 教师
    val courseCode: String = "",       // 课程代码（由 lessonNo 去掉序号部分得到）
    val classNo: String = "",          // 班级号（本地自建 / 导入课表可留空）
    /** 学分。来自课表页的课程列表表；缺该表时为空，由用户自行填写。 */
    val credit: Double? = null,
    /** 用户备注 */
    val note: String = "",
    /** 用户指定的配色槽位（0..15）；null = 自动分配。据此区分「用户固定」与「自动计算」。 */
    val colorOverride: Int? = null,
)

/**
 * 一次上课（时间块）。接口里一个教学活动块 = 一个 CourseSession。
 *
 * 权威字段为 [dayOfWeek] + [startPeriod] + [endPeriod] + [weeks]，
 * 教室原文只作展示，**不得**由它反推时段或周次。
 */
@Serializable
data class CourseSession(
    val id: String,                    // 课程 + 星期 + 节次，保证同一门课的两个时段不撞
    val courseId: String,              // → Course.id
    val dayOfWeek: Int,                // 1=周一 … 7=周日（7 是周日，不是 0）
    val startPeriod: Int,              // 起始节次，1-based
    val endPeriod: Int,                // 结束节次，闭区间
    val weeks: Set<Int>,               // 上课周次，1-based，来自周次位图
    val room: String = "",             // 教室原文，可能为空（如实验室）→ UI 显示 "—"
) {
    val periodSpan: Int get() = (endPeriod - startPeriod + 1).coerceAtLeast(1)

    fun isActiveIn(week: Int): Boolean = week in weeks

    fun periodLabel(): String =
        if (startPeriod == endPeriod) "第 $startPeriod 节" else "第 $startPeriod-$endPeriod 节"

    fun weekLabel(): String = compressWeeks(weeks)
}

/**
 * 未排课的课程（如形势与政策、社会实践、各类实习）。
 *
 * 这类课程出现在课程列表里，却没有任何教学活动块，因此没有星期与节次、画不进网格；
 * 但学分只在这张列表里有，丢掉就等于用户看不到学分。
 *
 * [weeksText] 是**文本**周次（如 `"7-14周"`），与网格用的 0/1 位图含义完全不同，
 * 二者不可混用。
 */
@Serializable
data class UnplacedCourse(
    val name: String,
    val teacher: String = "",
    val credit: Double = 0.0,          // 学分
    val hours: Int = 0,                // 学时（长安大学 EAMS 不提供，恒为 0）
    val weeksText: String = "",        // 文本周次，不是位图
    val courseCode: String = "",
)

/** 将 {1,2,3,5,6,7,10} 压缩为 "1-3,5-7,10"。课块与课程详情均需展示，未压缩则难以排布。 */
fun compressWeeks(weeks: Set<Int>): String {
    if (weeks.isEmpty()) return "—"
    val sorted = weeks.sorted()
    val sb = StringBuilder()
    var start = sorted.first()
    var prev = start

    for (i in 1..sorted.size) {
        val cur = sorted.getOrNull(i)
        if (cur != null && cur == prev + 1) {
            prev = cur
            continue
        }
        if (sb.isNotEmpty()) sb.append(',')
        if (start == prev) sb.append(start) else sb.append(start).append('-').append(prev)
        if (cur != null) {
            start = cur
            prev = cur
        }
    }
    return sb.toString()
}

/**
 * [compressWeeks] 的逆运算：将用户输入的 `"1-3,5-7,10"` 解析为周次集合。
 *
 * 自建课表须由用户手填周次（没有教务位图可读），而 [compressWeeks] 恰好生成该格式，
 * 故预填内容可原样重新解析，用户改其中一段也不会令整串失效。
 *
 * 容错（均静默跳过，不抛异常——此处接收的是键盘输入）：中英文逗号均识别；区间连接符识别
 * `-` `~` `—`；空白、非数字、倒序区间（`8-3`）忽略该段。
 *
 * 返回空集合表示未解析出任何一段，调用方须视为输入不合法并报错，
 * 不可当作「该课程无上课周次」，否则用户笔误会使课表静默缺失一节课。
 */
fun parseWeeks(text: String): Set<Int> {
    val out = LinkedHashSet<Int>()
    text.split(',', '，').forEach { part ->
        val seg = part.trim().replace('~', '-').replace('—', '-')
        if (seg.isEmpty()) return@forEach
        val dash = seg.indexOf('-')
        if (dash > 0) {
            val a = seg.substring(0, dash).trim().toIntOrNull()
            val b = seg.substring(dash + 1).trim().toIntOrNull()
            if (a != null && b != null && a in 1..60 && b in a..60) {
                for (w in a..b) out += w
            }
        } else {
            seg.toIntOrNull()?.let { if (it in 1..60) out += it }
        }
    }
    return out
}
