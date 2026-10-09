package com.tyust.course.academic.plugin

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.tyust.course.academic.AcademicSession
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
// API 30+ AtomicFile needs POSIX rename-over-existing, which Windows cannot emulate.
// NativeWebLoginDeviceTest also checks updates and deletion on the actual Android filesystem.
@Config(sdk = [28], application = Application::class)
class NativeWebCookieStoreTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val accounts get() = PluginServiceAccounts(app)
    private val login = "https://login.example.test"
    private val api = "https://api.example.test"
    private val pkg = PluginPackage(PluginManifest(JSONObject("""{
        "id":"test.native.web.cookies","kind":"native","version":"1.0.0","apiVersion":3,
        "servers":[{"id":"site","origin":"https://login.example.test","authentication":"web",
        "cookieOrigins":["https://login.example.test","https://api.example.test"]}]
    }""")), "", "synthetic", false)
    private val fixtureKey = { SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES") }

    private fun store(session: AcademicSession = accounts.session(pkg, "site"), owner: PluginPackage = pkg): NativeWebCookieStore =
        NativeWebCookieStore(NativePluginVault(app, PluginStorageScope.session(session, owner.manifest.id, !owner.official),
            keyProvider = fixtureKey), setOf(login, api)) { accounts.current(owner, session) }

    private fun jar(store: NativeWebCookieStore) = ScopedWebCookieJar(login, { true }, store::header, store::set, setOf(api))

    @Test fun nativeLoginCookiesSurviveNewSessionsWithoutAWebViewAndStayEncrypted() {
        val first = jar(store())
        val url = "$login/login?token=synthetic-query".toHttpUrl()
        first.saveFromResponse(url, listOf(Cookie.parse(url,
            "SESSION=synthetic-private-login; Domain=example.test; Path=/; Secure; HttpOnly")!!))
        val restored = jar(store())
        assertEquals("synthetic-private-login", restored.loadForRequest("$api/courses".toHttpUrl()).single().value)
        val saved = File(app.noBackupFilesDir, "native-plugin-vault").walkTopDown().filter { it.isFile }.toList()
        assertTrue(saved.isNotEmpty())
        assertTrue(saved.all { !it.readText().contains("synthetic-private-login") })
    }

    @Test fun storedCookiesRespectExactOriginsHostOnlyPathAndExpiry() {
        val cookies = store()
        cookies.set("$login/login", "SHARED=one; Domain=example.test; Path=/course; Secure; HttpOnly")
        cookies.set("$login/login", "HOST=one; Path=/; Secure")
        assertEquals("SHARED=one", cookies.header("$api/course/list"))
        assertEquals("", cookies.header("$api/courses"))
        for (url in listOf("http://api.example.test/course/list", "$api:8443/course/list",
            "https://unlisted.example.test/course/list", "https://api.example.test.attacker.test/course/list")) {
            assertEquals("", cookies.header(url))
            cookies.set(url, "FOREIGN=bad; Domain=example.test; Path=/")
        }
        cookies.set("$login/login", "SHARED=deleted; Domain=example.test; Path=/course; Max-Age=0; Secure")
        assertEquals("", cookies.header("$api/course/list"))
        assertEquals("HOST=one", cookies.header("$login/course/list"))
    }

    @Test fun concurrentNativePagesMergeUpdatesFromTheSameAccountVault() {
        val first = store()
        val second = store()
        first.set(login, "FIRST=one; Path=/")
        second.set(login, "SECOND=two; Path=/")
        assertEquals(setOf("FIRST=one", "SECOND=two"), store().header(login).split("; ").toSet())
        first.set(login, "FIRST=gone; Path=/; Max-Age=0")
        assertEquals("SECOND=two", second.header(login))
    }

    @Test fun accountPluginAndDevelopmentScopesNeverShareLoginCookies() {
        accounts.select(pkg, "site", "First")
        store().set(login, "SESSION=first-account; Path=/")
        accounts.select(pkg, "site", "Second")
        assertEquals("", store().header(login))
        val other = pkg.copy(manifest = PluginManifest(JSONObject(pkg.manifest.json.toString()).put("id", "test.other.web.cookies")))
        assertEquals("", store(accounts.session(other, "site"), other).header(login))
        accounts.select(pkg, "site", "First")
        assertEquals("SESSION=first-account", store().header(login))
        val official = pkg.copy(official = true)
        assertEquals("", store(accounts.session(official, "site"), official).header(login))
    }

    @Test fun removingAnAccountPreventsLateResponsesFromRestoringItsCookies() {
        val account = accounts.select(pkg, "site", "Current")
        val stale = store()
        stale.set(login, "SESSION=old; Path=/")
        accounts.clearCurrent(pkg, "site", account)
        assertThrows(PluginException::class.java) { stale.header(login) }
        assertThrows(PluginException::class.java) { stale.set(login, "SESSION=late; Path=/") }
        assertEquals("", store().header(login))
    }

    @Test fun profileMigrationKeepsCookieAttributesAndRemovesTheNativeCopyAfterFlush() {
        val cookies = store()
        cookies.set("$login/login?token=synthetic-query", "SESSION=one; Domain=example.test; Path=/; Secure; HttpOnly")
        val migrated = mutableListOf<Pair<String, String>>()
        var flushed = false
        cookies.migrate({ url, value -> migrated += url to value }, { flushed = true })
        assertTrue(flushed)
        assertEquals(1, migrated.size)
        assertEquals("$login/login", migrated.single().first)
        val cookie = Cookie.parse(migrated.single().first.toHttpUrl(), migrated.single().second)!!
        assertTrue(cookie.secure && cookie.httpOnly && !cookie.hostOnly)
        assertEquals("example.test", cookie.domain)
        assertEquals("", store().header(login))
        cookies.migrate({ _, _ -> fail("Already migrated") }, { fail("Already migrated") })
    }

    @Test fun failedMigrationRetainsTheNativeSessionForRetry() {
        val cookies = store()
        cookies.set(login, "SESSION=one; Path=/; Secure")
        assertThrows(IllegalStateException::class.java) {
            cookies.migrate({ _, _ -> }, { throw IllegalStateException("synthetic flush failure") })
        }
        assertEquals("SESSION=one", store().header(login))
    }

    @Test fun embeddedMigrationOnlyCompletesAfterUnchangedSnapshotAndVerifiedCommit() {
        val cookies = store()
        cookies.set(login, "SESSION=one; Path=/; Secure; HttpOnly")
        val snapshot = cookies.migrationSnapshot()!!
        cookies.set(login, "SESSION=two; Path=/; Secure; HttpOnly")
        var marked = false
        assertThrows(PluginException::class.java) { cookies.finishMigration(snapshot) { marked = true } }
        assertFalse(marked)
        assertEquals("SESSION=two", cookies.header(login))
        val latest = cookies.migrationSnapshot()!!
        assertThrows(IllegalStateException::class.java) { cookies.finishMigration(latest) { throw IllegalStateException("not verified") } }
        assertEquals("SESSION=two", cookies.header(login))
        cookies.finishMigration(latest) { marked = true }
        assertTrue(marked)
        assertNull(cookies.migrationSnapshot())
    }
}
