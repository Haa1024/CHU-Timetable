package com.seu.timetable.data.chu

import com.seu.timetable.domain.Course
import com.seu.timetable.domain.CourseSession
import com.seu.timetable.domain.TermContext
import com.seu.timetable.domain.Timetable
import com.seu.timetable.domain.UnplacedCourse

/**
 * 把 [ChuCourseTableParser] 抠出来的原始数据组装成领域模型。
 *
 * 位图与节次的语义都按长安大学 EAMS 的口径自洽实现，不套用其它教务系统的映射函数
 * （位图口径见 [ChuCourseTableParser.decodeWeekBitmap]，两者差一位，照抄会整体错一周）。
 *
 * 分组键是 `lessonNo`（课程序号，如 `29ZY1608.01`）：实测「水质分析」有 3 个活动块
 * （教室分别是 `*WM3209` / `水化学实验室` / `水文地质学实验室`），但 lessonNo 相同，
 * 故 3 个块 = 1 门课 —— 「同一门课必然同色」由此在结构上成立。
 */
object ChuMapper {

    fun buildTimetable(
        activities: List<ChuCourseTableParser.Activity>,
        rows: List<ChuCourseTableParser.CourseRow> = emptyList(),
        term: TermContext,
        currentWeek: Int = 1,
    ): Timetable {
        val rowByLesson = rows.associateBy { it.lessonNo }

        val courses = LinkedHashMap<String, Course>()
        val sessions = LinkedHashMap<String, CourseSession>()

        for (a in activities) {
            val id = a.lessonNo.ifBlank { a.courseName }
            if (id.isEmpty()) continue

            val weeks = ChuCourseTableParser.decodeWeekBitmap(a.weekBitmap)
            // 位图全 0 的教学活动没有有效周次，不能进网格
            if (weeks.isEmpty()) continue

            val row = rowByLesson[a.lessonNo]
            // 只在确实产出块时才登记课程，保证「每门课至少有一个时间块」这一不变式
            courses.getOrPut(id) {
                Course(
                    id = id,
                    name = a.courseName.ifBlank { row?.courseName.orEmpty() },
                    teacher = a.teacher.ifBlank { row?.teacher.orEmpty() },
                    courseCode = row?.courseCode ?: courseCodeOf(a.lessonNo),
                    // 学分由课程列表表提供；缺该表时留 null，由用户自行填写
                    credit = row?.credit,
                )
            }

            val roomKey = a.roomId.ifBlank { a.roomName }
            // 末尾带教室：同一门课可能在同一时段的两个教室各有一批周次
            //   （实测「水质分析」第 15 周同时出现在水化学实验室与水文地质学实验室），
            //   不带教室会让两条 id 相撞，网格上互相覆盖。
            val sid = "$id-${a.dayOfWeek}-${a.startPeriod}-${roomKey.ifBlank { "x" }}"
            val prev = sessions[sid]
            sessions[sid] = if (prev == null) {
                CourseSession(
                    id = sid,
                    courseId = id,
                    dayOfWeek = a.dayOfWeek,
                    startPeriod = a.startPeriod,
                    endPeriod = a.endPeriod,
                    weeks = weeks,
                    room = a.roomName,
                )
            } else {
                // 真正的重复块（同课同日同节同教室）只差周次，取并集而不是再插一条，
                // 否则同一格会渲染两次。
                prev.copy(weeks = prev.weeks + weeks, endPeriod = maxOf(prev.endPeriod, a.endPeriod))
            }
        }

        // 在课程列表里、但网格上没有块 = 未排课（实习、形势与政策等）。它们的学分只在这张表里有。
        val unplaced = rows
            .filter { it.lessonNo !in courses.keys }
            .map {
                UnplacedCourse(
                    name = it.courseName,
                    teacher = it.teacher,
                    credit = it.credit ?: 0.0,
                    // EAMS 不提供学时
                    hours = 0,
                    weeksText = it.weeksText,
                    courseCode = it.courseCode,
                )
            }

        return Timetable(
            term = term,
            courses = courses.values.toList(),
            sessions = sessions.values.toList(),
            unplaced = unplaced,
            currentWeek = currentWeek,
        )
    }

    /** `29ZY1608.01` -> `29ZY1608` */
    fun courseCodeOf(lessonNo: String): String = lessonNo.substringBefore('.')
}
