package com.stupidtree.hitax.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 系统状态变化后恢复提醒排期（「保活」的被动兜底）
 *
 * 下面这些情况都会让 AlarmManager 里的排期失效或错位：
 * - 重启 / 应用被覆盖安装：闹钟全部被清空；
 * - 用户手动改系统时间或时区：`RTC_WAKEUP` 闹钟的绝对时刻不再对应预期日程；
 * - Android 12+ 用户授予 / 撤销「闹钟与提醒」权限：需要立刻按新的能力重排。
 *
 * 每次都会重新跑一遍滚动窗口排期（幂等）。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val shouldReschedule = when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.intent.action.QUICKBOOT_POWERON" -> true
            else -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                action == "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
        }
        if (!shouldReschedule) return
        val app = context.applicationContext
        ReminderScheduler.rescheduleAll(app)
        // 用户开了「后台保活」，跟着一起把前台服务拉起来
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            KeepAliveService.startIfEnabled(app)
        }
    }
}
