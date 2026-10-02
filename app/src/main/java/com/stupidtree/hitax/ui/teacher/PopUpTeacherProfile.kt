package com.stupidtree.hitax.ui.teacher

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.view.ContextThemeWrapper
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.stupidtree.hitax.R
import com.stupidtree.hitax.data.repository.CrawlerRepository
import com.stupidtree.style.ThemeTools

/**
 * 任课教师个人简介弹窗（v1.0.7 需求 2）
 *
 * 从「科目详情」里点任课教师即可弹出。
 * 内容全部来自**爬取到本机的信息**（[CrawlerRepository.buildTeacherProfile]）：
 * 任教课程（课表聚合）、学院、职称、简介片段，以及该教师课程的教学大纲入口。
 *
 * 爬取结果里没有的资料会显示一句明确的提示，并给出「去学院官网查看」的入口，
 * 而不是假装有数据。
 */
class PopUpTeacherProfile : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_NAME = "teacher_name"

        /** 从科目详情里这样调用即可 */
        fun newInstance(teacherName: String): PopUpTeacherProfile {
            val f = PopUpTeacherProfile()
            f.arguments = Bundle().apply { putString(ARG_NAME, teacherName) }
            return f
        }
    }

    private var built = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        retainInstance = true
        setStyle(STYLE_NORMAL, R.style.TransparentBottomSheetDialogTheme)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = ContextThemeWrapper(requireContext(), ThemeTools.themeOf(requireContext()))
        val root = LayoutInflater.from(ctx)
            .inflate(R.layout.dialog_bottom_teacher_profile, container, false)
        val name = arguments?.getString(ARG_NAME).orEmpty()
        val b = Binding(root)

        b.name.text = name
        b.avatarText.text = name.take(1).ifEmpty { "师" }
        b.close.setOnClickListener { dismissAllowingStateLoss() }
        b.intro.visibility = View.GONE
        b.introLabel.visibility = View.GONE
        b.syllabusLabel.visibility = View.GONE
        b.official.visibility = View.GONE
        b.hint.text = getString(R.string.teacher_profile_loading)

        // 读本地爬取结果，放在子线程
        Thread {
            val profile = try {
                CrawlerRepository.buildTeacherProfile(requireContext().applicationContext, name)
            } catch (e: Exception) {
                null
            }
            activity?.runOnUiThread {
                if (isAdded) render(b, name, profile)
            }
        }.start()
        built = true
        return root
    }

    private fun render(b: Binding, name: String, profile: CrawlerRepository.TeacherProfile?) {
        if (profile == null) {
            b.hint.text = getString(R.string.teacher_profile_no_info)
            return
        }
        // 副标题：职称 · 学院
        val parts = mutableListOf<String>()
        profile.title?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        profile.faculty?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        b.subtitle.text = parts.joinToString(" · ").ifEmpty {
            getString(R.string.teacher_profile_no_info)
        }

        // 任教课程
        b.coursesBox.removeAllViews()
        if (profile.courses.isEmpty()) {
            b.coursesBox.addView(chip(getString(R.string.teacher_profile_no_course)))
        } else {
            for (c in profile.courses) b.coursesBox.addView(chip(c))
        }

        // 简介
        if (!profile.intro.isNullOrBlank()) {
            b.introLabel.visibility = View.VISIBLE
            b.intro.visibility = View.VISIBLE
            b.intro.text = profile.intro
        }

        // 关联大纲
        if (profile.syllabi.isNotEmpty()) {
            b.syllabusLabel.visibility = View.VISIBLE
            b.syllabusBox.removeAllViews()
            for (s in profile.syllabi.take(6)) {
                b.syllabusBox.addView(chip(s.name))
            }
        }

        // 提示 + 官网入口
        b.hint.text = if (profile.hasCrawledInfo) {
            getString(R.string.teacher_profile_source_hint)
        } else {
            getString(R.string.teacher_profile_no_info)
        }
        val url = profile.officialUrl
        if (!url.isNullOrBlank()) {
            b.official.visibility = View.VISIBLE
            b.official.text = getString(R.string.teacher_profile_official_named, hostOf(url))
            b.official.setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), url, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun hostOf(url: String): String =
        try {
            java.net.URI(url).host ?: url
        } catch (e: Exception) {
            url
        }

    /** 课程 / 大纲用的小圆角标签 */
    private fun chip(text: String): TextView {
        val tv = TextView(requireContext())
        tv.text = text
        tv.textSize = 13f
        tv.gravity = android.view.Gravity.CENTER_VERTICAL
        tv.setPadding(dp(12), dp(9), dp(12), dp(9))
        tv.setBackgroundResource(R.drawable.element_rounded_bar_grey_light_24)
        tv.setTextColor(resolvePrimary())
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(6)
        tv.layoutParams = lp
        return tv
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun resolvePrimary(): Int {
        val tv = android.util.TypedValue()
        requireContext().theme.resolveAttribute(R.attr.colorPrimary, tv, true)
        return if (tv.data != 0) tv.data else 0xFF00693E.toInt()
    }

    private class Binding(root: View) {
        val name: TextView = root.findViewById(R.id.name)
        val subtitle: TextView = root.findViewById(R.id.subtitle)
        val avatarText: TextView = root.findViewById(R.id.avatar_text)
        val close: View = root.findViewById(R.id.close)
        val coursesBox: LinearLayout = root.findViewById(R.id.courses_box)
        val coursesLabel: TextView = root.findViewById(R.id.courses_label)
        val intro: TextView = root.findViewById(R.id.intro)
        val introLabel: TextView = root.findViewById(R.id.intro_label)
        val syllabusBox: LinearLayout = root.findViewById(R.id.syllabus_box)
        val syllabusLabel: TextView = root.findViewById(R.id.syllabus_label)
        val hint: TextView = root.findViewById(R.id.hint)
        val official: TextView = root.findViewById(R.id.official)
    }
}
