package com.stupidtree.hitax.utils

/**
 * 励志短句（纯逻辑，不引用 Android API，可在 JVM 中单测）
 *
 * v1.0.5 需求 5：保活通知栏文本可以自己写，也可以自动更新励志短句。
 *
 * 这里做三件事：
 * 1. 准备一份**内置语录**，断网 / 接口挂掉时也能显示（绝不出现空白通知）；
 * 2. 解析两类免费公益 API 的返回：
 *    - 一言（hitokoto）：`{"hitokoto":"...","from":"...","from_who":"..."}`
 *    - 今日诗词（jinrishici）：`{"content":"...","origin":"...","author":"..."}`
 * 3. 决定什么时候该去刷新（[shouldRefresh]），避免频繁请求。
 *
 * 默认接口选的是**无需 key、公开可用**的公益服务：
 * `https://v1.hitokoto.cn/?c=d&c=i&encode=json`（c=d 文学，c=i 诗词）。
 */
object QuoteProvider {

    /** 默认接口（可在设置里改） */
    const val DEFAULT_API = "https://v1.hitokoto.cn/?c=d&c=i&encode=json"

    /** 刷新间隔：6 小时 */
    const val REFRESH_INTERVAL_MS = 6L * 60 * 60 * 1000

    /** 一条短句 */
    data class Quote(val text: String, val source: String, val author: String = "") {
        /** 组装成通知内容用的单行文本 */
        fun oneLine(): String {
            val from = when {
                author.isNotEmpty() && source.isNotEmpty() -> "$author《$source》"
                source.isNotEmpty() -> source
                author.isNotEmpty() -> author
                else -> ""
            }
            return if (from.isEmpty()) text else "$text —— $from"
        }
    }

    /**
     * 内置语录（离线兜底）。
     * 取自常见的励志句子与中大校训相关表述，避免依赖任何在线资源。
     */
    val BUILT_IN: List<Quote> = listOf(
        Quote("博学、审问、慎思、明辨、笃行", "中山大学校训"),
        Quote("今天多学一点，明天就少求人一次", ""),
        Quote("慢慢来，比较快", ""),
        Quote("所有的努力都不会被辜负", ""),
        Quote("不驰于空想，不骛于虚声", ""),
        Quote("把该做的事做完，就是最好的自律", ""),
        Quote("你只管努力，剩下的交给时间", ""),
        Quote("一寸光阴一寸金，寸金难买寸光阴", ""),
        Quote("业精于勤，荒于嬉；行成于思，毁于随", "韩愈", "《进学解》"),
        Quote("少年易老学难成，一寸光阴不可轻", "朱熹", "《偶成》"),
        Quote("纸上得来终觉浅，绝知此事要躬行", "陆游", "《冬夜读书示子聿》"),
        Quote("长风破浪会有时，直挂云帆济沧海", "李白", "《行路难》"),
        Quote("会当凌绝顶，一览众山小", "杜甫", "《望岳》"),
        Quote("天行健，君子以自强不息", "", "《周易》"),
        Quote("路虽远行则将至，事虽难做则必成", "", "《荀子》"),
        Quote("千里之行，始于足下", "老子", "《道德经》"),
        Quote("锲而不舍，金石可镂", "荀子", "《劝学》"),
        Quote("读书破万卷，下笔如有神", "杜甫", "《奉赠韦左丞丈二十二韵》"),
        Quote("山重水复疑无路，柳暗花明又一村", "陆游", "《游山西村》"),
        Quote("沉舟侧畔千帆过，病树前头万木春", "刘禹锡", "《酬乐天扬州初逢席上见赠》"),
        Quote("积土成山，风雨兴焉；积水成渊，蛟龙生焉", "荀子", "《劝学》"),
        Quote("苟日新，日日新，又日新", "", "《礼记·大学》"),
        Quote("知不足者好学，耻下问者自满", "林逋", "《省心录》"),
        Quote("靡不有初，鲜克有终", "", "《诗经·大雅》"),
        Quote("既然选择了远方，便只顾风雨兼程", "汪国真", "《热爱生命》"),
        Quote("心之所向，素履以往", ""),
        Quote("愿你走出半生，归来仍是少年", ""),
        Quote("此刻打盹，你将做梦；此刻学习，你将圆梦", ""),
        Quote("把每一天当作最后一天来努力，把每一节课当作第一节课来认真", ""),
        Quote("早起的人，拥有整个清晨", "")
    )

