package com.stupidtree.hitax.ui.eas.login

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import com.stupidtree.component.data.DataState
import com.stupidtree.hitax.data.repository.EASRepository
import com.stupidtree.hitax.data.source.web.sysu.SysuSession

/**
 * 中大统一身份认证登录的 ViewModel
 *
 * 与 HITSZ 版本不同：中大教务使用「统一身份认证 + 滑块验证码」，
 * 无法用账号密码直接 POST，因此登录在 WebView 中完成，本类负责
 * 用 WebView 中取得的 Cookie 建立教务会话。
 */
class LoginEASViewModel(application: Application) : AndroidViewModel(application) {

    private val easRepository = EASRepository.getInstance(application)

    private val loginController: MutableLiveData<LoginTrigger> = MutableLiveData()

    val loginResultLiveData: LiveData<DataState<Boolean>>
        get() = loginController.switchMap {
            easRepository.loginWithCookies(it.cookies, it.info)
        }

    /** 用 WebView 中获取的 Cookie 登录 */
    fun loginWithCookies(cookies: Map<String, String>, info: SysuSession.Info?) {
        if (cookies.isEmpty()) {
            loginController.value = LoginTrigger.getActioning(emptyMap(), null)
            return
        }
        loginController.value = LoginTrigger.getActioning(cookies, info)
    }

    /** 用粘贴的 Cookie 文本登录 */
    fun loginWithCookieText(text: String) {
        val cookies = SysuSession.parseCookieText(text)
        loginController.value = LoginTrigger.getActioning(cookies, null)
    }
}
