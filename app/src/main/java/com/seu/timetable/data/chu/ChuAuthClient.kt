package com.seu.timetable.data.chu

import android.webkit.CookieManager
import com.seu.timetable.util.DebugLog
import com.seu.timetable.data.WebViewCookieJar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** CAS 登录页。带 `service` 参数时才会把票发往该 service。 */
internal const val CHU_CAS_LOGIN_URL = "$CHU_CAS_BASE/login"

/**
 * 站点自己注册的换票目标。
 *
 * ★ 必须**逐字符**等于登录页里 `var service = [...]` 的取值，即
 * `http://bkjw.chd.edu.cn/eams/home.action`。
 *
 * CAS 是拿这个字符串去比对注册表的，差一个字符就会在换票那步拿到
 * `ticket=Unauthorized Service` —— 那种失败看起来像网络不通，实则一个字符都不许错。
 * 常见的两种"顺手改坏"：
 *  - 把 `/eams/home.action` 省成 `/eams/`（用户给的入口是 `.../eams/`，很容易照抄）；
 *  - 把 http 写成 https（业务域**根本没有** https，见 `network_security_config.xml`）。
 */
internal const val CHU_CAS_SERVICE = "$CHU_EAMS_BASE/eams/home.action"

/** 认证域原点。POST 时 `Origin` 头用它，与浏览器一致。 */
internal const val CHU_CAS_ORIGIN = "https://ids.chd.edu.cn"

private val FORM = "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()
private const val ACCEPT_HTML = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
private const val ACCEPT_ANY = "application/json, text/plain, */*"

/**
 * 一次自动登录的结果。**刻意做成多分支**：不同分支要让 UI 说不同的话、做不同的事
 * （重输口令 / 转网页 / 稍后重试 / 报错），压成一个 Boolean 会把它们全糊掉。
 */
sealed interface ChuAuthResult {

    /**
     * 认证通过，且业务域会话已建立。
     * @param protocol 实际协商出的协议。恒为 `h2`（见 [ChuAuthClient] 的说明），
     *   带出来只为在日志与诊断里能一眼确认这件事真的发生了。
     * @param eamsSession 跟票那一跳是否真的落到了 eams 而不是又被弹回登录页。
     *   为 false 时票是拿到了、但业务会话没建起来，值得让调用方知道。
     */
    data class Success(val protocol: String, val eamsSession: Boolean) : ChuAuthResult

    /** 口令不对。**不要重试**，直接请用户核对——继续试只会把账号推向验证码。 */
    data object BadCredentials : ChuAuthResult

    /** 站点要验证码（`captchaSwitch == "2"`，是**滑块**），程序给不出来，只能转网页。 */
    data object CaptchaRequired : ChuAuthResult

    /**
     * 本地预算拦下了这次提交，**没有发出任何请求**。
     * @param exhausted true = 窗口内次数用尽；false = 只是距上次太近
     */
    data class Throttled(val retryAfterSeconds: Long, val exhausted: Boolean) : ChuAuthResult

    /**
     * 流程键（`execution`）已失效。此时**重新取一次登录页再提交是安全的**：
     * 该失败发生在口令被判定之前，不额外消耗"口令机会"。
     */
    data object FlowExpired : ChuAuthResult

    /** 登录页里没有 `pwdEncryptSalt`。站点自己会退回明文提交，本端不干这事。 */
    data object NoSalt : ChuAuthResult

    /** 网络 / 协议 / 解析异常。 */
    data class Failed(val reason: String) : ChuAuthResult
}

