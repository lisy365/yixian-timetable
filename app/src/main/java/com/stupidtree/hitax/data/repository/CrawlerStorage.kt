package com.stupidtree.hitax.data.repository

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 教务信息本地仓库（v1.0.5 需求 3）
 *
 * 为什么不进 Room：
 * 爬到的内容是「原样文档 + 索引」，字段随教务系统改版而变（培养方案、教学大纲尤其如此），
 * 落到固定表结构需要频繁 Migration，风险远大于收益。这里用
 * `files/crawler/<分类>/<yyyMMdd-HHmmss>-<slug>.json` 保存原文，
 * 再维护一份 `files/crawler/index.json` 供 UI 列表展示。
 *
 * 目录布局：
 * ```
 * files/crawler/
 *   index.json                 索引（每次爬取追加一条）
 *   index.txt                  人类可读的概览（方便用户用文件管理器看）
 *   profile/                   学生信息 / 培养方案
 *   syllabus/                  教学大纲
 *   teacher/                   任课教师
 *   exam/                      考试安排
 *   score/                     成绩
 *   timetable/                 课表原始数据
 * ```
 */
object CrawlerStorage {

    private const val TAG = "CrawlerStorage"
    private const val ROOT_DIR = "crawler"
    private const val INDEX_FILE = "index.json"
    private const val INDEX_TEXT = "index.txt"

    const val CATEGORY_PROFILE = "profile"
    const val CATEGORY_SYLLABUS = "syllabus"
    const val CATEGORY_TEACHER = "teacher"
    const val CATEGORY_EXAM = "exam"
    const val CATEGORY_SCORE = "score"
    const val CATEGORY_TIMETABLE = "timetable"

    /** v1.0.5：校级公开站点（教务部等） */
    const val CATEGORY_SCHOOL = "school"

    /** v1.0.5：各学院官网公开栏目 */
    const val CATEGORY_COLLEGE = "college"

    /** 全部一级分类（建目录 + UI 展示用） */
    val CATEGORIES = linkedMapOf(
        CATEGORY_PROFILE to "培养方案 / 学生信息",
        CATEGORY_SYLLABUS to "课程教学大纲",
        CATEGORY_TEACHER to "任课教师信息",
        CATEGORY_EXAM to "考试安排",
        CATEGORY_SCORE to "成绩",
        CATEGORY_TIMETABLE to "课表原始数据",
        CATEGORY_SCHOOL to "教务部公开站点",
        CATEGORY_COLLEGE to "学院官网公开栏目"
    )

    /** 根目录 */
    fun rootDir(context: Context): File =
        File(context.filesDir, ROOT_DIR).apply { if (!exists()) mkdirs() }

