package com.stupidtree.hitax.utils

import com.stupidtree.hitax.data.model.timetable.TimePeriodInDay

/**
 * 时间表网格布局（纯逻辑，不引用 Android API，可在 JVM 中单测）
 *
 * 设计要点（v1.0.5 重构）：
 * - 每天固定 [FIXED_PERIODS] 节课（中山大学教务部作息：第 1~11 节）；
 * - 网格按「节」等分行，不再按真实钟点（60 分钟）分格 ——
 *   旧实现按小时缩放的后果是：一节课 45 分钟只占 3/4 格，
 *   两节连排的课程块会盖住下一节的行，视觉上永远对不齐；
 * - 课程块的行号直接取事件的 [com.stupidtree.hitax.data.model.timetable.EventItem.fromNumber] /
 *   `lastNumber`（导入课表时已按节次写入），缺失时按作息表时间反推。
 */
object TimetableGrid {

    /** 每天固定节数：中山大学教务部作息表共 11 节 */
    const val FIXED_PERIODS = 11

    /** 每行（每节课）的期望高度（dp） */
    const val ROW_HEIGHT_DP = 60

    /** 每行最小高度（dp）：保证「第1节 08:00」这样的标签能显示完整 */
    const val MIN_ROW_HEIGHT_DP = 52

    /**
     * 计算每行实际高度（px）
     * @param density 屏幕密度
     * @param availableHeightPx 可用高度（<=0 表示不限，用期望高度）
     */
    fun rowHeightPx(density: Float, availableHeightPx: Int = 0, minVisiblePeriods: Int = FIXED_PERIODS): Int {
        val preferred = (ROW_HEIGHT_DP * density).toInt()
        val minPx = (MIN_ROW_HEIGHT_DP * density).toInt()
        if (availableHeightPx <= 0 || minVisiblePeriods <= 0) return preferred.coerceAtLeast(minPx)
        val fit = availableHeightPx / minVisiblePeriods
        return fit.coerceIn(minPx, preferred)
    }

    /**
     * 把作息表规整为「恰好 [FIXED_PERIODS] 节」：
     * - 不足则用后一节的开始时间顺延补齐（每节 45 分钟 + 10 分钟课间）；
     * - 超出则截断（中山大学作息表只有 11 节，旧版本默认的 12~14 节是错的）。
     *
     * 已经是 11 节的情况返回原列表的浅拷贝，保持用户自定义的微调。
     */
    fun normalize(
        structure: List<TimePeriodInDay>,
        periods: Int = FIXED_PERIODS
    ): List<TimePeriodInDay> {
        val out = ArrayList<TimePeriodInDay>(periods)
        for (i in 0 until periods) {
            val src = structure.getOrNull(i)
            if (src != null) {
                out.add(TimePeriodInDay(src.from, src.to))
            } else {
                val prev = out.lastOrNull() ?: break
                // 上一节结束后休息 10 分钟，上课时长 45 分钟（与中大作息一致）
                val from = prev.to.getAdded(10)
                out.add(TimePeriodInDay(from, from.getAdded(45)))
            }
        }
        while (out.size < periods) {
            // 理论上不可达（上面的循环已补齐），防御性兜底
            val prev = out.lastOrNull() ?: break
            val from = prev.to.getAdded(10)
            out.add(TimePeriodInDay(from, from.getAdded(45)))
        }
        return out
    }

    /**
     * 判断一份作息表是否「就是中大官方 11 节」。
     * 用于升级时自动把老用户手里的 12/14 节错误结构迁移过来。
     */
    fun isOfficial(structure: List<TimePeriodInDay>): Boolean {
        val official = com.stupidtree.hitax.data.model.timetable.Timetable().getDefaultTimeStructure()
        if (structure.size != official.size) return false
        for (i in official.indices) {
            if (structure[i].from != official[i].from) return false
            if (structure[i].to != official[i].to) return false
        }
        return true
    }

    /**
     * 是否属于「老版本默认结构」——即 v1.0.4 及之前 `getDefaultTimeStructure()` 产出的 14 节，
     * 或更早的 12 节。这类结构的特征：下午第一节从 14:30 开始（官方应为 14:20）、
     * 且尾部有 21:45 之后的多余节次。
     */
    fun isLegacyDefault(structure: List<TimePeriodInDay>): Boolean {
        if (structure.isEmpty()) return true
        val fifth = structure.getOrNull(4) ?: return false
        val legacyAfternoon = fifth.from.hour == 14 && fifth.from.minute == 30
        val tooLong = structure.size > FIXED_PERIODS
        if (legacyAfternoon) return true
        if (tooLong && structure.size <= 14) {
            // 12 节老结构：第 5 节 14:30 或第 9 节 19:00、第 11 节 20:50、第 12 节 21:45
            val tail = structure.getOrNull(11)
            if (tail != null && tail.from.hour == 21 && tail.from.minute == 45) return true
        }
        return false
    }

