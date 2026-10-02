package com.stupidtree.hitax.data.source.web.sysu

import android.text.TextUtils
import com.stupidtree.hitax.data.model.eas.CourseItem
import com.stupidtree.hitax.data.model.eas.EASToken
import com.stupidtree.hitax.data.model.eas.TermItem
import com.stupidtree.hitax.data.model.timetable.TimePeriodInDay
import com.stupidtree.hitax.data.model.timetable.Timetable
import org.json.JSONArray
import org.json.JSONObject

/**
 * 教务系统「爬虫」数据源（v1.0.5 需求 3）
 *
 * 目标：把学生关心的教学资料抓到本地 ——
 * 培养方案、课程教学大纲、任课教师信息、各课程考试日期，
 * 外加学生信息 / 成绩 / 课表原始数据。
 *
 * 设计原则：
 * 1. **不猜接口**。中大教务（jwxt，Wisedu 系）的菜单 code 会随版本变化，
 *    所以每个资源都准备了一组候选入口（candidate），逐个尝试并把
 *    「试了哪个、结果如何」如实写进日志 —— 用户能看到爬虫到底做了什么，
 *    教务系统不提供或需要权限的项也能明确失败，而不是静默返回空。
 * 2. **能落地的先落地**。学生信息、课表、考试、成绩这些接口是 v1.0.1 起就在用的，
 *    一定可用；培养方案 / 教学大纲走「先试 JSON 接口，再退化为抓 HTML 页面文本」。
 * 3. **本类不碰存储**。抓到的内容装在 [Result.saves] 里返回，
 *    由 [com.stupidtree.hitax.data.repository.CrawlerStorage] 落盘，
 *    这样本类仍然可以在 JVM harness 里直接跑（只需要一个 mock 服务）。
 */
