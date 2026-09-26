package com.seu.timetable.data.chu

/**
 * 口令提交的次数预算与冷却。
 *
 * ## 为什么这条纪律必须写成代码，而不能靠自觉
 *
 * 上一轮拿真实账号调试登录：十几分钟里十几次失败提交，账号直接被风控冻结。
 * 回头看，问题出在当时**根本没数过**——而站点登录页自己就写着
 * `var _badCredentialsCount = "5";`：连续 5 次口令错误之后，页面会强制出验证码，
 * 且 `captchaSwitch == "2"` 走的是**滑块**（`createSliderCaptcha()`），
 * 纯 HTTP 完全过不去，只能人肉拖一次。
 *
 * 也就是说，服务端早就把预算写在页面上了，只是没人读。这里把数补上。
 *
 * ## 参数是怎么定的
 *
 * - **滑动窗口**每 [maxPerWindow] 次 / [windowMillis]，而不是"整个进程只许 3 次"。
 *   后者会让长期挂着的 App 在会话反复过期后彻底失去自动登录能力；前者会自愈。
 * - [minGapMillis] 20 秒：正常人改密码、重输，前后至少隔这么久；
 *   而一个写错的循环会在毫秒级连发。这条卡的是**代码出错**，不是人。
 * - 两个阈值都**远低于**服务端的 5 次，给"用户真的手滑了几次"留足余量。
 *
 * ## 只统计"真的发出去了"的那一次
 *
 * [tryAcquire] 是在 POST 之前调用的，而调用它之前所有可能失败的步骤（取 salt、加密、
 * 问验证码、查页面声明的剩余次数）都已经走完。所以不会出现"明明没提交，预算却被扣掉"。
 *
 * 反过来说：**任何新增的、可能在 POST 前失败的步骤，都要排在 [tryAcquire] 前面**。
 *
 * ## 为什么是 public 而不是 internal
 *
 * 它被 [ChuAuthClient] 的构造器当作可注入依赖暴露出来（测试要换掉 [clock]），
 * 而 [ChuAuthClient] 是公开类 —— Kotlin 不允许公开成员暴露 internal 类型
 * （编译器报 `Function 'public' exposes its 'internal' parameter type`）。
 *
 * 更实际的理由：[GLOBAL] 单例必须被所有调用点共用，而"重新 new 一个客户端就等于把预算清零"
 * 恰恰是这个设计里最容易踩空的地方。让它出现在 API 表面上，比藏进 internal 里更安全——
 * 藏起来只会让人以为"每次都新建一个也无所谓"。
 */
class ChuLoginBudget(
    private val maxPerWindow: Int = MAX_PER_WINDOW,
    private val windowMillis: Long = WINDOW_MILLIS,
    private val minGapMillis: Long = MIN_GAP_MILLIS,
    /** 注入时钟而非直取 `System.currentTimeMillis()`，否则这套判定没法离线测 */
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** 一次预算申请的结果。 */
    sealed interface Verdict {

        /** 放行，且这一次已经记账 */
        data object Ok : Verdict

        /** 距上一次提交太近。理由是"代码可能出了错"，而不是"你在乱试" */
        data class TooSoon(val retryAfterSeconds: Long) : Verdict

        /** 窗口内次数用尽。此时**必须转网页**，不要再想办法绕 */
        data class Exhausted(val retryAfterSeconds: Long) : Verdict
    }

    /** 已放行的提交时刻。只用尾部与头部，故 deque 比 list 合适。 */
    private val submissions = ArrayDeque<Long>()

    @Synchronized
    fun tryAcquire(): Verdict {
        val now = clock()

        // 先淘汰滑出窗口的旧记录。少了这一步，"用尽"状态永远不会解除——
        // 而这正是"滑动窗口"与"永久上限"的全部区别。
        prune(now)

        submissions.lastOrNull()?.let { last ->
            val gap = now - last
            if (gap < minGapMillis) return Verdict.TooSoon(secondsUntil(minGapMillis - gap))
        }

        if (submissions.size >= maxPerWindow) {
            return Verdict.Exhausted(secondsUntil(submissions.first() + windowMillis - now))
        }

        submissions.addLast(now)
        return Verdict.Ok
    }

    /** 把账本清空。供测试、以及"用户主动点了重新登录"这类明确意图使用。 */
    @Synchronized
    fun reset() = submissions.clear()

    /**
     * 当前窗口内已用掉几次。给界面显示"本次还能自动试几次"。
     *
     * ★ **这里也必须先淘汰**，不能直接返回 [submissions] 的长度。
     * 否则窗口早已滑过、实际随时可以再试了，界面却还显示"已用满 3 次"——
     * 这种"账本看着满、其实已清零"的显示错误，正好会让人以为自动登录彻底坏了，
     * 于是去重装 App。（这个 bug 在写完的第一版里真实存在过，被"滑出窗口后自动恢复"那条测试逮住。）
     */
    @Synchronized
    fun used(): Int {
        prune(clock())
        return submissions.size
    }

    /** 淘汰滑出窗口的旧记录。**只由持有锁的 [tryAcquire] / [used] 调用**。 */
    private fun prune(now: Long) {
        while (submissions.isNotEmpty() && now - submissions.first() >= windowMillis) {
            submissions.removeFirst()
        }
    }

    /** 向上取整到秒，且最小值给 1：提示"还要等 0 秒"是没有意义的。 */
    private fun secondsUntil(millis: Long): Long = ((millis + 999) / 1000).coerceAtLeast(1)

    companion object {
        /** 窗口内允许的提交次数。服务端阈值为 5，这里取 3。 */
        const val MAX_PER_WINDOW = 3

        /** 滑动窗口长度 */
        const val WINDOW_MILLIS = 10 * 60 * 1000L

        /** 两次提交之间的最小间隔 */
        const val MIN_GAP_MILLIS = 20 * 1000L

        /**
         * 进程级单例。
         *
         * 预算的意义就是"跨调用累计"，所以必须共用一份。若每个 [ChuAuthClient] 各持一份，
         * 那么"重新 new 一个客户端"就等于把预算清零——这恰好是最容易发生的事
         * （`by lazy` 的 Activity 重建、每次同步新建客户端…），也就等于没有预算。
         */
        val GLOBAL = ChuLoginBudget()
    }
}
