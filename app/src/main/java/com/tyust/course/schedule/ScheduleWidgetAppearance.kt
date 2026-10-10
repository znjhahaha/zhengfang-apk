package com.tyust.course.schedule

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.util.LruCache
import com.tyust.course.manager.WallpaperImageStore
import com.tyust.course.manager.boxBlurPixels
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class ScheduleWidgetAppearance(val image: String = "", val opacity: Int = 90, val blur: Int = 0)

object ScheduleWidgetAppearances {
    const val PREFS = "schedule_widget_appearance"
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    fun read(context: Context, id: Int): ScheduleWidgetAppearance = runCatching {
        val json = JSONObject(context.getSharedPreferences(PREFS, 0).getString(id.toString(), "{}")!!)
        ScheduleWidgetAppearance(json.optString("image").takeIf { it.matches(Regex("[a-f0-9]{32}")) }.orEmpty(),
            json.optInt("opacity", 90).coerceIn(0, 100), json.optInt("blur").coerceIn(0, 32))
    }.getOrDefault(ScheduleWidgetAppearance())
    fun save(context: Context, id: Int, value: ScheduleWidgetAppearance) {
        require(id > 0 && value.opacity in 0..100 && value.blur in 0..32 &&
            (value.image.isEmpty() || value.image.matches(Regex("[a-f0-9]{32}"))))
        val previous = read(context, id)
        context.getSharedPreferences(PREFS, 0).edit().putString(id.toString(), JSONObject().put("image", value.image)
            .put("opacity", value.opacity).put("blur", value.blur).toString()).apply()
        if (previous.image != value.image) discardImage(context, previous.image)
        ScheduleWidgetUpdater.update(context)
    }
    private fun imageContext(context: Context, token: String): Context {
        require(token.matches(Regex("[a-f0-9]{32}")))
        return object : ContextWrapper(context.applicationContext) {
            override fun getFilesDir(): File = File(super.getFilesDir(), "widget-images/$token").apply { mkdirs() }
        }
    }
    fun importImage(context: Context, uri: Uri): String {
        val token = UUID.randomUUID().toString().replace("-", "")
        if (WallpaperImageStore.import(imageContext(context, token), uri, 540) == null) {
            discardImage(context, token)
            error("图片无法读取，请重新选择")
        }
        return token
    }
    fun discardImage(context: Context, token: String) {
        if (token.isBlank()) return
        val referenced = context.getSharedPreferences(PREFS, 0).all.values.any { it.toString().contains(token) }
        if (!referenced) {
            val scoped = imageContext(context, token); WallpaperImageStore.clear(scoped); scoped.filesDir.delete()
            cache.snapshot().keys.filter { token in it }.forEach(cache::remove)
        }
    }
    fun delete(context: Context, id: Int) {
        val old = read(context, id)
        context.getSharedPreferences(PREFS, 0).edit().remove(id.toString()).apply()
        discardImage(context, old.image)
    }
    fun bitmap(context: Context, value: ScheduleWidgetAppearance, dark: Boolean): Bitmap {
        val key = value.toString() + dark
        cache.get(key)?.let { return it }
        val raw = value.image.takeIf { it.isNotBlank() }?.let { WallpaperImageStore.loadSharp(imageContext(context, it)) }
        val background = if (raw == null) Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
            eraseColor(if (dark) 0xFF202936.toInt() else 0xFFF1F5FB.toInt())
        } else if (value.blur == 0) raw else {
            val pixels = IntArray(raw.width * raw.height)
            raw.getPixels(pixels, 0, raw.width, 0, 0, raw.width, raw.height)
            Bitmap.createBitmap(boxBlurPixels(pixels, raw.width, raw.height, value.blur), raw.width, raw.height, Bitmap.Config.ARGB_8888).also { raw.recycle() }
        }
        val result = Bitmap.createBitmap(background.width, background.height, Bitmap.Config.ARGB_8888)
        Canvas(result).drawBitmap(background, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = value.opacity * 255 / 100 })
        background.recycle()
        cache.put(key, result)
        return result
    }
}
