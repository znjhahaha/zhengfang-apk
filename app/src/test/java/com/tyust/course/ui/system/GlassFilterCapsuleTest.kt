package com.tyust.course.ui.system

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class, qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassFilterCapsuleTest {
    @get:Rule val compose = createComposeRule()
    @Test fun largeFontChoicesWrapWithFullLabelsAndOneSelection() {
        var selected by mutableStateOf("全部")
        val labels = listOf("全部", "教务适配", "校园服务", "通用工具")
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.8f)) {
                FlowRow(Modifier.width(320.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    labels.forEach { label -> GlassFilterCapsule(label, { selected = label }, selected = selected == label) }
                }
            }
        } }
        labels.forEach { compose.onNodeWithText(it).assertIsDisplayed().assertHeightIsAtLeast(48.dp) }
        val first = compose.onNodeWithText(labels.first()).fetchSemanticsNode().boundsInRoot
        val last = compose.onNodeWithText(labels.last()).fetchSemanticsNode().boundsInRoot
        assertTrue(last.top > first.top)
        compose.onNodeWithText("通用工具").performClick().assertIsSelected()
        labels.dropLast(1).forEach { compose.onNodeWithText(it).assertIsNotSelected() }
    }
    @Test fun disabledSchoolFilterCannotChangeAndToggleReportsCheckedState() {
        var changes = 0
        var checked by mutableStateOf(false)
        compose.setContent { MaterialTheme { Column {
            GlassFilterCapsule("未选学校", { changes++ }, selected = false, toggle = true, enabled = false)
            GlassFilterCapsule("仅本校", { checked = !checked; changes++ }, selected = checked, toggle = true)
        } } }
        compose.onNodeWithText("未选学校").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, changes) }
        compose.onNodeWithText("仅本校").performClick().assertIsOn()
        compose.runOnIdle { assertEquals(1, changes) }
    }
}
