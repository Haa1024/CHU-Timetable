package com.seu.timetable.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.seu.timetable.data.CredentialStore
import com.seu.timetable.data.Credentials
import com.seu.timetable.data.NotLoggedInException
import com.seu.timetable.data.SessionProbe
import com.seu.timetable.data.SettingsStore
import com.seu.timetable.data.chu.CHU_EAMS_BASE
import com.seu.timetable.data.chu.CHU_UA
import com.seu.timetable.data.chu.ChuAuthClient
import com.seu.timetable.data.chu.ChuAuthResult
import com.seu.timetable.data.chu.ChuClient
import com.seu.timetable.ui.components.AccountField
import com.seu.timetable.ui.components.BackIcon
import com.seu.timetable.ui.components.FieldLabel
import com.seu.timetable.ui.components.PrimaryButton
import com.seu.timetable.ui.components.SectionLabel
import com.seu.timetable.ui.components.SeuCard
import com.seu.timetable.ui.components.SeuToggle
import com.seu.timetable.ui.theme.LocalSeuColors
import com.seu.timetable.ui.theme.LocalSeuType
import com.seu.timetable.ui.theme.SeuTheme
import com.seu.timetable.ui.theme.paletteOf
import com.seu.timetable.ui.theme.themeModeOf
import com.seu.timetable.util.DebugLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 承载登录界面三态：等用户动手 / 正在验证 / 完成。 */
private enum class LoginPhase { WAITING, CHECKING, DONE }

/**
 * 登录页当前呈现哪一种界面。
 *
 * [FORM] 是默认：只给账号密码表单，安静地把事办完。
 * [WEB] 是兜底：学校这次要验证码、或用户主动点「改用网页登录」时才露出网页——
 * 滑块验证码这一步只能人工在网页里完成，程序过不去。
 */
private enum class LoginStage { FORM, WEB }

private const val WAITING_HINT = "首次导入课表需要登录一次；此后查看、编辑、切换课表都不再需要联网。"
private const val AUTO_HINT = "正在用已保存的账号自动登录…"
private const val FORM_HINT = "填入学号与统一身份认证密码即可。登录只用于从教务导入课表。"
private const val WEB_HINT = "请在下方网页里完成登录。登录成功后会自动返回，也可以点右上角「已完成」。"
private const val WEB_ENTRY = "$CHU_EAMS_BASE/eams/"

/** 网页模式下轮询会话是否建立：每次间隔、以及总次数。 */
private const val WATCH_INTERVAL_MS = 1_500L
private const val WATCH_ROUNDS = 80

/**
 * 长安大学统一身份认证登录页。
 *
 * ## 两条路，同一份会话
 *
 * - **后台自动**（默认）：账号密码经 [ChuAuthClient] 走完 CAS，全程不见网页。
 * - **网页兜底**：学校要验证码、或用户主动点「改用网页登录」时，露出
 *   `bkjw.chd.edu.cn` 的登录页让人自己操作。
 *
 * 两条路共享同一份 cookie（都经 `WebViewCookieJar` 接 `CookieManager`），
 * 所以能互为兜底：后台登成功了网页打开就是登录态，用户在网页登过了后台也认得。
 *
 * ## 成功判据只有一条
 *
 * **真实业务接口能读到课表**（[probeOnce]）。不猜 cookie 名、不看页面返回，
 * 甚至不轻信 CAS 那一步拿到的票——票拿到了而业务域会话没建起来，是确实会发生的情况
 * （例如 `service` 串与注册值对不上）。
 *
 * ## 关于"别把账号当调试器"
 *
 * 本页**从不自动重试**。学校的认证侧有风控：连续失败若干次就强制滑块验证码，
 * 程序完全过不去，且计数归零之前用户自己也登不上。所以失败一律如实报告、
 * 把决定权交回用户——这条纪律的具体执行在 [ChuLoginBudget]，本页只负责不绕过它。
 */
class LoginActivity : ComponentActivity() {

    private val client by lazy { ChuClient() }
    private val auth by lazy { ChuAuthClient() }
    private val credentials by lazy { CredentialStore(this) }

    private val settings by lazy { SettingsStore(this) }

    /** 是否「能自动则自动」。由调用方决定（用户在「我的」页关掉自动登录则传 false）。 */
    private var autoMode = false

    private val phase = mutableStateOf(LoginPhase.WAITING)
    private val hint = mutableStateOf(WAITING_HINT)
    private val pageInfo = mutableStateOf("未打开网页")
    private val probeInfo = mutableStateOf("尚未探测")

