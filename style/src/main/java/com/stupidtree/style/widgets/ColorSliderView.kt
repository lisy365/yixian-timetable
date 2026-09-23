package com.stupidtree.style.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * 横向渐变滑杆
 *
 * 用在调色盘里控制「明度」和「饱和度」：轨道颜色由外部通过 [setGradient] 指定，
 * 因此用户能**直接看到**拖动过程中颜色是怎么变化的，比三条 R/G/B 滑杆直观得多。
 *
 * 手指按住时会请求祖先不要拦截手势，避免底部弹窗的拖拽把它抢走。
 */
class ColorSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 进度变化回调，取值 0..1 */
    var onProgressChanged: ((Float) -> Unit)? = null

    private var progress = 1f
    private var startColor = Color.BLACK
    private var endColor = Color.WHITE

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(60, 0, 0, 0)
    }
    private val thumbFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val thumbStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(120, 0, 0, 0)
    }

    private val density = resources.displayMetrics.density
    private val trackRect = RectF()
    private var trackHeight = 0f
    private var thumbRadius = 0f
    private var trackLeft = 0f
    private var trackRight = 0f

    init {
        isClickable = true
        isFocusable = false
    }

    fun setGradient(start: Int, end: Int) {
        if (start == startColor && end == endColor) return
        startColor = start
        endColor = end
        rebuildShader()
        invalidate()
    }

    fun setProgress(value: Float) {
        val v = value.coerceIn(0f, 1f)
        if (v == progress) return
        progress = v
        invalidate()
    }

    fun currentProgress(): Float = progress

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        trackHeight = (h - 2f * density * 10f).coerceAtLeast(density * 10f)
        thumbRadius = trackHeight * 0.62f
        val half = thumbRadius + density
        trackLeft = half
        trackRight = (w - half).coerceAtLeast(half + 1f)
        val top = (h - trackHeight) / 2f
        trackRect.set(trackLeft, top, trackRight, top + trackHeight)
        borderPaint.strokeWidth = density
        thumbStrokePaint.strokeWidth = density * 1.5f
        rebuildShader()
    }

    private fun rebuildShader() {
        if (trackRight <= trackLeft) return
        trackPaint.shader = LinearGradient(
            trackLeft, 0f, trackRight, 0f,
            startColor, endColor, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (trackRight <= trackLeft) return
        val radius = trackHeight / 2f
        canvas.drawRoundRect(trackRect, radius, radius, trackPaint)
        canvas.drawRoundRect(trackRect, radius, radius, borderPaint)

        val cx = trackLeft + (trackRight - trackLeft) * progress
        val cy = height / 2f
        thumbFillPaint.color = currentColorAt(progress)
        canvas.drawCircle(cx, cy, thumbRadius, thumbFillPaint)
        canvas.drawCircle(cx, cy, thumbRadius, thumbStrokePaint)
    }

    /** 拇指颜色 = 该位置在渐变上的颜色，方便对照 */
    private fun currentColorAt(p: Float): Int {
        val t = p.coerceIn(0f, 1f)
        val a = Color.alpha(startColor) + ((Color.alpha(endColor) - Color.alpha(startColor)) * t).toInt()
        val r = Color.red(startColor) + ((Color.red(endColor) - Color.red(startColor)) * t).toInt()
        val g = Color.green(startColor) + ((Color.green(endColor) - Color.green(startColor)) * t).toInt()
        val b = Color.blue(startColor) + ((Color.blue(endColor) - Color.blue(startColor)) * t).toInt()
        return Color.argb(a, r, g, b)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                disallowParentIntercept(true)
                updateFromX(event.x)
            }
            MotionEvent.ACTION_MOVE -> updateFromX(event.x)
            MotionEvent.ACTION_UP -> {
                updateFromX(event.x)
                disallowParentIntercept(false)
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> disallowParentIntercept(false)
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateFromX(x: Float) {
        if (trackRight <= trackLeft) return
        val v = ((x - trackLeft) / (trackRight - trackLeft)).coerceIn(0f, 1f)
        if (v == progress) return
        progress = v
        invalidate()
        onProgressChanged?.invoke(progress)
    }

    private fun disallowParentIntercept(disallow: Boolean) {
        var parent = parent
        while (parent is android.view.ViewGroup) {
            parent.requestDisallowInterceptTouchEvent(disallow)
            parent = parent.parent
        }
    }
}
