package com.stupidtree.style.widgets

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.stupidtree.style.R
import com.stupidtree.style.databinding.DialogBottomColorPickerBinding

/**
 * 调色盘弹窗（v1.0.4 重做）
 *
 * 交互从「三条 R/G/B 滑杆」改成更直观的方式：
 * - **色盘**：角度 = 色相、半径 = 饱和度，指哪打哪；
 * - **明度 / 饱和度渐变滑杆**：轨道本身就是渐变，拖到哪一眼就能看到颜色怎么变；
 * - **色号输入框**：可直接输入 `#RRGGBB` / `#AARRGGBB`（也接受 `#RGB`、省略 `#`）；
 * - **常用颜色**：一排色点一键选中。
 *
 * 对外 API 与旧版完全一致（[initColor] / [setOnColorSelectListener] / [OnColorSelectedListener]），
 * 两个调用点（TimetableDetailActivity、FragmentTimetablePanel）无需改动。
 */
class PopUpColorPicker : TransparentBottomSheetDialog<DialogBottomColorPickerBinding>() {

    interface OnColorSelectedListener {
        fun onSelected(color: Int)
    }

    private var onColorSelectedListener: OnColorSelectedListener? = null

    /** 初始颜色；alpha 为 0 时视为「还没选过」，打开时补成不透明 */
    private var initialColor: Int = Color.CYAN

    private var hue = 0f
    private var saturation = 1f
    private var value = 1f
    private var alpha = 255

    /** 程序化改写输入框内容时置 true，避免 TextWatcher 递归 */
    private var suppressTextWatcher = false

    override fun getLayoutId(): Int = R.layout.dialog_bottom_color_picker

    override fun initViewBinding(v: View): DialogBottomColorPickerBinding {
        return DialogBottomColorPickerBinding.bind(v)
    }

    fun initColor(color: Int): PopUpColorPicker {
        initialColor = color
        return this
    }

    fun setOnColorSelectListener(l: OnColorSelectedListener): PopUpColorPicker {
        onColorSelectedListener = l
        return this
    }

    // ------------------------------------------------------------------ 生命周期

    override fun onStart() {
        super.onStart()
        // 输入框需要软键盘：清掉会把本窗口排除在输入法目标之外的 flag
        dialog?.window?.apply {
            clearFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            )
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        val dialog = dialog ?: return
        val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?: return
        val behavior = BottomSheetBehavior.from(sheet)
        // 内容比默认 400dp 的 peekHeight 高，主动撑开，否则会以折叠态出现、看不全
        val target = (resources.displayMetrics.heightPixels * 0.78f).toInt()
        behavior.peekHeight = target
        sheet.layoutParams.height = target
        sheet.requestLayout()
        // 色盘/滑杆自己会请求「不要拦截手势」，弹窗不再参与拖拽，避免竖直拖动被抢走
        behavior.isDraggable = false
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        syncAllViews(updateText = true)
    }

    // ------------------------------------------------------------------ 视图装配

