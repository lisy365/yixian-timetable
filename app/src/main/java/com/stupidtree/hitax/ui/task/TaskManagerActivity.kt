package com.stupidtree.hitax.ui.task

import android.content.Context
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.viewbinding.ViewBinding
import com.google.android.material.tabs.TabLayout
import com.stupidtree.component.data.DataState
import com.stupidtree.hitax.R
import com.stupidtree.hitax.data.model.timetable.EventItem
import com.stupidtree.hitax.databinding.ActivityTaskManagerBinding
import com.stupidtree.hitax.databinding.ActivityTaskManagerItemBinding
import com.stupidtree.hitax.utils.TextTools
import com.stupidtree.hitax.utils.TimeTools
import com.stupidtree.style.base.BaseActivity
import com.stupidtree.style.base.BaseListAdapter
import com.stupidtree.style.widgets.PopUpText
import androidx.recyclerview.widget.LinearLayoutManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 待办事项管理页
 *
 * 与课表 / 日程同属一套时间体系：待办以 events 表存储，因此也会出现在「今日」时间轴上。
 */
class TaskManagerActivity :
    BaseActivity<TaskManagerViewModel, ActivityTaskManagerBinding>() {

    private lateinit var adapter: TaskAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setToolbarActionBack(binding.toolbar)
    }

    override fun initViewBinding(): ActivityTaskManagerBinding =
        ActivityTaskManagerBinding.inflate(layoutInflater)

    override fun getViewModelClass(): Class<TaskManagerViewModel> =
        TaskManagerViewModel::class.java

    override fun initViews() {
        adapter = TaskAdapter(this, mutableListOf())
        adapter.setOnItemClickListener(object : BaseListAdapter.OnItemClickListener<EventItem> {
            override fun onItemClick(data: EventItem?, card: View?, position: Int) {
                data?.let { showEdit(it) }
            }
        })
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        val titles = listOf(
            getString(R.string.task_filter_all),
            getString(R.string.task_filter_pending),
            getString(R.string.task_filter_finished)
        )
        for (t in titles) binding.tabs.addTab(binding.tabs.newTab().setText(t))
        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
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

        binding.fab.setOnClickListener { showEdit(viewModel.createNewTask()) }
        binding.sort.setOnClickListener {
            viewModel.toggleSort()
            Toast.makeText(
                this,
                if (viewModel.sortByDue) R.string.task_sort_by_due else R.string.task_sort_by_priority,
                Toast.LENGTH_SHORT
            ).show()
        }
        binding.clearFinished.setOnClickListener {
            PopUpText().setTitle(R.string.task_clear_finished_confirm)
                .setOnConfirmListener(object : PopUpText.OnConfirmListener {
                    override fun OnConfirm() {
                        viewModel.clearFinished()
                    }
                }).show(supportFragmentManager, "clear")
        }

        viewModel.tasksLiveData.observe(this) { state ->
            if (state.state == DataState.STATE.SUCCESS) {
                val list = state.data ?: emptyList()
                adapter.notifyItemChangedSmooth(list)
                binding.empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showEdit(task: EventItem) {
        PopUpAddTask().setTask(task).setOnSavedListener {
            // LiveData 会自动刷新，这里只需要重建提醒（已在新仓库内处理）
        }.show(supportFragmentManager, "add_task")
    }

    // ------------------------------------------------------------------

    private inner class TaskAdapter(
        context: Context,
        beans: MutableList<EventItem>
    ) : BaseListAdapter<EventItem, TaskAdapter.Holder>(context, beans) {

        inner class Holder(val binding: ActivityTaskManagerItemBinding) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root)

        override fun getViewBinding(parent: ViewGroup, viewType: Int): ViewBinding =
            ActivityTaskManagerItemBinding.inflate(mInflater, parent, false)

        override fun createViewHolder(viewBinding: ViewBinding, viewType: Int): Holder {
            val b = viewBinding as ActivityTaskManagerItemBinding
            val h = Holder(b)
            b.card.setOnClickListener {
                val pos = h.adapterPosition
                if (pos in mBeans.indices) mOnItemClickListener?.onItemClick(mBeans[pos], b.card, pos)
            }
            b.check.setOnClickListener {
                val pos = h.adapterPosition
                if (pos in mBeans.indices) {
                    viewModel.toggleDone(mBeans[pos], b.check.isChecked)
                }
            }
            b.more.setOnClickListener { showMore(h) }
            return h
        }

        private fun showMore(h: Holder) {
            val pos = h.adapterPosition
            if (pos !in mBeans.indices) return
            val task = mBeans[pos]
            val actions = mutableListOf<String>()
            actions.add(getString(R.string.task_action_edit))
            actions.add(
                getString(if (task.done) R.string.task_action_undone else R.string.task_action_done)
            )
            actions.add(getString(R.string.task_action_delete))
            com.stupidtree.style.widgets.PopUpCheckableList<String>()
                .setListData(actions, actions)
                .setTitle(task.name)
                .setOnConfirmListener(object :
                    com.stupidtree.style.widgets.PopUpCheckableList.OnConfirmListener<String> {
                    override fun OnConfirm(title: String?, key: String) {
                        when (key) {
                            actions[0] -> showEdit(task)
                            actions[1] -> viewModel.toggleDone(task, !task.done)
                            else -> viewModel.delete(task)
                        }
                    }
                }).show(supportFragmentManager, "task_action")
        }

        override fun bindHolder(holder: Holder, data: EventItem?, position: Int) {
            val b = holder.binding
            val task = data ?: return
            b.check.setOnCheckedChangeListener(null)
            b.check.isChecked = task.done
            b.name.text = task.name
            if (task.done) {
                b.name.paintFlags = b.name.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                b.name.alpha = 0.5f
                b.card.alpha = 0.6f
            } else {
                b.name.paintFlags = b.name.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                b.name.alpha = 1f
                b.card.alpha = 1f
            }
            b.due.text = getString(R.string.task_due_format, formatDue(task.from.time))
            if (task.note.isNullOrBlank()) {
                b.note.visibility = View.GONE
            } else {
                b.note.visibility = View.VISIBLE
                b.note.text = task.note
            }
            // 优先级色条
            val level = TaskManagerViewModel.PRIORITY.of(task.fromNumber)
            val color = when (level) {
                TaskManagerViewModel.PRIORITY.URGENT -> 0xFFE53935.toInt()
                TaskManagerViewModel.PRIORITY.IMPORTANT -> 0xFFFF9800.toInt()
                TaskManagerViewModel.PRIORITY.NORMAL -> getColorPrimary()
            }
            androidx.core.view.ViewCompat.setBackgroundTintList(
                b.priorityBar, android.content.res.ColorStateList.valueOf(color)
            )
            b.priorityBar.visibility =
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
