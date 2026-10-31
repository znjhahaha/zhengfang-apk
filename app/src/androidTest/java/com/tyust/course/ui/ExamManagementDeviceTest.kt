package com.tyust.course.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tyust.course.BuildConfig
import com.tyust.course.manager.UserManager
import com.tyust.course.study.*
import com.tyust.course.ui.route.rememberExamManagement
import com.tyust.course.ui.screen.*
import com.tyust.course.ui.system.GlassWindowHost
import com.tyust.course.ui.theme.CourseSelectorTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ExamManagementDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val user get() = UserManager.getInstance()
    private val scope = AcademicDataScope("exam-ui", "synthetic", "https://school.test/jsxsd", "qz")
    private val term = "2026-2027-1"
    private lateinit var store: AcademicSnapshotStore
    private var previous = ""
    @Before fun prepare() {
        assumeTrue(BuildConfig.UI_PREVIEW)
        previous = user.sessionState.token.accountStorageKey
        store = AcademicSnapshotStore.from(compose.activity, user.sessionState)
        store.removeAccount(scope.account)
        user.sessionState.replace(scope.account)
    }
    @After fun restore() { if (::store.isInitialized) { store.removeAccount(scope.account); user.sessionState.replace(previous) } }
    private fun frame(automatic: androidx.compose.runtime.State<List<ExamItemUi>>) {
        compose.setContent {
            CourseSelectorTheme { GlassWindowHost {
                val management = rememberExamManagement(store, scope, term, automatic.value)
                GradesScreen(2, {}, emptyList(), listOf(term), term, {}, false, emptyList(),
                    OverallStatsUi("--", "--", 0, 0, 0, 0, 0), false, management.items, false, {},
                    supportedTabs = setOf(2), examError = "本校暂未支持自动查询，可手动添加考试",
                    onAddExam = management.add, onEditExam = management.edit, onDeleteExam = management.delete)
            } }
        }
    }
    private fun capture(name: String) {
        val directory = File(compose.activity.getExternalFilesDir(null), "exam-device").apply { mkdirs() }
        compose.onRoot().captureToImage().let { image ->
            File(directory, "$name.png").outputStream().use {
                image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Test fun emptyUnsupportedPageAllowsAddingAndPersistsManualExam() {
        frame(mutableStateOf(emptyList()))
        compose.onNodeWithText("添加考试").performClick()
        compose.onNodeWithTag("exam-save").assertIsNotEnabled()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("exam-name"))).performTextInput("手动数学")
        compose.onNodeWithTag("exam-date").performClick()
        compose.onNodeWithText("确定").performClick()
        compose.onNodeWithTag("exam-save").performClick()
        compose.waitUntil(10_000) { store.manual(scope, term).isNotEmpty() }
        compose.onNodeWithText("手动数学").assertIsDisplayed()
        assertEquals("手动数学", AcademicSnapshotStore.from(compose.activity, user.sessionState).manual(scope, term).single().exam.courseName)
        capture("manual-empty-page")
    }

    @Test fun editedAutomaticExamSurvivesRefreshAndDeleteRestoresAutomatic() {
        val rows = mutableStateOf(listOf(ExamItemUi("线性代数", "2026-12-01 08:00", "A101", "", "期末", "", sourceId = "exam1")))
        frame(rows)
        compose.onNodeWithText("编辑").performClick()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("exam-location"))).performTextReplacement("B202")
        compose.onNodeWithTag("exam-save").performClick()
        compose.waitUntil(10_000) { store.manual(scope, term).isNotEmpty() }
        compose.runOnIdle { rows.value = rows.value.map { it.copy(location = "A303", examTime = "2026-12-02 09:00") } }
        compose.onNodeWithText("B202").assertIsDisplayed()
        capture("manual-override")
        compose.onNodeWithText("删除").performClick()
        compose.onAllNodesWithText("删除").onLast().performClick()
        compose.waitUntil(10_000) { store.manual(scope, term).isEmpty() }
        compose.onNodeWithText("A303").assertIsDisplayed()
        capture("override-deleted")
    }
}
