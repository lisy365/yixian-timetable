package com.stupidtree.hitax.ui.main.timetable.views

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.stupidtree.hitax.R
import com.stupidtree.hitax.data.model.timetable.EventItem
import com.stupidtree.hitax.data.model.timetable.TimeInDay
import com.stupidtree.hitax.data.model.timetable.TimePeriodInDay
import com.stupidtree.hitax.data.model.timetable.Timetable
import com.stupidtree.hitax.ui.main.timetable.TimetableStyleSheet
import com.stupidtree.hitax.ui.main.timetable.views.TimeTableBlockView.*
import com.stupidtree.hitax.utils.TimeTools
import com.stupidtree.hitax.utils.TimetableGrid
import java.util.*
import kotlin.math.max
import kotlin.math.min

/**
 * 一周课表网格（v1.0.5 重构）
 *
 * 与 v1.0.4 的区别：
 * - **按「节」等分**：每天固定 [TimetableGrid.FIXED_PERIODS] 节（中大作息为 11 节），
 *   每节一行、行高一致；旧实现按真实钟点（60 分钟）分格，
 *   45 分钟的课只占 3/4 行，两节连排的卡片必然与网格错位；
 * - 网格线按节绘制（而不是按整点），左侧行号由 [LeftLabelView] 画「第N节 + 时间」；
 * - 课程块的行号来自 [EventItem.fromNumber] / [EventItem.lastNumber]，
 *   手动新建（没有节次号）的课程按时间反推（[TimetableGrid.rowRange]）；
 * - 修复旧实现里 `mHeight` 被赋成「打包后的 MeasureSpec」（约 1.07e9）导致
 *   今日高亮矩形画到屏幕外的问题。
 */
class TimeTableView : ViewGroup {
    companion object {
        /** 当前课表的作息结构。**每次刷新页面时由 TimetableFragment 写入** */
        var timetableStructure: List<TimePeriodInDay> = Timetable().getDefaultTimeStructure()
    }

    var mWidth = 0
    var mHeight = 0
    var sectionWidth = 0

    /** 每「节」的高度（px）。名字沿用旧字段，语义已从「每小时」变为「每节课」 */
    var sectionHeight = 180
    var timelineColor = 0
    var addButton: TimeTableBlockAddView? = null
    lateinit var styleSheet: TimetableStyleSheet
    private val startDate: Calendar = Calendar.getInstance()
    private var onCardClickListener: OnCardClickListener? = null
    private var onAddClickListener: OnAddClickListener? = null

    private var onCardLongClickListener: OnCardLongClickListener? = null
    private val mPathEffect = DashPathEffect(floatArrayOf(20f, 20f), 0f)
    private val mLinePaint = Paint()
    private val mPeriodPaint = Paint()

