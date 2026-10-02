package com.stupidtree.hitax.ui.resource

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.view.ContextThemeWrapper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.tabs.TabLayout
import com.stupidtree.hitax.R
import com.stupidtree.hitax.data.repository.CrawlerRepository
import com.stupidtree.hitax.data.source.web.sysu.SysuCrawler
import com.stupidtree.hitax.data.source.web.sysu.SysuPublicCrawler
import com.stupidtree.hitax.databinding.FragmentResourceBrowserBinding
import com.stupidtree.style.ThemeTools
import com.stupidtree.style.base.BaseFragment

/**
 * 爬取资料浏览器（v1.0.7 需求 3）—— 纯 Fragment 版本
 *
 * 把爬到的**培养方案 / 教学大纲 / 任课教师 / 学院栏目**做成 App 内可读的列表：
 * - 顶部 TabLayout 切换分类；
 * - 卡片列表显示名称、来源时间、字数；
 * - 点开是阅读弹窗（可选中复制、可跳转来源）。
 *
 * 数据全部读本地 `files/crawler/`（[CrawlerRepository]），不联网。
 * 做成 Fragment 是为了既能被「小工具」板块内嵌，也能由 [ResourceBrowserActivity] 整页打开。
 */
class ResourceBrowserFragment :
    BaseFragment<ResourceBrowserViewModel, FragmentResourceBrowserBinding>() {

    private lateinit var adapter: ResourceAdapter

    /** Tab 顺序与分类的对应关系 */
    private val tabs: List<Pair<String, Array<String>>> = listOf(
        "培养方案" to arrayOf(SysuCrawler.CAT_PROFILE),
        "教学大纲" to arrayOf(SysuCrawler.CAT_SYLLABUS),
        "任课教师" to arrayOf(SysuCrawler.CAT_TEACHER),
        "学院栏目" to arrayOf(
            SysuPublicCrawler.CATEGORY_COLLEGE,
            SysuPublicCrawler.CATEGORY_SCHOOL
        )
    )

    private var currentIndex = 0

    override fun getViewModelClass(): Class<ResourceBrowserViewModel> =
        ResourceBrowserViewModel::class.java

    override fun initViewBinding(): FragmentResourceBrowserBinding =
        FragmentResourceBrowserBinding.inflate(layoutInflater)

    override fun initViews(view: View) {
        val b = binding ?: return
        // 内嵌形态下不显示自带顶栏（外层容器已经有标题）
        if (isEmbedded()) b.toolbar.visibility = View.GONE

        adapter = ResourceAdapter(mutableListOf()) { r -> openDetail(r) }
        b.list.layoutManager = LinearLayoutManager(requireContext())
        b.list.adapter = adapter

        for ((title, _) in tabs) b.tabs.addTab(b.tabs.newTab().setText(title))
        b.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentIndex = tab.position
                viewModel.load(categoriesOf(currentIndex))
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        b.refresh.setColorSchemeColors(getColorPrimary())
        b.refresh.setOnRefreshListener { viewModel.load(categoriesOf(currentIndex)) }

        viewModel.resourcesLiveData.observe(this) { list ->
            b.refresh.isRefreshing = false
            adapter.submit(list)
            b.empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
        viewModel.load(categoriesOf(0))
    }

    /** 从别处回到本页时重新读一次本地文件 */
    fun onPageShown() {
        binding ?: return
        viewModel.load(categoriesOf(currentIndex))
    }

    private fun isEmbedded(): Boolean =
        parentFragment is com.stupidtree.hitax.ui.tools.ToolboxFragment

    private fun categoriesOf(index: Int): Array<String> =
        tabs.getOrNull(index)?.second ?: arrayOf(SysuCrawler.CAT_PROFILE)

    private fun openDetail(r: CrawlerRepository.TeachingResource) {
        ResourceDetailSheet.newInstance(r).show(parentFragmentManager, "resource_detail")
    }

    // ------------------------------------------------------------------
    // 列表
    // ------------------------------------------------------------------

    private inner class ResourceAdapter(
        private val data: MutableList<CrawlerRepository.TeachingResource>,
        private val onClick: (CrawlerRepository.TeachingResource) -> Unit
    ) : RecyclerView.Adapter<ResourceAdapter.Holder>() {

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val card: View = v.findViewById(R.id.card)
            val name: TextView = v.findViewById(R.id.name)
            val meta: TextView = v.findViewById(R.id.meta)
        }

        fun submit(list: List<CrawlerRepository.TeachingResource>) {
            data.clear()
            data.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_resource_card, parent, false)
            return Holder(v)
        }

        override fun getItemCount(): Int = data.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = data[position]
            holder.name.text = item.name
            val bits = mutableListOf<String>()
            if (item.summary.isNotBlank()) bits.add(item.summary)
            bits.add("${item.chars} 字")
            if (item.savedAtText.isNotBlank()) bits.add(item.savedAtText)
            holder.meta.text = bits.joinToString(" · ")
            holder.card.setOnClickListener { onClick(item) }
        }
    }
}

/**
 * 资料阅读弹窗：全文文本（可选中）+ 复制 + 跳转来源
 */
class ResourceDetailSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_NAME = "name"
        private const val ARG_META = "meta"
        private const val ARG_BODY = "body"
        private const val ARG_URL = "url"

        fun newInstance(r: CrawlerRepository.TeachingResource): ResourceDetailSheet {
            val f = ResourceDetailSheet()
            f.arguments = Bundle().apply {
                putString(ARG_NAME, r.name)
                putString(
                    ARG_META,
                    listOf(r.categoryLabel, r.summary, "${r.chars} 字", r.savedAtText)
                        .filter { it.isNotBlank() }.joinToString(" · ")
                )
                putString(ARG_BODY, r.body)
                putString(ARG_URL, r.sourceUrl)
            }
            return f
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        retainInstance = true
        setStyle(STYLE_NORMAL, R.style.TransparentBottomSheetDialogTheme)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = ContextThemeWrapper(requireContext(), ThemeTools.themeOf(requireContext()))
        return LayoutInflater.from(ctx)
            .inflate(R.layout.dialog_bottom_resource_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val title = view.findViewById<TextView>(R.id.title)
        val meta = view.findViewById<TextView>(R.id.meta)
        val body = view.findViewById<TextView>(R.id.body)
        title.text = arguments?.getString(ARG_NAME).orEmpty()
        meta.text = arguments?.getString(ARG_META).orEmpty()
        body.text = arguments?.getString(ARG_BODY).orEmpty()
        view.findViewById<View>(R.id.close).setOnClickListener { dismissAllowingStateLoss() }

        view.findViewById<TextView>(R.id.copy).setOnClickListener {
            val cm = requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(title.text, body.text))
            Toast.makeText(requireContext(), R.string.resource_copied, Toast.LENGTH_SHORT).show()
        }

        val url = arguments?.getString(ARG_URL).orEmpty()
        val source = view.findViewById<TextView>(R.id.source)
        if (url.isBlank()) {
            source.visibility = View.GONE
        } else {
            source.setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), url, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // 阅读器给到较高高度，长文本才放得下
        val sheet = dialog?.findViewById<View>(R.id.design_bottom_sheet)
        sheet?.let {
            it.layoutParams = it.layoutParams.apply {
                height = (resources.displayMetrics.heightPixels * 0.85f).toInt()
            }
        }
    }
}
