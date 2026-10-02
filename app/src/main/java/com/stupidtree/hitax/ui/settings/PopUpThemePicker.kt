package com.stupidtree.hitax.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.stupidtree.hitax.R
import com.stupidtree.hitax.utils.ThemePalette
import com.stupidtree.hitax.utils.ThemePaletteRegistry
import com.stupidtree.style.ThemeTools
import com.stupidtree.style.widgets.PopUpCheckableList

/**
 * 全局主题色（色板）选择弹窗
 *
 * v1.0.5 需求 1：全局主题色可调，而不是只有黑白两种主题。
 * - 「昼夜模式」三选一（浅色 / 深色 / 跟随系统），复用项目里已有的 [PopUpCheckableList]；
 * - 「主题色」7 套预设色板（中大绿、逸仙蓝、木棉红、紫荆紫、荔枝橙、珠江水青、石墨灰）。
 *
 * 改动后直接 `recreate()` 当前 Activity，无需重启 App；持久化沿用 v1.0.4 的
 * `SharedPreferences("theme")`，老用户升级后自动落在默认的中大绿。
 */
class PopUpThemePicker : BottomSheetDialogFragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        retainInstance = true
        setStyle(STYLE_NORMAL, R.style.TransparentBottomSheetDialogTheme)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = ContextThemeWrapper(requireContext(), ThemeTools.themeOf(requireContext()))
        return LayoutInflater.from(ctx)
            .inflate(R.layout.dialog_bottom_theme_picker, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val dark = ThemeTools.isDark(requireContext())
        val current = ThemeTools.getPalette(requireContext())

        view.findViewById<TextView>(R.id.mode_value).text =
            getString(modeNameRes(ThemeTools.getThemeMode(requireContext())))
        view.findViewById<View>(R.id.mode_row).setOnClickListener { showModePicker() }

        val list = view.findViewById<RecyclerView>(R.id.palette_list)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = PaletteAdapter(ThemePaletteRegistry.swatches(dark), current)
        list.isNestedScrollingEnabled = false

        view.findViewById<View>(R.id.close).setOnClickListener { dismissAllowingStateLoss() }
    }

    private fun modeNameRes(mode: ThemeTools.MODE): Int = when (mode) {
        ThemeTools.MODE.LIGHT -> R.string.theme_mode_light
        ThemeTools.MODE.DARK -> R.string.theme_mode_dark
        else -> R.string.theme_mode_follow
    }

    private fun showModePicker() {
        val modes = listOf(
            ThemeTools.MODE.LIGHT,
            ThemeTools.MODE.DARK,
            ThemeTools.MODE.FOLLOW
        )
        val names = modes.map { getString(modeNameRes(it)) }
        PopUpCheckableList<Int>()
            .setTitle(getString(R.string.theme_mode_title))
            .setListData(names, modes.indices.toList())
            .setOnConfirmListener(object : PopUpCheckableList.OnConfirmListener<Int> {
                override fun OnConfirm(title: String?, key: Int) {
                    ThemeTools.setThemeMode(requireContext(), modes[key])
                    activity?.recreate()
                    dismissAllowingStateLoss()
                }
            })
            .show(parentFragmentManager, "theme_mode")
    }

    private inner class PaletteAdapter(
        private val data: List<ThemePaletteRegistry.Swatch>,
        private val current: String
    ) : RecyclerView.Adapter<PaletteAdapter.Holder>() {

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val dot: View = v.findViewById(R.id.dot)
            val name: TextView = v.findViewById(R.id.name)
            val check: ImageView = v.findViewById(R.id.check)
            val row: FrameLayout = v.findViewById(R.id.row)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_theme_palette, parent, false)
            return Holder(v)
        }

        override fun getItemCount(): Int = data.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = data[position]
            holder.name.text = getString(item.nameRes)
            androidx.core.view.ViewCompat.setBackgroundTintList(
                holder.dot, android.content.res.ColorStateList.valueOf(item.color)
            )
            val selected = ThemePalette.normalizeId(item.id) == ThemePalette.normalizeId(current)
            holder.check.visibility = if (selected) View.VISIBLE else View.INVISIBLE
            holder.row.setOnClickListener {
                ThemeTools.setPaletteId(requireContext(), item.id)
                activity?.recreate()
                dismissAllowingStateLoss()
            }
        }
    }
}
