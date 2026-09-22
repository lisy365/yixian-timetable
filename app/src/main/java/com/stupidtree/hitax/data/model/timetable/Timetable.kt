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
     * 与 SYSU 教务课表结构保持一致，见 `SysuTimetableParser.buildScheduleStructureFromSections()`：
     *   上午 1-2 节 08:00-09:40、3-4 节 10:00-11:40
     *   下午 5-6 节 14:30-16:10、7-8 节 16:20-18:00
     *   晚上 9-10 节 19:00-20:40、11-12 节 20:50-22:30
     * 各校区/学期可能有微调，用户可在「课表设置」中逐节修改。
     */
    fun getDefaultTimeStructure(): List<TimePeriodInDay> {
        val ranges = listOf(
            (8 to 0) to (8 to 45),
            (8 to 55) to (9 to 40),
            (10 to 0) to (10 to 45),
            (10 to 55) to (11 to 40),
            (14 to 30) to (15 to 15),
            (15 to 25) to (16 to 10),
            (16 to 20) to (17 to 5),
            (17 to 15) to (18 to 0),
            (19 to 0) to (19 to 45),
            (19 to 55) to (20 to 40),
            (20 to 50) to (21 to 35),
            (21 to 45) to (22 to 30),
            (22 to 40) to (23 to 25),
            (23 to 35) to (0 to 20)
        )
        return ranges.map { (from, to) ->
            TimePeriodInDay(TimeInDay(from.first, from.second), TimeInDay(to.first, to.second))
        }
    }


}