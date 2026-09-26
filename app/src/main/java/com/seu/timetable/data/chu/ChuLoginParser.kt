package com.seu.timetable.data.chu

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * CAS 登录页与登录响应的纯文本解析。
 *
 * ## 为什么单独成类
 *
 * 这里全是「拿一段文本、还一个结论」的无副作用逻辑，而登录这条链**最不该拿去打真实账号来验证**
 * ——上一轮就是这么把账号打冻结的（见 [com.seu.timetable.data.chu.ChuLoginBudget] 的说明）。
 * 所以判定必须能在离线夹具上逐条钉死：夹具是 `app/src/test/resources/chu_login_page.html`，
 * 2026-09 从 `ids.chd.edu.cn` 实抓。它是登录**前**的页面，本就不含任何凭据，无需脱敏。
 */
internal object ChuLoginParser {

    /**
     * 登录页里「用户名密码登录」那个表单的两个隐藏字段。
     *
     * @param execution 流程键。**一次性**：被消费后再用，服务端会回
     *   `A problem occurred restoring the flow execution with key ...`。
     * @param salt `pwdEncryptSalt`。**必须与本次的 JSESSIONID 配对**（技能 §1.3）。
     */
    data class LoginForm(val execution: String, val salt: String)

    /**
     * 取出用户名密码表单的字段。
     *
     * ## ★ 页面上有 **4 个** 表单各带一个 `name="execution"`
     *
     * 分别是 fido / dynamicLogin / userNameLogin / qrLogin，实测它们此刻的值**恰好都是 `e1s1`**。
     * 也就是说"随手抓第一个 execution"在这份夹具上碰巧也能对——但那是运气，不是逻辑。
     * 一旦某个表单的流程键被单独刷新，抓错就会让登录莫名其妙地失败，且现象无法与别的原因区分。
     * 所以这里先定位 `value="userNameLogin"` 那个表单（也就是真正要 POST 的那个），
     * 再**只在它的作用域内**取字段。
     *
     * @return 字段不全（例如页面结构变了）返回 null，由调用方如实报错，不要瞎猜
     */
    fun parseLoginForm(html: String): LoginForm? {
        val marker = html.indexOf(USERNAME_LOGIN_VALUE)
        if (marker < 0) return null

        // 以 marker 为锚点，往前找最近的 <form、往后找最近的 </form>，取这个区间当作用域
        val open = html.lastIndexOf(FORM_OPEN, marker)
        val close = html.indexOf(FORM_CLOSE, marker)
        val block = html.substring(
            if (open < 0) 0 else open,
            if (close < 0) html.length else close,
        )

        val execution = EXECUTION.find(block)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            ?: return null
        // salt 可能整段缺失：那种情况下不能返回 null（表单本身是好的），
        // 交给 [ChuAuthClient] 判成 NoSalt —— 它必须报错，而不是退回明文提交。
        val salt = SALT.find(block)?.groupValues?.get(1).orEmpty()

        return LoginForm(execution = execution, salt = salt)
    }

    /**
     * 站点自己声明「还剩几次口令机会」：页面里是 `var _badCredentialsCount = "5";`。
     *
     * 归零后页面的 `checkNeedCaptcha()` 会直接 `reloadCaptcha(true)`，即强制出验证码
     * （而 `captchaSwitch == "2"` 时是**滑块**验证码，程序完全过不去）。
     * 所以这个数是我们动手前唯一能拿到的"服务端还剩多少耐心"。
     *
     * 拿不到返回 null（页面结构变了）——调用方按"不知道"处理，**不要当成 0**，
     * 否则一次页面改版就会让自动登录永久失效。
     */
    fun parseBadCredentialsLeft(html: String): Int? =
        BAD_CREDENTIALS.find(html)?.groupValues?.get(1)?.trim()?.toIntOrNull()

    /**
     * `checkNeedCaptcha.htl` 的响应，形如 `{"isNeed":false}`。
     *
     * 不引 kotlinx.serialization：为一个单布尔字段的响应建一个 `@Serializable` 类，
     * 净多出一处要跟着服务端走的东西。认不出返回 null，调用方按"不知道"继续。
     */
    fun parseNeedCaptcha(body: String): Boolean? =
        IS_NEED.find(body)?.groupValues?.get(1)?.equals("true", ignoreCase = true)

