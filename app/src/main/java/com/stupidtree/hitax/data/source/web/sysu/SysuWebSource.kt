package com.stupidtree.hitax.data.source.web.sysu

import android.annotation.SuppressLint
import android.text.TextUtils
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.stupidtree.component.data.DataState
import com.stupidtree.hitax.data.model.eas.CourseItem
import com.stupidtree.hitax.data.model.eas.CourseScoreItem
import com.stupidtree.hitax.data.model.eas.EASToken
import com.stupidtree.hitax.data.model.eas.ExamItem
import com.stupidtree.hitax.data.model.eas.TermItem
import com.stupidtree.hitax.data.model.timetable.TermSubject
import com.stupidtree.hitax.data.model.timetable.TimeInDay
import com.stupidtree.hitax.data.model.timetable.TimePeriodInDay
import com.stupidtree.hitax.data.source.web.service.EASService
import com.stupidtree.hitax.ui.eas.classroom.BuildingItem
import com.stupidtree.hitax.ui.eas.classroom.ClassroomItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 中山大学教务系统数据源
 *
 * 会话来自 WebView 中的统一身份认证（[SysuApi.LOGIN_URL]），
 * 登录成功后 Cookie 保存于 [EASToken.cookies]，本类据此调用 jwxt 接口。
 */
class SysuWebSource : EASService {

    /** 用于测试时替换的接口根地址 */
    internal var host: String = SysuApi.HOST

    private fun api(token: EASToken): SysuApi = SysuApi(token.cookies).also { it.hostOverride = host }