class SysuCrawler(
    private val token: EASToken,
    private val host: String = SysuApi.HOST,
    private val logger: (String) -> Unit = {}
) {

    // ------------------------------------------------------------------
    // 资源描述
    // ------------------------------------------------------------------

    /** 一个可爬取的资源 */
    data class Source(
        val key: String,
        val category: String,
        val title: String
    )

    enum class State { PENDING, RUNNING, SUCCESS, FAILED, SKIPPED }

    /** 待落盘的一份内容 */
    class PendingSave(
        val category: String,
        val name: String,
        val content: String,
        val summary: String,
        val sourceUrl: String?,
        val raw: Boolean
    )

    /** 一个资源的爬取结果 */
    class Result(val source: Source) {
        var state: State = State.PENDING
        var message: String = ""
        var itemCount: Int = 0
        val saves: MutableList<PendingSave> = mutableListOf()

        val savedFiles: Int get() = saves.size
    }

    class NotLoggedIn : Exception("未登录或会话已过期")

    companion object {
        const val CAT_PROFILE = "profile"
        const val CAT_SYLLABUS = "syllabus"
        const val CAT_TEACHER = "teacher"
        const val CAT_EXAM = "exam"
        const val CAT_SCORE = "score"
        const val CAT_TIMETABLE = "timetable"

        /** 爬取顺序：先拿身份与课程，后面的资源才能带上下文 */
        val SOURCES: List<Source> = listOf(
            Source("student", CAT_PROFILE, "学生信息"),
            Source("timetable", CAT_TIMETABLE, "课表原始数据"),
            Source("teacher", CAT_TEACHER, "任课教师信息"),
            Source("exam", CAT_EXAM, "各课程考试日期"),
            Source("score", CAT_SCORE, "成绩与学分"),
            Source("training_plan", CAT_PROFILE, "本专业培养方案"),
            Source("syllabus", CAT_SYLLABUS, "课程教学大纲")
        )

        /**
         * 培养方案查询的候选入口。
         *
         * v1.0.7：这些路径是**从前端 bundle 里挖出来的**，不是猜的。
         * jwxt 的每个功能其实是一个独立微应用（`/jwxt/mk/<app>/`），培养方案属于
         * `training-programe` 模块（注意官方拼写就是 `training-programe`，
         * 而且有重复的 `training-programe/training-programe/` 前缀，**不要"顺手改对"**）：
         *
         * - `undergradute/student/personalMainProgram`：**无参数**，身份取自会话，
         *   返回 `{COLLEGE, GRADE, TEACHPLANNUMBER, STUDENTNUMBER, PROFESSIONNAME, PROFESSIONCODE}`（全大写）；
         * - `undergradute/baseinfo/left`：培养方案树，需要 `programType=student` 才是本人的方案；
         * - `courseSetting/pageList` 等以 `trainingProgramId` 为键的明细。
         */
        val TRAINING_PLAN_PATHS = listOf(
            "/training-programe/training-programe/undergradute/student/personalMainProgram",
            "/training-programe/training-programe/undergradute/baseinfo/left",
            "/training-programe/training-programe/undergradute/student/list"
        )

        /**
         * 教学大纲查询的候选入口（同样来自 bundle 证据）
         *
         * 入口在**选课微应用**的「已选课程」页：点课程号会调
         * `courseoutline/getalloutlineinfo?courseNum=&auditStatus=99`，
         * **大纲正文要再调一次** `courseoutline/outlinedetailbycourseid?courseId=`；
         * 只调第一个接口拿不到正文。
         *
         * `auditStatus=99` 是前端写死的（教研科最终通过），否则会提示
         * 「该课程教学大纲尚未在系统内完成提交审核」。
         */
        val SYLLABUS_PATHS = listOf(
            "/training-programe/courseoutline/getalloutlineinfo",
            "/training-programe/courseoutline/outlinedetailbycourseid",
            "/training-programe/courseoutline/showOutlineUpdataCourse"
        )

        /** 选课微应用的「已选课程」数据源（用于拿到 courseNum / courseId） */
        const val PATH_SELECTED_COURSES = "/choose-course-front-server/selectedCourse/list"

        /** 教学大纲审核状态：99 = 教研科通过（前端写死值） */
        const val OUTLINE_AUDIT_PASSED = "99"

        /** 培养方案树里标识「学生本人方案」的参数值 */
        const val PROGRAM_TYPE_STUDENT = "student"

        /**
         * 教学大纲兜底页面（抓 HTML 文本）
         *
         * 注意：v1.0.7 起真实入口是**选课微应用的「已选课程」页**
         * （`/jwxt/mk/courseSelection/`，SPA 的 hash 路由抓不到内容），
         * 所以这里只用真实可取的页面，抓不到就把失败原因写进日志。
         */
        val SYLLABUS_PAGES = listOf(
            "/mk/courseSelection/",
            "/course-info/syllabus"
        )

        /** 培养方案兜底页面（同上，SPA 路由不作为抓取目标） */
        val TRAINING_PLAN_PAGES = listOf(
            "/mk/",
            "/training-programe/training-programe/undergradute/student/personalMainProgram"
        )

        const val DEFAULT_WEEKS = 20

        /** 培养方案明细最多抓几个方案（防止树很大时请求过多） */
        const val MAX_PLAN_DETAILS = 6

        /** 其它 Wisedu 版本教务的培养方案入口（逐个兜底，不是主路径） */
        val LEGACY_TRAINING_PLAN_PATHS = listOf(
            "/training-program/trainingProgram/queryTrainingProgram",
            "/training-program/trainingProgram/list",
            "/training-program/queryTrainingProgram",
            "/cultivate-plan/cultivatePlan/queryPlan"
        )

        /** 其它 Wisedu 版本教务的教学大纲入口（逐个兜底，不是主路径） */
        val LEGACY_SYLLABUS_PATHS = listOf(
            "/course-info/courseOutline/list",
            "/teaching-task/courseOutline/list",
            "/base-info/course-outline/list"
        )
    }

    private val api = SysuApi(token.cookies).also { it.hostOverride = host }

    /**
     * 内部复用的 [SysuWebSource]
     *
     * **必须带上 host**：否则它会连生产环境的 jwxt（离线测试时就会变成「会话过期」）。
     */
    private fun webSource(): SysuWebSource = SysuWebSource().also { it.host = host }

    /** 抓取上下文（后续资源需要用到） */
    private var studentInfo: JSONObject? = null
    private var courses: List<CourseItem> = emptyList()
    private var termCode: String = ""
    private var structure: List<TimePeriodInDay> = Timetable().getDefaultTimeStructure()

    /**
     * 指定学期。导入课表后自动爬取时，用户关心的是刚导入的那个学期，
     * 而不是教务接口返回的「当前学期」。
     */
    fun preferTerm(code: String?) {
        if (!code.isNullOrBlank()) termCode = code
    }

    fun getStudentInfo(): JSONObject? = studentInfo

    /** 对外暴露日志（UI 也可以直接往日志窗口里写） */
    fun log(message: String) {
        logger(message)
    }

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    /**
     * 逐个爬取全部资源。阻塞式，请在子线程调用。
     *
     * @param onProgress 每个资源「开始 / 结束」时回调，用于刷新进度与日志窗口
     * @param shouldStop 返回 true 时跳过后续资源（用户点了「停止」）
     */
    fun crawlAll(
        onProgress: (Result, List<Result>) -> Unit = { _, _ -> },
        shouldStop: () -> Boolean = { false }
    ): List<Result> {
        log("=== 开始爬取教务信息 ===")
        if (api.loginStatus() != 1) {
            log("!! 会话无效或已过期，请重新登录教务")
            throw NotLoggedIn()
        }
        val results = SOURCES.map { Result(it) }
        for ((index, r) in results.withIndex()) {
            if (shouldStop()) {
                r.state = State.SKIPPED
                r.message = "用户已停止"
                onProgress(r, results)
                continue
            }
            r.state = State.RUNNING
            onProgress(r, results)
            log("--- [${index + 1}/${SOURCES.size}] ${r.source.title} ---")
            try {
                runSource(r)
                if (r.state == State.RUNNING) r.state = State.SUCCESS
            } catch (e: NotLoggedIn) {
                r.state = State.FAILED
                r.message = "会话已过期"
                log("!! 会话已过期，停止后续爬取")
                onProgress(r, results)
                break
            } catch (e: Exception) {
                r.state = State.FAILED
                r.message = e.message ?: e.javaClass.simpleName
                log("!! ${r.source.title} 失败：${r.message}")
            }
            if (r.state == State.RUNNING) r.state = State.SUCCESS
            onProgress(r, results)
        }
        val ok = results.count { it.state == State.SUCCESS }
        log("=== 爬取结束：成功 $ok 项，失败 ${results.count { it.state == State.FAILED }} 项 ===")
        return results
    }

    private fun runSource(r: Result) {
        when (r.source.key) {
            "student" -> crawlStudent(r)
            "timetable" -> crawlTimetable(r)
            "teacher" -> crawlTeachers(r)
            "exam" -> crawlExams(r)
            "score" -> crawlScores(r)
            "training_plan" -> crawlTrainingPlan(r)
            "syllabus" -> crawlSyllabus(r)
            else -> {
                r.state = State.SKIPPED
                r.message = "未知资源"
            }
        }
    }

    // ------------------------------------------------------------------
    // 1) 学生信息
    // ------------------------------------------------------------------

    private fun crawlStudent(r: Result) {
        var obj = api.get("/base-info/student-info/getStudentInfo")
        if (obj == null || !hasData(obj)) {
            log("  · /base-info/student-info/getStudentInfo 无数据，退回 /api/privilege")
            obj = api.get("/api/privilege")
        }
        val data = obj?.optJSONObject("data") ?: obj
        if (data == null || data.length() == 0) {
            r.state = State.FAILED
            r.message = "接口未返回学生信息"
            log("  !! 两个候选接口都没有返回内容")
            return
        }
        studentInfo = data
        val name = firstOf(data, "userName", "xm", "name", "studentName") ?: token.name
        val major = firstOf(data, "majorName", "zymc", "specialityName", "professionName") ?: token.major
        val faculty = firstOf(data, "facultyName", "yxmc", "collegeName", "departmentName") ?: token.school
        log("  · 姓名=$name 专业=${major ?: "-"} 学院=${faculty ?: "-"}")
        r.itemCount = 1
        r.message = "姓名=$name，专业=${major ?: "未知"}"
        r.saves.add(
            PendingSave(
                CAT_PROFILE, "学生信息", data.toString(), r.message,
                "/base-info/student-info/getStudentInfo", false
            )
        )
    }

    // ------------------------------------------------------------------
    // 2) 课表原始数据（后续教师 / 大纲都要用）
    // ------------------------------------------------------------------

    private fun crawlTimetable(r: Result) {
        val term = termCode.ifEmpty { resolveTerm() }
        termCode = term
        log("  · 学期代码：${term.ifEmpty { "未知" }}")
        val src = webSource()
        val weeks = try {
            src.fetchTotalWeeks(token, termItemOf(term)).let { if (it > 0) it else DEFAULT_WEEKS }
        } catch (e: Exception) {
            DEFAULT_WEEKS
        }
        log("  · 总周数：$weeks")

        val all = ArrayList<CourseItem>()
        val rawByWeek = JSONObject()
        var emptyStreak = 0
        for (week in 1..weeks) {
            val obj = api.studentClassTable(term, week)
            if (obj == null) {
                log("  · 第 $week 周：请求失败")
                emptyStreak++
                if (emptyStreak >= 3) break
                continue
            }
            val arr = pickArray(obj)
            rawByWeek.put("week$week", arr ?: JSONArray())
            val parsed = SysuTimetableParser.parseWeek(arr, week)
            if (parsed.isEmpty()) {
                emptyStreak++
                if (emptyStreak >= 3) {
                    log("  · 连续 3 周无课，判定学期结束（已抓 $week 周）")
                    break
                }
            } else {
                emptyStreak = 0
                all.addAll(parsed)
                log("  · 第 $week 周：${parsed.size} 条")
            }
        }
        courses = SysuTimetableParser.merge(all)
        log("  · 合并后共 ${courses.size} 门课")
        if (rawByWeek.length() == 0) {
            r.state = State.FAILED
            r.message = "课表接口无响应"
            return
        }
        r.itemCount = courses.size
        r.message = "${courses.size} 门课 / ${rawByWeek.length()} 周"
        r.saves.add(
            PendingSave(
                CAT_TIMETABLE, "课表原始数据", rawByWeek.toString(), r.message,
                "/timetable-search/classTableInfo/selectStudentClassTable", false
            )
        )
    }

    // ------------------------------------------------------------------
    // 3) 任课教师信息
    // ------------------------------------------------------------------

    private fun crawlTeachers(r: Result) {
        if (courses.isEmpty()) {
            log("  · 课表为空，先行抓取课表")
            crawlTimetable(Result(SOURCES.first { it.key == "timetable" }))
        }
        if (courses.isEmpty()) {
            r.state = State.FAILED
            r.message = "课表为空，无法提取教师"
            return
        }
        val teachers = linkedMapOf<String, MutableSet<String>>()
        for (c in courses) {
            val t = c.teacher?.trim().orEmpty()
            if (t.isEmpty()) continue
            // 一个格子可能出现「张三,李四」
            for (one in t.split(',', '，', '/', '、')) {
                val key = one.trim()
                if (key.isEmpty()) continue
                teachers.getOrPut(key) { linkedSetOf() }.add(c.name ?: "")
            }
        }
        if (teachers.isEmpty()) {
            r.state = State.SKIPPED
            r.message = "课表里没有教师字段"
            log("  · 课表数据中没有 rkjs 字段，跳过")
            return
        }
        val arr = JSONArray()
        for ((name, cs) in teachers) {
            val o = JSONObject()
            o.put("name", name)
            val courseArr = JSONArray()
            for (c in cs) courseArr.put(c)
            o.put("courses", courseArr)
            arr.put(o)
            log("  · 教师 $name：${cs.joinToString("、")}")
        }
        r.itemCount = teachers.size
        r.message = "${teachers.size} 位教师"
        r.saves.add(
            PendingSave(CAT_TEACHER, "任课教师信息", arr.toString(), r.message, "课表数据聚合", false)
        )
    }

    // ------------------------------------------------------------------
    // 4) 考试日期
    // ------------------------------------------------------------------

    private fun crawlExams(r: Result) {
        val term = termCode.ifEmpty { resolveTerm() }
        termCode = term
        val items = try {
            SysuWebSource().also { it.host = host }.fetchExams(token, termItemOf(term))
        } catch (e: Exception) {
            log("  !! 考试接口异常：${e.message}")
            emptyList()
        }
        if (items.isEmpty()) {
            r.state = State.FAILED
            r.message = "未获取到考试安排（可能尚未发布）"
            log("  · 考试周 / 考试信息为空")
            return
        }
        val arr = JSONArray()
        for (e in items) {
            val o = JSONObject()
            o.put("courseName", e.courseName ?: "")
            o.put("examDate", e.examDate ?: "")
            o.put("examTime", e.examTime ?: "")
            o.put("examLocation", e.examLocation ?: "")
            o.put("examType", e.examType ?: "")
            o.put("campus", e.campusName ?: "")
            o.put("term", e.termName ?: term)
            arr.put(o)
            log("  · ${e.courseName} ${e.examDate ?: "日期待定"} ${e.examTime ?: ""} ${e.examLocation ?: ""}")
        }
        r.itemCount = items.size
        r.message = "${items.size} 场考试"
        r.saves.add(
            PendingSave(
                CAT_EXAM, "考试安排", arr.toString(), r.message,
                "/examination-manage/classroomResource/queryStuEaxmInfo", false
            )
        )
    }

    // ------------------------------------------------------------------
    // 5) 成绩
    // ------------------------------------------------------------------

    private fun crawlScores(r: Result) {
        val term = termCode.ifEmpty { resolveTerm() }
        val items = try {
            webSource().fetchScores(termItemOf(term), token)
        } catch (e: Exception) {
            log("  !! 成绩接口异常：${e.message}")
            emptyList()
        }
        if (items.isEmpty()) {
            r.state = State.FAILED
            r.message = "未获取到成绩"
            return
        }
        val arr = JSONArray()
        var totalCredit = 0.0
        for (s in items) {
            val o = JSONObject()
            o.put("courseName", s.courseName ?: "")
            o.put("courseCode", s.courseCode ?: "")
            o.put("credits", s.credits)
            o.put("score", s.finalScores)
            o.put("property", s.courseProperty ?: "")
            o.put("term", s.termName ?: "")
            arr.put(o)
            totalCredit += s.credits
        }
        log("  · 共 ${items.size} 条成绩，累计 ${totalCredit} 学分")
        r.itemCount = items.size
        r.message = "${items.size} 条成绩 / ${totalCredit} 学分"
        r.saves.add(
            PendingSave(
                CAT_SCORE, "成绩单", arr.toString(), r.message,
                "/achievement-manage/score-check/list", false
            )
        )
    }

    // ------------------------------------------------------------------
    // 6) 培养方案
    // ------------------------------------------------------------------

    private fun crawlTrainingPlan(r: Result) {
        val si = studentInfo ?: JSONObject()
        val major = firstOf(si, "majorName", "zymc", "specialityName") ?: token.major
        val grade = firstOf(si, "gradeName", "njmc", "grade") ?: token.grade
        val faculty = firstOf(si, "facultyName", "yxmc", "collegeName") ?: token.school
        log("  · 定位：学院=${faculty ?: "-"} 专业=${major ?: "-"} 年级=${grade ?: "-"}")

        // ---- 1) 个人培养方案总览：无需参数，身份取自会话（v1.0.7 从 bundle 证实）----
        val main = api.get("/training-programe/training-programe/undergradute/student/personalMainProgram")
        if (main != null && hasData(main)) {
            log("  · 命中「个人培养方案」接口（personalMainProgram）")
            val payload = JSONObject()
            payload.put("mainProgram", main)
            r.itemCount = 1
            r.message = "个人培养方案总览"

            // ---- 2) 培养方案树：programType=student 才是本人方案 ----
            val left = api.get(
                "/training-programe/training-programe/undergradute/baseinfo/left",
                mapOf(
                    "grade" to (grade ?: ""),
                    "professionCode" to firstOf(si, "professionCode", "zydm").orEmpty(),
                    "professionId" to firstOf(si, "professionId", "zyid").orEmpty(),
                    "programType" to PROGRAM_TYPE_STUDENT,
                    "stuNum" to (token.stuId ?: "")
                )
            )
            if (left != null && hasData(left)) {
                payload.put("planTree", left)
                log("  · 命中培养方案树（baseinfo/left，programType=student）")
                // ---- 3) 按培养方案 id 抓明细 ----
                val ids = collectTrainingProgramIds(left)
                if (ids.isNotEmpty()) {
                    log("  · 方案条目 ${ids.size} 个，逐个抓取课程设置")
                    val details = JSONArray()
                    for (id in ids.take(MAX_PLAN_DETAILS)) {
                        val body = JSONObject()
                        body.put("pageNo", 1)
                        body.put("pageSize", 1000)
                        val param = JSONObject()
                        param.put("trainingProgramId", id)
                        body.put("param", param)
                        val page = api.postJson(
                            "/training-programe/courseSetting/pageList", body.toString()
                        )
                        if (page != null && hasData(page)) {
                            val one = JSONObject()
                            one.put("trainingProgramId", id)
                            one.put("courses", page)
                            details.put(one)
                            log("  · 方案 $id 课程设置已获取")
                        } else {
                            log("  · 方案 $id 课程设置为空")
                        }
                    }
                    if (details.length() > 0) payload.put("courseSettings", details)
                } else {
                    log("  · 树里没有解析到 trainingProgramId，跳过明细")
                }
            } else {
                log("  · 培养方案树为空（code=${left?.opt("code") ?: "无响应"}）")
            }
            r.message = "个人培养方案（含课程设置）"
            r.saves.add(PendingSave(CAT_PROFILE, "培养方案", payload.toString(), r.message,
                "/training-programe/training-programe/undergradute/student/personalMainProgram", false))
            return
        }
        log("  · personalMainProgram 无数据（code=${main?.opt("code") ?: "无响应"}）")

        // ---- 退化：老路径逐个试（兼容其它版本的 Wisedu 教务）----
        val params = linkedMapOf(
            "academicYear" to termCode,
            "majorName" to (major ?: ""),
            "grade" to (grade ?: ""),
            "facultyName" to (faculty ?: "")
        )
        for (path in LEGACY_TRAINING_PLAN_PATHS) {
            val obj = try {
                api.get(path, params)
            } catch (e: Exception) {
                null
            }
            if (obj != null && hasData(obj)) {
                log("  · 命中兼容接口：$path")
                r.itemCount = 1
                r.message = "接口 $path"
                r.saves.add(PendingSave(CAT_PROFILE, "培养方案", obj.toString(), r.message, path, false))
                return
            }
            log("  · $path 无数据（code=${obj?.opt("code") ?: "无响应"}）")
        }
        for (p in TRAINING_PLAN_PAGES) {
            val page = tryFetchPage(p)
            if (page != null) {
                log("  · 接口均不可用，已保存培养方案页面文本（$p，${page.length} 字）")
                r.state = State.SUCCESS
                r.message = "接口不可用，已保存页面文本备用"
                r.saves.add(PendingSave(CAT_PROFILE, "培养方案页面", page, r.message, p, true))
                return
            }
        }
        r.state = State.FAILED
        r.message = "培养方案接口无数据（已尝试 personalMainProgram 与 " +
                "${LEGACY_TRAINING_PLAN_PATHS.size} 个兼容入口）"
        log("  !! 培养方案未取到数据；如果你在 PC 端能看到「个人培养方案查看」，请把日志发给作者")
    }

    /** 从培养方案树里宽松地收集 trainingProgramId（不同版本字段名不一致） */
    fun collectTrainingProgramIds(node: Any?, out: MutableList<String> = mutableListOf()): List<String> {
        when (node) {
            is JSONArray -> for (i in 0 until node.length()) collectTrainingProgramIds(node.opt(i), out)
            is JSONObject -> {
                for (k in arrayOf("id", "originTrainingProgramId", "trainingProgramId", "programId")) {
                    val v = node.optString(k).trim()
                    if (v.isNotEmpty() && v != "null" && !out.contains(v)) out.add(v)
                }
                for (k in node.keys()) {
                    val child = node.opt(k)
                    if (child is JSONArray || child is JSONObject) collectTrainingProgramIds(child, out)
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------------
    // 7) 教学大纲
    // ------------------------------------------------------------------

    private fun crawlSyllabus(r: Result) {
        val names = courses.mapNotNull { it.name }.filter { it.isNotEmpty() }.distinct()
        if (names.isEmpty()) {
            r.state = State.SKIPPED
            r.message = "课表为空，无课程可查大纲"
            return
        }
        log("  · 待查课程 ${names.size} 门")

        // ---- 1) 先拿「已选课程」列表，才有 courseNum / courseId（选课微应用的数据源）----
        val selected = fetchSelectedCourses()
        if (selected.isEmpty()) {
            log("  · 已选课程列表为空，退化为按课程名逐一试接口")
        } else {
            log("  · 已选课程 ${selected.size} 条（来自 ${PATH_SELECTED_COURSES}）")
        }

        // 课程名 -> (courseNum, courseId)
        val byName = HashMap<String, Pair<String?, String?>>()
        for (row in selected) {
            val n = firstOf(row, "courseName", "kcmc") ?: continue
            byName[n] = firstOf(row, "courseNum", "courseNumber", "kcdm") to
                    firstOf(row, "courseId", "courseNumber", "id")
        }

        var found = 0
        for (course in names) {
            val pair = byName[course]
            val courseNum = pair?.first
            val courseId = pair?.second

            // 1.1 大纲表头 + courseId
            var header: JSONObject? = null
            if (!courseNum.isNullOrBlank()) {
                header = api.get(
                    "/training-programe/courseoutline/getalloutlineinfo",
                    mapOf("courseNum" to courseNum, "auditStatus" to OUTLINE_AUDIT_PASSED)
                )
                if (header != null && hasData(header)) {
                    log("  · 《$course》大纲表头已获取（courseNum=$courseNum，auditStatus=$OUTLINE_AUDIT_PASSED）")
                } else {
                    log("  · 《$course》大纲表头为空（courseNum=$courseNum，code=${header?.opt("code") ?: "无响应"}）")
                    header = null
                }
            }

            // 1.2 正文：必须再调一次 outlinedetailbycourseid，否则只有表头
            var detail: JSONObject? = null
            val cid = courseId ?: header?.let { extractCourseId(it) }
            if (!cid.isNullOrBlank()) {
                detail = api.get(
                    "/training-programe/courseoutline/outlinedetailbycourseid",
                    mapOf("courseId" to cid)
                )
                if (detail != null && hasData(detail)) {
                    log("  · 《$course》大纲正文已获取（courseId=$cid）")
                } else {
                    log("  · 《$course》大纲正文为空（courseId=$cid）")
                    detail = null
                }
            }

            if (header != null || detail != null) {
                val payload = JSONObject()
                payload.put("courseName", course)
                payload.put("courseNum", courseNum ?: "")
                payload.put("courseId", cid ?: "")
                header?.let { payload.put("header", it) }
                detail?.let { payload.put("detail", it) }
                r.saves.add(
                    PendingSave(
                        CAT_SYLLABUS, "教学大纲-$course", payload.toString(),
                        if (detail != null) "表头 + 正文" else "仅表头",
                        "/training-programe/courseoutline/getalloutlineinfo", false
                    )
                )
                found++
            }
        }

        if (found > 0) {
            r.itemCount = found
            r.message = "$found 门课程大纲"
            return
        }

        // ---- 2) 兜底：逐个试剩余候选入口 ----
        for (course in names.take(3)) {
            for (path in SYLLABUS_PATHS.drop(1)) {
                val obj = try {
                    api.get(path, mapOf("courseNum" to course, "courseNumber" to course,
                        "auditStatus" to OUTLINE_AUDIT_PASSED, "academicYear" to termCode))
                } catch (e: Exception) {
                    null
                }
                if (obj != null && hasData(obj)) {
                    log("  · 《$course》命中兼容接口：$path")
                    r.saves.add(
                        PendingSave(CAT_SYLLABUS, "教学大纲-$course", obj.toString(), "接口 $path", path, false)
                    )
                    found++
                    break
                }
            }
        }
        if (found > 0) {
            r.itemCount = found
            r.message = "$found 门课程大纲（兼容入口）"
            return
        }

        // ---- 3) 再兜底：老路径 ----
        for (course in names) {
            for (path in LEGACY_SYLLABUS_PATHS) {
                val obj = try {
                    api.get(path, mapOf("courseName" to course, "academicYear" to termCode))
                } catch (e: Exception) {
                    null
                }
                if (obj != null && hasData(obj)) {
                    log("  · 《$course》命中旧接口：$path")
                    r.saves.add(
                        PendingSave(CAT_SYLLABUS, "教学大纲-$course", obj.toString(), "接口 $path", path, false)
                    )
                    found++
                    break
                }
            }
        }
        if (found > 0) {
            r.itemCount = found
            r.message = "$found 门课程大纲（旧入口）"
            return
        }

        // ---- 4) 页面文本兜底 ----
        for (p in SYLLABUS_PAGES) {
            val page = tryFetchPage(p)
            if (page != null) {
                log("  · 接口均不可用，已保存大纲页面文本（$p，${page.length} 字）")
                r.state = State.SUCCESS
                r.message = "接口不可用，已保存页面文本备用"
                r.saves.add(PendingSave(CAT_SYLLABUS, "教学大纲页面", page, r.message, p, true))
                return
            }
        }
        r.state = State.FAILED
        r.message = "教学大纲未取到数据（已尝试选课入口 + ${SYLLABUS_PATHS.size} 个接口 + " +
                "${LEGACY_SYLLABUS_PATHS.size} 个旧入口）；若 PC 端「已选课程」里能看到大纲，" +
                "通常是课程号没取到，请把日志发给作者"
        log("  !! 教学大纲未取到数据")
    }

    /**
     * 抓「已选课程」列表（选课微应用 `choose-course-front-server`）
     *
     * 返回的每行含 `courseNum` / `courseId` / `courseName`，是查大纲的关键。
     */
    private fun fetchSelectedCourses(): List<JSONObject> {
        val body = JSONObject()
        body.put("pageNo", 1)
        body.put("pageSize", 500)
        body.put("total", 0)
        val param = JSONObject()
        param.put("semesterYear", termCode)
        body.put("param", param)
        val resp = try {
            api.postJson(PATH_SELECTED_COURSES, body.toString())
        } catch (e: Exception) {
            log("  · 已选课程接口异常：${e.message}")
            null
        } ?: return emptyList()
        if (!api.staticIsSuccess(resp)) {
            log("  · 已选课程接口返回 code=${resp.opt("code")}")
            return emptyList()
        }
        val data = resp.opt("data")
        val rows = when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("rows")
                ?: data.optJSONArray("list")
                ?: data.optJSONArray("records")
            else -> null
        } ?: return emptyList()
        return (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
    }

    /** 从大纲表头里宽松取 courseId */
    fun extractCourseId(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                for (k in arrayOf("courseId", "COURSEID", "courseNum")) {
                    val v = node.optString(k).trim()
                    if (v.isNotEmpty() && v != "null") return v
                }
                // 常见结构：{data:{outlineInfo:{courseId:...}}}
                for (k in node.keys()) {
                    val child = node.opt(k)
                    if (child is JSONObject) extractCourseId(child)?.let { return it }
                }
            }
            is JSONArray -> for (i in 0 until node.length()) {
                extractCourseId(node.opt(i))?.let { return it }
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun resolveTerm(): String {
        return try {
            val terms = webSource().fetchTerms(token)
            terms.firstOrNull { it.isCurrent }?.getCode() ?: terms.firstOrNull()?.getCode() ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun termItemOf(code: String): TermItem {
        return webSource().toTermItem(code)
            ?: TermItem("2026-", "2026-2027学年", "1", "秋季学期")
    }

    private fun hasData(obj: JSONObject): Boolean {
        if (!api.staticIsSuccess(obj)) return false
        val data = obj.opt("data") ?: return false
        return when (data) {
            is JSONArray -> data.length() > 0
            is JSONObject -> data.length() > 0
            is String -> data.isNotEmpty() && data != "null"
            else -> true
        }
    }

    private fun pickArray(obj: JSONObject): JSONArray? {
        val data = obj.opt("data")
        return when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("list")
                ?: data.optJSONArray("rows")
                ?: data.optJSONArray("records")
            else -> obj.optJSONArray("list") ?: obj.optJSONArray("rows")
        }
    }

    private fun firstOf(obj: JSONObject, vararg keys: String): String? {
        for (k in keys) {
            if (obj.has(k) && !obj.isNull(k)) {
                val v = obj.optString(k).trim()
                if (v.isNotEmpty() && v != "null") return v
            }
        }
        return null
    }

    /** 抓一个教务页面的可见文本（接口不可用时的兜底） */
    private fun tryFetchPage(path: String): String? {
        return try {
            val html = api.getRaw(path)
            if (TextUtils.isEmpty(html)) return null
            val doc = org.jsoup.Jsoup.parse(html)
            val text = doc.body()?.text()?.trim().orEmpty()
            if (text.length < 40) null else text
        } catch (e: Exception) {
            null
        }
    }
}
