package com.stupidtree.hitax.utils

import com.stupidtree.hitax.R
import com.stupidtree.style.ThemeTools

/**
 * 色板注册表（app 模块）
 *
 * `style` 模块不能引用 `app` 模块的资源，所以由本类在 `Application.onCreate()` 里
 * 把「色板 id -> R.style.* / R.string.*」的映射注册给 [ThemeTools]。
 *
 * 与 [ThemePalette.PRESETS] 一一对应（list 顺序必须一致，见 [swatches]）。
 */
object ThemePaletteRegistry : ThemeTools.ThemeSource {

    /** 色板 id -> 展示名资源 */
    private val NAMES: Map<String, Int> = mapOf(
        "green" to R.string.theme_palette_green,
        "blue" to R.string.theme_palette_blue,
        "red" to R.string.theme_palette_red,
        "purple" to R.string.theme_palette_purple,
        "orange" to R.string.theme_palette_orange,
        "teal" to R.string.theme_palette_teal,
        "graphite" to R.string.theme_palette_graphite
    )

    /** 浅色 style 资源 */
    private val LIGHT: Map<String, Int> = mapOf(
        "green" to R.style.AppTheme,
        "blue" to R.style.AppTheme_Blue,
        "red" to R.style.AppTheme_Red,
        "purple" to R.style.AppTheme_Purple,
        "orange" to R.style.AppTheme_Orange,
        "teal" to R.style.AppTheme_Teal,
        "graphite" to R.style.AppTheme_Graphite
    )

    /** 深色 style 资源 */
    private val DARK: Map<String, Int> = mapOf(
        "green" to R.style.AppTheme_Dark,
        "blue" to R.style.AppTheme_Blue_Night,
        "red" to R.style.AppTheme_Red_Night,
        "purple" to R.style.AppTheme_Purple_Night,
        "orange" to R.style.AppTheme_Orange_Night,
        "teal" to R.style.AppTheme_Teal_Night,
        "graphite" to R.style.AppTheme_Graphite_Night
    )

    /** 在 Application 启动时调用一次 */
    fun install() {
        ThemeTools.registerThemeSource(this)
    }

    override fun fallbackStyleRes(): Int = R.style.AppTheme

    override fun styleRes(paletteId: String, dark: Boolean): Int {
        val id = ThemePalette.normalizeId(paletteId)
        val table = if (dark) DARK else LIGHT
        return table[id] ?: fallbackStyleRes()
    }

    /** 色板展示名 */
    fun nameRes(paletteId: String): Int =
        NAMES[ThemePalette.normalizeId(paletteId)] ?: R.string.theme_palette_green

    /**
     * 设置页展示用的色板条目：id + 名称 + 预览色（已按当前昼夜模式取色）
     */
    data class Swatch(val id: String, val nameRes: Int, val color: Int)

    fun swatches(dark: Boolean): List<Swatch> {
        val list = ArrayList<Swatch>()
        for (p in ThemePalette.PRESETS) {
            list.add(
                Swatch(
                    p.id,
                    nameRes(p.id),
                    (ThemePalette.previewColor(p.id, dark) and 0xFFFFFF).toInt() or 0xFF000000.toInt()
                )
            )
        }
        return list
    }
}
