package com.stupidtree.hitax.ui.task

import android.content.Context
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewbinding.ViewBinding
import com.google.android.material.tabs.TabLayout
import com.stupidtree.component.data.DataState
import com.stupidtree.hitax.R
import com.stupidtree.hitax.data.model.timetable.EventItem
import com.stupidtree.hitax.databinding.ActivityTaskManagerItemBinding
import com.stupidtree.hitax.databinding.FragmentTaskBinding
import com.stupidtree.style.base.BaseFragment
import com.stupidtree.style.base.BaseListAdapter
import com.stupidtree.style.widgets.PopUpCheckableList
import com.stupidtree.style.widgets.PopUpText
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 待办事项（底部导航页）
 *
 * 与「待办管理页」共用同一套数据与交互，这里作为主页的一个 Tab 直接嵌入；
 * 列表项支持勾选完成、点击编辑、右侧「⋮」更多操作与长按。
 */
class FragmentTask : BaseFragment<TaskManagerViewModel, FragmentTaskBinding>() {

    private lateinit var adapter: TaskAdapter

    override fun getViewModelClass(): Class<TaskManagerViewModel> =
        TaskManagerViewModel::class.java

    override fun initViewBinding(): FragmentTaskBinding = FragmentTaskBinding.inflate(layoutInflater)

