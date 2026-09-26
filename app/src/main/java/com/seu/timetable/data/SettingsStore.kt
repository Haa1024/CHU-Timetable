package com.seu.timetable.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "seu_timetable")

/**
 * 本地设置。
 *
 * 这些值均**不属于教务数据**（教务侧仅含课表本身），故仅能存储于本地。
 *
 * 课表改为本地资产后，此类已大幅精简：原先按"启动即拉取"设计的
 *   「自动同步开关」「上次同步时间」等字段均已移除，仅保留与显示、提醒相关的偏好，
 *   与课表数据本身无关。
 */
class SettingsStore(private val context: Context) {

    /**
     * 已手动关闭「未排课」提示条的学期代码。
     *
     * 记录的是**学期**而非布尔值：换学期后提示应重新出现，
     *   否则新学期新增课程将永远不再提示。
     */
    val dismissedUnplacedTerm: Flow<String?> =
        context.settingsDataStore.data.map { it[KEY_DISMISSED_UNPLACED] }

    /**
     * 亮暗模式，存 `ThemeMode` 的枚举名；**没选过就是 null**。
     *
     * ★ 同 [themePalette]：这里不给默认值，默认只在 `ui.theme.ThemeMode` 一侧定。
     */
    val themeMode: Flow<String?> =
        context.settingsDataStore.data.map { it[KEY_THEME_MODE] }

    /**
     * 配色主题，存 `Palette` 的枚举名；**没选过就是 null**。
     *
     * ★ 这里刻意**不给默认值**：默认值只在 `ui.theme.Palette.DEFAULT` 一处定义。
     * 两处各写一份"默认是哪个"，迟早会不一致——而界面会照旧跑、只是颜色不对，
     * 没人会想到去查设置层。设置层存的是"用户选过什么"，"没选过用什么"是界面的事。
     */
    val themePalette: Flow<String?> =
        context.settingsDataStore.data.map { it[KEY_THEME_PALETTE] }

    val reminderEnabled: Flow<Boolean> =
        context.settingsDataStore.data.map { it[KEY_REMINDER_ENABLED] ?: false }

    /** 提前几分钟提醒，默认 15 */
    val reminderMinutes: Flow<Int> =
        context.settingsDataStore.data.map { it[KEY_REMINDER_MINUTES] ?: 15 }

    /**
     * 实操引导是否已经看过。
     *
     * 只在**真正完整走过一遍**（或主动跳过）后置真。默认 false——
     * 首次安装的用户值即为"没看过"，回主页时自动开始引导。
     *
     * 为什么不复用"是否有课表"来判断：那只能表达"用户建过课表"，
     * 无法表达"用户已经知道各功能在哪"。老用户升级到带引导的版本、
     * 或清过一次数据，都会退化成"又被引导一遍"。
     */
    val guideSeen: Flow<Boolean> =
        context.settingsDataStore.data.map { it[KEY_GUIDE_SEEN] ?: false }

    /**
     * 首次公告（这是个什么 App、从哪来、去哪儿点 star）是否看过。
     *
     * ★ 与 [guideSeen] **分开记**：两者性质不同——公告回答"这是什么"，
     *   操作引导回答"怎么用"。合成一个键，看过引导就会顺带把公告吞掉（或反过来）。
     *   默认 false 与 [guideSeen] 同理：首次安装即为"没看过"。
     */
    val noticeSeen: Flow<Boolean> =
        context.settingsDataStore.data.map { it[KEY_NOTICE_SEEN] ?: false }

    /**
     * 各学期的「第一周周一」。键是学期代码（`2026-2027-1`），值是 ISO 日期（`2026-08-31`）。
     *
     * ## 为什么必须由用户提供
     *
     * 长安大学的 EAMS **没有任何接口**给出学期起止日期——已逐一验证 `dataQuery.action`
     * 的各种 `dataType`（含 `semesterCalendar`），都不含日期字段。
     * 而这个值决定「今天第几周」和「今日课程」，猜错会让整个今日页错位。
     *
     * ## 为什么按学期分开存，而不是只存一个
     *
     * 开学日**逐学期不同**。只存一个的话，换学期后新学期会默默沿用上学期的日期——
     * 界面上完全看不出异常（课表网格、周次、节次全靠相对偏移，本来就对），
     * 只有"今天第几周"偏了，而这是最难发现的一类错误。
     *
     * 存储格式 `学期代码=ISO日期`，用 `StringSet` 而不是给 DataStore 加数据库。
     * 解析失败的条目直接丢弃（当作没配），不要让一条脏数据把整份设置弄崩。
     */
    val semesterStarts: Flow<Map<String, LocalDate>> =
        context.settingsDataStore.data.map { prefs ->
            prefs[KEY_SEMESTER_STARTS].orEmpty().mapNotNull { entry ->
                val i = entry.indexOf('=')
                if (i <= 0) return@mapNotNull null
                val date = runCatching { LocalDate.parse(entry.substring(i + 1)) }.getOrNull()
                if (date == null) null else entry.substring(0, i) to date
            }.toMap()
        }

