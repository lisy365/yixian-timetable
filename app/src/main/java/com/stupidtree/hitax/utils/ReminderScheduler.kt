package com.stupidtree.hitax.utils

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
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
 * 并安排一颗**每天触发一次**的「重新排期」保活闹钟，保证长期运行不漏提醒。
 *
 * 触发点：App 启动、导入课表后、日程/待办增删改、开机与 App 更新后、
 * 系统时间/时区变化后、精确闹钟权限变化后、用户修改提醒设置后。
 *
 * 「保活」说明见 [KeepAlivePlan]：不依赖常驻前台服务也能自愈，
 * 需要更强的保障时用户可在「通知提醒」里开启 [KeepAliveService]。
 */
object ReminderScheduler {

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

    /**
     * 当前是否已经拿到「精确闹钟」能力。
     * Android 12+ 需要 SCHEDULE_EXACT_ALARM；被拒绝时提醒会退化成不精确闹钟。
     */
    fun canScheduleExact(app: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return try {
            val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.canScheduleExactAlarms()
        } catch (e: Exception) {
            false
        }
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
                val repeat = prefs.repeatCount.coerceIn(0, MAX_REPEAT)
                val scheduled = mutableListOf<Int>()
                for (i in 0..repeat) {
                    // 触发时刻的计算抽成纯函数 KeepAlivePlan，便于离线单测
                    val triggerAt = KeepAlivePlan.triggerTimeAt(
                        event.from.time, leadMinutes, i, prefs.repeatInterval
                    )
                    if (!KeepAlivePlan.isTriggerDue(triggerAt, now, event.from.time, graceMs = 0L)) continue
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
                // 除了当前重复次数，还要把排期记录里出现过的下标一起清掉，
                // 否则用户把「重复次数」调小之后，老闹钟会一直留着继续响
                val indices = HashSet<Int>()
                for (i in 0..count) indices.add(i)
                val sp = app.getSharedPreferences(SP_ALARMS, Context.MODE_PRIVATE)
                (sp.getString(event.id, null)?.split(",") ?: emptyList())
                    .forEach { raw -> raw.toIntOrNull()?.let { indices.add(it) } }
                for (i in indices) {
                    pendingIntent(app, event.id, i, PendingIntent.FLAG_NO_CREATE)?.let {
                        am.cancel(it)
                        it.cancel()
                    }
                    legacyPendingIntent(app, event.id, i, PendingIntent.FLAG_NO_CREATE)?.let {
                        am.cancel(it)
                        it.cancel()
                    }
                }
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
                // 新版（带 data，避免 hash 冲突串台）
                pendingIntent(app, eventId, repeatIndex, PendingIntent.FLAG_NO_CREATE)?.let {
                    am.cancel(it)
                    it.cancel()
                }
                // 旧版（1.0.3 及以前没有 data，仅有请求码）——升级后要一并清掉，否则会残留幽灵闹钟
                legacyPendingIntent(app, eventId, repeatIndex, PendingIntent.FLAG_NO_CREATE)?.let {
                    am.cancel(it)
                    it.cancel()
                }
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
        val now = System.currentTimeMillis()

        // 保活：每天重排一次（旧实现是 6 天才排一次，丢一颗就永久失效）
        reschedulePendingIntent(app, PendingIntent.FLAG_UPDATE_CURRENT)?.let {
            setAlarmSafely(am, KeepAlivePlan.nextRearmAt(now), it)
        }

        if (!prefs.isEnabled) return

        val until = KeepAlivePlan.windowEnd(now)
        val events = try {
            AppDatabase.getDatabase(app).eventItemDao()
                .getEventsDuringSync(KeepAlivePlan.windowStart(now), until)
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
            val repeat = prefs.repeatCount.coerceIn(0, MAX_REPEAT)
            val scheduled = mutableListOf<Int>()
            for (i in 0..repeat) {
                val triggerAt = KeepAlivePlan.triggerTimeAt(e.from.time, lead, i, prefs.repeatInterval)
                if (!KeepAlivePlan.isTriggerDue(triggerAt, now, e.from.time)) continue
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
            // data 参与 PendingIntent 的相等性判断：只靠请求码时，
            // 两个 id 的 hashCode 低 16 位相同就会互相覆盖（提醒会串台/丢失）
            data = Uri.parse("yixian://remind/$repeatIndex/${Uri.encode(eventId)}")
            putExtra(AlarmReceiver.EXTRA_EVENT_ID, eventId)
            putExtra(AlarmReceiver.EXTRA_REPEAT_INDEX, repeatIndex)
        }
        return PendingIntent.getBroadcast(
            app, requestCodeOf(eventId, repeatIndex), intent, withImmutable(flags)
        )
    }

    /**
     * 1.0.3 及以前的 PendingIntent 形态（没有 data），
     * 只用于升级后把残留的旧闹钟清理干净。
     */
    private fun legacyPendingIntent(app: Context, eventId: String, repeatIndex: Int, flags: Int): PendingIntent? {
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
}
