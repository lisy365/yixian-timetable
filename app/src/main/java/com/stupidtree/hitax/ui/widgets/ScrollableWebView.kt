package com.stupidtree.hitax.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView

/**
 * 可独立手势滚动、且能在弹窗里正常唤起软键盘的 WebView
 *
 * 用在 BottomSheetDialog 内（例如统一身份认证登录页），解决两个问题：
 *
 * 1. **滚动被抢走**：底部弹窗的
 *    [com.google.android.material.bottomsheet.BottomSheetBehavior] 会在
 *    `onInterceptTouchEvent` 里把竖直滑动当成「拖拽弹窗」抢走，
 *    导致 WebView 里的页面根本不跟随手指滚动。
 *    这里在手指按下（ACTION_DOWN）时向所有祖先请求「不要拦截本次手势」，
 *    抬手/取消时再放开，使页面滚动与弹窗拖拽互不干扰。
 *
 * 2. **软键盘弹不出来**：`InputMethodManager.showSoftInput(view, …)` 只有在
 *    `mServedView === view`（也就是这个 WebView 自己是窗口内获得焦点的 View）时才生效，
 *    否则直接返回 false —— 表现就是「点了输入框，键盘不弹」。
 *    在 Dialog / BottomSheet 里，初始焦点常常落在弹窗里的按钮上，
 *    WebView 并不是焦点 View，所以 Chromium 内部的 `showSoftKeyboard()` 会被系统丢掉。
 *    这里在手指按下时先用 [requestFocusFromTouch] 把焦点抢到 WebView 上，
 *    保证后续 Chromium 的 IME 请求能被系统接受。
 */
class ScrollableWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    init {
        // 保证 WebView 能成为窗口内的焦点 View（部分 ROM 的 Dialog 默认不会给 WebView 焦点）
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        when (event?.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestParentDisallowIntercept(true)
                grabImeFocus()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                requestParentDisallowIntercept(false)
                // ACTION_UP 之后再补一次：少数 ROM 在 DOWN 时还没完成焦点切换
                grabImeFocus()
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * 把窗口焦点抢到本 WebView 上，使其成为输入法的目标 View。
     * 幂等：已经是焦点 View 时不做任何事，避免打断 WebView 内部的输入状态。
     */
    fun grabImeFocus() {
        if (isFocused) return
        try {
            // 优先用普通 requestFocus：只要本 View 是 focusableInTouchMode（构造函数里已设），
            // 在触摸模式下就能拿到焦点，而且不会把整个窗口踢出 touch mode
            // （踢出去会让所有按钮出现焦点描边）。
            if (requestFocus(FOCUS_DOWN)) return
            // 兜底：个别 ROM 的 Dialog 里 WebView 不在焦点搜索链路上，只能强制抢一次
            requestFocusFromTouch()
        } catch (e: Exception) {
            // 极端情况下（View 未 attached）忽略即可
        }
    }

    private fun requestParentDisallowIntercept(disallow: Boolean) {
        var parent = parent
        while (parent is android.view.ViewGroup) {
            parent.requestDisallowInterceptTouchEvent(disallow)
            parent = parent.parent
        }
    }
}
