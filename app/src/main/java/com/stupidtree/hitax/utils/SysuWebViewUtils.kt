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
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            ws.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cm.setAcceptThirdPartyCookies(webView, true)
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
