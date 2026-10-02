package com.stupidtree.hitax.ui.crawler

import android.content.Intent
import android.net.Uri
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.stupidtree.hitax.R
import com.stupidtree.hitax.data.repository.CrawlerStorage
import com.stupidtree.hitax.data.source.web.sysu.SysuPublicCrawler
import com.stupidtree.hitax.databinding.FragmentCrawlerBinding
import com.stupidtree.hitax.utils.ActivityUtils
import com.stupidtree.style.base.BaseFragment
import com.stupidtree.style.widgets.PopUpText
import java.io.File

/**
 * 教务信息爬取界面（v1.0.7：改为「小工具」下的二级界面）
 *
 * 由 [CrawlerActivity]（独立页）与「小工具」板块的内嵌容器共用同一份实现，
 * 布局 [R.layout.fragment_crawler] 自带头部与顶栏，顶栏用 `fitsSystemWindows`
 * 避让状态栏 / 刘海。
 */
class CrawlerFragment : BaseFragment<CrawlerViewModel, FragmentCrawlerBinding>() {

    /** 允许外部指定要从哪个学期开始爬（导入课表后自动爬取时用） */
    private var termOverride: String? = null

    private lateinit var logAdapter: LogAdapter

    override fun getViewModelClass(): Class<CrawlerViewModel> = CrawlerViewModel::class.java

    override fun initViewBinding(): FragmentCrawlerBinding =
        FragmentCrawlerBinding.inflate(layoutInflater)

    override fun initViews(view: View) {
        val b = binding ?: return
        termOverride = arguments?.getString(ARG_TERM)
        termOverride?.let { viewModel.preferTerm(it) }

        logAdapter = LogAdapter()
        b.logList.layoutManager = LinearLayoutManager(requireContext())
        b.logList.adapter = logAdapter

        b.start.setOnClickListener {
            if (viewModel.isRunning) viewModel.stopCrawl() else viewModel.startCrawl()
        }
        b.clearLog.setOnClickListener {
            PopUpText().setTitle(R.string.crawler_confirm_clear)
                .setOnConfirmListener(object : PopUpText.OnConfirmListener {
                    override fun OnConfirm() {
                        viewModel.clearLogs()
                        toast(R.string.crawler_cleared)
                    }
                }).show(childFragmentManager, "clear_log")
        }
        b.openDir.setOnClickListener { openSavedFolder() }

        buildSearchShortcuts(b.searchBox)

        viewModel.logsLiveData.observe(this) { list ->
            logAdapter.submit(list)
            b.logEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            if (list.isNotEmpty()) b.logList.scrollToPosition(list.size - 1)
        }
        viewModel.progressLiveData.observe(this) { p ->
            b.progressBar.max = p.total.coerceAtLeast(1)
            b.progressBar.progress = p.index
            b.start.text = getString(
                if (p.running) R.string.crawler_stop else R.string.crawler_start
            )
            b.progressTitle.text = when {
                p.running -> "${getString(R.string.crawler_state_running)} " +
                        "${p.index}/${p.total} · ${p.title}"
                p.finished -> "${p.title} · " + getString(
                    R.string.crawler_summary, p.successCount, p.failedCount
                )
                else -> getString(R.string.crawler_state_idle)
            }
            b.progressSub.text = if (p.savedFiles > 0) {
                getString(R.string.crawler_saved_count, p.savedFiles)
            } else {
                getString(R.string.crawler_scope_hint)
            }
        }
        viewModel.savedCountLiveData.observe(this) {
            b.savedCount.text = getString(R.string.crawler_saved_count, it)
        }
        viewModel.toastLiveData.observe(this) { msg ->
            if (msg == null) return@observe
            if (msg == MSG_NOT_LOGGED_IN) {
                toast(R.string.crawler_not_logged_in)
            } else {
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            }
            viewModel.consumeToast()
        }
        viewModel.refreshSavedCount()

        // 导入课表后自动跳进来时，直接开跑
        if (arguments?.getBoolean(ARG_AUTO_START) == true) {
            b.root.postDelayed({ if (isAdded) viewModel.startCrawl() }, 300)
        }
    }