    fun categoryDir(context: Context, category: String): File =
        File(rootDir(context), category).apply { if (!exists()) mkdirs() }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())

    /** 把任意文本变成安全的文件名片段 */
    fun slugify(raw: String?): String {
        if (raw.isNullOrBlank()) return "item"
        val s = raw.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
        return when {
            s.isEmpty() -> "item"
            s.length <= 40 -> s
            else -> s.substring(0, 40)
        }
    }

    /**
     * 保存一份爬取结果
     *
     * @param category 一级分类
     * @param name 人类可读名称（也用于文件名）
     * @param payload 正文（可为 JSON 字符串、HTML 文本或纯文本）
     * @param summary 一句话摘要，写进索引
     * @param sourceUrl 来源地址，便于回溯
     * @return 写入的文件
     */
    fun save(
        context: Context,
        category: String,
        name: String,
        payload: String,
        summary: String = "",
        sourceUrl: String? = null
    ): File {
        val dir = categoryDir(context, category)
        val file = File(dir, "${timestamp()}-${slugify(name)}.json")
        val obj = JSONObject()
        obj.put("name", name)
        obj.put("category", category)
        obj.put("summary", summary)
        obj.put("sourceUrl", sourceUrl ?: "")
        obj.put("savedAt", System.currentTimeMillis())
        obj.put(
            "savedAtText",
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        )
        // payload 尽量按 JSON 存；不是合法 JSON 就存成字符串
        val parsed = try {
            if (payload.trimStart().startsWith("{") || payload.trimStart().startsWith("[")) {
                JSONObject.wrap(org.json.JSONTokener(payload).nextValue())
            } else null
        } catch (e: Exception) {
            null
        }
        if (parsed != null) obj.put("payload", parsed) else obj.put("payloadText", payload)
        file.writeText(obj.toString(2), Charsets.UTF_8)
        appendIndex(context, file, name, category, summary, sourceUrl)
        Log.i(TAG, "saved ${file.absolutePath}")
        return file
    }

    /** 保存原始文本（HTML 等），不做 JSON 包装 */
    fun saveRaw(context: Context, category: String, name: String, text: String, sourceUrl: String? = null): File {
        val dir = categoryDir(context, category)
        val file = File(dir, "${timestamp()}-${slugify(name)}.txt")
        file.writeText(text, Charsets.UTF_8)
        appendIndex(context, file, name, category, "原始文本 ${text.length} 字", sourceUrl)
        return file
    }

    private fun appendIndex(
        context: Context,
        file: File,
        name: String,
        category: String,
        summary: String,
        sourceUrl: String?
    ) {
        try {
            val indexPath = File(rootDir(context), INDEX_FILE)
            val arr = if (indexPath.exists()) {
                try {
                    JSONArray(indexPath.readText(Charsets.UTF_8))
                } catch (e: Exception) {
                    JSONArray()
                }
            } else JSONArray()
            val item = JSONObject()
            item.put("name", name)
            item.put("category", category)
            item.put("summary", summary)
            item.put("sourceUrl", sourceUrl ?: "")
            item.put("path", file.relativeTo(rootDir(context)).path)
            item.put("size", file.length())
            item.put("savedAt", System.currentTimeMillis())
            item.put(
                "savedAtText",
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            )
            arr.put(item)
            indexPath.writeText(arr.toString(2), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "写索引失败", e)
        }
    }

    /** 读取索引（倒序，最新的在前） */
    fun readIndex(context: Context): List<JSONObject> {
        val indexPath = File(rootDir(context), INDEX_FILE)
        if (!indexPath.exists()) return emptyList()
        return try {
            val arr = JSONArray(indexPath.readText(Charsets.UTF_8))
            val list = ArrayList<JSONObject>(arr.length())
            for (i in arr.length() - 1 downTo 0) {
                arr.optJSONObject(i)?.let { list.add(it) }
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "读索引失败", e)
            emptyList()
        }
    }

    /** 已保存文件总数 */
    fun savedCount(context: Context): Int {
        val root = rootDir(context)
        return CATEGORIES.keys.sumOf { c -> File(root, c).listFiles()?.size ?: 0 }
    }

    /**
     * 写一份人类可读的概览，方便用户直接在文件管理器里查看
     */
    fun writeReadableIndex(context: Context): File {
        val sb = StringBuilder()
        sb.append("逸仙课表 · 教务信息爬取结果\n")
        sb.append("生成时间：")
            .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
            .append("\n\n")
        val index = readIndex(context)
        if (index.isEmpty()) {
            sb.append("（还没有任何爬取记录）\n")
        }
        for (item in index) {
            sb.append("[").append(item.optString("category")).append("] ")
                .append(item.optString("name")).append("\n")
            sb.append("    时间：").append(item.optString("savedAtText")).append("\n")
            val summary = item.optString("summary")
            if (summary.isNotEmpty()) sb.append("    摘要：").append(summary).append("\n")
            val url = item.optString("sourceUrl")
            if (url.isNotEmpty()) sb.append("    来源：").append(url).append("\n")
            sb.append("    文件：").append(item.optString("path")).append("\n\n")
        }
        val f = File(rootDir(context), INDEX_TEXT)
        f.writeText(sb.toString(), Charsets.UTF_8)
        return f
    }

    /** 导出为爬虫任务可用的「已保存文件」描述 */
    fun describe(context: Context): String {
        val root = rootDir(context)
        val sb = StringBuilder(root.absolutePath).append("\n")
        for ((c, label) in CATEGORIES) {
            val n = File(root, c).listFiles()?.size ?: 0
            sb.append("  ").append(label).append("：").append(n).append(" 个文件\n")
        }
        return sb.toString()
    }

    /** 清空全部爬取结果（设置页/需要时调用） */
    fun clearAll(context: Context) {
        val root = rootDir(context)
        root.listFiles()?.forEach { f ->
            if (f.isDirectory) f.deleteRecursively() else f.delete()
        }
        root.mkdirs()
        CATEGORIES.keys.forEach { categoryDir(context, it) }
    }
}
