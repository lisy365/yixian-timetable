package com.stupidtree.hitax.ui.eas.imp

import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.appbar.AppBarLayout
import com.stupidtree.hitax.R
import com.stupidtree.hitax.data.model.eas.TermItem
import com.stupidtree.hitax.data.model.timetable.TimePeriodInDay
import com.stupidtree.hitax.databinding.ActivityEasImportBinding
import com.stupidtree.style.base.BaseListAdapter
import com.stupidtree.component.data.DataState
import com.stupidtree.hitax.data.model.eas.EASToken
import com.stupidtree.hitax.data.source.preference.EasPreferenceSource
import com.stupidtree.hitax.ui.crawler.CrawlerActivity
import com.stupidtree.hitax.ui.eas.EASActivity
import com.stupidtree.hitax.ui.widgets.PopUpCalendarPicker
import com.stupidtree.style.widgets.PopUpCheckableList
import com.stupidtree.style.widgets.PopUpText
import com.stupidtree.hitax.ui.widgets.PopUpTimePeriodPicker
import com.stupidtree.hitax.ui.widgets.WidgetUtils
import com.stupidtree.hitax.utils.AnimationUtils
import com.stupidtree.hitax.utils.ImageUtils.dp2px
import com.stupidtree.hitax.utils.TextTools
import java.util.*


