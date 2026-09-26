package com.seu.timetable.data.chu

import com.seu.timetable.domain.compressWeeks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 用**真实抓下来的长安大学 EAMS 响应**（已脱敏，个人标识为 0）验证解析逻辑。
 *
 * 这些测试是"坑的护栏"：谁要是把周次位图按「第 i 个字符 = 第 i + 1 周」解、
 * 把星期当成 1 基、用 `courseName` 切块去找教师、或把课程序号正则写成 `\d{4,}`，
 * 这里会立刻红。
 *
 * 夹具来源与语义详见技能 `chu-eams-timetable-api`。
 */
class ChuCourseTableParserTest {

    private fun resource(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!
            .readBytes().toString(Charsets.UTF_8)

    private fun skeleton() = resource("chu_course_table_page_262.html")
    private fun fall() = resource("chu_course_table_262.html")
    private fun springWeek2() = resource("chu_course_table_242_week2.html")

    private fun semester() = ChuSemester(id = "262", schoolYear = "2026-2027", termName = "1")

    /** 2026-09-07 是周一 */
    private fun term() = ChuDefaults.termContextOf(
        semester = semester(),
        firstMonday = LocalDate.of(2026, 9, 7),
        totalWeeks = 26,
    )

    private fun timetable() = ChuMapper.buildTimetable(
        activities = ChuCourseTableParser.parseActivities(fall()),
        rows = ChuCourseTableParser.parseCourseRows(fall()),
        term = term(),
        currentWeek = 3,
    )

    // ------------------------------------------------------------ 页面骨架

    @Test
    fun `学生课表 ids 取第一个 不是班级课表那个`() {
        val html = skeleton()
        // std 与 class 各有一个 ids，且都不是 input 元素而是内联 JS 注入的
        assertEquals(
            "骨架页里不该有 name=ids 的 input",
            0,
            Regex("""<input[^>]*name="ids"""").findAll(html).count(),
        )
        assertEquals(2, Regex("""addInput\(form,\s*"ids"""").findAll(html).count())

        val std = ChuCourseTableParser.parseStudentIds(html)
        assertEquals("取 std 分支那个", "197506", std)
        assertNotEquals("绝不能取到班级课表那个", "4886", std)
    }

    @Test
    fun `总周数来自 startWeek 下拉`() {
        assertEquals(26, ChuCourseTableParser.parseTotalWeeks(skeleton()))
    }

    @Test
    fun `拿不到骨架页时返回 null 而不是瞎猜`() {
        assertNull(ChuCourseTableParser.parseStudentIds("<html>没有</html>"))
        assertNull(ChuCourseTableParser.parseTotalWeeks("<html>没有</html>"))
        assertEquals(
            "unitCount 取不到时按 11 兜底",
            ChuCourseTableParser.DEFAULT_UNIT_COUNT,
            ChuCourseTableParser.parseUnitCount("<html>没有</html>"),
        )
    }

    // ------------------------------------------------------------ 周次位图（核心坑）

    @Test
    fun `周次位图下标即周号 下标0不使用`() {
        assertEquals((1..6).toSet(), ChuCourseTableParser.decodeWeekBitmap("0111111" + "0".repeat(46)))
        assertEquals((1..8).toSet(), ChuCourseTableParser.decodeWeekBitmap("011111111" + "0".repeat(44)))
        assertEquals(setOf(15), ChuCourseTableParser.decodeWeekBitmap("0".repeat(15) + "1" + "0".repeat(37)))
    }

    @Test
    fun `位图首字符为1时不能解出第1周 这是两种口径的分水岭`() {
        // 「第 i 个字符 = 第 i + 1 周」的口径会在这里给出 {1}；长安大学必须给出空集
        assertEquals(emptySet<Int>(), ChuCourseTableParser.decodeWeekBitmap("1" + "0".repeat(52)))
        assertTrue(
            "任何情况下都不该产出第 0 周",
            ChuCourseTableParser.decodeWeekBitmap("1".repeat(53)).none { it == 0 },
        )
        assertEquals(emptySet<Int>(), ChuCourseTableParser.decodeWeekBitmap(null))
        assertEquals(emptySet<Int>(), ChuCourseTableParser.decodeWeekBitmap(""))
    }

    @Test
    fun `位图解出的周次与页面文案一致`() {
        val t = timetable()
        val river = t.courses.single { it.name == "河流动力学" }
        assertEquals("1-6", compressWeeks(t.weeksOf(river.id)))

        val water = t.courses.single { it.name == "水质分析" }
        assertEquals("9-10,12-13,15", compressWeeks(t.weeksOf(water.id)))
    }

    // ------------------------------------------------------------ 星期与节次（0 基坑）

    @Test
    fun `index 表达式里星期是0基 0即周一`() {
        val t = timetable()
        val survey = t.courses.single { it.name == "水文测验" }
        // `index = 0*unitCount+0` 渲染在"第1节·星期一"列
        assertTrue(
            "0*unitCount+0 必须落在周一第1节",
            t.sessionsOf(survey.id).any { it.dayOfWeek == 1 && it.startPeriod == 1 },
        )
        // `index = 2*unitCount+...` 落在周三
        assertTrue(t.sessionsOf(survey.id).any { it.dayOfWeek == 3 && it.startPeriod == 1 })
    }

    @Test
    fun `连续节次合并成一段 不拆开`() {
        val t = timetable()
        // index = 1*unitCount+6 与 +7 -> 周二第7-8节，合并为一段
        val river = t.courses.single { it.name == "河流动力学" }
        val tue = t.sessionsOf(river.id).single { it.dayOfWeek == 2 }
        assertEquals(7, tue.startPeriod)
        assertEquals(8, tue.endPeriod)
        assertEquals(2, tue.periodSpan)

        // 三节连排：3*unitCount+8..10 -> 周四第9-11节
        val job = t.courses.single { it.name == "发展决策与就业指导" }
        val thu = t.sessionsOf(job.id).single()
        assertEquals(4, thu.dayOfWeek)
        assertEquals(9, thu.startPeriod)
        assertEquals(11, thu.endPeriod)
    }

    @Test
    fun `节次分组之和等于每天节数`() {
        val t = term()
        assertEquals(ChuCourseTableParser.DEFAULT_UNIT_COUNT, t.periodsPerDay)
        assertEquals(ChuCourseTableParser.DEFAULT_UNIT_COUNT, ChuDefaults.UNITS_PER_DAY)
        assertEquals(DayOfWeek.MONDAY, LocalDate.of(2026, 9, 7).dayOfWeek)
        assertEquals(1, t.weekOf(LocalDate.of(2026, 9, 7)))
        assertEquals(3, t.weekOf(LocalDate.of(2026, 9, 25)))
    }

    // ------------------------------------------------------------ 两层模型

    @Test
    fun `262 全量解析出 11 门课与 32 个时间块`() {
        val t = timetable()
        assertEquals(32, t.sessions.size)
        assertEquals(11, t.courses.size)
    }

    @Test
    fun `同一门课的多个教室多个时段归到同一门课`() {
        val t = timetable()
        val water = t.courses.single { it.name == "水质分析" }
        val blocks = t.sessionsOf(water.id)

        // 实测 9 个块：周二/周五各有一次 *WM3209；周三有 *WM3209 之外的实验室；
        // 第 15 周周二的水化学实验室更是从第 1 节连排到第 8 节，被服务端拆成 4 条 TaskActivity
        // （各自 2 节），故按「一块 = 一个 TaskActivity 的连续节次」切出来就是 9 个，不是 7 个。
        assertEquals(9, blocks.size)
        assertEquals(
            setOf("*WM3209", "水化学实验室", "水文地质学实验室"),
            blocks.map { it.room }.toSet(),
        )
        assertTrue(blocks.all { it.courseId == water.id })
    }

    @Test
    fun `同一时段不同教室必须留成两个块 不能互相覆盖`() {
        val t = timetable()
        val water = t.courses.single { it.name == "水质分析" }

        // 周二第 5-6 节：*WM3209 上第 9/10/12/13 周，水化学实验室上第 15 周。
        // 块 id 若不带教室，这两条会算出同一个 id，网格上后写的把先写的覆盖掉——
        // 表现是「少了一半周次」且**不报任何错**，属最难查的静默失败。
        val tue56 = t.sessionsOf(water.id).filter { it.dayOfWeek == 2 && it.startPeriod == 5 }
        assertEquals(2, tue56.size)
        assertEquals(setOf("*WM3209", "水化学实验室"), tue56.map { it.room }.toSet())
        assertEquals(setOf(9, 10, 12, 13), tue56.single { it.room == "*WM3209" }.weeks)
        assertEquals(setOf(15), tue56.single { it.room == "水化学实验室" }.weeks)
        assertNotEquals("两条块的 id 必须不同", tue56[0].id, tue56[1].id)
    }

    @Test
    fun `时间块 id 唯一 两个教室不撞`() {
        val t = timetable()
        val ids = t.sessions.map { it.id }
        assertEquals("块 id 必须唯一，否则网格互相覆盖", ids.size, ids.toSet().size)
    }

    @Test
    fun `每门课至少有一个时间块`() {
        val t = timetable()
        for (c in t.courses) {
            assertTrue("课程 ${c.name} 没有任何时间块", t.sessionsOf(c.id).isNotEmpty())
        }
    }

    @Test
    fun `课程名剥掉教学班号 课程代码剥掉课序号`() {
        val t = timetable()
        val river = t.courses.single { it.id == "29ZY1608.01" }
        assertEquals("河流动力学", river.name)
        assertEquals("29ZY1608", river.courseCode)

        val suffix = Regex("""[（(]\s*[0-9A-Za-z]+\.[0-9A-Za-z]+\s*[)）]\s*$""")
        assertTrue("课名里不该残留教学班号", t.courses.none { suffix.containsMatchIn(it.name) })
    }

    @Test
    fun `学分直接来自课程列表表 不用用户手填`() {
        val t = timetable()
        assertEquals(1.5, t.courses.single { it.name == "河流动力学" }.credit!!, 0.0001)
        assertEquals(2.0, t.courses.single { it.name == "石油地质学" }.credit!!, 0.0001)
        assertEquals(2.5, t.courses.single { it.name == "地下水动力学" }.credit!!, 0.0001)
    }

    @Test
    fun `有课程序号但网格里没有块的是未排课 且带学分`() {
        val t = timetable()
        assertEquals(3, t.unplaced.size)

        val policy = t.unplaced.single { it.name == "形势与政策（五）" }
        assertEquals(0.25, policy.credit, 0.0001)
        assertEquals("14-15", policy.weeksText)
        assertEquals("16SZ6005", policy.courseCode)

        assertTrue(t.unplaced.any { it.name == "水文测验实习" })
        assertTrue(t.unplaced.any { it.name == "流域水文分析技术与实践" })
    }

    @Test
    fun `未排课课程不进网格 每个块都能找回它的课`() {
        val t = timetable()
        for (s in t.sessions) {
            assertNotNull("块 ${s.id} 找不到所属课程", t.courseOf(s))
        }
        assertEquals("有块的课就是 11 门，不多不少", 11, t.sessions.map { it.courseId }.toSet().size)
        assertTrue("未排课课程名不该出现在 courses 里", t.courses.none { it.name == "形势与政策（五）" })
    }

    @Test
    fun `教室原样保留 含校区前缀与标记符号`() {
        val t = timetable()
        assertTrue("带校区前缀与标记的教室要原样留", t.sessions.any { it.room == "*WM1316☆" })
        assertTrue("不带前缀的实验室也要留", t.sessions.any { it.room == "水化学实验室" })
    }

    // ------------------------------------------------------------ 跨学期：位图原点一致

    @Test
    fun `春季学期位图原点与秋季一致`() {
        // 242 的第 2 周被服务端过滤后：气排球{2-19} 在，工程测量{1,3,4,...} 不在。
        // 这正是当初判定"位图下标即周号"的那组唯一解对照，作为回归护栏保留。
        val acts = ChuCourseTableParser.parseActivities(springWeek2())

        val ball = acts.single { it.lessonNo == "1405420X.05" }
        assertTrue("气排球第 2 周有课，必须出现", ball.weekBitmap[2] == '1')
        assertEquals("2-19", compressWeeks(ChuCourseTableParser.decodeWeekBitmap(ball.weekBitmap)))

        assertFalse(
            "工程测量(*WH2301) 位图没有第 2 周，不该出现",
            acts.any { it.lessonNo == "26XK1454.01" && it.roomName == "*WH2301" },
        )
        assertTrue(
            "同门课另一教室(*WH2311) 含第 2 周，应当出现",
            acts.any { it.lessonNo == "26XK1454.01" && it.roomName == "*WH2311" },
        )
    }

    @Test
    fun `服务端按周过滤后位图原样返回 不被裁剪`() {
        val acts = ChuCourseTableParser.parseActivities(springWeek2())
        assertTrue("位图长度应恒为 53", acts.all { it.weekBitmap.length == 53 })
    }

    // ------------------------------------------------------------ 学期接口

    @Test
    fun `学期日历解析出分组与当前学期`() {
        val cal = ChuSemesterParser.parseCalendar(resource("chu_semester_calendar.txt"))
        assertEquals("262", cal.currentId)
        assertEquals("262", cal.current?.id)
        assertEquals("2026-2027", cal.current?.schoolYear)
        assertEquals("1", cal.current?.termName)
        assertEquals("2026-2027-1", cal.current?.termCode)

        assertEquals(17, cal.all.size)
        assertEquals(9, cal.groups.size)
        assertEquals("最后一个学年只有秋季一个学期", 1, cal.groups.last().size)
    }

    @Test
    fun `学期 id 是内部数字 跨学年不可推算 必须现拉`() {
        val cal = ChuSemesterParser.parseCalendar(resource("chu_semester_calendar.txt"))
        assertEquals(listOf("72", "73", "80", "81"), cal.all.map { it.id }.take(4))
        // 2016-2019 三个学年整个缺失。若按"每学年 +2 个 id"从 2015-2016 推算，
        // 2026-2027 会算成 72+11*20=292，而真实值是 262。这条就是防止有人日后改成推算。
        assertNotEquals("262", (72 + 11 * 20).toString())
        assertEquals(262, cal.current?.id?.toInt())
    }

    @Test
    fun `学期 option 列表作为兜底也能用`() {
        val list = ChuSemesterParser.parseOptionList(resource("chu_semester_list.txt"))
        assertEquals(17, list.size)
        assertEquals("72", list.first().id)
        assertEquals("2015-2016", list.first().schoolYear)
        assertEquals("1", list.first().termName)
        assertEquals("2015-2016学年1学期", list.first().displayName)
    }

    /**
     * ★ 回归护栏：`value` 曾经是空的（`dataType=semesterCalendar&value=&empty=false`）。
     *
     * 空 `value` 拿回来的是**空学期列表**，而界面上只表现为「无法获取当前学期」——
     * 从现象完全看不出是少了一个参数。为此赔掉了一整轮排查，所以在这里钉死。
     *
     * 页面自己的调用就是带值的：`semesterCalendar({empty:"false",onChange:"",value:"262"})`，
     * 且那个 `262` 正是骨架页里能解析出来的学期 id。
     */
    @Test
    fun `学期日历请求必须带上骨架页那个学期 id 不能为空`() {
        assertEquals(
            "dataType=semesterCalendar&value=262&empty=false",
            chuSemesterCalendarBody("262"),
        )
        assertFalse(
            "value 不能为空——服务端会当成「没有学期」而回空列表",
            chuSemesterCalendarBody("262").contains("value=&"),
        )
    }

    /** `value` 的值必须来自骨架页（`SELECTED_SEMESTER`），不能写死。 */
    @Test
    fun `学期 id 从骨架页解析得到`() {
        val html = skeleton()
        assertEquals("262", ChuCourseTableParser.parseSelectedSemesterId(html))
        assertEquals("26", ChuCourseTableParser.parseTotalWeeks(html).toString())
        assertEquals("197506", ChuCourseTableParser.parseStudentIds(html))
    }

    // ------------------------------------------------------------ 边界与容错

    /** 造一段最小可用的生成代码；[teachers] 传 null 表示省略教师数组 */
    private fun synth(
        name: String,
        lessonNo: String,
        room: String,
        bitmap: String,
        indexExprs: List<String>,
        teachers: String? = "张老师",
        roomId: String = "101",
    ): String {
        val t = if (teachers == null) "" else
            "var teachers = [{id:1,name:\"$teachers\",lab:false}];\n" +
                "var actTeachers = [{id:1,name:\"$teachers\",lab:false}];\n"
        val idx = indexExprs.joinToString("\n") {
            "index =$it;\ntable0.activities[index][table0.activities[index].length]=activity;\n"
        }
        return "var unitCount = 11;\n" +
            "var table0 = new CourseTable(2026,77);\n" +
            t +
            "var courseName = \"$name($lessonNo)\";\n" +
            "activity = new TaskActivity(actTeacherId.join(','),actTeacherName.join(',')," +
            "\"1($lessonNo)\",courseName,\"$lessonNo\",\"$roomId\",\"$room\",\"$bitmap\",null,\"\",assistantName,\"\");\n" +
            idx
    }

    private fun one(html: String) = ChuMapper.buildTimetable(
        activities = ChuCourseTableParser.parseActivities(html),
        term = term(),
    )

    @Test
    fun `实参里的 join(逗号) 会被正确配平 不会把参数切碎`() {
        // 若用 split(',') 切参数，12 个实参会变成 14 份，roomName 会取到错位值
        val acts = ChuCourseTableParser.parseActivities(fall())
        val first = acts.first()
        assertEquals("*WM1405", first.roomName)
        assertEquals("29ZY1608.01", first.lessonNo)
        assertTrue("教师应非空", first.teacher.isNotBlank())
    }

    @Test
    fun `教师取自本块 不会串到下一块`() {
        val t = timetable()
        assertEquals("王金凤", t.courses.single { it.name == "河流动力学" }.teacher)
        assertEquals("杨建军", t.courses.single { it.name == "发展决策与就业指导" }.teacher)
        assertEquals("李金龙,陈宇", t.courses.single { it.name == "水文统计学" }.teacher)
    }

    @Test
    fun `位图全0的活动不进网格`() {
        val html = synth("空课", "TEST0001.01", "A101", "0".repeat(53), listOf("0*unitCount+0"))
        val t = one(html)
        assertEquals(0, t.sessions.size)
        assertEquals("没有块的课不该被登记", 0, t.courses.size)
    }

    @Test
    fun `没有教师数组时教师留空 由课程列表兜底`() {
        val html = synth(
            "无师课", "TEST0002.01", "A102", "0111" + "0".repeat(49),
            listOf("1*unitCount+2"), teachers = null,
        )
        val t = one(html)
        assertEquals("", t.courses.single().teacher)
        assertEquals(2, t.sessions.single().dayOfWeek)
        assertEquals(3, t.sessions.single().startPeriod)
    }

    @Test
    fun `同一活动的多天分别成块 不跨天合并`() {
        val html = synth(
            "两天课", "TEST0003.01", "A103", "0111" + "0".repeat(49),
            listOf("0*unitCount+0", "0*unitCount+1", "4*unitCount+0", "4*unitCount+1"),
        )
        val t = one(html)
        assertEquals(2, t.sessions.size)
        assertEquals(setOf(1 to 1, 5 to 1), t.sessions.map { it.dayOfWeek to it.startPeriod }.toSet())
        assertTrue(t.sessions.all { it.endPeriod == 2 })
    }

    @Test
    fun `课程列表里的实验分组表不会被当成课程行`() {
        val rows = ChuCourseTableParser.parseCourseRows(fall())
        assertEquals(14, rows.size)
        assertTrue("学分都能解析出来", rows.all { it.credit != null })
        assertTrue("不该混入 5 列的实验分组表", rows.none { it.courseName.contains("实验项目") })
    }

    @Test
    fun `课程序号是两位数字开头 不是四位`() {
        val rows = ChuCourseTableParser.parseCourseRows(fall())
        assertTrue("16SZ6005.76 / 12XK1110.37 这类必须能解析", rows.any { it.lessonNo == "16SZ6005.76" })
        assertTrue("带字母后缀的也要能解析", rows.any { it.lessonNo == "87TX1002.I7" })
    }

    // ------------------------------------------------------------ 骨架学期 / 总周数来源

    @Test
    fun `骨架页能解析出它自己所属的学期`() {
        // 内联初始化里 value:"262"，与课表片段的 semester.id 一致
        assertEquals("262", ChuCourseTableParser.parseSelectedSemesterId(skeleton()))
        assertNull(ChuCourseTableParser.parseSelectedSemesterId("<html>没有</html>"))
    }

    @Test
    fun `骨架学期与目标学期一致才采用下拉周数`() {
        val s262 = ChuSemester(id = "262", schoolYear = "2026-2027", termName = "1")
        val s242 = ChuSemester(id = "242", schoolYear = "2025-2026", termName = "2")
        val sk = ChuSkeleton(ids = "197506", totalWeeks = 26, semesterId = "262")
        val acts = ChuCourseTableParser.parseActivities(fall())

        assertEquals(
            "骨架就是 262，26 周可信",
            26,
            chuTotalWeeksOf(sk, s262, acts),
        )
        // ★ 关键护栏：拿 262 的骨架去渲染 242 时，26 周属于 262，必须改用位图兜底。
        //   262 的位图里最大周次为 17（32 个块里最晚的一门），绝不能是骨架给的 26。
        assertEquals(17, chuTotalWeeksOf(sk, s242, acts))
    }

    @Test
    fun `骨架学期解析不到时仍采用下拉周数`() {
        val s = ChuSemester(id = "242", schoolYear = "2025-2026", termName = "2")
        val sk = ChuSkeleton(ids = "197506", totalWeeks = 26, semesterId = null)
        assertEquals(26, chuTotalWeeksOf(sk, s, emptyList()))
    }

    @Test
    fun `空课表且无骨架周数时退回兜底值`() {
        val s = ChuSemester(id = "242", schoolYear = "2025-2026", termName = "2")
        val sk = ChuSkeleton(null, null, "242")
        assertEquals(ChuDefaults.FALLBACK_TOTAL_WEEKS, chuTotalWeeksOf(sk, s, emptyList()))
    }

    // ------------------------------------------------------------ 学期选择

    @Test
    fun `学期码能定位到对应学期 找不到就报错不静默退回`() {
        val cal = ChuSemesterParser.parseCalendar(resource("chu_semester_calendar.txt"))

        assertEquals("262", chuResolveSemester(cal, "2026-2027-1").id)
        assertEquals("72", chuResolveSemester(cal, "2015-2016-1").id)

        // termCode 为 null = 当前学期
        assertEquals("262", chuResolveSemester(cal, null).id)

        // 明确选了不存在的学期必须报错——静默退回当前学期是查不出来的错
        val e = runCatching { chuResolveSemester(cal, "1999-2000-1") }.exceptionOrNull()
        assertTrue("应抛 TimetableException，实际 $e", e is com.seu.timetable.data.TimetableException)

        val empty = runCatching { chuResolveSemester(ChuSemesterCalendar.EMPTY, null) }.exceptionOrNull()
        assertTrue("空学期表应报错", empty is com.seu.timetable.data.TimetableException)
    }

    @Test
    fun `没有当前学期标记时按最新学期兜底`() {
        val cal = ChuSemesterCalendar(
            groups = listOf(
                listOf(ChuSemester("1", "2024-2025", "1")),
                listOf(ChuSemester("2", "2025-2026", "1")),
            ),
            currentId = null,
        )
        assertEquals("2", chuResolveSemester(cal, null).id)
    }

    // ------------------------------------------------------------ 开学日兜底

    @Test
    fun `未配置开学日时兜底为本周一 且只影响当前周推算`() {
        // 2026-09-25 是周五 -> 兜底本周一 = 2026-09-21
        assertEquals(
            LocalDate.of(2026, 9, 21),
            provisionalFirstMonday(LocalDate.of(2026, 9, 25)),
        )
        assertEquals(
            "周一当天应返回自己",
            LocalDate.of(2026, 9, 21),
            provisionalFirstMonday(LocalDate.of(2026, 9, 21)),
        )
        assertEquals(
            "周日属于该周的周一之后 6 天",
            LocalDate.of(2026, 9, 21),
            provisionalFirstMonday(LocalDate.of(2026, 9, 27)),
        )

        // 兜底后 weekOf 必然算出第 1 周，落在合法区间内
        val term = ChuDefaults.termContextOf(
            semester = ChuSemester("262", "2026-2027", "1"),
            firstMonday = provisionalFirstMonday(LocalDate.of(2026, 9, 25)),
            totalWeeks = 26,
        )
        assertEquals(1, term.weekOf(LocalDate.of(2026, 9, 25)))
    }
}
