package com.tyust.course.ui.route

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.tyust.course.manager.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class GradeBrowseStateTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private val scope = GradeBrowseScope("account-a", "school-a", "provider-a", "opaque")
    private val chosen = GradeBrowseSelection("remote:term-25/summer", "2025 夏季学期", 2)
    private val prefs get() = GradeBrowsePreferences.from(context)
    @Before fun clear() { context.getSharedPreferences("grade_browse_preferences", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun coldStartAndNewLoginReadExactOpaqueIdAndSelectedTab() {
        prefs.write(scope, chosen)
        assertEquals(chosen, GradeBrowsePreferences.from(context).read(scope.copy()))
    }

    @Test fun accountSchoolProviderAndFormatAreIndependent() {
        prefs.write(scope, chosen)
        for (other in listOf(scope.copy(account = "b"), scope.copy(school = "b"),
            scope.copy(provider = "b"), scope.copy(termFormat = "academic-year-semester"))) {
            assertEquals(GradeBrowseSelection(), prefs.read(other))
        }
        assertEquals(chosen, prefs.read(scope))
    }

    @Test fun deletingAccountRemovesAllItsProvidersButPreservesOtherAccounts() {
        prefs.write(scope, chosen); prefs.write(scope.copy(provider = "b"), chosen)
        val other = scope.copy(account = "account-b")
        prefs.write(other, chosen)
        prefs.removeAccount(scope.account)
        assertEquals(GradeBrowseSelection(), prefs.read(scope))
        assertEquals(GradeBrowseSelection(), prefs.read(scope.copy(provider = "b")))
        assertEquals(chosen, prefs.read(other))
    }

    @Test fun corruptPreferenceUsesDefaultWithoutClearingOtherScopes() {
        prefs.write(scope.copy(account = "b"), chosen)
        context.getSharedPreferences("grade_browse_preferences", Context.MODE_PRIVATE).edit().putString(scope.key, "not-json").commit()
        assertEquals(GradeBrowseSelection(), prefs.read(scope))
        assertEquals(chosen, prefs.read(scope.copy(account = "b")))
    }

    @Test fun emptyDelayedFailedOrChangedCatalogCannotReplaceExplicitSelection() {
        for (default in listOf("", "2026-2027-1", "2027-2028-1")) {
            assertEquals(chosen, applyGradeDefault(chosen, true, true, default, "当前学期"))
        }
    }

    @Test fun onlyUnchosenDefaultFollowsLateSchoolCurrentTerm() {
        val initial = applyGradeDefault(GradeBrowseSelection(), false, false, "local", "本地当前")
        assertEquals("local", initial.termId)
        assertEquals("school", applyGradeDefault(initial, false, true, "school", "学校当前").termId)
        assertEquals(initial, applyGradeDefault(initial, false, false, "new-date", "新学年"))
        assertEquals(initial, applyGradeDefault(initial, false, true, "", ""))
    }

    @Test fun restoredInstanceStateWinsOverDiskAndProviderChangesUseOwnPreference() {
        prefs.write(scope, GradeBrowseSelection("disk", "持久记录", 0))
        val identity = mutableStateOf(scope)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme {
            var value by rememberGradeBrowseSelection(identity.value)
            TextButton(onClick = { value = chosen }) { Text(value.termId) }
        } }
        compose.onNodeWithText("disk").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(chosen.termId).assertExists()
        compose.runOnIdle { identity.value = scope.copy(provider = "new-provider") }
        compose.onNodeWithText(chosen.termId).assertDoesNotExist()
    }
}
