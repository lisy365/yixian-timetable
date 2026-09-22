package com.stupidtree.hitax.ui.main.timetable.panel

import android.annotation.SuppressLint
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.stupidtree.hitax.R
import com.stupidtree.hitax.data.model.timetable.TimeInDay
import com.stupidtree.hitax.data.source.preference.TimetableBackgroundSource
import com.stupidtree.hitax.databinding.FragmentTimetablePanelBinding
import com.stupidtree.hitax.ui.settings.FragmentNotificationSettings
import com.stupidtree.style.widgets.TransparentModeledBottomSheetDialog

class FragmentTimetablePanel :
    TransparentModeledBottomSheetDialog<TimetablePanelViewModel, FragmentTimetablePanelBinding>() {

    private var pickImageLauncher: ActivityResultLauncher<Intent>? = null

    override fun initViews(view: View) {
        bindLiveData()
        binding?.reset?.setOnClickListener {
            viewModel.startResetColor()
        }
        binding?.from?.setOnClickListener {
            viewModel.startDateLiveData.value?.let {
                val minute = it % 100
                val hour = it / 100
                TimePickerDialog(requireContext(), { v, hourOfDay, minute ->
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    viewModel.changeStartDate(hourOfDay, minute)
                }, hour, minute, true)
                    .show()
            }
        }
        binding?.drawbglines?.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setDrawBGLines(isChecked)
        }
        binding?.colorEnable?.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setColorEnable(isChecked)
        }
        binding?.fadeEnable?.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setFadeEnable(isChecked)
        }

        // ---------------- 课表背景 ----------------
        binding?.bgPick?.setOnClickListener {
            val timetable = currentTimetable
            if (timetable == null) {
                Toast.makeText(requireContext(), R.string.add_timetable_first, Toast.LENGTH_SHORT)
                    .show()
                return@setOnClickListener
            }
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
            try {
                pickImageLauncher?.launch(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        binding?.bgClear?.setOnClickListener {
            viewModel.clearBackground()
            notifyTimetableFragment()
            Toast.makeText(requireContext(), R.string.bg_cleared, Toast.LENGTH_SHORT).show()
        }
        binding?.bgOpacity?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                viewModel.setBackgroundOpacity(progress)
                renderBackgroundLabels()
                notifyTimetableFragment()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        binding?.bgDim?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                viewModel.setBackgroundDim(progress)
                renderBackgroundLabels()
                notifyTimetableFragment()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // ---------------- 一键统一科目颜色 ----------------
        binding?.unifyColor?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            val timetable = currentTimetable
            if (timetable == null) {
                Toast.makeText(requireContext(), R.string.add_timetable_first, Toast.LENGTH_SHORT)
                    .show()
                return@setOnClickListener
            }
            com.stupidtree.style.widgets.PopUpColorPicker()
                .initColor(unifyColor)
                .setOnColorSelectListener(object :
                    com.stupidtree.style.widgets.PopUpColorPicker.OnColorSelectedListener {
                    override fun onSelected(color: Int) {
                        unifyColor = color
                        renderUnifyColorDot()
                        com.stupidtree.hitax.data.repository.SubjectRepository
                            .getInstance(requireActivity().application)
                            .actionUnifySubjectColors(timetable.id, color)
                        Toast.makeText(
                            requireContext(),
                            R.string.unify_subject_color_done,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }).show(parentFragmentManager, "unify_color")
        }

        // ---------------- 通知提醒（已移至功能中心） ----------------

        viewModel.currentTimetableLiveData.observe(this) {
            currentTimetable = it
            viewModel.loadBackgroundInfo(it)
        }
        viewModel.startLoadTimetable()
    }

    /** 当前选中的统一颜色（默认取主题色） */
    private var unifyColor: Int = 0

    private fun renderUnifyColorDot() {
        val dot = binding?.unifyColorDot ?: return
        val color = if (unifyColor != 0) unifyColor else getColorPrimary()
        androidx.core.view.ViewCompat.setBackgroundTintList(
            dot, android.content.res.ColorStateList.valueOf(color)
        )
    }

    private var currentTimetable: com.stupidtree.hitax.data.model.timetable.Timetable? = null

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        // ActivityResult 必须在初始化阶段注册
        pickImageLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
            val uri: Uri? = result.data?.data
            if (uri == null) return@registerForActivityResult
            val timetable = currentTimetable ?: return@registerForActivityResult
            val ok = TimetableBackgroundSource.getInstance(requireContext())
                .saveFromUri(timetable.id, uri)
            if (ok) {
                Toast.makeText(requireContext(), R.string.bg_applied, Toast.LENGTH_SHORT).show()
                viewModel.loadBackgroundInfo(timetable)
                notifyTimetableFragment()
            } else {
                Toast.makeText(requireContext(), R.string.bg_pick_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    fun bindLiveData() {
        viewModel.drawBGLinesLiveData.observe(this) {
            binding?.drawbglines?.isChecked = it
        }
        viewModel.startDateLiveData.observe(this) {
            binding?.from?.text = TimeInDay(it / 100, it % 100).toString()
        }
        viewModel.colorEnableLiveData.observe(this) {
            binding?.colorEnable?.isChecked = it
        }
        viewModel.fadeEnableLiveData.observe(this) {
            binding?.fadeEnable?.isChecked = it
        }
        viewModel.backgroundInfoLiveData.observe(this) { info ->
            binding?.bgOptions?.visibility =
                if (info.hasImage) View.VISIBLE else View.GONE
            binding?.bgOpacity?.progress = info.opacity
            binding?.bgDim?.progress = info.dim
            renderBackgroundLabels()
        }
        renderUnifyColorDot()
    }

    private fun renderBackgroundLabels() {
        val info = viewModel.backgroundInfoLiveData.value ?: return
        binding?.bgOpacityLabel?.text =
            getString(R.string.bg_opacity) + "：" + info.opacity + "%"
        binding?.bgDimLabel?.text =
            getString(R.string.bg_dim) + "：" + info.dim + "%"
    }

    /** 通知宿主的课表页刷新背景图层 */
    private fun notifyTimetableFragment() {
        (activity as? com.stupidtree.hitax.ui.main.MainActivity)?.refreshTimetableBackground()
    }

    override fun getLayoutId(): Int {
        return R.layout.fragment_timetable_panel
    }

    override fun getViewModelClass(): Class<TimetablePanelViewModel> {
        return TimetablePanelViewModel::class.java
    }

    override fun initViewBinding(v: View): FragmentTimetablePanelBinding {
        return FragmentTimetablePanelBinding.bind(v)
    }
}