    /** 默认表单；要验证码或用户主动改用网页时切到 [LoginStage.WEB]。 */
    private val stage = mutableStateOf(LoginStage.FORM)

    /** 表单下方那行错误提示，空串表示不显示。 */
    private val formError = mutableStateOf("")

    /** 网页模式顶部的说明文字，会随轮询结果变化。 */
    private val webHint = mutableStateOf(WEB_HINT)

    /** 会话轮询是否已在跑，保证只启动一次。 */
    private var watchRunning = false

    /** 本次会话是否已经谈妥（避免重复 finish）。 */
    private var finishedOk = false

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CookieManager.getInstance().setAcceptCookie(true)

        autoMode = intent.getBooleanExtra(EXTRA_AUTO, false)
        webView = buildWebView()

        setContent {
            // 登录页也跟着「我的 → 外观」走。它往往是用户看到的第一屏，
            // 配色不跟会最扎眼（此前这里是裸的 `SeuTheme {}`，于是永远显示默认色）。
            // 解析规则与主界面共用同一份（themeModeOf / paletteOf），不各写一遍。
            val themeName by settings.themeMode.collectAsState(initial = null)
            val paletteName by settings.themePalette.collectAsState(initial = null)
            SeuTheme(
                themeMode = themeModeOf(themeName),
                palette = paletteOf(paletteName),
            ) {
                LoginScreen(
                    stage = stage.value,
                    phase = phase.value,
                    hint = hint.value,
                    pageInfo = pageInfo.value,
                    probeInfo = probeInfo.value,
                    error = formError.value,
                    store = credentials,
                    webView = webView,
                    onSubmit = ::submitLogin,
                    onUseWeb = ::openWeb,
                    onManualCheck = { lifecycleScope.launch { if (probeOnce()) finishOk() } },
                    onGoBack = ::goBack,
                )
            }
        }

        DebugLog.i("===== CHU 登录页打开（auto=$autoMode）=====")
        hint.value = FORM_HINT

