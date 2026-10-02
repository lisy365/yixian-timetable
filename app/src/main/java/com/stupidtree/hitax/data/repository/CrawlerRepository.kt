package com.stupidtree.hitax.data.repository

import android.content.Context
import android.util.Log
import com.stupidtree.hitax.data.source.web.sysu.SysuCrawler
import com.stupidtree.hitax.data.source.web.sysu.SysuPublicCrawler
import org.json.JSONArray
import org.json.JSONObject

/**
 * 爬取结果读取层（v1.0.7）
 *
 * [CrawlerStorage] 负责「写」，本类负责「读」，把 `files/crawler/index.json` 与各分类下的
 * JSON 文件整理成 UI 可直接用的模型：
 *
 * - [TeachingResource]：培养方案 / 教学大纲等可在 App 内查看的长文资源；
 * - [TeacherProfile]：任课教师简介（课程聚合 + 学院官网教师名录链接 + 大纲里的补充信息）。
 *
 * 所有方法都是阻塞的（读本地文件），调用方放在子线程。
 */
object CrawlerRepository {

    private const val TAG = "CrawlerRepository"

    // ------------------------------------------------------------------
    // 资源（培养方案 / 教学大纲 / 其它长文）
    // ------------------------------------------------------------------

    /** 可在 App 内查看的一份教学资料 */
    data class TeachingResource(
        val name: String,
        val category: String,
        val categoryLabel: String,
        val summary: String,
        val sourceUrl: String,
        val savedAtText: String,
        /** 用于展示的正文（已按需截断） */
        val body: String,
        val chars: Int,
        /** 关联的课程名（教学大纲才有） */
        val courseName: String?
    )

    /** 分类 id -> 中文名（与 [CrawlerStorage.CATEGORIES] 同源） */
    fun categoryLabel(category: String): String =
        CrawlerStorage.CATEGORIES[category] ?: category

    /**
     * 读取某一分类下的全部资源，按保存时间倒序
     *
     * @param maxChars 单份内容最多展示多少字（避免巨大 JSON 卡住 UI）
     */
    fun loadResources(context: Context, vararg categories: String, maxChars: Int = 20000): List<TeachingResource> {
        val want = categories.toSet()
        val out = ArrayList<TeachingResource>()
        for (item in CrawlerStorage.readIndex(context)) {
            val category = item.optString("category")
            if (!want.contains(category)) continue
            val rel = item.optString("path")
            if (rel.isEmpty()) continue
            val file = java.io.File(CrawlerStorage.rootDir(context), rel)
            if (!file.exists()) continue
            val raw = try {
                file.readText(Charsets.UTF_8)
            } catch (e: Exception) {
                Log.w(TAG, "读取失败 $rel", e)
                continue
            }
            val body = extractBody(raw, maxChars)
            val name = item.optString("name")
            out.add(
                TeachingResource(
                    name = name,
                    category = category,
                    categoryLabel = categoryLabel(category),
                    summary = item.optString("summary"),
                    sourceUrl = item.optString("sourceUrl"),
                    savedAtText = item.optString("savedAtText"),
                    body = body,
                    chars = body.length,
                    courseName = courseNameOf(category, name)
                )
            )
        }
        // 倒序：index.json 本身就是倒序写入的，这里再按时间兜底排一次
        return out.sortedByDescending { it.savedAtText }
    }

    /** 从保存的 JSON 里抽出可读正文 */
    fun extractBody(raw: String, maxChars: Int = 20000): String {
        val pretty = try {
            val obj = JSONObject(raw)
            // 优先 payload；没有就退化到 payloadText，再退化到整份 JSON
            val payload = obj.opt("payload")
            val text = obj.optString("payloadText", "")
            when {
                payload is JSONObject -> payload.toString(2)
                payload is JSONArray -> payload.toString(2)
                text.isNotEmpty() -> text
                else -> obj.toString(2)
            }
        } catch (e: Exception) {
            raw
        }
        return if (pretty.length > maxChars) pretty.substring(0, maxChars) + "\n\n…（内容过长，已截断）" else pretty
    }

