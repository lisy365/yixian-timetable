package com.stupidtree.hitax.data.model.timetable

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import com.stupidtree.hitax.utils.TimeTools
import java.io.Serializable
import java.sql.Timestamp
import java.util.*
import kotlin.math.abs

@Entity(tableName = "events")
class EventItem :Serializable,Comparable<EventItem>{
    enum class TYPE {
        CLASS, EXAM, OTHER, TAG
    }

    @PrimaryKey
    var id: String = UUID.randomUUID().toString()
    var type: TYPE = TYPE.CLASS
    var name //名称
            : String = "NULL"
    var place //地点
            : String? = ""
    var teacher //教师
            : String? = ""
    var subjectId //科目id
            : String = ""
    var timetableId //课表的id
            : String = ""
    var from //开始时间
            : Timestamp = Timestamp(0)
    var to //结束时间
            : Timestamp = Timestamp(0)
    var fromNumber:Int = 0
    var lastNumber:Int = 0

    /** 备注（待办 / DDL 事项的详细说明，也可用于课程与日程） */
    var note: String? = null

    /** 是否已完成（待办 / DDL 事项使用） */
    var done: Boolean = false

    @ColumnInfo(name = "created_at")
    var createdAt //创建时间
            : Timestamp = Timestamp(System.currentTimeMillis())


    @Ignore
    var color:Int = 0//科目颜色，不进行存储

    override fun toString(): String {
        return "EventItem(id='$id', type=$type, name='$name', place=$place, teacher=$teacher, subjectId='$subjectId', timetableId='$timetableId', from=$from, to=$to, createdAt=$createdAt"
    }


    /**
     * 获取fromTime和当前时间的距离（分钟）
     */
    fun getFromTimeDistance(): Int {
        return (abs(from.time - System.currentTimeMillis()) / 60000).toInt()
    }

    /**
     * 获取持续时长
     */
    fun getDurationInMinutes(): Int {
        return ((to.time - from.time) / 60000).toInt()
    }

    fun getDurationInMills(): Long{
        return (to.time - from.time)
    }
    /**
     * 获取周几，周一为1
     */
    fun getDow():Int{
        return TimeTools.getDow(from.time)
    }

    /**
     * 该事件的时间范围是否包括某时间戳
     */
    fun containsTimeStamp(ts: Long): Boolean {
        val timestamp = Timestamp(ts)
        return from.before(timestamp) && to.after(timestamp)
    }

    /**
     * 在某时间戳之后发生
     */
    fun happensAfterTimeStamp(ts:Long):Boolean{
        val timestamp = Timestamp(ts)
        return from.after(timestamp)
    }

    /**
     * 计算某时间戳在本事件中的进度
     */
    fun getProgress(ts:Long):Float{
        val timestamp = Timestamp(ts)
        return when {
            timestamp.before(from) -> {
                0f
            }
            timestamp.after(to) -> {
                1f
            }
            else -> {
                ((ts-from.time).toFloat()/(to.time-from.time).toFloat())
            }
        }
    }



    override fun compareTo(other: EventItem): Int {
        return from.compareTo(other.from)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EventItem

        if (id != other.id) return false
        if (type != other.type) return false
        if (name != other.name) return false
        if (place != other.place) return false
        if (teacher != other.teacher) return false
        if (subjectId != other.subjectId) return false
        if (timetableId != other.timetableId) return false
        if (from != other.from) return false
        if (to != other.to) return false
        if (fromNumber != other.fromNumber) return false
        if (lastNumber != other.lastNumber) return false
        if (note != other.note) return false
        if (done != other.done) return false
        if (color != other.color) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + (place?.hashCode() ?: 0)
        result = 31 * result + (teacher?.hashCode() ?: 0)
        result = 31 * result + subjectId.hashCode()
        result = 31 * result + timetableId.hashCode()
        result = 31 * result + from.hashCode()
        result = 31 * result + to.hashCode()
        result = 31 * result + fromNumber
        result = 31 * result + lastNumber
        result = 31 * result + (note?.hashCode() ?: 0)
        result = 31 * result + done.hashCode()
        result = 31 * result + color
        return result
    }

    /** 是否为待办 / DDL 事项 */
    fun isTask(): Boolean = subjectId == SUBJECT_ID_TASK

    companion object{
        /** 待办 / DDL 事项使用的固定科目标记 */
        const val SUBJECT_ID_TASK = "YIXIAN_TASK"

        fun getTagInstance(name:String):EventItem{
            val result = EventItem()
            result.type = TYPE.TAG
            result.name = name
            return result
        }

        /**
         * 构造一个待办 / DDL 事项
         * 复用 events 表，但不挂在任何课表下（timetableId 为空），因此不会被课表删除影响。
         */
        fun getTaskInstance(name: String, due: Long): EventItem {
            val result = EventItem()
            result.type = TYPE.OTHER
            result.name = name
            result.subjectId = SUBJECT_ID_TASK
            result.timetableId = ""
            result.from = Timestamp(due)
            result.to = Timestamp(due)
            return result
        }
    }


}