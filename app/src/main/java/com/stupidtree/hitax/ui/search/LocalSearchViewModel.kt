package com.stupidtree.hitax.ui.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import com.stupidtree.component.data.DataState
import com.stupidtree.hitax.data.AppDatabase
import com.stupidtree.hitax.data.model.timetable.TermSubject
import java.util.concurrent.Executors

/**
 * 本地课程搜索（逸仙课表）
 *
 * 原项目的搜索依赖 HITSZ 的教师/社区服务端，中大不可用；
 * 这里改为在本地课表数据库中搜索：课程名、任课教师、上课地点。
 */
class LocalSearchViewModel(application: Application) : AndroidViewModel(application) {

    private val executor = Executors.newSingleThreadExecutor()
    private val subjectDao = AppDatabase.getDatabase(application).subjectDao()
    private val eventItemDao = AppDatabase.getDatabase(application).eventItemDao()

    /** 搜索关键词 */
    val queryLiveData = MutableLiveData<String>("")

    /** 搜索结果 */
    val resultLiveData: MediatorLiveData<DataState<List<TermSubject>>> = MediatorLiveData()

    /** 命中的匹配说明，与 result 一一对应 */
    var matchHints: Map<String, String> = emptyMap()
        private set

    init {
        resultLiveData.addSource(queryLiveData) { q ->
            search(q ?: "")
        }
    }

    fun search(keyword: String) {
        val q = keyword.trim()
        if (q.isEmpty()) {
            matchHints = emptyMap()
            resultLiveData.value = DataState(ArrayList<TermSubject>(), DataState.STATE.SUCCESS)
            return
        }
        executor.execute {
            try {
                val subjects = subjectDao.getAllSubjectsSync()
                val events = eventItemDao.getAllEventsSync()
                val lowered = q.lowercase()
                val hints = HashMap<String, String>()
                val matched = ArrayList<TermSubject>()
                for (s in subjects) {
                    if (s.name.lowercase().contains(lowered)) {
                        hints[s.id] = "课程名"
                        matched.add(s)
                        continue
                    }
                    val related = events.filter { it.subjectId == s.id }
                    val teacherHit = related.firstOrNull {
                        !it.teacher.isNullOrEmpty() && it.teacher!!.lowercase().contains(lowered)
                    }
                    if (teacherHit != null) {
                        hints[s.id] = "教师：${teacherHit.teacher}"
                        matched.add(s)
                        continue
                    }
                    val placeHit = related.firstOrNull {
                        !it.place.isNullOrEmpty() && it.place!!.lowercase().contains(lowered)
                    }
                    if (placeHit != null) {
                        hints[s.id] = "地点：${placeHit.place}"
                        matched.add(s)
                    }
                }
                matchHints = hints
                resultLiveData.postValue(DataState(matched, DataState.STATE.SUCCESS))
            } catch (e: Exception) {
                e.printStackTrace()
                resultLiveData.postValue(
                    DataState(DataState.STATE.FETCH_FAILED, e.message)
                )
            }
        }
    }

    /** 供观察者使用 */
    val results: LiveData<DataState<List<TermSubject>>>
        get() = resultLiveData
}