    /** 当前生效的作息结构（已规整为固定 11 节） */
    private var structure: List<TimePeriodInDay> = TimetableGrid.normalize(timetableStructure)

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        typedTimeTableView(attrs, 0)
    }

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    ) {
        typedTimeTableView(attrs, defStyleAttr)
    }

    fun setOnCardClickListener(onCardClickListener: OnCardClickListener?) {
        this.onCardClickListener = onCardClickListener
    }

    fun setOnAddClickListener(onAssClickListener: OnAddClickListener) {
        this.onAddClickListener = onAssClickListener
    }

    fun setOnCardLongClickListener(onCardLongClickListener: OnCardLongClickListener?) {
        this.onCardLongClickListener = onCardLongClickListener
    }

    constructor(context: Context?) : super(context) {}

    /** 设置作息结构（会规整成固定 11 节） */
    fun setStructure(list: List<TimePeriodInDay>?) {
        structure = TimetableGrid.normalize(list ?: Timetable().getDefaultTimeStructure())
        requestLayout()
        invalidate()
    }

    fun getStructure(): List<TimePeriodInDay> = structure

    /** 行高（px） */
    private fun rowHeight(): Int = sectionHeight.coerceAtLeast(1)

    private fun contentHeight(): Int =
        TimetableGrid.totalHeightPx(rowHeight(), structure.size.coerceAtLeast(TimetableGrid.FIXED_PERIODS))

    override fun dispatchDraw(canvas: Canvas) {
        if (TimeTools.isSameWeekWithStartDate(startDate, System.currentTimeMillis())) {
            drawTodayRect(canvas)
        }
        drawGridLines(canvas)
        super.dispatchDraw(canvas)
    }

    private fun typedTimeTableView(attrs: AttributeSet, defStyleAttr: Int) {
        val a = context.theme.obtainStyledAttributes(
            attrs,
            R.styleable.TimeTableViewGroup,
            defStyleAttr,
            0
        )
        val n = a.indexCount
        for (i in 0 until n) {
            when (val attr = a.getIndex(i)) {
                R.styleable.TimeTableViewGroup_timeLineColor ->
                    timelineColor = a.getColor(attr, Color.BLACK)
            }
        }
        a.recycle()
    }

    private fun drawTodayRect(canvas: Canvas) {
        val dow = TimeTools.currentDOW()
        if (dow !in 1..7) return
        val left: Int = sectionWidth * (dow - 1)
        val right = left + sectionWidth
        val paint = Paint()
        paint.color = styleSheet.todayBGColor
        canvas.drawRect(left.toFloat(), 0f, right.toFloat(), mHeight.toFloat(), paint)
    }

    /**
     * 按「节」画网格线：每节课一条实线分隔 + 节与节之间一条虚线（课间）
     */
    private fun drawGridLines(canvas: Canvas) {
        if (!styleSheet.drawBGLine) return
        mLinePaint.style = Paint.Style.STROKE
        mLinePaint.strokeWidth = 1f
        mLinePaint.color = timelineColor
        mLinePaint.alpha = 60
        mLinePaint.pathEffect = null
        mPeriodPaint.style = Paint.Style.STROKE
        mPeriodPaint.strokeWidth = 1f
        mPeriodPaint.color = timelineColor
        mPeriodPaint.alpha = 28
        mPeriodPaint.pathEffect = mPathEffect
        val rh = rowHeight()
        for (i in 0 until structure.size) {
            val top = i * rh
            val path = Path()
            path.moveTo(0f, top.toFloat())
            path.lineTo(mWidth.toFloat(), top.toFloat())
            if (i == 0) {
                canvas.drawPath(path, mLinePaint)
            } else {
                canvas.drawPath(path, mPeriodPaint)
            }
        }
        // 最后一行底部
        val bottomPath = Path()
        bottomPath.moveTo(0f, (structure.size * rh).toFloat())
        bottomPath.lineTo(mWidth.toFloat(), (structure.size * rh).toFloat())
        canvas.drawPath(bottomPath, mLinePaint)
    }


    /**
     * 更新视图
     */
    fun notifyRefresh(startDate: Long, events: List<EventItem>, styleSheet: TimetableStyleSheet) {
        this.styleSheet = styleSheet
        this.startDate.timeInMillis = startDate
        // 行高必须在 requestLayout 之前算好，否则第一帧仍用旧值（旧实现的顺序问题）
        sectionHeight = rowHeightFor(styleSheet)
        removeAllViewsInLayout()
        for (evs in aggregateEvents(events)) {
            addBlock(evs)
        }
        requestLayout()
        invalidate()
    }

    /** 依据样式表计算每节行高 */
    private fun rowHeightFor(styleSheet: TimetableStyleSheet): Int {
        val preferred = (TimetableGrid.ROW_HEIGHT_DP * resources.displayMetrics.density).toInt()
        val minPx = (TimetableGrid.MIN_ROW_HEIGHT_DP * resources.displayMetrics.density).toInt()
        val base = if (styleSheet.cardHeight > 0) styleSheet.cardHeight else preferred
        return base.coerceAtLeast(minPx)
    }

    class Interval(e: EventItem) {
        var from: Long = 0
        var to: Long = 1
        var evs: MutableList<EventItem> = mutableListOf()

        init {
            from = e.from.time
            to = e.to.time
            evs.add(e)
        }
        fun getDuration(): Long {
            return to - from
        }
        fun add(e: EventItem) {
            from = min(from, e.from.time)
            to = max(to, e.to.time)
            evs.add(e)
        }
    }

    private fun aggregateEvents(events: List<EventItem>): List<List<EventItem>> {
        val res = mutableListOf<List<EventItem>>()
        val buk = arrayOfNulls<MutableList<EventItem>>(7)
        for (i in 0 until 7) buk[i] = mutableListOf()
        //按照周数映射
        for (event in events) {
            buk[event.getDow() - 1]?.add(event)
        }
        for (dow in 0 until 7) {
            val b = buk[dow] ?: mutableListOf()
            if (b.isEmpty()) continue
            b.sortWith { i1, i2 -> i1.from.compareTo(i2.from) }
            val lst: LinkedList<Interval> = LinkedList()
            for (e in b) {
                if (lst.isEmpty() || lst.peekLast()!!.to < e.from.time
                    || (lst.peekLast()!!.to - e.from.time < e.getDurationInMills() * 0.5
                            && lst.peekLast()!!.to - e.from.time < lst.peekLast()!!.getDuration() * 0.5)

                ) { //不重叠(或遮盖少于一半)，加入队列
                    val itm = Interval(e)
                    lst.offerLast(itm)
                } else { //重叠且覆盖>50%
                    lst.peekLast()!!.add(e)
                }
            }
            for (inter in lst) {
                res.add(inter.evs)
            }
        }
        return res
    }

    /**
     * 仅更新startDate
     */
    fun setStartDate(ts: Long) {
        startDate.timeInMillis = ts
        invalidate()
    }

    /** 点击的 y 坐标 -> 节次下标（0-based），越界钳制 */
    private fun rowAt(y: Float): Int {
        val rh = rowHeight()
        val idx = (y / rh).toInt()
        return idx.coerceIn(0, structure.size - 1)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            removeView(addButton)
            if (sectionWidth <= 0) return super.onTouchEvent(event)
            val dow = (event.x.toInt() / sectionWidth + 1).coerceIn(1, 7)
            val index = rowAt(event.y)
            val period: TimePeriodInDay = structure.getOrNull(index)?.clone() ?: return super.onTouchEvent(event)
            addButton = TimeTableBlockAddView(context, period, dow)
            addButton?.bindPeriod(index + 1)
            addView(addButton)
            addButton?.onAddClickListener = object : TimeTableBlockAddView.OnAddClickListener {
                override fun onClick(view: View) {
                    onAddClickListener?.onAddClick(dow, period)
                }
            }
        } else {
            removeView(addButton)
        }
        return super.onTouchEvent(event)
    }

    @SuppressLint("DrawAllocation")
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = contentHeight()
        setMeasuredDimension(w, h)
        mWidth = w
        mHeight = h
        sectionWidth = (mWidth) / 7
        for (i in 0 until childCount) {
            when (val child = getChildAt(i)) {
                is TimeTableBlockView -> {
                    val cw = MeasureSpec.makeMeasureSpec(sectionWidth, MeasureSpec.EXACTLY)
                    val cH: Int =
                        MeasureSpec.makeMeasureSpec(getCardHeight(child), MeasureSpec.EXACTLY)
                    child.measure(cw, cH)
                }
                is TimeTableNowLine -> {
                    val cw = MeasureSpec.makeMeasureSpec(sectionWidth, MeasureSpec.EXACTLY)
                    val cH = MeasureSpec.makeMeasureSpec(4, MeasureSpec.EXACTLY)
                    child.measure(cw, cH)
                }
                is TimeTableBlockAddView -> {
                    val cw = MeasureSpec.makeMeasureSpec(sectionWidth, MeasureSpec.EXACTLY)
                    val cH = MeasureSpec.makeMeasureSpec(rowHeight(), MeasureSpec.EXACTLY)
                    child.measure(cw, cH)
                }
                else -> {
                    measureChild(child, widthMeasureSpec, heightMeasureSpec)
                }
            }
        }
    }

    fun init() {
        this.styleSheet = TimetableStyleSheet()
        sectionHeight = rowHeightFor(styleSheet)
        structure = TimetableGrid.normalize(timetableStructure)
        isClickable = true //设置为可点击，否则onTouchEvent只返回DOWN
    }

    /** 事件在网格中的行区间；null 表示无法定位（不绘制） */
    private fun rowRangeOf(vararg items: EventItem): Pair<Int, Int>? {
        val first = items.firstOrNull() ?: return null
        var fromNumber = Int.MAX_VALUE
        var lastNumber = 1
        var minFrom = Long.MAX_VALUE
        var maxTo = Long.MIN_VALUE
        for (e in items) {
            if (e.fromNumber in 1..structure.size) {
                fromNumber = min(fromNumber, e.fromNumber)
                lastNumber = max(lastNumber, e.lastNumber.coerceAtLeast(1))
            }
            minFrom = min(minFrom, e.from.time)
            maxTo = max(maxTo, e.to.time)
        }
        val fromMinutes = TimeTools.getHour(minFrom) * 60 + TimeTools.getMinute(minFrom)
        val toMinutes = TimeTools.getHour(maxTo) * 60 + TimeTools.getMinute(maxTo)
        return TimetableGrid.rowRange(
            if (fromNumber == Int.MAX_VALUE) -1 else fromNumber,
            lastNumber,
            fromMinutes,
            toMinutes,
            structure,
            structure.size
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val count = childCount //获得子控件个数
        val rh = rowHeight()

        for (i in 0 until count) {
            when (val child = getChildAt(i)) {
                is TimeTableBlockView -> {
                    val range = rowRangeOf(*child.events().toTypedArray()) ?: continue
                    val left = sectionWidth * (child.getDow() - 1)
                    val right = left + sectionWidth
                    val top = range.first * rh
                    val bottom = (range.second + 1) * rh
                    child.layout(left, top, right, bottom)
                }
                is TimeTableNowLine -> {
                    // 当前时间落在哪一节，就画在那一节内（按比例）
                    val idx = currentPeriodIndex() ?: return
                    val p = structure[idx]
                    val startMin = p.from.hour * 60 + p.from.minute
                    val endMin = p.to.hour * 60 + p.to.minute
                    val nowMin = TimeTools.getHour(System.currentTimeMillis()) * 60 +
                            TimeTools.getMinute(System.currentTimeMillis())
                    val ratio = if (endMin > startMin) {
                        ((nowMin - startMin).toFloat() / (endMin - startMin)).coerceIn(0f, 1f)
                    } else 0f
                    val top = (idx * rh + ratio * rh).toInt()
                    child.layout(0, top, mWidth, top + 4)
                }
                is TimeTableBlockAddView -> {
                    val left: Int = sectionWidth * (child.dow - 1)
                    val right = left + sectionWidth
                    // 新增块始终占满一整节
                    val idx = structure.indexOfFirst { it.from == child.timePeriod.from }
                    val rowIndex = if (idx >= 0) idx else 0
                    val top = rowIndex * rh
                    child.layout(left, top, right, top + rh)
                }
            }
        }
    }

    /** 当前时间所处的节次下标 */
    private fun currentPeriodIndex(): Int? {
        val now = Calendar.getInstance()
        val dow = now.get(Calendar.DAY_OF_WEEK)
        if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) return null
        val h = now.get(Calendar.HOUR_OF_DAY)
        val m = now.get(Calendar.MINUTE)
        val minutes = h * 60 + m
        for (i in structure.indices) {
            val p = structure[i]
            val s = p.from.hour * 60 + p.from.minute
            val e = p.to.hour * 60 + p.to.minute
            if (minutes in s until e) return i
        }
        return null
    }

    private fun addBlock(o: List<EventItem>) {
        if (o.isEmpty()) return
        if (o.size <= 1) {
            val timeTableBlockView = TimeTableBlockView(context, o[0], styleSheet, structure)
            timeTableBlockView.onCardClickListener =
                object : TimeTableBlockView.OnCardClickListener {
                    override fun onClick(v: View, ei: EventItem) {
                        onCardClickListener?.onEventClick(v, ei)
                    }
                }

            timeTableBlockView.onCardLongClickListener =
                object : TimeTableBlockView.OnCardLongClickListener {
                    override fun onLongClick(v: View, ei: EventItem): Boolean {
                        return onCardLongClickListener?.onEventLongClick(v, ei) == true
                    }
                }
            addView(timeTableBlockView)
        } else {
            val timeTableBlockView = TimeTableBlockView(context, o, styleSheet, structure)
            timeTableBlockView.onDuplicateCardClickListener =
                object : OnDuplicateCardClickListener {
                    override fun onDuplicateClick(v: View, list: List<EventItem>) {
                        onCardClickListener?.onDuplicateEventClick(v, list)
                    }
                }
            timeTableBlockView.onDuplicateCardLongClickListener =
                object : OnDuplicateCardLongClickListener {
                    override fun onDuplicateLongClick(v: View, list: List<EventItem>): Boolean {
                        return onCardLongClickListener?.onDuplicateEventClick(v, list) == true
                    }
                }
            addView(timeTableBlockView)
        }
    }

    interface OnCardClickListener {
        fun onEventClick(v: View, eventItem: EventItem)
        fun onDuplicateEventClick(v: View, eventItems: List<EventItem>)
    }

    private fun getCardHeight(timeTableBlockView: TimeTableBlockView): Int {
        val range = rowRangeOf(*timeTableBlockView.events().toTypedArray())
        val rows = if (range == null) 1 else (range.second - range.first + 1)
        return rows * rowHeight()
    }

    interface OnCardLongClickListener {
        fun onEventLongClick(v: View, eventItem: EventItem): Boolean
        fun onDuplicateEventClick(v: View, eventItems: List<EventItem>): Boolean
    }

    interface OnAddClickListener {
        fun onAddClick(dow: Int, period: TimePeriodInDay)
    }
}