        // 表单模式**不加载任何页面**：打开登录页是零网络开销的。
        if (autoMode) lifecycleScope.launch { autoLogin() }
    }

    // ---------------------------------------------------------------- 自动登录

    /**
     * 启动时的静默续期。
     *
     * 顺序刻意如此：**先看会话在不在，再考虑碰密码**。
     * 会话还在时（日常绝大多数情况）完全不需要用到保存的密码——
     * 这也是「存了密码却几乎用不上」的常态，顺带把风控风险降到零。
     */
    private suspend fun autoLogin() {
        if (client.hasSession()) {
            DebugLog.i("auto：会话仍然有效 → 无需登录")
            exitToCaller(sessionOk = true)
            return
        }

        val creds = credentials.load()
        if (creds == null) {
            DebugLog.i("auto：本地没有保存的账号 → 安静地回到表单")
            hint.value = FORM_HINT
            return
        }

        setStatus(LoginPhase.CHECKING, AUTO_HINT)
        DebugLog.i("auto：用保存的账号（${creds.username}）尝试登录")
        when (val r = auth.login(creds.username, creds.password)) {
            is ChuAuthResult.Success -> afterAuth(r, auto = true)
            // 失败一律只报告、不重试：理由见类注释
            ChuAuthResult.BadCredentials ->
                degrade("保存的学号或密码不对，请重新输入。")
            ChuAuthResult.CaptchaRequired ->
                degrade("学校这次要求验证码，请点下方「改用网页登录」完成。")
            ChuAuthResult.FlowExpired ->
                degrade("登录凭据已过期，请再点一次「登录」。")
            ChuAuthResult.NoSalt ->
                degrade("登录页结构和预期不一致（缺少加密参数），请改用网页登录。")
            is ChuAuthResult.Throttled -> degrade(throttleMessage(r))
            is ChuAuthResult.Failed ->
                degrade("自动登录没成功（${r.reason}），请手动输入账号密码。")
        }
    }

    // ---------------------------------------------------------------- 表单提交

    /**
     * 用表单里的账号密码登录。
     *
     * ★ 提交前**先退出登录**。这不是洁癖：如果上一任用户留下了有效的 CAS 凭据，
     * `GET /login` 会直接 302 发票，于是「用 A 账号的密码」会静默登成 B 账号——
     * 而且看起来一切正常。换账号是登录页的常规用法，必须清干净。
     */
    private fun submitLogin(username: String, password: String, remember: Boolean) {
        if (phase.value != LoginPhase.WAITING) return
        formError.value = ""
        setStatus(LoginPhase.CHECKING, "正在验证账号…")

        lifecycleScope.launch {
            val user = username.trim()
            auth.signOut()
            client.clearSessionCache()

            when (val r = auth.login(user, password)) {
                is ChuAuthResult.Success -> {
                    // 先落盘再收工：中途被杀也不会出现"登上了但没记住"
                    if (remember) {
                        credentials.save(user, password)
                        credentials.setAutoLogin(true)
                    } else {
                        credentials.clear()
                        credentials.setAutoLogin(false)
                    }
                    afterAuth(r, auto = false)
                }
                ChuAuthResult.BadCredentials ->
                    fail("学号或密码不对。请核对后再试 —— 连错几次学校会要求验证码。")
                ChuAuthResult.CaptchaRequired ->
                    fail("学校这次要求验证码，请点下方「改用网页登录」完成。")
                ChuAuthResult.FlowExpired ->
                    fail("登录凭据已过期，请再点一次「登录」。")
                ChuAuthResult.NoSalt ->
                    fail("登录页结构和预期不一致（缺少加密参数），请改用网页登录。")
                is ChuAuthResult.Throttled -> fail(throttleMessage(r))
                is ChuAuthResult.Failed -> fail(r.reason)
            }
        }
    }

    /**
     * CAS 那一步之后统一收口。
     *
     * **拿到票 ≠ 会话建好了**：票可能因为 `service` 与注册值差一个字符而被判给别处。
     * 所以这里必须再用真实业务接口确认一次 [probeOnce]，不靠 [ChuAuthResult.Success] 自证。
     */
    private suspend fun afterAuth(result: ChuAuthResult.Success, auto: Boolean) {
        setStatus(LoginPhase.CHECKING, "正在确认课表会话…")
        DebugLog.i("CAS 完成（协议 ${result.protocol}，跟票到 eams=${result.eamsSession}）→ 用业务接口确认")

        if (probeOnce()) {
            finishOk()
            return
        }
        val reason = when (val p = lastProbe) {
            is SessionProbe.NotLoggedIn -> "登录完成了，但课表会话没建起来（${p.reason}）。"
            is SessionProbe.Failed -> "登录完成了，但确认会话时连不上教务：${p.reason}"
            is SessionProbe.LoggedIn -> "登录完成了，但会话确认异常。"
        }
        if (auto) {
            degrade("$reason 请手动登录一次。")
        } else {
            fail(reason)
        }
    }

    private fun throttleMessage(r: ChuAuthResult.Throttled): String = when {
        r.exhausted ->
            "短时间内尝试次数过多，为避免账号被学校风控锁定，已暂停自动登录。请在 ${r.retryAfterSeconds} 秒后再试。"
        else ->
            "刚刚已经试过一次了，请等 ${r.retryAfterSeconds} 秒再试。"
    }

    // ---------------------------------------------------------------- 网页兜底

    /**
     * 露出网页让用户自己登。
     *
     * 长安大学这边这一步**极其简单**：打开 `bkjw.chd.edu.cn/eams/`，EAMS 自己会把人
     * 导到统一身份认证页，用户登完自然回到业务页。不需要替用户点任何东西——
     * 标准 Apereo CAS 的跳转链是服务端完成的，没有门户 SPA 那种"必须跑 JS 才出得来"的环节。
     */
    private fun openWeb() {
        formError.value = ""
        stage.value = LoginStage.WEB
        webHint.value = WEB_HINT
        setStatus(LoginPhase.WAITING, WEB_HINT)
        webView.loadUrl(WEB_ENTRY)
    }

    /**
     * 网页模式下的会话轮询。
     *
     * 为什么不靠"页面跳到了某个 URL"来判断：业务页在未登录时也可能返回 200 的登录页，
     * URL 未必变。真正的判据只有一个——业务接口能不能读到课表，所以这里直接轮询它。
     */
    private fun startWatch() {
        if (watchRunning) return
        watchRunning = true
        lifecycleScope.launch {
            try {
                repeat(WATCH_ROUNDS) {
                    delay(WATCH_INTERVAL_MS)
                    if (probeOnce()) {
                        finishOk()
                        return@launch
                    }
                }
                webHint.value = "还没检测到登录成功。若你已登录，请点右上角「已完成」；" +
                    "也可以返回上一页改用账号密码登录。"
            } finally {
                watchRunning = false
            }
        }
    }

    /** 网页里返回：退回表单（而不是直接退出登录页）。 */
    private fun goBack() {
        if (stage.value == LoginStage.WEB) {
            stage.value = LoginStage.FORM
            setStatus(LoginPhase.WAITING, hint.value)
            return
        }
        setResult(RESULT_CANCELED)
        finish()
    }

    // ---------------------------------------------------------------- 会话探测

    private var lastProbe: SessionProbe = SessionProbe.NotLoggedIn("尚未探测")

    /**
     * 用一次真实业务接口调用判断会话。
     *
     * 直接看骨架页而不是调 [ChuClient.hasSession]：后者为了调用方便把异常压成了 false，
     * 于是"网络不通"会被报成"没登录"——正好会让人去反复重登，而其实该做的是等一会儿。
     */
    private suspend fun probeOnce(): Boolean {
        val result = try {
            val sk = client.skeleton(force = true)
            if (sk.ids != null) {
                SessionProbe.LoggedIn("课表页返回 ids=${sk.ids}")
            } else {
                SessionProbe.NotLoggedIn("课表页被换成了登录页")
            }
        } catch (e: NotLoggedInException) {
            SessionProbe.NotLoggedIn(e.message ?: "会话已失效")
        } catch (e: Exception) {
            SessionProbe.Failed(e.message ?: (e::class.simpleName ?: "未知错误"))
        }
        lastProbe = result
        probeInfo.value = describe(result)
        DebugLog.i("探测 = ${describe(result)}")
        return result is SessionProbe.LoggedIn
    }

    private fun describe(r: SessionProbe): String = when (r) {
        is SessionProbe.LoggedIn -> "已登录（${r.detail}）"
        is SessionProbe.NotLoggedIn -> "未登录（${r.reason}）"
        is SessionProbe.Failed -> "异常（${r.reason}）"
    }

    // ---------------------------------------------------------------- 收尾

    private fun setStatus(p: LoginPhase, h: String) {
        phase.value = p
        hint.value = h
    }

    /** 自动模式失败：安静地退回表单，把原因写在表单下方，而不是弹窗打断。 */
    private fun degrade(reason: String) {
        DebugLog.w("自动登录未成功：$reason")
        phase.value = LoginPhase.WAITING
        stage.value = LoginStage.FORM
        hint.value = FORM_HINT
        formError.value = reason
    }

    private fun fail(reason: String) {
        DebugLog.w("登录失败：$reason")
        phase.value = LoginPhase.WAITING
        formError.value = reason
    }

    private fun finishOk() {
        if (finishedOk) return
        finishedOk = true
        phase.value = LoginPhase.DONE
        DebugLog.i("登录成功，返回调用方")
        exitToCaller(sessionOk = true)
    }

    private fun exitToCaller(sessionOk: Boolean) {
        // 会话落盘：进程若在此刻被回收，重进时还能用它。这是唯一值得 flush 的时机。
        runCatching { CookieManager.getInstance().flush() }
        setResult(if (sessionOk) RESULT_OK else RESULT_CANCELED)
        finish()
    }

    // ---------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): WebView = WebView(this).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // 与后台自动登录用同一个 UA：服务端会依赖 UA 判定风控字段，
        // 两条路表现为"同一个客户端"才不会互相当成异常。
        settings.userAgentString = CHU_UA_FOR_WEB
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        // 业务域是 http、认证域是 https，跳转过程中可能混用；兜底路径以求能用为先。
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageInfo.value = shorten(url)
                DebugLog.i("网页落点：${shorten(url)}")
                // 每落一页就试着探测一次；成功即收工
                if (stage.value == LoginStage.WEB) {
                    startWatch()
                    lifecycleScope.launch { if (probeOnce()) finishOk() }
                }
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                view?.let { fitNarrow(it) }
            }
        }
    }

    /**
     * EAMS 是桌面站点，窄屏上会把内容缩成一条。
     * 取一个略小于常见手机宽度的基准按比例缩放，比让它自己 `loadWithOverviewMode` 更可控。
     */
    private fun fitNarrow(view: WebView) {
        val target = 420f
        val w = view.width.takeIf { it > 0 }?.toFloat() ?: return
        val scale = (w / target).coerceIn(0.5f, 3f)
        if (view.scaleX == scale) return
        view.setInitialScale((scale * 100).toInt())
    }

    private fun shorten(url: String?): String {
        val u = url?.takeIf { it.isNotBlank() } ?: return "—"
        return u.removePrefix("https://").removePrefix("http://").take(72)
    }

    // ---------------------------------------------------------------- 伴生

    companion object {
        /** 传 true 走自动模式（有保存的凭据就自己登，失败安静退化成普通登录页）。 */
        const val EXTRA_AUTO = "com.seu.timetable.extra.AUTO"

        fun intent(context: Context, auto: Boolean = false): Intent =
            Intent(context, LoginActivity::class.java).putExtra(EXTRA_AUTO, auto)
    }
}

