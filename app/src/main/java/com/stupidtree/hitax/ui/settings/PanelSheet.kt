package com.stupidtree.hitax.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.fragment.app.Fragment
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.stupidtree.hitax.R
import com.stupidtree.style.ThemeTools

/**
 * 通用的「把某个面板包成底部弹窗」外壳（v1.0.7 需求 8）
 *
 * 为什么需要它：真正要展示的界面现在都写成了普通 [Fragment]
 * （[SettingsPanelFragment]、[NotificationSettingsPanelFragment]、
 * [com.stupidtree.hitax.ui.crawler.CrawlerFragment]、
 * [com.stupidtree.hitax.ui.resource.ResourceBrowserFragment]），
 * 这样它们既能被「小工具」板块内嵌成二级界面，也能被弹窗/整页复用。
 *
 * 本类只做一件事：建一个容器 -> 把面板放进去 -> 在 `onStart` 里把弹窗拉到全屏高度，
 * 让长列表有足够空间（默认 BottomSheet 的 peekHeight 会把内容压得很矮）。
 */
class PanelSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_TITLE = "title"
        private const val TAG_PANEL = "panel"

        /** 用一个工厂函数指定要展示的面板 */
        fun newInstance(titleRes: Int, factory: () -> Fragment): PanelSheet {
            val f = PanelSheet()
            f.arguments = Bundle().apply { putInt(ARG_TITLE, titleRes) }
            f.factory = factory
            return f
        }
    }

    private var factory: (() -> Fragment)? = null

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
        val root = LayoutInflater.from(ctx).inflate(R.layout.dialog_panel_host, container, false)
        (root.findViewById<TextView>(R.id.title)).setText(arguments?.getInt(ARG_TITLE) ?: R.string.settings_title)
        root.findViewById<View>(R.id.close).setOnClickListener { dismissAllowingStateLoss() }
        val box = root.findViewById<FrameLayout>(R.id.panel_container)
        if (childFragmentManager.findFragmentByTag(TAG_PANEL) == null) {
            val panel = factory?.invoke() ?: SettingsPanelFragment()
            childFragmentManager.beginTransaction()
                .replace(R.id.panel_container, panel, TAG_PANEL)
                .commitAllowingStateLoss()
        }
        return root
    }

    override fun onStart() {
        super.onStart()
        // 让弹窗占满宽度并给到较高的高度，长设置列表才放得下
        val sheet = dialog?.findViewById<View>(R.id.design_bottom_sheet)
        sheet?.let {
            it.layoutParams = it.layoutParams.apply {
                height = (resources.displayMetrics.heightPixels * 0.92f).toInt()
            }
        }
    }
}
