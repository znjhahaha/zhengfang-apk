package com.tyust.course.ui.screen

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.tyust.course.manager.AppearanceSettingsManager
import com.tyust.course.manager.WallpaperPreset
import com.tyust.course.ui.system.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.roundToInt

/** Native canvas checks of the actual headers; OEM/GPU rendering still needs device acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class, qualifiers = "w420dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaterialHeaderSurfaceTest {
    @get:Rule val compose = createComposeRule()
    private val progress = mutableFloatStateOf(1f)
    private val palette = mutableStateOf(WallpaperAppearanceColors.Light)
    private val wallpaper = Color(0xFF8BACCF)
    private val dark = WallpaperAppearanceColors.Light.copy(
        solidSurface = Color(0xFF171B22), onSurface = Color.White,
        onSurfaceVariant = Color.White, usesDarkForeground = false
    )
    private lateinit var renderedView: View
    private var scale = 1f
    private var statusHeight = 0f
    private var clicks = 0

    @Before fun materialMode() {
        AppearanceSettingsManager.updateWallpaper(WallpaperPreset.Aurora)
        AppearanceSettingsManager.updateGlassEffect(false)
    }

    @After fun restoreAppearance() {
        AppearanceSettingsManager.updateWallpaper(WallpaperPreset.Aurora)
        AppearanceSettingsManager.updateGlassEffect(true)
    }

    @Composable private fun Scene(content: @Composable () -> Unit) {
        val view = LocalView.current
        val density = LocalDensity.current
        val status = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        SideEffect {
            renderedView = view
            scale = density.density
            statusHeight = with(density) { status.toPx() }
        }
        MaterialTheme {
            CompositionLocalProvider(LocalGlassAppearanceOverride provides palette.value) {
                ProvideWallpaperAppearance(palette.value) {
                    Box(Modifier.fillMaxSize().background(wallpaper)) { content() }
                }
            }
        }
    }

    private fun pixels(check: (Bitmap) -> Unit) {
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(renderedView.width, renderedView.height, Bitmap.Config.ARGB_8888)
            try {
                renderedView.draw(Canvas(bitmap))
                check(bitmap)
            } finally { bitmap.recycle() }
        }
    }

    private fun expected(alpha: Float): Color =
        palette.value.solidSurface.copy(alpha = alpha).compositeOver(wallpaper)

    private fun assertColor(bitmap: Bitmap, x: Float, y: Float, expected: Color) {
        val actual = bitmap.getPixel(x.roundToInt(), y.roundToInt())
        val wanted = expected.toArgb()
        for (shift in listOf(0, 8, 16, 24)) {
            assertTrue("Unexpected surface at ($x, $y): ${actual.toUInt().toString(16)} != ${wanted.toUInt().toString(16)}",
                abs(((actual ushr shift) and 255) - ((wanted ushr shift) and 255)) <= 3)
        }
    }

    private fun assertHorizontalFill(bounds: Rect, y: Float, alpha: Float, inset: Float) {
        pixels { bitmap ->
            // Sample both the outer band and center: a hollow rectangle must not pass.
            for (step in 0..20) {
                val x = bounds.left + inset + (bounds.width - 2 * inset) * step / 20f
                assertColor(bitmap, x, y, expected(alpha))
            }
        }
    }

    private fun assertDateInk() {
        val bounds = compose.onNodeWithTag("schedule-date-title", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val foreground = palette.value.onSurface.toArgb()
        pixels { bitmap ->
            var ink = 0
            for (y in bounds.top.toInt() until bounds.bottom.toInt()) {
                for (x in bounds.left.toInt() until bounds.right.toInt()) {
                    val pixel = bitmap.getPixel(x, y)
                    if (listOf(0, 8, 16).all { shift ->
                        abs(((pixel ushr shift) and 255) - ((foreground ushr shift) and 255)) < 12
                    }) ink++
                }
            }
            assertTrue("Date must remain readable independently of the background reveal ($ink pixels)", ink > 12)
        }
    }

    @Test fun disabledGlassSlabFillsEntireRoundedInteriorInBothThemesAndDuringReversal() {
        compose.setContent {
            Scene {
                // Even a retained non-null sample must obey the live glass setting.
                val sample = rememberLayerBackdrop()
                HeaderGlassSlab(progress.floatValue, sample, 26.dp,
                    Modifier.padding(20.dp).size(300.dp, 112.dp).testTag("slab"))
            }
        }
        for (appearance in listOf(WallpaperAppearanceColors.Light, dark)) {
            compose.runOnIdle { palette.value = appearance }
            for (fraction in listOf(0f, 0.02f, 0.5f, 1f, 0.3f, 0f)) {
                compose.runOnIdle { progress.floatValue = fraction }
                val bounds = compose.onNodeWithTag("slab").fetchSemanticsNode().boundsInRoot
                assertHorizontalFill(bounds, bounds.center.y, fraction, 3f * scale)
                pixels { bitmap ->
                    assertColor(bitmap, bounds.center.x, bounds.top + 3f * scale, expected(fraction))
                    assertColor(bitmap, bounds.center.x, bounds.bottom - 3f * scale, expected(fraction))
                    assertColor(bitmap, bounds.left + scale, bounds.top + scale, wallpaper)
                    assertColor(bitmap, bounds.left - 3f * scale, bounds.center.y, wallpaper)
                }
            }
        }
    }

    @Test fun disablingGlassWithRetainedBackdropImmediatelyRestoresSolidSurface() {
        AppearanceSettingsManager.updateGlassEffect(true)
        compose.setContent {
            Scene {
                val sample = rememberLayerBackdrop()
                HeaderGlassSlab(progress.floatValue, sample, 26.dp,
                    Modifier.padding(20.dp).size(300.dp, 112.dp).testTag("slab"))
            }
        }
        repeat(2) {
            compose.runOnIdle { AppearanceSettingsManager.updateGlassEffect(false) }
            val bounds = compose.onNodeWithTag("slab").fetchSemanticsNode().boundsInRoot
            assertHorizontalFill(bounds, bounds.center.y, 1f, 3f * scale)
            compose.runOnIdle { AppearanceSettingsManager.updateGlassEffect(true) }
        }
    }

    @Test fun timetableMaterialSurfaceHasNoHoleAndControlsSurviveRevealAndReversal() {
        compose.setContent {
            Scene {
                WeekHeaderCompact(5, {}, {}, collapseFraction = progress.floatValue,
                    firstWeekDate = "2026-08-31", onDayView = { clicks++ })
            }
        }
        for (appearance in listOf(WallpaperAppearanceColors.Light, dark)) {
            compose.runOnIdle { palette.value = appearance }
            for (fraction in listOf(0f, 0.02f, 0.5f, 1f, 0.3f, 0f)) {
                compose.runOnIdle { progress.floatValue = fraction }
                val bounds = compose.onNodeWithTag("schedule-header").fetchSemanticsNode().boundsInRoot
                // Between the 56dp title row and the dates, away from all foreground ink.
                assertHorizontalFill(bounds, bounds.top + statusHeight + 56f * scale, fraction, 14f * scale)
                assertDateInk()
            }
        }
        compose.onNodeWithContentDescription("日视图").performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun gradesMaterialSurfaceUsesSamePaletteAndPreservesRefreshAction() {
        compose.setContent {
            Scene {
                MeasuredGradesHeader("本学期", listOf("成绩", "考试"), 0, {}, progress.floatValue,
                    null, false, false, false, {}, { clicks++ }, { _, _ -> })
            }
        }
        for (appearance in listOf(WallpaperAppearanceColors.Light, dark)) {
            compose.runOnIdle { palette.value = appearance }
            for (fraction in listOf(0f, 0.4f, 0.7f, 1f, 0.5f, 0f)) {
                compose.runOnIdle { progress.floatValue = fraction }
                val bounds = compose.onNodeWithTag("grades-header").fetchSemanticsNode().boundsInRoot
                assertHorizontalFill(bounds, bounds.bottom - 7f * scale,
                    ((fraction - 0.35f) / 0.65f).coerceIn(0f, 1f), 50f * scale)
            }
        }
        compose.onNodeWithContentDescription("刷新").performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun statusBarMaterialMaskFillsWithoutGlassCompositing() {
        compose.setContent {
            Scene { StatusBarFrost(28.dp, progress.floatValue, rememberLayerBackdrop()) }
        }
        for (appearance in listOf(WallpaperAppearanceColors.Light, dark)) {
            compose.runOnIdle { palette.value = appearance }
            for (fraction in listOf(0f, 0.02f, 0.5f, 1f, 0f)) {
                compose.runOnIdle { progress.floatValue = fraction }
                pixels { bitmap ->
                    assertColor(bitmap, 100f * scale, 8f * scale, expected(fraction))
                    assertColor(bitmap, 100f * scale, 32f * scale, wallpaper)
                }
            }
        }
    }

    // Canvas verifies the live foreground, including glass's no-sample fallback.
    // Actual EGL / RuntimeShader backdrop compositing needs device acceptance.
    @Test fun gradeLabelsStayCenteredAndKeepForegroundInkThroughoutCollapse() {
        val selected = mutableIntStateOf(0)
        var accent = Color.Blue
        val labels = listOf("学期", "总体", "考试")
        compose.setContent {
            Scene {
                accent = readableAccent(palette.value)
                MeasuredGradesHeader("15 门课程", labels, selected.intValue, { selected.intValue = it },
                    progress.floatValue, null, true, true, false, {}, {}, { _, _ -> })
            }
        }
        for (glass in listOf(false, true)) {
            compose.runOnIdle { AppearanceSettingsManager.updateGlassEffect(glass) }
            for (index in labels.indices) {
                compose.onNodeWithText(labels[index]).performClick()
                for (fraction in listOf(0f, 0.4f, 0.8f, 1f, 0.3f, 0f)) {
                    compose.runOnIdle { progress.floatValue = fraction }
                    compose.onAllNodesWithText(labels[index]).assertCountEquals(1)
                    val label = compose.onNodeWithText(labels[index]).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                    val segments = compose.onNodeWithTag("grades-segments").fetchSemanticsNode().boundsInRoot
                    val expectedCenter = segments.left + 5f * scale + (segments.width - 10f * scale) * (index + 0.5f) / 3f
                    assertTrue("Label drifts inside its segment", abs(label.center.x - expectedCenter) < 2f * scale)
                    assertTrue("Label drifts vertically", abs(label.center.y - segments.center.y) < 2f * scale)
                    pixels { bitmap ->
                        val ink = accent.toArgb()
                        var count = 0
                        for (y in label.top.toInt() until label.bottom.toInt()) for (x in label.left.toInt() until label.right.toInt()) {
                            val pixel = bitmap.getPixel(x, y)
                            if (listOf(0, 8, 16).all { shift -> abs(((pixel ushr shift) and 255) - ((ink ushr shift) and 255)) < 14 }) count++
                        }
                        assertTrue("Selected label must have foreground ink independently of glass ($count)", count > 10)
                    }
                }
            }
        }
    }
}
