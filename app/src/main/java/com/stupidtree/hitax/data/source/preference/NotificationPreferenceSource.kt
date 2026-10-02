package com.stupidtree.hitax.data.source.preference

import android.content.Context
import android.content.SharedPreferences

/**
 * 通知与提醒设置
 *
 * 全部设置项均保存在 SharedPreferences，用户可在「课表设置 → 通知提醒」中调整。
 * 保留了合理的预设值，未设置过的用户直接使用预设。
 */
class NotificationPreferenceSource private constructor(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val SP_NAME = "yixian_notification"

        // 总开关
        private const val KEY_ENABLE = "enable"
        private const val KEY_CLASS_ENABLE = "class_enable"
        private const val KEY_EVENT_ENABLE = "event_enable"
        private const val KEY_DDL_ENABLE = "ddl_enable"

        // 提前量（分钟）
        private const val KEY_LEAD_MINUTES = "lead_minutes"
        private const val KEY_DDL_LEAD_MINUTES = "ddl_lead_minutes"

        // 重复提醒
        private const val KEY_REPEAT_COUNT = "repeat_count"
        private const val KEY_REPEAT_INTERVAL = "repeat_interval"

        // 文案模板
        private const val KEY_TITLE_TEMPLATE = "title_template"
        private const val KEY_CONTENT_TEMPLATE = "content_template"
        private const val KEY_DDL_TITLE_TEMPLATE = "ddl_title_template"
        private const val KEY_DDL_CONTENT_TEMPLATE = "ddl_content_template"

        // 其他
        private const val KEY_SOUND = "sound"
        private const val KEY_VIBRATE = "vibrate"
        private const val KEY_ONLY_SCHOOL_DAYS = "only_school_days"

        /** 后台保活（前台服务常驻，默认关闭，避免默认占通知栏与耗电） */
        private const val KEY_KEEP_ALIVE = "keep_alive"

        // ---------------- v1.0.5：保活通知文案 ----------------
        private const val KEY_KEEPALIVE_TITLE = "keepalive_title"
        private const val KEY_KEEPALIVE_CONTENT = "keepalive_content"
        private const val KEY_KEEPALIVE_QUOTE_ENABLE = "keepalive_quote_enable"
        private const val KEY_KEEPALIVE_API = "keepalive_api"
        private const val KEY_KEEPALIVE_QUOTE_TEXT = "keepalive_quote_text"
        private const val KEY_KEEPALIVE_QUOTE_SOURCE = "keepalive_quote_source"
        private const val KEY_KEEPALIVE_QUOTE_AUTHOR = "keepalive_quote_author"
        private const val KEY_KEEPALIVE_QUOTE_AT = "keepalive_quote_at"
        private const val KEY_KEEPALIVE_OFFLINE_INDEX = "keepalive_offline_index"

        /** 预设：提前 15 分钟提醒 */
        const val DEFAULT_LEAD_MINUTES = 15

        /** 预设：额外重复 1 次（即共 2 次提醒），间隔 5 分钟 */
        const val DEFAULT_REPEAT_COUNT = 1
        const val DEFAULT_REPEAT_INTERVAL = 5

        const val DEFAULT_TITLE_TEMPLATE = "{time} {name}"
        /** 注意：{minutes} 已自带单位（如「15 分钟」），模板里不要重复写单位 */
        const val DEFAULT_CONTENT_TEMPLATE = "{minutes}后开始 · {place} {teacher}"

        const val DEFAULT_DDL_TITLE_TEMPLATE = "待办提醒：{name}"
        const val DEFAULT_DDL_CONTENT_TEMPLATE = "距截止还有 {minutes}{note}"

        /** 可选提前量（分钟） */
        val LEAD_OPTIONS = intArrayOf(0, 5, 10, 15, 20, 30, 45, 60, 120)

        /** 可选重复次数（0 = 只提醒一次） */
        val REPEAT_OPTIONS = intArrayOf(0, 1, 2, 3, 5)

        /** 可选重复间隔（分钟） */
        val INTERVAL_OPTIONS = intArrayOf(1, 3, 5, 10, 15)

        @Volatile
        private var instance: NotificationPreferenceSource? = null

        @JvmStatic
        fun getInstance(context: Context): NotificationPreferenceSource {
            if (instance == null) {
                synchronized(NotificationPreferenceSource::class.java) {
                    if (instance == null) {
                        instance = NotificationPreferenceSource(context.applicationContext)
                    }
                }
            }
            return instance!!
        }
    }

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    var isEnabled: Boolean
        get() = sp.getBoolean(KEY_ENABLE, true)
        set(v) = sp.edit().putBoolean(KEY_ENABLE, v).apply()

    var classEnabled: Boolean
        get() = sp.getBoolean(KEY_CLASS_ENABLE, true)
        set(v) = sp.edit().putBoolean(KEY_CLASS_ENABLE, v).apply()

    var eventEnabled: Boolean
        get() = sp.getBoolean(KEY_EVENT_ENABLE, true)
        set(v) = sp.edit().putBoolean(KEY_EVENT_ENABLE, v).apply()

    var ddlEnabled: Boolean
        get() = sp.getBoolean(KEY_DDL_ENABLE, true)
        set(v) = sp.edit().putBoolean(KEY_DDL_ENABLE, v).apply()

    /** 课程 / 日程提前量（分钟） */
    var leadMinutes: Int
        get() = sp.getInt(KEY_LEAD_MINUTES, DEFAULT_LEAD_MINUTES)
        set(v) = sp.edit().putInt(KEY_LEAD_MINUTES, v).apply()

    /** 待办 DDL 提前量（分钟） */
    var ddlLeadMinutes: Int
        get() = sp.getInt(KEY_DDL_LEAD_MINUTES, 60)
        set(v) = sp.edit().putInt(KEY_DDL_LEAD_MINUTES, v).apply()

    /** 重复次数（除首次外的额外次数） */
    var repeatCount: Int
        get() = sp.getInt(KEY_REPEAT_COUNT, DEFAULT_REPEAT_COUNT)
        set(v) = sp.edit().putInt(KEY_REPEAT_COUNT, v.coerceIn(0, 10)).apply()

    /** 重复间隔（分钟） */
    var repeatInterval: Int
        get() = sp.getInt(KEY_REPEAT_INTERVAL, DEFAULT_REPEAT_INTERVAL)
        set(v) = sp.edit().putInt(KEY_REPEAT_INTERVAL, v.coerceIn(1, 60)).apply()

    var titleTemplate: String
        get() = sp.getString(KEY_TITLE_TEMPLATE, DEFAULT_TITLE_TEMPLATE) ?: DEFAULT_TITLE_TEMPLATE
        set(v) = sp.edit().putString(KEY_TITLE_TEMPLATE, v).apply()

    var contentTemplate: String
        get() = sp.getString(KEY_CONTENT_TEMPLATE, DEFAULT_CONTENT_TEMPLATE) ?: DEFAULT_CONTENT_TEMPLATE
        set(v) = sp.edit().putString(KEY_CONTENT_TEMPLATE, v).apply()

    var ddlTitleTemplate: String
        get() = sp.getString(KEY_DDL_TITLE_TEMPLATE, DEFAULT_DDL_TITLE_TEMPLATE)
            ?: DEFAULT_DDL_TITLE_TEMPLATE
        set(v) = sp.edit().putString(KEY_DDL_TITLE_TEMPLATE, v).apply()

    var ddlContentTemplate: String
        get() = sp.getString(KEY_DDL_CONTENT_TEMPLATE, DEFAULT_DDL_CONTENT_TEMPLATE)
            ?: DEFAULT_DDL_CONTENT_TEMPLATE
        set(v) = sp.edit().putString(KEY_DDL_CONTENT_TEMPLATE, v).apply()

    var soundEnabled: Boolean
        get() = sp.getBoolean(KEY_SOUND, true)
        set(v) = sp.edit().putBoolean(KEY_SOUND, v).apply()

    var vibrateEnabled: Boolean
        get() = sp.getBoolean(KEY_VIBRATE, true)
        set(v) = sp.edit().putBoolean(KEY_VIBRATE, v).apply()

    /** 仅上课日提醒（周末的课程/日程不提醒） */
    var onlySchoolDays: Boolean
        get() = sp.getBoolean(KEY_ONLY_SCHOOL_DAYS, false)
        set(v) = sp.edit().putBoolean(KEY_ONLY_SCHOOL_DAYS, v).apply()

    /**
     * 后台保活：开启后常驻一个低优先级前台服务，
     * 周期性重排提醒排期，降低国产 ROM 省电策略导致的漏提醒概率。
     */
    var keepAlive: Boolean
        get() = sp.getBoolean(KEY_KEEP_ALIVE, false)
        set(v) = sp.edit().putBoolean(KEY_KEEP_ALIVE, v).apply()

    // ------------------------------------------------------------------
    // v1.0.5：保活通知文案（可自定义 / 可自动更新励志短句）
    // ------------------------------------------------------------------

    /** 保活通知标题；空字符串表示用默认文案 */
    var keepAliveTitle: String
        get() = sp.getString(KEY_KEEPALIVE_TITLE, "") ?: ""
        set(v) = sp.edit().putString(KEY_KEEPALIVE_TITLE, v).apply()

    /** 保活通知内容；空字符串表示用默认文案 / 短句 */
    var keepAliveContent: String
        get() = sp.getString(KEY_KEEPALIVE_CONTENT, "") ?: ""
        set(v) = sp.edit().putString(KEY_KEEPALIVE_CONTENT, v).apply()

    /** 是否自动更新励志短句 */
    var keepAliveQuoteEnabled: Boolean
        get() = sp.getBoolean(KEY_KEEPALIVE_QUOTE_ENABLE, false)
        set(v) = sp.edit().putBoolean(KEY_KEEPALIVE_QUOTE_ENABLE, v).apply()

    /** 励志短句接口（公益 API，默认一言） */
    var keepAliveQuoteApi: String
        get() = sp.getString(KEY_KEEPALIVE_API, "") ?: ""
        set(v) = sp.edit().putString(KEY_KEEPALIVE_API, v).apply()

    /** 已缓存的短句正文 */
    var quoteText: String
        get() = sp.getString(KEY_KEEPALIVE_QUOTE_TEXT, "") ?: ""
        set(v) = sp.edit().putString(KEY_KEEPALIVE_QUOTE_TEXT, v).apply()

    var quoteSource: String
        get() = sp.getString(KEY_KEEPALIVE_QUOTE_SOURCE, "") ?: ""
        set(v) = sp.edit().putString(KEY_KEEPALIVE_QUOTE_SOURCE, v).apply()

    var quoteAuthor: String
        get() = sp.getString(KEY_KEEPALIVE_QUOTE_AUTHOR, "") ?: ""
        set(v) = sp.edit().putString(KEY_KEEPALIVE_QUOTE_AUTHOR, v).apply()

    /** 上次成功获取短句的时间戳 */
    var quoteFetchedAt: Long
        get() = sp.getLong(KEY_KEEPALIVE_QUOTE_AT, 0L)
        set(v) = sp.edit().putLong(KEY_KEEPALIVE_QUOTE_AT, v).apply()

    /** 离线轮换游标（内置语录） */
    var offlineQuoteIndex: Int
        get() = sp.getInt(KEY_KEEPALIVE_OFFLINE_INDEX, 0)
        set(v) = sp.edit().putInt(KEY_KEEPALIVE_OFFLINE_INDEX, v).apply()

    /** 恢复默认设置 */
    fun resetToDefault() {
        sp.edit()
            .putBoolean(KEY_ENABLE, true)
            .putBoolean(KEY_CLASS_ENABLE, true)
            .putBoolean(KEY_EVENT_ENABLE, true)
            .putBoolean(KEY_DDL_ENABLE, true)
            .putInt(KEY_LEAD_MINUTES, DEFAULT_LEAD_MINUTES)
            .putInt(KEY_DDL_LEAD_MINUTES, 60)
            .putInt(KEY_REPEAT_COUNT, DEFAULT_REPEAT_COUNT)
            .putInt(KEY_REPEAT_INTERVAL, DEFAULT_REPEAT_INTERVAL)
            .putString(KEY_TITLE_TEMPLATE, DEFAULT_TITLE_TEMPLATE)
            .putString(KEY_CONTENT_TEMPLATE, DEFAULT_CONTENT_TEMPLATE)
            .putString(KEY_DDL_TITLE_TEMPLATE, DEFAULT_DDL_TITLE_TEMPLATE)
            .putString(KEY_DDL_CONTENT_TEMPLATE, DEFAULT_DDL_CONTENT_TEMPLATE)
            .putBoolean(KEY_SOUND, true)
            .putBoolean(KEY_VIBRATE, true)
            .putBoolean(KEY_ONLY_SCHOOL_DAYS, false)
            .putBoolean(KEY_KEEP_ALIVE, false)
            .putString(KEY_KEEPALIVE_TITLE, "")
            .putString(KEY_KEEPALIVE_CONTENT, "")
            .putBoolean(KEY_KEEPALIVE_QUOTE_ENABLE, false)
            .putString(KEY_KEEPALIVE_API, "")
            .putString(KEY_KEEPALIVE_QUOTE_TEXT, "")
            .putString(KEY_KEEPALIVE_QUOTE_SOURCE, "")
            .putString(KEY_KEEPALIVE_QUOTE_AUTHOR, "")
            .putLong(KEY_KEEPALIVE_QUOTE_AT, 0L)
            .putInt(KEY_KEEPALIVE_OFFLINE_INDEX, 0)
            .apply()
    }
}
