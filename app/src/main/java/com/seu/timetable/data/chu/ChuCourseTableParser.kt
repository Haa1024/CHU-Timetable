package com.seu.timetable.data.chu

/**
 * 长安大学 EAMS 课表页的**文本层解析**。
 *
 * 这一层只负责把 HTML 里的字面内容抠出来，不做任何领域换算；
 * 换算与组装在 [ChuMapper] 里。这样解析规则和业务规则可以分别测试。
 *
 * 已知的两个接口形态（**返回内容不同，不要混用**）：
 * - `GET  /eams/courseTableForStd.action`              -> 页面骨架，含 `ids` 与总周数
 * - `POST /eams/courseTableForStd!courseTable.action`  -> 表格片段，含课表 JS 与课程列表
 */
object ChuCourseTableParser {

    /** 一条教学活动（已从 `index` 表达式换算出星期与节次区间） */
    data class Activity(
        val courseName: String,
        val lessonNo: String,
        val teacher: String,
        val roomId: String,
        val roomName: String,
        /** 实验课的实验项目名（实参 11），如「吸附实验」；理论课为空串 */
        val experiItemName: String,
        val weekBitmap: String,
        /** 1 = 周一 … 7 = 周日 */
        val dayOfWeek: Int,
        /** 1 基，闭区间 */
        val startPeriod: Int,
        val endPeriod: Int,
    )

    /** 课程列表表里的一行（补充信息：学分 / 课程代码 / 起始周） */
    data class CourseRow(
        val lessonNo: String,
        val courseCode: String,
        val courseName: String,
        val category: String,
        val credit: Double?,
        val weeksText: String,
        val teacher: String,
    )

    // ---------------------------------------------------------------- 页面骨架

    /**
     * 从页面骨架里取**学生课表**的 `ids`。
     *
     * 注意：这个值**不是** HTML 元素，而是内联 JS 注入的，且 `std` / `class` 各有一个：
     * ```
     * if(jQuery("#courseTableType").val()=="std"){
     *     bg.form.addInput(form,"ids","197506");   // 取这个
     * }else{
     *     bg.form.addInput(form,"ids","4886");
     * }
     * ```
     * 因此按出现顺序取**第一个**。找不到返回 null（调用方应报错，不要瞎猜一个 id）。
     */
    fun parseStudentIds(skeletonHtml: String): String? =
        IDS_CALL.findAll(skeletonHtml).firstOrNull()?.groupValues?.get(1)

    /** 骨架页里 `startWeek` 下拉的最大选项 = 该学期总周数（实测 1..26） */
    fun parseTotalWeeks(skeletonHtml: String): Int? =
        START_WEEK_OPTION.findAll(skeletonHtml)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .maxOrNull()

    /**
     * 骨架页**自己**处于哪个学期。
     *
     * 骨架页是 `GET courseTableForStd.action`（不带学期参数），因此它永远反映
     * 「会话里当前选中的那个学期」——内联初始化 `semesterCalendar({empty:"false",onChange:"",value:"262"})`
     * 里的 `value` 就是它。
     *
     * ★ 为什么必须解析这个：`startWeek` 下拉的周数是**跟着骨架自己的学期走**的。
     * 若用户要加载旧学期（如 242），而骨架仍在 262，则拿到的 26 周属于 262，
     * 直接采用会让旧学期的周次切换器凭空多出一截。故调用方需比对二者是否一致（见 [ChuTimetableSource]）。
     */
    fun parseSelectedSemesterId(skeletonHtml: String): String? =
        SELECTED_SEMESTER.find(skeletonHtml)?.groupValues?.get(1)

    // ---------------------------------------------------------------- 表格片段

    /**
     * 每节课所在的格位由 `index = 星期 * unitCount + 节` 给出，故先取 `unitCount`。
     * 取不到时按每天 11 节兜底（实测 CHU 恒为 11）。
     */
    fun parseUnitCount(html: String): Int =
        UNIT_COUNT.find(html)?.groupValues?.get(1)?.toIntOrNull() ?: DEFAULT_UNIT_COUNT

