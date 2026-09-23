package com.stupidtree.hitax.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import com.stupidtree.hitax.R
import com.stupidtree.hitax.databinding.DialogBottomNotificationSettingsBinding
import com.stupidtree.hitax.data.source.preference.NotificationPreferenceSource
import com.stupidtree.hitax.utils.NotificationUtils
import com.stupidtree.style.widgets.PopUpCheckableList
import com.stupidtree.style.widgets.TransparentModeledBottomSheetDialog

/**
 * 通知提醒设置面板
 *
 * 所有项即时生效并立即重排提醒；底部「发送测试通知」可直接验证通知链路。
 */
class FragmentNotificationSettings :
    TransparentModeledBottomSheetDialog<NotificationSettingsViewModel, DialogBottomNotificationSettingsBinding>() {

    private var bindingViews: DialogBottomNotificationSettingsBinding? = null

    override fun getViewModelClass(): Class<NotificationSettingsViewModel> =
        NotificationSettingsViewModel::class.java

    override fun getLayoutId(): Int = R.layout.dialog_bottom_notification_settings

    override fun initViewBinding(v: View): DialogBottomNotificationSettingsBinding =
        DialogBottomNotificationSettingsBinding.bind(v)

    override fun initViews(view: View) {
        val b = binding ?: return
        bindingViews = b

        // 开关
        b.enable.setOnCheckedChangeListener { _, v -> viewModel.setEnabled(v) }
        b.classEnable.setOnCheckedChangeListener { _, v -> viewModel.setClassEnabled(v) }
        b.eventEnable.setOnCheckedChangeListener { _, v -> viewModel.setEventEnabled(v) }
        b.ddlEnable.setOnCheckedChangeListener { _, v -> viewModel.setDdlEnabled(v) }
        b.onlySchoolDays.setOnCheckedChangeListener { _, v -> viewModel.setOnlySchoolDays(v) }
        b.sound.setOnCheckedChangeListener { _, v -> viewModel.setSoundEnabled(v) }
        b.vibrate.setOnCheckedChangeListener { _, v -> viewModel.setVibrateEnabled(v) }

        // 提前量
        b.leadClass.setOnClickListener {
            pickNumber(
                getString(R.string.notify_lead_class),
                NotificationPreferenceSource.LEAD_OPTIONS,
                viewModel.leadMinutes
            ) { viewModel.setLeadMinutes(it) }
        }
        b.leadDdl.setOnClickListener {
            pickNumber(
                getString(R.string.notify_lead_ddl),
                NotificationPreferenceSource.LEAD_OPTIONS,
                viewModel.ddlLeadMinutes
            ) { viewModel.setDdlLeadMinutes(it) }
        }
        // 重复次数
        b.repeat.setOnClickListener {
            val options = NotificationPreferenceSource.REPEAT_OPTIONS
            val labels = options.map {
                if (it == 0) getString(R.string.notify_repeat_once)
                else getString(R.string.notify_repeat_times, it + 1)
            }
            PopUpCheckableList<Int>()
                .setListData(labels, options.toList())
                .setTitle(getString(R.string.notify_repeat))
                .setOnConfirmListener(object : PopUpCheckableList.OnConfirmListener<Int> {
                    override fun OnConfirm(title: String?, key: Int) {
                        viewModel.setRepeatCount(key)
                    }
                }).show(parentFragmentManager, "repeat")
        }
        // 重复间隔
        b.interval.setOnClickListener {
            pickNumber(
                getString(R.string.notify_interval),
                NotificationPreferenceSource.INTERVAL_OPTIONS,
                viewModel.repeatInterval
            ) { viewModel.setRepeatInterval(it) }
        }

        // 模板（失焦时保存）
        b.templateTitle.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveTemplates()
        }
        b.templateContent.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveTemplates()
        }
        b.templateDdlTitle.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveTemplates()
        }
        b.templateDdlContent.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveTemplates()
        }

        b.reset.setOnClickListener {
            viewModel.reset()
            render()
            Toast.makeText(requireContext(), R.string.notify_reset_done, Toast.LENGTH_SHORT).show()
        }
        b.test.setOnClickListener {
            if (!checkNotificationPermission()) return@setOnClickListener
            viewModel.sendTestNotification()
            Toast.makeText(requireContext(), R.string.notify_test, Toast.LENGTH_SHORT).show()
        }
        b.permissionHint.setOnClickListener {
            openNotificationSettings()
        }

        // ---- 后台保活与系统权限（v1.0.4）----
        b.keepAlive.setOnCheckedChangeListener { _, v -> viewModel.setKeepAlive(v) }
        b.exactHint.setOnClickListener { openExactAlarmSettings() }
        b.batteryHint.setOnClickListener { openBatteryOptimizationSettings() }

        viewModel.refreshTrigger.observe(this) { render() }
        render()
    }

    private fun pickNumber(title: String, options: IntArray, current: Int, onPick: (Int) -> Unit) {
        val labels = options.map { NotificationUtils.formatMinutes(requireContext(), it) }
        PopUpCheckableList<Int>()
            .setListData(labels, options.toList())
            .setTitle(title)
            .setOnConfirmListener(object : PopUpCheckableList.OnConfirmListener<Int> {
                override fun OnConfirm(title: String?, key: Int) {
                    onPick(key)
                }
            }).show(parentFragmentManager, "pick_$title")
    }

    private fun render() {
        val b = bindingViews ?: return
        b.enable.isChecked = viewModel.isEnabled
        b.classEnable.isChecked = viewModel.classEnabled
        b.eventEnable.isChecked = viewModel.eventEnabled
        b.ddlEnable.isChecked = viewModel.ddlEnabled
        b.onlySchoolDays.isChecked = viewModel.onlySchoolDays
        b.sound.isChecked = viewModel.soundEnabled
        b.vibrate.isChecked = viewModel.vibrateEnabled
        b.leadClass.text = NotificationUtils.formatMinutes(requireContext(), viewModel.leadMinutes)
        b.leadDdl.text = NotificationUtils.formatMinutes(requireContext(), viewModel.ddlLeadMinutes)
        b.repeat.text = if (viewModel.repeatCount == 0) {
            getString(R.string.notify_repeat_once)
        } else {
            getString(R.string.notify_repeat_times, viewModel.repeatCount + 1)
        }
        b.interval.text = getString(R.string.notify_interval_value, viewModel.repeatInterval)
        if (b.templateTitle.text?.toString() != viewModel.titleTemplate)
            b.templateTitle.setText(viewModel.titleTemplate)
        if (b.templateContent.text?.toString() != viewModel.contentTemplate)
            b.templateContent.setText(viewModel.contentTemplate)
        if (b.templateDdlTitle.text?.toString() != viewModel.ddlTitleTemplate)
            b.templateDdlTitle.setText(viewModel.ddlTitleTemplate)
        if (b.templateDdlContent.text?.toString() != viewModel.ddlContentTemplate)
            b.templateDdlContent.setText(viewModel.ddlContentTemplate)

        val visible = if (viewModel.isEnabled) View.VISIBLE else View.GONE
        b.groupClass.visibility = visible
        b.permissionHint.visibility =
            if (isNotificationEnabled()) View.GONE else View.VISIBLE

        // 后台保活 / 精确闹钟权限状态
        b.keepAlive.isChecked = viewModel.keepAlive
        val exactOk = viewModel.canScheduleExact()
        b.exactHint.text = getString(
            if (exactOk) R.string.notify_exact_hint_on else R.string.notify_exact_hint_off
        )
        // Android 12 以下没有「闹钟与提醒」这个开关，隐藏该行
        b.exactHint.visibility = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            View.VISIBLE
        } else {
            View.GONE
        }
        b.batteryHint.visibility =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) View.VISIBLE else View.GONE
    }

    private fun saveTemplates() {
        val b = bindingViews ?: return
        viewModel.setTemplate(
            b.templateTitle.text?.toString().orEmpty(),
            b.templateContent.text?.toString().orEmpty()
        )
        viewModel.setDdlTemplate(
            b.templateDdlTitle.text?.toString().orEmpty(),
            b.templateDdlContent.text?.toString().orEmpty()
        )
    }

    private fun isNotificationEnabled(): Boolean {
        return NotificationManagerCompat.from(requireContext()).areNotificationsEnabled()
    }

    /** 未开启通知权限时提示并跳转系统设置 */
    private fun checkNotificationPermission(): Boolean {
        if (isNotificationEnabled()) return true
        openNotificationSettings()
        return false
    }

    private fun openNotificationSettings() {
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", requireContext().packageName, null))
            }
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 跳转「闹钟与提醒」授权页（Android 12+）。
     *
     * 没拿到这个权限时 [android.app.AlarmManager] 只能排不精确闹钟，
     * 提醒可能被系统推迟几分钟到几十分钟 —— 这是「通知不准时」最常见的系统层原因。
     */
    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.fromParts("package", requireContext().packageName, null)
            })
        } catch (e: Exception) {
            // 部分 ROM 没有这个页面，退回到应用详情页
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", requireContext().packageName, null))
                )
            } catch (e2: Exception) {
                e2.printStackTrace()
            }
        }
    }

    /** 跳转电池优化白名单设置页 */
    private fun openBatteryOptimizationSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: Exception) {
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", requireContext().packageName, null))
                )
            } catch (e2: Exception) {
                e2.printStackTrace()
            }
        }
    }
}
