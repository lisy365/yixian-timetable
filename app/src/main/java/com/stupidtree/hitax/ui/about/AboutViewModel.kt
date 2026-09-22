package com.stupidtree.hitax.ui.about

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.stupidtree.hitax.data.repository.EASRepository

/**
 * 关于页的 ViewModel
 *
 * 逸仙课表：关于页文案已改为本地资源（原项目的在线「关于 / 检查更新」依赖 HITSZ 后端，已停用），
 * 这里只保留教务登录态查询。
 */
class AboutViewModel(application: Application) : AndroidViewModel(application) {

    /** 当前教务登录态：是否已登录中大教务 */
    fun isEasLoggedIn(): Boolean {
        return EASRepository.getInstance(getApplication()).getEasToken().isLogin()
    }
}
