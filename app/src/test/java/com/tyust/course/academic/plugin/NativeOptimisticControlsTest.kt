package com.tyust.course.academic.plugin

import android.app.Application
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class, qualifiers = "w360dp-h640dp")
class NativeOptimisticControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun editingKeepsFocusAndTheLatestTextAcrossOutOfOrderAcknowledgements() {
        val reconciler = NativeInputReconciliation()
        val inputs = mutableStateOf<Map<String, Any>>(emptyMap())
        val supplied = mutableStateOf("")
        val edits = mutableListOf<NativeInputReconciliation.Edit>()
        compose.setContent { MaterialTheme {
            NativePluginNode(JSONObject().put("id", "address").put("type", "input").put("label", "地址")
                .put("value", supplied.value).put("event", "address"), null, Modifier.fillMaxWidth(), inputValues = inputs.value) { event, _ ->
                edits += reconciler.edit("address", event.getString("value")); inputs.value = reconciler.values
            }
        } }
        val field = compose.onNode(hasSetTextAction())
        field.performTextInput("太原")
        field.performTextInput("科技大学")
        compose.runOnIdle {
            supplied.value = "太原"; reconciler.acknowledge(edits.first()); inputs.value = reconciler.values
        }
        field.assertIsFocused().assertTextContains("太原科技大学")
        compose.runOnIdle {
            supplied.value = "太原科技大学"; reconciler.acknowledge(edits.last()); inputs.value = reconciler.values
        }
        field.assertIsFocused().assertTextContains("太原科技大学")
    }

    @Test fun sliderAcceptsContinuousInputBeforeTheReducerReturnsAndThenUsesItsValidatedValue() {
        val reconciler = NativeInputReconciliation()
        val inputs = mutableStateOf<Map<String, Any>>(emptyMap())
        val supplied = mutableStateOf(0.0)
        val edits = mutableListOf<NativeInputReconciliation.Edit>()
        compose.setContent { MaterialTheme {
            NativePluginNode(JSONObject().put("id", "speed").put("type", "slider").put("label", "倍速")
                .put("value", supplied.value).put("min", 0.0).put("max", 1.0).put("event", "speed"), null,
                Modifier.fillMaxWidth(), inputValues = inputs.value) { event, _ ->
                edits += reconciler.edit("speed", event.getDouble("value")); inputs.value = reconciler.values
            }
        } }
        val slider = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(0.25f) }
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(0.75f) }
        compose.runOnIdle {
            supplied.value = 0.25; reconciler.acknowledge(edits.first()); inputs.value = reconciler.values
        }
        assertEquals(0.75f, slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, 0f)
        compose.runOnIdle {
            supplied.value = 0.6; reconciler.acknowledge(edits.last()); inputs.value = reconciler.values
        }
        assertEquals(0.6f, slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, 0f)
    }
}
