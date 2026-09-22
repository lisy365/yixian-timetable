package com.stupidtree.hitax.ui.task

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.lifecycle.MutableLiveData
import com.stupidtree.component.data.DataState
import com.stupidtree.hitax.R
import com.stupidtree.hitax.data.model.timetable.EventItem
import com.stupidtree.hitax.data.repository.TaskRepository
import com.stupidtree.hitax.databinding.DialogBottomAddTaskBinding
import com.stupidtree.hitax.utils.NotificationUtils
import com.stupidtree.hitax.utils.ReminderScheduler
import com.stupidtree.style.widgets.PopUpCheckableList
import com.stupidtree.style.widgets.TransparentModeledBottomSheetDialog
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 新建 / 编辑待办事项
 *
 * 「提前提醒」的选项是「距截止时间多少分钟」，与全局提醒设置配合：
 * 全局设置决定重复次数与间隔，单项设置决定从什么时候开始提醒。
 */
class PopUpAddTask :
    TransparentModeledBottomSheetDialog<TaskManagerViewModel, DialogBottomAddTaskBinding>() {

    /** <0 表示使用全局默认提前量 */
    private var customLeadMinutes: Int = ReminderScheduler.USE_GLOBAL_LEAD
    private var onSavedListener: (() -> Unit)? = null

    private val noRemind = ReminderScheduler.NO_REMIND
    private val useGlobal = ReminderScheduler.USE_GLOBAL_LEAD

    override fun getViewModelClass(): Class<TaskManagerViewModel> =
        TaskManagerViewModel::class.java

    override fun getLayoutId(): Int = R.layout.dialog_bottom_add_task

    override fun initViewBinding(v: View): DialogBottomAddTaskBinding =
        DialogBottomAddTaskBinding.bind(v)

    fun setTask(task: EventItem): PopUpAddTask {
        current = task
        return this
    }

    fun setOnSavedListener(l: () -> Unit): PopUpAddTask {
        onSavedListener = l
        return this
    }

    private var current: EventItem? = null
    private val resultLiveData = MutableLiveData<DataState<Boolean>>()

    override fun initViews(view: View) {
        val b = binding ?: return
        val task = current ?: EventItem.getTaskInstance("", System.currentTimeMillis())
        current = task
        customLeadMinutes = ReminderScheduler.getLeadOverride(
            requireActivity().application, task.id
        )

        b.title.setText(if (task.name.isBlank()) R.string.task_add_title else R.string.task_edit_title)
        b.cancel.setOnClickListener { dismiss() }
        b.name.setText(task.name)
        b.note.setText(task.note ?: "")
        renderDue()
        renderPriority()
        renderRemind()

        b.due.setOnClickListener { pickDateTime() }
        b.priority.setOnClickListener { pickPriority() }
        b.remind.setOnClickListener { pickRemind() }

        b.done.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            b.done.startAnimation()
            save()
        }

        resultLiveData.observe(this) { state ->
            if (state.state == DataState.STATE.SUCCESS) {
                val bitmap = com.stupidtree.hitax.utils.ImageUtils
                    .getResourceBitmap(requireContext(), R.drawable.ic_baseline_done_24)
                b.done.doneLoadingAnimation(getColorPrimary(), bitmap)
                Toast.makeText(requireContext(), R.string.task_saved, Toast.LENGTH_SHORT).show()
                onSavedListener?.invoke()
                b.done.postDelayed({ dismiss() }, 500)
            } else {
                b.done.revertAnimation()
                Toast.makeText(
                    requireContext(),
                    state.message ?: getString(R.string.task_save_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ------------------------------------------------------------------

    private fun renderDue() {
        val ts = current?.from?.time ?: System.currentTimeMillis()
        binding?.due?.text = SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(ts)
    }

    private fun renderPriority() {
        val level = TaskManagerViewModel.PRIORITY.of(current?.fromNumber ?: 0)
        binding?.priority?.text = when (level) {
            TaskManagerViewModel.PRIORITY.URGENT -> getString(R.string.task_priority_urgent)
            TaskManagerViewModel.PRIORITY.IMPORTANT -> getString(R.string.task_priority_important)
            TaskManagerViewModel.PRIORITY.NORMAL -> getString(R.string.task_priority_normal)
        }
    }

    private fun renderRemind() {
        binding?.remind?.text = when {
            customLeadMinutes == noRemind -> getString(R.string.task_remind_none)
            customLeadMinutes == useGlobal -> getString(R.string.task_remind_default)
            customLeadMinutes == 0 -> getString(R.string.notify_now)
            customLeadMinutes >= 60 && customLeadMinutes % 60 == 0 ->
                getString(R.string.notify_hours, customLeadMinutes / 60)
            customLeadMinutes > 60 ->
                getString(R.string.notify_hours_minutes, customLeadMinutes / 60, customLeadMinutes % 60)
            else -> getString(R.string.notify_minutes, customLeadMinutes)
        }
    }

    private fun pickDateTime() {
        val c = Calendar.getInstance().apply { timeInMillis = current?.from?.time ?: timeInMillis }
        DatePickerDialog(
            requireContext(),
            { _, y, m, d ->
                val c2 = Calendar.getInstance().apply {
                    timeInMillis = current?.from?.time ?: timeInMillis
                    set(Calendar.YEAR, y)
                    set(Calendar.MONTH, m)
                    set(Calendar.DAY_OF_MONTH, d)
                }
                TimePickerDialog(
                    requireContext(),
                    { v, h, min ->
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        c2.set(Calendar.HOUR_OF_DAY, h)
                        c2.set(Calendar.MINUTE, min)
                        c2.set(Calendar.SECOND, 0)
                        c2.set(Calendar.MILLISECOND, 0)
                        applyDue(c2.timeInMillis)
                    }, c2.get(Calendar.HOUR_OF_DAY), c2.get(Calendar.MINUTE), true
                ).show()
            },
            c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun applyDue(ts: Long) {
        current?.let {
            it.from = java.sql.Timestamp(ts)
            it.to = java.sql.Timestamp(ts)
        }
        renderDue()
    }

    private fun pickPriority() {
        val names = listOf(
            getString(R.string.task_priority_normal),
            getString(R.string.task_priority_important),
            getString(R.string.task_priority_urgent)
        )
        val values = listOf(
            TaskManagerViewModel.PRIORITY.NORMAL,
            TaskManagerViewModel.PRIORITY.IMPORTANT,
            TaskManagerViewModel.PRIORITY.URGENT
        )
        PopUpCheckableList<TaskManagerViewModel.PRIORITY>()
            .setListData(names, values)
            .setTitle(getString(R.string.task_label_priority))
            .setOnConfirmListener(object :
                PopUpCheckableList.OnConfirmListener<TaskManagerViewModel.PRIORITY> {
                override fun OnConfirm(title: String?, key: TaskManagerViewModel.PRIORITY) {
                    current?.fromNumber = key.level
                    renderPriority()
                }
            }).show(parentFragmentManager, "priority")
    }

    private fun pickRemind() {
        val labels = mutableListOf<String>()
        val values = mutableListOf<Int>()
        labels.add(getString(R.string.task_remind_default))
        values.add(useGlobal)
        labels.add(getString(R.string.task_remind_none))
        values.add(noRemind)
        labels.add(getString(R.string.notify_now))
        values.add(0)
        for (m in intArrayOf(5, 10, 15, 30, 60, 120, 1440)) {
            labels.add(
                if (m >= 60 && m % 60 == 0) getString(R.string.notify_hours, m / 60)
                else getString(R.string.notify_minutes, m)
            )
            values.add(m)
        }
        PopUpCheckableList<Int>()
            .setListData(labels, values)
            .setTitle(getString(R.string.task_label_remind))
            .setOnConfirmListener(object : PopUpCheckableList.OnConfirmListener<Int> {
                override fun OnConfirm(title: String?, key: Int) {
                    customLeadMinutes = key
                    renderRemind()
                }
            }).show(parentFragmentManager, "remind")
    }

    private fun save() {
        val task = current ?: return
        val name = binding?.name?.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            Toast.makeText(requireContext(), R.string.task_name_required, Toast.LENGTH_SHORT).show()
            return
        }
        task.name = name
        task.note = binding?.note?.text?.toString()?.trim()?.ifEmpty { null }
        if (task.from.time <= 0L) applyDue(System.currentTimeMillis())
        task.to = java.sql.Timestamp(task.from.time)
        if (task.id.isEmpty()) task.id = java.util.UUID.randomUUID().toString()

        val app = requireActivity().application
        // 单项提前量（未设置则跟随全局）
        ReminderScheduler.setLeadOverride(app, task.id, customLeadMinutes)
        TaskRepository.getInstance(app).saveTask(task, resultLiveData)
    }
}
