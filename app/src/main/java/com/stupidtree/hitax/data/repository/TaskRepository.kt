package com.stupidtree.hitax.data.repository

import android.app.Application
import androidx.annotation.WorkerThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.stupidtree.component.data.DataState
import com.stupidtree.hitax.data.AppDatabase
import com.stupidtree.hitax.data.model.timetable.EventItem
import com.stupidtree.hitax.utils.ReminderScheduler
import java.sql.Timestamp
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * 待办事项 / DDL 仓库
 *
 * 说明：待办事项复用 events 表存储（type = OTHER，subjectId = YIXIAN_TASK，
 * timetableId 为空），这样它会自动出现在「今日」时间轴里，也不会被课表删除操作影响。
 */
class TaskRepository private constructor(private val application: Application) {

    private val executor = Executors.newSingleThreadExecutor()
    private val eventItemDao = AppDatabase.getDatabase(application).eventItemDao()

    companion object {
        @Volatile
        private var instance: TaskRepository? = null

        @JvmStatic
        fun getInstance(application: Application): TaskRepository {
            if (instance == null) {
                synchronized(TaskRepository::class.java) {
                    if (instance == null) instance = TaskRepository(application)
                }
            }
            return instance!!
        }
    }

    /** 全部待办（按截止时间排序） */
    fun getAllTasks(): LiveData<List<EventItem>> {
        return eventItemDao.getTasks()
    }

    /** 未完成的待办数量（用于入口角标） */
    fun getPendingCount(): LiveData<Int> = eventItemDao.getPendingTaskCount()

    /** 同步读取全部待办 */
    @WorkerThread
    fun getAllTasksSync(): List<EventItem> = eventItemDao.getTasksSync()

    /**
     * 保存待办（新建或更新）
     * @param remindMinutes 提前提醒的分钟数；<0 表示不提醒
     */
    fun saveTask(
        task: EventItem,
        result: MutableLiveData<DataState<Boolean>>? = null
    ) {
        executor.execute {
            try {
                eventItemDao.saveEventSync(task)
                // 重新排提醒
                if (task.from.time > System.currentTimeMillis()) {
                    ReminderScheduler.rescheduleAll(application)
                } else {
                    ReminderScheduler.cancelForEvent(application, task)
                }
                result?.postValue(DataState(true, DataState.STATE.SUCCESS))
            } catch (e: Exception) {
                e.printStackTrace()
                result?.postValue(DataState(DataState.STATE.FETCH_FAILED, e.message))
            }
        }
    }

    /** 切换完成状态 */
    fun toggleDone(task: EventItem, done: Boolean?) {
        executor.execute {
            try {
                val target = done ?: !task.done
                eventItemDao.setTaskDone(task.id, target)
                if (target) {
                    // 完成之后不再提醒
                    ReminderScheduler.cancelForEvent(application, task)
                } else {
                    ReminderScheduler.rescheduleAll(application)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /** 删除待办 */
    fun deleteTask(task: EventItem) {
        executor.execute {
            try {
                ReminderScheduler.cancelForEvent(application, task)
                eventItemDao.deleteEventsInIdsSync(listOf(task.id))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /** 清理已完成的待办 */
    fun clearFinished() {
        executor.execute {
            try {
                val finished = eventItemDao.getTasksSync().filter { it.done }
                if (finished.isNotEmpty()) {
                    for (t in finished) ReminderScheduler.cancelForEvent(application, t)
                    eventItemDao.deleteEventsInIdsSync(finished.map { it.id })
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
