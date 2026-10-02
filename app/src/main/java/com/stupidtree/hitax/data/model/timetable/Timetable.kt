package com.stupidtree.hitax.data.model.timetable

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.stupidtree.hitax.ui.main.timetable.TimetableFragment.Companion.WEEK_MILLS
import java.sql.Timestamp
import java.util.*
import kotlin.math.roundToInt

@Entity(tableName = "timetable")
class Timetable {
    @PrimaryKey
    var id: String = UUID.randomUUID().toString()
    var name //课表名称
            : String? = null
    var code //适配教务的课表code
            : String? = null
    var startTime //开始时间
            : Timestamp = Timestamp(0)
    var endTime //结束时间
            : Timestamp = Timestamp(0)
    var createdAt //创建时间
            : Timestamp = Timestamp(System.currentTimeMillis())
    var scheduleStructure: List<TimePeriodInDay> = getDefaultTimeStructure()//时间表结构


    /**
     * 获取某时间戳所对应的周数 =
     */
    fun getWeekNumber(ts: Long): Int {
        val c = Calendar.getInstance()
        c.timeInMillis = ts
        c.firstDayOfWeek = Calendar.MONDAY
        c[Calendar.DAY_OF_WEEK] = Calendar.MONDAY
        c[Calendar.HOUR_OF_DAY] = 0
        c[Calendar.MINUTE] = 0
        if (c.timeInMillis > endTime.time) return -1
        val x = ((c.timeInMillis - startTime.time) / WEEK_MILLS.toFloat()).roundToInt()
        return when {
            x < 0 -> {
                -1
            }
            else -> {
                x + 1
            }
        }
    }


    fun getTimestamps(week:Int,dow:Int,start:Int,end:Int): List<Long> {
        val startOfDay:Long = startTime.time + (week-1).toLong()*7*24*60*60*1000 + (dow-1).toLong()*24*60*60*1000
        return listOf(startOfDay+scheduleStructure[start-1].from.toMills(),startOfDay+ scheduleStructure[end-1].to.toMills())
    }

    fun getTimestamps(week:Int,dow:Int,period: TimePeriodInDay): List<Long> {
        val startOfDay:Long = startTime.time + (week-1).toLong()*7*24*60*60*1000 + (dow-1).toLong()*24*60*60*1000
        return listOf(startOfDay+period.from.toMills(),startOfDay+ period.to.toMills())
    }

    fun transformTimePeriod(start:Int,end:Int):TimePeriodInDay{
        return TimePeriodInDay(scheduleStructure[start-1].from,scheduleStructure[end-1].to)
    }

    fun transformCourseNumber(period:TimePeriodInDay):Pair<Int,Int>{
        var start = 0
        var end = 0
        for(i in scheduleStructure.indices){
            if(scheduleStructure[i].contains(period.from)) start = i
            if(scheduleStructure[i].contains(period.to)) end = i
        }
        return Pair(start+1,end+1)
    }
    fun setScheduleStructure(tp: TimePeriodInDay, position: Int) {
        if (position < scheduleStructure.size) {
            scheduleStructure[position].from = tp.from
            scheduleStructure[position].to = tp.to
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Timetable

        if (id != other.id) return false
        if (name != other.name) return false
        if (code != other.code) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + (name?.hashCode() ?: 0)
        result = 31 * result + (code?.hashCode() ?: 0)
        return result
    }


    /**
     * 默认作息时间（中山大学标准作息，按「节」存：下标 i 即第 i+1 节）
     *
     * 与 SYSU 教务课表结构保持一致，见 `SysuTimetableParser.buildScheduleStructureFromSections()`。
     * **数据取自中山大学教务部官网「作息时间」栏目**（https://jwb.sysu.edu.cn/ 页脚）：
     *
     * ```
     * 上午 第一节 08:00~08:45   第二节 08:55~09:40
     *      第三节 10:10~10:55   第四节 11:05~11:50
     * 下午 第五节 14:20~15:05   第六节 15:15~16:00
     *      第七节 16:30~17:15   第八节 17:25~18:10
     * 晚上 第九节 19:00~19:45   第十节 19:55~20:40
     *      第十一节 20:50~21:35
     * ```
     *
     * 每天固定 **11 节**（[com.stupidtree.hitax.utils.TimetableGrid.FIXED_PERIODS]）。
     *
     * 修正记录（v1.0.5）：
     * - 旧默认结构有 14 节、且第 5 节写成 14:30（教务部实际为 **14:20**）——即「下午第一节课时间错误」的根因；
     * - 第 7/8 节旧值 16:20/17:15 也应为 16:30/17:25；
     * - 12~14 节（21:45 以后）教务部作息表中并不存在，属旧版本臆造，一并移除。
     *
     * 各校区/学期仍可能有微调，用户可在「课表设置 → 作息时间」中逐节修改。
     */
    fun getDefaultTimeStructure(): List<TimePeriodInDay> {
        val ranges = listOf(
            (8 to 0) to (8 to 45),      // 第 1 节
            (8 to 55) to (9 to 40),     // 第 2 节
            (10 to 10) to (10 to 55),   // 第 3 节
            (11 to 5) to (11 to 50),    // 第 4 节
            (14 to 20) to (15 to 5),    // 第 5 节（修正：14:30 -> 14:20）
            (15 to 15) to (16 to 0),    // 第 6 节
            (16 to 30) to (17 to 15),   // 第 7 节
            (17 to 25) to (18 to 10),   // 第 8 节
            (19 to 0) to (19 to 45),    // 第 9 节
            (19 to 55) to (20 to 40),   // 第 10 节
            (20 to 50) to (21 to 35)    // 第 11 节
        )
        return ranges.map { (from, to) ->
            TimePeriodInDay(TimeInDay(from.first, from.second), TimeInDay(to.first, to.second))
        }
    }


}