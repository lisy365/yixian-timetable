package com.stupidtree.hitax.utils

import android.content.Context
import android.util.Log
import com.stupidtree.hitax.data.AppDatabase
import com.stupidtree.hitax.data.model.timetable.EventItem
import com.stupidtree.hitax.data.model.timetable.TimePeriodInDay
import com.stupidtree.hitax.data.model.timetable.Timetable
import java.util.Calendar

/**
 * 作息结构迁移（v1.0.5）
 *
 * 背景：v1.0.4 及更早版本的默认作息有 **14 节**，且下午第一节写成 14:30
 * （中大教务部官方作息为 11 节、下午第一节 14:20）。用户库里已经存了旧的
 * `scheduleStructure`（Room 的 JSON 列），不迁移的话：
 * - 网格仍按 14 节排布，与「每天固定 11 节」不符；
 * - 第 5 节课的时间仍然是错的。
 *
 * 迁移策略（保守、幂等）：
 * 1. 只替换「明显是老默认结构」的课表（[TimetableGrid.isLegacyDefault]），
 *    用户自己逐节改过的作息一律不动；
 * 2. 替换后，把该课表里 `fromNumber` 超过 11 的课程事件删掉
 *    （这些节次在官方作息里不存在，属于旧数据噪音）；
 * 3. 用 SharedPreferences 标记只跑一次，失败下次启动再试。
 *
 * 这一步不触碰数据库结构，所以**不需要升 Room version / 加 Migration**。
 */
object TimetableStructureMigration {

    private const val SP_NAME = "yixian_migration"
    private const val KEY_STRUCTURE_V105 = "structure_v105_done"
    private const val TAG = "TimetableStructureMigration"

    /** 是否已经迁移过 */
    fun isDone(context: Context): Boolean =
        context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_STRUCTURE_V105, false)

    /** 在后台线程调用（读写数据库） */
    fun runIfNeeded(context: Context): Boolean {
        val app = context.applicationContext
        if (isDone(app)) return false
        return try {
            val changed = migrate(app)
            app.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_STRUCTURE_V105, true).apply()
            changed > 0
        } catch (e: Exception) {
            Log.e(TAG, "作息结构迁移失败", e)
            false
        }
    }

    /**
     * 执行迁移，返回被修正的课表数量
     */
    fun migrate(context: Context): Int {
        val db = AppDatabase.getDatabase(context)
        val dao = db.timetableDao()
        val eventDao = db.eventItemDao()
        val official: List<TimePeriodInDay> = Timetable().getDefaultTimeStructure()
        var changed = 0

        for (tt in dao.getTimetablesSync()) {
            val current = tt.scheduleStructure
            if (current.size == official.size && !TimetableGrid.isLegacyDefault(current)) continue
            tt.scheduleStructure = official.map { TimePeriodInDay(it.from, it.to) }
            dao.saveTimetableSync(tt)
            changed++
            // 清掉超出 11 节的课程（旧数据噪音），否则网格里会出现永远画不出来的块
            try {
                val events = eventDao.getEventsOfTimetableSync(tt.id)
                val toDelete = events.filter { e ->
                    e.type == EventItem.TYPE.CLASS &&
                            (e.fromNumber > TimetableGrid.FIXED_PERIODS ||
                                    e.fromNumber + e.lastNumber - 1 > TimetableGrid.FIXED_PERIODS)
                }
                if (toDelete.isNotEmpty()) {
                    eventDao.deleteEventsInIdsSync(toDelete.map { it.id })
                }
            } catch (e: Exception) {
                Log.e(TAG, "清理超范围课程失败 tt=${tt.id}", e)
            }
        }
        if (changed > 0) Log.i(TAG, "已把 $changed 套课表的作息迁移为中大官方 11 节")
        return changed
    }

    /**
     * 取某节课的开始时间（分钟数），越界返回 -1
     * 供爬虫 / 导出使用。
     */
    fun startMinutesOf(structure: List<TimePeriodInDay>, index: Int): Int {
        val p = structure.getOrNull(index) ?: return -1
        return p.from.hour * 60 + p.from.minute
    }

    /** 便捷方法：把时间戳格式化成 HH:mm */
    fun hhmm(ts: Long): String {
        val c = Calendar.getInstance()
        c.timeInMillis = ts
        return String.format("%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }
}
