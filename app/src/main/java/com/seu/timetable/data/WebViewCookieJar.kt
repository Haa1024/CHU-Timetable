package com.seu.timetable.data

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * 把 OkHttp 的 cookie 存取接到 WebView 的 [CookieManager] 上。
 *
 * ## 为什么必须有这一层
 *
 * 本项目「拿会话」有两条路：后台自动登录（纯 OkHttp）和网页兜底登录（WebView）。
 * 两者若各存一份 cookie，就会出现「网页明明登上了，后台却还说自己没登录」这种
 * 看起来像 bug 的状态。接到同一个 [CookieManager] 之后，两条路共享同一份 cookie，
 * **互为兜底**：
 *
 *  - 后台自动登录成功 → WebView 打开业务页时已是登录态，不会再多问一次密码；
 *  - 用户在网页里手动登录 → 后台随后的接口调用直接带上那次会话。
 *
 * 另一层收益是持久化：`CookieManager` 自己会把 cookie 落盘（配合一次 `flush()`），
 * 不必我们再实现一套 cookie 存储。
 *
 * ## 与 `android.webkit` 的耦合
 *
 * 这是整个数据层**唯一**直接依赖 Android framework 的地方（其余 HTTP 客户端都只依赖
 * OkHttp 接口）。所以后台自动登录那部分逻辑能在桌面 JVM 上离线单测，而本类不行——
 * 构造它必须依托真实设备或仪器测试。测试里若需要替换它，实现 [CookieJar] 即可。
 *
 * `getInstance()` 在极端情况下（例如单元测试的裸 JVM）会抛异常，
 * 故两处都 `runCatching` 兜住：拿不到就当没有 cookie，而不是让整个请求崩掉。
 */
class WebViewCookieJar : CookieJar {

    private val manager: CookieManager? get() = runCatching { CookieManager.getInstance() }.getOrNull()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val cm = manager ?: return
        cookies.forEach { cm.setCookie(url.toString(), it.toString()) }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val cm = manager ?: return emptyList()
        val header = cm.getCookie(url.toString()) ?: return emptyList()
        return header.split(';').mapNotNull { Cookie.parse(url, it.trim()) }
    }
}
