package com.tyust.course.academic.plugin

import android.app.Application
import java.io.IOException
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PluginUserscriptDownloaderTest {
    private val root = "https://update.greasyfork.org/scripts/123/"
    private val meta = root + "%E5%8E%9F%E8%84%9A%E6%9C%AC.meta.js"
    private val script = root + "%E5%8E%9F%E8%84%9A%E6%9C%AC.user.js"
    private val calls = mutableListOf<Request>()
    private var active = true
    private val original = """
        // ==UserScript==
        // @name 原脚本 fixture
        // @version 1.0.0
        // @author Original author
        // @license MIT
        // @updateURL $meta
        // @downloadURL $script
        // ==/UserScript==
        void 0;
    """.trimIndent()

    private fun policy(prefix: String = root) = PluginUserscriptPolicy(
        JSONObject().put("id", "original").put("serverId", "site").put("sourcePrefix", prefix)
            .put("matches", org.json.JSONArray()).put("connect", org.json.JSONArray()),
        PluginManifest(JSONObject().put("id", "test.script")))

    private fun downloader(policy: PluginUserscriptPolicy = policy(), transport: (Request) -> Response) =
        PluginUserscriptDownloader(policy, {
            if (!active) throw PluginException(PluginErrorCode.STALE_CONTEXT, "Account removed")
        }, OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).addInterceptor { chain ->
            calls.add(chain.request()); transport(chain.request())
        }.build())

    private fun response(request: Request, text: String = original, code: Int = 200,
                         headers: Map<String, String> = emptyMap()): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Synthetic")
            .body(text.toResponseBody("application/octet-stream".toMediaType()))
            .apply { headers.forEach { (key, value) -> header(key, value) } }.build()

    private fun relayResponse(request: Request, upstream: String = script, text: String = original) = response(request, text,
        headers = mapOf("X-Userscript-Source" to upstream, "X-Userscript-Sha256" to PluginJson.sha256(text.toByteArray())))

    @Test fun downloadsMetadataAndFullOriginalWhileTheUpstreamIsUnreachable() {
        val downloader = downloader { request ->
            if (request.url.host != "plugins.hidisiwa.xyz") throw IOException("Upstream blocked")
            relayResponse(request, if (request.url.encodedPath.endsWith(".meta.js")) meta else script)
        }
        assertEquals(original, downloader.download(meta))
        assertEquals(original, downloader.download(script))
        assertEquals(2, calls.size)
        assertTrue(calls.all { it.url.host == "plugins.hidisiwa.xyz" })
        assertEquals("1.0.0", UserscriptMetadata.validate(downloader.download(script), policy()).version)
        for (request in calls) {
            assertNull(request.header("Cookie")); assertNull(request.header("Authorization"))
            assertEquals("GET", request.method)
        }
    }

    @Test fun manualChecksRevalidateBothMetadataAndOriginal() {
        val downloader = downloader { relayResponse(it, if (it.url.encodedPath.endsWith(".meta.js")) meta else script) }
        downloader.download(meta, revalidate = true); downloader.download(script, revalidate = true)
        assertTrue(calls.all { it.header("Cache-Control") == "no-cache" })
    }

    @Test fun onlyCanonicalGreasyForkDownloadPathsUseTheRelay() {
        assertEquals("https://plugins.hidisiwa.xyz/api/userscripts/v1/greasyfork/scripts/123/456/original.user.js",
            PluginUserscriptDownloader.relayUrl((root + "456/original.user.js").toHttpUrl()).toString())
        for (url in listOf("http://update.greasyfork.org/scripts/123/code.user.js",
            "https://update.greasyfork.org:8443/scripts/123/code.user.js", "https://other.test/scripts/123/code.user.js",
            "https://user:secret@update.greasyfork.org/scripts/123/code.user.js", script + "?key=private", script + "#fragment",
            root + "extra/code.user.js", root + "code.json", root + "code%252f.user.js", root + "code%2f.user.js",
            root + "code%5c.user.js", root + "code%00.user.js", root + "code%3f.user.js")) {
            assertNull(url, PluginUserscriptDownloader.relayUrl(url.toHttpUrl()))
        }
    }

    @Test fun outOfDeclarationUrlsAreRejectedBeforeEitherTransport() {
        val downloader = downloader { error("No request should be made") }
        for (url in listOf(script.replace("/123/", "/124/"), script.replace("update.greasyfork.org", "other.test"))) {
            assertThrows(PluginException::class.java) { downloader.download(url) }
        }
        assertTrue(calls.isEmpty())
    }

    @Test fun unavailableOrCorruptRelayFallsBackOnlyToTheDeclaredOriginal() {
        for (mode in listOf("offline", "http", "html", "source", "digest", "redirect")) {
            calls.clear()
            val downloader = downloader { request ->
                if (request.url.host == "update.greasyfork.org") return@downloader response(request)
                when (mode) {
                    "offline" -> throw IOException("Relay offline")
                    "http" -> response(request, code = 503)
                    "html" -> relayResponse(request, text = "<html>Unavailable</html>")
                    "source" -> relayResponse(request, upstream = script.replace("/123/", "/124/"))
                    "digest" -> relayResponse(request).newBuilder().header("X-Userscript-Sha256", "0".repeat(64)).build()
                    else -> response(request, code = 302, headers = mapOf("Location" to "https://outside.test/"))
                }
            }
            assertEquals(mode, original, downloader.download(script))
            assertEquals(mode, listOf("plugins.hidisiwa.xyz", "update.greasyfork.org"), calls.map { it.url.host })
            assertEquals(script, calls.last().url.toString())
        }
    }

    @Test fun otherDeclaredSourcesKeepTheirDirectDownloadBehavior() {
        val url = "https://updates.test/scripts/123/code.user.js"
        val downloader = downloader(policy("https://updates.test/scripts/123/")) { response(it) }
        assertEquals(original, downloader.download(url))
        assertEquals(listOf(url), calls.map { it.url.toString() })
    }

    @Test fun directFallbackFollowsOnlyRedirectsInsideTheDeclaredSource() {
        val downloader = downloader { request ->
            when {
                request.url.host == "plugins.hidisiwa.xyz" -> throw IOException("Relay offline")
                request.url.toString() == script -> response(request, code = 302, headers = mapOf("Location" to "456/code.user.js"))
                else -> response(request)
            }
        }
        assertEquals(original, downloader.download(script))
        assertEquals(root + "456/code.user.js", calls.last().url.toString())
    }

    @Test fun escapedOrEndlessDirectRedirectsCannotChangeTrust() {
        for (location in listOf("https://outside.test/code.user.js", "../124/code.user.js", "http://update.greasyfork.org/scripts/123/code.user.js", script)) {
            calls.clear()
            val downloader = downloader { request ->
                if (request.url.host == "plugins.hidisiwa.xyz") throw IOException("Relay offline")
                response(request, code = 302, headers = mapOf("Location" to location))
            }
            assertThrows(PluginException::class.java) { downloader.download(script) }
            assertTrue(calls.size <= 5)
            assertTrue(calls.drop(1).all { it.url.host == "update.greasyfork.org" && it.url.isHttps })
        }
    }

    @Test fun bothOfflineReturnsAUsefulRetryableFailure() {
        val failure = assertThrows(PluginException::class.java) { downloader { throw IOException("Offline") }.download(script) }
        assertEquals(PluginErrorCode.NETWORK_RETRYABLE, failure.code)
        assertTrue(failure.message!!.contains("下载连接失败"))
        assertEquals(2, calls.size)
    }

    @Test fun revokedAccountCannotFinishOrStartAFallback() {
        for (offline in listOf(false, true)) {
            active = true; calls.clear()
            val downloader = downloader { request ->
                active = false
                if (offline) throw IOException("Account removed while waiting")
                relayResponse(request)
            }
            val failure = assertThrows(PluginException::class.java) { downloader.download(script) }
            assertEquals(PluginErrorCode.STALE_CONTEXT, failure.code)
            assertEquals(1, calls.size)
        }
    }

    @Test fun cancellationNeverTriggersTheFallback() {
        assertThrows(CancellationException::class.java) { downloader { throw CancellationException() }.download(script) }
        assertEquals(1, calls.size)
    }

    @Test fun overLimitContentCannotBeAcceptedFromEitherTransport() {
        val source = original + "x".repeat(2 * 1024 * 1024)
        val downloader = downloader { request ->
            if (request.url.host == "plugins.hidisiwa.xyz") relayResponse(request, text = source) else response(request, source)
        }
        val failure = assertThrows(PluginException::class.java) { downloader.download(script) }
        assertEquals(PluginErrorCode.RESOURCE_LIMIT, failure.code)
        assertEquals(2, calls.size)
    }
}
