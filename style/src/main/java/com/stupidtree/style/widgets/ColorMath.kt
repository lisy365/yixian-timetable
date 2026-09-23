package com.stupidtree.style.widgets

/**
 * 颜色换算的**纯函数**集合
 *
 * 这里刻意不引用 `android.graphics.Color`，所有计算只用 `kotlin.math`，
 * 因此可以直接在 JVM 单元测试（离线 harness）里跑，不需要模拟器。
 *
 * 约定：
 * - 颜色统一用 ARGB `Int` 表示（与 `TermSubject.color` 一致）；
 * - 色相 h 单位为度，允许任意实数，内部会归一化到 `[0, 360)`；
 * - 饱和度 s、明度 v 取值 `[0, 1]`。
 */
object ColorMath {

    private const val HEX_DIGITS = "0123456789ABCDEF"

    /** 把色相归一化到 `[0, 360)` */
    fun normalizeHue(h: Float): Float {
        if (h.isNaN() || h.isInfinite()) return 0f
        var v = h % 360f
        if (v < 0f) v += 360f
        return v
    }

    /** 打包 ARGB */
    fun argb(alpha: Int, r: Int, g: Int, b: Int): Int =
        ((alpha and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    fun alphaOf(color: Int): Int = (color ushr 24) and 0xFF
    fun redOf(color: Int): Int = (color shr 16) and 0xFF
    fun greenOf(color: Int): Int = (color shr 8) and 0xFF
    fun blueOf(color: Int): Int = color and 0xFF

    /**
     * HSV -> ARGB
     * @param h 色相（度，任意实数，自动归一化）
     * @param s 饱和度 0..1
     * @param v 明度 0..1
     * @param alpha 透明度 0..255
     */
    fun hsvToColor(h: Float, s: Float, v: Float, alpha: Int = 255): Int {
        val hue = normalizeHue(h)
        val sat = s.coerceIn(0f, 1f)
        val value = v.coerceIn(0f, 1f)
        val c = value * sat
        val hp = hue / 60f
        val x = c * (1f - kotlin.math.abs(hp % 2f - 1f))
        val m = value - c
        val r1: Float
        val g1: Float
        val b1: Float
        when {
            hp < 1f -> { r1 = c; g1 = x; b1 = 0f }
            hp < 2f -> { r1 = x; g1 = c; b1 = 0f }
            hp < 3f -> { r1 = 0f; g1 = c; b1 = x }
            hp < 4f -> { r1 = 0f; g1 = x; b1 = c }
            hp < 5f -> { r1 = x; g1 = 0f; b1 = c }
            else -> { r1 = c; g1 = 0f; b1 = x }
        }
        return argb(
            alpha,
            ((r1 + m) * 255f + 0.5f).toInt(),
            ((g1 + m) * 255f + 0.5f).toInt(),
            ((b1 + m) * 255f + 0.5f).toInt()
        )
    }

    /** ARGB -> `[h, s, v]`（h 单位度） */
    fun colorToHsv(color: Int): FloatArray {
        val r = redOf(color) / 255f
        val g = greenOf(color) / 255f
        val b = blueOf(color) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val d = max - min
        val h = when {
            d == 0f -> 0f
            max == r -> 60f * (((g - b) / d) % 6f)
            max == g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }
        val s = if (max == 0f) 0f else d / max
        return floatArrayOf(normalizeHue(h), s, max)
    }

    /** 只替换颜色的明度，保留色相/饱和度/透明度（滑杆用） */
    fun withValue(color: Int, value: Float): Int {
        val hsv = colorToHsv(color)
        return hsvToColor(hsv[0], hsv[1], value, alphaOf(color))
    }

    /** 只替换颜色的饱和度，保留色相/明度/透明度（滑杆用） */
    fun withSaturation(color: Int, saturation: Float): Int {
        val hsv = colorToHsv(color)
        return hsvToColor(hsv[0], saturation, hsv[2], alphaOf(color))
    }

    /** 统一输出 `#RRGGBB`（大写，忽略透明度） */
    fun toHex(color: Int): String {
        val sb = StringBuilder(7)
        sb.append('#')
        for (shift in intArrayOf(16, 8, 0)) {
            val v = (color shr shift) and 0xFF
            sb.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0x0F])
        }
        return sb.toString()
    }

    /**
     * 解析用户输入的色号，非法输入返回 `null`。
     * 支持：`#RGB` / `#RRGGBB` / `#AARRGGBB`，可以省略 `#`，也接受 `0x` 前缀，大小写不限。
     */
    fun parseHex(input: String?): Int? {
        var s = input?.trim().orEmpty()
        if (s.isEmpty()) return null
        if (s.startsWith("#")) s = s.substring(1)
        else if (s.length > 2 && (s.startsWith("0x") || s.startsWith("0X"))) s = s.substring(2)
        if (s.length != 3 && s.length != 6 && s.length != 8) return null
        for (c in s) {
            val ok = (c in '0'..'9') || (c in 'a'..'f') || (c in 'A'..'F')
            if (!ok) return null
        }
        val v = s.toLongOrNull(16) ?: return null
        return when (s.length) {
            3 -> {
                val r = ((v shr 8) and 0xF).toInt()
                val g = ((v shr 4) and 0xF).toInt()
                val b = (v and 0xF).toInt()
                argb(255, r * 17, g * 17, b * 17)
            }
            6 -> argb(255, ((v shr 16) and 0xFF).toInt(), ((v shr 8) and 0xFF).toInt(), (v and 0xFF).toInt())
            else -> v.toInt()
        }
    }

    fun isValidHex(input: String?): Boolean = parseHex(input) != null

    /**
     * 规范化「传入的初始颜色」：
     * - alpha 为 0（例如调用方用 `0` 表示「还没选过」）时补成不透明黑，
     *   避免调色盘打开时是一个全透明、什么都看不见的颜色。
     */
    fun normalizeInputColor(color: Int): Int =
        if (alphaOf(color) == 0) color or (0xFF shl 24) else color
}