    /**
     * LiveData 包装：把同步取数逻辑放到子线程执行
     */
    private fun <T> async(block: () -> T): LiveData<DataState<T>> {
        val res = MutableLiveData<DataState<T>>()
        Thread {
            try {
                res.postValue(DataState(block(), DataState.STATE.SUCCESS))
            } catch (e: NotLoggedInException) {
                res.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, e.message))
            } catch (e: Exception) {
                e.printStackTrace()
                res.postValue(DataState(DataState.STATE.FETCH_FAILED, e.message))
            }
        }.start()
        return res
    }

    /** 会话失效 */
    class NotLoggedInException(message: String = "未登录或会话已过期") : Exception(message)

    private fun requireLogin(api: SysuApi) {
        if (api.loginStatus() != 1) throw NotLoggedInException()
    }

    // ------------------------------------------------------------------
    // 登录
    // ------------------------------------------------------------------

    /**
     * 使用 Cookie 直接建立会话（WebView 登录后的路径）
     */
    override fun login(username: String, password: String, code: String?): LiveData<DataState<EASToken>> {
        val res = MutableLiveData<DataState<EASToken>>()
        Thread {
            try {
                val cookies = HashMap<String, String>()
                if (!TextUtils.isEmpty(code)) {
                    // code 承载 "k=v; k2=v2" 形式的 Cookie
                    code!!.split(";").forEach { seg ->
                        val idx = seg.indexOf('=')
                        if (idx > 0) cookies[seg.substring(0, idx).trim()] = seg.substring(idx + 1).trim()
                    }
                }
                if (cookies.isEmpty()) {
                    res.postValue(DataState(DataState.STATE.FETCH_FAILED, "缺少会话 Cookie"))
                    return@Thread
                }
                val token = EASToken()
                token.cookies = cookies
                token.username = username
                token.password = password
                if (!fetchUserInfo(token)) {
                    res.postValue(DataState(DataState.STATE.FETCH_FAILED, "会话无效或已过期"))
                    return@Thread
                }
                res.postValue(DataState(token, DataState.STATE.SUCCESS))
            } catch (e: Exception) {
                e.printStackTrace()
                res.postValue(DataState(DataState.STATE.FETCH_FAILED, e.message))
            }
        }.start()
        return res
    }

    /**
     * 校验会话，同时刷新学生信息
     */
    override fun loginCheck(token: EASToken): LiveData<DataState<Pair<Boolean, EASToken>>> {
        val res = MutableLiveData<DataState<Pair<Boolean, EASToken>>>()
        Thread {
            try {
                if (token.cookies.isEmpty()) {
                    res.postValue(DataState(Pair(false, token)))
                    return@Thread
                }
                val ok = fetchUserInfo(token)
                res.postValue(DataState(Pair(ok, token)))
            } catch (e: Exception) {
                res.postValue(DataState(DataState.STATE.FETCH_FAILED, e.message))
            }
        }.start()
        return res
    }

    /**
     * 拉取学生信息，返回是否仍然登录
     */
    private fun fetchUserInfo(token: EASToken): Boolean {
        val api = api(token)
        if (api.loginStatus() != 1) return false
        // 已登录：尽力补全姓名等信息，失败不影响登录态
        try {
            val obj = api.get("/base-info/student-info/getStudentInfo") ?: api.get("/api/privilege")
            val data = obj?.optJSONObject("data")
            if (data != null) {
                token.name = firstOf(data, "userName", "xm", "name", "studentName") ?: token.username
                token.stuId = firstOf(data, "userNumber", "xh", "studentNumber", "account")
                token.school = firstOf(data, "facultyName", "yxmc", "collegeName", "departmentName")
                token.major = firstOf(data, "majorName", "zymc", "specialityName", "professionName")
                token.grade = firstOf(data, "gradeName", "njmc", "grade")
                token.id = firstOf(data, "userId", "id")
            }
        } catch (e: Exception) {
            // ignore
        }
        if (token.name.isNullOrEmpty()) token.name = token.username
        return true
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

    // ------------------------------------------------------------------
    // 学年学期
    // ------------------------------------------------------------------

    override fun getAllTerms(token: EASToken): LiveData<DataState<List<TermItem>>> = async {
        fetchTerms(token)
    }

    /**
     * 同步获取学年学期列表。
     *
     * 中大接口 `/base-info/acadyearterm/showNewAcadlist` 只返回**当前**学年学期：
     * `{"code":200,"data":{"acadYearSemester":"2026-1","acadYear":"2026-2027","acadSemester":1,...}}`
     * 因此这里以接口返回的当前学期为准，再补全同一年度内的其它学期（秋季/春季），
     * 保证导入页可以切换学期。
     */
    fun fetchTerms(token: EASToken): List<TermItem> {
        val api = api(token)
        val obj = api.showNewAcadList() ?: api.showAcadList()
            ?: throw IOException("无法获取学年学期，请检查网络或重新登录")
        val data = obj.optJSONObject("data") ?: obj
        val currentCode = data.optString("acadYearSemester").ifEmpty { null }
        val terms = LinkedHashMap<String, TermItem>()
        // 1) 接口直接给出的当前学期及其它形如 2026-1 的字段
        val raw = mutableListOf<String>()
        collectTermStrings(data, raw)
        // 2) 由 acadYear（2026-2027）补全秋季/春季学期
        val acadYear = data.optString("acadYear")
        val ym = Regex("^(\\d{4})-(\\d{4})$").find(acadYear.trim())
        if (ym != null) {
            val y = ym.groupValues[1]
            raw.add("$y-1")
            raw.add("$y-2")
            raw.add("$y-3")
        }
        for (s in raw) {
            val t = toTermItem(s) ?: continue
            if (!terms.containsKey(t.getCode())) terms[t.getCode()] = t
        }
        if (terms.isEmpty()) throw IOException("学年学期列表为空")
        val list = terms.values.sortedWith(
            compareByDescending<TermItem> { it.yearCode }.thenByDescending { it.termCode }
        )
        list.forEach { it.isCurrent = it.getCode() == currentCode }
        if (list.none { it.isCurrent }) list[0].isCurrent = true
        return list
    }

    /**
     * 从接口返回中解析学年学期列表。
     * 兼容 `["2025-1","2024-2"]`、`[{"academicYear":"2025-1"}]`、
     * `{"data":{"list":[...]}}`、`{"data":{"acadYearSemester":"2025-1","acadYearSemesterName":"..."}}` 等结构。
     */
    fun parseTerms(obj: JSONObject, currentCode: String?): List<TermItem> {
        val raw = mutableListOf<String>()
        collectTermStrings(obj.opt("data") ?: obj, raw)
        val terms = LinkedHashMap<String, TermItem>()
        for (s in raw) {
            val t = toTermItem(s) ?: continue
            if (!terms.containsKey(t.getCode())) terms[t.getCode()] = t
        }
        val list = terms.values.sortedWith(
            compareByDescending<TermItem> { it.yearCode }.thenByDescending { it.termCode }
        )
        list.forEach { it.isCurrent = it.getCode() == currentCode }
        if (list.isNotEmpty() && list.none { it.isCurrent }) {
            // 若接口没有给出当前学期，退化为「第一个」
            list[0].isCurrent = true
        }
        return list
    }

    private fun collectTermStrings(node: Any?, out: MutableList<String>) {
        when (node) {
            is String -> {
                val s = node.trim()
                // 形如 "2025-1" 的字符串本身就是学期代码
                if (looksLikeTerm(s) && !out.contains(s)) out.add(s)
            }
            is JSONArray -> {
                for (i in 0 until node.length()) collectTermStrings(node.opt(i), out)
            }
            is JSONObject -> {
                // 1) 优先取「学期相关」字段
                for (key in TERM_KEYS) {
                    if (node.has(key) && !node.isNull(key)) {
                        val v = node.optString(key).trim()
                        if (looksLikeTerm(v) && !out.contains(v)) out.add(v)
                    }
                }
                // 2) 名称字段形如 "2025-2026学年秋季学期"，从中提取代码
                for (key in TERM_NAME_KEYS) {
                    if (node.has(key) && !node.isNull(key)) {
                        val code = termCodeFromName(node.optString(key))
                        if (code != null && !out.contains(code)) out.add(code)
                    }
                }
                // 3) 继续向下找 list / rows / records / data 等数组
                for (childKey in CHILD_KEYS) {
                    val child = node.opt(childKey)
                    if (child is JSONArray || child is JSONObject) collectTermStrings(child, out)
                }
            }
        }
    }

    /**
     * 从名称中提取学期代码，例如
     * `2025-2026学年秋季学期` -> `2025-1`；`2025学年春季学期` -> `2025-2`
     */
    fun termCodeFromName(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val m = Regex("(\\d{4})").find(name) ?: return null
        val year = m.groupValues[1]
        val code = when {
            name.contains("秋") -> "1"
            name.contains("春") -> "2"
            name.contains("夏") -> "3"
            name.contains("第一学期") || name.contains("上学期") -> "1"
            name.contains("第二学期") || name.contains("下学期") -> "2"
            else -> null
        } ?: return null
        return "$year-$code"
    }

    private fun looksLikeTerm(s: String): Boolean =
        Regex("^\\d{4}-\\d$").matches(s.trim())

    /**
     * `2025-1` -> 2025-2026学年 秋季学期
     *
     * 注意：[TermItem.getCode] 是 `yearCode + termCode`，而中大接口的学期代码形如
     * `2025-1`，因此这里把 `yearCode` 存成 `2025-`（带连字符），保证 round-trip 一致。
     */
    fun toTermItem(code: String): TermItem? {
        val c = code.trim()
        val m = Regex("^(\\d{4})-(\\d)$").find(c) ?: return null
        val year = m.groupValues[1].toInt()
        val term = m.groupValues[2]
        val yearCode = m.groupValues[1] + "-"
        val yearName = "$year-${year + 1}学年"
        val termName = when (term) {
            "1" -> "秋季学期"
            "2" -> "春季学期"
            "3" -> "夏季学期"
            else -> "第${term}学期"
        }
        return TermItem(yearCode, yearName, term, termName)
    }

    private val TERM_KEYS = listOf(
        "acadYearSemester", "academicYear", "acadyearterm", "xnxq", "semester", "term", "code", "value", "id"
    )

    private val TERM_NAME_KEYS = listOf(
        "acadYearSemesterName", "academicYearName", "xnxqmc", "name", "label", "text", "semesterName"
    )

    private val CHILD_KEYS = listOf("list", "rows", "records", "data", "content", "children", "items")

    // ------------------------------------------------------------------
    // 学期开始日期
    // ------------------------------------------------------------------

    @SuppressLint("SimpleDateFormat")
    override fun getStartDate(token: EASToken, term: TermItem): LiveData<DataState<Calendar>> = async {
        fetchStartDate(token, term)
    }

    /** 同步获取学期第一周开始日期 */
    @SuppressLint("SimpleDateFormat")
    fun fetchStartDate(token: EASToken, term: TermItem): Calendar {
        val api = api(token)
        // 1) 教学日历第 1 周（`{"data":{"startTime":"2026-09-07","endTime":"2026-09-13"}}`）
        val obj = api.calendar(term.getCode(), 1)
        var result: Calendar? = extractWeekStart(obj)
        // 2) 退化：使用学年开始时间戳
        if (result == null) {
            val acad = api.showNewAcadList()?.optJSONObject("data")
            val ts: Long = acad?.optLong("acadStartdate", 0L) ?: 0L
            if (ts > 0) {
                result = Calendar.getInstance().apply { timeInMillis = ts }
            }
        }
        // 3) 最后退化为 9 月 1 日
        val finalResult: Calendar = result ?: run {
            val y = term.yearCode.trimEnd('-').toIntOrNull() ?: Calendar.getInstance().get(Calendar.YEAR)
            Calendar.getInstance().apply {
                set(Calendar.YEAR, y)
                set(Calendar.MONTH, Calendar.SEPTEMBER)
                set(Calendar.DAY_OF_MONTH, 1)
            }
        }
        // 统一对齐到所在周的周一：HITA 的课表以周一为一周起点
        finalResult.firstDayOfWeek = Calendar.MONDAY
        finalResult.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        finalResult.set(Calendar.HOUR_OF_DAY, 0)
        finalResult.set(Calendar.MINUTE, 0)
        finalResult.set(Calendar.SECOND, 0)
        finalResult.set(Calendar.MILLISECOND, 0)
        return finalResult
    }

    /**
     * 获取该学期的总周数（由 `/base-info/school-calender/weekly` 的 weeklyList 得出）
     */
    fun fetchTotalWeeks(token: EASToken, term: TermItem): Int {
        val api = api(token)
        val obj = api.weeklyList(term.getCode()) ?: return 0
        val list = obj.optJSONObject("data")?.optJSONArray("weeklyList") ?: return 0
        return list.length()
    }

    /**
     * 从教学日历响应中提取该周的开始日期
     */
    @SuppressLint("SimpleDateFormat")
    fun extractWeekStart(obj: JSONObject?): Calendar? {
        if (obj == null) return null
        var best: String? = null
        val dates = mutableListOf<String>()
        findDateStrings(obj, dates)
        for (d in dates) {
            if (best == null || d < best!!) best = d
        }
        val s = best ?: return null
        return try {
            val c = Calendar.getInstance()
            c.time = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).parse(s)!!
            c
        } catch (e: Exception) {
            null
        }
    }

    private fun findDateStrings(node: Any?, out: MutableList<String>) {
        when (node) {
            is JSONObject -> {
                for (k in node.keys()) {
                    val v = node.opt(k)
                    if (v is String) {
                        Regex("(\\d{4}-\\d{2}-\\d{2})").find(v)?.let { out.add(it.groupValues[1]) }
                    } else {
                        findDateStrings(v, out)
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) findDateStrings(node.opt(i), out)
            }
        }
    }

    // ------------------------------------------------------------------
    // 课表结构
    // ------------------------------------------------------------------

    override fun getScheduleStructure(
        term: TermItem,
        isUndergraduate: Boolean?,
        token: EASToken
    ): LiveData<DataState<MutableList<TimePeriodInDay>>> {
        val res = MutableLiveData<DataState<MutableList<TimePeriodInDay>>>()
        res.value = DataState(SysuTimetableParser.buildScheduleStructureFromSections())
        return res
    }

    // ------------------------------------------------------------------
    // 课表
    // ------------------------------------------------------------------

    override fun getTimetableOfTerm(
        term: TermItem,
        token: EASToken
    ): LiveData<DataState<List<CourseItem>>> = async {
        fetchTimetable(term, token)
    }

    /**
     * 同步抓取整学期课表：中大接口按周返回，这里逐周抓取后合并周次。
     */
    fun fetchTimetable(term: TermItem, token: EASToken): List<CourseItem> {
        val api = api(token)
        requireLogin(api)
        // 先用教学日历确定总周数，避免无谓的请求
        val totalWeeks = fetchTotalWeeks(token, term).let { if (it > 0) it else MAX_WEEKS }
        val all = mutableListOf<CourseItem>()
        var emptyStreak = 0
        var lastError: String? = null
        for (week in 1..totalWeeks.coerceAtMost(MAX_WEEKS)) {
            val obj = api.studentClassTable(term.getCode(), week)
            if (obj == null) {
                lastError = "第 $week 周课表获取失败"
                emptyStreak++
                if (emptyStreak >= 3) break
                continue
            }
            val data = pickArray(obj)
            val parsed = SysuTimetableParser.parseWeek(data, week)
            if (parsed.isEmpty()) {
                emptyStreak++
                // 连续三周没有课，认定学期结束
                if (emptyStreak >= 3) break
            } else {
                emptyStreak = 0
                all.addAll(parsed)
            }
        }
        val merged = SysuTimetableParser.merge(all)
        if (merged.isEmpty()) {
            throw IOException(lastError ?: "未获取到课程，请确认学期是否正确")
        }
        return merged
    }

    /** 从响应中取课表数据数组 */
    fun pickArray(obj: JSONObject): JSONArray? {
        val data = obj.opt("data")
        return when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("list")
                ?: data.optJSONArray("rows")
                ?: data.optJSONArray("records")
            else -> obj.optJSONArray("list") ?: obj.optJSONArray("rows")
        }
    }

    // ------------------------------------------------------------------
    // 成绩
    // ------------------------------------------------------------------

    override fun getPersonalScores(
        term: TermItem,
        token: EASToken,
        testType: EASService.TestType
    ): LiveData<DataState<List<CourseScoreItem>>> = async {
        fetchScores(term, token)
    }

    /** 同步获取某学期成绩 */
    fun fetchScores(term: TermItem, token: EASToken): List<CourseScoreItem> {
        val api = api(token)
        requireLogin(api)
        // 中大成绩接口需要 scoSchoolYear=2026-2027 / scoSemester=1 形式的参数
        val year = term.yearCode.trimEnd('-').toIntOrNull()
            ?: term.yearCode.take(4).toIntOrNull() ?: 0
        val schoolYear = "$year-${year + 1}"
        val semester = term.termCode
        val obj = api.scoreList(schoolYear, semester) ?: throw IOException("成绩接口无响应")
        val arr = pickArray(obj)
        val list = mutableListOf<CourseScoreItem>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val row = arr.optJSONObject(i) ?: continue
                val item = CourseScoreItem()
                item.courseName = firstOf(row, "courseName", "kcmc", "course_name")
                item.courseCode = firstOf(row, "courseNumber", "kcdm", "courseCode")
                item.courseProperty = firstOf(row, "courseProperty", "kcxz", "courseType", "courseNature")
                item.courseCategory = firstOf(row, "courseCategory", "kclb", "courseKind")
                item.schoolName = firstOf(row, "facultyName", "yxmc", "openFaculty", "departmentName")
                item.assessMethod = firstOf(row, "examType", "khfs", "assessMethod", "examForm")
                item.termName = firstOf(row, "academicYear", "acadYearSemester", "xnxqmc")
                    ?: "${schoolYear}学年第${semester}学期"
                item.credits = numOf(row, "credit", "xf", "credits", "scoCredit")
                item.hours = numOf(row, "hour", "xs", "hours", "scoHour")
                item.finalScores = numOf(
                    row, "score", "zzcj", "finalScore", "grade",
                    "scoScore", "finalScoreView", "scoreView", "scoScoreView"
                )
                if (!item.courseName.isNullOrEmpty()) list.add(item)
            }
        }
        return list
    }

    private fun numOf(obj: JSONObject, vararg keys: String): Int {
        for (k in keys) {
            if (!obj.has(k) || obj.isNull(k)) continue
            val raw = obj.optString(k).trim()
            if (raw.isEmpty() || raw == "null") continue
            raw.toDoubleOrNull()?.let { return Math.round(it).toInt() }
            // 形如 "3.0" / "85分"
            Regex("(\\d+(?:\\.\\d+)?)").find(raw)?.let {
                it.groupValues[1].toDoubleOrNull()?.let { d -> return Math.round(d).toInt() }
            }
            // 中大允许等级制成绩，映射为可展示的百分制等价分
            levelScore(raw)?.let { return it }
        }
        return 0
    }

    /** 等级制 / 通过制成绩的百分制等价映射 */
    private fun levelScore(raw: String): Int? = when (raw.uppercase()) {
        "A+", "A", "优秀", "优" -> 95
        "A-", "B+", "良好", "良" -> 88
        "B", "B-", "中等", "中" -> 80
        "C+", "C", "及格", "合格", "通过", "PASS" -> 68
        "D", "不及格", "不合格", "FAIL" -> 50
        else -> null
    }

    // ------------------------------------------------------------------
    // 考试
    // ------------------------------------------------------------------

    override fun getExamItems(token: EASToken): LiveData<DataState<List<ExamItem>>> = async {
        fetchExams(token)
    }
    /**
     * 同步获取考试安排
     *
     * 中大考试信息在 mk 微应用中，需要两步：
     * 1) `queryExamWeekName?yearTerm=` 取考试周（缓补考 / 10-17周结课考 / 18-19周期末考）
     * 2) POST `queryStuEaxmInfo?code=jwxsd_ksxxck` 取该考试周内的考试
     */
    fun fetchExams(token: EASToken, term: TermItem? = null): List<ExamItem> {
        val api = api(token)
        requireLogin(api)
        val yearTerm = term?.getCode() ?: run {
            val t = fetchTerms(token).firstOrNull { it.isCurrent } ?: fetchTerms(token).firstOrNull()
            t?.getCode() ?: return emptyList()
        }
        val weekObj = api.examWeekName(yearTerm) ?: return emptyList()
        val weeks = pickArray(weekObj) ?: return emptyList()
        val result = mutableListOf<ExamItem>()
        for (i in 0 until weeks.length()) {
            val w = weeks.optJSONObject(i) ?: continue
            val weekId = firstOf(w, "examWeekId", "id")
            val weekName = firstOf(w, "examWeekName", "weekName", "name") ?: ""
            val detail = api.studentExamInfo(yearTerm, weekId, weekName, w) ?: continue
            val rows = pickArray(detail) ?: continue
            parseExamRows(rows, weekName, yearTerm, result)
        }
        return result
    }

    /**
     * 解析考试信息：每行是一个「节次」，`timetable` 字段是「星期 -> 单元格数组」的二维结构
     */
    private fun parseExamRows(
        rows: JSONArray,
        weekName: String,
        yearTerm: String,
        out: MutableList<ExamItem>
    ) {
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val section = firstOf(row, "dataNumber", "section") ?: ""
            val timetable = row.optJSONObject("timetable") ?: continue
            for (dowKey in timetable.keys()) {
                val cells = timetable.optJSONArray(dowKey) ?: continue
                for (j in 0 until cells.length()) {
                    val cell = cells.optJSONObject(j) ?: continue
                    if (cell.optBoolean("emptyFlag", false)) continue
                    val item = ExamItem()
                    item.courseName = firstOf(cell, "examSubjectName", "courseName", "kcmc")
                        ?: continue
                    item.examDate = firstOf(cell, "examDate", "ksrq")
                    item.examTime = firstOf(cell, "durationTime", "startTime", "examTime", "kssj")
                    item.examLocation = firstOf(cell, "classroomNumber", "examPlace", "ksdd")
                    item.examType = firstOf(cell, "examStage", "examMode", "examType") ?: weekName
                    item.termName = yearTerm
                    item.campusName = firstOf(cell, "campusName", "xqmc")
                    out.add(item)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 以下接口在中大教务暂无稳定对应实现，返回空结果
    // ------------------------------------------------------------------

    override fun getSubjectsOfTerm(
        token: EASToken,
        term: TermItem
    ): LiveData<DataState<MutableList<TermSubject>>> {
        val res = MutableLiveData<DataState<MutableList<TermSubject>>>()
        Thread {
            try {
                val api = api(token)
                val subjects = mutableListOf<TermSubject>()
                var emptyStreak = 0
                val seen = HashMap<String, TermSubject>()
                for (week in 1..MAX_WEEKS) {
                    val obj = api.studentClassTable(term.getCode(), week) ?: continue
                    val arr = pickArray(obj) ?: continue
                    var found = 0
                    for (i in 0 until arr.length()) {
                        val row = arr.optJSONObject(i) ?: continue
                        for (dow in 1..7) {
                            val raw = row.optString(SysuTimetableParser.WEEKDAY_FIELDS[dow])
                            if (raw.isNullOrEmpty()) continue
                            for (block in raw.split(",,")) {
                                val name = block.split(";;").getOrNull(0)?.trim() ?: continue
                                if (name.isEmpty()) continue
                                val clean = name.replace(Regex("\\[([^\\]]*)\\]"), "").trim()
                                if (clean.isEmpty() || seen.containsKey(clean)) continue
                                val s = TermSubject()
                                s.name = clean
                                val teacher = block.split(";;").getOrNull(1)?.trim()
                                s.key = clean
                                seen[clean] = s
                                subjects.add(s)
                                found++
                            }
                        }
                    }
                    if (found == 0) {
                        emptyStreak++
                        if (emptyStreak >= 3) break
                    } else emptyStreak = 0
                }
                res.postValue(DataState(subjects, DataState.STATE.SUCCESS))
            } catch (e: Exception) {
                res.postValue(DataState(DataState.STATE.FETCH_FAILED, e.message))
            }
        }.start()
        return res
    }

    override fun getTeachingBuildings(token: EASToken): LiveData<DataState<List<BuildingItem>>> {
        val res = MutableLiveData<DataState<List<BuildingItem>>>()
        Thread {
            val list = mutableListOf<BuildingItem>()
            try {
                val api = api(token)
                val campus = api.campusList()
                val arr = campus?.optJSONArray("data") ?: campus?.optJSONArray("list")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val c = arr.optJSONObject(i) ?: continue
                        val campusId = firstOf(c, "id", "campusId", "code") ?: continue
                        val campusName = firstOf(c, "name", "campusName", "label") ?: campusId
                        val bObj = api.teachingBuildingList(campusId)
                        val bArr = bObj?.optJSONArray("data") ?: bObj?.optJSONArray("list")
                        if (bArr != null) {
                            for (j in 0 until bArr.length()) {
                                val b = bArr.optJSONObject(j) ?: continue
                                val item = BuildingItem()
                                item.id = firstOf(b, "id", "buildingId", "code") ?: ""
                                item.name = "$campusName ${firstOf(b, "name", "buildingName") ?: ""}".trim()
                                list.add(item)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            res.postValue(DataState(list, DataState.STATE.SUCCESS))
        }.start()
        return res
    }

    override fun queryEmptyClassroom(
        token: EASToken,
        term: TermItem,
        building: BuildingItem,
        weeks: List<String>
    ): LiveData<DataState<List<ClassroomItem>>> {
        val res = MutableLiveData<DataState<List<ClassroomItem>>>()
        res.value = DataState(ArrayList<ClassroomItem>(), DataState.STATE.SUCCESS)
        return res
    }

    companion object {
        /** 中大一学期最多 30 周，用于按周抓取课表时的安全上限 */
        const val MAX_WEEKS = 30
    }
}
