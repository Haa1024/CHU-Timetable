package com.seu.timetable.data.chu

import com.seu.timetable.data.NotLoggedInException
import com.seu.timetable.data.TimetableException
import com.seu.timetable.data.WebViewCookieJar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** 业务系统是 http（不是 https），认证中心是 https，两者各自一套 cookie。 */
internal const val CHU_EAMS_BASE = "http://bkjw.chd.edu.cn"
internal const val CHU_CAS_BASE = "https://ids.chd.edu.cn/authserver"

/**
 * 认证域的主机名。用于判断"这次响应有没有把我们指向认证中心"。
 *
 * 单独拎出来是因为它有一个**不显眼但很要紧**的用法：探测时只比较 302 的 `Location`，
 * 而**绝不去真的访问它**（理由见 `ChuClient.probeHttp` 的注释）。
 */
internal const val CHU_CAS_HOST = "ids.chd.edu.cn"

/** GET 这个拿页面骨架：含 `ids` / 总周数 / 骨架自身所属学期 */
internal const val CHU_PAGE_URL = "$CHU_EAMS_BASE/eams/courseTableForStd.action"

/** POST 这个拿表格片段：含课表 JS 与课程列表 */
internal const val CHU_TABLE_URL = "$CHU_EAMS_BASE/eams/courseTableForStd!courseTable.action"

internal const val CHU_DATA_QUERY_URL = "$CHU_EAMS_BASE/eams/dataQuery.action"

/** 本科。`project.id` 实测恒为 1。 */
internal const val CHU_PROJECT_ID = "1"

/**
 * `dataType=semesterCalendar` 的请求体。
 *
 * ★ 单独抽成函数，是为了让「[semesterId] 不能为空」这条**能被单测钉住**。
 *   它曾经就是空的（`value=&empty=false`），后果是服务端回一个空学期列表，
 *   而界面上只表现为「无法获取当前学期」——从现象完全看不出是少了一个参数，
 *   为此赔掉了一整轮排查。页面自己的调用是
 *   `semesterCalendar({empty:"false",onChange:"",value:"262"})`，`value` 必填。
 */
internal fun chuSemesterCalendarBody(semesterId: String): String =
    "dataType=semesterCalendar&value=$semesterId&empty=false"

/**
 * 用桌面 Chrome 的 UA。
 *
 * 两个理由：① EAMS 是桌面站点，WebView 兜底登录时用的也是这个形态；
 * ② 服务端对 `MULTIFACTOR_BROWSER_FINGERPRINT` 这类风控字段的判定与 UA 相关，
 * 后台登录与 WebView 登录应表现为"同一个客户端"。
 */
internal const val CHU_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

private val FORM = "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()

/**
 * 课表页骨架一次 GET 能同时拿到的三样东西。
 *
 * 之所以合成一个结构体：三者都来自**同一个响应**，分两次取就会把这个页面拉两遍
 * （多一次往返、多一份一整页 HTML 的内存）。骨架是纯静态模板，没有任何随时间变化的内容。
 *
 * ★ [totalWeeks] 只对 [semesterId] 那个学期成立——骨架页不带学期参数，
 * 永远反映会话里当前选中的学期。加载旧学期时必须先比对（见 [ChuTimetableSource]）。
 */
data class ChuSkeleton(
    /** 学生课表 `ids`。页面被换成登录页时为 null */
    val ids: String?,
    /** `startWeek` 下拉的最大选项 = [semesterId] 那个学期的总周数 */
    val totalWeeks: Int?,
    /** 骨架页自己所属的学期 id；解析不到为 null */
    val semesterId: String?,
) {
    companion object {
        val EMPTY = ChuSkeleton(null, null, null)
    }
}

/**
 * 长安大学 EAMS 客户端。
 *
 * 会话由 [WebViewCookieJar]（接 Android 的 CookieManager）承载，
 * 因此**后台登录与 WebView 登录共享同一份 cookie**，两条路可互为兜底。
 *
 * 接口形态（详见技能 `chu-eams-timetable-api`）：
 * ```
 * 1. GET  CHU_PAGE_URL                    -> 页面骨架（ids / 总周数 / 骨架学期）
 * 2. POST CHU_DATA_QUERY_URL dataType=... -> 学期列表
 * 3. POST CHU_TABLE_URL                   -> 课表 HTML  ← 必须带 ids，否则 500
 * ```
 * 到业务接口的请求数因此是 **2 次**（骨架 + 片段）拿全量课表，学期列表另计。
 */
