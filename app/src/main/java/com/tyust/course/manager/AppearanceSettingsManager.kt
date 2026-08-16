package com.tyust.course.manager

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

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
 * 解析后的壁纸外观。
 *
 * 绘制层与页面背景只需要这些东西，不需要知道它是"某个预设"、"用户自己调的颜色"
 * 还是"用户自己传的图片"。把 [WallpaperPreset] 换成它之后，三种来源才能走
 * 完全同一条渲染路径。
 *
 * 图片相关的四个字段都有默认值，所以 [toStyle] 与 [customWallpaperStyle]
 * 两个构造点不用改一个字。
 */
data class WallpaperStyle(
    val baseColor: Color,
    val glowColor: Color,
    val shadeColor: Color,
    val isDark: Boolean,
    val accents: List<WallpaperAccent> = emptyList(),
    /** 归一化后的原图。为 null 表示当前不是图片壁纸，或还在异步解码。 */
    val image: ImageBitmap? = null,
    /** 缩略图。拉满整屏就是模糊层，见 `drawWallpaperPattern`。 */
    val imageSoft: ImageBitmap? = null,
    /** 蒙版强度 0..1。 */
    val imageDim: Float = 0f,
    /** 模糊强度 0..1。1 = 只画缩略图。 */
    val imageBlur: Float = 0f
)

fun WallpaperPreset.toStyle(): WallpaperStyle =
    WallpaperStyle(baseColor, glowColor, shadeColor, isDark, accents)

/** 壁纸来源。取代原来的 `useCustomWallpaper` 布尔——它表达不了第三种。 */
enum class WallpaperMode { Preset, Color, Image }

/**
 * 由用户挑的一个底色推导出整套壁纸。
 *
 * 为什么不能只把底色填满了就完事：玻璃的模糊与折射需要背景里【有东西可采样】，
 * 纯色平面折射出来还是同一个颜色，玻璃就退化成一层白雾（Aurora 预设带光斑就是这个原因）。
 * 所以这里按底色的色相推出一组邻近色光斑，位置沿用 Aurora 那四个点。
 *
 * 光斑用邻近色（±35°、±70°）而不是补色：补色打在饱和底色上会发灰发脏。
 * 饱和度设了下限，否则用户选纯白/纯灰时推出来的光斑全是灰的，等于没有层次。
 */
fun customWallpaperStyle(base: Color): WallpaperStyle {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(base.toArgb(), hsv)
    val hue = hsv[0]
    val saturation = hsv[1]
    val isDark = base.luminance() < 0.42f

    val accentSaturation = saturation.coerceIn(0.28f, 0.58f)
    val accentValue = if (isDark) 0.62f else 0.95f
    fun accentColor(hueShift: Float): Color =
        Color.hsv((hue + hueShift + 360f) % 360f, accentSaturation, accentValue)

    return WallpaperStyle(
        baseColor = base,
        glowColor = if (isDark) {
            // 深色底不能用纯白高光，会在左上角糊出一块死白；提亮底色本身即可
            Color.hsv(hue, saturation * 0.6f, (hsv[2] + 0.22f).coerceAtMost(1f))
        } else {
            Color.White
        },
        // 暗角：保留色相、压低明度，比统一用黑色更贴合底色
        shadeColor = Color.hsv(hue, saturation.coerceIn(0.10f, 0.45f), if (isDark) 0.06f else 0.34f),
        isDark = isDark,
        accents = listOf(
            WallpaperAccent(accentColor(-35f), cx = 0.15f, cy = 0.18f, radius = 0.58f, alpha = 0.34f),
            WallpaperAccent(accentColor(35f), cx = 0.88f, cy = 0.32f, radius = 0.62f, alpha = 0.30f),
            WallpaperAccent(accentColor(-70f), cx = 0.26f, cy = 0.64f, radius = 0.56f, alpha = 0.28f),
            WallpaperAccent(accentColor(70f), cx = 0.80f, cy = 0.88f, radius = 0.60f, alpha = 0.26f)
        )
    )
}

/**
 * 图片壁纸。
 *
 * 位图是异步解码的（见 [AppearanceSettingsManager.initialize] 的说明），
 * 到位之前 [image] 为 null，此时退化成"用主色调出来的一版渐变壁纸"——
 * 冷启动因此不会先闪一下白屏再跳成照片。
 *
 * 蒙版拉过一半就按深色底算：这时前景已经在深色背景上了。
 */
fun imageWallpaperStyle(
    image: ImageBitmap?,
    imageSoft: ImageBitmap?,
    dominant: Color,
    dim: Float,
    blur: Float
): WallpaperStyle {
    val base = customWallpaperStyle(dominant)
    if (image == null) return base
    return base.copy(
        isDark = base.isDark || dim > 0.5f,
        image = image,
        imageSoft = imageSoft,
        imageDim = dim.coerceIn(0f, 1f),
        imageBlur = blur.coerceIn(0f, 1f)
    )
}

/**
 * 外观偏好单例。壁纸以 Compose state 暴露，供各页画布观察，切换即重绘。
 */
