package com.stupidtree.hitax.utils

/**
 * 全局主题色板（纯逻辑，不引用 Android API，可在 JVM 中单测）
 *
 * 背景（v1.0.5 需求 1）：
 * v1.0.4 之前 App 只有「黑 / 白」两种模式（深色 / 浅色），主色调被写死成中大绿，
 * 用户无法更改。本类提供「预设色板 + 自定义取色」的能力：
 *
 * - 每个色板在 `values/themes.xml` 与 `values-night/themes.xml` 里各有一个 style
 *   （`AppTheme`、`AppTheme.Blue`、`AppTheme.Purple` ...），运行时由
 *   [com.stupidtree.style.ThemeTools] 依据「当前昼夜模式」挑选对应的一个；
 * - 色板的「颜色」只用于界面展示与自定义取色的回填，真正的配色由 style 资源决定，
 *   这样所有通过 `?attr/colorPrimary` 引用的布局会自动跟随，无需逐个找 View。
 *
 * 本类只做**数据与选择逻辑**，`R.style.*` / `R.string.*` 的解析放在
 * [ThemePaletteRegistry]（app 模块）里，保证本文件能在 JVM 中直接单测。
 */
object ThemePalette {

    /** 默认色板（中山大学绿） */
    const val DEFAULT = "green"

    /**
     * 一个预设色板
     * @param id 存进 SharedPreferences 的稳定标识（不要随意改，否则老用户设置会丢）
     * @param styleLight 浅色模式下的 style 资源名（不含 `@style/` 前缀）
     * @param styleDark 深色模式下的 style 资源名
     * @param colorLight 浅色模式下的主色（十六进制，用于界面展示 / 预览小块）
     * @param colorDark 深色模式下的主色
     */
    data class Preset(
        val id: String,
        val styleLight: String,
        val styleDark: String,
        val colorLight: Long,
        val colorDark: Long
    )

    /**
     * 内置色板。顺序即设置页展示顺序，第一个是默认（中大绿）。
     *
     * 颜色的挑选原则：与中大绿同一个明度层级，保证白色标题在卡片上仍然清晰；
     * 深色模式稍微提亮，避免在黑底上发灰。
     */
    val PRESETS: List<Preset> = listOf(
        // 中大绿（默认，保持原品牌色不变）
        Preset("green", "AppTheme", "AppTheme", 0xFF00693E, 0xFF2E8B62),
        // 逸仙蓝
        Preset("blue", "AppTheme.Blue", "AppTheme.Blue", 0xFF1565C0, 0xFF4A90D9),
        // 木棉红
        Preset("red", "AppTheme.Red", "AppTheme.Red", 0xFFC62828, 0xFFE05A5A),
        // 紫荆紫
        Preset("purple", "AppTheme.Purple", "AppTheme.Purple", 0xFF6A1FB0, 0xFF9B6BD6),
        // 荔枝橙
        Preset("orange", "AppTheme.Orange", "AppTheme.Orange", 0xFFE65100, 0xFFF08A3C),
        // 珠江水青
        Preset("teal", "AppTheme.Teal", "AppTheme.Teal", 0xFF00695C, 0xFF2E9E8F),
        // 石墨灰（极简黑白党）
        Preset("graphite", "AppTheme.Graphite", "AppTheme.Graphite", 0xFF37474F, 0xFF78909C)
    )

    /** 是否内置色板 */
    fun isPreset(id: String?): Boolean = PRESETS.any { it.id == id }

    /**
     * 规整用户传来的色板 id：非法值一律回落到默认色板。
     * 老版本升级上来的用户没有这个键，也会得到默认值。
     */
    fun normalizeId(id: String?): String {
        if (id == null) return DEFAULT
        val t = id.trim()
        if (t.isEmpty()) return DEFAULT
        return if (isPreset(t)) t else DEFAULT
    }

    fun byId(id: String?): Preset =
        PRESETS.firstOrNull { it.id == normalizeId(id) } ?: PRESETS.first()

    /** 依据昼夜模式挑选 style 资源名 */
    fun styleName(id: String?, dark: Boolean): String {
        val preset = byId(id)
        return if (dark) preset.styleDark else preset.styleLight
    }

    /** 依据昼夜模式挑选展示用主色 */
    fun previewColor(id: String?, dark: Boolean): Long {
        val preset = byId(id)
        return if (dark) preset.colorDark else preset.colorLight
    }

    /**
     * 由主色推导「次要 / 禁用」色（纯算术，避免依赖 android.graphics）。
     *
     * @param color 主色（AARRGGBB）
     * @param ratio 与白色的混合比例（0 = 原色，1 = 纯白）
     */
    fun lighten(color: Long, ratio: Float): Long {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        val k = ratio.coerceIn(0f, 1f)
        val nr = (r + (255 - r) * k).toInt().coerceIn(0, 255)
        val ng = (g + (255 - g) * k).toInt().coerceIn(0, 255)
        val nb = (b + (255 - b) * k).toInt().coerceIn(0, 255)
        return (0xFFL shl 24) or (nr.toLong() shl 16) or (ng.toLong() shl 8) or nb.toLong()
    }

    /**
     * 判断颜色是否偏亮（用于自动决定状态栏图标是深色还是浅色）
     * 使用简单的感知亮度公式，取值 0~255。
     */
    fun luminance(color: Long): Int {
        val r = ((color shr 16) and 0xFF).toInt()
        val g = ((color shr 8) and 0xFF).toInt()
        val b = (color and 0xFF).toInt()
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    fun isLight(color: Long): Boolean = luminance(color) >= 160

    /** 把 0xRRGGBB / 0xAARRGGBB 统一补成不透明的 AARRGGBB */
    fun opaque(color: Long): Long = if (color and 0xFF000000L == 0L) color or 0xFF000000L else color

    /** 格式化为 `#RRGGBB`（大写），设置页展示用 */
    fun toHex(color: Long): String {
        val c = opaque(color)
        return String.format("#%06X", (c and 0xFFFFFFL))
    }
}
