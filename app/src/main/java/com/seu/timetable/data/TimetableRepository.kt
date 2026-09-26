package com.seu.timetable.data

import android.content.Context
import com.seu.timetable.data.chu.ChuClient
import com.seu.timetable.data.chu.ChuSemesterCalendar
import com.seu.timetable.data.chu.ChuTimetableSource
import com.seu.timetable.data.chu.SemesterProbe
import com.seu.timetable.data.chu.StoredSemesterStart
import com.seu.timetable.domain.BoardContent
import com.seu.timetable.domain.BoardIndex
import com.seu.timetable.domain.BoardMeta
import com.seu.timetable.domain.BoardSource
import com.seu.timetable.domain.Course
import com.seu.timetable.domain.CourseSession
import com.seu.timetable.domain.MANUAL_TERM_PREFIX
import com.seu.timetable.domain.PeriodTime
import com.seu.timetable.domain.PeriodTimes
import com.seu.timetable.domain.TermContext
import com.seu.timetable.domain.Timetable
import java.time.LocalDate

/**
 * 课表业务层：导入 / 复制 / 自建 / 切换 / 增删改课程。
 *
 * 课表是本地资产：仅导入时联网下载，之后可完全离线使用。
 * 写操作一律「先内容、后索引」，两步之间进程被杀时最坏留下孤儿内容文件，
 * 不会出现「索引里有这张表、打开却是空的」这类看似丢数据的状况。
 */
