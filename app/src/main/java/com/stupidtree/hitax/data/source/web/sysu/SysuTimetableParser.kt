package com.stupidtree.hitax.data.source.web.sysu

import com.stupidtree.hitax.data.model.eas.CourseItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * 中山大学教务课表解析器
 *
 * jwxt 学生课表接口（`/timetable-search/classTableInfo/selectStudentClassTable?academicYear=&weekly=`）
 * 返回形如：
 * ```
 * { "code":200, "data":[
 *     { "section":4, "weekly":1,
 *       "monday":"xslx:bk;;kcmc:高等数学一（I）;;rkjs:邹雄;;skdd:第五教学楼(逸夫楼)逸401;;0;;jxb:202610078;;xkzrs:154
 *                 ;;xslx:bk;;sksj:;;kclb:;;zs:;;js:周;;sfttk:;;sameTeacherDiffPlaceTime:邹雄//南校园第五教学楼(逸夫楼)逸401
 *                 ;;bajcpk:0;;ksjssksj:;;xwjsxm:;;skrq:第1周/2026-09-07/星期一/",
 *       "tuesday":"...", ... } ] }
 * ```
 * 一个格子可含多个教学班，用 `,,` 分隔；每个教学班是 `key:value` 用 `;;` 分隔的键值串。
 * 关键字段：`kcmc` 课程名、`rkjs` 任课教师、`skdd` 上课地点、`jxb` 教学班、
 * `skrq` 上课日期（`第N周/yyyy-MM-dd/星期X/`）、`js`（周/单周/双周）。
 *
 * 本类只做「纯字符串/JSON -> CourseItem」的转换，不涉及网络，便于单元测试。
 */
object SysuTimetableParser {

    /** 星期字段名，索引 1..7 */
    val WEEKDAY_FIELDS = arrayOf(
        "", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"
    )

    /** 中文星期，兼容部分版本返回中文表头 */
    private val WEEKDAY_CN = arrayOf(
        "", "周一", "周二", "周三", "周四", "周五", "周六", "周日"
    )

    private const val BLOCK_SEP = ",,"
    private const val PART_SEP = ";;"

    /** 一节大课的最大节数，超过的部分做截断保护 */
    const val MAX_SECTION = 16

    /** 中文星期 -> 1..7 */
    private val CN_DOW = mapOf(
        "星期一" to 1, "星期二" to 2, "星期三" to 3, "星期四" to 4,
        "星期五" to 5, "星期六" to 6, "星期日" to 7, "星期天" to 7,
        "周一" to 1, "周二" to 2, "周三" to 3, "周四" to 4, "周五" to 5, "周六" to 6, "周日" to 7
    )

    /**
     * 解析同一周内的一页课表
     *
     * @param data 接口 data 数组
     * @param week 当前查询的周次（接口未给出周次时的兜底值）
     */
    fun parseWeek(data: JSONArray?, week: Int): List<CourseItem> {
        val result = mutableListOf<CourseItem>()
        if (data == null) return result
        for (i in 0 until data.length()) {
            val row = data.optJSONObject(i) ?: continue
            val section = optSection(row)
            if (section <= 0) continue
            val rowWeek = row.optInt("weekly", week).let { if (it > 0) it else week }
            for (dow in 1..7) {
                val raw = firstNonEmpty(row, WEEKDAY_FIELDS[dow], WEEKDAY_CN[dow]) ?: continue
                parseCell(raw, section, rowWeek, result, dow)
            }
        }
        return result
    }

    private fun optSection(row: JSONObject): Int {
        for (key in arrayOf("section", "SECTION", "jc", "sectionNum", "paiming")) {
            if (row.has(key) && !row.isNull(key)) {
                val v = row.optString(key).trim()
                v.toIntOrNull()?.let { if (it > 0) return it }
                Regex("(\\d+)").find(v)?.let {
                    it.groupValues[1].toIntOrNull()?.let { n -> if (n > 0) return n }
                }
            }
        }
        return -1
    }

    private fun firstNonEmpty(row: JSONObject, vararg keys: String): String? {
        for (k in keys) {
            if (!row.has(k) || row.isNull(k)) continue
            val v = row.optString(k).trim()
            if (v.isNotEmpty() && v != "null" && v != "-") return v
        }
        return null
    }