    /** 记下某学期的第一周周一（同一学期重复设置即覆盖）。 */
    suspend fun setSemesterStart(termCode: String, firstMonday: LocalDate) =
        context.settingsDataStore.edit { prefs ->
            val kept = prefs[KEY_SEMESTER_STARTS].orEmpty().filterNot { it.startsWith("$termCode=") }
            prefs[KEY_SEMESTER_STARTS] = (kept + "$termCode=$firstMonday").toSet()
        }

    /** 忘掉某学期的开学日，回到「未配置」（此时数据层走"本周即第 1 周"的兜底）。 */
    suspend fun clearSemesterStart(termCode: String) =
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_SEMESTER_STARTS] = prefs[KEY_SEMESTER_STARTS].orEmpty()
                .filterNot { it.startsWith("$termCode=") }
                .toSet()
        }

    suspend fun markGuideSeen() =
        context.settingsDataStore.edit { it[KEY_GUIDE_SEEN] = true }

    /** 供「重看新手指引」使用：把标记清掉，回主页时即会重新开始。 */
    suspend fun resetGuideSeen() =
        context.settingsDataStore.edit { it.remove(KEY_GUIDE_SEEN) }

    /**
     * 公告看过就置真。两个按钮（去点 star / 开始使用）都会走到这里，见 AppRoot 里的用法。
     *
     * 刻意**不做 resetNoticeSeen**：公告只该出现一次。要再找作者的入口在「我的」页，
     * 那里是常驻的，不必靠重放公告。
     */
    suspend fun markNoticeSeen() =
        context.settingsDataStore.edit { it[KEY_NOTICE_SEEN] = true }

    suspend fun dismissUnplaced(termCode: String) =
        context.settingsDataStore.edit { it[KEY_DISMISSED_UNPLACED] = termCode }

    suspend fun restoreUnplacedBar() =
        context.settingsDataStore.edit { it.remove(KEY_DISMISSED_UNPLACED) }

    suspend fun setThemeMode(mode: String) =
        context.settingsDataStore.edit { it[KEY_THEME_MODE] = mode }

    suspend fun setThemePalette(name: String) =
        context.settingsDataStore.edit { it[KEY_THEME_PALETTE] = name }

    suspend fun setReminderEnabled(value: Boolean) =
        context.settingsDataStore.edit { it[KEY_REMINDER_ENABLED] = value }

    suspend fun setReminderMinutes(value: Int) =
        context.settingsDataStore.edit { it[KEY_REMINDER_MINUTES] = value }

    /**
     * 此处**刻意不提供** `autoSync` / `lastSyncAt`。
     *
     * 课表为本机资产：启动应用不会向教务拉取任何数据，用户未点击「同步」则数据
     *   永不被覆盖。因此"上次同步时间"类字段无存在意义——它只会产生虚假的
     *   "自动同步"概念，使用户误以为后台在持续运行。
     * 真正需展示的是**该课表最后一次导入的时刻**，属每张课表自身属性，
     *   存储于 `BoardMeta.lastImportAt`，与全局设置无关。
     *
     * 「待同步标记」「同步失败重试」同理：无自动同步则无相关状态需记录。
     */
    private companion object {
        val KEY_DISMISSED_UNPLACED = stringPreferencesKey("unplaced_bar_dismissed_term")
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_THEME_PALETTE = stringPreferencesKey("theme_palette")
        val KEY_REMINDER_ENABLED = booleanPreferencesKey("reminder_enabled")
        val KEY_REMINDER_MINUTES = intPreferencesKey("reminder_minutes")
        val KEY_GUIDE_SEEN = booleanPreferencesKey("guide_seen")
        val KEY_NOTICE_SEEN = booleanPreferencesKey("notice_seen")

        /** 形如 `2026-2027-1=2026-08-31`。见 [semesterStarts] */
        val KEY_SEMESTER_STARTS = stringSetPreferencesKey("semester_starts")
    }
}
