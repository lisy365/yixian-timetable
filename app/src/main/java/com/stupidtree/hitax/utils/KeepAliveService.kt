package com.stupidtree.hitax.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.stupidtree.hitax.R
import com.stupidtree.hitax.data.source.preference.NotificationPreferenceSource
import com.stupidtree.hitax.ui.main.MainActivity

/**
 * 提醒「后台保活」前台服务（可在「通知提醒」里开关，默认关闭）
 *
 * 为什么需要：
 * - 国产 ROM 的省电策略会在灭屏后冻结应用进程，`AlarmManager` 的闹钟虽然由系统投递，
 *   但应用被「深度冻结 / 强制停止」后排期可能整批丢失；
 * - 保持一个前台服务（带常驻通知）能让进程处于「前台优先级」，
 *   配合 [KeepAlivePlan.KEEPALIVE_TICK_MS] 周期自检，把漏掉的排期重新补上。
 *
 * 设计取舍：
 * - **默认关闭**，由用户在「通知提醒」里显式开启，避免默认常驻通知影响体验与续航；
 * - 只是一层兜底：不做任何网络/CPU 密集操作，每次自检就是一次幂等的重排；
 * - 通知渠道为最低重要性（不响铃、不震动、不出横幅）。
 */
class KeepAliveService : Service() {

    companion object {
        private const val CHANNEL_ID = "yixian_channel_keepalive"
        private const val NOTIFICATION_ID = 9001
        private const val ACTION_START = "com.stupidtree.hitax.action.KEEPALIVE_START"
        private const val ACTION_STOP = "com.stupidtree.hitax.action.KEEPALIVE_STOP"

        /** v1.0.5：通知文案改了 / 短句更新了，让服务重新贴一次通知 */
        private const val ACTION_UPDATE = "com.stupidtree.hitax.action.KEEPALIVE_UPDATE"

        /** 用户在设置里切换开关时调用 */
        fun setEnabled(context: Context, enabled: Boolean) {
            val app = context.applicationContext
            if (enabled) start(app) else stop(app)
        }

        /**
         * 通知文案变化后刷新常驻通知（v1.0.5）
         * 服务没在跑时什么都不做（不需要为了改文案把服务拉起来）。
         */
        fun refreshNotification(context: Context) {
            val app = context.applicationContext
            try {
                app.startService(
                    Intent(app, KeepAliveService::class.java).setAction(ACTION_UPDATE)
                )
            } catch (e: Exception) {
                // 服务没在跑时忽略
            }
        }

        fun start(context: Context) {
            val app = context.applicationContext
            try {
                val intent = Intent(app, KeepAliveService::class.java).setAction(ACTION_START)
                ContextCompat.startForegroundService(app, intent)
            } catch (e: Exception) {
                // Android 12+ 后台启动前台服务有限制，失败就退化为「只有闹钟兜底」
                e.printStackTrace()
            }
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            try {
                app.startService(
                    Intent(app, KeepAliveService::class.java).setAction(ACTION_STOP)
                )
            } catch (e: Exception) {
                // 服务没在跑时忽略
            }
        }

        /** 开机 / 更新后如果用户开过保活，就自动恢复 */
        fun startIfEnabled(context: Context) {
            val app = context.applicationContext
            if (NotificationPreferenceSource.getInstance(app).keepAlive) start(app)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var ticker: Runnable? = null
    private var lastTickAt = 0L
    private var foregroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTicker()
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_UPDATE) {
            // 只更新通知文案：服务没在前台时把它拉起来（用户主动改设置，属于前台行为）
            if (!foregroundStarted) {
                startForegroundCompat()
            } else {
                repostNotification()
            }
            if (ticker == null) startTicker()
            return START_STICKY
        }
        startForegroundCompat()
        // 拉起时立刻自检一次，把可能丢掉的排期补回来
        ReminderScheduler.rescheduleAll(this)
        lastTickAt = System.currentTimeMillis()
        startTicker()
        return START_STICKY
    }

    /** 重新贴一次常驻通知（文案可能已经变了） */
    private fun repostNotification() {
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        stopTicker()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 周期自检

    private fun startTicker() {
        if (ticker != null) return
        val r = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                if (KeepAlivePlan.shouldTick(lastTickAt, now)) {
                    lastTickAt = now
                    ReminderScheduler.rescheduleAll(this@KeepAliveService)
                    refreshQuoteIfNeeded()
                }
                handler.postDelayed(this, KeepAlivePlan.KEEPALIVE_TICK_MS)
            }
        }
        ticker = r
        handler.postDelayed(r, KeepAlivePlan.KEEPALIVE_TICK_MS)
        // 启动时如果短句过期了，顺手刷一条
        refreshQuoteIfNeeded()
    }

    /**
     * v1.0.5：按需刷新励志短句并更新通知。
     * 网络请求放在子线程，避免阻塞主线程（ticker 跑在主 looper 上）。
     */
    private fun refreshQuoteIfNeeded() {
        val prefs = NotificationPreferenceSource.getInstance(this)
        if (!prefs.keepAliveQuoteEnabled) return
        if (!KeepAlivePlan.shouldRefreshQuote(prefs.quoteFetchedAt, System.currentTimeMillis())) return
        Thread {
            try {
                val updated = com.stupidtree.hitax.data.repository.KeepAliveNotifier
                    .refreshIfNeeded(this, force = false)
                if (updated) {
                    handler.post { repostNotification() }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun stopTicker() {
        ticker?.let { handler.removeCallbacks(it) }
        ticker = null
    }

    // ------------------------------------------------------------------ 通知

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notify_channel_keepalive),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = getString(R.string.notify_keepalive_channel_desc)
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            nm.createNotificationChannel(channel)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )
        // v1.0.5：标题 / 内容可由用户自定义，也可以自动换成励志短句
        val (title, content) = com.stupidtree.hitax.data.repository.KeepAliveNotifier.renderNow(
            this,
            getString(R.string.notify_keepalive_title),
            getString(R.string.notify_keepalive_content)
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_baseline_access_alarm_24)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()
    }

    private fun startForegroundCompat() {
        if (foregroundStarted) return
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
            foregroundStarted = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            // ignore
        }
        foregroundStarted = false
    }
}
