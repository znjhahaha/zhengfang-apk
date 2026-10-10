package com.tyust.course.update

import android.app.Application
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class UpdateManagerTest {
    private lateinit var app: Application
    private var manager: UpdateManager? = null
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("app_update_v2", 0).edit().clear().commit()
        File(app.filesDir, "app-updates").deleteRecursively()
    }
    @After fun cleanup() { manager?.close() }
    private fun create(server: MockWebServer, abis: List<String> = listOf("arm64-v8a", "armeabi-v7a")): UpdateManager = UpdateManager(app, UpdateFixtures.keys,
        { listOf(server.url("/manifest").toString()) }, { it.startsWith(server.url("/").toString()) }, { abis }).also { manager = it }
    private fun awaitCheck(manager: UpdateManager) = runBlocking {
        withTimeout(8000) { manager.state.first { it.check !in setOf(UpdateManager.Check.IDLE, UpdateManager.Check.CHECKING) } }
    }
    @Test fun invalidJsonIsFailureNotUpToDate() { MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("<html>error</html>")); val m = create(server); m.checkForUpdate(true)
        assertEquals(UpdateManager.Check.FAILED, awaitCheck(m).check)
    } }
    @Test fun successfulManifestOffersUpdate() { MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody(UpdateFixtures.envelope(UpdateFixtures.payload().put("versionCode", 1000).put("versionName", "1.0.1000").toString())))
        val m = create(server); m.checkForUpdate(true); val result = awaitCheck(m)
        assertEquals(UpdateManager.Check.AVAILABLE, result.check); assertTrue(result.showDialog)
    } }
    @Test fun migratedClientCanCheckOlderStableReleaseWithoutDowngrade() { MockWebServer().use { server ->
        val m = create(server)
        assertFalse(m.state.value.testChannel)
        val stable = UpdateFixtures.payload().put("versionCode", m.getCurrentVersionCode() - 1).put("versionName", "1.0.97")
        server.enqueue(MockResponse().setBody(UpdateFixtures.envelope(stable.toString())))
        m.checkForUpdate(true)
        assertEquals(UpdateManager.Check.UP_TO_DATE, awaitCheck(m).check)
        assertEquals(UpdateManager.Phase.IDLE, m.state.value.phase)
    } }
    @Test fun defaultChannelRejectsTestManifestInsteadOfSilentlyOptingIn() { MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody(UpdateFixtures.envelope(UpdateFixtures.payload().put("channel", "test").toString())))
        val m = create(server); m.checkForUpdate(true)
        assertEquals(UpdateManager.Check.FAILED, awaitCheck(m).check)
        assertFalse(m.state.value.testChannel)
    } }
    @Test fun concurrentChecksShareOneRequest() { MockWebServer().use { server ->
        server.enqueue(MockResponse().setBodyDelay(100, TimeUnit.MILLISECONDS).setBody(UpdateFixtures.envelope()))
        val m = create(server); repeat(20) { m.checkForUpdate(true) }; awaitCheck(m)
        assertEquals(1, server.requestCount)
    } }
    @Test fun failedRefreshPreservesVerifiedCache() { MockWebServer().use { server ->
        val dir = File(app.filesDir, "app-updates").apply { mkdirs() }
        File(dir, "stable-manifest.json").writeText(UpdateFixtures.envelope())
        server.enqueue(MockResponse().setResponseCode(503)); val m = create(server); m.checkForUpdate(true)
        val result = awaitCheck(m); assertEquals(UpdateManager.Check.CACHED, result.check); assertNotNull(result.manifest)
    } }
    @Test fun tamperedCacheNotTrusted() { MockWebServer().use { server ->
        val dir = File(app.filesDir, "app-updates").apply { mkdirs() }; File(dir, "stable-manifest.json").writeText("{}")
        server.enqueue(MockResponse().setResponseCode(503)); val m = create(server); m.checkForUpdate(true)
        assertEquals(UpdateManager.Check.FAILED, awaitCheck(m).check)
    } }
    @Test fun sameVersionRepackIsNotAccepted() { MockWebServer().use { server ->
        val dir = File(app.filesDir, "app-updates").apply { mkdirs() }; File(dir, "stable-manifest.json").writeText(UpdateFixtures.envelope())
        server.enqueue(MockResponse().setBody(UpdateFixtures.envelope(UpdateFixtures.payload().put("sha256", "c".repeat(64)).put("revision", 101).toString())))
        val m = create(server); m.checkForUpdate(true); val result = awaitCheck(m)
        assertEquals(UpdateManager.Check.CACHED, result.check); assertEquals("a".repeat(64), result.manifest!!.sha256)
    } }
    @Test fun onlyMatching304CanUseStoredEnvelope() { MockWebServer().use { server ->
        val dir = File(app.filesDir, "app-updates").apply { mkdirs() }; val envelope = UpdateFixtures.envelope()
        File(dir, "stable-manifest.json").writeText(envelope)
        app.getSharedPreferences("app_update_v2", 0).edit().putString("etag:${server.url("/manifest")}", "one")
            .putString("etag-payload:${server.url("/manifest")}", envelope).commit()
        server.enqueue(MockResponse().setResponseCode(304)); val m = create(server); m.checkForUpdate(true)
        assertNotEquals(UpdateManager.Check.CACHED, awaitCheck(m).check)
        assertEquals("one", server.takeRequest().getHeader("If-None-Match"))
    } }
    @Test fun automaticFailureDoesNotRemindFromCache() { MockWebServer().use { server ->
        val dir = File(app.filesDir, "app-updates").apply { mkdirs() }
        File(dir, "stable-manifest.json").writeText(UpdateFixtures.envelope(UpdateFixtures.payload().put("versionCode", 1000).put("versionName", "1.0.1000").toString()))
        server.enqueue(MockResponse().setResponseCode(503)); val m = create(server); m.checkForUpdate()
        val state = awaitCheck(m); assertEquals(UpdateManager.Check.CACHED, state.check); assertFalse(state.showDialog)
    } }
    @Test fun verifiedTaskResumesAsPausedAndCancelDeletesPartial() { MockWebServer().use { server ->
        val dir = File(app.filesDir, "app-updates").apply { mkdirs() }
        val envelope = UpdateFixtures.envelope(UpdateFixtures.payload().put("versionCode", 1000).put("versionName", "1.0.1000").toString())
        File(dir, "task.json").writeText(org.json.JSONObject().put("envelope", envelope).put("channel", "stable").toString())
        File(dir, "download.part").writeText("abc")
        val m = create(server)
        val state = runBlocking { withTimeout(5000) { m.state.first { it.phase == UpdateManager.Phase.PAUSED } } }
        assertEquals(3L, state.bytes); assertFalse(state.active)
        m.pauseDownload(cancel = true)
        runBlocking { withTimeout(5000) { m.state.first { it.phase == UpdateManager.Phase.CANCELLED } } }
        assertFalse(File(dir, "download.part").exists()); assertFalse(File(dir, "task.json").exists())
    } }
    @Test fun invalidArchiveNeverBecomesInstallable() { MockWebServer().use { server ->
        val file = File(app.cacheDir, "fake.apk").apply { writeText("bad") }
        val m = create(server)
        assertThrows(UpdateFailure::class.java) { m.verifyApk(UpdateFixtures.manifest(), file) }
    } }
    @Test fun freshCheckOffersDeviceArtifactAndStoresOriginalSignedEnvelope() { MockWebServer().use { server ->
        val payload = UpdateFixtures.splitPayload().put("versionCode", 1000).put("versionName", "1.0.1000")
        val envelope = UpdateFixtures.envelope(payload.toString())
        server.enqueue(MockResponse().setBody(envelope))
        val m = create(server, listOf("armeabi-v7a")); m.checkForUpdate(true)
        val result = awaitCheck(m)
        assertEquals("armeabi-v7a", result.manifest!!.selectedAbi); assertEquals(3L, result.manifest!!.size)
        val cached = UpdateManifestVerifier.verify(File(app.filesDir, "app-updates/stable-manifest.json").readText(), UpdateFixtures.keys, "stable")
        assertNull(cached.selectedAbi); assertEquals(6L, cached.size)
    } }
    @Test fun savedArm32DownloadDoesNotChangeToArm64OnResume() { MockWebServer().use { server ->
        val raw = UpdateFixtures.manifest(UpdateFixtures.splitPayload().put("versionCode", 1000))
        val selected = raw.forAbi("armeabi-v7a")
        val dir = File(app.filesDir, "app-updates").apply { mkdirs() }
        File(dir, "task.json").writeText(org.json.JSONObject().put("envelope", raw.envelope).put("channel", "stable")
            .put("artifactAbi", "armeabi-v7a").put("artifactIdentity", selected.identity).toString())
        File(dir, "download.part").writeText("ab")
        val m = create(server)
        val value = runBlocking { withTimeout(5000) { m.state.first { it.phase == UpdateManager.Phase.PAUSED } } }
        assertEquals("armeabi-v7a", value.manifest!!.selectedAbi); assertEquals(2L, value.bytes)
    } }
    @Test fun oldUpdaterTaskRetainsUniversalDownloadEvenWhenSplitManifestExists() { MockWebServer().use { server ->
        val raw = UpdateFixtures.manifest(UpdateFixtures.splitPayload().put("versionCode", 1000))
        val dir = File(app.filesDir, "app-updates").apply { mkdirs() }
        File(dir, "task.json").writeText(org.json.JSONObject().put("envelope", raw.envelope).put("channel", "stable").toString())
        File(dir, "download.part").writeText("abc")
        val m = create(server)
        val value = runBlocking { withTimeout(5000) { m.state.first { it.phase == UpdateManager.Phase.PAUSED } } }
        assertNull(value.manifest!!.selectedAbi); assertEquals(6L, value.manifest!!.size)
    } }
    @Test fun mismatchedSavedArtifactNeverResumesPartialDownload() { MockWebServer().use { server ->
        val raw = UpdateFixtures.manifest(UpdateFixtures.splitPayload().put("versionCode", 1000))
        val dir = File(app.filesDir, "app-updates").apply { mkdirs() }
        File(dir, "task.json").writeText(org.json.JSONObject().put("envelope", raw.envelope).put("channel", "stable")
            .put("artifactAbi", "armeabi-v7a").put("artifactIdentity", raw.forAbi("arm64-v8a").identity).toString())
        File(dir, "download.part").writeText("ab")
        server.enqueue(MockResponse().setBody(raw.envelope))
        val m = create(server); m.checkForUpdate(true)
        assertEquals(UpdateManager.Phase.IDLE, awaitCheck(m).phase)
        assertEquals(0L, m.state.value.bytes)
    } }

}
