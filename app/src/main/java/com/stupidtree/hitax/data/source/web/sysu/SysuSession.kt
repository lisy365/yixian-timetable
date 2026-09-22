package com.stupidtree.hitax.data.source.web.sysu

import android.text.TextUtils
import org.json.JSONObject
import java.net.URLDecoder
import java.util.Base64

/**
 * 中山大学统一身份认证会话解析工具
 *
 * 登录在 WebView 中完成后，教务系统会把学生信息（学号、姓名、身份）通过
 * `#/student?data=<urlencode(base64(json))>` 或 login 接口回传给前端。
 * 本类负责从这些文本中提取信息，以及把 Cookie 字符串解析为 Map。
 *
 * 纯 JVM 逻辑（仅依赖 android.net.Uri / android.util.Base64），便于单元测试。
 */
object SysuSession {

    /** 已登录用户信息 */
    data class Info(
        var userNumber: String? = null,
        var userName: String? = null,
        var loginPattern: String? = null,
        var userId: String? = null,
        var facultyName: String? = null,
        var majorName: String? = null,
        var gradeName: String? = null
    ) {
        fun merge(other: Info): Info {
            if (other.userNumber != null) userNumber = other.userNumber
            if (other.userName != null) userName = other.userName
            if (other.loginPattern != null) loginPattern = other.loginPattern
            if (other.userId != null) userId = other.userId
            if (other.facultyName != null) facultyName = other.facultyName
            if (other.majorName != null) majorName = other.majorName
            if (other.gradeName != null) gradeName = other.gradeName
            return this
        }

        fun isValid(): Boolean = !userNumber.isNullOrEmpty() || !userName.isNullOrEmpty()
    }

    /**
     * 从任意 URL 中解析会话信息。
     * 支持：
     * - `.../jwxt/#/student?data=xxx`
     * - `.../jwxt/api/sso/cas/login?ticket=xxx&data=xxx`
     * - 纯 JSON 文本（login 接口的返回体）
     */
    fun parseFromUrl(url: String?): Info? {
        if (url.isNullOrEmpty()) return null
        // query 可能在 fragment 里
        var query = ""
        val qIdx = url.indexOf('?')
        if (qIdx >= 0) query = url.substring(qIdx + 1)
        if (query.isNotEmpty()) {
            query.split("&").forEach { seg ->
                val eq = seg.indexOf('=')
                if (eq <= 0) return@forEach
                val key = seg.substring(0, eq)
                if (key == "data") {
                    parseFromData(seg.substring(eq + 1))?.let { return it }
                }
            }
        }
        return null
    }

    /**
     * 解析 data 参数内容：可能是 URL 编码的 base64 JSON，也可能直接是 JSON
     */
    fun parseFromData(raw: String?): Info? {
        if (raw.isNullOrEmpty()) return null
        val text0: String = raw
        // 先尝试直接当 JSON
        jsonToInfo(text0)?.let { return it }
        // URL 解码
        var s: String = try {
            URLDecoder.decode(text0, "UTF-8")
        } catch (e: Exception) {
            text0
        }
        jsonToInfo(s)?.let { return it }
        // base64 解码（标准 / URL-safe）
        try {
            val bytes = try {
                Base64.getDecoder().decode(s)
            } catch (e: Exception) {
                Base64.getUrlDecoder().decode(s.trimEnd('='))
            }
            val text = String(bytes, Charsets.UTF_8)
            jsonToInfo(text)?.let { return it }
            // 双重编码兜底
            val decodedAgain = try {
                URLDecoder.decode(text, "UTF-8")
            } catch (e: Exception) {
                text
            }
            jsonToInfo(decodedAgain)?.let { return it }
        } catch (e: Exception) {
            // ignore
        }
        return null
    }

    private fun jsonToInfo(text: String?): Info? {
        if (text.isNullOrBlank() || !text.trim().startsWith("{")) return null
        return try {
            jsonObjectToInfo(JSONObject(text))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 从 JSONObject 中提取学生信息（兼容多种字段命名与嵌套）
     */
    fun jsonObjectToInfo(obj: JSONObject): Info? {
        val info = Info()
        fill(obj, info)
        if (!info.isValid()) {
            for (key in arrayOf("data", "userInfo", "user", "result")) {
                val sub = obj.optJSONObject(key) ?: continue
                fill(sub, info)
                if (info.isValid()) break
                val sub2 = sub.optJSONObject("userInfo")
                if (sub2 != null) {
                    fill(sub2, info)
                    if (info.isValid()) break
                }
            }
        }
        return if (info.isValid()) info else null
    }

    private fun fill(obj: JSONObject, info: Info) {
        str(obj, "userNumber", "userNo", "account", "xh", "studentNumber")?.let { info.userNumber = it }
        str(obj, "userName", "xm", "name", "realName", "studentName")?.let { info.userName = it }
        str(obj, "loginPattern", "pattern")?.let { info.loginPattern = it }
        str(obj, "userId", "id")?.let { info.userId = it }
        str(obj, "facultyName", "yxmc", "collegeName", "departmentName")?.let { info.facultyName = it }
        str(obj, "majorName", "zymc", "specialityName")?.let { info.majorName = it }
        str(obj, "gradeName", "njmc", "grade")?.let { info.gradeName = it }
    }

    private fun str(obj: JSONObject, vararg keys: String): String? {
        for (k in keys) {
            if (obj.has(k) && !obj.isNull(k)) {
                val v = obj.optString(k).trim()
                if (v.isNotEmpty() && v != "null") return v
            }
        }
        return null
    }

    /** 解析 `k=v; k2=v2` 形式的 Cookie 文本 */
    fun parseCookieText(text: String?): HashMap<String, String> {
        val res = HashMap<String, String>()
        if (text.isNullOrBlank()) return res
        for (seg in text.split(";", "\n")) {
            val s = seg.trim()
            if (s.isEmpty()) continue
            val idx = s.indexOf('=')
            if (idx <= 0) continue
            val k = s.substring(0, idx).trim()
            val v = s.substring(idx + 1).trim()
            if (k.isNotEmpty()) res[k] = v
        }
        return res
    }

    /** 从任意响应头/文本中解析 Set-Cookie */
    fun parseSetCookie(line: String?): Pair<String, String>? {
        if (line.isNullOrBlank()) return null
        val first = line.split(";").firstOrNull()?.trim() ?: return null
        val idx = first.indexOf('=')
        if (idx <= 0) return null
        return first.substring(0, idx).trim() to first.substring(idx + 1).trim()
    }

    /**
     * 判断 WebView 当前页面是否已经完成登录：
     * 只要用户的登录身份是学生，且 URL 已经进入 jwxt 主应用即视为成功。
     */
    fun isLoggedIn(url: String?, info: Info?): Boolean {
        if (url.isNullOrEmpty()) return false
        if (!url.contains("jwxt.sysu.edu.cn")) return false
        if (url.contains("/esc-sso/")) return false
        val pattern = info?.loginPattern
        if (pattern != null) return pattern == "student-login"
        return info?.isValid() == true
    }

    /**
     * 页面文本是否仍停留在登录页（用于兜底判断）
     */
    fun looksLikeLoginPage(text: String?): Boolean {
        if (text.isNullOrEmpty()) return true
        return text.contains("请输入密码") || text.contains("统一身份认证")
                || text.contains("帐号登录") || text.contains("id=\"password\"")
    }
}