/**
 * 网页兜底用的 UA —— 刻意与 [ChuClient] / [ChuAuthClient] 用**同一个常量**。
 *
 * 服务端会依赖 UA 判定风控字段（`MULTIFACTOR_BROWSER_FINGERPRINT` 之类），
 * 后台自动登录与网页登录必须表现为"同一个客户端"，否则会被当成异常行为。
 * 因此这里直接引用 [CHU_UA]，而不是自己再写一遍字符串——复制一份就迟早会不一致。
 */
private val CHU_UA_FOR_WEB = CHU_UA

// ---------------------------------------------------------------- 界面

/**
 * 登录页。
 *
 * [LoginStage.FORM] 时表单盖在最上层；[LoginStage.WEB] 时露出网页。
 *
 * 网页用 `alpha` 而不是 `visibility` 来藏：WebView 必须真的完成布局与加载，
 * 页面里的脚本才跑得起来（验证码就是脚本渲染的）。`INVISIBLE` / `GONE` 还可能让
 * WebView 暂停 JS 定时器，故取 alpha=0——对 View 系统而言它仍是 VISIBLE。
 */
@Composable
private fun LoginScreen(
    stage: LoginStage,
    phase: LoginPhase,
    hint: String,
    pageInfo: String,
    probeInfo: String,
    error: String,
    store: CredentialStore,
    webView: WebView,
    onSubmit: (String, String, Boolean) -> Unit,
    onUseWeb: () -> Unit,
    onManualCheck: () -> Unit,
    onGoBack: () -> Unit,
) {
    val c = LocalSeuColors.current

    Box(
        Modifier
            .fillMaxSize()
            .background(c.bg)
    ) {
        Column(Modifier.fillMaxSize()) {
            if (stage == LoginStage.WEB) {
                WebBar(
                    hint = hint,
                    pageInfo = pageInfo,
                    probeInfo = probeInfo,
                    phase = phase,
                    onManualCheck = onManualCheck,
                    onGoBack = onGoBack,
                )
            }

            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .alpha(if (stage == LoginStage.WEB) 1f else 0f),
                factory = { webView },
            )
        }

        if (stage == LoginStage.FORM) {
            LoginForm(
                phase = phase,
                hint = hint,
                error = error,
                store = store,
                onSubmit = onSubmit,
                onUseWeb = onUseWeb,
                onGoBack = onGoBack,
            )
        }
    }
}

