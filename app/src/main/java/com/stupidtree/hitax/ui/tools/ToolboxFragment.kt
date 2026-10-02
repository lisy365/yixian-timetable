package com.stupidtree.hitax.ui.tools

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.stupidtree.hitax.R
import com.stupidtree.hitax.databinding.FragmentToolboxBinding
import com.stupidtree.hitax.ui.task.TaskManagerActivity
import com.stupidtree.hitax.utils.ActivityUtils
import com.stupidtree.style.base.BaseFragment

/**
 * 「小工具」板块（v1.0.5 需求 4）
 *
 * 底部导航的第三个 Tab 由「待办」改成了「小工具」：
 * - 列表由 [ToolRegistry.ALL] 驱动，加工具只需改注册表；
 * - 点「待办事项」会在本页内嵌 [com.stupidtree.hitax.ui.task.FragmentTask]
 *   （带返回键回到列表），点其它工具则打开对应的 Activity / 弹窗。
 *
 * 这样做的好处：待办仍然是「一点即达」，同时不再独占一个 Tab，
 * 后续「课堂签到」「成绩分析」「自习计时」等工具可以直接往注册表里加。
 */
class ToolboxFragment : BaseFragment<ToolboxViewModel, FragmentToolboxBinding>() {

    private lateinit var adapter: ToolAdapter
    private var embeddedId: String? = null

    override fun getViewModelClass(): Class<ToolboxViewModel> = ToolboxViewModel::class.java

    override fun initViewBinding(): FragmentToolboxBinding =
        FragmentToolboxBinding.inflate(layoutInflater)