    override fun initViews(view: View) {
        val b = binding ?: return
        adapter = TaskAdapter(requireContext(), mutableListOf())
        adapter.setOnItemClickListener(object : BaseListAdapter.OnItemClickListener<EventItem> {
            override fun onItemClick(data: EventItem?, card: View?, position: Int) {
                data?.let { showEdit(it) }
            }
        })
        adapter.setOnItemLongClickListener(object : BaseListAdapter.OnItemLongClickListener<EventItem> {
            override fun onItemLongClick(data: EventItem?, view: View?, position: Int): Boolean {
                if (data == null || view == null) return false
                view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                showMore(data)
                return true
            }
        })
        b.list.layoutManager = LinearLayoutManager(requireContext())
        b.list.adapter = adapter

        val titles = listOf(
            getString(R.string.task_filter_all),
            getString(R.string.task_filter_pending),
            getString(R.string.task_filter_finished)
        )
        for (t in titles) b.tabs.addTab(b.tabs.newTab().setText(t))
        b.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                viewModel.setFilter(
                    when (tab.position) {
                        1 -> TaskManagerViewModel.FILTER.PENDING
                        2 -> TaskManagerViewModel.FILTER.FINISHED
                        else -> TaskManagerViewModel.FILTER.ALL
                    }
                )
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        b.fab.setOnClickListener { showEdit(viewModel.createNewTask()) }
        b.sort.setOnClickListener {
            viewModel.toggleSort()
            Toast.makeText(
                requireContext(),
                if (viewModel.sortByDue) R.string.task_sort_by_due else R.string.task_sort_by_priority,
                Toast.LENGTH_SHORT
            ).show()
        }
        b.clearFinished.setOnClickListener {
            PopUpText().setTitle(R.string.task_clear_finished_confirm)
                .setOnConfirmListener(object : PopUpText.OnConfirmListener {
                    override fun OnConfirm() {
                        viewModel.clearFinished()
                    }
                }).show(childFragmentManager, "clear")
        }

        viewModel.tasksLiveData.observe(viewLifecycleOwner) { state ->
            if (state.state == DataState.STATE.SUCCESS) {
                val list = state.data ?: emptyList()
                adapter.notifyItemChangedSmooth(list)
                b.empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showEdit(task: EventItem) {
        PopUpAddTask().setTask(task).show(childFragmentManager, "add_task")
    }

    private fun showMore(task: EventItem) {
        val actions = listOf(
            getString(R.string.task_action_edit),
            getString(if (task.done) R.string.task_action_undone else R.string.task_action_done),
            getString(R.string.task_action_delete)
        )
        PopUpCheckableList<Int>()
            .setListData(actions, listOf(0, 1, 2))
            .setTitle(task.name)
            .setOnConfirmListener(object : PopUpCheckableList.OnConfirmListener<Int> {
                override fun OnConfirm(title: String?, key: Int) {
                    when (key) {
                        0 -> showEdit(task)
                        1 -> viewModel.toggleDone(task, !task.done)
                        else -> viewModel.delete(task)
                    }
                }
            }).show(childFragmentManager, "task_action")
    }

    /** 列表项：与待办管理页保持同一视觉 */
    inner class TaskAdapter(
        context: Context,
        beans: MutableList<EventItem>
    ) : BaseListAdapter<EventItem, TaskAdapter.Holder>(context, beans) {

        inner class Holder(val binding: ActivityTaskManagerItemBinding) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root)

        override fun getViewBinding(parent: ViewGroup, viewType: Int): ViewBinding =
            ActivityTaskManagerItemBinding.inflate(mInflater, parent, false)

        override fun createViewHolder(viewBinding: ViewBinding, viewType: Int): Holder {
            val vb = viewBinding as ActivityTaskManagerItemBinding
            val h = Holder(vb)
            vb.card.setOnClickListener {
                val pos = h.adapterPosition
                if (pos in mBeans.indices) mOnItemClickListener?.onItemClick(mBeans[pos], vb.card, pos)
            }
            vb.card.setOnLongClickListener {
                val pos = h.adapterPosition
                if (pos in mBeans.indices) {
                    it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    showMore(mBeans[pos])
                }
                true
            }
            vb.check.setOnClickListener {
                val pos = h.adapterPosition
                if (pos in mBeans.indices) viewModel.toggleDone(mBeans[pos], vb.check.isChecked)
            }
            vb.more.setOnClickListener {
                val pos = h.adapterPosition
                if (pos in mBeans.indices) showMore(mBeans[pos])
            }
            return h
        }

        override fun bindHolder(holder: Holder, data: EventItem?, position: Int) {
            val vb = holder.binding
            val task = data ?: return
            vb.check.setOnCheckedChangeListener(null)
            vb.check.isChecked = task.done
            vb.name.text = task.name
            if (task.done) {
                vb.name.paintFlags = vb.name.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                vb.name.alpha = 0.5f
                vb.card.alpha = 0.6f
            } else {
                vb.name.paintFlags = vb.name.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                vb.name.alpha = 1f
                vb.card.alpha = 1f
            }
            vb.due.text = getString(R.string.task_due_format, formatDue(task.from.time))
            if (task.note.isNullOrBlank()) {
                vb.note.visibility = View.GONE
            } else {
                vb.note.visibility = View.VISIBLE
                vb.note.text = task.note
            }
            val level = TaskManagerViewModel.PRIORITY.of(task.fromNumber)
            val color = when (level) {
                TaskManagerViewModel.PRIORITY.URGENT -> 0xFFE53935.toInt()
                TaskManagerViewModel.PRIORITY.IMPORTANT -> 0xFFFF9800.toInt()
                TaskManagerViewModel.PRIORITY.NORMAL -> getColorPrimary()
            }
            androidx.core.view.ViewCompat.setBackgroundTintList(
                vb.priorityBar, android.content.res.ColorStateList.valueOf(color)
            )
            vb.priorityBar.visibility =
                if (level == TaskManagerViewModel.PRIORITY.NORMAL) View.GONE else View.VISIBLE
        }
    }

    private fun formatDue(ts: Long): String {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply { timeInMillis = ts }
        val sameDay = now.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
        val tomorrow = now.clone() as Calendar
        tomorrow.add(Calendar.DAY_OF_YEAR, 1)
        val isTomorrow = tomorrow.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
                tomorrow.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(target.time)
        return when {
            sameDay -> "${getString(R.string.today)} $time"
            isTomorrow -> "${getString(R.string.tomorrow)} $time"
            else -> SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(target.time)
        }
    }
}
