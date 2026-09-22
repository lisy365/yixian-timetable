package com.stupidtree.hitax.utils

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.stupidtree.hitax.data.AppDatabase
import com.stupidtree.hitax.data.model.timetable.EventItem
import com.stupidtree.hitax.data.source.preference.NotificationPreferenceSource
import java.util.concurrent.Executors

/**
 * 提醒调度器
 *
 * 策略：滚动窗口。每次调度扫描「当前时间 ~ 未来 N 天」内的事件，
 * 为每个事件按用户设置（提前量 + 重复次数 + 重复间隔）安排精确闹钟，
 * 并在窗口末尾安排一次「重新调度」闹钟，保证长期运行不漏提醒。
 *
 * 触发点：App 启动、导入课表后、日程/待办增删改、开机与 App 更新后、用户修改提醒设置后。
 */
object ReminderScheduler {

    /** 单次扫描的时间窗口（天） */
    private const val WINDOW_DAYS = 7

    private const val SP_ALARMS = "yixian_alarms"
    private const val SP_TASK_LEAD = "yixian_task_lead"
    private const val REQUEST_RESCHEDULE = 999999
    private const val REQUEST_BASE_EVENT = 100000
    private const val MAX_REPEAT = 10

    const val USE_GLOBAL_LEAD = -1
    const val NO_REMIND = -2

    private val executor = Executors.newSingleThreadExecutor()

    /**
     * 读取单个事件的「提前量覆盖」：
     * -1 表示跟随全局设置；-2 表示不提醒；其他为分钟数。
     */
    fun getLeadOverride(app: Context, eventId: String): Int {
        return app.getSharedPreferences(SP_TASK_LEAD, Context.MODE_PRIVATE)
            .getInt(eventId, USE_GLOBAL_LEAD)
    }

    fun setLeadOverride(app: Context, eventId: String, value: Int) {
        val sp = app.getSharedPreferences(SP_TASK_LEAD, Context.MODE_PRIVATE)
        if (value == USE_GLOBAL_LEAD) sp.edit().remove(eventId).apply()
        else sp.edit().putInt(eventId, value).apply()
    }