    private fun toast(res: Int) {
        Toast.makeText(requireContext(), res, Toast.LENGTH_LONG).show()
    }

    private fun buildSearchShortcuts(box: LinearLayout) {
        box.removeAllViews()
        for ((name, template, _) in SysuPublicCrawler.SEARCH_TEMPLATES) {
            val chip = TextView(requireContext()).apply {
                text = name
                textSize = 12f
                setTextColor(resolvePrimary())
                gravity = android.view.Gravity.CENTER
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setBackgroundResource(R.drawable.element_rounded_bar_grey_light_24)
                setOnClickListener { openUrl(template.replace("{}", Uri.encode("教学大纲"))) }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = dp(8)
            chip.layoutParams = lp
            box.addView(chip)
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), url, Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun resolvePrimary(): Int {
        val tv = TypedValue()
        requireContext().theme.resolveAttribute(R.attr.colorPrimary, tv, true)
        return if (tv.data != 0) tv.data else 0xFF00693E.toInt()
    }

    /**
     * 「查看已保存内容」：导出到 `Android/data/<pkg>/files/crawler-out/` 再尝试打开，
     * 打不开就弹窗给出路径（应用私有目录普通文件管理器看不到）。
     */
    private fun openSavedFolder() {
        val outDir = exportToExternal()
        if (outDir != null) {
            try {
                val uri = Uri.parse(
                    "content://com.android.externalstorage.documents/document/primary%3A" +
                            Uri.encode("Android/data/${requireContext().packageName}/files/crawler-out")
                )
                startActivity(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "vnd.android.document/directory")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                )
                Toast.makeText(
                    requireContext(),
                    getString(R.string.crawler_exported, outDir.absolutePath),
                    Toast.LENGTH_LONG
                ).show()
                return
            } catch (e: Exception) {
                // 没有文件管理器就退化为弹窗
            }
        }
        ActivityUtils.showTextDialog(
            requireActivity(),
            R.string.crawler_open_dir,
            "已保存内容位于：\n${CrawlerStorage.rootDir(requireContext()).absolutePath}\n\n" +
                    (outDir?.let { "已导出可浏览副本：\n${it.absolutePath}\n\n" } ?: "") +
                    CrawlerStorage.describe(requireContext())
        )
    }

    private fun exportToExternal(): File? {
        return try {
            val base = requireContext().getExternalFilesDir(null) ?: return null
            val out = File(base, "crawler-out")
            if (!out.exists()) out.mkdirs()
            CrawlerStorage.rootDir(requireContext()).copyRecursively(out, overwrite = true)
            out
        } catch (e: Exception) {
            null
        }
    }

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
            holder.text.setTextColor(
                when (line.level) {
                    CrawlerViewModel.LogLine.Level.OK -> 0xFF2E7D32.toInt()
                    CrawlerViewModel.LogLine.Level.WARN -> 0xFFE65100.toInt()
                    CrawlerViewModel.LogLine.Level.ERROR -> 0xFFC62828.toInt()
                    else -> resolvePrimary()
                }
            )
        }
    }

    companion object {
        private const val ARG_TERM = "term"
        private const val ARG_AUTO_START = "auto_start"
        internal const val MSG_NOT_LOGGED_IN = "NOT_LOGGED_IN"

        /** 导入课表后自动爬取用 */
        fun newInstance(termCode: String?, autoStart: Boolean): CrawlerFragment {
            val f = CrawlerFragment()
            f.arguments = android.os.Bundle().apply {
                putString(ARG_TERM, termCode)
                putBoolean(ARG_AUTO_START, autoStart)
            }
            return f
        }
    }
}
