package com.seu.timetable.data

/**
 * 一次「会话还在不在」的探测结果。
 *
 * ## 为什么是三段而不是 Boolean
 *
 * 界面对这三种情况的反应**完全不同**，压成一个 Boolean 就必然有一类被答错：
 *
 * | 结果 | 界面该做什么 |
 * |---|---|
 * | [LoggedIn] | 放行，正常拉数据 |
 * | [NotLoggedIn] | 弹登录页 —— 这是用户能自己解决的问题 |
 * | [Failed] | 提示「网络或服务异常，请重试」—— 让用户去登录是误导，他登一百次也没用 |
 *
 * 最典型的是第三种：服务器 502 时若报成「未登录」，用户会反复重登，
 * 而真正该做的是等一会儿再试。
 *
 * ## detail / reason 分别放什么
 *
 * [LoggedIn.detail] 放**证明**会话有效的证据（哪个接口返回了什么），
 * 这样「明明显示已登录，一拉课表就报未登录」时能立刻定位是哪一步的判断错了。
 * 另外两个的 reason 直接面向用户，要写成能看懂的话。
 *
 * 探测本身必须是**一次真实的业务接口调用**，不能靠「某个 cookie 还在不在」来猜：
 * cookie 的名字和有效期都会变，而服务端会话过期后 cookie 未必同步消失。
 */
sealed interface SessionProbe {

    /** 会话有效：真实接口确实返回了业务数据 */
    data class LoggedIn(val detail: String) : SessionProbe

    /** 明确是没登录 / 会话已过期 */
    data class NotLoggedIn(val reason: String) : SessionProbe

    /** 其它错误：网络不通、5xx、响应解析失败等。**不要**引导用户去登录 */
    data class Failed(val reason: String) : SessionProbe
}
