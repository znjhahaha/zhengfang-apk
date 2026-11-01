package com.tyust.course.academic.plugin

import android.app.Application
import com.tyust.course.academic.AcademicSession
import com.tyust.course.academic.AcademicSessionStore
import okhttp3.Cookie
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.URLEncoder
import java.net.ServerSocket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Real local HTTP through PluginHost; grants and their HTTPS policy have separate tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class PluginServiceHostSecurityTest {
    private val mobile = "synthetic-mobile-+86"
    private val otp = "synthetic-otp-012345"
    private val mobileHandle = "opaque-synthetic-mobile"
    private val otpHandle = "opaque-synthetic-otp"
    private val sessionToken = "synthetic-protected-session-token"
    private val key = ByteArray(16) { (it + 1).toByte() }
    private val iv = ByteArray(16) { (31 - it).toByte() }
    private val operations = mutableListOf<PluginOperation>()
    private val sessions = mutableListOf<AcademicSession>()

    @After fun cleanup() {
        operations.forEach(PluginOperation::close)
        sessions.forEach(AcademicSession::retire)
        PluginHttpClients.clearPlugin("test.cpdaily.service-security")
    }

    private fun server() = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
    private fun manifest(vararg servers: MockWebServer, version: Int = 5) = PluginManifest(JSONObject()
        .put("id", "test.cpdaily.service-security").put("version", "0.1.0")
        .put("apiVersion", 3).put("kind", "native")
        .put("permissions", JSONArray(listOf("network", "auth", "credentials")))
        .put("requires", JSONArray().put(JSONObject().put("name", "network.request").put("version", version)))
        .put("network", JSONArray(servers.map { server -> JSONObject()
            .put("origin", server.url("/").toString().trimEnd('/')).put("pathPrefix", "/api")
            .put("methods", JSONArray(listOf("GET", "POST")))
            .put("purposes", JSONArray(listOf("query", "auth"))) })))

    private fun session(server: MockWebServer) = AcademicSessionStore()
        .session("synthetic-school", "synthetic-account", server.url("/").toString())
        .also(sessions::add)

    private fun operation(server: MockWebServer, method: String = "serviceAuth.start",
                          manifest: PluginManifest = manifest(server), session: AcademicSession = session(server),
                          active: () -> Boolean = { true }) =
        PluginOperation(session, manifest, method, packageDigest = "a".repeat(64), scopeStillActive = active)
            .also(operations::add)

    private fun host(operation: PluginOperation, resolver: ((String) -> JSONObject)? = null) = PluginHost(
        operation, RuntimeEnvironment.getApplication().cacheDir, credentialResolver = resolver)

    private fun resolve(handle: String): JSONObject = when (handle) {
        mobileHandle -> JSONObject().put("mobile", mobile)
        otpHandle -> JSONObject().put("otp", otp)
        else -> throw AssertionError("Unexpected synthetic credential handle")
    }

    private fun login(server: MockWebServer) = JSONObject()
        .put("url", server.url("/api/login").toString()).put("method", "POST").put("purpose", "auth")
        .put("headers", JSONObject().put("Content-Type", "application/json"))
        .put("body", """{"a":"","b":"first_v4"}""")
        .put("credentials", JSONObject().put("mobile", mobileHandle).put("otp", otpHandle))
        .put("bindings", JSONObject().put("body", JSONObject().put("a", JSONObject()
            .put("fields", JSONArray()
                .put(JSONObject().put("key", "c").put("from", "mobile.mobile"))
                .put(JSONObject().put("key", "d").put("from", "otp.otp"))
                .put(JSONObject().put("key", "e").put("value", "CPDAILY"))
                .put(JSONObject().put("key", "f").put("value", "86")))
            .put("transform", JSONObject().put("type", "aes-cbc")
                .put("keyBase64", key.toByteString().base64()).put("ivBase64", iv.toByteString().base64()))
            .put("encoding", "tc2"))))

    private fun query(server: MockWebServer, header: String = "SessionToken") = JSONObject()
        .put("url", server.url("/api/me").toString()).put("method", "GET").put("purpose", "query")
        .put("headers", JSONObject().put(header, sessionToken))

    private fun denied(code: PluginErrorCode, block: () -> Unit): PluginException =
        assertThrows(PluginException::class.java) { block() }.also { assertEquals(code, it.code) }

    private fun assertNoSecrets(value: String) {
        for (secret in listOf(mobile, otp, mobileHandle, otpHandle, sessionToken)) assertFalse(value, value.contains(secret))
    }

    @Test fun serviceAuthV5SendsEncryptedOrderedMobileAndOtpThroughRealHttp() {
        server().use { server ->
            server.enqueue(MockResponse().setBody("{\"authenticated\":true}"))
            val resolved = mutableListOf<String>()
            val host = host(operation(server)) { handle -> resolved += handle; resolve(handle) }
            val request = login(server)
            val original = request.toString()
            val response = host.call("http", request)
            assertEquals(200, response.getJSONObject("data").getInt("status"))
            assertEquals(listOf(mobileHandle, otpHandle).sorted(), resolved.sorted())
            assertEquals("The caller's opaque request is not mutated", original, request.toString())
            val sent = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("POST", sent.method)
            assertEquals("/api/login", sent.path)
            assertTrue(sent.getHeader("Content-Type")!!.startsWith("application/json"))
            val wire = sent.body.readUtf8()
            val body = JSONObject(wire)
            assertEquals(setOf("a", "b"), body.keys().asSequence().toSet())
            assertEquals("first_v4", body.getString("b"))
            val wrapped = body.getString("a")
            val encrypted = wrapped.replace(" ", "").decodeBase64()!!.toByteArray()
            val expectedTc2 = encrypted.toList().chunked(45).joinToString("") { chunk ->
                chunk.toByteArray().toByteString().base64() + if (chunk.size == 45) " " else ""
            }
            assertEquals(expectedTc2, wrapped)
            val plaintext = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(this@PluginServiceHostSecurityTest.key, "AES"),
                    IvParameterSpec(this@PluginServiceHostSecurityTest.iv))
            }.doFinal(encrypted).toString(Charsets.UTF_8)
            assertEquals("""{"c":"$mobile","d":"$otp","e":"CPDAILY","f":"86"}""", plaintext)
            assertNoSecrets(wire)
            assertNoSecrets(response.toString())
            assertNoSecrets(host.report().toString())
        }
    }

    @Test fun deniedDestinationNeverResolvesCredentialsOrSendsHttp() {
        server().use { allowed -> server().use { other ->
            repeat(3) { allowed.enqueue(MockResponse().setBody("unexpected request")) }
            other.enqueue(MockResponse().setBody("unexpected request"))
            var resolutions = 0
            for (url in listOf(allowed.url("/outside/login"), allowed.url("/api-evil/login"), other.url("/api/login"))) {
                val host = host(operation(allowed)) { resolutions++; resolve(it) }
                denied(PluginErrorCode.UNTRUSTED_URL) { host.call("http", login(allowed).put("url", url.toString())) }
                assertNoSecrets(host.report().toString())
            }
            assertEquals(0, resolutions)
            assertEquals(0, allowed.requestCount)
            assertEquals(0, other.requestCount)
        } }
    }

    @Test fun v5OptInAndValidAliasBindingsAreRequiredBeforeVaultAccess() {
        server().use { server ->
            var resolutions = 0
            val oldHost = host(operation(server, manifest = manifest(server, version = 4))) { resolutions++; resolve(it) }
            denied(PluginErrorCode.VALIDATION_FAILED) { oldHost.call("http", login(server)) }
            val invalid = login(server).apply {
                getJSONObject("bindings").getJSONObject("body").getJSONObject("a")
                    .getJSONArray("fields").getJSONObject(1).put("from", "missing.otp")
            }
            denied(PluginErrorCode.VALIDATION_FAILED) {
                host(operation(server)) { resolutions++; resolve(it) }.call("http", invalid)
            }
            assertEquals(0, resolutions)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun staleOperationEpochRetiredSessionAndChangedAccountFailBeforeCredentialAccess() {
        server().use { server ->
            var resolutions = 0
            for (stale in listOf("closed", "epoch", "retired", "account")) {
                val session = session(server)
                var selectedAccount = "synthetic-account"
                val op = operation(server, session = session, active = { selectedAccount == session.key.accountKey })
                val host = host(op) { resolutions++; resolve(it) }
                when (stale) {
                    "closed" -> op.close()
                    "epoch" -> session.invalidate()
                    "retired" -> session.retire()
                    "account" -> selectedAccount = "synthetic-other-account"
                }
                val expected = if (stale == "closed") PluginErrorCode.CANCELLED else PluginErrorCode.SESSION_EXPIRED
                denied(expected) {
                    host.call("http", login(server).put("accountId", "synthetic-account").put("sessionEpoch", op.epoch))
                }
            }
            assertEquals(0, resolutions)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun sessionTokenHeadersAreAcceptedOnlyInProtectedV5OperationGroups() {
        server().use { server ->
            val protected = listOf("serviceAuth.start", "serviceAuth.resume", "serviceAuth.validate", "serviceAuth.logout",
                "services.invoke", "workflow.prepare", "workflow.step")
            for (method in protected) {
                server.enqueue(MockResponse().setBody("{\"ok\":true}"))
                val host = host(operation(server, method))
                assertEquals(200, host.call("http", query(server)).getJSONObject("data").getInt("status"))
                assertEquals(sessionToken, server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("SessionToken"))
                assertNoSecrets(host.report().toString())
            }
            for (method in listOf("host.effect", "ui.init", "task.run", "data.refresh", "command.run", "auth.start", "service.page")) {
                denied(PluginErrorCode.VALIDATION_FAILED) { host(operation(server, method)).call("http", query(server)) }
            }
            for (method in protected) {
                denied(PluginErrorCode.VALIDATION_FAILED) {
                    host(operation(server, method, manifest(server, version = 4))).call("http", query(server))
                }
            }
            assertEquals(protected.size, server.requestCount)
        }
    }

    @Test fun protocolTokensCannotFollowCrossOriginRedirectsEvenToDeclaredTargets() {
        server().use { source -> server().use { target ->
            target.enqueue(MockResponse().setBody("must not receive a protocol token"))
            for (header in listOf("SessionToken", "sessiontokenkey", "AmpCookies", "CasPCookies", "TGC")) {
                for (status in listOf(301, 302, 303, 307, 308)) {
                    source.enqueue(MockResponse().setResponseCode(status).setHeader("Location", target.url("/api/result")))
                    val host = host(operation(source, manifest = manifest(source, target)))
                    denied(PluginErrorCode.UNTRUSTED_URL) { host.call("http", query(source, header)) }
                    assertEquals(sessionToken, source.takeRequest(1, TimeUnit.SECONDS)!!.getHeader(header))
                    assertEquals(0, target.requestCount)
                    assertNoSecrets(host.report().toString())
                }
            }
        } }
    }

    @Test fun sameOriginRedirectKeepsProtectedSessionToken() {
        server().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/api/result"))
            server.enqueue(MockResponse().setBody("safe-result"))
            val host = host(operation(server, "services.invoke"))
            assertEquals("safe-result", host.call("http", query(server)).getJSONObject("data").getString("body"))
            assertEquals(sessionToken, server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("SessionToken"))
            val redirected = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("/api/result", redirected.path)
            assertEquals(sessionToken, redirected.getHeader("SessionToken"))
            assertNoSecrets(host.report().toString())
        }
    }

    @Test fun privateV5HookReturnsRedirectStatusAndLocationWithoutFollowingDeclaredSecondHost() {
        server().use { source -> server().use { target ->
            target.enqueue(MockResponse().setBody("must not be requested"))
            val location = target.url("/api/result?ticket=synthetic-ticket").toString()
            val statuses = listOf(301, 302, 303, 307, 308)
            for (status in statuses) {
                source.enqueue(MockResponse().setResponseCode(status).setHeader("Location", location)
                    .setBody("redirect body is not exposed"))
                val host = host(operation(source, manifest = manifest(source, target)))
                val result = host.call("http", query(source).put("purpose", "auth").put("followRedirects", false))
                    .getJSONObject("data")
                assertEquals(status, result.getInt("status"))
                assertEquals(source.url("/api/me").toString(), result.getString("url"))
                assertEquals(location, result.getJSONObject("headers").getString("Location"))
                assertEquals("", result.getString("body"))
                val sent = source.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals("/api/me", sent.path)
                assertEquals(sessionToken, sent.getHeader("SessionToken"))
                assertEquals(0, target.requestCount)
                assertNoSecrets(host.report().toString())
            }
            assertEquals(statuses.size, source.requestCount)
        } }
    }

    @Test fun manualRedirectFlagIsDeniedForUiEffectOldV4AndMutationBeforeRequestPreflight() {
        server().use { server ->
            repeat(3) { server.enqueue(MockResponse().setBody("must not be requested")) }
            for ((method, version, purpose) in listOf(
                Triple("host.effect", 5, "auth"),
                Triple("serviceAuth.start", 4, "auth"),
                Triple("serviceAuth.start", 5, "mutation")
            )) {
                val manifest = manifest(server, version = version)
                manifest.network.single().getJSONArray("purposes").put("mutation")
                var preflights = 0
                val host = PluginHost(operation(server, method, manifest), RuntimeEnvironment.getApplication().cacheDir,
                    requestGuard = { _, _, _, _ -> preflights++ })
                // No credential binding or protocol header can independently reject the v4 case.
                val request = JSONObject().put("url", server.url("/api/login").toString())
                    .put("method", "POST").put("purpose", purpose).put("body", "{}")
                    .put("followRedirects", false)
                denied(PluginErrorCode.VALIDATION_FAILED) { host.call("http", request) }
                // This also distinguishes the flag guard from later mutation-confirmation denial.
                assertEquals("$method/v$version/$purpose must fail at the field guard", 0, preflights)
                assertEquals(0, server.requestCount)
            }
        }
    }

    @Test fun privateV5ReadOmitsExistingCookiesWithoutClearingTheSessionJar() {
        server().use { server ->
            for (method in listOf("serviceAuth.start", "services.invoke", "workflow.step")) {
                val session = session(server)
                val url = server.url("/api/me")
                session.cookies.saveFromResponse(url, listOf(Cookie.Builder().name("SYNTHETIC_SESSION")
                    .value(sessionToken).hostOnlyDomain(url.host).path("/api").build()))
                val host = host(operation(server, method, session = session))
                val request = JSONObject().put("url", url.toString()).put("method", "GET").put("purpose", "query")
                server.enqueue(MockResponse().setBody("ordinary private read"))
                host.call("http", request)
                assertEquals("SYNTHETIC_SESSION=$sessionToken", server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Cookie"))
                server.enqueue(MockResponse().setBody("anonymous source")
                    .setHeader("Set-Cookie", "OMITTED_RESPONSE=synthetic-new-cookie; Path=/api"))
                val result = host.call("http", JSONObject(request.toString()).put("omitCookies", true))
                assertEquals("anonymous source", result.getJSONObject("data").getString("body"))
                assertNull("$method must omit the existing session cookie", server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Cookie"))
                assertEquals(listOf("SYNTHETIC_SESSION"), session.cookies.loadForRequest(url).map { it.name })
                assertEquals(sessionToken, session.cookies.loadForRequest(url).single().value)
                assertNoSecrets(result.toString())
                assertNoSecrets(host.report().toString())
            }
            assertEquals(6, server.requestCount)
        }
    }

    @Test fun omitCookiesRejectsUiAndCredentialBearingRequestsBeforePreflightOrVaultAccess() {
        server().use { server ->
            val base = JSONObject().put("url", server.url("/api/source").toString())
                .put("method", "GET").put("purpose", "query").put("omitCookies", true)
            val cases = listOf(
                "host.effect" to JSONObject(base.toString()),
                "ui.init" to JSONObject(base.toString()),
                "services.invoke" to JSONObject(base.toString()).put("credential", mobileHandle),
                "serviceAuth.start" to JSONObject(base.toString()).put("credentials", JSONObject().put("mobile", mobileHandle)),
                "workflow.step" to JSONObject(base.toString()).put("bindings", JSONObject()),
                "services.invoke" to JSONObject(base.toString()).put("headers", JSONObject().put("SessionToken", sessionToken))
            )
            repeat(cases.size) { server.enqueue(MockResponse().setBody("must not be requested")) }
            for ((method, request) in cases) {
                var preflights = 0
                var resolutions = 0
                val host = PluginHost(operation(server, method), RuntimeEnvironment.getApplication().cacheDir,
                    requestGuard = { _, _, _, _ -> preflights++ },
                    credentialResolver = { resolutions++; resolve(it) })
                denied(PluginErrorCode.VALIDATION_FAILED) { host.call("http", request) }
                assertEquals("$method must reject anonymous-read misuse before network policy", 0, preflights)
                assertEquals(0, resolutions)
                assertEquals(0, server.requestCount)
                assertNoSecrets(host.report().toString())
            }
        }
    }

    @Test fun sessionCookieWritesAreDeniedFromServicesWorkflowAndUiEffects() {
        server().use { server ->
            for (method in listOf("services.invoke", "workflow.prepare", "workflow.step", "ui.init", "host.effect", "auth.start")) {
                val host = host(operation(server, method))
                // Deliberately no grant: operation-group denial must precede grant or cookie parsing.
                val error = denied(PluginErrorCode.VALIDATION_FAILED) {
                    host.call("sessionCookies.set", JSONObject().put("url", server.url("/api/").toString())
                        .put("cookies", JSONArray().put(JSONObject().put("name", "SYNTHETIC_SESSION").put("value", sessionToken))))
                }
                assertNoSecrets(error.message.orEmpty())
                assertNoSecrets(host.report().toString())
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun credentialEchoInResponseNeverReachesHostEffectUi() {
        server().use { server ->
            for (secret in listOf(mobile, otp)) {
                for (echo in listOf(secret, secret.toByteArray().toByteString().base64(), URLEncoder.encode(secret, "UTF-8"))) {
                    for (responseType in listOf("text", "base64")) {
                        server.enqueue(MockResponse().setBody("{\"echo\":\"$echo\"}"))
                        val resolved = mutableListOf<String>()
                        val host = host(operation(server, "host.effect")) { handle -> resolved += handle; resolve(handle) }
                        val error = denied(PluginErrorCode.PERMISSION_DENIED) {
                            host.call("http", login(server).put("responseType", responseType))
                        }
                        assertEquals(2, resolved.size)
                        assertEquals("/api/login", server.takeRequest(1, TimeUnit.SECONDS)!!.path)
                        assertNoSecrets(error.message.orEmpty())
                        assertNoSecrets(host.report().toString())
                    }
                }
            }
        }
    }

    @Test fun safeCredentialBoundResponseCanReachHostEffectUi() {
        server().use { server ->
            server.enqueue(MockResponse().setBody("{\"authenticated\":true}"))
            val host = host(operation(server, "host.effect"), ::resolve)
            val response = host.call("http", login(server))
            assertEquals("{\"authenticated\":true}", response.getJSONObject("data").getString("body"))
            assertNoSecrets(response.toString())
            assertNoSecrets(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8())
        }
    }

    @Test fun protectedProviderUiCannotReceiveRawAuthenticationResponsesButLegacyUiStillCan() {
        server().use { server ->
            server.enqueue(MockResponse().setBody("{\"issuedToken\":\"new-secret\"}"))
            val protected = PluginManifest(JSONObject(manifest(server).json.toString()).put("dynamicNetworkScopes", true))
            var resolved = 0
            denied(PluginErrorCode.VALIDATION_FAILED) {
                host(operation(server, "host.effect", protected)) { resolved++; resolve(it) }.call("http", login(server))
            }
            assertEquals(0, resolved); assertEquals(0, server.requestCount)
            assertTrue(host(operation(server, "host.effect"), ::resolve).call("http", login(server)).getJSONObject("data").getString("body").contains("new-secret"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun invalidRequestMetadataAndLateInvalidAliasFailBeforeAnyOtpConsumption() {
        server().use { server ->
            val requests = listOf(
                login(server).put("headers", JSONObject().put("Cookie", "forbidden")),
                login(server).put("headers", JSONObject().put("Referer", "https://undeclared.example/page")),
                login(server).put("responseType", "unsupported"),
                login(server).put("sameOriginReferer", "true"),
                login(server).put("headers", JSONObject().put("Content-Type", "invalid media type")),
                login(server).apply { getJSONObject("credentials").put("z-invalid", 12) },
                login(server).put("purpose", "mutation")
            )
            for (request in requests) {
                var resolutions = 0
                try { host(operation(server)) { resolutions++; resolve(it) }.call("http", request); fail("invalid request accepted") }
                catch (_: Exception) { /* Rejection must precede credential resolution and HTTP. */ }
                assertEquals(request.toString(), 0, resolutions)
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun rejectedPreflightPreservesRealOtpUntilOneSuccessfulSend() {
        server().use { server ->
            val vault = NativePluginVault(RuntimeEnvironment.getApplication(), "real-otp-preflight", sharedEphemeral = true)
            try {
                val mobileId = vault.saveCredential("mobile", JSONObject().put("mobile", mobile), false)
                val otpId = vault.saveCredential("otp", JSONObject().put("otp", otp), false)
                val request = login(server).put("credentials", JSONObject().put("mobile", mobileId).put("otp", otpId))
                val host = host(operation(server), vault::consumeCredential)
                denied(PluginErrorCode.VALIDATION_FAILED) { host.call("http", JSONObject(request.toString()).put("headers", JSONObject().put("Cookie", "forbidden"))) }
                assertNotNull(vault.get("credential:$otpId"))
                assertEquals(0, server.requestCount)
                server.enqueue(MockResponse().setBody("safe-result"))
                host.call("http", request)
                assertNull(vault.get("credential:$otpId"))
                denied(PluginErrorCode.SESSION_EXPIRED) { host.call("http", request) }
                assertEquals(1, server.requestCount)
            } finally { vault.clear() }
        }
    }

    @Test fun protectedV5ProtocolHeadersPreserveSpacesAndEnforcePrintableAsciiAndSize() {
        server().use { server ->
            val headers = listOf("SessionToken", "SessionTokenKey", "CpdailyClientType", "TenantId", "AmpCookies", "CasPCookies", "TGC")
            for (header in headers) {
                for (value in listOf("", "a\tb", "a\rb", "a\nb", "a\u007fb", "a\u0000b", "a\u0080b", "x".repeat(8193))) {
                    var resolutions = 0
                    denied(PluginErrorCode.VALIDATION_FAILED) {
                        host(operation(server)) { resolutions++; resolve(it) }.call("http", login(server).put("headers", JSONObject().put(header, value)))
                    }
                    assertEquals(0, resolutions)
                }
                for (value in listOf(" padded tc2 value ", " ", "x".repeat(8192))) {
                    server.enqueue(MockResponse().setBody("ok"))
                    host(operation(server)).call("http", JSONObject().put("url", server.url("/api/me")).put("purpose", "query").put("headers", JSONObject().put(header, value)))
                    // MockWebServer trims parsed values; the raw socket regression below checks wire bytes.
                    assertEquals(value.trim(), server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader(header))
                }
            }
        }
    }

    @Test fun tc2TrailingSpaceReachesTheActualHttpWireUnchanged() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { socket ->
            socket.soTimeout = 10_000
            val executor = Executors.newSingleThreadExecutor()
            try {
                val received = executor.submit(Callable {
                    socket.accept().use { connection ->
                        connection.soTimeout = 10_000
                        val reader = connection.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        val lines = mutableListOf<String>()
                        while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; lines += line }
                        connection.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                        lines
                    }
                })
                val origin = "http://127.0.0.1:${socket.localPort}"
                val manifest = PluginManifest(JSONObject(manifest().json.toString()).put("network", JSONArray().put(JSONObject()
                    .put("origin", origin).put("pathPrefix", "/api").put("methods", JSONArray(listOf("GET"))).put("purposes", JSONArray(listOf("query"))))))
                val session = AcademicSessionStore().session("synthetic-school", "wire-account", origin).also(sessions::add)
                val op = PluginOperation(session, manifest, "services.invoke").also(operations::add)
                val value = " " + "x".repeat(60) + " "
                host(op).call("http", JSONObject().put("url", "$origin/api/me").put("purpose", "query").put("headers", JSONObject().put("AmpCookies", value)))
                assertEquals("AmpCookies: $value", received.get(10, TimeUnit.SECONDS).single { it.startsWith("AmpCookies:") })
            } finally { executor.shutdownNow() }
        }
    }
}
