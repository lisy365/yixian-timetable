package com.stupidtree.hitax.ui.main.timeline

import com.stupidtree.hitax.data.model.timetable.EventItem

/**
 * 「今日」时间轴的列表装配规则
 *
 * 时间轴的数据来自两路：
 *  1. 当天 00:00~24:00 的课程 / 日程（含当天到期的待办）；
 *  2. 未完成、且截止时间在明天及以后的待办 —— 这类待办不在当天的 SQL 窗口里，
 *     不合并的话它们在「今日」页完全看不见（只有下拉的「即将到来」会露出几条）。
 *
 * 这里只做纯函数装配，便于离线单测（见 `_scratch/harness`）。
 */
object TimelineTasks {

    /**
     * 把未来的待办合并进当天列表。
     *
     * @param todayEvents 当天事件（可能已包含当天到期的待办）
     * @param futureTasks 未完成、截止时间 >= 明天 0 点的待办
     * @return 合并去重后的列表（保持两段的顺序，排序交给调用方）
     */
    fun mergeTodayTasks(
        todayEvents: List<EventItem>,
        futureTasks: List<EventItem>?
    ): List<EventItem> {
        val result = ArrayList<EventItem>(todayEvents.size + (futureTasks?.size ?: 0))
        result.addAll(todayEvents)
        if (futureTasks.isNullOrEmpty()) return result
        val exists = HashSet<String>(todayEvents.size * 2)
        for (e in todayEvents) exists.add(e.id)
        for (t in futureTasks) {
            // 只并未来待办：当天窗口里已经有的不重复添加，已完成的也不进时间轴
            if (t.done || !t.isTask()) continue
            if (exists.add(t.id)) result.add(t)
        }
        return result
    }

    /**
     * 时间轴展示顺序：未完成在前，其余按时间先后。
     * 已完成的待办统一沉到列表末尾，避免它们夹在课程中间看着像"消失/错位"。
     * 时间戳相同时用 id 兜底，保证顺序稳定可预期。
     */
    fun displayOrder(list: List<EventItem>): List<EventItem> =
        list.sortedWith(
            compareBy<EventItem> { if (it.isTask() && it.done) 1 else 0 }
                .thenBy { it.from.time }
                .thenBy { it.id }
        )
}
