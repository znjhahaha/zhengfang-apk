package com.tyust.course.ui.screen

import android.graphics.Color as AndroidColor
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tyust.course.manager.AppearanceSettingsManager
import com.tyust.course.manager.WallpaperMode
import com.tyust.course.manager.WallpaperPreset
import com.tyust.course.manager.customWallpaperStyle
import com.tyust.course.ui.system.GlassGradientSlider
import com.tyust.course.ui.system.SystemDialog
import com.tyust.course.ui.system.SystemPrimaryButton
import com.tyust.course.ui.system.SystemSecondaryButton
import com.tyust.course.ui.system.SystemSegmentedControl
import com.tyust.course.ui.system.drawWallpaperPattern
import com.tyust.course.ui.system.glass.LiquidColorField
import com.tyust.course.ui.theme.NeuPrimary

/**
 * 背景设置弹窗：预设色 / 自定义颜色 / 自定义图片。
 *
 * 三种来源都走「实时应用」而不是「选好再确认」：整个 App 的背景就在弹窗后面，
 * 调的时候直接看到真实效果比看一个小色块准得多。落盘只在松手与关闭时发生
 * （见 [AppearanceSettingsManager.updateCustomColor] 的 persist 参数）。
 *
 * 三页共用一个弹窗、用分段控件切换，而不是套多层弹窗——`DialogHost`
 * 同一时刻只持有一个弹窗，嵌套会把外层顶掉。
 */