object AppearanceSettingsManager {
    private const val PREFS_NAME = "appearance_settings"
    private const val KEY_WALLPAPER = "wallpaper_preset"
    private const val KEY_CUSTOM_COLOR = "wallpaper_custom_color"
    /** 旧键。只在读不到 [KEY_MODE] 时用来推断模式，升级用户的设置才不会丢。 */
    private const val KEY_USE_CUSTOM = "wallpaper_use_custom"
    private const val KEY_MODE = "wallpaper_mode"
    private const val KEY_IMAGE_DIM = "wallpaper_image_dim"
    private const val KEY_IMAGE_BLUR = "wallpaper_image_blur"
    private const val KEY_IMAGE_COLOR = "wallpaper_image_color"

    private const val TAG = "AppearanceSettings"

    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null

    /**
     * 图片解码用的作用域。
     *
     * [initialize] 是在 `CourseApplication.onCreate` 的**主线程**里调的，
     * 那里不能同步解两张位图——冷启动会白屏可见地卡一下。
     */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 最近一次选中的预设。自定义模式下它仍然保留，切回"预设"时不用重新挑。 */
    var wallpaper by mutableStateOf(WallpaperPreset.Aurora)
        private set

    /** 用户自定义底色，从未设置过则为 null。 */
    var customColor by mutableStateOf<Color?>(null)
        private set

    var mode by mutableStateOf(WallpaperMode.Preset)
        private set

    /** 本机是否存着一张自定义图片。与"当前是否正在用它"无关。 */
    var hasImageWallpaper by mutableStateOf(false)
        private set

    var imageDim by mutableFloatStateOf(0f)
        private set

    var imageBlur by mutableFloatStateOf(0f)
        private set

    private var imageColor by mutableStateOf(Color(0xFFF2F2F7))
    private var imageSharp by mutableStateOf<ImageBitmap?>(null)
    private var imageSoft by mutableStateOf<ImageBitmap?>(null)

    /**
     * 当前生效的壁纸外观。**缓存成 state 而不是每次 get 重算**——绘制路径每帧都会读它，
     * 自定义模式下重算要做一次 HSV 转换加四个光斑对象。
     */
    var style by mutableStateOf(WallpaperPreset.Aurora.toStyle())
        private set

    /** 兼容旧调用点：它们问的是"是不是自定义颜色"。 */
    val useCustomWallpaper: Boolean
        get() = mode == WallpaperMode.Color

    val currentWallpaperName: String
        get() = when (mode) {
            WallpaperMode.Image -> "自定义图片"
            WallpaperMode.Color -> "自定义颜色"
            WallpaperMode.Preset -> wallpaper.displayName
        }

