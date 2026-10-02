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
 * 统一设置弹窗（v1.0.7 需求 6）
 *
 * 只是一个很薄的壳：真正的界面在 [SettingsPanelFragment]，
 * 这样同一份设置界面既能被「小工具」板块内嵌成二级界面，也能从「功能中心」以弹窗打开。
 *
 * 入口收敛说明：以前设置项散落在
 * ① 课表页标题栏的调色盘图标、② 功能中心的「通知提醒」卡片、③ 右上角主题按钮，
 * 现在统一到本弹窗（分组：外观 / 提醒 / 资料与数据 / 关于）；
 * 首页右上角按钮保留为快捷方式（单击开设置、长按循环昼夜模式）。
 */
class FragmentSettings : BottomSheetDialogFragment() {

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
            .setText(R.string.settings_title)
        view.findViewById<View>(R.id.close).setOnClickListener { dismissAllowingStateLoss() }
        if (childFragmentManager.findFragmentByTag(TAG_PANEL) == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.panel_container, SettingsPanelFragment(), TAG_PANEL)
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
        private const val TAG_PANEL = "settings_panel"
    }
}
