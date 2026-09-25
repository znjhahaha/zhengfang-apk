package com.tyust.course.academic.plugin

import android.app.Application
import android.net.Uri
import com.tyust.course.academic.AcademicSession
import com.tyust.course.academic.AcademicSessionKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.net.URLDecoder
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher

/** Exercises auth.prompt -> opaque vault handle -> native effect -> actual local HTTP. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24], application = Application::class)
class NativeCredentialIntegrationTest {
    private lateinit var app: Application
    private val password = "synthetic-password-中文"
    @Before fun setup() { app = RuntimeEnvironment.getApplication(); Dispatchers.setMain(Dispatchers.Unconfined) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private val interaction = object : NativePluginInteraction {
        override suspend fun confirm(title: String, message: String) = true
        override suspend fun authenticate(challenge: JSONObject, image: File?) = JSONObject()
            .put("values", JSONObject().put("username", "synthetic-student").put("password", password)).put("remember", false)
        override suspend fun pick(types: Array<String>): Uri? = null
        override suspend fun notificationPermission() = false
        override fun haptic() {}
        override fun navigate(pageId: String, params: JSONObject) {}
        override fun back() {}
    }
    private fun pkg(origin: String) = PluginPackage(PluginManifest(JSONObject().put("id", "test.native-login")
        .put("name", "Synthetic login").put("kind", "native").put("apiVersion", 3).put("version", "3.2.0")
        .put("category", "campus").put("contributes", JSONObject().put("pages", JSONArray()).put("entries", JSONArray()))
        .put("permissions", JSONArray(listOf("network", "auth", "credentials")))
        .put("requires", JSONArray().put(JSONObject().put("name", "network.request").put("version", 2)))
        .put("network", JSONArray().put(JSONObject().put("origin", origin).put("pathPrefix", "/")
            .put("methods", JSONArray(listOf("POST"))).put("purposes", JSONArray(listOf("auth")))))), "", "a".repeat(64), false)
    private fun effect(name: String, input: JSONObject, version: Int = 1) = JSONObject()
        .put("id", "synthetic-effect").put("capability", name).put("version", version).put("input", input)
    private suspend fun credential(host: NativeCapabilityHost): String {
        val input = JSONObject().put("title", "Synthetic login").put("key", "login").put("fields", JSONArray()
            .put(JSONObject().put("id", "username").put("label", "Account").put("type", "text"))
            .put(JSONObject().put("id", "password").put("label", "Password").put("type", "password")))
        val result = host.execute(effect("auth.prompt", input), NativeFlow(true)) as JSONObject
        assertEquals(setOf("credential"), result.keys().asSequence().toSet())
        assertFalse(result.toString().contains(password))
        return result.getString("credential")
    }
    @Test fun nativeRsaLoginSendsEncryptedFormWithoutReturningThePasswordToThePlugin() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val origin = server.url("/").toString().trimEnd('/')
            val session = AcademicSession(AcademicSessionKey("plugin:test.native-login", "default"), origin)
            var live = true
            val host = NativeCapabilityHost(app, pkg(origin), session, interaction) { live }
            try {
                val handle = credential(host)
                val key = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
                val request = JSONObject().put("url", "$origin/xtgl/login_slogin.html").put("method", "POST").put("purpose", "auth")
                    .put("credential", handle).put("form", JSONObject().put("mmsfjm", "1").put("csrftoken", "synthetic-csrf"))
                    .put("bindings", JSONObject().put("form", JSONObject().put("yhm", "username").put("mm", JSONObject().put("from", "password")
                        .put("transform", JSONObject().put("type", "rsa-pkcs1").put("publicKeySpkiBase64", Base64.getEncoder().encodeToString(key.public.encoded))))))
                server.enqueue(MockResponse().setBody("{\"authenticated\":true}"))
                val result = host.execute(effect("network.request", request, 2), NativeFlow(true)) as JSONObject
                assertEquals(200, result.getInt("status")); assertFalse(result.toString().contains(password))
                val sent = server.takeRequest(1, TimeUnit.SECONDS)!!
                val form = sent.body.readUtf8().split('&').associate { pair ->
                    val parts = pair.split('=', limit = 2)
                    URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
                }
                assertEquals("1", form["mmsfjm"]); assertEquals("synthetic-csrf", form["csrftoken"])
                assertEquals("synthetic-student", form["yhm"]); assertNotEquals(password, form["mm"])
                val decrypt = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.DECRYPT_MODE, key.private) }
                assertEquals(password, String(decrypt.doFinal(Base64.getDecoder().decode(form["mm"])), Charsets.UTF_8))
                host.execute(effect("credentials.remove", JSONObject().put("handle", handle)), NativeFlow(true))
                assertEquals(JSONObject.NULL, host.execute(effect("credentials.find", JSONObject().put("key", "login")), NativeFlow(true)))
                try { host.execute(effect("network.request", request, 2), NativeFlow(true)); fail("Removed credentials must not send another request") }
                catch (error: PluginException) { assertEquals(PluginErrorCode.SESSION_EXPIRED, error.code) }
                assertEquals(1, server.requestCount)
            } finally { live = false; host.close(); session.retire() }
        }
    }

    @Test fun nativeTokenBindingUsesTheVaultAndDeclaredNetworkScope() = runBlocking {
        MockWebServer().use { server ->
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            val origin = "http://127.0.0.1:${server.port}"
            val pkg = pkg(origin)
            pkg.manifest.json.getJSONArray("requires").getJSONObject(0).put("version", 3)
            pkg.manifest.network.single().put("authHeader", "X-Token")
            val session = AcademicSession(AcademicSessionKey("plugin:test.native-login", "default"), origin)
            val host = NativeCapabilityHost(app, pkg, session, interaction) { true }
            try {
                host.requireCompatible()
                val handle = credential(host)
                val request = JSONObject().put("url", "$origin/api/login").put("method", "POST").put("purpose", "auth")
                    .put("credential", handle).put("bindings", JSONObject().put("headers", JSONObject().put("X-Token", "username")))
                server.enqueue(MockResponse().setBody("ok"))
                assertEquals(200, (host.execute(effect("network.request", request, 3), NativeFlow(true)) as JSONObject).getInt("status"))
                val sent = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals("synthetic-student", sent.getHeader("X-Token")); assertNull(sent.getHeader("Authorization"))
                pkg.manifest.network.single().remove("authHeader")
                try { host.execute(effect("network.request", request, 3), NativeFlow(true)); fail("Undeclared token must not be sent") }
                catch (error: PluginException) { assertEquals(PluginErrorCode.UNTRUSTED_URL, error.code) }
                assertEquals(1, server.requestCount)
            } finally { host.close(); session.retire() }
        }
    }
}