/**
 * 长安大学统一身份认证（CAS）的纯 HTTP 客户端。
 *
 * ## 它在整条链里的位置
 *
 * 「拿会话」这件事在本项目里只有它管：走完 CAS，把票换成 `bkjw.chd.edu.cn` 上的会话。
 * 拿到会话之后，课表怎么拉是 [ChuClient] 的事——本类不碰课表接口，也不做渲染。
 * 因此**后台自动登录与 WebView 兜底共享同一份 cookie**（都经 [WebViewCookieJar] 接
 * `CookieManager`），两条路可以互为兜底。
 *
 * ## ★ CAS 那条链只允许 HTTP/2
 *
 * 这是本项目最贵的一条结论，值得写在这里而不是只留在技能文档里：
 *
 * | 客户端 | 协议 | 结果 |
 * |---|---|---|
 * | Chromium 默认 | HTTP/2 (`h2`) | 成功，302 + 票据 |
 * | Chromium `--disable-http2` | HTTP/1.1 | **HTTP 500** |
 * | Python urllib / 各种脚本 | HTTP/1.1 | **HTTP 500** |
 * | 错口令 + 正确加密 | 任意 | 干净的 401（说明解密无误，500 发生在**认证成功之后**） |
 *
 * 判别方法只有一条：**对的密码回 500、错的密码回 401**。两者都 500 才是加密/字段的问题；
 * 只有对的密码 500，就是协议问题——此时**不要再改 body、改 header、改 cookie、
 * 改负载均衡节点，全都验证过无效**（技能 §1.1）。
 *
 * 之所以能在 Android 上"天然可用"：OkHttp 默认就走 ALPN 协商 `h2`。
 *
 * ## ★ 这里**不能**把协议限死成只许 h2
 *
 * 早先写成 `.protocols(listOf(Protocol.HTTP_2))`，想表达"要么真用 h2，要么在握手阶段明确失败"。
 * 那是错的，而且错得很响——OkHttp 直接拒绝这种配置：
 *
 * ```
 * java.lang.IllegalArgumentException: protocols must contain h2_prior_knowledge or http/1.1: [h2]
 * ```
 *
 * 关键在它**抛在 `OkHttpClient.Builder` 里**，而这个客户端是 [ChuAuthClient] 的字段，
 * `ChuAuthClient` 在上层又是 lazy 的：于是"构造认证客户端"这一步就崩，
 * 而它恰好发生在用户点下「登录」的那一刻（`submitLogin` → `auth.signOut()`）。
 * 现象是**登录页瞬间消失、弹回上一页**——与"登录失败"长得一模一样，
 * 但日志里连一条 POST 都不会有，非常难往协议配置上想。
 *
 * 所以现在改成**事后检查**：不限制协议，拿到响应后看实际协商出的协议，不是 h2 就明说。
 * 同样达到"把误导人的 500 换成一句说得清的话"的目的，且不会崩。
 *
 * 业务域（`http://bkjw.chd.edu.cn`）本就不能限制协议：明文 HTTP/2（h2c）要预先约定，
 * OkHttp 不会用，而那条链本就是普通 302。故两个域各配一个客户端（见 [cas] / [eams]）。
 *
 * ## 调用顺序不可改
 *
 * ```
 * ① GET  /authserver/login?service=<编码后的 CHU_CAS_SERVICE>   → execution / salt / 剩余口令次数
 * ② GET  /authserver/checkNeedCaptcha.htl?username=<user>       → {"isNeed":false}  ★ 零成本
 * ③ GET  /authserver/tenant/info                                ← 隐蔽必需项
 * ④ GET  /authserver/bfp/info?bfp=<32位大十六进制>               ← 必需，服务端据此种 HttpOnly 指纹 cookie
 * ⑤ POST /authserver/login                                      → 302 Location: <service>?ticket=ST-xxx
 * ⑥ GET  <⑤ 的 Location>                                        → 建立 bkjw.chd.edu.cn 的会话
 * ```
 *
 * ## 关于"别把账号当调试器"
 *
 * 见 [ChuLoginBudget]。本类在发出口令之前会依次完成：读页面声明的剩余次数 → 问验证码 →
 * 取 salt → 加密 → 查本地预算。**任何一步不通过就一个字节都不发**，
 * 且前四步全部不消耗"口令机会"。
 */
