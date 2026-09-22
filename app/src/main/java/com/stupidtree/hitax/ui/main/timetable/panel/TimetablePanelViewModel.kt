package com.stupidtree.hitax.ui.main.timetable.panel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import com.stupidtree.component.data.SharedPreferenceBooleanLiveData
import com.stupidtree.component.data.SharedPreferenceIntLiveData
import com.stupidtree.component.data.Trigger
import com.stupidtree.hitax.data.model.timetable.Timetable
import com.stupidtree.hitax.data.repository.SubjectRepository
import com.stupidtree.hitax.data.repository.TimetableRepository
import com.stupidtree.hitax.data.repository.TimetableStyleRepository
import com.stupidtree.hitax.data.repository.TimetableStyleRepository.Companion.KEY_COLOR_ENABLE
import com.stupidtree.hitax.data.repository.TimetableStyleRepository.Companion.KEY_DRAW_BG_LINE
import com.stupidtree.hitax.data.repository.TimetableStyleRepository.Companion.KEY_FADE_ENABLE
import com.stupidtree.hitax.data.repository.TimetableStyleRepository.Companion.KEY_START_DATE
import com.stupidtree.hitax.data.source.preference.NotificationPreferenceSource
import com.stupidtree.hitax.data.source.preference.TimetableBackgroundSource

class TimetablePanelViewModel(application: Application) : AndroidViewModel(application) {

    private val timetableStyleRepository = TimetableStyleRepository.getInstance(application)
    private val subjectRepository = SubjectRepository.getInstance(application)
    private val timetableRepository = TimetableRepository.getInstance(application)
    private val backgroundSource = TimetableBackgroundSource.getInstance(application)
    private val notificationPrefs = NotificationPreferenceSource.getInstance(application)

    val startDateLiveData: SharedPreferenceIntLiveData
        get() = timetableStyleRepository.startTimeLiveData
    val drawBGLinesLiveData: SharedPreferenceBooleanLiveData
        get() = timetableStyleRepository.drawBGLinesLiveData

    val colorEnableLiveData: SharedPreferenceBooleanLiveData
        get() = timetableStyleRepository.colorEnableLiveData

    val fadeEnableLiveData: SharedPreferenceBooleanLiveData
        get() = timetableStyleRepository.fadeEnableLiveData

    // ------------------------------------------------------------------
    // 课表背景
    // ------------------------------------------------------------------

    /** 当前课表（背景设置按课表区分） */
    private val timetableTrigger = MutableLiveData<Trigger>()

    val currentTimetableLiveData: LiveData<Timetable?> = timetableTrigger.switchMap {
        timetableRepository.getRecentTimetable()
    }

    /** 触发一次当前课表查询 */
    fun startLoadTimetable() {
        timetableTrigger.value = Trigger.actioning
    }

    /** 背景相关状态 */
    val backgroundInfoLiveData = MutableLiveData<BackgroundInfo>()

    data class BackgroundInfo(
        val hasImage: Boolean,
        val opacity: Int,
        val dim: Int
    )

    fun setBackgroundTimetable(timetable: Timetable?) {
        loadBackgroundInfo(timetable)
    }

    private var current: Timetable? = null

    fun loadBackgroundInfo(timetable: Timetable?) {
        current = timetable
        publishBackgroundInfo()
    }

    private fun publishBackgroundInfo() {
        val t = current
        if (t == null) {
            backgroundInfoLiveData.value = BackgroundInfo(false, 100, 0)
            return
        }
        backgroundInfoLiveData.value = BackgroundInfo(
            backgroundSource.hasBackground(t.id),
            backgroundSource.getOpacity(t.id),
            backgroundSource.getDim(t.id)
        )
    }

    fun setBackgroundOpacity(value: Int) {
        val t = current ?: return
        backgroundSource.setOpacity(t.id, value)
        backgroundInfoLiveData.value = backgroundInfoLiveData.value?.copy(opacity = value)
            ?: BackgroundInfo(true, value, 0)
    }

    fun setBackgroundDim(value: Int) {
        val t = current ?: return
        backgroundSource.setDim(t.id, value)
        backgroundInfoLiveData.value = backgroundInfoLiveData.value?.copy(dim = value)
            ?: BackgroundInfo(true, 100, value)
    }

    fun clearBackground() {
        val t = current ?: return
        backgroundSource.clearBackground(t.id)
        publishBackgroundInfo()
    }

    /** 通知提醒摘要文案 */
    fun notificationSummary(): String {
        return if (!notificationPrefs.isEnabled) "已关闭"
        else "提前 ${notificationPrefs.leadMinutes} 分钟"
    }

    // ------------------------------------------------------------------

    fun changeStartDate(hour: Int, minute: Int) {
        val v = hour * 100 + minute
        timetableStyleRepository.putData(KEY_START_DATE, v)
    }

    fun setDrawBGLines(draw: Boolean) {
        timetableStyleRepository.putData(KEY_DRAW_BG_LINE, draw)
    }

    fun setColorEnable(draw: Boolean) {
        timetableStyleRepository.putData(KEY_COLOR_ENABLE, draw)
    }

    fun setFadeEnable(draw: Boolean) {
        timetableStyleRepository.putData(KEY_FADE_ENABLE, draw)
    }

    fun startResetColor() {
        subjectRepository.actionResetRecentSubjectColors()
    }
}
