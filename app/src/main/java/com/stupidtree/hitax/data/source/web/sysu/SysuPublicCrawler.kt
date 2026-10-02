package com.stupidtree.hitax.data.source.web.sysu

import android.text.TextUtils
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * 校级公开站点爬虫（v1.0.5 需求 3 的补充：把「sysu 各学院」也纳入爬取范围）
 *
 * 与 [SysuCrawler]（走 jwxt 登录会话）不同，这里的站点都是**公开可访问**的，
 * 不需要 Cookie，因此可以独立运行、也更容易在教务接口未开放时拿到有用信息：
 *
 * - **教务部 `jwb.sysu.edu.cn`**：抓「作息时间」「校历」页脚信息、通知通告列表 ——
 *   作息表的权威来源就是这里，抓下来存本地便于核对。
 * - **各学院官网**：把课程/教师相关的公开栏目（本科生教务、通知公告）的标题列表抓下来，
 *   形成一份「本学院教学通知索引」，方便查教学大纲、开课变动这类信息。
 *
 * 设计取舍：
 * - 站点用 **HTTP/HTTPS 直接拿页面文本**，不做全站递归爬取（避免给学校服务器造成压力），
 *   只抓每个站点 1~2 个最有价值的栏目页，且失败就跳过、如实记录日志。
 * - 学院域名无法穷举，内置一份**常用学院站点表**，用户也可以自己加 URL（见设置/调用方传参）。
 */
