package com.stupidtree.hitax.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.stupidtree.hitax.R
import com.stupidtree.hitax.data.model.timetable.EventItem
import com.stupidtree.hitax.data.source.preference.NotificationPreferenceSource
import com.stupidtree.hitax.ui.main.MainActivity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 通知工具
 *
 * 负责：通知渠道创建、提醒文案模板渲染、发送系统通知。
 * 文案模板支持占位符，用户可在「课表设置 → 通知提醒」中自定义。
 */
object NotificationUtils {

    const val CHANNEL_CLASS = "yixian_channel_class"
    const val CHANNEL_EVENT = "yixian_channel_event"
    const val CHANNEL_DDL = "yixian_channel_ddl"

    /** 模板占位符说明（设置页展示用） */
    val PLACEHOLDERS = arrayOf(
        "{name}" to "名称",
        "{time}" to "开始时间",
        "{place}" to "地点",
        "{teacher}" to "教师",
        "{minutes}" to "距开始分钟数",
        "{note}" to "备注"
    )

    /**
     * 创建通知渠道（幂等）
     */
    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val prefs = NotificationPreferenceSource.getInstance(context)
        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val attrs = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .build()
        val defs = listOf(
            Triple(CHANNEL_CLASS, context.getString(R.string.notify_channel_class), "上课提醒"),
            Triple(CHANNEL_EVENT, context.getString(R.string.notify_channel_event), "日程提醒"),
            Triple(CHANNEL_DDL, context.getString(R.string.notify_channel_ddl), "待办 / DDL 提醒")
        )
        for ((id, name, desc) in defs) {
            if (nm.getNotificationChannel(id) != null) continue
            val channel = NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH)
            channel.description = desc
            channel.enableVibration(prefs.vibrateEnabled)
            if (prefs.soundEnabled) {
                channel.setSound(soundUri, attrs)
            } else {
                channel.setSound(null, null)
            }
            nm.createNotificationChannel(channel)
        }
    }

    /**
     * 渲染提醒文案
     * @param isDdl 是否为待办 / DDL 提醒（使用另一套模板）
     */
    fun render(
        context: Context,
        event: EventItem,
        minutesBefore: Int,
        isDdl: Boolean
    ): Pair<String, String> {
        val prefs = NotificationPreferenceSource.getInstance(context)
        val titleTpl = if (isDdl) prefs.ddlTitleTemplate else prefs.titleTemplate
        val contentTpl = if (isDdl) prefs.ddlContentTemplate else prefs.contentTemplate
        val timeText = SimpleDateFormat("HH:mm", Locale.getDefault()).format(event.from)
        val map = mapOf(
            "{name}" to event.name,
            "{time}" to timeText,
            "{place}" to (event.place ?: ""),
            "{teacher}" to (event.teacher ?: ""),
            "{minutes}" to formatMinutes(context, minutesBefore),
            "{note}" to (event.note ?: "")
        )
        return render(titleTpl, map) to render(contentTpl, map)
    }

    private fun render(template: String, map: Map<String, String>): String {
        var s = template
        for ((k, v) in map) s = s.replace(k, v)
        // 清理因为占位符为空导致的重复分隔符与多余空格
        s = s.replace(Regex("\\s*·\\s*(?=($|·|\\s))"), " ")
        s = s.replace(Regex("\\s{2,}"), " ").trim()
        s = s.trim('·', ' ', '-', '：', ':')
        return s.ifBlank { "提醒" }
    }

    /**
     * 模板预览用的示例文案（设置页展示，帮助用户理解变量效果）
     */
    fun templateHelp(): List<Pair<String, String>> = PLACEHOLDERS.toList()

    fun formatMinutes(context: Context, minutes: Int): String {
        if (minutes <= 0) return context.getString(R.string.notify_now)
        return if (minutes >= 60 && minutes % 60 == 0) {
            context.getString(R.string.notify_hours, minutes / 60)
        } else if (minutes > 60) {
            context.getString(R.string.notify_hours_minutes, minutes / 60, minutes % 60)
        } else {
            context.getString(R.string.notify_minutes, minutes)
        }
    }

    /**
     * 发送提醒通知
     */
    fun notifyEvent(context: Context, event: EventItem, minutesBefore: Int, isDdl: Boolean) {
        ensureChannels(context)
        val (title, content) = render(context, event, minutesBefore, isDdl)
        val channelId = when {
            isDdl -> CHANNEL_DDL
            event.type == EventItem.TYPE.CLASS -> CHANNEL_CLASS
            else -> CHANNEL_EVENT
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = PendingIntent.getActivity(context, event.id.hashCode(), intent, flags)

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_baseline_access_alarm_24)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        val prefs = NotificationPreferenceSource.getInstance(context)
        if (prefs.vibrateEnabled) {
            builder.setVibrate(longArrayOf(0, 250, 200, 250))
        }
        if (!prefs.soundEnabled) {
            builder.setSilent(true)
        }
        try {
            NotificationManagerCompat.from(context)
                .notify(notificationIdOf(event, minutesBefore), builder.build())
        } catch (e: SecurityException) {
            // 未授予通知权限
            e.printStackTrace()
        }
    }

    /** 通知 id：同一条提醒重复更新，不叠加 */
    fun notificationIdOf(event: EventItem, minutesBefore: Int): Int {
        return (event.id.hashCode() * 31 + minutesBefore) and 0x7fffffff
    }

    /** 是否处于「上课日」（周一~周五） */
    fun isSchoolDay(ts: Long): Boolean {
        val c = Calendar.getInstance()
        c.timeInMillis = ts
        val dow = c.get(Calendar.DAY_OF_WEEK)
        return dow != Calendar.SATURDAY && dow != Calendar.SUNDAY
    }
}
