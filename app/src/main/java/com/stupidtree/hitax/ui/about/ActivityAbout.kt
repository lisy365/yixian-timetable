package com.stupidtree.hitax.ui.about

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.view.HapticFeedbackConstants
import android.widget.Toast
import com.stupidtree.hitax.R
import com.stupidtree.hitax.databinding.ActivityAboutBinding
import com.stupidtree.style.base.BaseActivity

/**
 * 关于页
 *
 * 逸仙课表：关于内容改为本地文案。原项目的在线「关于」与「检查更新」依赖 HITSZ 后端，已停用；
 * 「作者的话」取自 strings.xml 的 `yixian_author_note`，作者可直接修改该字符串。
 */
class ActivityAbout : BaseActivity<AboutViewModel, ActivityAboutBinding>() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setWindowParams(true, false, false)
        setToolbarActionBack(binding.toolbar)
    }

    override fun initViews() {
        binding.aboutInfo.text = Html.fromHtml(getString(R.string.yixian_about_info))
        binding.privacyProtocol.setOnClickListener {
            UserAgreementDialog().show(supportFragmentManager, "a")
        }
        // 原「检查更新」按钮改为跳转 GitHub 项目主页
        binding.check.text = getString(R.string.yixian_check_update)
        binding.check.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            Toast.makeText(this, R.string.yixian_update_hint, Toast.LENGTH_SHORT).show()
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.yixian_repo_url)))
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        refresh()
    }

    @SuppressLint("SetTextI18n")
    fun refresh() {
        var packageInfo: PackageInfo? = null
        try {
            packageInfo = packageManager.getPackageInfo(packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
        }
        binding.version.text = getString(R.string.version) + (packageInfo?.versionName ?: "")
    }

    override fun initViewBinding(): ActivityAboutBinding {
        return ActivityAboutBinding.inflate(layoutInflater)
    }

    override fun getViewModelClass(): Class<AboutViewModel> {
        return AboutViewModel::class.java
    }
}
