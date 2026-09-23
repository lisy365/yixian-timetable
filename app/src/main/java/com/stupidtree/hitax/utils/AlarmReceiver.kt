package com.stupidtree.hitax.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.stupidtree.hitax.data.AppDatabase
import com.stupidtree.hitax.data.model.timetable.EventItem
import com.stupidtree.hitax.data.source.preference.NotificationPreferenceSource

/**
 * 提醒闹钟接收器
 *
 * - ACTION_REMIND：到点发送通知（课程 / 日程 / 待办 DDL）
 * - ACTION_RESCHEDULE：滚动窗口重排（保活闹钟每天触发一次）
 *
 * 说明：`goAsync()` 之后系统只保证进程存活很短一段时间，CPU 可能立刻进入
 * 低功耗状态，因此这里显式持有一个短时 [PowerManager.WakeLock]，
 * 保证「读库 -> 发通知」这段关键路径能跑完（这是通知不准时的常见原因之一）。
 */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_REMIND = "com.stupidtree.hitax.action.REMIND"
        const val ACTION_RESCHEDULE = "com.stupidtree.hitax.action.RESCHEDULE"
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_REPEAT_INDEX = "repeat_index"

        private const val WAKE_LOCK_TAG = "yixian:reminder"
        private const val WAKE_LOCK_TIMEOUT_MS = 30_000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            ACTION_RESCHEDULE -> {
                // 重排本身是异步的（走到调度器的单线程 executor），
                // 这里不持唤醒锁：即使这次没排完，保活闹钟第二天还会再来一次
                ReminderScheduler.rescheduleAll(app)
            }
            ACTION_REMIND -> {
                val eventId = intent.getStringExtra(EXTRA_EVENT_ID) ?: return
                val pending = goAsync()
                val wakeLock = acquireWakeLock(app)
                Thread {
                    try {
                        handleRemind(app, eventId)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        releaseWakeLock(wakeLock)
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
        val now = System.currentTimeMillis()
        // 事件已经开始超过 5 分钟就不再打扰（不精确闹钟可能晚到）
        if (KeepAlivePlan.isTooLateToNotify(now, event.from.time)) return
        // 已过期的课程 / 日程不再提醒
        if (event.to.time < now && !isTask) return

        val lead = if (isTask) prefs.ddlLeadMinutes else prefs.leadMinutes
        val minutes = ((event.from.time - now) / 60_000L).toInt()
        val shown = if (minutes > 0) minutes else lead
        NotificationUtils.notifyEvent(app, event, shown, isTask)
    }

    /** 短时唤醒锁：拿不到（权限被拒等）时静默降级，不影响提醒本身 */
    private fun acquireWakeLock(app: Context): PowerManager.WakeLock? {
        return try {
            val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
            val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            lock.setReferenceCounted(false)
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
            lock
        } catch (e: Exception) {
            null
        }
    }

    private fun releaseWakeLock(lock: PowerManager.WakeLock?) {
        try {
            if (lock?.isHeld == true) lock.release()
        } catch (e: Exception) {
            // ignore
        }
    }
}
