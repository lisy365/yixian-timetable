package com.stupidtree.hitax.utils

/**
 * 提醒「保活」与触发时刻计算的**纯函数**集合
 *
 * 这里不引用任何 Android API，因此可以直接在离线 JVM harness 里断言，
 * 用来锁住「提醒到底该在什么时刻响」以及「保活闹钟多久重排一次」这两条规则。
 */
object KeepAlivePlan {

    const val MINUTE_MS = 60_000L
    const val HOUR_MS = 60L * MINUTE_MS
    const val DAY_MS = 24L * HOUR_MS

    /** 单次扫描的时间窗口（天）：窗口内的事件都会被排上闹钟 */
    const val WINDOW_DAYS = 7

    /**
     * 「重新排期」保活闹钟的间隔。
     *
     * 旧实现是 `now + 6 天` 只排一次：这一颗闹钟一旦被系统丢掉（省电策略、
     * 强制停止、异常重启），后续提醒就**永久失效**了。
     * 改成每天重排一次后，即使丢掉一两颗，第二天自检就能自动恢复，
     * 而且不需要常驻前台服务。
     */
    const val REARM_INTERVAL_MS = DAY_MS

    /** 前台保活服务里自检的间隔（仅当用户开启「后台保活」时使用） */
    const val KEEPALIVE_TICK_MS = 15L * MINUTE_MS

    /** 已经过期的提醒还允许补发的宽限时间（分钟） */
    const val GRACE_MS = 5L * MINUTE_MS

    /** 保活闹钟下一次该在什么时刻触发 */
    fun nextRearmAt(now: Long): Long = now + REARM_INTERVAL_MS

    /** 扫描窗口的右边界 */
    fun windowEnd(now: Long): Long = now + WINDOW_DAYS * DAY_MS

    /** 扫描窗口的左边界：往回多扫 12 小时，避免正在进行中的事件被漏掉 */
    fun windowStart(now: Long): Long = now - 12 * HOUR_MS

    /**
     * 判定是否需要「精确闹钟」。
     *
     * Android 12（API 31）起 `setExactAndAllowWhileIdle` 需要 SCHEDULE_EXACT_ALARM 权限，
     * 而 Android 14 起对 targetSdk < 33 的应用默认**拒绝**该权限，
     * 于是提醒会静默退化为不精确闹钟（可能晚几分钟到几十分钟）。
     */
    fun needExactAlarm(sdkInt: Int, canScheduleExact: Boolean): Boolean =
        sdkInt < 31 || canScheduleExact

    /**
     * 第 [repeatIndex] 次提醒的触发时刻 = 事件开始时间 - 提前量 - index × 重复间隔。
     */
    fun triggerTimeAt(
        eventFrom: Long,
        leadMinutes: Int,
        repeatIndex: Int,
        repeatIntervalMinutes: Int
    ): Long = eventFrom -
        leadMinutes.coerceAtLeast(0) * MINUTE_MS -
        repeatIndex.coerceAtLeast(0) * repeatIntervalMinutes.coerceAtLeast(0) * MINUTE_MS

    /**
     * 该触发时刻是否值得排期：
     * - 早于 `now - graceMs` 的丢掉（已经来不及了）；
     * - 晚于事件开始时间的丢掉（提醒不能晚于事件本身）。
     */
    fun isTriggerDue(
        triggerAt: Long,
        now: Long,
        eventFrom: Long,
        graceMs: Long = GRACE_MS
    ): Boolean = triggerAt >= now - graceMs && triggerAt <= eventFrom

    /**
     * 计算某个事件应该被排期的全部触发时刻（升序，去重）。
     *
     * 规则（与线上行为一致）：
     * - 基准时刻 = 事件开始时间 - 提前量；
     * - 第 i 次重复 = 基准时刻 - i × 重复间隔（i 从 0 到 repeatCount）；
     * - 丢弃「早于 now - grace」的（已经来不及了）；
     * - 丢弃「晚于事件开始时间」的（提醒不能晚于事件本身）。
     */
    fun triggerTimes(
        eventFrom: Long,
        leadMinutes: Int,
        repeatCount: Int,
        repeatIntervalMinutes: Int,
        now: Long,
        graceMs: Long = GRACE_MS,
        maxRepeat: Int = 10
    ): List<Long> {
        val repeat = repeatCount.coerceIn(0, maxRepeat)
        val result = ArrayList<Long>(repeat + 1)
        for (i in 0..repeat) {
            val triggerAt = triggerTimeAt(eventFrom, leadMinutes, i, repeatIntervalMinutes)
            if (!isTriggerDue(triggerAt, now, eventFrom, graceMs)) continue
            if (!result.contains(triggerAt)) result.add(triggerAt)
        }
        result.sort()
        return result
    }

    /**
     * 保活服务的存活判定：距离上次自检超过 [KEEPALIVE_TICK_MS] 就需要再排一次。
     */
    fun shouldTick(lastTickAt: Long, now: Long): Boolean =
        now - lastTickAt >= KEEPALIVE_TICK_MS

    /**
     * 提醒是否应该被「静默丢弃」：
     * 事件开始超过 [lateToleranceMs] 之后才送达的通知没有意义（例如手机刚开机）。
     */
    fun isTooLateToNotify(now: Long, eventFrom: Long, lateToleranceMs: Long = GRACE_MS): Boolean =
        now > eventFrom + lateToleranceMs
}
