package com.tyust.course.ui.system

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual toolbar/overlay composition and touch sequence, without a WebView or GPU claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WebToolbarMenuTest {
    @get:Rule val compose = createComposeRule()
    @Test fun firstTapOpensWebsiteMenuAndClosingRunsOneAction() {
        var closed = 0
        compose.setContent { MaterialTheme { GlassWindowHost(capturePage = false) {
            GlassPageScaffold(title = "网页", topBar = {
                SystemTopBar(title = "网页", collapseFraction = 1f, actions = {
                    SystemActionMenu("网页选项", listOf(
                        SystemMenuAction("前进", Icons.Outlined.Close, {}, enabled = false),
                        SystemMenuAction("刷新", Icons.Outlined.Close, {}),
                        SystemMenuAction("回到入口", Icons.Outlined.Close, {}),
                        SystemMenuAction("固定到主导航", Icons.Outlined.Close, {}),
                        SystemMenuAction("在浏览器打开", Icons.Outlined.Close, {}),
                        SystemMenuAction("清理此网站账号数据", Icons.Outlined.Close, {}),
                        SystemMenuAction("关闭网页", Icons.Outlined.Close, { closed++ })
                    ))
                })
            }) { padding -> Box(Modifier.fillMaxSize().padding(padding)) { Text("网站正文") } }
        } } }
        compose.waitForIdle(); compose.mainClock.autoAdvance = false
        fun frames() { repeat(65) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() } }
        compose.onNodeWithContentDescription("网页选项").performTouchInput { click() }
        frames()
        compose.onNodeWithText("前进").assertIsDisplayed().assertIsNotEnabled()
        // Use a real gesture and drain frames: performScrollTo's synchronous retry
        // loop cannot advance this deliberately frozen animation/layout clock.
        compose.onNode(hasScrollAction()).performTouchInput { swipeUp() }
        frames()
        compose.onNodeWithText("关闭网页").assertIsDisplayed().performTouchInput { click() }
        frames()
        compose.runOnIdle { assertEquals(1, closed) }
        compose.onNodeWithText("关闭网页").assertDoesNotExist()
    }
}
