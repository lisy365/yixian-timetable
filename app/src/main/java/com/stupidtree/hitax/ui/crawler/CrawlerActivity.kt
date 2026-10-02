package com.stupidtree.hitax.ui.crawler

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.stupidtree.hitax.R
import com.stupidtree.hitax.data.repository.CrawlerStorage
import com.stupidtree.hitax.data.source.web.sysu.SysuPublicCrawler
import com.stupidtree.hitax.databinding.ActivityCrawlerBinding
import com.stupidtree.hitax.utils.ActivityUtils
import com.stupidtree.style.base.BaseActivity
import com.stupidtree.style.widgets.PopUpText
import java.io.File

/**
 * 教务信息爬取页（v1.0.5 需求 3）
 *
 * 界面分三块：
 * 1. 顶部进度卡（当前资源 / 成功失败计数 / 进度条）；
 * 2. 操作行（开始 / 停止、清空日志、查看已保存内容）；
 * 3. **爬取日志窗口**（实时滚动，逐条说明爬虫做了什么、命中哪个接口、哪里不可用）。
 */
class CrawlerActivity : BaseActivity<CrawlerViewModel, ActivityCrawlerBinding>() {

    private lateinit var logAdapter: LogAdapter

    override fun getViewModelClass(): Class<CrawlerViewModel> = CrawlerViewModel::class.java

    override fun initViewBinding(): ActivityCrawlerBinding =
        ActivityCrawlerBinding.inflate(layoutInflater)

