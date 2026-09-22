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
            .apply()
    }
}
