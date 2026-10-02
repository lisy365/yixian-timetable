package com.stupidtree.hitax.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.view.ContextThemeWrapper
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.stupidtree.hitax.R
import com.stupidtree.style.ThemeTools

/**
 * 通知提醒设置弹窗（v1.0.7）
 *
 * 界面本体已抽到 [NotificationSettingsPanelFragment]（普通 Fragment），
 * 这样「设置 → 通知提醒」能用弹窗打开，「小工具」等容器也能内嵌同一份界面。
 */
class FragmentNotificationSettings : BottomSheetDialogFragment() {

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
            .inflate(R.layout.dialog_panel_host, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (view.findViewById<android.widget.TextView>(R.id.title))
            .setText(R.string.notify_settings_title)
        view.findViewById<View>(R.id.close).setOnClickListener { dismissAllowingStateLoss() }
        if (childFragmentManager.findFragmentByTag(TAG_PANEL) == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.panel_container, NotificationSettingsPanelFragment(), TAG_PANEL)
                .commitAllowingStateLoss()
        }
    }

    override fun onStart() {
        super.onStart()
        val sheet = dialog?.findViewById<View>(R.id.design_bottom_sheet)
        sheet?.let {
            it.layoutParams = it.layoutParams.apply {
                height = (resources.displayMetrics.heightPixels * 0.92f).toInt()
            }
        }
    }

    companion object {
        private const val TAG_PANEL = "notify_panel"
    }
}
