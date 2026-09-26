package com.seu.timetable.data.chu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 登录链的离线护栏。
 *
 * ## 为什么这个文件的分量特别重
 *
 * 上一轮为了搞清"密码正确却回 500"，拿真实账号反复打登录接口，十几分钟内十几次失败提交，
 * 账号被风控冻结。事后看，那些结论**本来全都可以离线验证**——它们要么是页面上的静态字段，
 * 要么是纯字符串/数学问题。这份测试就是把这笔账补上：凡是能不带凭据验证的，一律不留到线上。
 *
 * 于是这里的每一条都对应一个"只靠真账号才能发现"的坑：
 *  - 页面上有 **4 个** `execution`（多表单），抓错就登录失败；
 *  - `pwdEncryptSalt` **没有 `name` 属性**，按惯例找 `name=` 会一无所获 → 然后退回明文；
 *  - 密文是 Base64，那个 `+` 在表单编码里代表**空格**，不转义就"密码对却登不上"；
 *  - `service` 必须逐字符等于站点注册值，差一个字符就 `ticket=Unauthorized Service`；
 *  - 服务端只给 **5** 次口令机会（页面自己写着），此前根本没人读它。
 *
 * 夹具：`chu_login_page.html` —— 2026-09 从 `ids.chd.edu.cn` **实抓**的登录页（27 KB）。
 * 它是登录**前**的页面，本就不含任何凭据，因此无需脱敏。
 */
class ChuAuthClientTest {