    override fun initViews(view: View) {
        val b = binding ?: return
        adapter = ToolAdapter()
        b.toolList.layoutManager = LinearLayoutManager(requireContext())
        b.toolList.adapter = adapter
        b.toolList.isNestedScrollingEnabled = false

        b.toolBack.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            closeEmbeddedTool()
        }
        // v1.0.7 需求 7：「小工具」页右上角的「+」是功能迁移前遗留的「新建待办」入口，
        // 待办已经不是独立 Tab 了，这里不再对外暴露该入口（统一从「待办事项」工具里新建）。
        b.toolAction.visibility = View.GONE
        viewModel.embeddedToolId.observe(this) { id ->
            renderEmbedded(id)
        }
    }

    /**
     * 宿主 Activity 切到本页时回调。
     *
     * [BaseFragment] 在 `onDestroyView` 里会把 `binding` 置空，
     * 而 `MainActivity` 的 ViewPager 会把本页常驻内存，所以这里必须先判空，
     * 避免在视图已销毁时操作 binding 造成 NPE。
     */
    fun onPageShown() {
        viewModel.refresh()
        binding ?: return
        // 内嵌工具在页面重新可见时同步一次（例如待办列表在别处被改过、
        // 或教学资料刚在爬取页新增）
        renderEmbedded(embeddedId)
        embeddedChild()?.let { child ->
            when (child) {
                is com.stupidtree.hitax.ui.resource.ResourceBrowserFragment -> child.onPageShown()
            }
        }
    }

    /** 当前内嵌的工具实例 */
    private fun embeddedChild(): Fragment? =
        childFragmentManager.findFragmentByTag(TAG_EMBEDDED)

    private fun openTool(item: ToolRegistry.ToolItem) {
        val host = activity ?: return
        if (item is ToolRegistry.Embedded) {
            viewModel.embeddedToolId.value = item.id
            return
        }
        val handled = try {
            item.open(requireContext(), host as androidx.fragment.app.FragmentActivity)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
        if (!handled) {
            viewModel.embeddedToolId.value = item.id
        }
    }

    private fun closeEmbeddedTool() {
        viewModel.embeddedToolId.value = null
    }

    private fun renderEmbedded(id: String?) {
        val b = binding ?: return
        embeddedId = id
        if (id == null) {
            b.toolHost.visibility = View.GONE
            b.toolListScroll.visibility = View.VISIBLE
            adapter.notifyDataSetChanged()
            return
        }
        val item = ToolRegistry.byId(id) ?: return
        b.toolHost.visibility = View.VISIBLE
        b.toolListScroll.visibility = View.GONE
        b.toolTitle.setText(item.nameRes)
        // 有「完整页面」的工具才显示右上角入口（待办 / 爬取 / 教学资料），
        // 其余工具一律隐藏 —— v1.0.7 需求 7 就是把遗留的「新建待办」加号收掉。
        val fullPage = ToolRegistry.fullPageOf(id)
        b.toolAction.visibility = if (fullPage != null) View.VISIBLE else View.GONE
        b.toolAction.text = getString(R.string.toolbox_full_page)
        b.toolAction.setOnClickListener {
            if (fullPage != null) ActivityUtils.startActivity(requireContext(), fullPage)
        }

        val fm = childFragmentManager
        val existing = fm.findFragmentByTag(TAG_EMBEDDED)
        if (existing != null && existing.javaClass.name == fragmentClassOf(item)) {
            return
        }
        // 只在视图已创建时做事务，避免 onPageShown() 在视图已销毁时被调用
        if (isAdded.not() || view == null) return
        val tx = fm.beginTransaction()
        existing?.let { tx.remove(it) }
        if (item is ToolRegistry.Embedded) {
            tx.replace(R.id.tool_container, item.factory(), TAG_EMBEDDED)
        }
        tx.commitAllowingStateLoss()
    }

    private fun fragmentClassOf(item: ToolRegistry.ToolItem): String? {
        return if (item is ToolRegistry.Embedded) item.factory().javaClass.name else null
    }

    /** 返回键：先关内嵌页面，交给 Activity 处理时返回 false */
    fun handleBackPressed(): Boolean {
        return if (embeddedId != null) {
            closeEmbeddedTool()
            true
        } else false
    }

    // ------------------------------------------------------------------
    // 适配器（卡片 + 分组标题，全部由 ToolRegistry 驱动）
    // ------------------------------------------------------------------

    private inner class ToolAdapter : RecyclerView.Adapter<ToolAdapter.Holder>() {

        /** 扁平化后的行：header 或 tool */
        private val rows: List<Row> = buildRows()

        private fun buildRows(): List<Row> {
            val out = ArrayList<Row>()
            for ((group, tools) in ToolRegistry.groups()) {
                out.add(Row.Header(group))
                for (t in tools) out.add(Row.Tool(t))
            }
            return out
        }

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val section: TextView = v.findViewById(R.id.section)
            val card: View = v.findViewById(R.id.card)
            val icon: ImageView = v.findViewById(R.id.icon)
            val name: TextView = v.findViewById(R.id.name)
            val desc: TextView = v.findViewById(R.id.desc)
        }

        override fun getItemCount(): Int = rows.size

        override fun getItemViewType(position: Int): Int =
            if (rows[position] is Row.Header) TYPE_HEADER else TYPE_TOOL

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_tool_card, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> {
                    holder.section.visibility = View.VISIBLE
                    holder.card.visibility = View.GONE
                    holder.section.setText(row.group.titleRes)
                }
                is Row.Tool -> {
                    holder.section.visibility = View.GONE
                    holder.card.visibility = View.VISIBLE
                    holder.icon.setImageResource(row.item.iconRes)
                    holder.name.setText(row.item.nameRes)
                    holder.desc.setText(row.item.descRes)
                    holder.card.alpha = if (embeddedId == row.item.id) 0.6f else 1f
                    holder.card.setOnClickListener { openTool(row.item) }
                }
            }
        }
    }

    private sealed class Row {
        class Header(val group: ToolRegistry.Group) : Row()
        class Tool(val item: ToolRegistry.ToolItem) : Row()
    }

    companion object {
        private const val TAG_EMBEDDED = "tool_embedded"
        private const val TYPE_HEADER = 0
        private const val TYPE_TOOL = 1
    }
}