class ChuClient(
    cookieJar: CookieJar = WebViewCookieJar(),
    private val http: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {

    /**
     * 探测专用：**不跟随重定向**。
     *
     * ★ 为什么必须单独配一个：未登录时业务接口会 302 指向 CAS 登录页，而
     * `ids.chd.edu.cn` **每被 GET 一次就下发一把新的** `JSESSIONID=…; Path=/authserver`
     * （实测确认）。cookie 经共享的 [WebViewCookieJar] 进的是同一个 `CookieManager`，
     * 于是"探测"会把 WebView 里那张登录页正在用的会话换掉——用户提交时
     * `execution` / `pwdEncryptSalt` 已与当前会话不配对，CAS 便**静默把登录页重发一遍**，
     * 表现为「在网页里登录完又被弹回统一身份认证页」。
     *
     * 裸看 302 还更省：不必每 1.5 秒把 26 KB 的登录页拉下来一遍。
     */
    private val probeHttp: OkHttpClient = http.newBuilder()
        .followRedirects(false)
        .build()

    /**
     * 骨架页。一次 GET 拿到 `ids` / 总周数 / 骨架所属学期三样东西
     */
    suspend fun skeleton(force: Boolean = false): ChuSkeleton {
        cachedSkeleton?.let { if (!force) return it }

        val page = rawGet(CHU_PAGE_URL)
        // ★ 被指向认证中心 = 未登录。**到此为止，不跟过去**，理由见 [probeHttp]。
        //   这一步的结论比"页面正文里能不能解析出 ids"更硬：302 是服务端自己说的，
        //   不依赖任何解析规则，页面改了也不会失效。
        if (page.code in 300..399 && page.location.orEmpty().contains(CHU_CAS_HOST)) {
            throw NotLoggedInException("课表页把请求转到了登录页 —— 会话已失效")
        }
        if (page.code != 200) throw httpErrorOf(page.code, page.reason, CHU_PAGE_URL)

        val parsed = ChuSkeleton(
            ids = ChuCourseTableParser.parseStudentIds(page.body),
            totalWeeks = ChuCourseTableParser.parseTotalWeeks(page.body),
            semesterId = ChuCourseTableParser.parseSelectedSemesterId(page.body),
        )
        // 只缓存"看得到 ids"的成功结果：解析不到多半是拿到了登录页，
        // 缓存下来会让"重新登录后仍报未登录"这种难查的假故障出现。
        if (parsed.ids != null) cachedSkeleton = parsed
        return parsed
    }

    /**
     * 学生课表的 `ids`。
     *
     * 它**不是** HTML 元素，而是骨架页内联 JS 注入的（`bg.form.addInput(form,"ids","197506")`），
     * 且 `std` / `class` 各有一个 —— 解析器取的是第一个（std）。
     * 实测同一学生跨学期稳定，故随骨架一起缓存。
     */
    suspend fun studentIds(force: Boolean = false): String =
        skeleton(force).ids
            ?: throw NotLoggedInException("拿不到课表 ids —— 会话可能已失效（页面被换成了登录页）")

    /**
     * 学期列表（含当前学期）。
     *
     * ★ **`value` 是必填参数，它不是"可选筛选"。** 它的值是「当前学期 id」，也就是
     *   骨架页内联初始化里那个 `value`：
     *   `semesterCalendar({empty:"false",onChange:"",value:"262"}, "searchTable()")`。
     *   本方法一开始发的是 `value=`（空），实测拿到的是**空学期列表**，而界面上只表现为
     *   「无法获取当前学期」——完全看不出是少了个参数，排查代价极大。
     *
     * 当前学期 id 取自骨架页（[ChuCourseTableParser.parseSelectedSemesterId]）。
     * 调 [skeleton] 不额外发请求：它本来就要被 [com.seu.timetable.data.chu.ChuTimetableSource.load]
     * 用到，且有进程级缓存；顺带也把"未登录"变成一次明确的 [NotLoggedInException]，
     * 而不是伪装成"这个账号没有学期"。
     *
     * 返回的是**该学生有数据的全部学期**（跨学年分组），不只是当前学年。
     */
    suspend fun semesterCalendar(): ChuSemesterCalendar {
        val currentId = skeleton().semesterId
        if (currentId != null) {
            val cal = ChuSemesterParser.parseCalendar(
                post(CHU_DATA_QUERY_URL, chuSemesterCalendarBody(currentId)),
            )
            if (cal.all.isNotEmpty()) return cal
        }
        // 退化：老式 option 列表。它没有"当前学期"的概念，[ChuSemesterCalendar.currentId]
        // 只能是 null，由调用方自算（见 chuResolveSemester 的兜底规则）。
        val fallback = ChuSemesterParser.parseOptionList(
            post(CHU_DATA_QUERY_URL, "dataType=semester&projectId=$CHU_PROJECT_ID"),
        )
        return ChuSemesterCalendar(groups = listOf(fallback), currentId = null)
    }

    /**
     * 拉某个学期的课表 HTML。
     *
     * ★ `ids` 是**必需**参数：实测去掉后服务端直接抛 500。
     * ★ `startWeek` 留空 = 全学期。传具体周号会得到**被服务端过滤过**的片段
     *   （课程列表不会同步过滤，会凭空多出一堆"未排课"），所以同步一律取全量。
     */
    suspend fun fetchCourseTable(semesterId: String, ids: String): String {
        val body = "ignoreHead=1&setting.kind=std&startWeek=&semester.id=$semesterId&ids=$ids"
        val html = post(CHU_TABLE_URL, body)
        if (!html.contains("new TaskActivity(") && !html.contains("courseTableForm")) {
            // 会话失效时服务端会把登录页当成 200 正文返回，必须显式识别
            if (html.contains("pwdEncryptSalt") || html.contains("authserver")) {
                throw NotLoggedInException("课表接口返回登录页 —— 会话已失效")
            }
            throw TimetableException("课表接口返回了无法解析的内容（${html.length} 字节）")
        }
        return html
    }

    /** 用一次真实接口调用判断登录态，而不是猜某个 cookie 是否存在 */
    suspend fun hasSession(): Boolean = runCatching {
        skeleton(force = true).ids != null
    }.getOrDefault(false)

    /**
     * 清掉骨架页缓存（含 `ids` 与总周数）。
     *
     * 退出登录、换账号后**必须**调它，否则界面会认为仍然登录着——最典型的表现是
     * 「退出登录了却还能拉到课表」。
     *
     * 注意它清的是**进程级**的那一份，见 [cachedSkeleton] 的说明。
     */
    fun clearSessionCache() {
        cachedSkeleton = null
    }

    // ---------------------------------------------------------------- 传输

    /** 一次**不跟随重定向**的 GET：只交事实、不给结论，302 的含义由调用方按业务判断。 */
    private suspend fun rawGet(url: String): Raw = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", CHU_UA)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .get()
            .build()
        val resp = runCatching { probeHttp.newCall(req).execute() }
            .getOrElse { throw TimetableException("请求失败：${it.message}", it) }
        resp.use { Raw(it.code, it.header("Location"), it.message, it.body?.string().orEmpty()) }
    }

    /**
     * 裸响应。刻意不在这里判成败：3xx 到底是"未登录"还是"换个地址"，
     * 只有调用方按业务含义才说得清（登录之后的 302 也照样是 3xx）。
     */
    private data class Raw(
        val code: Int,
        val location: String?,
        val reason: String,
        val body: String,
    )

    private suspend fun post(url: String, form: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", CHU_UA)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("X-Requested-With", "XMLHttpRequest")
            .post(form.toRequestBody(FORM))
            .build()
        execute(req, url)
    }

    private fun execute(req: Request, url: String): String {
        val resp = runCatching { http.newCall(req).execute() }
            .getOrElse { throw TimetableException("请求失败：${it.message}", it) }
        resp.use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw httpErrorOf(it.code, it.message, url)
            return body
        }
    }

    private fun httpErrorOf(code: Int, message: String, url: String): TimetableException = when (code) {
        401, 403 -> NotLoggedInException("登录态已失效（HTTP $code）")
        // 500 在 CHU 侧最典型的成因是缺 ids；这条提示能省掉一轮排查
        else -> TimetableException("HTTP $code $message <- $url")
    }

    companion object {
        /**
         * 骨架页解析结果缓存。页面是静态模板，同一会话内不会变，故缓存整份结果。
         *
         * ★ **进程级共享，而不是实例字段。**
         *
         * 会话本身存在 Android 的 `CookieManager` 里，是全局的；缓存若各实例一份，就会出现
         * 「这个实例认为已登录（缓存里有 ids）、那个实例认为没有」这种自相矛盾的状态。
         * 而"随手 new 一个 ChuClient"恰恰是最容易发生的事——仓库一个、登录页一个、
         * `SessionManager` 一个。实测后果是「退出登录后仍能拉到课表」，极难定位。
         *
         * 只缓存"看得到 ids"的成功结果：解析不到多半是拿到了登录页，缓存下来会让
         * 「重新登录后仍报未登录」这种同样难查的假故障出现。
         */
        @Volatile
        private var cachedSkeleton: ChuSkeleton? = null
    }
}
