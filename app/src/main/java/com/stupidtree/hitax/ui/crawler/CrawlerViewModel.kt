package com.stupidtree.hitax.ui.crawler

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.stupidtree.hitax.data.repository.CrawlerStorage
import com.stupidtree.hitax.data.repository.EASRepository
import com.stupidtree.hitax.data.source.web.sysu.SysuCrawler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 教务信息爬取 ViewModel（v1.0.5 需求 3）
 *
 * 职责：
 * - 在后台线程驱动 [SysuCrawler]；
 * - 把日志、进度、每个资源的成败通过 LiveData 抛给 UI（日志窗口实时滚动）；
 * - 把抓到的内容交给 [CrawlerStorage] 落盘。
 */
class CrawlerViewModel(application: Application) : AndroidViewModel(application) {

    /** 一条日志 */
    data class LogLine(val time: String, val text: String, val level: Level) {
        enum class Level { INFO, OK, WARN, ERROR }
    }

    /** 进度快照 */
    data class Progress(
        val running: Boolean = false,
        val index: Int = 0,
        val total: Int = SysuCrawler.SOURCES.size,
        val title: String = "",
        val finished: Boolean = false,
        val successCount: Int = 0,
        val failedCount: Int = 0,
        val savedFiles: Int = 0
    )

    private val handler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    val logsLiveData = MutableLiveData<MutableList<LogLine>>(mutableListOf())
    val progressLiveData = MutableLiveData(Progress())
    val toastLiveData = MutableLiveData<String?>()
    val savedCountLiveData = MutableLiveData(0)

    @Volatile
    private var stopRequested = false

    @Volatile
    private var running = false

    private var worker: Thread? = null

    init {
        refreshSavedCount()
    }

    val isRunning: Boolean get() = running

    // ------------------------------------------------------------------
    // 日志
    // ------------------------------------------------------------------

    fun appendLog(text: String, level: LogLine.Level = LogLine.Level.INFO) {
        val line = LogLine(timeFormat.format(Date()), text, level)
        handler.post {
            val list = logsLiveData.value ?: mutableListOf()
            list.add(line)
            // 日志窗口不必无限增长
            while (list.size > MAX_LOG_LINES) list.removeAt(0)
            logsLiveData.value = list
        }
    }

    fun clearLogs() {
        handler.post { logsLiveData.value = mutableListOf() }
    }

    private fun classify(text: String): LogLine.Level = when {
        text.startsWith("!!") -> LogLine.Level.ERROR
        text.startsWith("---") -> LogLine.Level.OK
        text.contains("·") && text.contains("命中") -> LogLine.Level.OK
        text.endsWith("无数据") || text.contains("不可用") -> LogLine.Level.WARN
        else -> LogLine.Level.INFO
    }

    // ------------------------------------------------------------------
    // 爬取
    // ------------------------------------------------------------------