    /**
     * 登录 POST 的 body。
     *
     * 字段集是**精确的**，多一个少一个都不行：
     *
     *  - 可见的密码框叫 `passwordText`，而**加密值进的是 `password`**。浏览器里两者不冲突，
     *    是因为提交前 JS 给 `passwordText` 加了 `disabled`（见 `login.js` 的 `checkForm`），
     *    被禁用的控件根本不参与提交。我们手工拼 body，就别去模仿那个"先填再禁用"的两步动作。
     *  - **不要**带 `rememberMe`：未勾选时浏览器就不发它。凭空加上只是多给服务端一个要判的字段
     *    （技能 §3.1 记：多塞字段会拿到 Spring 的 JSON 500）。
     *  - **不要**带 `passwordText`，理由同上。
     *
     * ## ★ 密文必须做表单转义
     *
     * 密文是标准 Base64，含 `+` `/` `=`。其中 **`+` 在 `application/x-www-form-urlencoded`
     * 里代表空格** —— 不转义就会在服务端解出别的字节，于是"密码明明是对的却登不上"，
     * 而且失败现象与真的密码错一模一样。这条不踩一次是不会想到的。
     */
    fun buildLoginBody(username: String, cipher: String, execution: String): String =
        listOf(
            "username" to username,
            "password" to cipher,
            "captcha" to "",
            "_eventId" to "submit",
            "cllt" to "userNameLogin",
            "dllt" to "generalLogin",
            "lt" to "",
            "execution" to execution,
        ).joinToString("&") { (k, v) -> "$k=${formEncode(v)}" }

    /**
     * 从回跳地址里取票据。判据是**真的有值**：`ticket=Unauthorized Service` 那种
     * "名字在、值也在、但内容是错误文案"的不算（踩过，代价是一整轮排查）。
     *
     * 失败返回 null；调用方**不能**把 null 当成功——一次判错就会让整条链在后续被静默拒绝，
     * 现象看起来像网络问题。
     */
    fun ticketFromLocation(location: String?): String? {
        if (location.isNullOrBlank()) return null
        val raw = TICKET_PARAM.find(location)?.groupValues?.get(1) ?: return null
        val value = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        return value.takeIf { it.isNotBlank() && !value.startsWith("Unauthorized") }
    }

    /**
     * 这段正文是不是"登录页本身"。
     *
     * 用两个**几乎不会出现在别处**的特征串，而不是泛泛的 `contains("login")`：
     * 隐藏字段的 id `pwdEncryptSalt`，以及表单的 id `pwdFromId`。
     * 课表页、错误页里都不会有这两个词，所以判错的概率极低。
     */
    fun looksLikeLoginPage(body: String): Boolean =
        body.contains("pwdEncryptSalt") || body.contains("pwdFromId")

    /**
     * 换票被拒：回跳地址里写着 `ticket=Unauthorized Service`。
     *
     * 它的成因**不是**登录态，而是 `service` 字符串与 CAS 注册值对不上（差一个字符、
     * 或者把 http 写成了 https）。所以它既不能算成功，也不能算"口令错"——
     * 后者会让人去改密码，改到天荒地老也不会好。
     */
    fun isUnauthorizedService(text: String?): Boolean =
        text?.contains("ticket=Unauthorized") == true

    /**
     * 流程键是否已失效（`execution` 被消费过一次）。
     *
     * 服务端把话说在 **Location 的 query 里**（`?exception.message=...`），不在正文里，
     * 所以两个地方都要看。
     */
    fun isFlowExpired(text: String?): Boolean =
        text != null && (
            text.contains("restoring the flow execution") ||
                text.contains("exception.message") ||
                text.contains("FlowExecutionException")
            )

    /**
     * 一次登录 POST 该判成什么。
     *
     * ## 为什么判定要单独抽出来
     *
     * CHU 的 CAS **失败返回不区分病因**：错密码 401、流程键失效 302、session 错配 200、
     * HTTP/1.1 时 500。上一轮为了二分这几种情况反复打真接口，把账号打进了风控。
     * 抽成纯函数之后，这套判定可以在夹具上穷举，不再需要真账号陪跑。
     *
     * ## 判定顺序不能换
     *
     * 先认"带票的 302"（唯一的成功判据），再认流程键失效——因为流程失效**也是 302**，
     * 若先按"302 回登录页 = 密码错"来判，会把一个"重取流程键就能成功"的情况报成密码错，
     * 用户改半天密码也没用。
     */
    fun classifyLoginPost(code: Int, location: String?, body: String): PostOutcome {
        val ticket = ticketFromLocation(location)
        return when {
            code in 300..399 && ticket != null -> PostOutcome.Redirect(location!!, ticket)

            isFlowExpired(location) || isFlowExpired(body) -> PostOutcome.FlowExpired

            // service 串与注册值对不上。放在 401 与"302 回登录页"之前，
            // 因为它俩的解释（改密码 / 重登）对这种情况全都不成立。
            isUnauthorizedService(location) -> PostOutcome.ServiceRejected

            code == 401 -> PostOutcome.BadCredentials

            // 200 却把登录页又发一遍：salt 与提交时的 JSESSIONID 不是同一个会话（技能 §1.3）。
            // 服务端不报错、不 500，静默重发页面 —— 最容易被误判成"登录失败"的一种。
            code == 200 && looksLikeLoginPage(body) -> PostOutcome.KickedBackToForm

            // 300 段里既没票也不是流程失效，落回登录页：老版本 CAS 判口令不对的另一种表现形式
            code in 300..399 -> PostOutcome.BadCredentials

            code >= 500 -> PostOutcome.ServerError

            else -> PostOutcome.Unexpected(code)
        }
    }