    /** 「教学大纲-高等数学」-> 高等数学 */
    private fun courseNameOf(category: String, name: String): String? {
        if (category != SysuCrawler.CAT_SYLLABUS) return null
        val idx = name.indexOf('-')
        return if (idx >= 0 && idx < name.length - 1) name.substring(idx + 1) else null
    }

    /** 培养方案 / 教学大纲是否已抓过 */
    fun hasResources(context: Context): Boolean = CrawlerStorage.savedCount(context) > 0

    // ------------------------------------------------------------------
    // 任课教师
    // ------------------------------------------------------------------

    /** 任课教师简介（数据来自爬取结果 + 本地课表） */
    data class TeacherProfile(
        val name: String,
        /** 任教课程名 */
        val courses: List<String>,
        /** 所属学院（来自学生信息里的学院名，作为近似归属） */
        val faculty: String?,
        /** 职称 / 头衔（学院官网页面上抓到的，可能为空） */
        val title: String?,
        /** 简介正文（来自学院官网栏目文本片段 / 大纲补充） */
        val intro: String?,
        /** 学院官网教师名录链接（可跳转浏览器） */
        val officialUrl: String?,
        /** 关联的教学大纲资源 */
        val syllabi: List<TeachingResource>
    ) {
        val hasCrawledInfo: Boolean
            get() = !title.isNullOrBlank() || !intro.isNullOrBlank() || officialUrl != null
    }

