package com.stupidtree.hitax.ui.crawler

import android.content.Intent
import android.os.Bundle
import com.stupidtree.hitax.R
import com.stupidtree.hitax.databinding.ActivityCrawlerBinding
import com.stupidtree.style.base.BaseActivity

/**
 * 教务信息爬取的**独立页面**外壳（v1.0.7）
 *
 * 主入口已经挪到「小工具 → 教务信息爬取」（内嵌 [CrawlerFragment]，作为二级界面）；
 * 这个 Activity 保留是为了两种场景：
 * 1. 导入课表结束后需要跳到一个**独立**页面跑爬取（避免打断当前导入流程）；
 * 2. 外部（设置面板等）直接以整页形式打开。
 *
 * 真正的界面与逻辑都在 [CrawlerFragment] 里，这里只负责放进容器并接上返回键。
 */
class CrawlerActivity : BaseActivity<CrawlerViewModel, ActivityCrawlerBinding>() {

    override fun getViewModelClass(): Class<CrawlerViewModel> = CrawlerViewModel::class.java

    override fun initViewBinding(): ActivityCrawlerBinding =
        ActivityCrawlerBinding.inflate(layoutInflater)

    override fun initViews() {
        if (supportFragmentManager.findFragmentById(R.id.container) == null) {
            supportFragmentManager.beginTransaction()
                .replace(
                    R.id.container,
                    CrawlerFragment.newInstance(
                        intent.getStringExtra(EXTRA_TERM),
                        intent.getBooleanExtra(EXTRA_AUTO_START, false)
                    )
                )
                .commitAllowingStateLoss()
        }
    }

    companion object {
        const val EXTRA_TERM = "extra_term"
        const val EXTRA_AUTO_START = "extra_auto_start"

        /** 构造一个「导入后自动开始爬取」的 Intent */
        fun autoStartIntent(
            context: android.content.Context,
            termCode: String?
        ): Intent = Intent(context, CrawlerActivity::class.java).apply {
            putExtra(EXTRA_TERM, termCode)
            putExtra(EXTRA_AUTO_START, true)
        }
    }
}