    /**
     * 解析单个格子
     *
     * @param fallbackDow 当格子内没有日期信息时使用的星期
     */
    fun parseCell(
        raw: String,
        section: Int,
        week: Int,
        out: MutableList<CourseItem>,
        fallbackDow: Int = -1
    ) {
        for (block in raw.split(BLOCK_SEP)) {
            val b = block.trim().trim('\n', '\r')
            if (b.isEmpty()) continue
            val map = splitKv(b)
            // 课程名：优先 kcmc，退化到第一个非键值片段 / 整体文本
            val name = firstNotEmpty(
                map["kcmc"], map["courseName"], map["kcName"], map["kc"], plainFirst(b)
            ) ?: continue
            var teacher = firstNotEmpty(map["rkjs"], map["teacher"], map["jsxm"], map["skjs"])
            var place = firstNotEmpty(map["skdd"], map["classroom"], map["jsdd"], map["cdmc"])

            // 周次：优先 skrq 中的「第N周」，其次 js 单双周标记
            var weeks: MutableList<Int>? = null
            val round = Regex("第\\s*(\\d+)\\s*周").find(map["skrq"] ?: "")
            if (round != null) {
                round.groupValues[1].toIntOrNull()?.let { weeks = mutableListOf(it) }
            }
            if (weeks == null) {
                // 名称里可能带 [1-16周] / [1-8周(单)]
                val wk = Regex("\\[([^\\]]*周[^\\]]*)\\]").find(name)
                if (wk != null) weeks = parseWeeks(wk.groupValues[1])
            }
            // 单/双周过滤（js 字段：周 / 单周 / 双周）
            val js = (map["js"] ?: "").trim()
            if (weeks != null) {
                when {
                    js.contains("单") -> weeks = weeks!!.filter { it % 2 == 1 }.toMutableList()
                    js.contains("双") -> weeks = weeks!!.filter { it % 2 == 0 }.toMutableList()
                }
            }

            // 星期：优先 skrq 中的「星期X」，否则用表头
            var dow = fallbackDow
            val dowText = map["skrq"] ?: ""
            for ((k, v) in CN_DOW) {
                if (dowText.contains(k)) {
                    dow = v
                    break
                }
            }
            if (dow <= 0) dow = fallbackDow
            if (dow <= 0) continue

            // 清洗课程名：去掉方括号补充说明，保留主体
            val cleanName = name.replace(Regex("\\[([^\\]]*)\\]"), "").trim()
            if (cleanName.isEmpty()) continue
            teacher = teacher?.replace(Regex("\\[([^\\]]*)\\]"), "")?.trim()?.ifEmpty { null }

            val item = CourseItem()
            item.name = cleanName
            item.teacher = teacher
            item.classroom = place
            item.dow = dow
            item.begin = section
            item.last = 1
            item.weeks.clear()
            item.weeks.addAll(weeks ?: mutableListOf(week))
            out.add(item)
        }
    }

    /**
     * 把 `a:1;;b:2;;0;;c:3` 解析为 map。
     * 没有冒号的片段（如单独的 `0`）忽略。
     */
    fun splitKv(block: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (seg in block.split(PART_SEP)) {
            val idx = seg.indexOf(':')
            if (idx <= 0) continue
            val k = seg.substring(0, idx).trim()
            if (k.isEmpty() || k.contains(' ')) continue
            val v = seg.substring(idx + 1).trim()
            // 同名 key 保留第一个（部分接口有重复的 xslx / sksj）
            if (!map.containsKey(k)) map[k] = v
        }
        return map
    }

    /** 取第一个非空值 */
    private fun firstNotEmpty(vararg values: String?): String? {
        for (v in values) {
            if (!v.isNullOrBlank() && v != "null") return v.trim()
        }
        return null
    }

    /** 非键值格式的单元格（老版本 / 第三方格式）里，第一个片段视为课程名 */
    private fun plainFirst(block: String): String? {
        for (seg in block.split(PART_SEP)) {
            val s = seg.trim()
            if (s.isEmpty()) continue
            if (s.contains(':')) continue
            return s
        }
        return null
    }

