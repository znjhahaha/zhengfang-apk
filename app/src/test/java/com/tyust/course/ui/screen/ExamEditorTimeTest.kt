package com.tyust.course.ui.screen

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.tyust.course.ui.system.GlassOverlayHost
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class, qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExamEditorTimeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun editingLocationPreservesTheCompleteAutomaticTimeText() {
        val time = "2026-12-01 09:07～11:13（延期）"
        var saved: ExamItemUi? = null
        compose.setContent { MaterialTheme { GlassOverlayHost {
            ExamEditorDialog(ExamItemUi("数学", time, "A101", "", "期末", ""), {}, { saved = it })
        } } }
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("exam-location"))).performTextReplacement("B202")
        compose.onNodeWithTag("exam-save").performClick()
        assertEquals(time, saved?.examTime)
        assertEquals("B202", saved?.location)
    }

    @Test fun confirmingAnExistingRangeKeepsBothClockValues() {
        var saved: ExamItemUi? = null
        compose.setContent { MaterialTheme { GlassOverlayHost {
            ExamEditorDialog(ExamItemUi("数学", "2026-12-01 09:07至11:13", "A101", "", "期末", ""), {}, { saved = it })
        } } }
        compose.onNodeWithText("09:07至11:13").performClick()
        compose.onAllNodesWithText("保存").onLast().performClick()
        compose.onNodeWithTag("exam-save").performClick()
        assertEquals("2026-12-01 09:07-11:13", saved?.examTime)
    }

    @Test fun malformedDateCannotBeSavedAsAnUneditableRecord() {
        compose.setContent { MaterialTheme { GlassOverlayHost {
            ExamEditorDialog(ExamItemUi("数学", "2026-12-01", "", "", "", ""), {}, {})
        } } }
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("exam-date-input"))).performTextReplacement("2026-1-001")
        compose.onNodeWithTag("exam-save").assertIsNotEnabled()
    }
}
