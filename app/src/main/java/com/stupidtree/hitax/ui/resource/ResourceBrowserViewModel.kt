package com.stupidtree.hitax.ui.resource

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import com.stupidtree.hitax.data.repository.CrawlerRepository

/**
 * 爬取资料浏览器的 ViewModel（v1.0.7 需求 3）
 *
 * 读本地文件是阻塞操作，所以放到子线程，读完 postValue 回 UI。
 */
class ResourceBrowserViewModel(application: Application) : AndroidViewModel(application) {

    val resourcesLiveData = MutableLiveData<List<CrawlerRepository.TeachingResource>>(emptyList())

    @Volatile
    private var loading = false

    fun load(categories: Array<String>) {
        if (loading) return
        loading = true
        val app = getApplication<Application>()
        Thread {
            val list = try {
                CrawlerRepository.loadResources(app, *categories)
            } catch (e: Exception) {
                emptyList()
            }
            resourcesLiveData.postValue(list)
            loading = false
        }.start()
    }
}
