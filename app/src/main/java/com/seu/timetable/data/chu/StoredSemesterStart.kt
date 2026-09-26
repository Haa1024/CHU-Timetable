package com.seu.timetable.data.chu

import com.seu.timetable.data.SettingsStore
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * 把「用户填的开学日」接到数据层的 [ChuSemesterStart] 上。
 *
 * ## 为什么需要这一层
 *
 * [ChuSemesterStart.firstMondayOf] 是个**非挂起**函数（它被 [ChuTimetableSource] 在
 * 组装学期上下文时同步调用），而设置存在 DataStore 里、只能挂起读。
 * 直接让它挂起会把这个简单的形状污染到整条链上，所以中间垫一层内存缓存：
 *
 * ```
 * 设置页写入 → SettingsStore(DataStore)
 *                    ↓ refresh()（挂起，每次加载课表前调一次）
 *              本类的内存快照  ← 同步读
 *                    ↓ firstMondayOf()
 *              ChuTimetableSource
 * ```
 *
 * [refresh] 是一次 DataStore 读，很便宜；放在加载课表前调用即可，
 * 用户刚在设置里改完开学日，下一次导入/同步就会用上新值。
 *
 * ## 未配置就是未配置
 *
 * 查不到时返回 null，**不要**顺手编一个日期。这个值只影响「今天第几周」和「今日课程」，
 * 数据层会用 [provisionalFirstMonday] 兜底（本周即第 1 周）——那个兜底至少是诚实的，
 * 而一个编出来的开学日会让界面显示一个看起来很正常、实际错误的周次。
 */
class StoredSemesterStart(private val settings: SettingsStore) : ChuSemesterStart {

    private var cache: Map<String, LocalDate> = emptyMap()

    /** 从本地设置重新读一遍开学日。每次加载课表前调用。 */
    suspend fun refresh() {
        cache = settings.semesterStarts.first()
    }

    override fun firstMondayOf(semester: ChuSemester): LocalDate? = cache[semester.termCode]

    /** 已配置开学日的学期代码，供界面提示「还有哪些学期没填」。 */
    fun configuredTerms(): Set<String> = cache.keys
}