    /**
     * 解析全部教学活动。
     *
     * 生成代码里每块的顺序是：
     * ```
     * var teachers = [...];  var actTeachers = [...];  // 教师在这里
     * ...
     * var courseName = "河流动力学(29ZY1608.01)";       // 课程名在这里
     * activity = new TaskActivity(...);
     * index = 3*unitCount+2;  table0.activities[index][...] = activity;
     * ```
     * ★ 教师数组在 `courseName` **之前**，所以**不能**用 `courseName` 切块再去块内找教师——
     *   那样每块取到的都是**下一个块的教师**（实测过，全部串位）。
     *   正确做法：以 `new TaskActivity(` 为主锚点，向前就近找 `courseName` 与 `actTeachers`，
     *   向后取到下一个 `TaskActivity` 之前的 `index = ...` 行。
     */
    fun parseActivities(html: String): List<Activity> {
        val unitCount = parseUnitCount(html)
        val calls = TASK_ACTIVITY.findAll(html).toList()
        val names = COURSE_NAME.findAll(html).toList()
        val teacherArrays = ACT_TEACHERS.findAll(html).toList()

        val out = ArrayList<Activity>(calls.size)

        calls.forEachIndexed { i, call ->
            val args = readCallArgs(html, call.range.last)
            if (args.size < 8) return@forEachIndexed

            val nameMatch = names.lastOrNull { it.range.last < call.range.first } ?: return@forEachIndexed
            val teacherBlock = teacherArrays.lastOrNull { it.range.last < call.range.first }

            val slotEnd = calls.getOrNull(i + 1)?.range?.first ?: html.length
            val tail = html.substring(call.range.last + 1, slotEnd)

            val lessonNo = literal(args.getOrNull(4))
            val bitmap = literal(args.getOrNull(7))
            val slots = INDEX_ASSIGN.findAll(tail).mapNotNull { toSlot(it.groupValues[1], unitCount) }.toList()
            if (slots.isEmpty()) return@forEachIndexed

            val displayName = stripLessonSuffix(unescape(nameMatch.groupValues[1]))
            val teacher = teacherBlock?.groupValues?.get(1)
                ?.let { arr ->
                    NAME_IN_OBJ.findAll(arr)
                        .map { unescape(it.groupValues[1]).trim() }
                        .filter { it.isNotEmpty() }
                        .joinToString(",")
                }
                .orEmpty()

            // 同一个 TaskActivity 可能落在多天；按天分组后把连续节次并成一段
            slots.groupBy { it.first }.forEach { (day, list) ->
                val units = list.map { it.second }.distinct().sorted()
                var runStart = units.first()
                var prev = runStart
                for (k in 1..units.size) {
                    val cur = units.getOrNull(k)
                    if (cur != null && cur == prev + 1) {
                        prev = cur
                        continue
                    }
                    out += Activity(
                        courseName = displayName,
                        lessonNo = lessonNo,
                        teacher = teacher,
                        roomId = literal(args.getOrNull(5)),
                        roomName = literal(args.getOrNull(6)),
                        experiItemName = literal(args.getOrNull(11)),
                        weekBitmap = bitmap,
                        dayOfWeek = day + 1,
                        startPeriod = runStart + 1,
                        endPeriod = prev + 1,
                    )
                    if (cur != null) {
                        runStart = cur
                        prev = cur
                    }
                }
            }
        }
        return out
    }

