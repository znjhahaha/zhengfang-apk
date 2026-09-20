package com.tyust.course.academic.plugin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tyust.course.academic.AcademicSessionStore
import com.tyust.course.academic.plugin.runtime.PluginSandboxClient
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PluginSandboxDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val sessions = AcademicSessionStore()
    private fun operation(method: String = "study.terms", manifest: JSONObject = JSONObject().put("network", org.json.JSONArray())) = PluginOperation(
        sessions.session("test-school", "test-account", "https://school.test"),
        PluginManifest(manifest.put("id", "test.school").put("version", "1.0.0")), method, development = true)
    private suspend fun run(source: String, op: PluginOperation = operation()) = PluginSandboxClient(context).execute(source, JSONObject(), op, PluginHost(op, context.cacheDir))
    @Test fun runsSharedHtmlAndPassesLargeResponsesOutsideBinder() = runBlocking {
        val result = run("""globalThis.plugin={study:{terms:async(a,c,s)=>({ok:true,data:{text:s.html.text('<b>A &amp; B</b>'),large:'x'.repeat(2*1024*1024),android:typeof Java,network:typeof fetch}})}};""")
        val data = result.getJSONObject("data")
        assertEquals("A & B", data.getString("text"))
        assertEquals(2 * 1024 * 1024, data.getString("large").length)
        assertEquals("undefined", data.getString("android"))
        assertEquals("undefined", data.getString("network"))
    }
    @Test fun sharedCryptoVectorsMatchIncludingRsaPaddingAndUnicode() = runBlocking {
        val vectors = JSONObject(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
            .open("academic-plugin/crypto-vectors.json").bufferedReader().use { it.readText() }).getJSONArray("calls")
        val source = """globalThis.plugin={study:{terms:async(a,c,s)=>({ok:true,data:await Promise.all(a.calls.map(v=>s.crypto[v.method](...Object.values(v.args))))})}};"""
        val op = operation()
        val result = PluginSandboxClient(context).execute(source, JSONObject().put("calls", vectors), op, PluginHost(op, context.cacheDir)).getJSONArray("data")
        for (i in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(i)
            if (vector.has("expected")) assertEquals(vector.getString("method"), vector.getString("expected"), result.getString(i))
            else {
                val key = java.security.KeyFactory.getInstance("RSA").generatePrivate(java.security.spec.PKCS8EncodedKeySpec(android.util.Base64.decode(vector.getString("privateKeyPkcs8Base64"), android.util.Base64.DEFAULT)))
                val plain = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
                    init(javax.crypto.Cipher.DECRYPT_MODE, key)
                    doFinal(android.util.Base64.decode(result.getString(i), android.util.Base64.DEFAULT))
                }
                assertEquals(vector.getJSONObject("args").getString("text"), plain.toString(Charsets.UTF_8))
            }
        }
    }
    @Test fun busyLoopStopsAndNextInvocationStillWorks() = runBlocking {
        val start = android.os.SystemClock.elapsedRealtime()
        try { run("while(true){}") ; fail("loop was accepted") }
        catch (e: PluginException) { assertEquals(PluginErrorCode.TIMEOUT, e.code) }
        assertTrue(android.os.SystemClock.elapsedRealtime() - start < 15000)
        assertTrue(run("globalThis.plugin={study:{terms:()=>({ok:true,data:{}})}};").getBoolean("ok"))
    }
    @Test fun memoryLimitStopsAllocation() = runBlocking {
        try { run("const x=[];while(true)x.push(new Array(100000).fill(1));"); fail("allocation was accepted") }
        catch (e: PluginException) { assertTrue(e.code in setOf(PluginErrorCode.RESOURCE_LIMIT, PluginErrorCode.TIMEOUT)) }
    }
    @Test fun cancellationInterruptsAndStaleResultsCannotReturn() = runBlocking {
        val op = operation()
        val job = launch { run("while(true){}", op) }
        delay(400)
        withTimeout(5000) { job.cancelAndJoin() }
        try { op.requireActive(); fail("operation remained active") } catch (e: PluginException) { assertEquals(PluginErrorCode.CANCELLED, e.code) }
        assertTrue(run("globalThis.plugin={study:{terms:()=>({ok:true,data:{}})}};").getBoolean("ok"))
    }
    @Test fun hostWaitDoesNotConsumeJsBudgetAndRedirectIsChecked() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("ok").setBodyDelay(6, TimeUnit.SECONDS))
            val origin = server.url("/").toString().trimEnd('/')
            val manifest = JSONObject("""{"network":[{"origin":"$origin","pathPrefix":"/query","methods":["GET"],"purposes":["query"]}]}""")
            val source = """globalThis.plugin={study:{terms:async(a,c,s)=>({ok:true,data:await s.http({url:'$origin/query',purpose:'query'})})}};"""
            assertEquals("ok", run(source, operation(manifest = manifest)).getJSONObject("data").getString("body"))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "$origin/outside"))
            try { run(source, operation(manifest = manifest)); fail("redirect escaped") }
            catch (e: PluginException) { assertEquals(PluginErrorCode.UNTRUSTED_URL, e.code) }
            assertEquals(2, server.requestCount)
        }
    }
    @androidx.test.filters.SdkSuppress(minSdkVersion = 26)
    @Test fun processExitInvalidatesQueryAndNeverReplaysSentMutation() = runBlocking {
        val device = androidx.test.uiautomator.UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation())
        for (mutation in listOf(false, true)) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
                val origin = server.url("/").toString().trimEnd('/')
                val purpose = if (mutation) "mutation" else "query"
                val method = if (mutation) "selection.select" else "study.terms"
                val manifest = PluginManifest(JSONObject("""{"id":"test.exit","version":"1.0.0","network":[{"origin":"$origin","pathPrefix":"/wait","methods":["GET"],"purposes":["$purpose"]}]}"""))
                val op = PluginOperation(sessions.session("test-exit", "test", origin), manifest, method, development = true, confirmed = mutation)
                val source = """globalThis.plugin={${method.substringBefore('.') }:{${method.substringAfter('.')}:async(a,c,s)=>({ok:true,data:await s.http({url:'$origin/wait',purpose:'$purpose'})})}};"""
                val task = async(Dispatchers.IO) { runCatching { run(source, op) } }
                assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(10, TimeUnit.SECONDS) })
                // Android appends the isolated service component to the process name.
                val pids = device.executeShellCommand("ps -A").lineSequence().map { it.trim().split(Regex("\\s+")) }
                    .filter { it.size > 2 && it.last().startsWith("${context.packageName}:academic_plugin") }
                    .map { it[1] }.filter { it.matches(Regex("[0-9]+")) }.toList()
                assertTrue("isolated service must exist in ps", pids.isNotEmpty())
                pids.forEach { device.executeShellCommand("am crash $it") }
                val error = withTimeout(10_000) { task.await().exceptionOrNull() }
                assertTrue("$error", error is PluginException)
                assertEquals(if (mutation) PluginErrorCode.RESULT_UNKNOWN else PluginErrorCode.RUNTIME_EXITED, (error as PluginException).code)
                assertEquals(1, server.requestCount)
            }
        }
        assertTrue(run("globalThis.plugin={study:{terms:()=>({ok:true,data:{}})}};").getBoolean("ok"))
    }
}
