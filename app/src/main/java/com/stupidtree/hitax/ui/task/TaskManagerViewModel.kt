package com.stupidtree.hitax.ui.task

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import androidx.lifecycle.map
import com.stupidtree.component.data.DataState
import com.stupidtree.hitax.data.model.timetable.EventItem
import com.stupidtree.hitax.data.repository.TaskRepository
import java.sql.Timestamp

/**
 * 待办事项管理页的 ViewModel
 *
 * 支持三种筛选：全部 / 未完成 / 已完成；并可按截止时间与优先级排序。
 */
class TaskManagerViewModel(application: Application) : AndroidViewModel(application) {

    enum class FILTER { ALL, PENDING, FINISHED }

    /** 优先级（紧急程度） */
    enum class PRIORITY(val level: Int) {
        NORMAL(0), IMPORTANT(1), URGENT(2);

        companion object {
            fun of(level: Int): PRIORITY = when (level) {
                2 -> URGENT
                1 -> IMPORTANT
                else -> NORMAL
            }
        }
    }

    private val repository = TaskRepository.getInstance(application)

    private val filterLiveData = MutableLiveData(FILTER.ALL)
    private val sortByDueLiveData = MutableLiveData(true)

    val tasksLiveData: LiveData<DataState<List<EventItem>>> = MediatorLiveData<DataState<List<EventItem>>>().apply {
        var raw: List<EventItem> = emptyList()
        fun apply() {
            val filter = filterLiveData.value ?: FILTER.ALL
            var list = raw.filter {
                when (filter) {
                    FILTER.ALL -> true
                    FILTER.PENDING -> !it.done
                    FILTER.FINISHED -> it.done
                }
            }
            list = if (sortByDueLiveData.value != false) {
                list.sortedWith(
                    compareBy<EventItem> { it.done }
                        .thenByDescending { it.fromNumber }
                        .thenBy { it.from.time }
                )
            } else {
                list.sortedWith(
                    compareBy<EventItem> { it.done }
                        .thenByDescending { it.fromNumber }
                        .thenByDescending { it.from.time }
                )
            }
            value = DataState(list, DataState.STATE.SUCCESS)
        }
        addSource(repository.getAllTasks()) {
            raw = it ?: emptyList()
            apply()
        }
        addSource(filterLiveData) { apply() }
        addSource(sortByDueLiveData) { apply() }
    }

    val pendingCountLiveData: LiveData<Int> = repository.getPendingCount()

    /** 筛选与排序状态（供 UI 显示） */
    var currentFilter: FILTER = FILTER.ALL
        private set
    var sortByDue: Boolean = true
        private set

    fun setFilter(filter: FILTER) {
        currentFilter = filter
        filterLiveData.value = filter
    }

    fun toggleSort() {
        sortByDue = !sortByDue
        sortByDueLiveData.value = sortByDue
    }

    fun toggleDone(task: EventItem, done: Boolean) {
        repository.toggleDone(task, done)
    }

    fun delete(task: EventItem) {
        repository.deleteTask(task)
    }

    fun clearFinished() {
        repository.clearFinished()
    }

    fun createNewTask(): EventItem {
        // 默认截止时间：今天 23:59
        val c = java.util.Calendar.getInstance()
        c.set(java.util.Calendar.HOUR_OF_DAY, 23)
        c.set(java.util.Calendar.MINUTE, 59)
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        return EventItem.getTaskInstance("", c.timeInMillis).apply {
            from = Timestamp(c.timeInMillis)
            to = Timestamp(c.timeInMillis)
        }
    }
}
