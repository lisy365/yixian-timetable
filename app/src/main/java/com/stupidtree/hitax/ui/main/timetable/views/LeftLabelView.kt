package com.stupidtree.hitax.ui.main.timetable.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.stupidtree.hitax.R
import com.stupidtree.hitax.data.model.timetable.TimePeriodInDay
import com.stupidtree.hitax.data.model.timetable.Timetable
import com.stupidtree.hitax.utils.TimetableGrid

/**
 * 课表左侧的节次栏（v1.0.5 重构）
 *
 * 旧实现画的是整点时钟（8:00、9:00…23:00），
 * 与「每天固定 11 节、按节等分网格」的新时间表完全对不上（且 sectionHeight 写死 180、
 * 颜色写死黑色，构造时读到的 attr 从未生效）。
 *
 * 现在每行画两行文字：
 * ```
 *  3         <- 节次，加粗、主色
 * 10:10     <- 上课时间，次要色
 * ```
 * 行数与行高严格跟随 [TimeTableView] 的作息结构，保证左侧与右侧网格一一对齐。
 */
class LeftLabelView : View {
    constructor(context: Context?) : super(context) {}

    constructor(context: Context?, attrs: AttributeSet) : super(context, attrs) {
        typedTimeTableView(attrs, 0)
    }

    constructor(context: Context?, attrs: AttributeSet, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    ) {
        typedTimeTableView(attrs, defStyleAttr)
    }

    private fun typedTimeTableView(attrs: AttributeSet, defStyleAttr: Int) {
        val a = context.theme.obtainStyledAttributes(
            attrs,
            R.styleable.LeftLabelView,
            defStyleAttr,
            0
        )
        val n = a.indexCount
        for (i in 0 until n) {
            when (val attr = a.getIndex(i)) {
                R.styleable.LeftLabelView_timeLabelSize -> labelSize = a.getDimensionPixelSize(
                    attr, TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_SP, 8f, resources.displayMetrics
                    ).toInt()
                )
                R.styleable.LeftLabelView_timeLabelColor ->
                    labelColor = a.getColor(attr, Color.BLACK)
            }
        }
        a.recycle()
    }

    private val mNumberPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mTimePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    var labelColor = 0
    var labelSize = 0

    /** 每节行高，由 TimetableFragment 与 TimeTableView 同步设置 */
    var sectionHeight = 180

    /** 当前作息结构 */
    private var structure: List<TimePeriodInDay> = Timetable().getDefaultTimeStructure()

    fun setStructure(list: List<TimePeriodInDay>?) {
        structure = TimetableGrid.normalize(list ?: Timetable().getDefaultTimeStructure())
        invalidate()
    }

    fun setRowHeight(h: Int) {
        if (h > 0 && h != sectionHeight) {
            sectionHeight = h
            invalidate()
        }
    }

    /** 兼容旧调用（旧代码传的是左侧栏的起始小时） */
    fun setStartDate(hour: Int, minute: Int) {
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (structure.isEmpty()) return
        val w = width.toFloat()
        val rh = sectionHeight.toFloat()

        val numberSize = if (labelSize > 0) labelSize * 1.25f else rh * 0.26f
        val timeSize = if (labelSize > 0) labelSize * 0.88f else rh * 0.18f

        mNumberPaint.color = if (labelColor != 0) labelColor else Color.BLACK
        mNumberPaint.textSize = numberSize
        mNumberPaint.textAlign = Paint.Align.CENTER
        mNumberPaint.typeface = Typeface.DEFAULT_BOLD

        mTimePaint.color = if (labelColor != 0) labelColor else Color.BLACK
        mTimePaint.textSize = timeSize
        mTimePaint.textAlign = Paint.Align.CENTER
        mTimePaint.typeface = Typeface.DEFAULT

        for (i in structure.indices) {
            val top = i * rh
            val p = structure[i]
            val number = TimetableGrid.periodNumberText(i)
            // 节次居中于该行的上半部分，时间在下半部分
            val numberBaseline = top + rh * 0.5f
            val timeBaseline = top + rh * 0.82f
            canvas.drawText(number, w / 2f, numberBaseline, mNumberPaint)
            canvas.drawText(TimetableGrid.hhmm(p.from), w / 2f, timeBaseline, mTimePaint)
        }
    }
}
