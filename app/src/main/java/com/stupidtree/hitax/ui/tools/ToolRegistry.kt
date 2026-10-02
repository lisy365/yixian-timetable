package com.stupidtree.hitax.ui.tools

import android.content.Context
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.stupidtree.hitax.R
import com.stupidtree.hitax.ui.about.ActivityAbout
import com.stupidtree.hitax.ui.crawler.CrawlerActivity
import com.stupidtree.hitax.ui.settings.PopUpThemePicker
import com.stupidtree.hitax.ui.settings.FragmentNotificationSettings
import com.stupidtree.hitax.ui.task.FragmentTask
import com.stupidtree.hitax.utils.ActivityUtils

/**
 * 「小工具」注册表（v1.0.5 需求 4）
 *
 * 底部导航原来的第 3 个 Tab 是「待办」，现在换成「小工具」，
 * 待办只是其中的一项。为了让后续继续加工具的成本降到最低，
 * **所有工具都在这里声明**，[ToolboxFragment] 只负责渲染：
 *
 * - 新增一个「打开某个 Activity」的工具 → 在 [ALL] 里加一条 [Embedded] 或 [Launcher]，
 *   不需要改任何布局或适配器代码；
 * - 新增一个「内嵌页面」的工具 → 提供 [Embedded.factory]（返回 Fragment），
 *   工具箱会用 `childFragmentManager` 把它挂进内容区，返回键回到列表。
 *
 * 分组（[ToolItem.group]）决定卡片上方的分组标题，新增分组只需加一个字符串资源。
 */
object ToolRegistry {

    enum class Group(val titleRes: Int) {
        STUDY(R.string.tool_group_study),
        APPEARANCE(R.string.tool_group_appearance),
        OTHER(R.string.tool_group_other)
    }

    sealed class ToolItem {
        abstract val id: String
        abstract val nameRes: Int
        abstract val descRes: Int
        abstract val iconRes: Int
        abstract val group: Group

        /** 点击后做什么。返回 true 表示已处理（工具箱不用再切页面） */
        abstract fun open(context: Context, activity: FragmentActivity): Boolean

        /** 用于列表去重 / 记忆最近使用 */
        override fun equals(other: Any?): Boolean = other is ToolItem && other.id == id
        override fun hashCode(): Int = id.hashCode()
    }

    /** 直接在工具箱内嵌一个 Fragment 页面 */
    class Embedded(
        override val id: String,
        override val nameRes: Int,
        override val descRes: Int,
        override val iconRes: Int,
        override val group: Group,
        val factory: () -> Fragment
    ) : ToolItem() {
        override fun open(context: Context, activity: FragmentActivity): Boolean = false
    }

    /** 打开一个 Activity / 弹窗 */
    class Launcher(
        override val id: String,
        override val nameRes: Int,
        override val descRes: Int,
        override val iconRes: Int,
        override val group: Group,
        private val action: (Context, FragmentActivity) -> Unit
    ) : ToolItem() {
        override fun open(context: Context, activity: FragmentActivity): Boolean {
            action(context, activity)
            return true
        }
    }

    /** 全部工具（顺序即展示顺序） */
    val ALL: List<ToolItem> = listOf(
        Embedded(
            id = "task",
            nameRes = R.string.tool_task_name,
            descRes = R.string.tool_task_desc,
            iconRes = R.drawable.ic_nav_task,
            group = Group.STUDY,
            factory = { FragmentTask() }
        ),
        Launcher(
            id = "crawler",
            nameRes = R.string.tool_crawler_name,
            descRes = R.string.tool_crawler_desc,
            iconRes = R.drawable.ic_baseline_cloud_download_24,
            group = Group.STUDY
        ) { context, _ ->
            ActivityUtils.startActivity(context, CrawlerActivity::class.java)
        },
        Launcher(
            id = "timetable_manager",
            nameRes = R.string.tool_timetable_manager_name,
            descRes = R.string.tool_timetable_manager_desc,
            iconRes = R.drawable.ic_nav_timetable,
            group = Group.STUDY
        ) { context, _ ->
            ActivityUtils.startTimetableManager(context)
        },
        Launcher(
            id = "theme",
            nameRes = R.string.tool_theme_name,
            descRes = R.string.tool_theme_desc,
            iconRes = R.drawable.ic_baseline_palette_24,
            group = Group.APPEARANCE
        ) { _, activity ->
            PopUpThemePicker().show(activity.supportFragmentManager, "theme_picker")
        },
        Launcher(
            id = "notification",
            nameRes = R.string.tool_notify_name,
            descRes = R.string.tool_notify_desc,
            iconRes = R.drawable.ic_baseline_notifications_24,
            group = Group.APPEARANCE
        ) { _, activity ->
            FragmentNotificationSettings().show(activity.supportFragmentManager, "notify_settings")
        },
        Launcher(
            id = "about",
            nameRes = R.string.tool_about_name,
            descRes = R.string.tool_about_desc,
            iconRes = R.drawable.ic_theme,
            group = Group.OTHER
        ) { context, _ ->
            ActivityUtils.startActivity(context, ActivityAbout::class.java)
        }
    )

    fun byId(id: String): ToolItem? = ALL.firstOrNull { it.id == id }

    fun groups(): List<Pair<Group, List<ToolItem>>> =
        Group.values().map { g -> g to ALL.filter { it.group == g } }.filter { it.second.isNotEmpty() }
}