class TimetableRepository(
    context: Context,
    private val settings: SettingsStore = SettingsStore(context),
) {

    private val library = TimetableLibrary(context)

    /**
     * 教务客户端。**全 App 只此一份**，不要在这里之外再 new 第二个——
     * 它持有骨架页缓存（`ids` / 总周数），多一份就会出现
     * 「这边说已登录、那边说没有会话」这种自相矛盾的状态。
     */
    private val client = ChuClient()

    /** 开学日的内存快照。见 [StoredSemesterStart] 的说明 */
    private val semesterStart = StoredSemesterStart(settings)

    private val source = ChuTimetableSource(client, semesterStart)

    /**
     * 教务会话探测——导入页用它区分「要登录」和「网络坏了」。
     *
     * 这里**刻意不用** [ChuClient.hasSession]：那个方法为了调用方便，内部把异常压成了
     * false，于是网络不通也会被报成「未登录」——正好是 [SessionProbe] 的三段设计要避免的
     * 那类误判（让用户反复重登，而其实该做的是等一会儿再试）。
     * 所以直接看骨架页本身：能解析出 `ids`，才是真的登录着。
     */
    suspend fun probeSession(): SessionProbe = try {
        val sk = client.skeleton(force = true)
        if (sk.ids != null) {
            SessionProbe.LoggedIn("课表页返回了 ids=${sk.ids}")
        } else {
            SessionProbe.NotLoggedIn("课表页被换成了登录页 —— 会话已失效")
        }
    } catch (e: NotLoggedInException) {
        SessionProbe.NotLoggedIn(e.message ?: "会话已失效")
    } catch (e: Exception) {
        SessionProbe.Failed(e.message ?: (e::class.simpleName ?: "未知错误"))
    }

    suspend fun hasSession(): Boolean = client.hasSession()

    /** 可用学期列表（含当前学期）。导入页据此让用户直接选，不必手敲学期代码。 */
    suspend fun semesters(): ChuSemesterCalendar = source.semesters()

    /**
     * 当前学期，**附失败原因**。
     *
     * 导入页用它，是为了在拿不到学期时能说清是「没登录」还是「接口坏了 / 页面变了」——
     * 这两种情况的下一步动作完全不同（去登录 / 手动填学期代码）。
     * [currentTermCode] / [currentTermName] 那种「吞成 null」的形态只适合不关心原因的场合。
     */
    suspend fun probeSemester(): SemesterProbe = source.probeSemester()


    // ------------------------------------------------------------ 读

    suspend fun index(): BoardIndex = library.readIndex()

    /** 打开某张课表；读不到元信息返回 null。当前周现算不落盘：它依赖「今天」，存盘即过期。 */
    suspend fun open(id: String, today: LocalDate = LocalDate.now()): LoadedBoard? {
        val idx = library.readIndex()
        val meta = idx.meta(id) ?: return null
        val content = library.readContent(id)
        return LoadedBoard(meta, buildTimetable(meta, content, today), content)
    }

    suspend fun openActive(today: LocalDate = LocalDate.now()): LoadedBoard? =
        library.readIndex().effectiveActiveId?.let { open(it, today) }

    /**
     * 各课表的课程数。索引与内容分开存（见 [BoardMeta]），故逐一读内容文件；
     * 内容仅数 KB，不值得为省 IO 冗余进索引，两处真相必然漏同步。
     */
    suspend fun courseCounts(): Map<String, Int> {
        val idx = library.readIndex()
        return idx.boards.associate { it.id to library.readContent(it.id).courses.size }
    }

    // ------------------------------------------------------------ 切换 / 改名 / 删除

    suspend fun activate(id: String) {
        val idx = library.readIndex()
        if (idx.meta(id) == null) return
        library.writeIndex(idx.copy(activeId = id))
    }

    suspend fun rename(id: String, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        val idx = library.readIndex()
        val old = idx.meta(id) ?: return
        library.writeIndex(
            idx.copy(
                boards = idx.boards.map {
                    if (it.id == id) old.copy(name = clean, updatedAt = now()) else it
                }
            )
        )
    }

    /** 删一张课表：元信息 + 内容一起删；若删的是当前选中，自动切到剩下的第一张。 */
    suspend fun remove(id: String) {
        val idx = library.readIndex()
        if (idx.meta(id) == null) return
        val rest = idx.boards.filterNot { it.id == id }
        library.deleteContent(id)
        library.writeIndex(
            idx.copy(
                boards = rest,
                activeId = if (idx.activeId == id) rest.firstOrNull()?.id else idx.activeId,
            )
        )
    }

    /** 复制一张课表。内容数据类不可变，修改即生成新对象，故无需逐条 clone。 */
    suspend fun duplicate(id: String, newName: String? = null): String? {
        val idx = library.readIndex()
        val src = idx.meta(id) ?: return null
        val newId = library.newId()
        val copy = src.copy(
            id = newId,
            name = newName?.trim()?.takeIf { it.isNotEmpty() } ?: "${src.name} 副本",
            source = BoardSource.COPY,
            createdAt = now(),
            updatedAt = now(),
        )
        val content = library.readContent(id)
        library.writeContent(newId, content)
        library.writeIndex(idx.copy(boards = idx.boards + copy, activeId = idx.activeId))
        return newId
    }

    // ------------------------------------------------------------ 导入 / 新建

    /**
     * 从教务查一份课表（先不落盘），供导入页展示摘要后确认写入。
     *
     * 每次先刷新一遍开学日：用户可能刚在设置里改过，而这个值直接决定「今天第几周」。
     *
     * @throws NotLoggedInException 会话失效，UI 应弹登录页
     * @throws TimetableException 学期查不到 / 网络出错
     */
    suspend fun fetchTimetable(termCode: String?): Timetable {
        semesterStart.refresh()
        return source.load(termCode)
    }

    /**
     * 当前学期代码，如 `2026-2027-1`。拿不到返回 null。
     *
     * 与 [currentTermName] 共用同一份学期列表缓存，所以连着调这两个**只会打一次接口**——
     * 而「两个都要」正是导入页的常态。
     */
    suspend fun currentTermCode(): String? = source.currentSemester()?.termCode

    /** 当前学期显示名，如 `2026-2027学年1学期` */
    suspend fun currentTermName(): String? = source.currentSemester()?.displayName

    /**
     * 把 [fetchTimetable] 得到的课表存为本地课表。
     *
     * @param name 空则用学期名，再空用学期代码
     * @param firstMonday 用户填的第一周周一；null = 沿用 [timetable] 里那个（服务端兜底值）。
     *   填了就顺带记进学期设置，这个学期以后再次导入会直接取用，不必让用户重填。
     * @return 新建课表 id
     */
    suspend fun saveImported(
        timetable: Timetable,
        name: String,
        termName: String? = null,
        firstMonday: LocalDate? = null,
        campus: PeriodTimes.Campus = PeriodTimes.Campus.DEFAULT,
    ): String {
        val idx = library.readIndex()
        val id = library.newId()

        // 学期名优先用用户填的，其次服务端给的，最后退成学期代码
        val resolvedTerm = timetable.term.copy(
            termName = termName?.trim()?.takeIf { it.isNotEmpty() }
                ?: timetable.term.termName.ifBlank { timetable.term.termCode },
            firstMonday = firstMonday ?: timetable.term.firstMonday,
        )

        val finalName = name.trim().takeIf { it.isNotEmpty() }
            ?: resolvedTerm.termName
        val unique = uniqueName(finalName, idx.boards.map { it.name })

        val meta = BoardMeta(
            id = id,
            name = unique,
            source = BoardSource.EHALL,
            term = resolvedTerm,
            campus = campus.name,
            // 不写 schedule（空 = 跟随该校区预设）：照抄 11 行会把作息钉成"自定义"，
            // 学校日后调整该校区作息就再也跟不上。
            schedule = emptyList(),
            sourceTermCode = resolvedTerm.termCode,
            lastImportAt = now(),
            createdAt = now(),
            updatedAt = now(),
        )

        library.writeContent(
            id,
            BoardContent(timetable.courses, timetable.sessions, timetable.unplaced),
        )
        library.writeIndex(idx.copy(boards = idx.boards + meta, activeId = id))
        // 只在用户**明确填过**时才记：没填时那个值是兜底（"本周即第 1 周"），
        // 把兜底当作用户的意思存下来，下次会以讹传讹。
        if (firstMonday != null) settings.setSemesterStart(resolvedTerm.termCode, firstMonday)
        return id
    }

    /**
     * 新建空白课表（自建），内容为空，课程由用户在 App 内添加。
     * @param firstMonday 第一周周一，自建课表也需它才能推算周次。
     * @param campus 校区，决定 [schedule] 为空时跟随哪套作息预设。
     * @param schedule 自定义作息；**空 = 跟随 [campus] 那套预设**（沿用他表时用 [settingsOf] 取来再传入）。
     * @param copiedFrom 作息复制来源课表名（仅展示）。
     */
    suspend fun createBlank(
        name: String,
        firstMonday: LocalDate,
        totalWeeks: Int,
        lastTeachingWeek: Int = totalWeeks,
        morningPeriods: Int = 4,
        afternoonPeriods: Int = 4,
        eveningPeriods: Int = 3,
        campus: PeriodTimes.Campus = PeriodTimes.Campus.DEFAULT,
        schedule: List<PeriodTime> = emptyList(),
        copiedFrom: String? = null,
    ): String {
        val idx = library.readIndex()
        val id = library.newId()
        val label = name.trim().takeIf { it.isNotEmpty() } ?: "我的课表"

        val term = TermContext(
            // 前缀为哨兵而非可见学期名（见 MANUAL_TERM_PREFIX）；有此前缀即标记该表不可手动同步。
            termCode = MANUAL_TERM_PREFIX + id,
            termName = label,
            firstMonday = firstMonday,
            totalWeeks = totalWeeks,
            lastTeachingWeek = lastTeachingWeek,
            morningPeriods = morningPeriods,
            afternoonPeriods = afternoonPeriods,
            eveningPeriods = eveningPeriods,
        )

        val meta = BoardMeta(
            id = id,
            name = uniqueName(label, idx.boards.map { it.name }),
            source = BoardSource.MANUAL,
            term = term,
            campus = campus.name,
            schedule = schedule,
            copiedFrom = copiedFrom,
            createdAt = now(),
            updatedAt = now(),
        )

        library.writeContent(id, BoardContent.EMPTY)
        library.writeIndex(idx.copy(boards = idx.boards + meta, activeId = id))
        return id
    }

    // ------------------------------------------------------------ 内容修改

    /** 整份内容替换，单点修改（改课 / 删课 / 加课）均走它。内容即一个 JSON，整份写回可避免并发交错丢更新。 */
    suspend fun saveContent(id: String, content: BoardContent) {
        val idx = library.readIndex()
        if (idx.meta(id) == null) return
        library.writeContent(id, content)
        touch(id)
    }

    /** 更新（或新增）一门课。id 不存在则追加。 */
    suspend fun upsertCourse(id: String, course: Course) {
        val content = library.readContent(id)
        val exists = content.courses.any { it.id == course.id }
        val courses = if (exists) {
            content.courses.map { if (it.id == course.id) course else it }
        } else {
            content.courses + course
        }
        saveContent(id, content.copy(courses = courses))
    }

    /** 一站式保存「一门课 + 其全部时间块」，分开调用会留下「课已存、块未存」的中间态。 */
    suspend fun saveCourseWithSessions(
        id: String,
        course: Course,
        sessions: List<CourseSession>,
    ) {
        val content = library.readContent(id)
        val courses = if (content.courses.any { it.id == course.id }) {
            content.courses.map { if (it.id == course.id) course else it }
        } else {
            content.courses + course
        }
        val kept = content.sessions.filterNot { it.courseId == course.id }
        saveContent(id, content.copy(courses = courses, sessions = kept + sessions))
    }

    /** 删一门课，连同它的所有时间块（只清块会留下一个永远不显示的幽灵课程）。 */
    suspend fun deleteCourse(id: String, courseId: String) {
        val content = library.readContent(id)
        saveContent(
            id,
            content.copy(
                courses = content.courses.filterNot { it.id == courseId },
                sessions = content.sessions.filterNot { it.courseId == courseId },
            )
        )
    }

    /** 只删一个时间块（同一门课还有别的时段时用） */
    suspend fun deleteSession(id: String, sessionId: String) {
        val content = library.readContent(id)
        saveContent(id, content.copy(sessions = content.sessions.filterNot { it.id == sessionId }))
    }

    // ------------------------------------------------------------ 学期骨架

    /**
     * 修改自建课表学期骨架（第一周周一 / 总周数 / 最后教学周 / 节次分组）。
     * 第一周周一是时间原点，填错会整体偏移日期且界面无异常，故必须可改；
     * 导入课表的骨架来自 `cxjcs.do`，属服务端权威值，本地改即假数据。
     * @return 非自建或找不到返回 false。
     */
    suspend fun saveTermConfig(
        id: String,
        firstMonday: LocalDate,
        totalWeeks: Int,
        lastTeachingWeek: Int,
        morningPeriods: Int,
        afternoonPeriods: Int,
        eveningPeriods: Int,
    ): Boolean {
        val idx = library.readIndex()
        val old = idx.meta(id) ?: return false
        if (old.source != BoardSource.MANUAL) return false

        val updated = old.copy(
            term = old.term.copy(
                firstMonday = firstMonday,
                totalWeeks = totalWeeks,
                lastTeachingWeek = lastTeachingWeek.coerceIn(1, totalWeeks),
                morningPeriods = morningPeriods,
                afternoonPeriods = afternoonPeriods,
                eveningPeriods = eveningPeriods,
            ),
            updatedAt = now(),
        )
        library.writeIndex(idx.copy(boards = idx.boards.map { if (it.id == id) updated else it }))
        return true
    }

    /**
     * 修改**第一周周一**（整张课表的时间原点），并把这天记进学期设置。
     *
     * ## 为什么不受 [saveTermConfig] 那道「仅自建课表」的限制
     *
     * 那道限制的理由是"导入课表的骨架来自服务端，本地改即假数据"——它对旧学校的接口成立
     * （那里确实下发学期起始日期），**对长安大学不成立**：EAMS 没有任何接口给出学期起止日期
     * （技能 §9.2）。导入表的 `firstMonday` 同样只是个兜底值（"把本周当第 1 周"），
     * 不修正就让整张表的周次静默偏掉，比"本地改"错得多。
     *
     * ## 为什么连同类学期的其它课表一起改
     *
     * 开学日是**学期**的属性，不是某张课表的属性：两张同学期的表给出不同周次是自相矛盾，
     * 而非"互不影响的独立设置"。（作息则相反，按表存是有意的——见 [savePeriod]。）
     *
     * 同时写进 [SettingsStore]：这张表立即生效，且该学期**以后再次导入/同步**时直接取用
     * （[StoredSemesterStart] 读的就是它），不必让用户重填一遍。
     */
    suspend fun setFirstMonday(id: String, firstMonday: LocalDate) {
        val idx = library.readIndex()
        val target = idx.meta(id) ?: return
        val termCode = target.term.termCode

        library.writeIndex(
            idx.copy(
                boards = idx.boards.map {
                    // 自建表的 termCode 是 `manual-xxx` 哨兵，天然不会与真实学期撞上
                    if (it.id == id || (termCode.isNotBlank() && it.term.termCode == termCode)) {
                        it.copy(term = it.term.copy(firstMonday = firstMonday), updatedAt = now())
                    } else {
                        it
                    }
                }
            )
        )

        if (termCode.isNotBlank() && !termCode.startsWith(MANUAL_TERM_PREFIX)) {
            settings.setSemesterStart(termCode, firstMonday)
        }
    }

    // ------------------------------------------------------------ 作息时间

    /**
     * 保存**校区 + 作息**——这两件事必须一起写。
     *
     * 校区决定"跟随哪套预设"，而"是否自定义"由 [custom] 是否为空表示；
     * 分两次写会留下"校区已经换了、作息还指着旧那套"的中间态（进程被杀就真落盘了）。
     *
     * 仅改 [id] 这一张，其它课表不动；沿用他表时用 [settingsOf] 取来再调用，复制即一次性独立。
     *
     * @param custom 自定义的那些行；**传 null 或空表示跟随 [campus] 的预设**，
     *   而不是"没有作息"——学校日后调整该校区作息时它会自动跟上。
     * @param copiedFrom 复制来源课表名（仅展示）；跟随预设时无来源可言，会被清空。
     */
    suspend fun savePeriod(
        id: String,
        campus: PeriodTimes.Campus,
        custom: List<PeriodTime>?,
        copiedFrom: String? = null,
    ) {
        val idx = library.readIndex()
        val old = idx.meta(id) ?: return
        val times = custom.orEmpty()
        library.writeIndex(
            idx.copy(
                boards = idx.boards.map {
                    if (it.id == id) {
                        old.copy(
                            campus = campus.name,
                            schedule = times,
                            copiedFrom = if (times.isEmpty()) null else copiedFrom,
                            updatedAt = now(),
                        )
                    } else {
                        it
                    }
                }
            )
        )
    }

    /**
     * 是否在周网格里显示非本周课程（半透明影子块）。
     * 刻意做成独立写入而非并入设置页的「保存」：它属于预览性质的开关，
     * 用户预期点了立即生效，要求再按保存会让人以为开关坏了。
     */
    suspend fun setShowOutOfWeek(id: String, value: Boolean) {
        val idx = library.readIndex()
        if (idx.meta(id) == null) return
        library.writeIndex(
            idx.copy(
                boards = idx.boards.map {
                    if (it.id == id) it.copy(showOutOfWeek = value, updatedAt = now()) else it
                }
            )
        )
    }

    /**
     * 读一张课表的可复制设置，供「从其它课表导入设置」用。
     *
     * 不只返回作息数值，还要带出「来源是否在用预设」与**哪个校区**：
     * 来源用预设时 [SettingsSnapshot.scheduleToApply] 返回 null，新表同样记为「跟随那套预设」，
     * 以后学校调整作息两张表会一起跟上；来源自定义过才真抄那些数值。
     * 若一律抄数值，复制出来的表都会变成「自定义」而与学校默认脱钩。
     */
    suspend fun settingsOf(id: String): SettingsSnapshot? {
        val meta = library.readIndex().meta(id) ?: return null
        return SettingsSnapshot(
            boardName = meta.name,
            custom = meta.hasCustomSchedule,
            campus = meta.campusOf,
            schedule = meta.periodSchedule.times,
            morningPeriods = meta.term.morningPeriods,
            afternoonPeriods = meta.term.afternoonPeriods,
            eveningPeriods = meta.term.eveningPeriods,
        )
    }

    // ------------------------------------------------------------ 手动同步

    /**
     * 手动从教务重新拉一个学期，覆盖这张课表的课程数据。
     *
     * 必须由用户显式触发：课表是本机资产，用户改过的内容（改名、挪节次、删课）即唯一真相，
     * 自动同步会将其静默冲掉，表现为「我改的东西自己变回去了」。
     * 覆盖时按课程配对保留教务不提供的本地属性（[Course.colorOverride] / [Course.credit] /
     * [Course.note]），否则用户挑的颜色会在一次同步后无提示地变回自动分配。
     * 配对键先按 [Course.id]，再退到 `课程号 + 课序号`——教务重排教学班时 id 会变。
     *
     * 学期骨架里**只有总周数来自服务端**（骨架页的 `startWeek` 下拉，或位图里出现过的最大周次）；
     * 第一周周一来自用户设置（见 [StoredSemesterStart]），因为教务接口根本不提供学期起止日期。
     * 作息时间同样不动（接口不提供时刻），课表名也不改（那是用户起的）。
     */
    suspend fun syncFromServer(boardId: String): SyncOutcome {
        val idx = library.readIndex()
        val meta = idx.meta(boardId) ?: return SyncOutcome.Failed("这张课表已经不存在了。")

        val termCode = meta.syncTermCode
            ?: return SyncOutcome.NotSyncable(
                "「${meta.name}」是自建课表，教务系统没有对应的学期可以拉取。"
            )

        val fresh = try {
            semesterStart.refresh()
            source.load(termCode)
        } catch (e: NotLoggedInException) {
            // 交给 UI 去弹登录页——同步是个用户显式发起的动作，值得为它走一次认证
            return SyncOutcome.NeedLogin
        } catch (e: Exception) {
            return SyncOutcome.Failed(e.message ?: (e::class.simpleName ?: "未知错误"))
        }

        val old = library.readContent(boardId)
        val oldById = old.courses.associateBy { it.id }
        val oldByKey = old.courses
            .filter { it.courseCode.isNotBlank() }
            .associateBy { it.courseCode to it.classNo }

        val merged = fresh.courses.map { c ->
            val prev = oldById[c.id] ?: oldByKey[c.courseCode to c.classNo]
            if (prev == null) {
                c
            } else {
                c.copy(
                    colorOverride = prev.colorOverride,
                    credit = prev.credit,
                    note = prev.note,
                )
            }
        }

        // 顺序同所有写操作：先内容、后索引（见类注释）
        library.writeContent(
            boardId,
            BoardContent(merged, fresh.sessions, fresh.unplaced),
        )

        val updated = meta.copy(
            term = meta.term.copy(
                // 学期骨架以服务端为准，但学期名保留用户/首次导入时定下的那个
                termName = meta.term.termName.ifBlank { fresh.term.termName },
                firstMonday = fresh.term.firstMonday,
                totalWeeks = fresh.term.totalWeeks,
                lastTeachingWeek = fresh.term.lastTeachingWeek,
                morningPeriods = fresh.term.morningPeriods,
                afternoonPeriods = fresh.term.afternoonPeriods,
                eveningPeriods = fresh.term.eveningPeriods,
            ),
            lastImportAt = now(),
            updatedAt = now(),
        )
        library.writeIndex(
            idx.copy(boards = idx.boards.map { if (it.id == boardId) updated else it })
        )

        return SyncOutcome.Success(
            courseCount = merged.size,
            sessionCount = fresh.sessions.size,
            unplacedCount = fresh.unplaced.size,
            changed = old.courses.size != merged.size || old.sessions.size != fresh.sessions.size,
        )
    }

    // ------------------------------------------------------------ 内部

    /** 更新 `updatedAt`。读写磁盘都是挂起操作，所以这里也必须是 suspend。 */
    private suspend fun touch(id: String) {
        val idx = library.readIndex()
        val old = idx.meta(id) ?: return
        library.writeIndex(
            idx.copy(
                boards = idx.boards.map { if (it.id == id) old.copy(updatedAt = now()) else it }
            )
        )
    }

    private fun now(): Long = System.currentTimeMillis()

    private fun buildTimetable(meta: BoardMeta, content: BoardContent, today: LocalDate): Timetable =
        Timetable(
            term = meta.term,
            courses = content.courses,
            sessions = content.sessions,
            unplaced = content.unplaced,
            // 学期还没开始（负数/0）时夹到第 1 周，否则周次切换器会显示"第 0 周"
            currentWeek = meta.term.weekOf(today).coerceAtLeast(1),
        )

    companion object {
        /** 同名就加 `(2)`、`(3)`…——两张同名课表在列表中无法区分 */
        internal fun uniqueName(base: String, taken: List<String>): String {
            if (base !in taken) return base
            var n = 2
            while ("$base ($n)" in taken) n++
            return "$base ($n)"
        }
    }
}

