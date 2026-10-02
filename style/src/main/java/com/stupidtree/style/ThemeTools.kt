package com.stupidtree.style

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate

/**
 * 主题与配色工具
 *
 * v1.0.5 起支持「全局主题色可调」：
 * - **昼夜模式**（[MODE]）保留原有三档：深色 / 浅色 / 跟随系统；
 * - **色板**（palette）新增，可选项由宿主 App 通过 [ThemeSource] 提供
 *   （style 模块不能依赖 app 模块的资源，所以走注册回调）。
 *
 * 应用时机：Activity 必须在 `super.onCreate()` **之前** 调 [applyTheme]，
 * 否则 `setTheme` 对已经 inflate 的布局不生效。
 */
object ThemeTools {

    enum class MODE { DARK, LIGHT, FOLLOW }

    private const val SP_NAME = "theme"
    private const val KEY_MODE = "mode"
    private const val KEY_PALETTE = "palette"
    private const val KEY_CUSTOM_RGB = "custom_rgb"

    /** 未注册色板时的兜底 style（宿主 App 的默认主题） */
    private var fallbackStyleRes: Int = 0

    /** 宿主 App 注册的色板来源 */
    @Volatile
    private var source: ThemeSource? = null

    /**
     * 色板来源。app 模块实现它，把「色板 id -> style 资源」的映射注册进来。
     */
    interface ThemeSource {
        /** app 模块的 `R.style.AppTheme`，作为未注册时的兜底 */
        fun fallbackStyleRes(): Int

        /** 浅色模式下该色板对应的 style 资源；返回 0 表示未知 */
        fun styleRes(paletteId: String, dark: Boolean): Int
    }

    /** 由宿主 App 在 `Application.onCreate()` 中调用一次 */
    fun registerThemeSource(s: ThemeSource) {
        source = s
        fallbackStyleRes = s.fallbackStyleRes()
    }

    // ------------------------------------------------------------------
    // 昼夜模式
    // ------------------------------------------------------------------

    fun getThemeMode(context: Context): MODE {
        val pr = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        return when (pr.getString(KEY_MODE, "follow")) {
            "dark" -> MODE.DARK
            "light" -> MODE.LIGHT
            else -> MODE.FOLLOW
        }
    }

    fun setThemeMode(context: Context, mode: MODE) {
        val pr = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        val str = when (mode) {
            MODE.DARK -> "dark"
            MODE.LIGHT -> "light"
            else -> "follow"
        }
        pr.edit().putString(KEY_MODE, str).apply()
    }

    /** 昼夜循环切换（保留原行为：深色 -> 浅色 -> 跟随系统） */
    fun switchTheme(activity: Activity) {
        val newMode = when (getThemeMode(activity)) {
            MODE.DARK -> MODE.LIGHT
            MODE.LIGHT -> MODE.FOLLOW
            MODE.FOLLOW -> MODE.DARK
        }
        setThemeMode(activity, newMode)
        activity.recreate()
    }

    // ------------------------------------------------------------------
    // 色板
    // ------------------------------------------------------------------

    /** 用户当前选择的色板 id（未知值一律回落到默认色板） */
    fun getPalette(context: Context): String {
        val pr = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        return pr.getString(KEY_PALETTE, null) ?: "green"
    }

    /** 设置色板并重建界面 */
    fun setPalette(activity: Activity, paletteId: String) {
        setPaletteId(activity, paletteId)
        activity.recreate()
    }

    /** 只写设置不重建（供批量设置使用） */
    fun setPaletteId(context: Context, paletteId: String) {
        context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_PALETTE, paletteId).apply()
    }

    /** 自定义主色（0 表示未设置），存 RGB 三个分量避免依赖 android.graphics */
    fun getCustomColor(context: Context): Int {
        val v = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_CUSTOM_RGB, 0)
        return if (v == 0) 0 else 0xFF000000.toInt() or (v and 0xFFFFFF)
    }

    fun setCustomColor(context: Context, color: Int) {
        context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_CUSTOM_RGB, color and 0xFFFFFF).apply()
    }

    /**
     * 是否处于深色模式。优先看 AppCompatDelegate 的默认（App 唯一真源），
     * 退化到当前配置的 uiMode。
     */
    fun isDark(context: Context): Boolean {
        return when (AppCompatDelegate.getDefaultNightMode()) {
            AppCompatDelegate.MODE_NIGHT_YES -> true
            AppCompatDelegate.MODE_NIGHT_NO -> false
            else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
        }
    }

    /**
     * 当前应使用的 style 资源 id。
     * 由 [ThemeSource] 依据「色板 id + 昼夜模式」解析；未注册时回落到默认主题。
     */
    fun getStyleRes(context: Context): Int {
        val s = source ?: return fallbackStyleRes
        val palette = getPalette(context)
        val dark = isDark(context)
        val res = s.styleRes(palette, dark)
        return if (res != 0) res else s.fallbackStyleRes()
    }

    /** [TransparentDialog] 等自建 ContextThemeWrapper 时使用 */
    fun themeOf(context: Context): Int = getStyleRes(context)

    /**
     * 应用主题。**必须在 `Activity.onCreate()` 的 `super.onCreate()` 之前调用。**
     *
     * 顺序很重要：
     * 1. 先定昼夜模式（沿用 v1.0.4 的 `setDefaultNightMode`，它对后续 Activity 全局生效）；
     * 2. 再按「昼夜模式 + 色板」setTheme 到具体 style。
     */
    fun applyTheme(activity: Activity) {
        val mode = when (getThemeMode(activity)) {
            MODE.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            MODE.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
        val res = getStyleRes(activity)
        if (res != 0) activity.setTheme(res)
    }
}
