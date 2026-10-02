package com.stupidtree.hitax.ui.tools

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

/**
 * 工具箱 ViewModel（v1.0.5 需求 4）
 *
 * 目前只需要记住「当前内嵌的是哪个工具」，
 * 这样旋屏 / 重建后仍停留在同一个工具页，而不是弹回列表。
 */
class ToolboxViewModel : ViewModel() {

    /** 当前内嵌工具 id；null 表示显示工具列表 */
    val embeddedToolId = MutableLiveData<String?>(null)

    fun refresh() {
        // 预留：后续工具需要在进入时刷新数据（例如爬虫的已保存数量）
    }
}