/** 网页模式下的顶部条：返回、手动确认，以及两行诊断。 */
@Composable
private fun WebBar(
    hint: String,
    pageInfo: String,
    probeInfo: String,
    phase: LoginPhase,
    onManualCheck: () -> Unit,
    onGoBack: () -> Unit,
) {
    val c = LocalSeuColors.current
    val t = LocalSeuType.current

    Column(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .background(c.bg)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 用户点歪了能退回去（退化成"一个正常浏览器该有的样子"）
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onGoBack() }
                    .padding(6.dp)
            ) {
                BackIcon(c.textPrimary, 18.dp)
            }
            Spacer(Modifier.weight(1f))
            Text("登录校园账号", style = t.navTitle, color = c.textPrimary)
            Spacer(Modifier.weight(1f))
            // 兜底出口：万一有没预料到的跳转（新窗口、额外确认页），
            // 用户主动确认一次即可，不必卡在这一页。
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onManualCheck() }
                    .padding(6.dp)
            ) {
                Text("已完成", style = t.caption, color = c.primary)
            }
        }

        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(hint, style = t.micro, color = c.textSecondary)
            // 诊断两行：登录不通时能直接说明卡在哪一步。网页是排障用的界面，只在这里显示。
            DiagLine("页面：$pageInfo")
            DiagLine("会话：$probeInfo")
        }

        if (phase == LoginPhase.CHECKING) {
            // 不要全屏转圈，用一条细进度条
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** 登录表单：本页的默认界面。 */
@Composable
private fun LoginForm(
    phase: LoginPhase,
    hint: String,
    error: String,
    store: CredentialStore,
    onSubmit: (String, String, Boolean) -> Unit,
    onUseWeb: () -> Unit,
    onGoBack: () -> Unit,
) {
    val c = LocalSeuColors.current
    val t = LocalSeuType.current

    val savedUsername by store.savedUsername.collectAsState(initial = null)
    val hasPassword by store.hasPassword.collectAsState(initial = false)
    val autoLogin by store.autoLoginEnabled.collectAsState(initial = false)

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var remember by remember { mutableStateOf(false) }

    // 学号只回填一次，不覆盖用户正在输入的内容；密码永不回填。
    var usernameFilled by remember { mutableStateOf(false) }
    LaunchedEffect(savedUsername) {
        if (!usernameFilled && !savedUsername.isNullOrBlank()) {
            username = savedUsername.orEmpty()
            usernameFilled = true
        }
    }

    // 「保存账号密码」的初值跟随用户此前在「我的 → 校园账号」里的选择，
    // 但一旦用户手动拨过，就不再被数据流覆盖。
    var rememberTouched by remember { mutableStateOf(false) }
    LaunchedEffect(autoLogin) {
        if (!rememberTouched) remember = autoLogin
    }

    val busy = phase != LoginPhase.WAITING
    val canSubmit = !busy && username.isNotBlank() && password.isNotEmpty()

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onGoBack() }
                    .padding(6.dp)
            ) {
                BackIcon(c.textPrimary, 18.dp)
            }
            Spacer(Modifier.weight(1f))
            Text("登录校园账号", style = t.navTitle, color = c.textPrimary)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(30.dp))
        }

        Spacer(Modifier.height(12.dp))

        // ---- 先说清楚"为什么值得填"：不少人会误以为不登录就用不了 ----
        SeuCard(Modifier.fillMaxWidth()) {
            Column {
                Text("什么时候需要登录", style = t.itemTitle, color = c.textPrimary)
                Spacer(Modifier.height(8.dp))
                Text(
                    "只在从教务导入课表和「从教务同步」时需要。查看、编辑、切换课表都不需要登录，" +
                        "断网也能正常使用。",
                    style = t.caption,
                    color = c.textSecondary,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("账号密码")
        Spacer(Modifier.height(8.dp))
        SeuCard(Modifier.fillMaxWidth()) {
            Column {
                FieldLabel("学号")
                AccountField(
                    value = username,
                    onValueChange = { username = it },
                    // ★ 这里只能是**编造的**示例。曾经填的是真实学号，等于把一个同学的
                    //   个人信息印在了每个用户的登录页上——而且这行会被提交进公开仓库。
                    //   改这一行时请守住"一眼看得出是假号"这条线。
                    placeholder = "例如 2026123456",
                    keyboardType = KeyboardType.Text,
                    masked = false,
                )
                Spacer(Modifier.height(14.dp))
                FieldLabel("密码")
                AccountField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = "统一身份认证密码",
                    keyboardType = KeyboardType.Password,
                    masked = true,
                )
                if (error.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(error, style = t.micro, color = c.danger)
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        SeuCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("保存账号密码", style = t.body, color = c.textPrimary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (hasPassword) {
                            "已保存 ${savedUsername.orEmpty()}；密码经系统密钥库加密后存在本机"
                        } else {
                            "密码经系统密钥库加密后存在本机，登录态失效时自动续期"
                        },
                        style = t.micro,
                        color = c.textTertiary,
                    )
                }
                Spacer(Modifier.width(14.dp))
                SeuToggle(
                    checked = remember,
                    onCheckedChange = {
                        rememberTouched = true
                        remember = it
                    },
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        PrimaryButton(
            text = if (busy) "正在登录…" else "登录",
            enabled = canSubmit,
            onClick = { onSubmit(username.trim(), password, remember) },
            modifier = Modifier.fillMaxWidth(),
        )

        if (phase == LoginPhase.CHECKING) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(12.dp))
        Text(hint, style = t.caption, color = c.textSecondary)

        Spacer(Modifier.height(16.dp))
        // 出口：需要验证码，或表单这条路走不通时，退回学校原本的网页登录。
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = !busy) { onUseWeb() }
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("改用网页登录", style = t.itemTitle, color = c.textSecondary)
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DiagLine(text: String) {
    Text(
        text,
        style = LocalSeuType.current.micro,
        color = LocalSeuColors.current.textTertiary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
