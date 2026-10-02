package com.stupidtree.hitax.data.repository

import android.content.Context
import android.util.Log
import com.stupidtree.hitax.R
import com.stupidtree.hitax.data.source.preference.NotificationPreferenceSource
import com.stupidtree.hitax.utils.QuoteProvider
import java.net.HttpURLConnection
import java.net.URL

/**
 * 保活通知文案仓库（v1.0.5 需求 5）
 *
 * 职责：
 * - 提供「通知标题 / 内容」的最终文本（用户自定义 > 励志短句 > 默认文案）；
 * - 按需从公益 API 拉取短句并缓存；失败时轮换内置语录，**永远不返回空文本**。
 *
 * 网络实现用 [HttpURLConnection]，因为这是系统 API、无额外依赖，
 * 而且调用点只有一处（保活服务每 6 小时一次）。
 */
object KeepAliveNotifier {

    private const val TAG = "KeepAliveNotifier"
    private const val TIMEOUT_MS = 8000

    /**
     * 计算要展示的标题与内容（同步、不联网）
     *
     * @param fallbackTitle 默认标题（资源字符串）
     * @param fallbackContent 默认内容（资源字符串）
     */
    fun renderNow(context: Context, fallbackTitle: String, fallbackContent: String): Pair<String, String> {
        val prefs = NotificationPreferenceSource.getInstance(context)
        val quote = currentQuote(context)?.let {
            QuoteProvider.Quote(it.first, it.second, it.third)
        }
        return QuoteProvider.render(
            titleTemplate = prefs.keepAliveTitle,
            contentTemplate = prefs.keepAliveContent,
            quote = quote,
            fallbackTitle = fallbackTitle,
            fallbackContent = fallbackContent,
            placeholders = mapOf(
                "{date}" to java.text.SimpleDateFormat("M月d日", java.util.Locale.getDefault())
                    .format(java.util.Date())
            )
        )
    }

    /**
     * 当前缓存的短句（正文, 出处, 作者）；没有则返回 null
     */
    fun currentQuote(context: Context): Triple<String, String, String>? {
        val prefs = NotificationPreferenceSource.getInstance(context)
        val text = prefs.quoteText
        if (text.isBlank()) return null
        return Triple(text, prefs.quoteSource, prefs.quoteAuthor)
    }

    /**
     * 到了刷新时间就联网拉一条新的短句（阻塞，请在子线程调用）
     *
     * @return true 表示短句有更新
     */
    fun refreshIfNeeded(context: Context, force: Boolean = false): Boolean {
        val prefs = NotificationPreferenceSource.getInstance(context)
        if (!prefs.keepAliveQuoteEnabled && !force) return false
        val now = System.currentTimeMillis()
        if (!force && !QuoteProvider.shouldRefresh(prefs.quoteFetchedAt, now)) return false

        val api = prefs.keepAliveQuoteApi.ifBlank { QuoteProvider.DEFAULT_API }
        val fetched = try {
            QuoteProvider.parse(httpGet(api))
        } catch (e: Exception) {
            Log.w(TAG, "拉取励志短句失败：${e.message}")
            null
        }
        if (fetched != null) {
            prefs.quoteText = fetched.text
            prefs.quoteSource = fetched.source
            prefs.quoteAuthor = fetched.author
            prefs.quoteFetchedAt = now
            Log.i(TAG, "短句已更新：${fetched.text}")
            return true
        }
        // 失败：轮换内置语录（确定性，方便复现）
        val idx = prefs.offlineQuoteIndex + 1
        prefs.offlineQuoteIndex = idx
        val builtIn = QuoteProvider.builtInAt(idx)
        prefs.quoteText = builtIn.text
        prefs.quoteSource = builtIn.source
        prefs.quoteAuthor = builtIn.author
        prefs.quoteFetchedAt = now
        return true
    }

    /** 强制刷新一条（设置页「立即刷新一句」用），返回展示文本 */
    fun refreshForcedBlocking(context: Context): String {
        val ok = try {
            refreshIfNeeded(context, force = true)
        } catch (e: Exception) {
            false
        }
        val prefs = NotificationPreferenceSource.getInstance(context)
        // force = true 时 refreshIfNeeded 一定会写入（联网或内置兜底）
        val fallbackTitle = context.getString(R.string.notify_keepalive_title)
        val fallbackContent = context.getString(R.string.notify_keepalive_content)
        val rendered = renderNow(context, fallbackTitle, fallbackContent)
        if (!ok && prefs.quoteText.isBlank()) return fallbackContent
        return rendered.second
    }

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", "YixianTimetable/1.0.5 (Android)")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            if (stream == null) return ""
            return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            try {
                conn.disconnect()
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}
