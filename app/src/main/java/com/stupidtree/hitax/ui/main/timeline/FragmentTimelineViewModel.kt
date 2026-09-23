package com.stupidtree.hitax.ui.main.timeline

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import com.stupidtree.component.data.Trigger
import com.stupidtree.hitax.data.model.timetable.EventItem
import com.stupidtree.hitax.data.repository.TaskRepository
import com.stupidtree.hitax.data.repository.TimetableRepository
import java.util.Calendar

class FragmentTimelineViewModel(application: Application) : AndroidViewModel(application){

    /**
     * 仓库区
     */
    private val timetableRepository = TimetableRepository.getInstance(application)
    private val taskRepository = TaskRepository.getInstance(application)

    /**
     * 数据区
     */
    private val todayEventsController:MutableLiveData<Trigger> = MutableLiveData()

    /** 今天 0 点 */
    private fun dayStart(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /** 明天 0 点 */
    private fun tomorrowStart(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        c.add(Calendar.DATE, 1)
        return c.timeInMillis
    }

    /** 当天 00:00~24:00 的课程 / 日程（含当天到期的待办） */
    private val todayEventsLiveData:LiveData<List<EventItem>> =  todayEventsController.switchMap{
        return@switchMap timetableRepository.getEventsDuring(dayStart(), tomorrowStart())
    }

    /**
     * 未完成、且截止时间在明天及以后的待办
     *
     * 待办的 `from == to`（就是那个截止时刻），只查当天窗口的话，
     * 截止日期排在明天以后的待办在时间轴上一行都看不到。
     */
    private val futureTasksLiveData:LiveData<List<EventItem>> = todayEventsController.switchMap{
        return@switchMap taskRepository.getPendingTasksAfter(tomorrowStart())
    }

    /** 「今日」时间轴的最终列表 = 当天事件 + 未来未完成的待办 */
    val todayListLiveData:LiveData<List<EventItem>> = MediatorLiveData<List<EventItem>>().apply {
        var events: List<EventItem> = emptyList()
        var tasks: List<EventItem> = emptyList()
        fun apply() {
            value = TimelineTasks.displayOrder(TimelineTasks.mergeTodayTasks(events, tasks))
        }
        addSource(todayEventsLiveData) {
            events = it ?: emptyList()
            apply()
        }
        addSource(futureTasksLiveData) {
            tasks = it ?: emptyList()
            apply()
        }
    }

    /** 下拉的「即将到来」：最近的几个日程，未完成的待办也一起排队 */
    val weekEventsLiveData:LiveData<List<EventItem>> = MediatorLiveData<List<EventItem>>().apply {
        var soon: List<EventItem> = emptyList()
        var tasks: List<EventItem> = emptyList()
        fun apply() {
            val merged = ArrayList<EventItem>(soon)
            val ids = HashSet<String>()
            for (e in soon) ids.add(e.id)
            for (t in tasks) if (ids.add(t.id)) merged.add(t)
            value = merged.sortedBy { it.from.time }.take(4)
        }
        addSource(todayEventsController.switchMap {
            timetableRepository.getEventsAfter(System.currentTimeMillis(), 4)
        }) {
            soon = it ?: emptyList()
            apply()
        }
        addSource(todayEventsController.switchMap {
            // 今天还没结束的待办也算「即将到来」
            taskRepository.getPendingTasksAfter(dayStart())
        }) {
            tasks = it ?: emptyList()
            apply()
        }
    }


    fun startRefresh(){
        todayEventsController.value = Trigger.actioning
    }
}