    /**
     * 构造某位教师的简介
     *
     * 数据来源（按优先级）：
     * 1. 爬取到的「任课教师信息」（`teacher` 分类，由课表聚合）→ 任教课程；
     * 2. 学生信息里的学院名 → 归属学院；
     * 3. 学院官网公开栏目文本（`college` 分类）→ 职称、简介片段；
     * 4. 教学大纲（`syllabus` 分类）→ 该教师所授课程的大纲条目。
     *
     * @param fallbackCourses 本地课表里的课程（爬取前也能用）
     */
    fun buildTeacherProfile(
        context: Context,
        teacherName: String,
        fallbackCourses: List<String> = emptyList(),
        fallbackFaculty: String? = null
    ): TeacherProfile {
        val name = teacherName.trim()
        var courses: List<String> = emptyList()

        // 1) 爬取到的教师 -> 课程映射
        for (item in CrawlerStorage.readIndex(context)) {
            if (item.optString("category") != SysuCrawler.CAT_TEACHER) continue
            val file = java.io.File(CrawlerStorage.rootDir(context), item.optString("path"))
            if (!file.exists()) continue
            try {
                val root = JSONObject(file.readText(Charsets.UTF_8))
                val payload = root.opt("payload")
                val arr = when (payload) {
                    is JSONArray -> payload
                    is JSONObject -> payload.optJSONArray("teachers")
                    else -> null
                }
                if (arr == null) continue
                for (i in 0 until arr.length()) {
                    val t = arr.optJSONObject(i) ?: continue
                    if (t.optString("name").trim() != name) continue
                    val cs = t.optJSONArray("courses")
                    if (cs != null) {
                        courses = (0 until cs.length()).mapNotNull { cs.optString(it).takeIf { s -> s.isNotBlank() } }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "读取教师信息失败", e)
            }
        }
        if (courses.isEmpty()) courses = fallbackCourses

        // 2) 学院名
        val faculty = readStudentFaculty(context) ?: fallbackFaculty

        // 3) 学院官网里的职称 / 简介片段
        var title: String? = null
        var intro: String? = null
        var officialUrl: String? = null
        for (r in loadResources(context, SysuPublicCrawler.CATEGORY_COLLEGE, maxChars = 200000)) {
            val hit = findTeacherMention(r.body, name) ?: continue
            if (intro == null) intro = hit
            if (officialUrl == null) officialUrl = r.sourceUrl.ifBlank { null }
            if (title == null) title = guessTitle(hit)
        }
        if (officialUrl == null) {
            // 兜底：给出学院官网首页，用户可自行搜索
            officialUrl = readStudentFacultyUrl(context)
        }

        // 4) 关联大纲
        val syllabi = loadResources(context, SysuCrawler.CAT_SYLLABUS, maxChars = 200000)
            .filter { s -> courses.any { it.isNotBlank() && s.name.contains(it) } }

        return TeacherProfile(
            name = name,
            courses = courses,
            faculty = faculty,
            title = title,
            intro = intro,
            officialUrl = officialUrl,
            syllabi = syllabi
        )
    }

    /**
     * 在一段文本里找教师名字附近的介绍片段（纯字符串处理，可单测）
     *
     * @return 命中的片段（截取名字前后各 120 字），没命中返回 null
     */
    fun findTeacherMention(text: String, name: String, radius: Int = 120): String? {
        if (name.isBlank() || text.isBlank()) return null
        val idx = text.indexOf(name)
        if (idx < 0) return null
        val from = (idx - radius).coerceAtLeast(0)
        val to = (idx + name.length + radius).coerceAtMost(text.length)
        return text.substring(from, to).trim()
    }

    /** 从片段里猜职称（中文高校常见几种） */
    fun guessTitle(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val titles = listOf(
            "教授", "副教授", "助理教授", "讲师", "特聘教授", "研究员", "副研究员",
            "助理研究员", "博士后", "博士生导师", "硕士生导师", "教授级高级工程师"
        )
        // 长的优先，避免「教授」先命中「副教授」
        for (t in titles.sortedByDescending { it.length }) {
            if (text.contains(t)) return t
        }
        return null
    }

    /** 学生信息里的学院名 */
    fun readStudentFaculty(context: Context): String? {
        for (item in CrawlerStorage.readIndex(context)) {
            if (item.optString("category") != SysuCrawler.CAT_PROFILE) continue
            if (!item.optString("name").contains("学生信息")) continue
            val file = java.io.File(CrawlerStorage.rootDir(context), item.optString("path"))
            if (!file.exists()) continue
            try {
                val root = JSONObject(file.readText(Charsets.UTF_8))
                val payload = root.optJSONObject("payload") ?: continue
                for (k in listOf("facultyName", "yxmc", "collegeName", "departmentName")) {
                    val v = payload.optString(k)
                    if (v.isNotBlank() && v != "null") return v
                }
            } catch (e: Exception) {
                // ignore
            }
        }
        return null
    }

    /** 学院名 -> 官网地址（内置常用学院，用于教师简介页的「去官网查看」） */
    private fun readStudentFacultyUrl(context: Context): String? {
        val faculty = readStudentFaculty(context) ?: return null
        val hit = SysuPublicCrawler.COLLEGES.firstOrNull { c ->
            faculty.contains(c.name.replace("学院", "")) || c.name.contains(faculty.replace("学院", ""))
        }
        return hit?.baseUrl ?: SysuPublicCrawler.JWB.baseUrl
    }

    /** 全部出现过的教师名（课表聚合 + 本地课表兜底） */
    fun allTeacherNames(context: Context): List<String> {
        val set = LinkedHashSet<String>()
        for (item in CrawlerStorage.readIndex(context)) {
            if (item.optString("category") != SysuCrawler.CAT_TEACHER) continue
            val file = java.io.File(CrawlerStorage.rootDir(context), item.optString("path"))
            if (!file.exists()) continue
            try {
                val root = JSONObject(file.readText(Charsets.UTF_8))
                val payload = root.opt("payload")
                val arr = when (payload) {
                    is JSONArray -> payload
                    is JSONObject -> payload.optJSONArray("teachers")
                    else -> null
                } ?: continue
                for (i in 0 until arr.length()) {
                    val n = arr.optJSONObject(i)?.optString("name")?.trim().orEmpty()
                    if (n.isNotEmpty()) set.add(n)
                }
            } catch (e: Exception) {
                // ignore
            }
        }
        return set.toList()
    }
}