    fun startCrawl() {
        if (running) {
            toastLiveData.value = "正在爬取中"
            return
        }
        val token = EASRepository.getInstance(getApplication()).getEasToken()
        if (!token.isLogin()) {
            appendLog("!! 未登录教务，无法爬取", LogLine.Level.ERROR)
            toastLiveData.value = "NOT_LOGGED_IN"
            return
        }
        stopRequested = false
        running = true
        clearLogs()
        progressLiveData.value = Progress(running = true, title = "准备中…")
        appendLog("会话：${token.name ?: token.username}（${token.stuId ?: "-"}）", LogLine.Level.INFO)

        val app = getApplication<Application>()
        worker = Thread {
            val crawler = SysuCrawler(token) { msg -> appendLog(msg, classify(msg)) }
            try {
                val results = crawler.crawlAll(
                    onProgress = { r, all ->
                        val done = all.count { it.state != SysuCrawler.State.PENDING && it.state != SysuCrawler.State.RUNNING }
                        handler.post {
                            progressLiveData.value = Progress(
                                running = r.state == SysuCrawler.State.RUNNING,
                                index = done,
                                total = all.size,
                                title = r.source.title,
                                finished = false,
                                successCount = all.count { it.state == SysuCrawler.State.SUCCESS },
                                failedCount = all.count { it.state == SysuCrawler.State.FAILED },
                                savedFiles = all.sumOf { it.savedFiles }
                            )
                        }
                    },
                    shouldStop = { stopRequested }
                )
                // 落盘
                var saved = 0
                for (r in results) {
                    for (s in r.saves) {
                        try {
                            if (s.raw) {
                                CrawlerStorage.saveRaw(app, s.category, s.name, s.content, s.sourceUrl)
                            } else {
                                CrawlerStorage.save(app, s.category, s.name, s.content, s.summary, s.sourceUrl)
                            }
                            saved++
                        } catch (e: Exception) {
                            appendLog("!! 写入失败：${s.name}（${e.message}）", LogLine.Level.ERROR)
                        }
                    }
                }
                CrawlerStorage.writeReadableIndex(app)
                val success = results.count { it.state == SysuCrawler.State.SUCCESS }
                val failed = results.count { it.state == SysuCrawler.State.FAILED }
                appendLog("已写入 $saved 个文件到 ${CrawlerStorage.rootDir(app).absolutePath}", LogLine.Level.OK)
                handler.post {
                    progressLiveData.value = Progress(
                        running = false,
                        index = results.size,
                        total = results.size,
                        title = if (stopRequested) "已停止" else "已完成",
                        finished = true,
                        successCount = success,
                        failedCount = failed,
                        savedFiles = saved
                    )
                    savedCountLiveData.value = CrawlerStorage.savedCount(app)
                }

                // ---- 第二阶段：校级 / 学院公开站点（不依赖教务会话）----
                if (!stopRequested) {
                    runPublicSites(app, saved)
                }
            } catch (e: SysuCrawler.NotLoggedIn) {
                appendLog("!! 会话已过期，请重新登录教务", LogLine.Level.ERROR)
                handler.post {
                    progressLiveData.value = Progress(running = false, finished = true, title = "会话过期")
                    toastLiveData.value = "NOT_LOGGED_IN"
                }
            } catch (e: Exception) {
                appendLog("!! 爬取异常：${e.message}", LogLine.Level.ERROR)
                handler.post {
                    progressLiveData.value = Progress(running = false, finished = true, title = "失败")
                }
            } finally {
                running = false
            }
        }.also { it.start() }
    }

    fun stopCrawl() {
        if (!running) return
        stopRequested = true
        appendLog("· 已请求停止，将在当前资源结束后退出", LogLine.Level.WARN)
    }

    /**
     * 第二阶段：抓取校级 / 学院公开站点（v1.0.5 需求 3 的「sysu 各学院也可纳入爬取范围」）
     *
     * 这些站点无需登录，所以放在教务部分之后独立跑；
     * 每个站点的可达性如实写进日志，个别学院站点挂掉不影响整体。
     */
    private fun runPublicSites(app: Application, alreadySaved: Int) {
        appendLog("=== 进入第二阶段：校级 / 学院公开站点 ===", LogLine.Level.OK)
        val sites = com.stupidtree.hitax.data.source.web.sysu.SysuPublicCrawler.defaultSites()
        val crawler = com.stupidtree.hitax.data.source.web.sysu.SysuPublicCrawler { msg ->
            appendLog(msg, classify(msg))
        }
        var saved = 0
        val saves = try {
            crawler.crawl(sites) { site, got ->
                handler.post {
                    val p = progressLiveData.value ?: Progress()
                    progressLiveData.value = p.copy(
                        running = true,
                        title = "公开站点：${site.name}" + if (got > 0) "（$got 页）" else "（不可达）"
                    )
                }
            }
        } catch (e: Exception) {
            appendLog("!! 公开站点抓取异常：${e.message}", LogLine.Level.ERROR)
            emptyList()
        }
        for (s in saves) {
            try {
                CrawlerStorage.save(
                    app, s.category, s.name, s.content, s.summary, s.sourceUrl
                )
                saved++
            } catch (e: Exception) {
                appendLog("!! 写入失败：${s.name}（${e.message}）", LogLine.Level.ERROR)
            }
        }
        CrawlerStorage.writeReadableIndex(app)
        val total = alreadySaved + saved
        appendLog("已写入 $saved 个公开站点文件（本次共 $total 个）", LogLine.Level.OK)
        handler.post {
            val p = progressLiveData.value ?: Progress()
            progressLiveData.value = p.copy(
                running = false,
                finished = true,
                title = "已完成",
                savedFiles = total
            )
            savedCountLiveData.value = CrawlerStorage.savedCount(app)
        }
    }

    fun refreshSavedCount() {
        savedCountLiveData.value = CrawlerStorage.savedCount(getApplication())
    }

    fun consumeToast() {
        toastLiveData.value = null
    }

    override fun onCleared() {
        stopRequested = true
        super.onCleared()
    }

    companion object {
        const val MAX_LOG_LINES = 800
    }
}
