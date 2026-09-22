package com.stupidtree.hitax.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机 / 应用更新后恢复提醒排期
 *
 * Android 在重启或应用被替换后会清空 AlarmManager 中的闹钟，
 * 这里重新按用户设置排一遍。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                ReminderScheduler.rescheduleAll(context.applicationContext)
            }
        }
    }
}
