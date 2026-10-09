package com.tyust.course.academic.plugin

import android.app.Application
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class PluginBrowserWireTest {
    @Test fun timeoutKeepsItsTypeAndStageInsteadOfClaimingUnsupported() {
        val error = runCatching { runBlocking { withTimeout(1) { delay(100) } } }.exceptionOrNull()!!
        val reply = PluginBrowserWire.failure(error, "script.control")
        assertEquals("TIMEOUT", reply.getString("code"))
        assertTrue(reply.getString("message").contains("启动原脚本"))
    }

    @Test fun unexpectedExceptionsNeverLeakRequestOrCredentialText() {
        val reply = PluginBrowserWire.failure(IllegalStateException("Cookie: secret; token=fixture"), "cookies.read")
        assertTrue(reply.getString("message").contains("同步登录状态"))
        assertTrue(reply.getString("message").contains("IllegalStateException"))
        assertFalse(reply.toString().contains("secret"))
        assertFalse(reply.toString().contains("fixture"))
    }
}