    fun initialize(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (prefs == null) {
            prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
        val stored = prefs?.getString(KEY_WALLPAPER, null)
        wallpaper = stored
            ?.let { name -> runCatching { WallpaperPreset.valueOf(name) }.getOrNull() }
            ?: WallpaperPreset.Aurora
        customColor = prefs
            ?.takeIf { it.contains(KEY_CUSTOM_COLOR) }
            ?.getInt(KEY_CUSTOM_COLOR, 0)
            ?.let { Color(it) }
        imageDim = prefs?.getFloat(KEY_IMAGE_DIM, 0f) ?: 0f
        imageBlur = prefs?.getFloat(KEY_IMAGE_BLUR, 0f) ?: 0f
        prefs?.takeIf { it.contains(KEY_IMAGE_COLOR) }
            ?.getInt(KEY_IMAGE_COLOR, 0)
            ?.let { imageColor = Color(it) }
        hasImageWallpaper = WallpaperImageStore.exists(app)
        mode = resolveMode()
        recomputeStyle()
        if (mode == WallpaperMode.Image) loadImageAsync()
    }

    /** 模式落盘的是新键，旧键顺手同步，降级安装回旧版本时不会突然变回预设。 */
    private fun resolveMode(): WallpaperMode {
        val stored = prefs?.getString(KEY_MODE, null)
            ?.let { name -> runCatching { WallpaperMode.valueOf(name) }.getOrNull() }
        var resolved = stored ?: if (prefs?.getBoolean(KEY_USE_CUSTOM, false) == true) {
            WallpaperMode.Color
        } else {
            WallpaperMode.Preset
        }
        if (resolved == WallpaperMode.Color && customColor == null) {
            resolved = WallpaperMode.Preset
        }
        if (resolved == WallpaperMode.Image && !hasImageWallpaper) {
            resolved = if (customColor != null) WallpaperMode.Color else WallpaperMode.Preset
        }
        return resolved
    }

    fun updateWallpaper(preset: WallpaperPreset) {
        wallpaper = preset
        mode = WallpaperMode.Preset
        prefs?.edit()
            ?.putString(KEY_WALLPAPER, preset.name)
            ?.putString(KEY_MODE, WallpaperMode.Preset.name)
            ?.putBoolean(KEY_USE_CUSTOM, false)
            ?.apply()
        recomputeStyle()
    }

    /**
     * 设置自定义底色。
     *
     * @param persist false 用于拖动取色器时的实时预览——拖一次会产生上百帧，
     *                每帧都写一次 SharedPreferences 没有意义。松手时再落盘。
     */
    fun updateCustomColor(color: Color, persist: Boolean = true) {
        customColor = color
        mode = WallpaperMode.Color
        recomputeStyle()
        if (persist) persistCustomColor()
    }

    fun persistCustomColor() {
        val color = customColor ?: return
        prefs?.edit()
            ?.putInt(KEY_CUSTOM_COLOR, color.toArgb())
            ?.putString(KEY_MODE, WallpaperMode.Color.name)
            ?.putBoolean(KEY_USE_CUSTOM, true)
            ?.apply()
    }

    /**
     * 导入一张图片当壁纸。导入成功即切到图片模式。
     *
     * @param onResult 回主线程，false 表示这张图读不了（调用方去提示用户）。
     */
    fun importImageWallpaper(context: Context, uri: Uri, onResult: (Boolean) -> Unit = {}) {
        val app = appContext ?: context.applicationContext.also { appContext = it }
        val metrics = context.resources.displayMetrics
        val target = max(metrics.widthPixels, metrics.heightPixels)
        ioScope.launch {
            val dominant = WallpaperImageStore.import(app, uri, target)
            val sharp = dominant?.let { WallpaperImageStore.loadSharp(app)?.asImageBitmap() }
            val soft = dominant?.let { WallpaperImageStore.loadSoft(app)?.asImageBitmap() }
            withContext(Dispatchers.Main) {
                if (dominant == null || sharp == null) {
                    Log.w(TAG, "图片壁纸导入失败")
                    onResult(false)
                    return@withContext
                }
                imageColor = Color(dominant)
                imageSharp = sharp
                imageSoft = soft
                hasImageWallpaper = true
                mode = WallpaperMode.Image
                prefs?.edit()
                    ?.putString(KEY_MODE, WallpaperMode.Image.name)
                    ?.putInt(KEY_IMAGE_COLOR, dominant)
                    ?.apply()
                recomputeStyle()
                onResult(true)
            }
        }
    }

    /** 切到已存在的图片壁纸。没有图片时什么都不做——否则背景会空掉。 */
    fun useImageWallpaper() {
        if (!hasImageWallpaper) return
        mode = WallpaperMode.Image
        prefs?.edit()?.putString(KEY_MODE, WallpaperMode.Image.name)?.apply()
        recomputeStyle()
        if (imageSharp == null) loadImageAsync()
    }

    fun removeImageWallpaper() {
        val app = appContext
        imageSharp = null
        imageSoft = null
        hasImageWallpaper = false
        mode = if (customColor != null) WallpaperMode.Color else WallpaperMode.Preset
        prefs?.edit()
            ?.putString(KEY_MODE, mode.name)
            ?.putBoolean(KEY_USE_CUSTOM, mode == WallpaperMode.Color)
            ?.apply()
        recomputeStyle()
        if (app != null) ioScope.launch { WallpaperImageStore.clear(app) }
    }

    /**
     * 蒙版与模糊。两者都是纯绘制参数，实时应用不要钱，所以和颜色同款：
     * 拖动时 persist = false，松手落盘。
     */
    fun updateImageAdjust(
        dim: Float = imageDim,
        blur: Float = imageBlur,
        persist: Boolean = true
    ) {
        imageDim = dim.coerceIn(0f, 1f)
        imageBlur = blur.coerceIn(0f, 1f)
        recomputeStyle()
        if (persist) persistImageAdjust()
    }

    fun persistImageAdjust() {
        prefs?.edit()
            ?.putFloat(KEY_IMAGE_DIM, imageDim)
            ?.putFloat(KEY_IMAGE_BLUR, imageBlur)
            ?.apply()
    }

    private fun loadImageAsync() {
        val app = appContext ?: return
        ioScope.launch {
            val sharp = WallpaperImageStore.loadSharp(app)?.asImageBitmap()
            val soft = WallpaperImageStore.loadSoft(app)?.asImageBitmap()
            withContext(Dispatchers.Main) {
                imageSharp = sharp
                imageSoft = soft
                if (sharp == null) {
                    // 文件被清了/解不出来：别把用户留在一张空背景上
                    hasImageWallpaper = false
                    if (mode == WallpaperMode.Image) {
                        mode = if (customColor != null) WallpaperMode.Color else WallpaperMode.Preset
                        prefs?.edit()?.putString(KEY_MODE, mode.name)?.apply()
                    }
                }
                recomputeStyle()
            }
        }
    }

    private fun recomputeStyle() {
        style = when (mode) {
            WallpaperMode.Image -> imageWallpaperStyle(
                image = imageSharp,
                imageSoft = imageSoft,
                dominant = imageColor,
                dim = imageDim,
                blur = imageBlur
            )
            WallpaperMode.Color -> customColor?.let { customWallpaperStyle(it) }
                ?: wallpaper.toStyle()
            WallpaperMode.Preset -> wallpaper.toStyle()
        }
    }
}