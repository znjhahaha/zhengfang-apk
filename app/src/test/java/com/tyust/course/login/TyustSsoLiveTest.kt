package com.tyust.course.login

import com.tyust.course.model.SchoolConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TyustSsoLiveTest {
    @Test
    fun authorizedAccountCanReachAuthenticatedTeachingPage() {
        val username = System.getenv("TYUST_TEST_USERNAME").orEmpty()
        val password = System.getenv("TYUST_TEST_PASSWORD").orEmpty()
        assumeTrue(username.isNotBlank() && password.isNotBlank())

        val callback = LiveCallback()
        TyustSsoLoginManager().login(
            SchoolConfig("tyust", "太原科技大学", "newjwc.tyust.edu.cn", "https"),
            username,
            password,
            callback
        )

        assertTrue("live login callback timed out", callback.await())
        assertNull(callback.failure)
        val cookie = callback.cookie.orEmpty()
        assertTrue(cookie.isNotBlank())
        assertFalse(cookie.contains("SESSION="))

        val request = Request.Builder()
            .url("https://newjwc.tyust.edu.cn/jwglxt/xtgl/index_cxYhxxIndex.html?xt=jw&gnmkdm=index")
            .header("Cookie", cookie)
            .get()
            .build()
        OkHttpClient().newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            assertTrue(response.isSuccessful)
            assertFalse(body.contains("用户登录"))
            assertFalse(body.contains("login-page-flowkey"))
        }
    }

    private class LiveCallback : PasswordLoginCallback {
        private val latch = CountDownLatch(1)
        var cookie: String? = null
        var failure: String? = null

        override fun onSuccess(cookie: String) {
            this.cookie = cookie
            latch.countDown()
        }

        override fun onCaptchaRequired(imageBytes: ByteArray) {
            failure = "live account requires captcha"
            latch.countDown()
        }

        override fun onCaptchaInvalid() {
            failure = "live captcha was rejected"
            latch.countDown()
        }

        override fun onInvalidCredentials() {
            failure = "live credentials were rejected"
            latch.countDown()
        }

        override fun onError(message: String) {
            failure = message
            latch.countDown()
        }

        fun await(): Boolean = latch.await(45, TimeUnit.SECONDS)
    }
}