    override fun initViews() {
        setToolbarActionBack(binding.toolbar)

        logAdapter = LogAdapter()
        binding.logList.layoutManager = LinearLayoutManager(this)
        binding.logList.adapter = logAdapter

        binding.start.setOnClickListener {
            if (viewModel.isRunning) viewModel.stopCrawl() else viewModel.startCrawl()
        }
        binding.clearLog.setOnClickListener {
            PopUpText().setTitle(R.string.crawler_confirm_clear)
                .setOnConfirmListener(object : PopUpText.OnConfirmListener {
                    override fun OnConfirm() {
                        viewModel.clearLogs()
                        Toast.makeText(
                            this@CrawlerActivity,
                            R.string.crawler_cleared,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }).show(supportFragmentManager, "clear_log")
        }
        binding.openDir.setOnClickListener { openSavedFolder() }

        buildSearchShortcuts()

        viewModel.logsLiveData.observe(this) { list ->
            logAdapter.submit(list)
            binding.logEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            if (list.isNotEmpty()) binding.logList.scrollToPosition(list.size - 1)
        }
        viewModel.progressLiveData.observe(this) { p ->
            binding.progressBar.max = p.total.coerceAtLeast(1)
            binding.progressBar.progress = p.index
            binding.start.text = getString(
                if (p.running) R.string.crawler_stop else R.string.crawler_start
            )
            val title = when {
                p.running -> "${getString(R.string.crawler_state_running)} ${p.index}/${p.total} · ${p.title}"
                p.finished -> "${p.title} · " + getString(
                    R.string.crawler_summary, p.successCount, p.failedCount
                )
                else -> getString(R.string.crawler_state_idle)
            }
            binding.progressTitle.text = title
            binding.progressSub.text = if (p.savedFiles > 0) {
                getString(R.string.crawler_saved_count, p.savedFiles)
            } else {
                getString(R.string.crawler_scope_hint)
            }
        }
        viewModel.savedCountLiveData.observe(this) {
            binding.savedCount.text = getString(R.string.crawler_saved_count, it)
        }
        viewModel.toastLiveData.observe(this) { msg ->
            if (msg == null) return@observe
            if (msg == "NOT_LOGGED_IN") {
                Toast.makeText(this, R.string.crawler_not_logged_in, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
            viewModel.consumeToast()
        }
        viewModel.refreshSavedCount()
    }

    fun setToolbarActionBack(toolbar: com.google.android.material.appbar.MaterialToolbar) {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeButtonEnabled(true)
        toolbar.setNavigationOnClickListener { onBackPressed() }
    }

    /**
     * 「在校内系统里检索」快捷入口
     *
     * 教学大纲、培养方案原文这类内容，教务系统不一定会开放接口；
     * 与其硬猜接口，不如给用户一个一键跳到校内检索页的入口。
     */
    private fun buildSearchShortcuts() {
        val box = binding.searchBox
        box.removeAllViews()
        for ((name, template, needLogin) in SysuPublicCrawler.SEARCH_TEMPLATES) {
            val chip = TextView(this).apply {
                text = name
                textSize = 12f
                setTextColor(resolveColorPrimary())
                gravity = android.view.Gravity.CENTER
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setBackgroundResource(R.drawable.element_rounded_bar_grey_light_24)
                setOnClickListener {
                    val url = template.replace("{}", Uri.encode("教学大纲"))
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (e: Exception) {
                        Toast.makeText(
                            this@CrawlerActivity,
                            R.string.crawler_open_dir,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = dp(8)
            chip.layoutParams = lp
            box.addView(chip)
            if (needLogin) chip.alpha = 0.85f
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun resolveColorPrimary(): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(R.attr.colorPrimary, tv, true)
        return if (tv.data != 0) tv.data else 0xFF00693E.toInt()
    }

    /**
     * 「查看已保存内容」
     *
     * 应用私有目录（`/data/data/<pkg>/files`）在未 root 的机器上无法用文件管理器浏览，
     * 所以这里做两件事：
     * 1. 把结果**导出**一份到 `Android/data/<pkg>/files/crawler-out/`（这个目录
     *    在 Android 11+ 的文件管理器与 USB 连接里可以直接打开）；
     * 2. 尝试用系统「文件」应用打开该目录，打不开就弹窗显示路径。
     */
    private fun openSavedFolder() {
        val outDir = exportToExternal()
        if (outDir != null) {
            try {
                val uri = Uri.parse(
                    "content://com.android.externalstorage.documents/document/primary%3A" +
                            Uri.encode("Android/data/$packageName/files/crawler-out")
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "vnd.android.document/directory")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(intent)
                Toast.makeText(this, getString(R.string.crawler_exported, outDir.absolutePath), Toast.LENGTH_LONG).show()
                return
            } catch (e: Exception) {
                // 没有文件管理器就退化为弹窗
            }
        }
        ActivityUtils.showTextDialog(
            this,
            R.string.crawler_open_dir,
            "已保存内容位于：\n${CrawlerStorage.rootDir(this).absolutePath}\n\n" +
                    (outDir?.let { "已导出可浏览副本：\n${it.absolutePath}\n\n" } ?: "") +
                    CrawlerStorage.describe(this)
        )
    }

    /** 把爬取结果导出到外部可浏览目录 */
    private fun exportToExternal(): File? {
        return try {
            val base = getExternalFilesDir(null) ?: return null
            val out = File(base, "crawler-out")
            if (!out.exists()) out.mkdirs()
            val src = CrawlerStorage.rootDir(this)
            src.copyRecursively(out, overwrite = true)
            out
        } catch (e: Exception) {
            null
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.refreshSavedCount()
    }

    // ------------------------------------------------------------------
    // 日志适配器
    // ------------------------------------------------------------------

    private inner class LogAdapter : RecyclerView.Adapter<LogAdapter.Holder>() {
        private var data: List<CrawlerViewModel.LogLine> = emptyList()

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val time: TextView = v.findViewById(R.id.time)
            val text: TextView = v.findViewById(R.id.text)
        }

        fun submit(list: List<CrawlerViewModel.LogLine>) {
            data = ArrayList(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_crawler_log, parent, false)
            return Holder(v)
        }

        override fun getItemCount(): Int = data.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val line = data[position]
            holder.time.text = line.time
            holder.text.text = line.text
            val color = when (line.level) {
                CrawlerViewModel.LogLine.Level.OK -> 0xFF2E7D32.toInt()
                CrawlerViewModel.LogLine.Level.WARN -> 0xFFE65100.toInt()
                CrawlerViewModel.LogLine.Level.ERROR -> 0xFFC62828.toInt()
                else -> resolveTextPrimary()
            }
            holder.text.setTextColor(color)
        }

        private fun resolveTextPrimary(): Int {
            val tv = android.util.TypedValue()
            theme.resolveAttribute(R.attr.textColorPrimary, tv, true)
            return tv.data
        }
    }
}