    private fun resource(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!
            .readBytes().toString(Charsets.UTF_8)

    private fun loginPage() = resource("chu_login_page.html")

    /** 真实登录页里的 salt（同一会话内稳定，换会话就变，属临时值） */
    private val realSalt = "KMwu9C6Dl4FaMSXw"

    // ================================================================ 口令加密

    /**
     * 密文长度是加密实现对不对的**第一道、也是最便宜的一道**判据。
     *
     * 13 字符口令 → 明文 64+13=77 字节 → PKCS7 补齐到 80 → Base64 得 108 字符。
     * 站点自己的 `encrypt.js`（Node 跑）产出的正是 108，两边对上了才说明
     * key/iv/padding/前缀这四件事**全都**对。
     *
     * ★ 口令用**等长占位串**：这里曾写着当时的真实口令，而测试文件是要进公开仓库的。
     * 长度是这条断言唯一依赖的性质，换成占位串不影响它证明的东西。
     */
    @Test
    fun `13 字符口令的密文长度是 108`() {
        val cipher = ChuPasswordCrypto.encrypt("dummy-pwd-13c", realSalt)
        assertEquals(108, cipher.length)
    }

    @Test
    fun `密文随口令长度整块增长`() {
        // PKCS7 只补到 16 的整数倍，故长度不是线性的（这也是"只对长度"能证伪实现的原因）：
        //   13 字符 → 明文 77 → 补到 80 → Base64 108
        //   29 字符 → 明文 93 → 补到 96 → Base64 128
        assertEquals(108, ChuPasswordCrypto.encrypt("a".repeat(13), realSalt).length)
        assertEquals(128, ChuPasswordCrypto.encrypt("a".repeat(29), realSalt).length)
    }

    /**
     * 解密回来必须恰好是「前缀 + 口令」。
     *
     * 这条比长度检查强得多：它证明 key 就是 salt 的 UTF-8 字节、就是 AES-128、
     * 就是 CBC、就是 PKCS7，而且那 64 个字符确确实实在口令**前面**（顺序错了服务端就剥不掉）。
     * 解出来多一个空格、少一个字节，这里都红。
     */
    @Test
    fun `解回来是前缀加口令`() {
        val password = "p@ss word+中文"
        val prefix = ChuPasswordCrypto.randomString(ChuPasswordCrypto.PREFIX_LEN)
        val iv = ChuPasswordCrypto.randomString(ChuPasswordCrypto.IV_LEN)

        val cipher = ChuPasswordCrypto.encrypt(password, realSalt, prefix, iv)
        val plain = decrypt(cipher, realSalt, iv)

        assertEquals(prefix + password, plain)
        assertEquals(64, prefix.length)
    }

    /** IV 与随机前缀都要**每次不同**——它们是这套方案里唯一的随机性来源。 */
    @Test
    fun `每次加密都不同`() {
        val a = ChuPasswordCrypto.encrypt("same-password", realSalt)
        val b = ChuPasswordCrypto.encrypt("same-password", realSalt)
        assertNotEquals(
            "两次密文相同说明随机前缀/IV 没起作用",
            a, b,
        )
    }

    /** 换 salt 就是换密钥，密文必须变——否则说明我们没把 salt 当 key 用。 */
    @Test
    fun `换 salt 密文就变`() {
        val a = ChuPasswordCrypto.encrypt("same-password", realSalt)
        val b = ChuPasswordCrypto.encrypt("same-password", "AAAAAAAAAAAAAAAA")
        assertNotEquals(a, b)
    }

    /**
     * ★ salt 为空时必须**抛异常**，绝不能像站点 JS 那样退回明文。
     *
     * 站点自己的 `encryptPassword` 是 try/catch 后 `return n`，而 `encryptAES` 在 salt
     * 为假值时也直接返回明文。也就是说 salt 一丢，它会安安静静地把口令明文发出去——
     * 而"发的是明文"这件事，在成功/失败的日志上完全看不出来。
     * 这里把这个静默降级钉死成异常，任何人想"兼容一下"都会被这条测试拦住。
     */
    @Test
    fun `salt 为空要报错而不是发明文`() {
        assertThrows(IllegalArgumentException::class.java) {
            ChuPasswordCrypto.encrypt("secret", "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChuPasswordCrypto.encrypt("secret", "   ")
        }
    }

    @Test
    fun `salt 长度不对要报错`() {
        assertThrows(IllegalArgumentException::class.java) {
            ChuPasswordCrypto.encrypt("secret", "tooshort")
        }
    }

    @Test
    fun `随机串长度与字符集都与站点一致`() {
        val s = ChuPasswordCrypto.randomString(64)
        assertEquals(64, s.length)
        // 站点字符集刻意排除了易混字符，我们保持同形
        assertFalse("不该出现站点字符集之外的字符", s.any { it !in AES_CHARS })
        assertFalse("站点字符集本身就不含 0/1/9", "019".any { it in AES_CHARS })
    }

    /**
     * 设备指纹只要求"32 位、十六进制形态"——服务端**只存不验内容**（技能 §3.2）。
     * 但形态必须对：大写十六进制才与站点 `md5(...).toUpperCase()` 的产出同形。
     */
    @Test
    fun `设备指纹是 32 位大写十六进制`() {
        val fp = ChuPasswordCrypto.randomFingerprint()
        assertEquals(32, fp.length)
        assertTrue("应为大写十六进制：$fp", Regex("^[0-9A-F]{32}$").matches(fp))
        assertNotEquals(fp, ChuPasswordCrypto.randomFingerprint())
    }

    // ================================================================ 登录页解析

    @Test
    fun `从真实登录页取出 execution 与 salt`() {
        val form = ChuLoginParser.parseLoginForm(loginPage())
        assertEquals("e1s1", form?.execution)
        assertEquals(realSalt, form?.salt)
    }

    /**
     * ★ 多表单陷阱：页面上有 4 个 `name="execution"`，值此刻**恰好都一样**，
     * 所以"随手抓第一个"在这份夹具上碰巧能对。这条测试把这个"碰巧"拆穿——
     * 把最先出现的那一个（fido 表单）改成别的值，看我们是否还能取对。
     */
    @Test
    fun `页面上有四个 execution 必须认用户名密码那个`() {
        val page = loginPage()

        // 先确认陷阱真实存在
        assertEquals(
            "实测页面有 4 个 execution（fido/dynamicLogin/userNameLogin/qrLogin）",
            4,
            Regex("""name="execution"""").findAll(page).count(),
        )

        // 再把最先出现的那一个改掉。它就是"随手抓第一个"会抓到的那个。
        val fidoFirst = page.replaceFirst(
            """name="execution" value="e1s1"""",
            """name="execution" value="FIDOKEY"""",
        )
        assertNotEquals(page, fidoFirst)

        // 朴素写法确实会中招——这条断言是给"想简化成一行正则"的人看的
        val naive = Regex("""name="execution" value="([^"]*)"""").find(fidoFirst)
        assertEquals("FIDOKEY", naive?.groupValues?.get(1))

        // 而我们必须仍然取到 userNameLogin 表单里那个
        assertEquals("e1s1", ChuLoginParser.parseLoginForm(fidoFirst)?.execution)
    }

    /**
     * ★ `pwdEncryptSalt` 这个 input **只有 `id`、没有 `name`**。
     *
     * 这条断言是在拦住一种具体的"顺手修正"：看到页面里是 `pwdEncryptSalt` 就写
     * `name="pwdEncryptSalt"` 去找。那样会一无所获，然后（照着站点 JS 的容错走）
     * 退回明文提交。所以这里同时钉住"没有 name"这个事实。
     */
    @Test
    fun `pwdEncryptSalt 只有 id 没有 name`() {
        val page = loginPage()
        assertTrue("应有 id 形式的 salt 字段", page.contains("""id="pwdEncryptSalt""""))
        assertFalse("它没有 name 属性，按 name 去找必然一无所获", page.contains("""name="pwdEncryptSalt""""))
        assertEquals(realSalt, ChuLoginParser.parseLoginForm(page)?.salt)
    }

    @Test
    fun `站点声明的剩余口令机会是 5`() {
        // 这个数字是整个"次数预算"的立法依据，必须能从页面上读到
        assertEquals(5, ChuLoginParser.parseBadCredentialsLeft(loginPage()))
    }

    @Test
    fun `页面结构变了返回 null 而不是瞎猜`() {
        assertNull(ChuLoginParser.parseLoginForm("<html><body>没有表单</body></html>"))
        assertNull(ChuLoginParser.parseLoginForm(""))
        // 页面里只剩 salt、却没有 execution → 也当解析失败
        assertNull(ChuLoginParser.parseLoginForm("""<form><input id="pwdEncryptSalt" value="x"></form>"""))
        assertNull(ChuLoginParser.parseBadCredentialsLeft("没有这个变量"))
    }

    // ================================================================ service 串

    /**
     * ★ `service` 必须逐字符等于站点自己声明的那个值。
     *
     * 这条测试直接从**真实登录页**里把 `var service = [...]` 抠出来与我们代码里的常量比对，
     * 而不是把常量抄一遍。两个好处：改常量会红；站点哪天换注册值，也会红在这里，
     * 而不是等到线上换票变成 `ticket=Unauthorized Service`——
     * 那种失败看起来像网络不通，一个字符都看不出来。
     */
    @Test
    fun `service 与站点注册值逐字符一致`() {
        val declared = Regex("""var service = \["([^"]*)"]""")
            .find(loginPage())?.groupValues?.get(1)
            // 页面里是 JS 字面量，斜杠被转义成 \/
            ?.replace("\\/", "/")

        assertEquals("http://bkjw.chd.edu.cn/eams/home.action", declared)
        assertEquals(declared, CHU_CAS_SERVICE)
    }

    /**
     * service 塞进 query 时的编码，必须与**服务端自己下发的那个**一模一样。
     *
     * 基准不是我们拍的：实测 `GET http://bkjw.chd.edu.cn/eams/` 时服务端回的 302 就是
     *
     *     location: https://ids.chd.edu.cn/authserver/login?service=http%3A%2F%2Fbkjw.chd.edu.cn%2Feams%2Fhome.action
     *
     * 注意 `:` 与 `/` **都被编码了**。另一类 CAS 实现只转义 `?` 与 `&`，
     * 照搬那种写法反而会错——编码方式没有通用答案，只能对着服务端下发的原文抄。
     */
    @Test
    fun `service 的编码与服务端下发的一致`() {
        val encoded = ChuLoginParser.formEncode(CHU_CAS_SERVICE)
        assertEquals("http%3A%2F%2Fbkjw.chd.edu.cn%2Feams%2Fhome.action", encoded)
    }

    @Test
    fun `service 是 http 且指向 home_action`() {
        // 业务域没有 https，写成 https 会被 CAS 判成"发给别的服务"
        assertTrue(CHU_CAS_SERVICE.startsWith("http://"))
        assertFalse(CHU_CAS_SERVICE.startsWith("https://"))
        assertTrue(CHU_CAS_SERVICE.endsWith("/eams/home.action"))
    }

    // ================================================================ POST body

    /**
     * ★ 密文是 Base64，含 `+` `/` `=`；其中 **`+` 在表单编码里代表空格**。
     *
     * 不转义的话，服务端把 `+` 解成空格，口令就"错"了——而且失败现象与真的密码错
     * 一模一样，能查一整天。这里用一个含 `+` 的密文把这条钉死。
     */
    @Test
    fun `密文里的加号斜杠等号都必须转义`() {
        val cipher = "a+b/c=d+e"
        val body = ChuLoginParser.buildLoginBody("2026123456", cipher, "e1s1")

        // password 的取值里不能出现裸的 +（裸 = 号也会把 value 切断）
        val passwordValue = body.substringAfter("password=").substringBefore("&")
        assertFalse("裸加号会在服务端被解成空格：$passwordValue", passwordValue.contains('+'))
        assertTrue(passwordValue.contains("%2B"))
        assertTrue(passwordValue.contains("%2F"))
        assertTrue(passwordValue.contains("%3D"))

        // 反向确认：解回来还是原密文（说明只是转义，没有改内容）
        assertEquals(cipher, decodeFormValue(passwordValue))
    }

    /**
     * 字段集必须**精确**。少一个服务端认不出；多一个（尤其 `rememberMe` / `passwordText`）
     * 会得到 Spring 的 JSON 500（技能 §3.1）。
     */
    @Test
    fun `body 字段集精确 不多不少`() {
        val body = ChuLoginParser.buildLoginBody("2026123456", "CIPHER", "e1s1")
        val keys = body.split('&').map { it.substringBefore('=') }

        assertEquals(
            listOf("username", "password", "captcha", "_eventId", "cllt", "dllt", "lt", "execution"),
            keys,
        )
        assertFalse("绝不能带 passwordText", body.contains("passwordText"))
        assertFalse("绝不能带 rememberMe", body.contains("rememberMe"))
        assertTrue(body.contains("cllt=userNameLogin"))
        assertTrue(body.contains("_eventId=submit"))
        assertTrue(body.contains("execution=e1s1"))
    }

    @Test
    fun `captcha 与 lt 是空值 但键必须在`() {
        val body = ChuLoginParser.buildLoginBody("u", "c", "e")
        // 这两个字段浏览器也是以空值提交的，删掉键反而不对
        assertTrue(body.contains("captcha=&"))
        assertTrue(body.endsWith("lt=&execution=e"))
    }

    // ================================================================ 响应判定

    @Test
    fun `带票的 302 是唯一成功判据`() {
        val loc = "http://bkjw.chd.edu.cn/eams/home.action?ticket=ST-123-abc"
        val out = ChuLoginParser.classifyLoginPost(302, loc, "")
        assertTrue(out is ChuLoginParser.PostOutcome.Redirect)
        assertEquals("ST-123-abc", (out as ChuLoginParser.PostOutcome.Redirect).ticket)
    }

    /**
     * ★ `ticket=Unauthorized Service` 是**最像网络故障**的一种失败：
     * 名字在、值也在，只是内容是错误文案。它意味着 `service` 串与 CAS 注册值不符——
     * 重登一百次都没用。所以它既不能算成功，也不能算"密码错"（那会让人去改密码）。
     */
    @Test
    fun `ticket=Unauthorized 既不算成功也不算口令错`() {
        val loc = "http://bkjw.chd.edu.cn/eams/?ticket=Unauthorized Service"
        assertNull("不能当成拿到了票", ChuLoginParser.ticketFromLocation(loc))

        val out = ChuLoginParser.classifyLoginPost(302, loc, "")
        assertTrue("必须单独归类，指向 service 串写错", out is ChuLoginParser.PostOutcome.ServiceRejected)
    }

    @Test
    fun `401 是口令错`() {
        assertTrue(
            ChuLoginParser.classifyLoginPost(401, null, "") is
                ChuLoginParser.PostOutcome.BadCredentials
        )
    }

    /**
     * 200 却把登录页又发一遍 = salt 与提交时的 JSESSIONID 不是同一个会话（技能 §1.3）。
     * 服务端不报错、不 500，静默重发页面，所以这个分支**必须**与"成功"分得开。
     */
    @Test
    fun `200 加登录页 判成被退回表单页`() {
        val out = ChuLoginParser.classifyLoginPost(200, null, loginPage())
        assertTrue(out is ChuLoginParser.PostOutcome.KickedBackToForm)
    }

    @Test
    fun `500 是服务端错误`() {
        val body = """{"error":"Internal Server Error","timestamp":1790349474804,"status":500}"""
        assertTrue(
            ChuLoginParser.classifyLoginPost(500, null, body) is
                ChuLoginParser.PostOutcome.ServerError
        )
    }

    /**
     * ★ 判定顺序：流程键失效**也是 302**。
     *
     * 若先按"302 回登录页 = 口令错"来判，会把一个"重取流程键就能成功"的情况报成密码错，
     * 用户改半天密码也没用。所以这条测试同时钉住两件事：能认出来、且优先于别的 302 分支。
     */
    @Test
    fun `流程键失效优先于其他 302 判定`() {
        val loc = "https://ids.chd.edu.cn/authserver/login" +
            "?exception.message=A+problem+occurred+restoring+the+flow+execution+with+key+%27e2s1%27"
        val out = ChuLoginParser.classifyLoginPost(302, loc, "")
        assertTrue(out is ChuLoginParser.PostOutcome.FlowExpired)
    }

    @Test
    fun `没票的 302 回登录页算口令错`() {
        val loc = "https://ids.chd.edu.cn/authserver/login?service=http%3A%2F%2Fbkjw.chd.edu.cn%2Feams%2Fhome.action"
        assertTrue(
            ChuLoginParser.classifyLoginPost(302, loc, "") is
                ChuLoginParser.PostOutcome.BadCredentials
        )
    }

    @Test
    fun `认不出的状态码不瞎归类`() {
        val out = ChuLoginParser.classifyLoginPost(418, null, "")
        assertEquals(418, (out as ChuLoginParser.PostOutcome.Unexpected).code)
    }

    @Test
    fun `是否登录页 认准两个特征串`() {
        assertTrue(ChuLoginParser.looksLikeLoginPage(loginPage()))
        // 课表片段与错误页都不该被误判成登录页
        assertFalse(ChuLoginParser.looksLikeLoginPage("<html><title>error</title>login failed</html>"))
        assertFalse(ChuLoginParser.looksLikeLoginPage(resource("chu_course_table_262.html")))
    }

    @Test
    fun `needCaptcha 响应解析`() {
        assertEquals(false, ChuLoginParser.parseNeedCaptcha("""{"isNeed":false}"""))
        assertEquals(true, ChuLoginParser.parseNeedCaptcha("""{"isNeed":true}"""))
        assertEquals(true, ChuLoginParser.parseNeedCaptcha("""{"isNeed":TRUE}"""))
        // 问不出来要返回 null，让调用方按"不知道"继续，而不是当成"不需要"
        assertNull(ChuLoginParser.parseNeedCaptcha(""))
        assertNull(ChuLoginParser.parseNeedCaptcha("<html>502 Bad Gateway</html>"))
    }

    // ================================================================ 次数预算

    private class FakeClock(var now: Long = 0L) {
        fun advance(millis: Long) { now += millis }
    }

    private fun budgetWith(clock: FakeClock) = ChuLoginBudget(clock = { clock.now })

    @Test
    fun `窗口内连发会被冷却拦住`() {
        val clock = FakeClock()
        val budget = budgetWith(clock)

        assertTrue(budget.tryAcquire() is ChuLoginBudget.Verdict.Ok)

        // 立刻再来一次：太近。这条卡的是"代码写错了在毫秒级连发"，不是人
        val v = budget.tryAcquire()
        assertTrue(v is ChuLoginBudget.Verdict.TooSoon)
        assertEquals(20L, (v as ChuLoginBudget.Verdict.TooSoon).retryAfterSeconds)

        // 等过最小间隔就放行，且这是窗口内第 2 次
        clock.advance(ChuLoginBudget.MIN_GAP_MILLIS)
        assertTrue(budget.tryAcquire() is ChuLoginBudget.Verdict.Ok)

        clock.advance(ChuLoginBudget.MIN_GAP_MILLIS)
        assertTrue(budget.tryAcquire() is ChuLoginBudget.Verdict.Ok)
        assertEquals(ChuLoginBudget.MAX_PER_WINDOW, budget.used())

        // 第 4 次：窗口内已用完，必须拦住
        clock.advance(ChuLoginBudget.MIN_GAP_MILLIS)
        val exhausted = budget.tryAcquire()
        assertTrue(exhausted is ChuLoginBudget.Verdict.Exhausted)
    }

    /**
     * ★ 这是"滑动窗口"与"永久上限"的全部区别。
     *
     * 若做成"进程内只许 3 次"，那么一个长期挂着的 App 在会话反复过期后会**彻底失去**
     * 自动登录能力，只能重装或重启进程。滑动窗口会自己恢复。
     */
    @Test
    fun `滑出窗口后自动恢复`() {
        val clock = FakeClock()
        val budget = budgetWith(clock)

        repeat(ChuLoginBudget.MAX_PER_WINDOW) {
            clock.advance(ChuLoginBudget.MIN_GAP_MILLIS)
            assertTrue(budget.tryAcquire() is ChuLoginBudget.Verdict.Ok)
        }
        clock.advance(ChuLoginBudget.MIN_GAP_MILLIS)
        assertTrue(budget.tryAcquire() is ChuLoginBudget.Verdict.Exhausted)

        // 走过整个窗口，账本应当被清空
        clock.advance(ChuLoginBudget.WINDOW_MILLIS)
        assertEquals(0, budget.used())
        assertTrue(budget.tryAcquire() is ChuLoginBudget.Verdict.Ok)
    }

    @Test
    fun `用户主动重来可以清空预算`() {
        val clock = FakeClock()
        val budget = budgetWith(clock)

        repeat(ChuLoginBudget.MAX_PER_WINDOW) {
            clock.advance(ChuLoginBudget.MIN_GAP_MILLIS)
            budget.tryAcquire()
        }
        budget.reset()
        assertTrue(budget.tryAcquire() is ChuLoginBudget.Verdict.Ok)
    }

    /**
     * 预算的阈值必须**低于**服务端的容忍度。服务端在登录页上写着 5
     * （`var _badCredentialsCount`），我们取 3；两者相等或反过来的话，预算就失去意义了。
     */
    @Test
    fun `本地预算比服务端阈值更保守`() {
        assertEquals(5, ChuLoginParser.parseBadCredentialsLeft(loginPage()))
        assertTrue(
            "本地允许的次数必须少于服务端的 5 次",
            ChuLoginBudget.MAX_PER_WINDOW < 5,
        )
        // 最小间隔要明显大于正常人手速，否则会误伤真人
        assertTrue(ChuLoginBudget.MIN_GAP_MILLIS >= 10_000L)
    }

    // ---------------------------------------------------------------- 工具

    private fun decrypt(cipherB64: String, salt: String, iv: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(salt.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(iv.toByteArray(Charsets.UTF_8)),
        )
        return String(cipher.doFinal(Base64.getDecoder().decode(cipherB64)), Charsets.UTF_8)
    }

    private fun decodeFormValue(s: String): String =
        java.net.URLDecoder.decode(s, "UTF-8")

    private companion object {
        /** 与站点 `$aes_chars` 一致，用作"不该出现别的字符"的参照 */
        const val AES_CHARS = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678"
    }
}
