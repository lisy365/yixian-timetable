package com.stupidtree.hitax.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.webkit.WebView

/**
 * 可独立手势滚动的 WebView
 *
 * 用在 BottomSheetDialog 内（例如统一身份认证登录页）：底部弹窗的
 * [com.google.android.material.bottomsheet.BottomSheetBehavior] 会在
 * `onInterceptTouchEvent` 里把竖直滑动当成「拖拽弹窗」抢走，
 * 导致 WebView 里的页面根本不跟随手指滚动。
 *
 * 这里在手指按下（ACTION_DOWN）时向所有祖先请求「不要拦截本次手势」，
 * 抬手/取消时再放开，使页面滚动与弹窗拖拽互不干扰。
 */
class ScrollableWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        when (event?.actionMasked) {
            MotionEvent.ACTION_DOWN -> requestParentDisallowIntercept(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                requestParentDisallowIntercept(false)
        }
        return super.onTouchEvent(event)
    }

    private fun requestParentDisallowIntercept(disallow: Boolean) {
        var parent = parent
        while (parent is android.view.ViewGroup) {
            parent.requestDisallowInterceptTouchEvent(disallow)
            parent = parent.parent
        }
    }
}
