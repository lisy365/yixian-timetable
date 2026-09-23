package com.stupidtree.hitax.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import com.stupidtree.hitax.data.model.timetable.EventItem
import com.stupidtree.hitax.data.source.preference.NotificationPreferenceSource
import com.stupidtree.hitax.utils.KeepAliveService
import com.stupidtree.hitax.utils.NotificationUtils
import com.stupidtree.hitax.utils.ReminderScheduler
import java.sql.Timestamp
import java.util.Calendar

/**
 * 通知提醒设置页的 ViewModel
 */
class NotificationSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = NotificationPreferenceSource.getInstance(application)

    /** 每次设置变化都自增，UI 据此刷新 */
    val refreshTrigger = MutableLiveData(0)

    val isEnabled: Boolean get() = prefs.isEnabled
    val classEnabled: Boolean get() = prefs.classEnabled
    val eventEnabled: Boolean get() = prefs.eventEnabled
    val ddlEnabled: Boolean get() = prefs.ddlEnabled
    val onlySchoolDays: Boolean get() = prefs.onlySchoolDays
    val leadMinutes: Int get() = prefs.leadMinutes
    val ddlLeadMinutes: Int get() = prefs.ddlLeadMinutes
    val repeatCount: Int get() = prefs.repeatCount
    val repeatInterval: Int get() = prefs.repeatInterval
    val soundEnabled: Boolean get() = prefs.soundEnabled
    val vibrateEnabled: Boolean get() = prefs.vibrateEnabled
    val titleTemplate: String get() = prefs.titleTemplate
    val contentTemplate: String get() = prefs.contentTemplate
    val ddlTitleTemplate: String get() = prefs.ddlTitleTemplate
    val ddlContentTemplate: String get() = prefs.ddlContentTemplate
    val keepAlive: Boolean get() = prefs.keepAlive

    /** 是否已经拿到「精确闹钟」能力（Android 12+ 需要用户授权） */
    fun canScheduleExact(): Boolean = ReminderScheduler.canScheduleExact(getApplication())

    private fun changed() {
        refreshTrigger.value = (refreshTrigger.value ?: 0) + 1
        // 设置变化后立即重排提醒
        ReminderScheduler.rescheduleAll(getApplication())
    }

    fun setEnabled(v: Boolean) { prefs.isEnabled = v; changed() }
    fun setClassEnabled(v: Boolean) { prefs.classEnabled = v; changed() }
    fun setEventEnabled(v: Boolean) { prefs.eventEnabled = v; changed() }
    fun setDdlEnabled(v: Boolean) { prefs.ddlEnabled = v; changed() }
    fun setOnlySchoolDays(v: Boolean) { prefs.onlySchoolDays = v; changed() }
    fun setLeadMinutes(v: Int) { prefs.leadMinutes = v; changed() }
    fun setDdlLeadMinutes(v: Int) { prefs.ddlLeadMinutes = v; changed() }
    fun setRepeatCount(v: Int) { prefs.repeatCount = v; changed() }
    fun setRepeatInterval(v: Int) { prefs.repeatInterval = v; changed() }
    fun setSoundEnabled(v: Boolean) { prefs.soundEnabled = v; changed() }
    fun setVibrateEnabled(v: Boolean) { prefs.vibrateEnabled = v; changed() }

    /** 后台保活开关：同步启停前台服务 */
    fun setKeepAlive(v: Boolean) {
        prefs.keepAlive = v
        KeepAliveService.setEnabled(getApplication(), v)
        changed()
    }

    fun setTemplate(title: String, content: String) {
        prefs.titleTemplate = title
        prefs.contentTemplate = content
        changed()
    }

    fun setDdlTemplate(title: String, content: String) {
        prefs.ddlTitleTemplate = title
        prefs.ddlContentTemplate = content
        changed()
    }

    fun reset() {
        prefs.resetToDefault()
        changed()
    }

    /** 发送一条测试通知，验证通知链路是否正常 */
    fun sendTestNotification() {
        val app = getApplication<Application>()
        val now = Calendar.getInstance()
        val event = EventItem().apply {
            id = "yixian_test_notification"
            name = app.getString(com.stupidtree.hitax.R.string.notify_test_title)
            place = "测试地点"
            teacher = "测试教师"
            type = EventItem.TYPE.CLASS
            from = Timestamp(now.timeInMillis)
            to = Timestamp(now.timeInMillis)
        }
        NotificationUtils.notifyEvent(app, event, prefs.leadMinutes, false)
    }

    /** 生成模板预览文案 */
    fun preview(): Pair<String, String> {
        val app = getApplication<Application>()
        val now = Calendar.getInstance()
        val event = EventItem().apply {
            name = "高等数学"
            place = "第五教学楼逸401"
            teacher = "邹雄"
            note = "记得带作业"
            type = EventItem.TYPE.CLASS
            from = Timestamp(now.timeInMillis)
            to = Timestamp(now.timeInMillis)
        }
        return NotificationUtils.render(app, event, prefs.leadMinutes, false)
    }
}