@Composable
fun WallpaperSettingsDialog(onDismiss: () -> Unit) {
    val manager = AppearanceSettingsManager
    val context = LocalContext.current
    var tabIndex by remember {
        mutableIntStateOf(
            when (manager.mode) {
                WallpaperMode.Image -> 2
                WallpaperMode.Color -> 1
                WallpaperMode.Preset -> 0
            }
        )
    }

    // HSV 初值只算一次。用 remember(key) 会在实时应用后被自己的结果重置，形成回灌。
    val initialHsv = remember {
        val seed = manager.customColor ?: manager.style.baseColor
        FloatArray(3).also { AndroidColor.colorToHSV(seed.toArgb(), it) }
    }
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember { mutableFloatStateOf(initialHsv[1]) }
    var brightness by remember { mutableFloatStateOf(initialHsv[2].coerceAtLeast(0.08f)) }

    val customColor = Color.hsv(hue, saturation, brightness)

    fun applyCustom(persist: Boolean) {
        manager.updateCustomColor(Color.hsv(hue, saturation, brightness), persist)
    }

    fun pickHsv(color: Color) {
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(color.toArgb(), hsv)
        hue = hsv[0]
        saturation = hsv[1]
        brightness = hsv[2]
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        manager.importImageWallpaper(context, uri) { success ->
            if (!success) {
                Toast.makeText(context, "图片读取失败，请换一张试试", Toast.LENGTH_SHORT).show()
            }
        }
    }
    fun launchPicker() {
        imagePicker.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    // 切页就立刻生效，否则用户在调之前看不到任何变化
    LaunchedEffect(tabIndex) {
        when (tabIndex) {
            0 -> if (manager.mode != WallpaperMode.Preset) manager.updateWallpaper(manager.wallpaper)
            1 -> applyCustom(persist = true)
            // 没有图片就不切模式，否则背景会空掉
            2 -> manager.useImageWallpaper()
        }
    }

    SystemDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "背景",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        confirmButton = {
            SystemPrimaryButton(
                text = "完成",
                onClick = {
                    when (tabIndex) {
                        1 -> manager.persistCustomColor()
                        2 -> manager.persistImageAdjust()
                    }
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SystemSegmentedControl(
                options = listOf("预设", "颜色", "图片"),
                selectedIndex = tabIndex,
                onSelect = { tabIndex = it },
                modifier = Modifier.fillMaxWidth()
            )

            when (tabIndex) {
                0 -> {
                    WallpaperPreset.values().forEach { preset ->
                        val selected = manager.mode == WallpaperMode.Preset &&
                            preset == manager.wallpaper
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { manager.updateWallpaper(preset) }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(preset.baseColor)
                                    .border(0.5.dp, Color.Black.copy(alpha = 0.12f), CircleShape)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Text(
                                text = preset.displayName,
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                            )
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = NeuPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                1 -> {
                    // 预览画的是真实的 drawWallpaperPattern，光斑与暗角都在里面——
                    // 只放一个纯色块会骗人，自定义底色推导出的层次看不到
                    val previewStyle = remember(customColor) { customWallpaperStyle(customColor) }
                    WallpaperPreview(badge = hexOf(customColor)) {
                        drawWallpaperPattern(previewStyle)
                    }

                    LiquidColorField(
                        hue = hue,
                        saturation = saturation,
                        brightness = brightness,
                        onChange = { s, v ->
                            saturation = s
                            brightness = v
                            applyCustom(persist = false)
                        },
                        onChangeFinished = { applyCustom(persist = true) }
                    )

                    ColorSliderRow(label = "色相") {
                        GlassGradientSlider(
                            value = hue / 360f,
                            onValueChange = {
                                hue = it * 360f
                                applyCustom(persist = false)
                            },
                            onValueChangeFinished = { applyCustom(persist = true) },
                            brush = Brush.horizontalGradient(HueStops),
                            thumbColor = Color.hsv(hue, 1f, 1f)
                        )
                    }

                    ColorSliderRow(label = "推荐色") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            RecommendedColors.forEach { swatch ->
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(swatch)
                                        .border(
                                            0.5.dp,
                                            Color.Black.copy(alpha = 0.12f),
                                            CircleShape
                                        )
                                        .clickable {
                                            pickHsv(swatch)
                                            manager.updateCustomColor(swatch, persist = true)
                                        }
                                )
                            }
                        }
                    }

                    Text(
                        text = "浅色底更接近系统观感；选深色底时界面文字仍按系统深浅色显示，" +
                            "过深的底色可能影响可读性。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                    )
                }

                else -> {
                    if (manager.hasImageWallpaper) {
                        // 读取放在绘制 lambda 里：位图是异步到位的，读在外面不会触发重绘
                        WallpaperPreview(badge = null) {
                            drawWallpaperPattern(AppearanceSettingsManager.style)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            SystemSecondaryButton(
                                text = "更换",
                                onClick = { launchPicker() },
                                modifier = Modifier.weight(1f)
                            )
                            SystemSecondaryButton(
                                text = "移除",
                                onClick = { manager.removeImageWallpaper() },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        ColorSliderRow(label = "蒙版") {
                            GlassGradientSlider(
                                value = manager.imageDim,
                                onValueChange = {
                                    manager.updateImageAdjust(dim = it, persist = false)
                                },
                                onValueChangeFinished = { manager.persistImageAdjust() },
                                brush = Brush.horizontalGradient(
                                    listOf(Color.White, Color.Black)
                                ),
                                thumbColor = Color(0xFF8E8E93)
                            )
                        }

                        ColorSliderRow(label = "模糊") {
                            GlassGradientSlider(
                                value = manager.imageBlur,
                                onValueChange = {
                                    manager.updateImageAdjust(blur = it, persist = false)
                                },
                                onValueChangeFinished = { manager.persistImageAdjust() },
                                brush = Brush.horizontalGradient(
                                    listOf(Color(0xFF48484A), Color(0xFFE5E5EA))
                                ),
                                thumbColor = Color(0xFF8E8E93)
                            )
                        }

                        Text(
                            text = "适当模糊能让前景的玻璃控件折射得更自然；蒙版用来压暗照片，" +
                                "保证界面文字清晰可读。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                                .border(
                                    0.5.dp,
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                                    RoundedCornerShape(18.dp)
                                )
                                .clickable { launchPicker() },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Image,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(30.dp)
                                )
                                Text(
                                    text = "从相册选择一张图片",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        SystemPrimaryButton(
                            text = "选择图片",
                            onClick = { launchPicker() },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Text(
                            text = "图片只保存在本机，选中后会自动压缩到屏幕尺寸，不会上传。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                        )
                    }
                }
            }
        }
    }
}

/** 104dp 的真实壁纸预览。右下角可选一个 hex 徽标。 */
@Composable
private fun WallpaperPreview(
    badge: String?,
    draw: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(104.dp)
            .clip(RoundedCornerShape(18.dp))
            .border(0.5.dp, Color.Black.copy(alpha = 0.10f), RoundedCornerShape(18.dp))
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) { draw() }
        if (badge != null) {
            Text(
                text = badge,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.72f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF1C1C1E)
            )
        }
    }
}

@Composable
private fun ColorSliderRow(
    label: String,
    slider: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        slider()
    }
}

private val HueStops = listOf(
    Color(0xFFFF0000),
    Color(0xFFFFFF00),
    Color(0xFF00FF00),
    Color(0xFF00FFFF),
    Color(0xFF0000FF),
    Color(0xFFFF00FF),
    Color(0xFFFF0000)
)

/**
 * 挑过的背景色：前八个是浅底（和系统观感一致），后四个是深底。
 * 都刻意压低了饱和度——高饱和底色会把玻璃控件里的折射染成一片单色。
 */
private val RecommendedColors = listOf(
    Color(0xFFF7F7FA), // 雾白
    Color(0xFFFBF7F0), // 米白
    Color(0xFFE6EFFB), // 浅蓝
    Color(0xFFE6F5EF), // 薄荷
    Color(0xFFEFEAFA), // 淡紫
    Color(0xFFFBEDE6), // 蜜桃
    Color(0xFFFAEAF0), // 樱粉
    Color(0xFFFAF3E0), // 麦黄
    Color(0xFF2C2C2E), // 石墨
    Color(0xFF1E2A3A), // 深蓝
    Color(0xFF1E2E28), // 墨绿
    Color(0xFF2E1E24) // 酒红
)

private fun hexOf(color: Color): String =
    "#%06X".format(0xFFFFFF and color.toArgb())
