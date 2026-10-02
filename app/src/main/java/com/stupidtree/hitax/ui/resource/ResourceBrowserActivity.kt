package com.stupidtree.hitax.ui.resource

import com.stupidtree.hitax.R
import com.stupidtree.hitax.databinding.ActivityResourceBrowserBinding
import com.stupidtree.style.base.BaseActivity

/**
 * 爬取资料浏览器的独立页面外壳（v1.0.7）
 *
 * 界面本体在 [ResourceBrowserFragment]（小工具板块会内嵌同一个 Fragment）。
 * 这个 Activity 用于从别处「整页打开」，例如设置面板里的「我的教学资料」。
 */
class ResourceBrowserActivity :
    BaseActivity<ResourceBrowserViewModel, ActivityResourceBrowserBinding>() {

    override fun getViewModelClass(): Class<ResourceBrowserViewModel> = ResourceBrowserViewModel::class.java

    override fun initViewBinding(): ActivityResourceBrowserBinding =
        ActivityResourceBrowserBinding.inflate(layoutInflater)

    override fun initViews() {
        // 界面本体在 Fragment 里（自带顶栏），Activity 只提供容器与返回
        if (supportFragmentManager.findFragmentById(R.id.container) == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, ResourceBrowserFragment())
                .commitAllowingStateLoss()
        }
    }
}