    /** 用指定的提前量单独为某个事件安排提醒（用于单项自定义提前量） */
    fun scheduleEventWithLead(context: Context, event: EventItem, leadMinutes: Int) {
        val app = context.applicationContext
        executor.execute {
            try {
                NotificationUtils.ensureChannels(app)
                val prefs = NotificationPreferenceSource.getInstance(app)
                if (!prefs.isEnabled) return@execute
                val now = System.currentTimeMillis()
                val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val base = event.from.time - leadMinutes * 60_000L
                val intervalMs = prefs.repeatInterval * 60_000L
                val repeat = prefs.repeatCount.coerceIn(0, MAX_REPEAT)
                val scheduled = mutableListOf<Int>()
                for (i in 0..repeat) {
                    val triggerAt = base - i * intervalMs
                    if (triggerAt < now || triggerAt > event.from.time) continue
                    val pi = pendingIntent(app, event.id, i, PendingIntent.FLAG_UPDATE_CURRENT) ?: continue
                    if (setAlarmSafely(am, triggerAt, pi)) scheduled.add(i)
                }
                if (scheduled.isNotEmpty()) {
                    app.getSharedPreferences(SP_ALARMS, Context.MODE_PRIVATE)
                        .edit().putString(event.id, scheduled.joinToString(",")).apply()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ------------------------------------------------------------------
    // 对外接口
    // ------------------------------------------------------------------

    /** 全量重新调度（先清理旧的排期，再按当前设置重排） */
    fun rescheduleAll(context: Context) {
        val app = context.applicationContext
        executor.execute {
            try {
                cancelScheduledInternal(app)
                scheduleInternal(app)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /** 取消某个事件的全部提醒 */
    fun cancelForEvent(context: Context, event: EventItem, repeatCount: Int? = null) {
        val app = context.applicationContext
        executor.execute {
            try {
                val prefs = NotificationPreferenceSource.getInstance(app)
                val count = (repeatCount ?: prefs.repeatCount).coerceIn(0, MAX_REPEAT)
                val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                for (i in 0..count) {
                    val pi = pendingIntent(app, event.id, i, PendingIntent.FLAG_NO_CREATE) ?: continue
                    am.cancel(pi)
                    pi.cancel()
                }
                val sp = app.getSharedPreferences(SP_ALARMS, Context.MODE_PRIVATE)
                sp.edit().remove(event.id).apply()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /** 取消全部提醒 */
    fun cancelAll(context: Context) {
        val app = context.applicationContext
        executor.execute {
            try {
                cancelScheduledInternal(app)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /** 按排期记录取消所有已设置的闹钟 */
    private fun cancelScheduledInternal(app: Context) {
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val sp = app.getSharedPreferences(SP_ALARMS, Context.MODE_PRIVATE)
        for ((eventId, value) in sp.all) {
            val ids = (value as? String)?.split(",") ?: continue
            for (raw in ids) {
                val repeatIndex = raw.toIntOrNull() ?: continue
                val pi = pendingIntent(app, eventId, repeatIndex, PendingIntent.FLAG_NO_CREATE) ?: continue
                am.cancel(pi)
                pi.cancel()
            }
        }
        val rp = reschedulePendingIntent(app, PendingIntent.FLAG_NO_CREATE)
        if (rp != null) {
            am.cancel(rp)
            rp.cancel()
        }
        sp.edit().clear().apply()
    }

    private fun scheduleInternal(app: Context) {
        NotificationUtils.ensureChannels(app)
        val prefs = NotificationPreferenceSource.getInstance(app)
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // 安排下一次「窗口重排」
        reschedulePendingIntent(app, PendingIntent.FLAG_UPDATE_CURRENT)?.let {
            setAlarmSafely(am, System.currentTimeMillis() + (WINDOW_DAYS - 1) * DAY_MS, it)
        }

        if (!prefs.isEnabled) return

        val now = System.currentTimeMillis()
        val until = now + WINDOW_DAYS * DAY_MS
        val events = try {
            AppDatabase.getDatabase(app).eventItemDao()
                .getEventsDuringSync(now - 12L * 3600 * 1000, until)
        } catch (e: Exception) {
            e.printStackTrace()
            return
        }

        val sp = app.getSharedPreferences(SP_ALARMS, Context.MODE_PRIVATE)
        val editor = sp.edit()
        for (e in events) {
            if (e.type == EventItem.TYPE.TAG) continue
            val isTask = e.isTask()
            if (isTask) {
                if (!prefs.ddlEnabled || e.done) continue
            } else if (e.type == EventItem.TYPE.CLASS) {
                if (!prefs.classEnabled) continue
                if (prefs.onlySchoolDays && !NotificationUtils.isSchoolDay(e.from.time)) continue
            } else {
                if (!prefs.eventEnabled) continue
                if (prefs.onlySchoolDays && !NotificationUtils.isSchoolDay(e.from.time)) continue
            }

            val defaultLead = if (isTask) prefs.ddlLeadMinutes else prefs.leadMinutes
            // 单项自定义提前量优先
            val override = getLeadOverride(app, e.id)
            val lead = when (override) {
                NO_REMIND -> continue
                USE_GLOBAL_LEAD -> defaultLead
                else -> override
            }
            val base = e.from.time - lead * 60_000L
            val intervalMs = prefs.repeatInterval * 60_000L
            val repeat = prefs.repeatCount.coerceIn(0, MAX_REPEAT)
            val scheduled = mutableListOf<Int>()
            for (i in 0..repeat) {
                // 第 i 次提醒 = 基准时间 - i * 间隔
                val triggerAt = base - i * intervalMs
                if (triggerAt < now - 5 * 60_000L) continue
                if (triggerAt > e.from.time) continue
                val pi = pendingIntent(app, e.id, i, PendingIntent.FLAG_UPDATE_CURRENT) ?: continue
                if (setAlarmSafely(am, triggerAt, pi)) scheduled.add(i)
            }
            if (scheduled.isNotEmpty()) {
                editor.putString(e.id, scheduled.joinToString(","))
            }
        }
        editor.apply()
    }

    /**
     * 设置闹钟，兼容 Android 12+ 精确闹钟权限被拒绝的情况
     */
    private fun setAlarmSafely(am: AlarmManager, triggerAt: Long, pi: PendingIntent): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
            true
        } catch (e: SecurityException) {
            try {
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                true
            } catch (e2: Exception) {
                e2.printStackTrace()
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun reschedulePendingIntent(app: Context, flags: Int): PendingIntent? {
        val intent = Intent(app, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_RESCHEDULE
        }
        return PendingIntent.getBroadcast(app, REQUEST_RESCHEDULE, intent, withImmutable(flags))
    }

    private fun pendingIntent(app: Context, eventId: String, repeatIndex: Int, flags: Int): PendingIntent? {
        val intent = Intent(app, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_REMIND
            putExtra(AlarmReceiver.EXTRA_EVENT_ID, eventId)
            putExtra(AlarmReceiver.EXTRA_REPEAT_INDEX, repeatIndex)
        }
        return PendingIntent.getBroadcast(
            app, requestCodeOf(eventId, repeatIndex), intent, withImmutable(flags)
        )
    }

    private fun withImmutable(flags: Int): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags or PendingIntent.FLAG_IMMUTABLE
        } else flags
    }

    private fun requestCodeOf(eventId: String, repeatIndex: Int): Int {
        return REQUEST_BASE_EVENT + ((eventId.hashCode() and 0xFFFF) * 16 + repeatIndex)
    }

    private const val DAY_MS = 24L * 3600 * 1000
}
