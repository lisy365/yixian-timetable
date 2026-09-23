package com.stupidtree.hitax.utils

import android.annotation.SuppressLint
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * 统一身份认证 WebView 的工具方法
 *
 * 中大教务（jwxt.sysu.edu.cn）与统一身份认证（cas.sysu.edu.cn）均使用学校 CA 签发的证书，
 * 部分定制 ROM 的 WebView 根证书列表较旧，可能拦截证书；为不阻塞登录，
 * 仅对 *.sysu.edu.cn 域名放行 SSL 错误（其它域名一律不放行）。
 */
object SysuWebViewUtils {

    const val DOMAIN_SUFFIX = "sysu.edu.cn"

    /**
     * 输入框获得焦点后自动把它滚动到可视区域中部。
     *
     * 为什么需要：BottomSheetDialog 会把窗口设成 edge-to-edge
     * （`WindowCompat.setDecorFitsSystemWindows(window, false)`），
     * 于是 `adjustResize` / `adjustPan` 都不再生效，软键盘会直接盖在页面上。
     * 登录页的账号/密码框如果位于下半屏，输完就看不见了。
     * 用脚本在 focusin / resize 时把当前输入框滚到中间，避免被键盘挡住。
     */
    val FOCUS_SCROLL_JS: String = """
        (function () {
          if (window.__yxFocusScrollInstalled) { return; }
          window.__yxFocusScrollInstalled = true;
          var active = null;
          function isEditable(el) {
            if (!el) { return false; }
            var tag = (el.tagName || '').toUpperCase();
            return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable === true;
          }
          function reveal(el) {
            if (!el || !el.scrollIntoView) { return; }
            try {
              el.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'smooth' });
            } catch (e) {
              try { el.scrollIntoView(); } catch (e2) { /* ignore */ }
            }
          }
          document.addEventListener('focusin', function (e) {
            var t = e.target;
            if (!isEditable(t)) { return; }
            active = t;
            // 等软键盘动画基本结束再滚动，否则会被 resize 布局覆盖
            setTimeout(function () { reveal(t); }, 320);
          }, true);
          window.addEventListener('resize', function () {
            if (!active) { return; }
            setTimeout(function () { reveal(active); }, 60);
          });
        })();
    """.trimIndent()

    fun isSysuHost(url: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        return try {
            val host = java.net.URI(url).host ?: return false
            host == DOMAIN_SUFFIX || host.endsWith(".$DOMAIN_SUFFIX")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 是否放行该 SSL 错误：仅学校域名放行
     */
    fun shouldProceedSslError(url: String?): Boolean = isSysuHost(url)

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(webView: WebView) {
        val ws: WebSettings = webView.settings
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true
        ws.databaseEnabled = true
        ws.useWideViewPort = true
        ws.loadWithOverviewMode = true
        ws.builtInZoomControls = false
        ws.setSupportZoom(true)
        ws.allowFileAccess = false
        ws.allowContentAccess = false
        // 登录页需要能在弹窗内获得焦点，否则 InputMethodManager 会拒绝弹出软键盘
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            ws.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cm.setAcceptThirdPartyCookies(webView, true)
        }
    }

    /** 注入「输入框自动滚入可视区」脚本（页面加载完成后调用，可重复调用） */
    fun installFocusScroll(webView: WebView) {
        try {
            webView.evaluateJavascript(FOCUS_SCROLL_JS, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** 统一的 WebViewClient：只处理 SSL 白名单，页面逻辑由调用方接管 */
    abstract class SysuWebViewClient : WebViewClient() {
        override fun onReceivedSslError(
            view: WebView?,
            handler: SslErrorHandler?,
            error: SslError?
        ) {
            if (shouldProceedSslError(error?.url)) {
                handler?.proceed()
            } else {
                handler?.cancel()
            }
        }
    }
}
