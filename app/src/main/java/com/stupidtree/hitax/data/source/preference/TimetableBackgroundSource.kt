package com.stupidtree.hitax.data.source.preference

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * 课表背景设置
 *
 * 背景图片保存在 App 私有目录（files/backgrounds/<timetableId>.jpg），
 * 不依赖外部存储权限；每套课表可单独设置背景、不透明度与压暗程度。
 */
class TimetableBackgroundSource private constructor(context: Context) {

    private val appContext = context.applicationContext

    private val sp = appContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val SP_NAME = "yixian_timetable_bg"
        private const val DIR = "backgrounds"

        const val DEFAULT_OPACITY = 100
        const val DEFAULT_DIM = 0

        @Volatile
        private var instance: TimetableBackgroundSource? = null

        @JvmStatic
        fun getInstance(context: Context): TimetableBackgroundSource {
            if (instance == null) {
                synchronized(TimetableBackgroundSource::class.java) {
                    if (instance == null) {
                        instance = TimetableBackgroundSource(context.applicationContext)
                    }
                }
            }
            return instance!!
        }
    }

    // ------------------------------------------------------------------
    // 文件
    // ------------------------------------------------------------------

    fun backgroundDir(): File {
        val d = File(appContext.filesDir, DIR)
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun backgroundFile(timetableId: String): File = File(backgroundDir(), "$timetableId.jpg")

    fun hasBackground(timetableId: String): Boolean {
        if (timetableId.isEmpty()) return false
        return backgroundFile(timetableId).let { it.exists() && it.length() > 0 }
    }

    /**
     * 从相册选中的 Uri 保存为课表背景（自动限制最长边，避免内存过大）
     * @return 是否成功
     */
    fun saveFromUri(timetableId: String, uri: Uri, maxSize: Int = 2000): Boolean {
        if (timetableId.isEmpty()) return false
        return try {
            val input = appContext.contentResolver.openInputStream(uri) ?: return false
            // 先读尺寸
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(input, null, opts)
            input.close()
            var sample = 1
            var maxEdge = maxOf(opts.outWidth, opts.outHeight)
            while (maxEdge / sample > maxSize) sample *= 2
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            val input2 = appContext.contentResolver.openInputStream(uri) ?: return false
            val bitmap = BitmapFactory.decodeStream(input2, null, decodeOpts)
            input2.close()
            if (bitmap == null) return false
            FileOutputStream(backgroundFile(timetableId)).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
            }
            bitmap.recycle()
            setEnabled(timetableId, true)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun clearBackground(timetableId: String) {
        try {
            val f = backgroundFile(timetableId)
            if (f.exists()) f.delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        sp.edit()
            .remove(key(timetableId, "enable"))
            .remove(key(timetableId, "opacity"))
            .remove(key(timetableId, "dim"))
            .apply()
    }

    // ------------------------------------------------------------------
    // 设置项
    // ------------------------------------------------------------------

    private fun key(timetableId: String, name: String) = "${timetableId}_$name"

    fun isEnabled(timetableId: String): Boolean {
        if (!hasBackground(timetableId)) return false
        return sp.getBoolean(key(timetableId, "enable"), true)
    }

    fun setEnabled(timetableId: String, enabled: Boolean) {
        sp.edit().putBoolean(key(timetableId, "enable"), enabled).apply()
    }

    /** 背景不透明度 0~100 */
    fun getOpacity(timetableId: String): Int =
        sp.getInt(key(timetableId, "opacity"), DEFAULT_OPACITY).coerceIn(0, 100)

    fun setOpacity(timetableId: String, value: Int) {
        sp.edit().putInt(key(timetableId, "opacity"), value.coerceIn(0, 100)).apply()
    }

    /** 压暗程度 0~80（百分比），保证白色卡片上的文字依然清晰 */
    fun getDim(timetableId: String): Int =
        sp.getInt(key(timetableId, "dim"), DEFAULT_DIM).coerceIn(0, 80)

    fun setDim(timetableId: String, value: Int) {
        sp.edit().putInt(key(timetableId, "dim"), value.coerceIn(0, 80)).apply()
    }
}