class SysuPublicCrawler(
    private val logger: (String) -> Unit = {}
) {

    /** 待保存内容 */
    class PendingSave(
        val category: String,
        val name: String,
        val content: String,
        val summary: String,
        val sourceUrl: String?
    )

    /** 一个公开站点 */
    data class Site(
        val id: String,
        val name: String,
        val baseUrl: String,
        /** 要抓的栏目路径（相对 baseUrl） */
        val paths: List<String>,
        val category: String = CATEGORY_COLLEGE
    )

    companion object {
        const val CATEGORY_COLLEGE = "college"
        const val CATEGORY_SCHOOL = "school"

        /** 教务部（作息时间 / 校历 / 通知的权威来源） */
        val JWB = Site(
            id = "jwb",
            name = "中山大学教务部",
            baseUrl = "https://jwb.sysu.edu.cn",
            paths = listOf("/", "/school-calendar", "/newscenter/notice"),
            category = CATEGORY_SCHOOL
        )

        /**
         * 常用学院 / 校区站点。
         * 域名与栏目路径取自各学院官网的公开入口；抓不到会在日志里说明，不影响其它站点。
         */
        val COLLEGES: List<Site> = listOf(
            Site("cse", "计算机学院", "https://cse.sysu.edu.cn", listOf("/", "/teach", "/news")),
            Site("math", "数学学院", "https://math.sysu.edu.cn", listOf("/", "/teach")),
            Site("phys", "物理学院", "https://spe.sysu.edu.cn", listOf("/", "/teach")),
            Site("chem", "化学学院", "https://ce.sysu.edu.cn", listOf("/", "/teach")),
            Site("lingnan", "岭南学院", "https://lingnan.sysu.edu.cn", listOf("/", "/undergraduateprogram")),
            Site("isbf", "国际金融学院", "https://isbf.sysu.edu.cn", listOf("/", "/teach")),
            Site("sese", "电子与信息工程学院", "https://sese.sysu.edu.cn", listOf("/", "/teach")),
            Site("saa", "航空航天学院", "https://saa.sysu.edu.cn", listOf("/", "/teach")),
            Site("stm", "旅游学院", "https://stm.sysu.edu.cn", listOf("/", "/teach/undergraduate")),
            Site("civil", "土木工程学院", "https://civil.sysu.edu.cn", listOf("/", "/teach")),
            Site("szmed", "深圳校区 · 医学院", "https://szmed.sysu.edu.cn", listOf("/", "/teach"))
        )

        /**
         * 常用的教务检索入口（UI 里作为「在校内系统里检索」用，不需要爬）
         */
        val SEARCH_TEMPLATES: List<Triple<String, String, Boolean>> = listOf(
            // 名称, URL 模板（{} 为关键词占位）, 是否需要登录后的会话
            Triple("教务通知检索", "https://jwb.sysu.edu.cn/search?keys={}", false),
            Triple("教务系统 · 课程信息", "https://jwxt.sysu.edu.cn/jwxt/", true),
            Triple("本科教学信息平台", "https://jwb.sysu.edu.cn/portal", true),
            Triple("图书馆 · 课程参考书", "https://library.sysu.edu.cn/search/node?keys={}", false)
        )

        /** 默认要抓的站点：教务部 + 全部内置学院 */
        fun defaultSites(): List<Site> = listOf(JWB) + COLLEGES
    }

    private var lastError: String? = null

    fun log(msg: String) = logger(msg)

    /**
     * 抓取全部站点
     *
     * @param sites 要抓的站点，默认 [defaultSites]
     * @param onSiteProgress 每个站点结束后的回调（站点, 抓到的页数）
     */
    fun crawl(
        sites: List<Site> = defaultSites(),
        onSiteProgress: (Site, Int) -> Unit = { _, _ -> }
    ): List<PendingSave> {
        val out = mutableListOf<PendingSave>()
        log("=== 开始抓取校级 / 学院公开站点（共 ${sites.size} 个）===")
        var okSites = 0
        for (site in sites) {
            var got = 0
            val pages = JSONArray()
            for (p in site.paths) {
                val url = site.baseUrl + p
                val html = fetch(url)
                if (html == null) {
                    log("  · ${site.name} $p 不可达（${lastError ?: "无响应"}）")
                    continue
                }
                val doc = Jsoup.parse(html)
                val title = doc.title().ifBlank { site.name }
                val text = doc.body()?.text()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
                if (text.length < 30) {
                    log("  · ${site.name} $p 内容过短，跳过")
                    continue
                }
                val page = JSONObject()
                page.put("url", url)
                page.put("title", title)
                page.put("textLength", text.length)
                page.put("text", text.take(20000))
                // 顺带把该页里的链接标题抽出来，形成可读的栏目索引
                page.put("links", extractLinks(doc, site.baseUrl))
                pages.put(page)
                log("  · ${site.name} $p 抓到 ${text.length} 字（$title）")
                got++
            }
            if (got > 0) {
                okSites++
                val payload = JSONObject()
                payload.put("site", site.name)
                payload.put("baseUrl", site.baseUrl)
                payload.put("fetchedAt", System.currentTimeMillis())
                payload.put("pages", pages)
                out.add(
                    PendingSave(
                        category = site.category,
                        name = "${site.name}-公开栏目",
                        content = payload.toString(),
                        summary = "抓取 $got 个页面",
                        sourceUrl = site.baseUrl
                    )
                )
            }
            onSiteProgress(site, got)
        }
        log("=== 公开站点抓取结束：${okSites}/${sites.size} 个站点有内容 ===")
        return out
    }

    /** 抽取页面里的站内链接（标题 + 绝对地址），最多 60 条 */
    private fun extractLinks(doc: org.jsoup.nodes.Document, baseUrl: String): JSONArray {
        val arr = JSONArray()
        val seen = HashSet<String>()
        for (a in doc.select("a[href]")) {
            if (arr.length() >= 60) break
            val href = a.attr("href").trim()
            val text = a.text().trim()
            if (text.length < 4 || href.isEmpty()) continue
            if (href.startsWith("#") || href.startsWith("javascript")) continue
            val abs = when {
                href.startsWith("http") -> href
                href.startsWith("/") -> baseUrl + href
                else -> "$baseUrl/$href"
            }
            if (!seen.add(abs)) continue
            val o = JSONObject()
            o.put("text", text.take(80))
            o.put("url", abs)
            arr.put(o)
        }
        return arr
    }

    /** 抓一个 URL 的 HTML；失败返回 null 并记录原因 */
    fun fetch(url: String): String? {
        return try {
            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 15000
                instanceFollowRedirects = true
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                )
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    lastError = "HTTP $code"
                    return null
                }
                val bytes = conn.inputStream.readBytes()
                lastError = null
                // 教务/学院站点多为 UTF-8；若声明是 gbk 再转一次
                val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
                val charsetName =
                    if (head.contains("gb2312", true) || head.contains("gbk", true)) "GBK" else "UTF-8"
                val cs = try {
                    java.nio.charset.Charset.forName(charsetName)
                } catch (e: Exception) {
                    Charsets.UTF_8
                }
                String(bytes, cs)
            } finally {
                runCatching { conn.disconnect() }
            }
        } catch (e: Exception) {
            lastError = e.javaClass.simpleName + ": " + (e.message ?: "")
            null
        }
    }

    /** 判断一份保存内容是否有效（供 UI 过滤） */
    fun isValid(save: PendingSave): Boolean =
        !TextUtils.isEmpty(save.content) && save.content.length > 60
}
