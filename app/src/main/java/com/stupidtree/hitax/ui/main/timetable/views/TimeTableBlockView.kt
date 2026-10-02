package com.stupidtree.hitax.ui.main.timetable.views

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.stupidtree.hitax.R
import com.stupidtree.hitax.data.model.timetable.EventItem
import com.stupidtree.hitax.ui.main.timetable.TimetableStyleSheet
import kotlin.math.max
import kotlin.math.min

class TimeTableBlockView constructor(
    context: Context,
    var block: Any,
    var styleSheet: TimetableStyleSheet,
    /** 当前作息结构，用于把课程渲染成「第N节」 */
    var structure: List<com.stupidtree.hitax.data.model.timetable.TimePeriodInDay>? = null
) :
    FrameLayout(context) {
    lateinit var card: View
    var title: TextView? = null
    var subtitle: TextView? = null
    var period: TextView? = null
    var icon: ImageView? = null
    var onCardClickListener: OnCardClickListener? = null
    var onCardLongClickListener: OnCardLongClickListener? = null
    var onDuplicateCardClickListener: OnDuplicateCardClickListener? = null
    var onDuplicateCardLongClickListener: OnDuplicateCardLongClickListener? = null

    /** 该卡片承载的全部事件（重复卡里可能有多个） */
    fun events(): List<EventItem> {
        return when (val b = block) {
            is EventItem -> listOf(b)
            is List<*> -> b.filterIsInstance<EventItem>()
            else -> emptyList()
        }
    }

    /** 卡片上要显示的节次文本，形如 `第3-4节`；无法判断时返回 null */
    fun periodLabel(): String? {
        val evs = events()
        if (evs.isEmpty()) return null
        val structureSize = structure?.size ?: 0
        var fromNumber = Int.MAX_VALUE
        var lastNumber = 1
        for (e in evs) {
            if (structureSize > 0 && e.fromNumber in 1..structureSize) {
                fromNumber = kotlin.math.min(fromNumber, e.fromNumber)
                lastNumber = kotlin.math.max(lastNumber, e.lastNumber.coerceAtLeast(1))
            }
        }
        if (fromNumber == Int.MAX_VALUE) {
            // 手动新建的课程没有节次号：按开始/结束时间反推
            val st = structure ?: return null
            var minFrom = Long.MAX_VALUE
            var maxTo = Long.MIN_VALUE
            for (e in evs) {
                minFrom = kotlin.math.min(minFrom, e.from.time)
                maxTo = kotlin.math.max(maxTo, e.to.time)
            }
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = minFrom
            val fromMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
            cal.timeInMillis = maxTo
            val toMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
            val range = com.stupidtree.hitax.utils.TimetableGrid.rowRange(
                -1, 1, fromMin, toMin, st, st.size
            ) ?: return null
            fromNumber = range.first + 1
            lastNumber = range.second - range.first + 1
        }
        return com.stupidtree.hitax.utils.TimetableGrid.periodLabel(fromNumber, lastNumber)
    }

    interface OnCardClickListener {
        fun onClick(v: View, ei: EventItem)
    }

    interface OnCardLongClickListener {
        fun onLongClick(v: View, ei: EventItem): Boolean
    }

    interface OnDuplicateCardClickListener {
        fun onDuplicateClick(v: View, list: List<EventItem>)
    }

    private fun getColor(color: Int): Int {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(color, typedValue, true)
        return typedValue.data
    }

    /** 是否显示卡片上的「第N节」标签（可在课表样式里关掉） */
    private var periodEnabled: Boolean = styleSheet.showPeriodLabel

    /** 「第N节」标签的颜色：跟随副标题设置，保证与地点文字同一层次 */
    private fun periodTextColor(): Int {
        return when (styleSheet.subTitleColor) {
            "subject" -> {
                val first = events().firstOrNull()
                if (styleSheet.isColorEnabled && first != null) first.color
                else getColor(R.attr.colorPrimary)
            }
            "white" -> Color.WHITE
            "black" -> Color.BLACK
            else -> getColor(R.attr.colorPrimary)
        }
    }

    private fun initEventCard(context: Context) {
        val ei = block as EventItem
        inflate(context, R.layout.fragment_timetable_class_card, this)
        card = findViewById(R.id.card)
        title = findViewById(R.id.title)
        subtitle = findViewById(R.id.subtitle)
        period = findViewById(R.id.period)
        icon = findViewById(R.id.icon)
        if (styleSheet.isFadeEnabled) {
            card.setBackgroundResource(R.drawable.spec_timetable_card_background_fade)
        } else {
            card.setBackgroundResource(R.drawable.spec_timetable_card_background)
        }
        if (styleSheet.isColorEnabled) {
            card.backgroundTintList = ColorStateList.valueOf(ei.color)
        } else {
            card.backgroundTintList = ColorStateList.valueOf(getColor(R.attr.colorPrimary))
        }
        when (styleSheet.cardTitleColor) {
            "subject" -> if (styleSheet.isColorEnabled) {
                title?.setTextColor(ei.color)
            } else title?.setTextColor(getColor(R.attr.colorPrimary))
            "white" -> title?.setTextColor(Color.WHITE)
            "black" -> title?.setTextColor(Color.BLACK)
            "primary" -> title?.setTextColor(getColor(R.attr.colorPrimary))
        }
        when (styleSheet.subTitleColor) {
            "subject" -> if (styleSheet.isColorEnabled) {
                subtitle?.setTextColor(ei.color)
            } else subtitle?.setTextColor(getColor(R.attr.colorPrimary))
            "white" -> subtitle?.setTextColor(Color.WHITE)
            "black" -> subtitle?.setTextColor(Color.BLACK)
            "primary" -> subtitle?.setTextColor(getColor(R.attr.colorPrimary))
        }
        if (styleSheet.cardIconEnabled) {
            icon?.visibility = VISIBLE
            icon?.setColorFilter(Color.WHITE)
            when (styleSheet.iconColor) {
                "subject" -> if (styleSheet.isColorEnabled) {
                    icon?.setColorFilter(ei.color)
                } else icon?.setColorFilter(getColor(R.attr.colorPrimary))
                "white" -> icon?.setColorFilter(Color.WHITE)
                "black" -> icon?.setColorFilter(Color.BLACK)
                "primary" -> icon?.setColorFilter(getColor(R.attr.colorPrimary))
            }
        } else {
            icon?.visibility = GONE
        }

        card.setOnClickListener { v -> onCardClickListener?.onClick(v, ei) }
        card.setOnLongClickListener { v: View ->
            return@setOnLongClickListener onCardLongClickListener?.onLongClick(v, ei) == true
        }
        title?.text = ei.name
        subtitle?.text = if (TextUtils.isEmpty(ei.place)) "" else ei.place
        // 节次标签：课程以「第N节」的形式显示在时间表内
        val label = periodLabel()
        period?.text = label ?: ""
        period?.visibility = if (label == null || !periodEnabled) GONE else VISIBLE
        period?.setTextColor(periodTextColor())
        card.background.mutate().alpha = (255 * (styleSheet.cardOpacity.toFloat() / 100)).toInt()
        if (styleSheet.isBoldText) {
            title?.typeface = Typeface.DEFAULT_BOLD
            subtitle?.typeface = Typeface.DEFAULT_BOLD
        }
        title?.alpha = styleSheet.titleAlpha.toFloat() / 100
        subtitle?.alpha = styleSheet.subtitleAlpha.toFloat() / 100
        title?.gravity = styleSheet.titleGravity
    }


    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (block is EventItem) {
            initEventCard(context)
        } else if (block is List<*>) {
            initDuplicateCard(context)
        }
    }


    private fun initDuplicateCard(context: Context) {
        val list: List<EventItem> = block as List<EventItem>
        inflate(context, R.layout.fragment_timetable_duplicate_card, this)
        card = findViewById(R.id.card)
        val subCard:LinearLayout = findViewById(R.id.card1)
        title = findViewById(R.id.title)
        icon = findViewById(R.id.icon)
        val names = mutableListOf<String>()
        for (ei in list) names.add(ei.name)
        title?.text = names.joinToString(",\n")
        // 重复卡片同样显示节次
        period = findViewById(R.id.period)
        val dupLabel = periodLabel()
        period?.text = dupLabel ?: ""
        period?.visibility = if (dupLabel == null || !periodEnabled) GONE else VISIBLE
        period?.setTextColor(periodTextColor())
        if (onDuplicateCardClickListener != null) card.setOnClickListener { v ->
            onDuplicateCardClickListener?.onDuplicateClick(
                v,
                list
            )
        }
        if (onDuplicateCardLongClickListener != null) card.setOnLongClickListener { v ->
            onDuplicateCardLongClickListener?.let { return@let it.onDuplicateLongClick(v, list) }
            return@setOnLongClickListener false
        }
        val mainItem = list[0]
        val subItem =  if(list.size>1)list[1] else mainItem
        if (styleSheet.isColorEnabled) {
            subCard.backgroundTintList = ColorStateList.valueOf(mainItem.color)
            card.backgroundTintList = ColorStateList.valueOf(subItem.color)

            when (styleSheet.cardTitleColor) {
                "subject" -> title?.setTextColor(mainItem.color)
                "white" -> title?.setTextColor(Color.WHITE)
                "black" -> title?.setTextColor(Color.BLACK)
                "primary" -> title?.setTextColor(getColor(R.attr.colorPrimary))
            }
        } else {
            card.backgroundTintList = ColorStateList.valueOf(getColor(R.attr.colorPrimary))
            subCard.backgroundTintList = ColorStateList.valueOf(getColor(R.attr.colorPrimary))
            when (styleSheet.cardTitleColor) {
                "white" -> title?.setTextColor(Color.WHITE)
                "black" -> title?.setTextColor(Color.BLACK)
                "primary" -> title?.setTextColor(getColor(R.attr.colorPrimary))
            }
        }
        if (icon != null) {
            if (styleSheet.isColorEnabled) {
                icon?.visibility = VISIBLE
                icon?.setColorFilter(Color.WHITE)
                when (styleSheet.iconColor) {
                    "subject" -> if (styleSheet.isColorEnabled) {
                        icon?.setColorFilter(mainItem.color)
                    } else icon?.setColorFilter(getColor(R.attr.colorPrimary))
                    "white" -> icon?.setColorFilter(Color.WHITE)
                    "black" -> icon?.setColorFilter(Color.BLACK)
                    "primary" -> icon?.setColorFilter(getColor(R.attr.colorPrimary))
                }
            } else {
                //icon?.visibility = GONE
            }
        }
        card.background.mutate().alpha = (255 * (styleSheet.cardOpacity.toFloat() / 100)).toInt()
        if (styleSheet.isBoldText) {
            title?.typeface = Typeface.DEFAULT_BOLD
        }
        title?.alpha = styleSheet.titleAlpha.toFloat() / 100
        title?.gravity = styleSheet.titleGravity
    }

    interface OnDuplicateCardLongClickListener {
        fun onDuplicateLongClick(v: View, list: List<EventItem>): Boolean
    }


    fun getDow(): Int {
        if (block is EventItem) {
            return (block as EventItem).getDow()
        } else if (block is List<*>) {
            return (block as List<EventItem>)[0].getDow()
        }
        return -1
    }

    fun getDuration(): Int {
        if (block is EventItem) {
            return (block as EventItem).getDurationInMinutes()
        } else if (block is List<*>) {
            var minStart = Long.MAX_VALUE
            var maxEnd = Long.MIN_VALUE
            for (e in block as List<*>) {
                minStart = min(minStart, (e as EventItem).from.time)
                maxEnd = max(maxEnd, e.to.time)
            }
            return ((maxEnd - minStart) / (60 * 1000)).toInt()
        }
        return -1
    }

    fun getStartTime(): Long {
        if (block is EventItem) {
            return (block as EventItem).from.time
        } else if (block is List<*>) {
            var minStart = Long.MAX_VALUE
            for (e in block as List<*>) {
                minStart = min(minStart, (e as EventItem).from.time)
            }
            return minStart
        }
        return -1
    }


}