    /**
     * 解析「课程列表」表。
     *
     * 锚点是 `<tbody ...>`——表格 id 是服务端生成的，不能写死。
     * 同页还有「实验分组」表（5 列），靠**列数 = 12** 区分，故不按顺序取。
     */
    fun parseCourseRows(html: String): List<CourseRow> {
        val out = ArrayList<CourseRow>()
        TBODY.findAll(html).forEach { body ->
            ROW.findAll(body.groupValues[1]).forEach { row ->
                val cells = TD.findAll(row.groupValues[1]).map { clean(it.groupValues[1]) }.toList()
                if (cells.size != 12) return@forEach
                val lessonNo = cells[1]
                if (!LESSON_NO.matches(lessonNo)) return@forEach
                out += CourseRow(
                    lessonNo = lessonNo,
                    courseCode = cells[2],
                    courseName = cells[3],
                    category = cells[4],
                    credit = cells[5].toDoubleOrNull(),
                    weeksText = cells[8],
                    teacher = cells[9],
                )
            }
        }
        return out
    }

    /**
     * 解码周次位图。
     *
     * ★ 口径：**下标 W = 第 W 周**，且**下标 0 不使用**（实测恒为 '0'）。
     *
     * 另一种常见口径是「第 i 个字符（0 基）= 第 i + 1 周」，两者整整差一位。
     * 按那种口径实现会让所有周次整体错开一周，而课表网格看起来完全正常——
     * 用户只会反馈"这周的课怎么没了"，极难定位。
     */
    fun decodeWeekBitmap(bitmap: String?): Set<Int> {
        if (bitmap.isNullOrEmpty()) return emptySet()
        val out = LinkedHashSet<Int>()
        for (i in 1 until bitmap.length) {
            if (bitmap[i] == '1') out += i
        }
        return out
    }

    // ---------------------------------------------------------------- 内部工具