    /**
     * 解析周次文本，支持 `1-16周`、`1,3,5周`、`1-16周(单)`、`1-16周(双)`、`1-16单周`
     */
    fun parseWeeks(text: String?): MutableList<Int> {
        val res = mutableListOf<Int>()
        if (text.isNullOrBlank()) return res
        var t = text.replace(" ", "")
            .replace("第", "")
            .replace("周", "")
            .replace("（", "(")
            .replace("）", ")")
        var odd = false
        var even = false
        when {
            t.contains("单") -> {
                odd = true
                t = t.replace("单", "")
            }
            t.contains("双") -> {
                even = true
                t = t.replace("双", "")
            }
        }
        t = t.replace("(", "").replace(")", "")
        for (seg in t.split(",", "，", "、")) {
            if (seg.isBlank()) continue
            if (seg.contains("-")) {
                val ft = seg.split("-")
                val from = ft.getOrNull(0)?.trim()?.toIntOrNull() ?: continue
                val to = ft.getOrNull(1)?.trim()?.toIntOrNull() ?: from
                for (i in from..to) {
                    if (odd && i % 2 == 0) continue
                    if (even && i % 2 != 0) continue
                    if (!res.contains(i)) res.add(i)
                }
            } else {
                val n = seg.trim().toIntOrNull() ?: continue
                if (odd && n % 2 == 0) continue
                if (even && n % 2 != 0) continue
                if (!res.contains(n)) res.add(n)
            }
        }
        res.sort()
        return res
    }

    /**
     * 合并「同一门课、同一星期、同一老师/地点」的条目，把周次求并集、节次求区间。
     * 按周查询时同一门课会出现多条记录（每周一条），必须合并。
     */
    fun merge(courses: List<CourseItem>): MutableList<CourseItem> {
        val map = LinkedHashMap<String, CourseItem>()
        for (c in courses) {
            if (c.name.isNullOrEmpty()) continue
            val key = listOf(
                c.name, c.teacher ?: "", c.classroom ?: "", c.dow.toString()
            ).joinToString("\u0001")
            val exist = map[key]
            if (exist == null) {
                val copy = CourseItem()
                copy.name = c.name
                copy.teacher = c.teacher
                copy.classroom = c.classroom
                copy.dow = c.dow
                copy.begin = c.begin
                copy.last = c.last
                copy.weeks = ArrayList(c.weeks)
                map[key] = copy
            } else {
                val newBegin = minOf(exist.begin, c.begin)
                val existEnd = exist.begin + exist.last - 1
                val cEnd = c.begin + c.last - 1
                val newEnd = maxOf(existEnd, cEnd)
                exist.begin = newBegin
                exist.last = newEnd - newBegin + 1
                for (w in c.weeks) if (!exist.weeks.contains(w)) exist.weeks.add(w)
            }
        }
        val out = ArrayList(map.values)
        out.forEach { it.weeks.sort() }
        out.sortWith(compareBy({ it.dow }, { it.begin }, { it.name ?: "" }))
        return out
    }

    /**
     * 默认课表结构（中大作息，导入页可自行微调）
     *
     * HITA 的课表结构按「节」存：schedule[i] 即第 i+1 节的起止时间，
     * 这里给出 1..14 节的作息时间。
     */
    fun buildScheduleStructureFromSections(): MutableList<com.stupidtree.hitax.data.model.timetable.TimePeriodInDay> {
        val res = mutableListOf<com.stupidtree.hitax.data.model.timetable.TimePeriodInDay>()
        var idx = 0
        while (idx < MAX_SECTION) {
            val from = SECTION_START_TIMES.getOrNull(idx) ?: break
            val to = SECTION_END_TIMES.getOrNull(idx) ?: break
            res.add(
                com.stupidtree.hitax.data.model.timetable.TimePeriodInDay(
                    com.stupidtree.hitax.data.model.timetable.TimeInDay(from.first, from.second),
                    com.stupidtree.hitax.data.model.timetable.TimeInDay(to.first, to.second)
                )
            )
            idx++
        }
        return res
    }

    /** 单节起始/结束时间（中大南校园作息，可在导入页调整） */
    private val SECTION_START_TIMES = listOf(
        8 to 0, 8 to 55, 10 to 0, 10 to 55,
        14 to 30, 15 to 25, 16 to 20, 17 to 15,
        19 to 0, 19 to 55, 20 to 50, 21 to 45,
        22 to 40, 23 to 35
    )

    private val SECTION_END_TIMES = listOf(
        8 to 45, 9 to 40, 10 to 45, 11 to 40,
        15 to 15, 16 to 10, 17 to 5, 18 to 0,
        19 to 45, 20 to 40, 21 to 35, 22 to 30,
        23 to 25, 0 to 20
    )
}