    override fun initViews(v: View) {
        val b = binding ?: return
        applyInitialColor()
        syncAllViews(updateText = true)

        // 色盘取色
        b.colorWheel.onHueSatChanged = { h, s ->
            hue = h
            saturation = s
            onColorEdited()
        }

        // 明度
        b.colorValue.onProgressChanged = { p ->
            value = p
            onColorEdited()
        }

        // 饱和度
        b.colorSat.onProgressChanged = { p ->
            saturation = p
            b.colorWheel.setHueSat(hue, saturation)
            onColorEdited()
        }

        // 色号输入
        b.colorApply.setOnClickListener { commitHexInput() }
        b.colorHex.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                commitHexInput()
                true
            } else {
                false
            }
        }
        b.colorHex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (suppressTextWatcher) return
                // 输入到合法色号就实时预览（用户没按「应用」也不打断输入）
                val parsed = ColorMath.parseHex(s?.toString()) ?: return
                applyColor(parsed, updateText = false)
            }
        })

        buildPresetDots()

        b.done.setOnClickListener {
            onColorSelectedListener?.onSelected(currentColor())
            dismiss()
        }
    }

    // ------------------------------------------------------------------ 内部逻辑

    private fun applyInitialColor() {
        val c = ColorMath.normalizeInputColor(initialColor)
        val hsv = ColorMath.colorToHsv(c)
        hue = hsv[0]
        saturation = hsv[1]
        value = hsv[2]
        alpha = ColorMath.alphaOf(c)
    }

    /** 把一个颜色灌进所有控件（直接输入色号 / 点常用颜色时用） */
    private fun applyColor(color: Int, updateText: Boolean) {
        val hsv = ColorMath.colorToHsv(color)
        hue = hsv[0]
        saturation = hsv[1]
        value = hsv[2]
        alpha = ColorMath.alphaOf(color)
        syncAllViews(updateText)
    }

    private fun currentColor(): Int = ColorMath.hsvToColor(hue, saturation, value, alpha)

    /** 用户通过色盘 / 滑杆改动后：整体刷新（输入框正在输入时不覆盖它） */
    private fun onColorEdited() {
        syncAllViews(updateText = !isHexFieldFocused())
    }

    private fun isHexFieldFocused(): Boolean = binding?.colorHex?.hasFocus() == true

    private fun syncAllViews(updateText: Boolean) {
        val b = binding ?: return
        val color = currentColor()

        b.colorDemo.backgroundTintList = ColorStateList.valueOf(color)

        b.colorWheel.setHueSat(hue, saturation)
        b.colorWheel.setValue(value)

        // 明度轨道：黑 -> 当前色相/饱和度下的最亮色
        b.colorValue.setGradient(Color.BLACK, ColorMath.hsvToColor(hue, saturation, 1f, alpha))
        b.colorValue.setProgress(value)

        // 饱和度轨道：当前色相下「最不饱和 -> 最饱和」
        b.colorSat.setGradient(
            ColorMath.hsvToColor(hue, 0f, value, alpha),
            ColorMath.hsvToColor(hue, 1f, value, alpha)
        )
        b.colorSat.setProgress(saturation)

        if (updateText) setHexText(ColorMath.toHex(color))
    }

    private fun setHexText(text: String) {
        val field = binding?.colorHex ?: return
        if (field.text?.toString() == text) return
        suppressTextWatcher = true
        field.setText(text)
        field.setSelection(field.text?.length ?: 0)
        suppressTextWatcher = false
    }

    private fun commitHexInput() {
        val field = binding?.colorHex ?: return
        val parsed = ColorMath.parseHex(field.text?.toString())
        if (parsed == null) {
            Toast.makeText(requireContext(), R.string.color_picker_hex_invalid, Toast.LENGTH_SHORT)
                .show()
            return
        }
        applyColor(parsed, updateText = true)
        hideKeyboard(field)
    }

    private fun hideKeyboard(view: View) {
        try {
            val imm = requireContext()
                .getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(view.windowToken, 0)
            view.clearFocus()
        } catch (e: Exception) {
            // ignore
        }
    }

    /** 常用颜色：与 app 的 ColorTools.colors_material 一致，另加黑白，一键选中 */
    private fun buildPresetDots() {
        val container = binding?.colorPresets ?: return
        if (container.childCount > 0) return
        val density = resources.displayMetrics.density
        val size = (26 * density).toInt()
        val margin = (4 * density).toInt()
        for (hex in PRESET_COLORS) {
            val parsed = ColorMath.parseHex(hex) ?: continue
            val dot = View(requireContext())
            val lp = LinearLayout.LayoutParams(size, size)
            lp.marginEnd = margin
            dot.layoutParams = lp
            dot.background = ContextCompat.getDrawable(requireContext(), R.drawable.element_round_white)
            dot.backgroundTintList = ColorStateList.valueOf(parsed)
            dot.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                applyColor(parsed, updateText = true)
            }
            container.addView(dot)
        }
        (container.parent as? HorizontalScrollView)?.isHorizontalScrollBarEnabled = false
    }

    companion object {
        /** 常用颜色（与 app 的 ColorTools.colors_material 保持一致的 9 色 + 黑白） */
        val PRESET_COLORS = arrayOf(
            "#ec407a", "#FF9E00", "#7c4dff", "#536dfe", "#2196f3",
            "#26c6da", "#009688", "#7cba59", "#E96D71",
            "#000000", "#FFFFFF"
        )
    }
}
