package com.stupidtree.style.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * 色盘（HSV 圆盘）
 *
 * - 角度 = 色相 h，半径 = 饱和度 s（圆心是白色、边缘是最饱和的颜色）；
 * - 明度 v 由旁边的滑杆控制；
 * - 手指按下即取色，拖动连续取色；手指按住时会请求祖先不要拦截手势，
 *   避免 [com.google.android.material.bottomsheet.BottomSheetBehavior] 把竖直拖动当成「收起弹窗」。
 *
 * 取色只依赖纯函数 [ColorMath]，绘制部分不参与单元测试。
 */
class ColorWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 取色回调：hue 单位为度（0..360），saturation 为 0..1 */
    var onHueSatChanged: ((hue: Float, saturation: Float) -> Unit)? = null

    private var hue = 0f
    private var saturation = 1f
    /** 明度只影响圆盘整体亮度（让用户看到「我选的明度下颜色长这样」） */
    private var value = 1f

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val markerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private var sweepShader: SweepGradient? = null
    private var radialShader: RadialGradient? = null
    private var cachedRadius = 0f
    /** 圆形取色区域的外接矩形，复用避免 onDraw 里反复分配 */
    private val circleRect = RectF()

    private val strokeWidth = resources.displayMetrics.density * 3f

    init {
        isClickable = true
        isFocusable = false
        markerPaint.strokeWidth = strokeWidth
    }

    /** 设置当前颜色（会拆成 h/s/v 三部分） */
    fun setColor(color: Int) {
        val hsv = ColorMath.colorToHsv(color)
        hue = hsv[0]
        saturation = hsv[1]
        value = hsv[2]
        invalidate()
    }

    fun setValue(v: Float) {
        value = v.coerceIn(0f, 1f)
        invalidate()
    }

    /** 设置色相（外部滑杆/输入框改色时同步圆盘指针） */
    fun setHueSat(h: Float, s: Float) {
        hue = ColorMath.normalizeHue(h)
        saturation = s.coerceIn(0f, 1f)
        invalidate()
    }

    fun currentHue(): Float = hue
    fun currentSaturation(): Float = saturation

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        // 圆盘必须正方形，否则角度会被拉伸，取色位置和视觉不符
        val size = when {
            w <= 0 && h <= 0 -> (resources.displayMetrics.density * 200).toInt()
            w <= 0 -> h
            h <= 0 -> w
            else -> min(w, h)
        }
        setMeasuredDimension(size, size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val radius = min(w, h) / 2f - strokeWidth
        cachedRadius = radius
        val cx = w / 2f
        val cy = h / 2f
        circleRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        // 色相环：0°=红在 3 点钟方向，顺时针（与触摸角度 atan2 的坐标系一致）
        sweepShader = SweepGradient(
            cx, cy,
            intArrayOf(
                Color.RED, Color.MAGENTA, Color.BLUE, Color.CYAN,
                Color.GREEN, Color.YELLOW, Color.RED
            ),
            floatArrayOf(0f, 1f / 6f, 2f / 6f, 3f / 6f, 4f / 6f, 5f / 6f, 1f)
        )
        // 径向叠加白色：圆心饱和度 0，边缘饱和度 1
        radialShader = RadialGradient(
            cx, cy, radius.coerceAtLeast(1f),
            intArrayOf(Color.WHITE, Color.WHITE, Color.TRANSPARENT),
            floatArrayOf(0f, 0.02f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = cachedRadius
        if (radius <= 0f) return
        val cx = width / 2f
        val cy = height / 2f

        fillPaint.shader = sweepShader
        canvas.drawCircle(cx, cy, radius, fillPaint)

        overlayPaint.shader = radialShader
        canvas.drawCircle(cx, cy, radius, overlayPaint)

        // 明度压暗：v=1 时不画，v<1 时铺一层黑色，直观反映最终颜色
        if (value < 1f) {
            overlayPaint.shader = null
            overlayPaint.color = Color.argb(((1f - value) * 255f).toInt().coerceIn(0, 255), 0, 0, 0)
            canvas.drawCircle(cx, cy, radius, overlayPaint)
        }

        // 指针位置
        val angleRad = Math.toRadians(hue.toDouble())
        val r = radius * saturation
        val px = cx + (r * cos(angleRad)).toFloat()
        val py = cy + (r * sin(angleRad)).toFloat()
        val markerRadius = strokeWidth * 2.6f
        markerFillPaint.color = Color.WHITE
        canvas.drawCircle(px, py, markerRadius, markerFillPaint)
        markerPaint.color = Color.argb(180, 0, 0, 0)
        canvas.drawCircle(px, py, markerRadius, markerPaint)
        markerPaint.color = Color.WHITE
        canvas.drawCircle(px, py, markerRadius + strokeWidth * 0.4f, markerPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                disallowParentIntercept(true)
                handleTouch(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> handleTouch(event.x, event.y)
            MotionEvent.ACTION_UP -> {
                handleTouch(event.x, event.y)
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

    private fun handleTouch(x: Float, y: Float) {
        if (cachedRadius <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val dx = x - cx
        val dy = y - cy
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val newHue = ColorMath.normalizeHue(Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat())
        val newSat = (dist / cachedRadius).coerceIn(0f, 1f)
        if (newHue == hue && newSat == saturation) return
        hue = newHue
        saturation = newSat
        invalidate()
        onHueSatChanged?.invoke(hue, saturation)
    }

    private fun disallowParentIntercept(disallow: Boolean) {
        var parent = parent
        while (parent is android.view.ViewGroup) {
            parent.requestDisallowInterceptTouchEvent(disallow)
            parent = parent.parent
        }
    }
}
