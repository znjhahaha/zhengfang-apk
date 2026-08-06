package com.tyust.course.manager

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * 背景预设色。iOS 同款语义色，供画布壁纸与设置选择共用。
 * baseColor 为画布底色；glowColor/shadeColor 为左上高光与右下暗角，
 * 提升明暗对比，让 Backdrop 折射有可采样层次。
 */
enum class WallpaperPreset(
    val displayName: String,
    val baseColor: Color,
    val glowColor: Color,
    val shadeColor: Color,
    val isDark: Boolean
) {
    SystemGray6("浅雾灰", Color(0xFFF2F2F7), Color(0xFFFFFFFF), Color(0xFF6D7884), false),
    WarmWhite("暖白", Color(0xFFFBFBFD), Color(0xFFFFFFFF), Color(0xFFB0A89C), false),
    CoolGray("冷灰", Color(0xFFEBEDF0), Color(0xFFFFFFFF), Color(0xFF5A6672), false),
    Sky("天蓝", Color(0xFFEAF1FB), Color(0xFFFFFFFF), Color(0xFF4F86C6), false),
    Graphite("石墨灰", Color(0xFF2C2C2E), Color(0xFF48484A), Color(0xFF000000), true),
    SpaceBlack("深空黑", Color(0xFF1C1C1E), Color(0xFF3A3A3C), Color(0xFF000000), true);
}

/**
 * 外观偏好单例。壁纸预设以 Compose state 暴露，供 MainActivity 画布观察，切换即重绘。
 */
object AppearanceSettingsManager {
    private const val PREFS_NAME = "appearance_settings"
    private const val KEY_WALLPAPER = "wallpaper_preset"

    private var prefs: SharedPreferences? = null

    var wallpaper by mutableStateOf(WallpaperPreset.SystemGray6)
        private set

    fun initialize(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
        val stored = prefs?.getString(KEY_WALLPAPER, null)
        wallpaper = stored
            ?.let { name -> runCatching { WallpaperPreset.valueOf(name) }.getOrNull() }
            ?: WallpaperPreset.SystemGray6
    }

    fun updateWallpaper(preset: WallpaperPreset) {
        wallpaper = preset
        prefs?.edit()?.putString(KEY_WALLPAPER, preset.name)?.apply()
    }
}