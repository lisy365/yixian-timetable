package com.stupidtree.hitax.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.stupidtree.hitax.R
import com.stupidtree.hitax.ui.about.ActivityAbout
import com.stupidtree.hitax.ui.about.UserAgreementDialog
import com.stupidtree.hitax.ui.crawler.CrawlerActivity
import com.stupidtree.hitax.ui.main.timetable.panel.FragmentTimetablePanel
import com.stupidtree.hitax.ui.tools.ToolRegistry
import com.stupidtree.hitax.utils.ActivityUtils
import com.stupidtree.hitax.utils.ThemePalette
import com.stupidtree.hitax.utils.ThemePaletteRegistry
import com.stupidtree.style.ThemeTools

/**
 * 统一设置面板（v1.0.7 需求 6）—— 纯 Fragment 版本
 *
 * 背景：设置项原来散落在三处 ——
 * 1. 课表页标题栏的调色盘图标（其实是课表样式）；
 * 2. 「功能中心」里的「通知提醒」卡片；
 * 3. 右上角主题切换按钮 + 长按循环。
 *
 * 现在收敛成**一个入口**：功能中心 →「设置」（也可从「小工具 → 设置」进）。
 * 面板按「外观 / 提醒 / 资料与数据 / 关于」分组，每行复用同一套 `settings_row_*` style，
 * 后续加设置项只写 id + 文案。
 *
 * 做成普通 Fragment 而不是 BottomSheetDialogFragment，是为了能直接被「小工具」
 * 板块当二级界面内嵌（[com.stupidtree.hitax.ui.tools.ToolboxFragment]）、
 * 也能被 [com.stupidtree.hitax.ui.settings.PanelSheet] 包成弹窗。
 */
class SettingsPanelFragment : Fragment() {

    /** 面板里用到的子工具，直接复用工具箱注册表，避免文案两边维护 */
    private fun tool(id: String) = ToolRegistry.byId(id)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_bottom_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 内嵌 / 弹窗两种形态下「关闭」按钮的含义不同
        view.findViewById<View>(R.id.close).apply {
            visibility = if (isEmbedded()) View.GONE else View.VISIBLE
            setOnClickListener { (activity as? androidx.fragment.app.FragmentActivity)?.onBackPressed() }
        }

        // ---- 外观 ----
        value(view, R.id.theme_value) { describeTheme() }
        view.findViewById<View>(R.id.row_theme).setOnClickListener {
            PopUpThemePicker().show(parentFragmentManager, "theme_picker")
        }
        view.findViewById<View>(R.id.row_timetable_style).setOnClickListener {
            FragmentTimetablePanel().show(parentFragmentManager, "timetable_panel")
        }

        // ---- 提醒 ----
        view.findViewById<View>(R.id.row_notification).setOnClickListener {
            FragmentNotificationSettings().show(parentFragmentManager, "notify_settings")
        }

        // ---- 资料与数据 ----
        value(view, R.id.resources_value) { describeResources() }
        view.findViewById<View>(R.id.row_resources).setOnClickListener {
            ActivityUtils.startActivity(requireContext(), com.stupidtree.hitax.ui.resource.ResourceBrowserActivity::class.java)
        }
        view.findViewById<View>(R.id.row_crawler).setOnClickListener {
            ActivityUtils.startActivity(requireContext(), CrawlerActivity::class.java)
        }
        view.findViewById<View>(R.id.row_timetable_manager).setOnClickListener {
            ActivityUtils.startTimetableManager(requireContext())
        }

        // ---- 关于 ----
        view.findViewById<View>(R.id.row_about).setOnClickListener {
            ActivityUtils.startActivity(requireContext(), ActivityAbout::class.java)
        }
        view.findViewById<View>(R.id.row_ua).setOnClickListener {
            UserAgreementDialog().show(parentFragmentManager, "ua")
        }
    }

    private fun isEmbedded(): Boolean = parentFragment is com.stupidtree.hitax.ui.tools.ToolboxFragment

    private fun value(root: View, id: Int, supplier: () -> String) {
        root.findViewById<TextView>(id)?.text = supplier()
    }

    private fun describeTheme(): String {
        val ctx = requireContext()
        val palette = ThemeTools.getPalette(ctx)
        val mode = when (ThemeTools.getThemeMode(ctx)) {
            ThemeTools.MODE.DARK -> getString(R.string.theme_mode_dark)
            ThemeTools.MODE.LIGHT -> getString(R.string.theme_mode_light)
            else -> getString(R.string.theme_mode_follow)
        }
        // 顺带校验一次色板 id，老的非法值会被 normalize 回默认
        val safe = ThemePalette.byId(palette).id
        val name = getString(ThemePaletteRegistry.nameRes(safe))
        return "$mode · $name"
    }

    private fun describeResources(): String {
        val n = com.stupidtree.hitax.data.repository.CrawlerStorage.savedCount(requireContext())
        return if (n > 0) {
            getString(R.string.settings_resources_saved, n)
        } else {
            getString(R.string.settings_resources_none)
        }
    }
}
