package com.stupidtree.hitax.ui.main.timetable.views

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.stupidtree.hitax.R
import com.stupidtree.hitax.data.model.timetable.TimePeriodInDay

class TimeTableBlockAddView(context: Context, var timePeriod: TimePeriodInDay, val dow: Int) : FrameLayout(context) {

    var card: View
    var add: View
    private var periodText: android.widget.TextView? = null

    /** 该块对应的节次（1-based，用于「第N节」显示） */
    var periodNumber: Int = 0

    val duration: Int
        get() = timePeriod.getLengthInMinutes()

    interface OnAddClickListener{
        fun onClick(view:View)
    }

    var onAddClickListener:OnAddClickListener?=null

    init {
        inflate(context, R.layout.dynamic_timetable_block_add, this)
        add = findViewById(R.id.add)
        card = findViewById(R.id.card)
        periodText = findViewById(R.id.period)
        periodText?.text = if (periodNumber > 0) "第${periodNumber}节" else ""
        add.setOnClickListener {
            val parent = parent as ViewGroup
            onAddClickListener?.onClick(it)
            parent.removeView(this@TimeTableBlockAddView)
        }
        card.setOnClickListener { add.callOnClick() }
    }

    fun bindPeriod(number: Int) {
        periodNumber = number
        periodText?.text = if (number > 0) "第${number}节" else ""
    }
}