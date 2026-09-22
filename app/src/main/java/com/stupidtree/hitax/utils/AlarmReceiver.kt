package com.stupidtree.hitax.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.stupidtree.hitax.data.AppDatabase
import com.stupidtree.hitax.data.model.timetable.EventItem
import com.stupidtree.hitax.data.source.preference.NotificationPreferenceSource

/**
 * 提醒闹钟接收器
 *
 * - ACTION_REMIND：到点发送通知（课程 / 日程 / 待办 DDL）
 * - ACTION_RESCHEDULE：滚动窗口重排（由调度器在窗口末尾触发）
 */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_REMIND = "com.stupidtree.hitax.action.REMIND"
        const val ACTION_RESCHEDULE = "com.stupidtree.hitax.action.RESCHEDULE"
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_REPEAT_INDEX = "repeat_index"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            ACTION_RESCHEDULE -> {
                ReminderScheduler.rescheduleAll(app)
            }
            ACTION_REMIND -> {
                val eventId = intent.getStringExtra(EXTRA_EVENT_ID) ?: return
                val pending = goAsync()
                Thread {
                    try {
                        handleRemind(app, eventId)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
        }
    }

    private fun handleRemind(app: Context, eventId: String) {
        val prefs = NotificationPreferenceSource.getInstance(app)
        if (!prefs.isEnabled) return
        val event: EventItem = try {
            AppDatabase.getDatabase(app).eventItemDao().getEventInIdsSync(listOf(eventId))
                .firstOrNull() ?: return
        } catch (e: Exception) {
            return
        }
        if (event.type == EventItem.TYPE.TAG) return
        val isTask = event.isTask()
        if (isTask) {
            if (!prefs.ddlEnabled || event.done) return
        } else if (event.type == EventItem.TYPE.CLASS) {
            if (!prefs.classEnabled) return
        } else {
            if (!prefs.eventEnabled) return
        }
        // 事件已经开始超过 5 分钟就不再打扰
        if (System.currentTimeMillis() > event.from.time + 5 * 60_000L) return
        // 已过期的课程 / 日程不再提醒
        if (event.to.time < System.currentTimeMillis() && !isTask) return

        val lead = if (isTask) prefs.ddlLeadMinutes else prefs.leadMinutes
        val minutes = ((event.from.time - System.currentTimeMillis()) / 60_000L).toInt()
        val shown = if (minutes > 0) minutes else lead
        NotificationUtils.notifyEvent(app, event, shown, isTask)
    }
}
