package com.stupidtree.hitax.data.source.web.sysu

import android.text.TextUtils
import com.stupidtree.hitax.data.model.eas.EASToken
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Connection
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * 中山大学教务系统（jwxt.sysu.edu.cn）HTTP 客户端
 *
 * 说明：
 * - 会话通过统一身份认证（CAS）在 WebView 中完成，登录后把 Cookie 交给本类；
 * - 所有接口以 Cookie 作为身份凭证，返回 JSON；
 * - 统一走 [get]/[postJson] 两个入口，便于统一处理 UA、请求头与错误码。
 */
class SysuApi(
    private val cookies: Map<String, String>,
    private val timeout: Int = 15000
) {

    companion object {
        const val HOST = "https://jwxt.sysu.edu.cn/jwxt"

        /** 学生登录入口（WebView 使用；pattern=student-login） */
        const val LOGIN_URL = "$HOST/api/sso/cas/login?pattern=student-login"

        const val UA =
            "Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        /** SYSU 教务接口的业务成功码 */
        private const val CODE_OK_1 = 200
        private const val CODE_OK_2 = "200"

        internal fun encode(s: String?): String = URLEncoder.encode(s ?: "", "UTF-8")
    }

    private val jar: MutableMap<String, String> = ConcurrentHashMap<String, String>().apply {
        putAll(cookies)
    }

    /** 测试时可指向本地 mock 服务 */
    internal var hostOverride: String? = null

    private val base: String
        get() = hostOverride ?: HOST

    private val headerMap: Map<String, String>
        get() = mapOf(
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
            "X-Requested-With" to "XMLHttpRequest",
            "moduleid" to "null",
            "menuid" to "null",
            "lastaccesstime" to System.currentTimeMillis().toString(),
            "Referer" to (base + "/"),
            "Origin" to originOf(base),
            "User-Agent" to UA
        )

    private fun originOf(url: String): String {
        return try {
            val u = java.net.URI(url)
            "${u.scheme}://${u.host}"
        } catch (e: Exception) {
            "https://jwxt.sysu.edu.cn"
        }
    }

    fun currentCookies(): Map<String, String> = HashMap(jar)

    private fun connection(url: String): Connection =
        Jsoup.connect(url)
            .timeout(timeout)
            .cookies(jar)
            .headers(headerMap)
            .ignoreContentType(true)
            .ignoreHttpErrors(true)
            .followRedirects(true)

    private fun absorbCookies(response: Connection.Response) {
        response.cookies().forEach { (k, v) -> jar[k] = v }
    }

    /**
     * 发起 GET 请求并返回原始文本；网络异常抛出 [IOException]
     */
    @Throws(IOException::class)
    fun getRaw(path: String, params: Map<String, String> = emptyMap()): String {
        val url = buildUrl(path, params)
        val res = connection(url).method(Connection.Method.GET).execute()
        absorbCookies(res)
        return res.body()
    }

    /**
     * 发起 JSON POST 请求
     */
    @Throws(IOException::class)
    fun postJsonRaw(path: String, body: String): String {
        val res = connection(base + path)
            .method(Connection.Method.POST)
            .header("Content-Type", "application/json;charset=UTF-8")
            .requestBody(body)
            .execute()
        absorbCookies(res)
        return res.body()
    }

    /**
     * GET 并解析为 [JSONObject]，网络/解析失败返回 null
     */
    fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject? {
        return try {
            val text = getRaw(path, params)
            if (TextUtils.isEmpty(text)) null else JSONObject(text)
        } catch (e: Exception) {
            null
        }
    }

    fun postJson(path: String, body: String): JSONObject? {
        return try {
            val text = postJsonRaw(path, body)
            if (TextUtils.isEmpty(text)) null else JSONObject(text)
        } catch (e: Exception) {
            null
        }
    }

    private fun buildUrl(path: String, params: Map<String, String>): String {
        val sb = StringBuilder(base).append(path)
        val query = StringBuilder()
        params.forEach { (k, v) ->
            query.append(if (query.isEmpty()) "?" else "&")
                .append(encode(k)).append("=").append(encode(v))
        }
        // 打散缓存
        query.append(if (query.isEmpty()) "?" else "&").append("_t=").append(System.currentTimeMillis())
        return sb.append(query).toString()
    }

    /** 判断接口响应是否为业务成功（code == 200） */
    fun staticIsSuccess(obj: JSONObject?): Boolean {
        if (obj == null) return false
        val code = obj.opt("code")
        return code?.toString() == CODE_OK_1.toString() || code?.toString() == CODE_OK_2
    }

    // ------------------------------------------------------------------
    // 具体接口
    // ------------------------------------------------------------------

    /**
     * 登录状态：data == 1 表示已登录
     */
    fun loginStatus(): Int {
        val obj = get("/api/login/status") ?: return -1
        if (!staticIsSuccess(obj)) return -1
        return obj.optInt("data", -1)
    }

    fun logout() {
        try {
            get("/api/logout")
        } catch (e: Exception) {
            // ignore
        }
    }

    /**
     * 当前学年学期
     * 返回：`{"code":200,"data":{"acadYearSemester":"2026-1","acadYear":"2026-2027",
     *        "acadSemester":1,"acadStartdate":1788278400000,"acadEnddate":1801929600000}}`
     */
    fun showNewAcadList(): JSONObject? = get("/base-info/acadyearterm/showNewAcadlist")

    /** 全部学年学期列表 */
    fun showAcadList(): JSONObject? = get("/base-info/acadyearterm/showAcadlist")

    /**
     * 该学期的周次列表
     * 返回：`{"code":200,"data":{"weeklyList":[{"weeklyName":"第1周","weekly":1}, ...]}}`
     */
    fun weeklyList(academicYear: String): JSONObject? = get(
        "/base-info/school-calender/weekly",
        mapOf("academicYear" to academicYear)
    )

    /**
     * 当前周次（由 weeklyList 推导：返回 null 表示无法判断）
     */
    fun currentWeek(academicYear: String): Int? {
        val weeks = weeklyList(academicYear) ?: return null
        val list = weeks.optJSONObject("data")?.optJSONArray("weeklyList") ?: return null
        // 接口不直接给当前周；调用方可用日期自行计算
        return if (list.length() > 0) list.length() else null
    }

    /**
     * 教学日历：某一周的日期区间
     * 返回：`{"code":200,"data":{"startTime":"2026-09-07","endTime":"2026-09-13"}}`
     */
    fun calendar(academicYear: String, weekly: Int): JSONObject? = get(
        "/base-info/school-calender",
        mapOf("academicYear" to academicYear, "weekly" to weekly.toString())
    )

    /**
     * 学生课表（按周查询）
     * 返回：`{"code":200,"data":[{"section":4,"weekly":1,"monday":"xslx:bk;;kcmc:...;;rkjs:...;;skdd:...;;skrq:第1周/2026-09-07/星期一/", ...}]}`
     */
    fun studentClassTable(academicYear: String, weekly: Int): JSONObject? = get(
        "/timetable-search/classTableInfo/selectStudentClassTable",
        mapOf("academicYear" to academicYear, "weekly" to weekly.toString())
    )

    // ------------------------------------------------------------------
    // 成绩
    // ------------------------------------------------------------------

    fun checkStuStatus(): JSONObject? = get("/achievement-manage/score-check/checkStuStatus")

    /** 成绩查询下拉框（学年 / 学期 / 培养类型） */
    fun scorePull(): JSONObject? = get("/achievement-manage/score-check/getPull")

    /**
     * 成绩列表
     * @param scoSchoolYear 形如 `2026-2027`
     * @param scoSemester 形如 `1`
     */
    fun scoreList(
        scoSchoolYear: String,
        scoSemester: String,
        trainTypeCode: String = "01",
        addScoreFlag: Boolean = false
    ): JSONObject? = get(
        "/achievement-manage/score-check/list",
        mapOf(
            "scoSchoolYear" to scoSchoolYear,
            "trainTypeCode" to trainTypeCode,
            "addScoreFlag" to addScoreFlag.toString(),
            "scoSemester" to scoSemester
        )
    )

    /** 按学年汇总的成绩 / 排名 */
    fun scoreSortByYear(
        scoSchoolYear: String,
        scoSemester: String,
        trainTypeCode: String = "01",
        addScoreFlag: Boolean = false
    ): JSONObject? = get(
        "/achievement-manage/score-check/getSortByYear",
        mapOf(
            "scoSchoolYear" to scoSchoolYear,
            "trainTypeCode" to trainTypeCode,
            "addScoreFlag" to addScoreFlag.toString(),
            "scoSemester" to scoSemester
        )
    )

    /** 学分完成情况 */
    fun creditSituation(): JSONObject? = get("/achievement-manage/score-check/stuCreditSitlist")

    fun scorePie(): JSONObject? = get("/achievement-manage/score-check/getPicPie")

    // ------------------------------------------------------------------
    // 考试（mk 微应用接口）
    // ------------------------------------------------------------------

    /**
     * 考试周列表
     * 返回：`{"code":200,"data":[{"examWeekId":"...","examWeekName":"18-19周期末考","startDate":"2027-01-04","endDate":"2027-01-17"}]}`
     */
    fun examWeekName(yearTerm: String): JSONObject? = get(
        "/schedule/agg/commonScheduleExamTime/queryExamWeekName",
        mapOf("yearTerm" to yearTerm)
    )

    /**
     * 学生考试信息
     * 需要 POST，参数：acadYear / examWeekId / examDate / examWeekName / examWeekObj
     * 返回：`{"code":200,"data":[{...}]}`，每行含 `timetable` 二维结构
     */
    fun studentExamInfo(
        acadYear: String,
        examWeekId: String?,
        examWeekName: String,
        examWeekObj: JSONObject?
    ): JSONObject? {
        val body = JSONObject()
        body.put("acadYear", acadYear)
        body.put("examWeekId", examWeekId ?: JSONObject.NULL)
        body.put("examDate", "")
        body.put("examWeekName", examWeekName)
        body.put("examWeekObj", examWeekObj ?: JSONObject.NULL)
        return postJson("/examination-manage/classroomResource/queryStuEaxmInfo?code=jwxsd_ksxxck", body.toString())
    }

    /**
     * 空教室
     */
    fun campusList(): JSONObject? = get("/base-info/campus/findCampusNamesBox")

    fun teachingBuildingList(campusId: String): JSONObject? = get(
        "/base-info/teaching-building/pull",
        mapOf("campusId" to campusId)
    )

    /**
     * 从任意 JSON 结构中宽松地取出数组（兼容 data/list/rows 等命名）
     */
    fun staticArrayOf(obj: JSONObject?, vararg keys: String): JSONArray? {
        if (obj == null) return null
        for (k in keys) {
            val arr = obj.optJSONArray(k)
            if (arr != null) return arr
        }
        val data = obj.optJSONObject("data")
        if (data != null) {
            for (k in keys) {
                val arr = data.optJSONArray(k)
                if (arr != null) return arr
            }
            val inner = data.optJSONObject("list")
            if (inner != null) return inner.optJSONArray("rows") ?: inner.optJSONArray("list")
        }
        return obj.optJSONObject("content")?.optJSONArray("list")
    }
}
