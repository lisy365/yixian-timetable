package com.stupidtree.hitax.ui.eas.login

import android.annotation.SuppressLint
import android.app.Activity
import android.content.DialogInterface
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.stupidtree.hitax.R
import com.stupidtree.hitax.data.source.web.sysu.SysuApi
import com.stupidtree.hitax.data.source.web.sysu.SysuSession
import com.stupidtree.hitax.databinding.DialogBottomEasLoginBinding
import com.stupidtree.component.data.DataState
import com.stupidtree.hitax.utils.ImageUtils
import com.stupidtree.hitax.utils.SysuWebViewUtils
import com.stupidtree.style.widgets.TransparentModeledBottomSheetDialog

/**
 * 中山大学统一身份认证登录弹窗
 *
 * 交互与原项目保持一致（底部弹窗 + 圆形加载按钮），
 * 但登录方式改为在 WebView 内完成统一身份认证（含滑块验证），
 * 登录成功后自动提取 Cookie 建立教务会话。
 */
class PopUpLoginEAS :
    TransparentModeledBottomSheetDialog<LoginEASViewModel, DialogBottomEasLoginBinding>() {

    var lock = false
    var onResponseListener: OnResponseListener? = null

    private var info: SysuSession.Info? = null
    private var polling = false
    private var submitted = false

    override fun getViewModelClass(): Class<LoginEASViewModel> {
        return LoginEASViewModel::class.java
    }

    override fun getLayoutId(): Int = R.layout.dialog_bottom_eas_login

    override fun initViewBinding(v: View): DialogBottomEasLoginBinding {
        return DialogBottomEasLoginBinding.bind(v)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun initViews(view: View) {
        val b = binding ?: return

        // 登录结果
        viewModel.loginResultLiveData.observe(this) { state ->
            polling = false
            if (state.state == DataState.STATE.SUCCESS) {
                hapticFeedback()
                val bitmap = ImageUtils.getResourceBitmap(requireContext(), R.drawable.ic_baseline_done_24)
                b.buttonLogin.doneLoadingAnimation(getColorPrimary(), bitmap)
                onResponseListener?.onSuccess(this)
            } else {
                submitted = false
                val bitmap = ImageUtils.getResourceBitmap(requireContext(), R.drawable.ic_baseline_error_24)
                b.buttonLogin.doneLoadingAnimation(getColorPrimary(), bitmap)
                b.buttonLogin.postDelayed({ b.buttonLogin.revertAnimation() }, 600)
                android.widget.Toast.makeText(
                    requireContext(),
                    state.message ?: getString(R.string.sysu_login_failed),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                onResponseListener?.onFailed(this)
            }
        }

        // WebView 配置（统一由 SysuWebViewUtils 处理 JS / Cookie / SSL 白名单）
        b.webview.settings.userAgentString = SysuApi.UA
        SysuWebViewUtils.configure(b.webview)

        b.webview.webViewClient = object : SysuWebViewUtils.SysuWebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean {
                handleUrl(url)
                return false
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest?): Boolean {
                handleUrl(request?.url?.toString())
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                handleUrl(url)
                b.progress.visibility = View.GONE
                if (!submitted) startPolling()
            }
        }
        b.webview.loadUrl(SysuApi.LOGIN_URL)

        // 手动确认按钮
        b.buttonLogin.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            b.buttonLogin.startAnimation()
            submitted = true
            checkCookieOnce(true)
        }

        // Cookie 粘贴登录（兜底方案）
        b.cookieEntry.setOnClickListener {
            b.cookieGroup.visibility =
                if (b.cookieGroup.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        // 弹窗不再响应拖拽（避免和 WebView 滚动抢手势），因此提供显式关闭入口
        b.dismiss.setOnClickListener { dismiss() }
        // 长按标题：把当前 WebView 的 Cookie 复制到剪贴板（排查登录问题用）
        b.title.setOnLongClickListener {
            val cookies = readJwxtCookies()
            val text = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            val cm = requireContext()
                .getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("cookie", text))
            android.widget.Toast.makeText(
                requireContext(),
                if (cookies.isEmpty()) "当前没有 Cookie" else "Cookie 已复制（${cookies.size} 项）",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            true
        }
        b.cookieConfirm.setOnClickListener {
            val text = b.cookieInput.text?.toString() ?: ""
            if (text.isBlank()) return@setOnClickListener
            submitted = true
            b.buttonLogin.startAnimation()
            viewModel.loginWithCookieText(text)
        }
    }

    private fun hapticFeedback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            binding?.buttonLogin?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        } else {
            binding?.buttonLogin?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        }
    }

    /**
     * 处理 WebView 跳转：从回调 URL 中提取学生信息，并在进入主应用时提交登录
     */
    private fun handleUrl(url: String?) {
        if (url.isNullOrEmpty()) return
        val parsed = SysuSession.parseFromUrl(url)
        if (parsed != null) {
            info = (info ?: SysuSession.Info()).merge(parsed)
        }
        if (!submitted && SysuSession.isLoggedIn(url, info)) {
            submitted = true
            binding?.buttonLogin?.startAnimation()
            checkCookieOnce(true)
        }
    }

    override fun onStart() {
        super.onStart()
        // 让底部弹窗接近全屏，便于操作登录页
        val dialog = dialog ?: return
        val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?: return
        val behavior = BottomSheetBehavior.from(sheet)
        val height = (resources.displayMetrics.heightPixels * 0.92f).toInt()
        behavior.peekHeight = height
        sheet.layoutParams.height = height
        sheet.requestLayout()
        // 关键：弹窗不参与拖拽，竖直手势全部交给 WebView 滚动
        // （BottomSheetBehavior 会在 onInterceptTouchEvent 里抢走滑动，导致登录页滑不动）
        behavior.isDraggable = false
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    /**
     * 轮询读取 WebView Cookie，验证会话是否已建立
     */
    private fun startPolling() {
        if (polling || submitted) return
        polling = true
        val startAt = System.currentTimeMillis()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                if (!isAdded || submitted) return
                val cookies = readJwxtCookies()
                if (hasSessionCookie(cookies)) {
                    submitted = true
                    binding?.buttonLogin?.startAnimation()
                    viewModel.loginWithCookies(cookies, info)
                    return
                }
                if (System.currentTimeMillis() - startAt < 5 * 60 * 1000L) {
                    handler.postDelayed(this, 1500)
                } else {
                    polling = false
                }
            }
        }
        handler.postDelayed(runnable, 1500)
    }

    /** 判断 Cookie 里是否已经出现教务会话标识 */
    private fun hasSessionCookie(cookies: Map<String, String>): Boolean {
        if (cookies.isEmpty()) return false
        return cookies.keys.any {
            it.equals("SESSION", true) || it.contains("SESSIONID", true) || it.equals("JSESSIONID", true)
        }
    }

    /**
     * 主动读取一次 Cookie（点「已完成登录」或跳转命中时）。
     * CookieManager 偶发滞后，这里做有限次重试，避免误报失败。
     */
    private fun checkCookieOnce(enableRetry: Boolean, attempt: Int = 0) {
        val cookies = readJwxtCookies()
        if (cookies.isEmpty() || !hasSessionCookie(cookies)) {
            if (enableRetry && attempt < 6) {
                binding?.webview?.postDelayed({
                    if (isAdded && submitted) checkCookieOnce(enableRetry, attempt + 1)
                }, 400)
                return
            }
            submitted = false
            binding?.buttonLogin?.revertAnimation()
            android.widget.Toast.makeText(
                requireContext(), R.string.sysu_login_failed, android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        viewModel.loginWithCookies(cookies, info)
    }

    private fun readJwxtCookies(): Map<String, String> {
        val result = HashMap<String, String>()
        val cm = CookieManager.getInstance()
        for (url in arrayOf("https://jwxt.sysu.edu.cn", "https://jwxt.sysu.edu.cn/jwxt/")) {
            val raw = try {
                cm.getCookie(url)
            } catch (e: Exception) {
                null
            } ?: continue
            result.putAll(SysuSession.parseCookieText(raw))
        }
        return result
    }

    interface OnResponseListener {
        fun onSuccess(window: PopUpLoginEAS)
        fun onFailed(window: PopUpLoginEAS)
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        if (lock) {
            if (context is Activity) {
                (context as Activity).finish()
            }
        }
    }

    override fun onDestroyView() {
        try {
            binding?.webview?.stopLoading()
            binding?.webview?.destroy()
        } catch (e: Exception) {
            // ignore
        }
        super.onDestroyView()
    }
}