class ImportTimetableActivity :
    EASActivity<ImportTimetableViewModel, ActivityEasImportBinding>() {

    private lateinit var scheduleStructureAdapter: TimetableStructureListAdapter

    companion object {
        /** v1.0.7：记住「导入后是否已经问过要不要爬取」 */
        private const val SP_CRAWL = "yixian_crawl"
        private const val KEY_ASKED = "asked_after_import_v1"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setToolbarActionBack(binding.toolbar)
    }

    override fun initViews() {
        super.initViews()
        bindLiveData()
        initList()
        binding.toolbar.title = ""
        binding.collapse.title = ""
        binding.appbar.addOnOffsetChangedListener(AppBarLayout.OnOffsetChangedListener { appBarLayout, verticalOffset ->
            val scale = 1.0f + verticalOffset / appBarLayout.height.toFloat()
            binding.termPick.translationX =
                (binding.toolbar.contentInsetStartWithNavigation + dp2px(
                    getThis(),
                    8f
                )) * (1 - scale)
            binding.termPick.scaleX = 0.5f * (1 + scale)
            binding.termPick.scaleY = 0.5f * (1 + scale)
            binding.termPick.translationY =
                (binding.termPick.height / 2) * (1 - binding.termPick.scaleY)

            binding.buttonImport.translationY = dp2px(getThis(), 24f) * (1 - scale)
            binding.buttonImport.scaleX = 0.7f + 0.3f * scale
            binding.buttonImport.scaleY = 0.7f + 0.3f * scale
            binding.buttonImport.translationX =
                (binding.buttonImport.width / 2) * (1 - binding.buttonImport.scaleX)

        })
        binding.cardName.isEnabled = false
        binding.termPick.setOnClickListener {
            val names = mutableListOf<String>()
            for (i in viewModel.startGetAllTerms()) {
                names.add(i.name)
            }
            if (names.isEmpty()) {
                return@setOnClickListener
            }
            PopUpCheckableList<TermItem>()
                .setListData(names, viewModel.startGetAllTerms())
                .setTitle(getString(R.string.pick_import_term))
                .setOnConfirmListener(object :
                    PopUpCheckableList.OnConfirmListener<TermItem> {
                    override fun OnConfirm(title: String?, key: TermItem) {
                        viewModel.changeSelectedTerm(key)
                    }
                }).show(supportFragmentManager, "terms")
        }
        binding.buttonImport.setOnClickListener {
            if (viewModel.startImportTimetable()) {
                it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                binding.buttonImport.startAnimation()
            }
        }
        binding.cardDate.onCardClickListener = View.OnClickListener {
            viewModel.startDateLiveData.value?.data?.let {
                PopUpCalendarPicker().setInitValue(it.timeInMillis)
                    .setOnConfirmListener(object : PopUpCalendarPicker.OnConfirmListener {
                        override fun onConfirm(c: Calendar) {
                            viewModel.changeStartDate(c)
                        }
                    }).show(supportFragmentManager, "pick")
            }

        }
        // 中大教务没有「本科/研究生培养方案」之分，该开关隐藏
        binding.stutype.visibility = android.view.View.GONE

    }


    private fun bindLiveData() {
        viewModel.selectedTermLiveData.observe(this) {
            it?.let {
                binding.termText.text = it.name
                binding.cardName.setTitle(it.name)
            }
        }
        viewModel.termsLiveData.observe(this) { data ->
            binding.refresh.isRefreshing = false
            if (data.state == DataState.STATE.SUCCESS) {
                if (!data.data.isNullOrEmpty()) {
                    for (t in data.data!!) {
                        if (t.isCurrent) {
                            viewModel.changeSelectedTerm(t)
                            return@observe
                        }
                    }
                    viewModel.changeSelectedTerm(data.data!![0])
                }
            } else {
                binding.termText.setText(R.string.load_failed)
            }
        }
        viewModel.startDateLiveData.observe(this) {
            if ((it.state == DataState.STATE.SUCCESS || it.state == DataState.STATE.SPECIAL) && it.data != null) {
                binding.cardDate.setTitle(
                    TextTools.getNormalDateText(
                        getThis(),
                        it.data!!
                    )
                )
            } else {
                binding.cardDate.setTitle(R.string.no_valid_date)
            }
        }
        viewModel.scheduleStructureLiveData.observe(this) {
            AnimationUtils.enableLoadingButton(binding.buttonImport, !it.data.isNullOrEmpty())
            if (it.state == DataState.STATE.SUCCESS) {
                it.data?.let { data ->
                    scheduleStructureAdapter.notifyItemChangedSmooth(data)
                }
            }
        }
        viewModel.isUndergraduateLiveData.observe(this){
            binding.stutype.text = if(it) getString(R.string.undergrad_structure) else
                getString(R.string.postgrad_structure)
        }
        viewModel.importTimetableResultLiveData.observe(this) {
            AnimationUtils.loadingButtonDone(
                binding.buttonImport, it.state == DataState.STATE.SUCCESS,
                successStr = R.string.import_success, failStr = R.string.import_failed
            )
            //通知小组件
            WidgetUtils.sendRefreshToAll(this)
            // v1.0.7 需求 9：课表导入成功后，顺手把教务信息也爬一份到本机。
            // 这里只提示一次（用 SharedPreferences 记住用户的选择），避免每次导入都打扰。
            if (it.state == DataState.STATE.SUCCESS) {
                askCrawlAfterImport()
            }
        }
    }

    /**
     * 导入完成后的「顺带爬一次」提示（v1.0.7 需求 9）
     *
     * 用 `SP("yixian_crawl")` 里的标记记住用户已经做过选择，之后不再打扰；
     * 用户答「开始爬取」就跳到爬取页并自动开始。
     */
    private fun askCrawlAfterImport() {
        val sp = getSharedPreferences(SP_CRAWL, MODE_PRIVATE)
        if (sp.getBoolean(KEY_ASKED, false)) return
        sp.edit().putBoolean(KEY_ASKED, true).apply()
        val termCode = viewModel.selectedTermLiveData.value?.getCode()
        PopUpText()
            .setTitle(R.string.crawler_after_import_title)
            .setText(getString(R.string.crawler_after_import_msg))
            .setDialogCancelable(true)
            .setOnConfirmListener(object : PopUpText.OnConfirmListener {
                override fun OnConfirm() {
                    startActivity(CrawlerActivity.autoStartIntent(getThis(), termCode))
                }
            })
            .show(supportFragmentManager, "crawl_after_import")
    }

    /**
     * 初始化课表结构列表
     */
    private fun initList() {
        scheduleStructureAdapter = TimetableStructureListAdapter(getThis(), mutableListOf())
        binding.scheduleStructure.adapter = scheduleStructureAdapter
        binding.scheduleStructure.layoutManager = LinearLayoutManager(getThis())
        binding.refresh.setColorSchemeColors(getColorPrimary())
        binding.refresh.setOnRefreshListener {
            viewModel.startRefreshTerms()
        }
        scheduleStructureAdapter.setOnItemClickListener(object :
            BaseListAdapter.OnItemClickListener<TimePeriodInDay> {
            override fun onItemClick(data: TimePeriodInDay?, card: View?, position: Int) {
                if (data == null) return
                PopUpTimePeriodPicker().setInitialValue(data.from, data.to)
                    .setDialogTitle(R.string.pick_time_period)
                    .setOnDialogConformListener(object :
                        PopUpTimePeriodPicker.OnDialogConformListener {
                        override fun onClick(
                            timePeriodInDay: TimePeriodInDay
                        ) {
                            viewModel.setStructureData(timePeriodInDay, position)
                        }

                    }).show(supportFragmentManager, "pick")
            }

        })
    }


    override fun initViewBinding(): ActivityEasImportBinding {
        return ActivityEasImportBinding.inflate(layoutInflater)
    }

    override fun onLoginCheckSuccess(retry:Boolean) {
        super.onLoginCheckSuccess(retry)
        // 中大的课表结构固定（按节），无需选择培养方案
        viewModel.changeIsUndergraduate(true)
    }



    override fun refresh() {
        binding.buttonImport.background = ContextCompat.getDrawable(
            getThis(),
            R.drawable.element_rounded_button_bg_grey
        )
        binding.buttonImport.isEnabled = false
        binding.refresh.isRefreshing = true
        viewModel.startRefreshTerms()
    }

    override fun getViewModelClass(): Class<ImportTimetableViewModel> {
        return ImportTimetableViewModel::class.java
    }
}