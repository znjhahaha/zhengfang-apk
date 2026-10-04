package com.tyust.course.ui.system

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.tyust.course.manager.AppearanceSettingsManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Software canvas evidence of the action fill, not device GPU acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class, qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SystemDialogButtonTest {
    @get:Rule val compose = createComposeRule()
    @After fun reset() { AppearanceSettingsManager.updateGlassEffect(true) }

    @Test fun restingButtonsHaveFilledCapsulesInBothThemesAndGlassModes() {
        val dark = mutableStateOf(false)
        val background = Color(0xFF84A3BF)
        var scheme = lightColorScheme()
        lateinit var view: View
        compose.setContent {
            val currentView = LocalView.current
            val colors = if (dark.value) darkColorScheme() else lightColorScheme()
            SideEffect { view = currentView; scheme = colors }
            MaterialTheme(colorScheme = colors) {
                CompositionLocalProvider(LocalControlBackdrop provides rememberLayerBackdrop()) {
                    Column(Modifier.fillMaxSize().background(background).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SystemDialogButton({}, Modifier.width(260.dp).testTag("secondary")) { Text("取消") }
                        SystemDialogButton({}, Modifier.width(260.dp).testTag("primary"), primary = true) { Text("确认安装") }
                        SystemDialogButton({}, Modifier.width(260.dp).testTag("danger"), destructive = true) { Text("卸载") }
                    }
                }
            }
        }
        for (isDark in listOf(false, true)) for (glass in listOf(false, true)) {
            compose.runOnIdle { dark.value = isDark; AppearanceSettingsManager.updateGlassEffect(glass) }
            for ((tag, color) in listOf("secondary" to { scheme.surfaceContainerHigh }, "primary" to { scheme.primary }, "danger" to { scheme.error })) {
                compose.onNodeWithTag(tag).assertHeightIsAtLeast(48.dp)
                val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                compose.runOnIdle {
                    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    try {
                        view.draw(Canvas(bitmap))
                        // Above the text: a TextButton or transparent outline cannot pass.
                        assertEquals(color().toArgb(), bitmap.getPixel(bounds.center.x.toInt(), (bounds.top + 3).toInt()))
                        assertEquals(background.toArgb(), bitmap.getPixel((bounds.left + 1).toInt(), (bounds.top + 1).toInt()))
                    } finally { bitmap.recycle() }
                }
            }
        }
    }

    @Test fun disabledActionRetainsSurfaceAndCannotSubmit() {
        val enabled = mutableStateOf(false)
        var clicks = 0
        compose.setContent { MaterialTheme {
            SystemDialogButton({ clicks++ }, enabled = enabled.value) { Text("确认安装") }
        } }
        compose.onNodeWithText("确认安装").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, clicks); enabled.value = true }
        compose.onNodeWithText("确认安装").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun longMultiActionDialogRemainsReachableWithLargeFont() {
        var remembered = 0
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.8f)) {
                GlassOverlayHost {
                    SystemDialog(onDismissRequest = {}, title = { Text("授权使用教务登录") },
                        dismissButton = { SystemDialogButton({}) { Text("拒绝") } },
                        confirmButton = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SystemDialogButton({}) { Text("仅本次") }
                            SystemDialogButton({ remembered++ }, primary = true) { Text("允许并记住") }
                        } }) { Text("模拟授权说明") }
                }
            }
        } }
        for (label in listOf("拒绝", "仅本次", "允许并记住")) compose.onNodeWithText(label).assertIsDisplayed()
        compose.onNodeWithText("允许并记住").performClick()
        compose.runOnIdle { assertEquals(1, remembered) }
    }

    @Test fun popupOpacityMatchesReadableReferenceWithoutChangingPermanentMaterial() {
        assertEquals(0.78f, modalSurfaceAlpha(false, false), 0f)
        assertEquals(0.84f, modalSurfaceAlpha(true, false), 0f)
        assertEquals(0.96f, modalSurfaceAlpha(false, true), 0f)
        assertEquals(0.18f, dropdownSurfaceAlpha(false, false), 0f)
        assertEquals(0.22f, dropdownSurfaceAlpha(true, false), 0f)
        assertEquals(0.96f, dropdownSurfaceAlpha(false, true), 0f)
        assertEquals(0.96f, dropdownSurfaceAlpha(true, true), 0f)
        assertEquals(0.62f, GlassMaterials.resolve(GlassMaterialRole.Modal).surfaceAlpha, 0.001f)
    }

    @Test fun longScrollableDialogKeepsActionsVisibleAndTitleAlignedWithBody() {
        var clicked = false
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                Box(Modifier.height(460.dp).fillMaxWidth()) {
                    GlassOverlayHost {
                        SystemDialog(onDismissRequest = {}, scrollContent = true,
                            title = { Text("确认操作", Modifier.testTag("modal-title")) },
                            confirmButton = { SystemDialogButton({ clicked = true }, primary = true) { Text("继续") } }) {
                            Text("第一段说明", Modifier.testTag("modal-body"))
                            repeat(20) { Text("较长的操作影响说明 $it") }
                        }
                    }
                }
            }
        } }
        val title = compose.onNodeWithTag("modal-title").fetchSemanticsNode().boundsInRoot
        val body = compose.onNodeWithTag("modal-body").fetchSemanticsNode().boundsInRoot
        assertEquals(title.left, body.left, 1f)
        compose.onNodeWithText("继续").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(clicked) }
    }

    @Test fun fallbackWindowUsesSameDimAsHostedDialog() {
        var window: android.view.Window? = null
        compose.setContent { MaterialTheme {
            SystemDialog(onDismissRequest = {}) {
                val view = LocalView.current
                SideEffect { window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window }
                Text("独立窗口")
            }
        } }
        compose.onNodeWithText("独立窗口").assertIsDisplayed()
        compose.runOnIdle {
            val actual = requireNotNull(window)
            assertEquals(DialogScrimAlpha, actual.attributes.dimAmount, 0.001f)
            assertTrue(actual.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0)
        }
    }
}