/**
 * 打开一张课表的结果：元信息 + 运行时课表 + 原始内容。
 * 内容一并带出是因为编辑页要基于最新的 content 修改，再读一次磁盘既多余也可能读到更新的版本。
 */
data class LoadedBoard(
    val meta: BoardMeta,
    val timetable: Timetable,
    val content: BoardContent,
)

/** 手动同步的结果。UI 按它决定"提示成功 / 弹登录页 / 报错"。 */
sealed interface SyncOutcome {

    /**
     * @param courseCount 同步后这张课表有几门课
     * @param changed `false` 表示内容与本地一致，UI 应提示「已是最新」而非笼统的「同步成功」。
     */
    data class Success(
        val courseCount: Int,
        val sessionCount: Int,
        val unplacedCount: Int,
        val changed: Boolean,
    ) : SyncOutcome

    /** 会话失效 —— 调用方该弹登录页，登录成功后可以重试这一次同步 */
    data object NeedLogin : SyncOutcome

    /** 这张课表不能同步（自建课表 / 学期代码不可用） */
    data class NotSyncable(val message: String) : SyncOutcome

    data class Failed(val message: String) : SyncOutcome
}

/**
 * 一张课表里可被复制过去的那部分设置。
 *
 * 只含校区、作息与节次分组，不含第一周周一 / 总周数——后两者属于「这个学期」，
 * 换一张课表就是另一个学期，抄过去反而是错的。
 *
 * 用法：`repo.savePeriod(目标id, snap.campus, snap.scheduleToApply, snap.copiedFromLabel)`。
 */
data class SettingsSnapshot(
    val boardName: String,
    /** 来源那张表是否自定义过作息 */
    val custom: Boolean,
    /**
     * 来源的校区。**必须随作息一起抄**：作息值与校区本就绑定，
     * 只抄时刻不抄校区会得到"渭水的时刻表 + 南校区的标签"这种自相矛盾的组合。
     */
    val campus: PeriodTimes.Campus,
    /** 生效的作息值（来源没自定义时就是该校区预设的 11 行，供界面预览） */
    val schedule: List<PeriodTime>,
    val morningPeriods: Int,
    val afternoonPeriods: Int,
    val eveningPeriods: Int,
) {
    /**
     * 应写进目标课表的**自定义**作息。来源在用预设时返回 null（而非 11 行数值）：
     * null 的语义是「跟随 [campus] 那套预设」，两张表以后会一起跟上学校调整。
     */
    val scheduleToApply: List<PeriodTime>? get() = if (custom) schedule else null

    /** 写进 [BoardMeta.copiedFrom] 的来源名；用预设时没有"抄自哪里"可言，返回 null */
    val copiedFromLabel: String? get() = if (custom) boardName else null
}
