package com.tyust.course.manager

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * 壁纸彩色光斑：归一化坐标 (cx, cy)、相对屏宽半径 radius、透明度 alpha。
 */
data class WallpaperAccent(
    val color: Color,
    val cx: Float,
    val cy: Float,
    val radius: Float,
    val alpha: Float
)

/**
 * 背景预设色。iOS 同款语义色，供画布壁纸与设置选择共用。
 * baseColor 为画布底色；glowColor/shadeColor 为左上高光与右下暗角，
 * accents 为多彩流体光斑，提升明暗与色彩层次，让 Backdrop 折射有可采样内容。
 */
enum class WallpaperPreset(
    val displayName: String,
    val baseColor: Color,
    val glowColor: Color,
    val shadeColor: Color,
    val isDark: Boolean,
    val accents: List<WallpaperAccent> = emptyList()
) {
    Aurora(
        "流光", Color(0xFFF6F6FB), Color(0xFFFFFFFF), Color(0xFF5A6B85), false,
        listOf(
            WallpaperAccent(Color(0xFF9BC4F8), cx = 0.15f, cy = 0.18f, radius = 0.58f, alpha = 0.42f),
            WallpaperAccent(Color(0xFFC3B3F2), cx = 0.88f, cy = 0.32f, radius = 0.62f, alpha = 0.38f),
            WallpaperAccent(Color(0xFF9BE5DE), cx = 0.26f, cy = 0.64f, radius = 0.56f, alpha = 0.34f),
            WallpaperAccent(Color(0xFFF4B8D4), cx = 0.80f, cy = 0.88f, radius = 0.60f, alpha = 0.32f)
        )
    ),
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

    var wallpaper by mutableStateOf(WallpaperPreset.Aurora)
        private set

    fun initialize(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
        val stored = prefs?.getString(KEY_WALLPAPER, null)
        wallpaper = stored
            ?.let { name -> runCatching { WallpaperPreset.valueOf(name) }.getOrNull() }
            ?: WallpaperPreset.Aurora
    }

    fun updateWallpaper(preset: WallpaperPreset) {
        wallpaper = preset
        prefs?.edit()?.putString(KEY_WALLPAPER, preset.name)?.apply()
    }
}