    /**
     * 解析接口返回
     *
     * @param body HTTP 响应体
     * @return 解析出的短句；无法解析时返回 null（调用方应回退到内置语录）
     */
    fun parse(body: String?): Quote? {
        if (body.isNullOrBlank()) return null
        val t = body.trim()
        if (!t.startsWith("{")) return null
        return try {
            val obj = org.json.JSONObject(t)
            // 一言
            val hitokoto = obj.optString("hitokoto").takeIf { it.isNotBlank() && it != "null" }
            if (hitokoto != null) {
                val from = obj.optString("from").let { if (it == "null") "" else it }.trim()
                val who = obj.optString("from_who").let { if (it == "null") "" else it }.trim()
                return Quote(clean(hitokoto), from, who)
            }
            // 今日诗词
            val content = obj.optString("content").takeIf { it.isNotBlank() && it != "null" }
            if (content != null) {
                val origin = obj.optString("origin").let { if (it == "null") "" else it }.trim()
                val author = obj.optString("author").let { if (it == "null") "" else it }.trim()
                return Quote(clean(content), origin, author)
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun clean(s: String): String =
        s.replace(Regex("\\s+"), " ").trim().trim('"', '“', '”')

    /**
     * 按索引取一条内置语录（用于「立即刷新」在离线时的确定性轮换）
     */
    fun builtInAt(index: Int): Quote {
        if (BUILT_IN.isEmpty()) return Quote("逸仙课表正在后台守护你的提醒", "")
        val i = ((index % BUILT_IN.size) + BUILT_IN.size) % BUILT_IN.size
        return BUILT_IN[i]
    }

    /**
     * 是否到了该刷新的时候。
     * @param lastFetchAt 上次成功刷新时间（0 表示从未）
     * @param intervalMs 间隔，默认 [REFRESH_INTERVAL_MS]
     */
    fun shouldRefresh(lastFetchAt: Long, now: Long, intervalMs: Long = REFRESH_INTERVAL_MS): Boolean {
        if (lastFetchAt <= 0L) return true
        if (intervalMs <= 0L) return true
        return now - lastFetchAt >= intervalMs
    }

    /**
     * 渲染最终要显示在通知里的两行文本。
     *
     * @param titleTemplate 标题。为空则用 [fallbackTitle]
     * @param contentTemplate 内容。为空则用短句
     * @param quote 当前短句，可为 null
     * @param placeholders 额外的 {key} -> value 替换（例如 {date}）
     */
    fun render(
        titleTemplate: String?,
        contentTemplate: String?,
        quote: Quote?,
        fallbackTitle: String,
        fallbackContent: String,
        placeholders: Map<String, String> = emptyMap()
    ): Pair<String, String> {
        val map = HashMap<String, String>()
        map["{quote}"] = quote?.text ?: fallbackContent
        map["{source}"] = quote?.source ?: ""
        map["{author}"] = quote?.author ?: ""
        map["{quote_line}"] = quote?.oneLine() ?: fallbackContent
        map.putAll(placeholders)

        var title = (titleTemplate ?: "").ifBlank { fallbackTitle }
        var content = (contentTemplate ?: "").ifBlank {
            if (quote != null) quote.oneLine() else fallbackContent
        }
        for ((k, v) in map) {
            title = title.replace(k, v)
            content = content.replace(k, v)
        }
        // 清理因占位符为空产生的多余分隔与空格
        content = content.replace(Regex("\\s*——\\s*(?=$)"), "")
            .replace(Regex("\\s{2,}"), " ").trim().trim('—', '-', '·', ' ')
        title = title.replace(Regex("\\s{2,}"), " ").trim()
        if (title.isEmpty()) title = fallbackTitle
        if (content.isEmpty()) content = fallbackContent
        return title to content
    }
}
