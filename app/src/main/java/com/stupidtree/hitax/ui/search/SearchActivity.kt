package com.stupidtree.hitax.ui.search

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewbinding.ViewBinding
import com.stupidtree.component.data.DataState
import com.stupidtree.hitax.R
import com.stupidtree.hitax.data.model.timetable.TermSubject
import com.stupidtree.hitax.databinding.ActivitySearchBinding
import com.stupidtree.hitax.databinding.DynamicSearchResultCardBinding
import com.stupidtree.hitax.utils.ActivityUtils
import com.stupidtree.style.base.BaseActivity
import com.stupidtree.style.base.BaseListAdapter

/**
 * 本地课程搜索页（逸仙课表）
 *
 * 布局沿用原项目的搜索页（顶部搜索条 + 列表），
 * 但数据源换成本地课表：按课程名 / 任课教师 / 上课地点检索。
 */
class SearchActivity : BaseActivity<LocalSearchViewModel, ActivitySearchBinding>() {

    private lateinit var adapter: ResultAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setToolbarActionBack(binding.toolbar)
    }

    override fun initViewBinding(): ActivitySearchBinding {
        return ActivitySearchBinding.inflate(layoutInflater)
    }

    override fun getViewModelClass(): Class<LocalSearchViewModel> {
        return LocalSearchViewModel::class.java
    }

    override fun initViews() {
        binding.toolbar.title = ""

        adapter = ResultAdapter(this, mutableListOf())
        adapter.setOnItemClickListener(object : BaseListAdapter.OnItemClickListener<TermSubject> {
            override fun onItemClick(data: TermSubject?, card: View?, position: Int) {
                data?.let { ActivityUtils.startSubjectActivity(getThis(), it.id) }
            }
        })
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.list.visibility = View.VISIBLE
        binding.pager.visibility = View.GONE
        binding.tabs.visibility = View.GONE
        binding.searchview.setHint(R.string.sysu_search_hint)

        binding.searchview.setOnEditorActionListener(object : TextView.OnEditorActionListener {
            override fun onEditorAction(textView: TextView, i: Int, keyEvent: KeyEvent?): Boolean {
                if (i == EditorInfo.IME_ACTION_GO || i == EditorInfo.IME_ACTION_SEARCH) {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
                    imm?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
                    binding.loading.visibility = View.VISIBLE
                    viewModel.search(binding.searchview.text.toString())
                    return true
                }
                return false
            }
        })

        // 传入关键词时直接搜索
        intent.getStringExtra("keyword")?.let {
            binding.searchview.setText(it)
            binding.loading.visibility = View.VISIBLE
            viewModel.search(it)
        }
        binding.searchview.requestFocus()

        viewModel.results.observe(this) { state ->
            binding.loading.visibility = View.GONE
            if (state.state == DataState.STATE.SUCCESS) {
                binding.list.visibility = View.VISIBLE
                adapter.notifyDataSetChanged(state.data ?: emptyList())
            }
        }
    }

    /** 搜索结果列表项 */
    private class ResultAdapter(
        context: Context,
        beans: MutableList<TermSubject>
    ) : BaseListAdapter<TermSubject, ResultAdapter.Holder>(context, beans) {

        class Holder(val binding: DynamicSearchResultCardBinding) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root)

        override fun getViewBinding(parent: ViewGroup, viewType: Int): ViewBinding {
            return DynamicSearchResultCardBinding.inflate(mInflater, parent, false)
        }

        override fun createViewHolder(viewBinding: ViewBinding, viewType: Int): Holder {
            val b = viewBinding as DynamicSearchResultCardBinding
            val h = Holder(b)
            b.root.setOnClickListener {
                val pos = h.adapterPosition
                if (pos >= 0 && pos < mBeans.size) {
                    mOnItemClickListener?.onItemClick(mBeans[pos], b.root, pos)
                }
            }
            return h
        }

        override fun bindHolder(holder: Holder, data: TermSubject?, position: Int) {
            holder.binding.name.text = data?.name ?: ""
            val hint = (mContext as SearchActivity).viewModel.matchHints[data?.id]
            holder.binding.label.text = hint ?: ""
            holder.binding.label.visibility = if (hint.isNullOrEmpty()) View.GONE else View.VISIBLE
            val color = data?.color ?: ContextCompat.getColor(mContext, R.color.cruel_summer_primary)
            androidx.core.view.ViewCompat.setBackgroundTintList(
                holder.binding.colorDot,
                android.content.res.ColorStateList.valueOf(color)
            )
        }
    }
}
