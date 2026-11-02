package com.tyust.course.academic.plugin

import android.app.Application
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class, qualifiers = "w360dp-h640dp")
class PluginScopeConsentDialogTest {
    @get:Rule val compose = createComposeRule()
    private val disclosure = "学校：synthetic\n" + (1..30).joinToString("\n\n") {
        "https://school.example/task/$it\nGET、POST · 读取任务与要求、提交业务数据\n查询约束：{\"kind\":\"task\"}"
    }

    @Test fun compactOverviewKeepsDetailsCollapsedAndApprovalExplicit() {
        val result = CompletableDeferred<JSONObject?>()
        compose.setContent { MaterialTheme { PluginScopeConsentDialog(PagePrompt("授权学校网络", disclosure, null, result, scopeConsent = true)) } }
        compose.onNodeWithText("本次申请 1 个站点、30 项网络范围。").assertIsDisplayed()
        compose.onNodeWithText(disclosure).assertDoesNotExist()
        compose.onNodeWithText("撤销入口：插件详情 → 权限与数据 → 撤销学校网络授权").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("允许并记住").assertIsDisplayed()
        assertFalse(result.isCompleted)
        compose.onNodeWithText("查看完整授权详情（站点、路径、方法与用途）").performScrollTo().performClick()
        compose.onNodeWithText(disclosure).assertExists()
        assertFalse(result.isCompleted)
        compose.onNodeWithText("拒绝").performClick()
        assertNull(runBlocking { result.await() })
    }

    @Test fun allowRequiresExplicitClick() {
        val result = CompletableDeferred<JSONObject?>()
        compose.setContent { MaterialTheme { PluginScopeConsentDialog(PagePrompt("授权学校网络", disclosure, null, result, scopeConsent = true)) } }
        assertFalse(result.isCompleted)
        compose.onNodeWithText("允许并记住").performClick()
        assertNotNull(runBlocking { result.await() })
    }

    @Test fun largeTextKeepsApprovalAndRejectionReachableWhileDetailsScroll() {
        val result = CompletableDeferred<JSONObject?>()
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                PluginScopeConsentDialog(PagePrompt("授权学校网络", disclosure, null, result, scopeConsent = true))
            }
        } }
        compose.onNodeWithText("允许并记住").assertIsDisplayed()
        compose.onNodeWithText("拒绝").assertIsDisplayed()
        compose.onNodeWithText("查看完整授权详情（站点、路径、方法与用途）").performScrollTo().performClick()
        compose.onNodeWithText(disclosure).assertExists()
        compose.onNodeWithText("允许并记住").assertIsDisplayed()
        assertFalse(result.isCompleted)
        compose.onNodeWithText("拒绝").performClick()
        assertNull(runBlocking { result.await() })
    }

    @Test fun oldInteractionReceivesFullDisclosureAndCanDeny() = runBlocking {
        var received: Pair<String, String>? = null
        val interaction = object : NativePluginInteraction {
            override suspend fun confirm(title: String, message: String): Boolean { received = title to message; return false }
            override suspend fun authenticate(challenge: JSONObject, image: File?): JSONObject? = null
            override suspend fun pick(types: Array<String>): Uri? = null
            override suspend fun notificationPermission() = false
            override fun haptic() {}
            override fun navigate(pageId: String, params: JSONObject) {}
            override fun back() {}
        }
        assertFalse(interaction.scopeConsent("授权学校网络", disclosure))
        assertEquals("授权学校网络" to disclosure, received)
    }

    @Test fun pageInteractionRetainsFullScopeAndClosingCancelsPendingConsent() = runBlocking {
        val interaction = PageInteraction()
        val answer = async(start = CoroutineStart.UNDISPATCHED) { interaction.scopeConsent("授权学校网络", disclosure) }
        assertTrue(interaction.prompt!!.scopeConsent)
        assertEquals(disclosure, interaction.prompt!!.message)
        assertFalse(answer.isCompleted)
        interaction.close()
        answer.join()
        assertTrue(answer.isCancelled)
        assertNull(interaction.prompt)
    }
}