    /** form-urlencoded 转义。密文里那个 `+` 就靠它保命，见 [buildLoginBody]。 */
    fun formEncode(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** [classifyLoginPost] 的结论。**多分支而非布尔**：每种失败要指引用户做不同的事。 */
    sealed interface PostOutcome {

        /** 302 且确实带了票。[location] 就是要跟过去的带票地址 */
        data class Redirect(val location: String, val ticket: String) : PostOutcome

        /** 口令不对。**不要重试**，请用户核对——继续试只会把账号推向验证码 */
        data object BadCredentials : PostOutcome

        /**
         * 流程键失效。此时重新 GET 登录页、拿新 `execution` 再提交是**安全**的重试：
         * 这一步失败发生在口令被判定之前，不额外消耗"口令机会"。
         */
        data object FlowExpired : PostOutcome

        /** 200 却回登录页：salt 与 JSESSIONID 错配。重新取一份 salt 即可 */
        data object KickedBackToForm : PostOutcome

        /**
         * 换票被拒（`ticket=Unauthorized Service`）：`service` 串与 CAS 注册值对不上。
         * **不是登录态问题**，重登无用，要去查 service 的拼写与编码。
         */
        data object ServiceRejected : PostOutcome

        /** 5xx。CHU 侧最典型的成因是客户端走了 HTTP/1.1，见技能 §1.1 */
        data object ServerError : PostOutcome

        data class Unexpected(val code: Int) : PostOutcome
    }

    // ---------------------------------------------------------------- 内部

    private const val FORM_OPEN = "<form"
    private const val FORM_CLOSE = "</form>"
    private const val USERNAME_LOGIN_VALUE = "value=\"userNameLogin\""

    /**
     * `execution` 的两种书写顺序都要认：用户名密码表单里是
     * `<input id="execution" name="execution" value="e1s1">`，二维码表单却是
     * `<input name="execution" value="e1s1">`。故匹配 "execution" 之后任意属性再取 value。
     */
    private val EXECUTION = Regex("\"execution\"[^>]*?value=\"([^\"]*)\"")

    /**
     * ★ `pwdEncryptSalt` 这个 input **没有 `name` 属性**，只有 `id`：
     * `<input type="hidden" id="pwdEncryptSalt" value="…" />`。
     *
     * 按惯常写法去找 `name="pwdEncryptSalt"` 会一无所获；而一旦照着站点 JS 的容错走，
     * 下一步就是**明文提交**。这是它最阴的地方——所以这里注释写得比代码长。
     */
    private val SALT = Regex("pwdEncryptSalt\"[^>]*value=\"([^\"]*)\"")

    private val BAD_CREDENTIALS = Regex("_badCredentialsCount\\s*=\\s*\"([^\"]*)\"")

    /** 大小写不敏感：JSON 里本该是小写，但没理由让一个 `TRUE` 把探测整条作废。 */
    private val IS_NEED = Regex("\"isNeed\"\\s*:\\s*(true|false)", RegexOption.IGNORE_CASE)

    private val TICKET_PARAM = Regex("[?&]ticket=([^&]*)")

    /**
     * 本对象全部正则的原始串，**只给单测做语法自检**（见 `RegexIcuStrictnessTest`）。
     *
     * 存在的理由：JVM 与 Android ICU 对正则的严格度不同——孤立的 `}` 在 JVM 里是字面量、
     * 在 ICU 里是语法错误。这道链上任何一条正则写错，死的都是**登录**。
     */
    internal val selfCheckPatterns: List<String>
        get() = listOf(
            EXECUTION.pattern,
            SALT.pattern,
            BAD_CREDENTIALS.pattern,
            IS_NEED.pattern,
            TICKET_PARAM.pattern,
        )
}