    /**
     * 事件落在网格中的行区间（0-based，闭区间）。
     *
     * @param fromNumber 事件起始节次（1-based，<=0 表示未知）
     * @param lastNumber 事件跨的节数（>=1）
     * @param fromMinutes 事件开始时刻（当天分钟数，-1 表示未知）
     * @param toMinutes 事件结束时刻（当天分钟数，-1 表示未知）
     * @param structure 作息表（已规整为 [periods] 节）
     */
    fun rowRange(
        fromNumber: Int,
        lastNumber: Int,
        fromMinutes: Int,
        toMinutes: Int,
        structure: List<TimePeriodInDay>,
        periods: Int = FIXED_PERIODS
    ): Pair<Int, Int>? {
        if (periods <= 0) return null
        // 1) 优先使用节次号（导入课表一定带；手动新建的课程也在 EventItem 里写了）
        if (fromNumber in 1..periods) {
            val span = lastNumber.coerceAtLeast(1)
            val end = (fromNumber + span - 1).coerceAtMost(periods)
            return (fromNumber - 1) to (end - 1)
        }
        // 2) 退化：按时间落入哪一节
        if (fromMinutes < 0 || toMinutes < 0) return null
        var start = -1
        var end = -1
        for (i in 0 until minOf(periods, structure.size)) {
            val p = structure[i]
            val pf = p.from.hour * 60 + p.from.minute
            val pt = p.to.hour * 60 + p.to.minute
            val ptSafe = if (pt <= pf) pf + 45 else pt
            if (fromMinutes < ptSafe && toMinutes > pf) {
                if (start < 0) start = i
                end = i
            }
        }
        if (start < 0) {
            // 完全落在课间：归到时间上距离最近的一节
            var best = 0
            var bestDist = Int.MAX_VALUE
            for (i in 0 until minOf(periods, structure.size)) {
                val p = structure[i]
                val pf = p.from.hour * 60 + p.from.minute
                val d = kotlin.math.abs(pf - fromMinutes)
                if (d < bestDist) {
                    bestDist = d
                    best = i
                }
            }
            return best to best
        }
        return start to end
    }

    /** 单个节次的行区间 */
    fun periodRow(index: Int, periods: Int = FIXED_PERIODS): Int? =
        if (index in 0 until periods) index else null

    /**
     * 网格内容总高度（px）
     */
    fun totalHeightPx(rowHeight: Int, periods: Int = FIXED_PERIODS): Int =
        (rowHeight.coerceAtLeast(1)) * periods.coerceAtLeast(1)

    /**
     * 一节课的标签文本，形如 `第3节` / `第3-4节`
     */
    fun periodLabel(fromNumber: Int, lastNumber: Int): String {
        val start = fromNumber.coerceAtLeast(1)
        val end = (fromNumber + lastNumber.coerceAtLeast(1) - 1).coerceAtLeast(start)
        return if (end <= start) "第${start}节" else "第${start}-${end}节"
    }

    /**
     * 左侧栏「第N节 + 时间」的时间文本，形如 `08:00 - 08:45`（补零，避免 15:5 这种显示）
     */
    fun timeText(period: TimePeriodInDay?): String {
        if (period == null) return ""
        return "${hhmm(period.from)} - ${hhmm(period.to)}"
    }

    /** 补零的 HH:mm */
    fun hhmm(t: com.stupidtree.hitax.data.model.timetable.TimeInDay): String =
        String.format("%02d:%02d", t.hour, t.minute)

    /** 索引（0-based）-> 节次号（1-based） */
    fun periodNumberOf(index: Int): Int = index + 1

    /**
     * 一节「节次标签」的短文本：数字部分，用于左侧大号数标
     */
    fun periodNumberText(index: Int): String = periodNumberOf(index).toString()

    // ------------------------------------------------------------------
    // 课表里「该显示哪些事件」（v1.0.7）
    // ------------------------------------------------------------------

    /**
     * 该事件是否应该出现在**周课表网格**里。
     *
     * 用户反馈：「待办事项不应出现在时间表中」。
     * 根因：待办复用 `events` 表（`type = OTHER`、`subjectId = 'YIXIAN_TASK'`、
     * `timetableId = ''`），而周视图取数走的是
     * `EventItemDao.getEventsDuring(from, to)` —— 只按时间范围过滤，
     * 于是「截止时间落在这一周」的待办就被画进了课表格子里。
     *
     * 判断依据（任一命中即排除）：
     * 1. [subjectId] 是待办专用标记 `YIXIAN_TASK`（见 `EventItem.isTask()`）；
     * 2. 事件没有挂课表（`timetableId` 为空）—— 课表格子只承载某套课表下的课程；
     * 3. 事件类型是标签占位（`TAG`，`listAdapter` 用的伪数据，不该落到网格）。
     *
     * 注意 `EXAM` / `OTHER` 里挂在课表下的事件（例如教务导入的考试、用户自己加的日程）
     * **仍然保留**，它们本来就是课表的一部分。
     */
    fun isVisibleInTimetable(
        subjectId: String?,
        timetableId: String?,
        type: String?
    ): Boolean {
        if (subjectId == TASK_SUBJECT_ID) return false
        if (type == "TAG") return false
        // 待办与「今日时间轴专用」的条目都不挂在课表下
        if (timetableId.isNullOrBlank()) return false
        return true
    }

    /** 与 `EventItem.SUBJECT_ID_TASK` 保持一致（这里不引用 EventItem，保持纯逻辑可单测） */
    const val TASK_SUBJECT_ID = "YIXIAN_TASK"

    /** 过滤掉不该出现在课表里的事件（保持原有顺序） */
    fun <T> filterForTimetable(
        events: List<T>,
        subjectId: (T) -> String?,
        timetableId: (T) -> String?,
        type: (T) -> String?
    ): List<T> = events.filter { isVisibleInTimetable(subjectId(it), timetableId(it), type(it)) }
}