class ChuAuthClient(
    /**
     * 与 [ChuClient] 共用同一个罐子才能互为兜底：这里落的会话 cookie，之后
     * 后台拉课表时直接用得上；WebView 登录过的会话，这里也认得。
     */
    private val jar: CookieJar = WebViewCookieJar(),
    private val budget: ChuLoginBudget = ChuLoginBudget.GLOBAL,
    base: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(jar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {

    /**
     * 对认证域（https）专用：**不跟随重定向**。
     *
     * 不跟随是有意的：`POST /login` 的成功响应就是一个 302，**票在 `Location` 里**，
     * 交给 OkHttp 自动跟随后我们就看不到那一跳的 Location 了（而它正是唯一的成功判据）。
     * 另外，持有有效 TGT 时 `GET /login` 也会 302 发票，同样需要自己看。
     *
     * 协议刻意**不作限制**——原因见类注释里那段 ★，那正是本项目最贵的一个崩溃。
     */
    private val cas: OkHttpClient = base.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /**
     * 对业务域（http）专用：默认协议 + 跟随重定向。
     *
     * 跟票那一跳必须跟到底——会话 cookie 可能落在 302 链的中间某一跳上，
     * 停在第一个响应就会把它漏掉，然后就变成"看着都成功、业务接口却说未登录"。
     */
    private val eams: OkHttpClient = base.newBuilder()
        .followRedirects(true)
        .build()

    // ---------------------------------------------------------------- 登录

    /**
     * 用账号密码走完 CAS，并把 `bkjw.chd.edu.cn` 的会话建起来。
     *
     * @param username 学号
     * @param password 统一身份认证口令。**本方法不落盘、不缓存、不打日志**。
     */
    suspend fun login(username: String, password: String): ChuAuthResult = withContext(Dispatchers.IO) {
        val user = username.trim()
        if (user.isEmpty() || password.isEmpty()) {
            return@withContext ChuAuthResult.Failed("账号或密码为空")
        }

        // ---- ① 登录页：三样东西一次拿到 ----
        val loginUrl = "$CHU_CAS_LOGIN_URL?service=${ChuLoginParser.formEncode(CHU_CAS_SERVICE)}"
        val page = when (val n = send(cas, get(loginUrl, accept = ACCEPT_HTML))) {
            is Net.Err -> return@withContext handshakeOrNetworkFailure(n.message)
            is Net.Ok -> n.reply
        }

        // 手上已有有效 TGT 时，CAS 会**直接 302 发票** —— 此时一个字节的口令都不用碰。
        // 这条捷径很值：日常"会话过期后重新续期"绝大多数都走这里，根本不消耗口令机会。
        page.location?.let { loc ->
            ChuLoginParser.ticketFromLocation(loc)?.let { ticket ->
                DebugLog.i("CHU 登录：CAS 直接发票（已有会话）→ 不提交口令")
                return@withContext followTicket(loc, ticket, page.protocol)
            }
        }

        if (page.code != 200) {
            return@withContext ChuAuthResult.Failed("登录页返回 HTTP ${page.code}（协议 ${page.protocol}）")
        }

        val form = ChuLoginParser.parseLoginForm(page.body)
            ?: return@withContext ChuAuthResult.Failed("登录页里找不到用户名密码表单（页面结构可能变了）")

        // ---- ② 站点自己声明的"还剩几次口令机会" ----
        // 归零后页面会强制滑块验证码，此时提交纯属白送一次失败计数。宁可现在就转人工。
        ChuLoginParser.parseBadCredentialsLeft(page.body)?.let { left ->
            if (left <= 0) {
                DebugLog.w("CHU 登录：页面声明剩余口令机会为 0 → 站点已强制验证码，不提交")
                return@withContext ChuAuthResult.CaptchaRequired
            }
            DebugLog.i("CHU 登录：站点声明剩余口令机会 $left 次")
        }

        // salt 必须在**加密之前**就判掉。放它过去的话，就只剩"退回明文提交"一条路了——
        // 而站点自己的 JS 恰好就是那么干的（见 ChuPasswordCrypto 的说明），我们不跟。
        if (form.salt.isBlank()) {
            DebugLog.w("CHU 登录：登录页没有 pwdEncryptSalt → 站点会退回明文，本端拒绝")
            return@withContext ChuAuthResult.NoSalt
        }

        // ---- ③ 先问验证码 ----
        // 这一问是**零成本**的 GET（不含任何凭据），也是判断"账号是否处于风控状态"的正规办法。
        when (val need = needCaptcha(user)) {
            true -> {
                DebugLog.w("CHU 登录：checkNeedCaptcha = true → 转人工（滑块验证码程序过不去）")
                return@withContext ChuAuthResult.CaptchaRequired
            }
            null -> DebugLog.w("CHU 登录：checkNeedCaptcha 没问出来，继续尝试")
            false -> Unit
        }

        // ---- ④ 两处隐蔽必需项 ----
        warmUp(loginUrl)

        // ---- ⑤ 加密。放在预算之前：这一步失败不该消耗口令机会 ----
        val cipher = try {
            ChuPasswordCrypto.encrypt(password, form.salt)
        } catch (e: IllegalArgumentException) {
            return@withContext ChuAuthResult.Failed("口令加密失败：${e.message}")
        }

        // ---- ⑥ 本地预算：在**真正发 POST 之前**查 ----
        when (val v = budget.tryAcquire()) {
            is ChuLoginBudget.Verdict.Ok -> Unit
            is ChuLoginBudget.Verdict.TooSoon ->
                return@withContext ChuAuthResult.Throttled(v.retryAfterSeconds, exhausted = false)
            is ChuLoginBudget.Verdict.Exhausted ->
                return@withContext ChuAuthResult.Throttled(v.retryAfterSeconds, exhausted = true)
        }

        // ---- ⑦ 提交 ----
        val body = ChuLoginParser.buildLoginBody(user, cipher, form.execution)
        val post = Request.Builder()
            // ★ 必须打**带 `?service=` 的**那个地址，而不是裸的 [CHU_CAS_LOGIN_URL]。
            //
            // 页面上的静态 `<form … action="/authserver/login">` **不带** query，照着它抄就会
            // 漏掉 service —— 而页面自己的 JS 在运行时把 query 拼了回去（`login.js`：
            // `utils.setUrlParam("pwdFromId", "?service", encodeURIComponent(service))`），
            // 所以浏览器实际提交的是 `/authserver/login?service=…`。**静态 HTML 在这里是骗人的。**
            //
            // 这个漏项的失败形态极具迷惑性：**错口令仍是干净的 401，对口令却回 500（正文还是登录页）**
            // ——因为流程要到"口令已通过、准备按 service 构造回跳"那一步才用到它。
            // 本项目曾把这条 500 归因成"认证中心只支持 HTTP/2"，是错的：
            // 实测该域根本不支持 ALPN（`openssl s_client -alpn h2` → No ALPN negotiated），
            // 压根不可能协商出 h2，而 Chromium 走 HTTP/1.1 照样登录成功。
            .url(loginUrl)
            .header("User-Agent", CHU_UA)
            .header("Accept", ACCEPT_HTML)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Content-Type", "application/x-www-form-urlencoded")
            // Referer 用登录页地址（浏览器就是这个值），Origin 与之一致
            .header("Referer", loginUrl)
            .header("Origin", CHU_CAS_ORIGIN)
            .post(body.toRequestBody(FORM))
            .build()

        val reply = when (val n = send(cas, post)) {
            is Net.Err -> return@withContext handshakeOrNetworkFailure(n.message)
            is Net.Ok -> n.reply
        }

        DebugLog.i(
            "CHU 登录 POST → HTTP ${reply.code}，协议 ${reply.protocol}，" +
                "body ${reply.body.length} 字节"
        )

        if (reply.code >= 500) {
            // ★ 服务端 5xx 时会回一段**自带说明**的正文（实测约 1.6 KB），里面通常直接写着
            //   异常类型与 message。这条链上的 500 客户端完全看不见原因（只拿到一个状态码），
            //   把正文抄进日志是唯一能自己查下去的路——否则只剩猜。
            //   截断 + 压平空白：够看清异常，又不至于刷屏。
            DebugLog.w("CHU 登录 5xx 正文：" + reply.body.take(400).replace(Regex("\\s+"), " "))
        }

        when (val outcome = ChuLoginParser.classifyLoginPost(reply.code, reply.location, reply.body)) {
            is ChuLoginParser.PostOutcome.Redirect ->
                followTicket(outcome.location, outcome.ticket, reply.protocol)

            ChuLoginParser.PostOutcome.BadCredentials -> {
                DebugLog.w("CHU 登录：口令不对（HTTP ${reply.code}）")
                ChuAuthResult.BadCredentials
            }

            ChuLoginParser.PostOutcome.FlowExpired -> {
                DebugLog.w("CHU 登录：流程键已失效（execution 是一次性的）")
                ChuAuthResult.FlowExpired
            }

            ChuLoginParser.PostOutcome.KickedBackToForm -> {
                // 200 却又是登录页：salt 与 JSESSIONID 不是同一个会话（技能 §1.3）。
                // 服务端不报错，静默重发页面——最容易被误判成"登录失败"的一种。
                // 重试代价低（重新取一份 salt 即可），但**必须换一次新的会话**才可能对，
                // 所以交给调用方决定，这里如实报出去。
                DebugLog.w("CHU 登录：HTTP 200 却回登录页 → salt 与 JSESSIONID 疑似错配")
                ChuAuthResult.Failed("登录被服务端退回表单页（会话与 salt 不配对），请稍后重试一次")
            }

            ChuLoginParser.PostOutcome.ServiceRejected -> {
                // 换票被拒（`ticket=Unauthorized Service`）。这个失败**与登录态无关**：
                // 是拿出去的 service 串跟 CAS 注册值对不上（差一个字符，或把 http 写成 https）。
                // 所以它绝不能报成"密码错"——那会让人一直去改密码，改到天荒地老也不会好。
                DebugLog.w("CHU 登录：换票被拒（Unauthorized Service）→ 与注册值不符")
                ChuAuthResult.Failed(
                    "换取票据时被认证中心拒绝（Unauthorized Service）：" +
                        "客户端使用的 service 是 $CHU_CAS_SERVICE，与服务器注册值不一致。" +
                        "这是配置问题，重登无效。"
                )
            }

            // 5xx 且**正文又是登录页** = 服务端把这次提交退回了表单，不是"认证中心崩了"。
            // 唯一可靠的判据就是正文——本项目曾经只看状态码，于是把这条 500 误判成"协议问题"，
            // 还照那个错结论写了协议限制，最后崩在用户点登录那一刻（见类注释的 ★）。
            // 已知成因是"提交地址/参数与流程不匹配"（URL 上漏掉 service、execution 已过期），
            // 而这些只在"口令已通过"之后才会被用到，所以症状是"错口令 401、对口令 500"。
            ChuLoginParser.PostOutcome.ServerError -> ChuAuthResult.Failed(
                if (ChuLoginParser.looksLikeLoginPage(reply.body)) {
                    "CAS 回了 HTTP ${reply.code}，且正文又是登录页 —— 服务端把这次提交退回了表单，" +
                        "与网络和协议都无关。多半是提交地址或参数与流程不匹配" +
                        "（URL 上的 service、已过期的 execution）。"
                } else {
                    "CAS 回了 HTTP ${reply.code}（协议 ${reply.protocol}），正文也不是登录页，" +
                        "属服务端侧异常。稍后重试一次；若持续出现请反馈。"
                }
            )

            is ChuLoginParser.PostOutcome.Unexpected ->
                ChuAuthResult.Failed("登录响应无法归类（HTTP ${outcome.code}）")
        }
    }

    /**
     * 跟票：把 CAS 下发的带票地址走完，让 `bkjw.chd.edu.cn` 把会话建起来。
     *
     * 必须**跟随重定向**（用 [eams] 而不是 [cas]）：这一跳自己就带 302，
     * 而会话 cookie 可能落在链的中间某一跳，停在第一个响应会把它漏掉。
     *
     * ## 为什么"跟到了 eams"要单独判一次
     *
     * 拿到票 ≠ 建好会话。票可能因为 `service` 与注册值差一个字符而被判给别处
     * （那种情况下落点会是 CAS 的错误页），也可能业务域反手把人弹回登录页。
     * 判据只有一个：**落点不是登录页**。跟票成功与否，只有这个能证明。
     */
    private fun followTicket(location: String, ticket: String, protocol: String): ChuAuthResult {
        val landed = when (val n = send(eams, get(location, accept = ACCEPT_HTML))) {
            is Net.Err -> {
                DebugLog.w("CHU 登录：跟票失败 ${n.message}")
                return ChuAuthResult.Success(protocol, eamsSession = false)
            }
            is Net.Ok -> n.reply
        }
        val onEams = landed.code == 200 && !ChuLoginParser.looksLikeLoginPage(landed.body)
        DebugLog.i(
            "CHU 登录：跟票落点 HTTP ${landed.code}，" +
                "落到 eams=${onEams}（票 ${ticket.take(12)}…）"
        )
        if (onEams) {
            // 会话落盘。进程若在此刻被回收，重进时还能用它——也是唯一一个值得 flush 的时机。
            runCatching { CookieManager.getInstance().flush() }
        }
        return ChuAuthResult.Success(protocol, eamsSession = onEams)
    }

    // ---------------------------------------------------------------- 零成本探测

    /** 账号在认证侧的当前状态。两个字段都可能为 null（"没问出来"）。 */
    data class AccountState(
        /**
         * 站点这次是否要求验证码。true 时**不要提交口令**：
         * 它是滑块验证码，程序过不去，而且提交会把失败计数推高一格。
         */
        val needCaptcha: Boolean?,
        /** 站点声明的剩余口令机会。0 表示已强制验证码 */
        val badCredentialsLeft: Int?,
    )

    /**
     * 探测账号状态。**全程 GET、不含任何凭据、不消耗口令机会**。
     *
     * 这是官方登录页自己就会做的两件事（`checkNeedCaptcha()`），我们只是照做并读出结果。
     * 用途：在真正提交之前给用户一句实话（"学校这次要验证码，请走网页"），
     * 以及事后判断账号是不是已经被风控缠上。
     */
    suspend fun probeAccountState(username: String): AccountState = withContext(Dispatchers.IO) {
        val user = username.trim()
        val loginUrl = "$CHU_CAS_LOGIN_URL?service=${ChuLoginParser.formEncode(CHU_CAS_SERVICE)}"
        val page = (send(cas, get(loginUrl, accept = ACCEPT_HTML)) as? Net.Ok)?.reply
        AccountState(
            needCaptcha = if (user.isEmpty()) null else needCaptcha(user),
            badCredentialsLeft = page?.body?.let { ChuLoginParser.parseBadCredentialsLeft(it) },
        )
    }

    /**
     * 退出登录：清掉两个域下的会话 cookie。
     *
     * ## 为什么不直接 `removeAllCookies()`
     *
     * 那是**全进程**的，会连别的域（例如 WebView 里访问过的第三方站点）一起抹掉，
     * 越权得离谱。所以按「域 + 路径」逐条置过期。
     *
     * 路径必须写**cookie 自己的**：`CookieManager.setCookie(url, "名=; Path=…")` 里
     * (域, 路径) 与原 cookie 不完全一致就静默失败——日志上"已清"打得出来，实际一个没删。
     * 这一条踩过，代价是「退出登录后居然还能拉到课表」。
     *
     * `LOCALE` 刻意不清：它是语言偏好，不是凭证，留着反而少一次"界面忽然变英文"。
     */
    fun signOut() {
        val cm = runCatching { CookieManager.getInstance() }.getOrNull() ?: return

        var attempted = 0
        CHU_COOKIE_SITES.forEach { (url, paths) ->
            paths.forEach { path ->
                CHU_SESSION_COOKIES.forEach { name ->
                    runCatching {
                        cm.setCookie(
                            url,
                            "$name=; Path=$path; " +
                                "Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0",
                        )
                    }
                    attempted++
                }
            }
        }
        runCatching { cm.flush() }

        // 自查：删不掉才是常态（路径不匹配即静默失败），所以回读一遍。
        val left = runCatching { cm.getCookie("$CHU_CAS_BASE/login") }.getOrNull()
            .orEmpty()
            .split(';')
            .map { it.substringBefore('=').trim() }
            .filter { it.isNotEmpty() && it in CHU_SESSION_COOKIES }
        DebugLog.i(
            "CHU 退出登录：尝试清 $attempted 条；认证域残留=" +
                if (left.isEmpty()) "无" else left.joinToString(",")
        )
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 两处"隐蔽必需项"（技能 §3 标了 ★）。都是 GET、都不含凭据，
     * 失败也不中止流程——它们只影响紧接着那次 POST 的成败，而真正的判据始终是 POST 的结果。
     */
    private fun warmUp(referer: String) {
        send(cas, get("$CHU_CAS_BASE/tenant/info", referer = referer, accept = ACCEPT_ANY))
        send(
            cas,
            get(
                "$CHU_CAS_BASE/bfp/info?bfp=${ChuPasswordCrypto.randomFingerprint()}",
                referer = referer,
                accept = ACCEPT_ANY,
            ),
        )
    }

    /** 需要验证码则 true；问不出来返回 null（调用方按"不知道"继续，不要当成 false）。 */
    private fun needCaptcha(username: String): Boolean? {
        val url = "$CHU_CAS_BASE/checkNeedCaptcha.htl?username=${ChuLoginParser.formEncode(username)}"
        val reply = (send(cas, get(url, accept = ACCEPT_ANY)) as? Net.Ok)?.reply ?: return null
        return ChuLoginParser.parseNeedCaptcha(reply.body)
    }

    private fun get(url: String, referer: String? = null, accept: String = ACCEPT_ANY): Request =
        Request.Builder()
            .url(url)
            .header("User-Agent", CHU_UA)
            .header("Accept", accept)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .apply { referer?.let { header("Referer", it) } }
            .get()
            .build()

    /**
     * 一跳响应。只留这几个字段，并且**立刻**把 body 读完关闭。
     *
     * 读+关不是讲究：OkHttp 是在响应体被消费或关闭时**才**把 Set-Cookie 交给 [CookieJar] 的。
     * 拿到 `Response` 不消费就返回，服务端刚种下的 `JSESSIONID` / `route` 就永远进不了罐子，
     * 表现为"每一跳都是 200，下一步却说没有会话"。
     */
    private data class Reply(
        val code: Int,
        val location: String?,
        val body: String,
        /** 实际协商出的协议，`h2` 或 `http/1.1`。CHU 的成败与它直接相关 */
        val protocol: String,
    )

    private sealed interface Net {
        data class Ok(val reply: Reply) : Net
        data class Err(val message: String) : Net
    }

    private fun send(client: OkHttpClient, req: Request): Net = try {
        client.newCall(req).execute().use { r ->
            Net.Ok(
                Reply(
                    code = r.code,
                    location = r.header("Location"),
                    body = runCatching { r.body?.string().orEmpty() }.getOrDefault(""),
                    protocol = r.protocol.toString(),
                )
            )
        }
    } catch (e: Exception) {
        DebugLog.w("CHU 请求异常 ${req.url.host}${req.url.encodedPath}：${e.message}")
        Net.Err(e.message ?: (e::class.simpleName ?: "未知网络异常"))
    }

    /**
     * 把"发不出去"翻译成一句能指向下一步的话。
     *
     * 因为 [cas] 被限定成只许 h2，握手失败会以异常形式出现（而不是那个误导人的 500），
     * 所以这里**正是**最可能出现的地方之一——值得单独说清楚，否则这条护栏就白设了。
     */
    private fun handshakeOrNetworkFailure(message: String): ChuAuthResult {
        val lower = message.lowercase()
        val looksLikeProtocolIssue =
            lower.contains("protocol") || lower.contains("alpn") ||
                lower.contains("ssl") || lower.contains("unexpected")
        return if (looksLikeProtocolIssue) {
            ChuAuthResult.Failed(
                "与认证中心的连接没能建立：$message\n\n" +
                    "本项目把认证链限定为 HTTP/2——CHU 的口令校验在 HTTP/1.1 下会回 500。" +
                    "若你看到这条，说明协商 h2 就没成功（网络中间设备？），" +
                    "而不是密码不对。可改用网页登录兜底。"
            )
        } else {
            ChuAuthResult.Failed("认证中心连不上：$message")
        }
    }

    private companion object {
        /**
         * (探测用 URL, 该域下 cookie 可能落在的路径)。
         *
         * 探测 URL 必须选**该域下真实存在**的地址，因为 `CookieManager.getCookie()`
         * 是按路径匹配的：拿域名根去问业务域，会返回空，从而误导排查。
         */
        val CHU_COOKIE_SITES: List<Pair<String, List<String>>> = listOf(
            // 认证域（https）。JSESSIONID / route / CASTGC 的 path 都是 /authserver
            "$CHU_CAS_BASE/login" to listOf("/authserver", "/"),
            // 业务域（http）。JSESSIONID 的 path 是 /eams
            "$CHU_EAMS_BASE/eams/" to listOf("/eams", "/"),
        )

        /**
         * 需要清掉的会话 cookie 名。
         *
         * 实测清单（见 `chd_state.json`）：
         * `ids.chd.edu.cn` → JSESSIONID / route / CASTGC / MULTIFACTOR_BROWSER_FINGERPRINT / happyVoyage；
         * `bkjw.chd.edu.cn` → JSESSIONID / SERVERNAME。
         *
         * 其中 `route` 只是负载均衡节点选择器、对成败无影响（技能 §3.2），
         * 但留着会让下一次登录被甩到"上次那台"，故一并清。
         * `happyVoyage` 是不参与登录的装饰 cookie，清掉无害而归一。
         */
        val CHU_SESSION_COOKIES = listOf(
            "JSESSIONID",
            "route",
            "CASTGC",
            "MULTIFACTOR_BROWSER_FINGERPRINT",
            "happyVoyage",
            "SERVERNAME",
        )
    }
}