    /**
     * 从 `(` 的下标开始，按括号配平读出实参列表。
     *
     * 必须配平而不能用逗号切分：实参里含 `actTeacherId.join(',')` 这类调用，
     * 直接 split(',') 会把一个实参切成三个（实测 12 个实参会被切成 14 份）。
     * 同时要跳过字符串字面量内的括号与引号。
     */
    private fun readCallArgs(s: String, openParen: Int): List<String> {
        val args = ArrayList<String>()
        val sb = StringBuilder()
        var depth = 0
        var quote = '\u0000'
        var i = openParen
        while (i < s.length) {
            val c = s[i]
            when {
                quote != '\u0000' -> {
                    sb.append(c)
                    if (c == '\\' && i + 1 < s.length) {
                        sb.append(s[i + 1]); i++
                    } else if (c == quote) {
                        quote = '\u0000'
                    }
                }
                c == '"' || c == '\'' -> { quote = c; sb.append(c) }
                c == '(' || c == '[' || c == '{' -> { depth++; if (depth > 1) sb.append(c) }
                c == ')' || c == ']' || c == '}' -> {
                    depth--
                    if (depth == 0) {
                        args += sb.toString().trim()
                        return args
                    }
                    sb.append(c)
                }
                c == ',' && depth == 1 -> { args += sb.toString().trim(); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        return args
    }

    /** `3*unitCount+2` -> (3, 2)；纯数字形态也认 */
    private fun toSlot(expr: String, unitCount: Int): Pair<Int, Int>? {
        INDEX_EXPR.find(expr)?.let {
            val a = it.groupValues[1].toIntOrNull() ?: return null
            val b = it.groupValues[2].toIntOrNull() ?: return null
            return a to b
        }
        val flat = expr.trim().toIntOrNull() ?: return null
        return (flat / unitCount) to (flat % unitCount)
    }

    /** 实参可能是字符串字面量（`"*WM1405"`）也可能是变量（`courseName` / `assistantName`），只取字面量 */
    private fun literal(arg: String?): String {
        val a = arg?.trim() ?: return ""
        if (a.length < 2 || a.first() != '"' || a.last() != '"') return ""
        return unescape(a.substring(1, a.length - 1))
    }

    private fun stripLessonSuffix(name: String): String =
        name.replace(Regex("""[（(]\s*[0-9A-Za-z]+\.[0-9A-Za-z]+\s*[)）]\s*$"""), "").trim()

    private fun unescape(s: String): String =
        s.replace("\\\"", "\"").replace("\\\\", "\\")

    /** 去标签、还原实体、压掉换行与首尾空白（单元格里常有 `\n` 与 `&nbsp;`） */
    private fun clean(cell: String): String =
        cell.replace(Regex("""<[^>]*>"""), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("""\s+"""), " ")
            .trim()

    const val DEFAULT_UNIT_COUNT = 11

    private val IDS_CALL = Regex("""addInput\(\s*form\s*,\s*"ids"\s*,\s*"(\d+)"""")
    private val START_WEEK_OPTION = Regex("""<option value="(\d+)">第\d+周</option>""")

    /**
     * 骨架页里学期选择器的初始化实参：`{empty:"false",onChange:"",value:"262"}`。
     * 用 `[^}]` 而非 `[^)]` 限界——因为同一行里 `jQuery("#...")[0]` 自带右括号，
     * 用 `[^)]*` 会在这里直接失配。
     */
    private val SELECTED_SEMESTER = Regex("""\{[ ]*empty\s*:\s*"[^"]*"[^}]*?value\s*:\s*"(\d+)"""")
    private val UNIT_COUNT = Regex("""var\s+unitCount\s*=\s*(\d+)""")
    private val COURSE_NAME = Regex("""var\s+courseName\s*=\s*"((?:[^"\\]|\\.)*)"\s*;""")
    private val TASK_ACTIVITY = Regex("""new\s+TaskActivity\s*\(""")

    /**
     * 只匹配**形如** `index = 3*unitCount+2;` 或 `index = 35;` 的整句赋值。
     * 不能用宽松的 `index\s*=\s*([^;]+);`——页面别处（学期日历）有 `index='0'>` 这类属性，
     * 宽松匹配会把它们一路吞到下一个分号。
     */
    private val INDEX_ASSIGN = Regex("""index\s*=\s*(\d+(?:\s*\*\s*unitCount\s*\+\s*\d+)?)\s*;""")
    private val INDEX_EXPR = Regex("""^\s*(\d+)\s*\*\s*unitCount\s*\+\s*(\d+)\s*$""")
    private val ACT_TEACHERS = Regex("""var\s+actTeachers\s*=\s*\[(.*?)]\s*;""", RegexOption.DOT_MATCHES_ALL)
    private val NAME_IN_OBJ = Regex("""name\s*:\s*"((?:[^"\\]|\\.)*)"""")
    private val TBODY = Regex("""<tbody[^>]*>(.*?)</tbody>""", RegexOption.DOT_MATCHES_ALL)
    private val ROW = Regex("""<tr[^>]*>(.*?)</tr>""", RegexOption.DOT_MATCHES_ALL)
    private val TD = Regex("""<td[^>]*>(.*?)</td>""", RegexOption.DOT_MATCHES_ALL)

    /** 课程序号形如 `16SZ6005.76` / `1405420X.05` —— 开头是**两位**数字，不是四位 */
    private val LESSON_NO = Regex("""^[0-9A-Za-z]{2,}\.[0-9A-Za-z]+$""")

    /**
     * 本对象全部正则的原始串，**只给单测做语法自检**（见 `RegexIcuStrictnessTest`）。
     *
     * 存在的理由：JVM 与 Android ICU 对正则的严格度不同——孤立的 `}` 在 JVM 里是字面量、
     * 在 ICU 里是语法错误，而错误发生在 object 的 `<clinit>`，表现为"单测全绿、真机一点就崩"。
     * 把正则串暴露出来是唯一能让单测看见它们的口子。
     */
    internal val selfCheckPatterns: List<String>
        get() = listOf(
            IDS_CALL.pattern,
            START_WEEK_OPTION.pattern,
            SELECTED_SEMESTER.pattern,
            UNIT_COUNT.pattern,
            COURSE_NAME.pattern,
            TASK_ACTIVITY.pattern,
            INDEX_ASSIGN.pattern,
            INDEX_EXPR.pattern,
            ACT_TEACHERS.pattern,
            NAME_IN_OBJ.pattern,
            TBODY.pattern,
            ROW.pattern,
            TD.pattern,
            LESSON_NO.pattern,
        )
}
