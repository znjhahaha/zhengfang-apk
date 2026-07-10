# TYUST SSO Protocol Login Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make TYUST password login authenticate through `sso1.tyust.edu.cn`, follow the CAS service chain into `newjwc.tyust.edu.cn`, and return a validated Zhengfang Cookie without opening a WebView.

**Architecture:** Add a small password-login gateway interface and a TYUST-specific implementation backed by an attempt-local, standards-compliant CookieJar and a normal-TLS OkHttpClient. Keep SSO HTML parsing and DES encryption in a pure Kotlin protocol helper, route only `school.id == "tyust"` through the new manager, and retain the existing direct Zhengfang manager for every other school.

**Tech Stack:** Kotlin, OkHttp 4.12, Jsoup, JCE DES, JUnit 4, MockWebServer, Android/Compose existing callback UI.

---

## File Structure

- Create `app/src/main/java/com/tyust/course/login/PasswordLoginGateway.kt`: common login/captcha interface and school-based factory.
- Create `app/src/main/java/com/tyust/course/login/TyustSsoProtocol.kt`: pure form parser, DES encryption, and response classification.
- Create `app/src/main/java/com/tyust/course/login/MatchingMemoryCookieJar.kt`: attempt-local RFC-style cookie matching and safe serialization.
- Create `app/src/main/java/com/tyust/course/login/TyustSsoLoginManager.kt`: asynchronous SSO/CAS state machine.
- Modify `app/src/main/java/com/tyust/course/login/PasswordLoginManager.kt`: implement the common gateway and clear sensitive state.
- Modify `app/src/main/java/com/tyust/course/LoginActivity.kt`: select the gateway and defer credential persistence until validation/binding succeeds.
- Modify `app/src/main/java/com/tyust/course/ui/route/GradesRoute.kt`: use the school-based gateway for automatic relogin.
- Modify `app/src/main/java/com/tyust/course/ui/route/SettingsRoute.kt`: use the school-based gateway for manual Cookie refresh without touching unrelated user changes.
- Modify `app/build.gradle`: add local JVM test dependencies.
- Create tests under `app/src/test/java/com/tyust/course/login/`.

### Task 1: Establish the JVM test harness

**Files:**
- Modify: `app/build.gradle:157-166`
- Create: `app/src/test/java/com/tyust/course/login/TyustSsoProtocolTest.kt`

- [ ] **Step 1: Add test dependencies**

```groovy
testImplementation 'junit:junit:4.13.2'
testImplementation 'com.squareup.okhttp3:mockwebserver:4.12.0'
```

- [ ] **Step 2: Write a failing parser smoke test**

```kotlin
class TyustSsoProtocolTest {
    @Test
    fun parsesCryptoKeyAndExecutionFromLoginPage() {
        val html = """
            <p id="login-croypto">MTIzNDU2Nzg=</p>
            <p id="login-page-flowkey">flow-key</p>
            <p id="recaptcha-invisible">false</p>
            <p id="captcha-url"></p>
        """.trimIndent()

        val form = TyustSsoProtocol.parseLoginPage(html)

        assertEquals("MTIzNDU2Nzg=", form.cryptoKeyBase64)
        assertEquals("flow-key", form.execution)
        assertFalse(form.captchaRequired)
    }
}
```

- [ ] **Step 3: Run the test and verify RED**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests com.tyust.course.login.TyustSsoProtocolTest
```

Expected: compilation fails because `TyustSsoProtocol` does not exist.

- [ ] **Step 4: Commit the harness**

```powershell
git add app/build.gradle app/src/test/java/com/tyust/course/login/TyustSsoProtocolTest.kt
git commit -m "test: add TYUST SSO protocol harness"
```

### Task 2: Implement SSO form parsing and DES encryption

**Files:**
- Create: `app/src/main/java/com/tyust/course/login/TyustSsoProtocol.kt`
- Modify: `app/src/test/java/com/tyust/course/login/TyustSsoProtocolTest.kt`

- [ ] **Step 1: Add failing tests for malformed forms and encryption**

```kotlin
@Test(expected = TyustSsoProtocolException::class)
fun rejectsLoginPageWithoutExecution() {
    TyustSsoProtocol.parseLoginPage("<p id='login-croypto'>MTIzNDU2Nzg=</p>")
}

@Test
fun encryptsPasswordWithDesEcbPkcs7() {
    assertEquals(
        "RpEpIH9dSgIJYLKpHvn7aQ==",
        TyustSsoProtocol.encryptPassword("MTIzNDU2Nzg=", "protocol-test")
    )
}

@Test
fun parsesCaptchaMetadata() {
    val form = TyustSsoProtocol.parseLoginPage(
        """
        <p id="login-croypto">MTIzNDU2Nzg=</p>
        <p id="login-page-flowkey">flow-key</p>
        <p id="recaptcha-invisible">true</p>
        <p id="captcha-url">/api/captcha/generate/DEFAULT</p>
        """.trimIndent()
    )
    assertTrue(form.captchaRequired)
    assertEquals("/api/captcha/generate/DEFAULT", form.captchaUrl)
}
```

- [ ] **Step 2: Run the tests and verify the expected failures**

Expected: parser test may pass after minimal creation, while malformed/encryption/CAPTCHA tests fail until implemented.

- [ ] **Step 3: Implement the pure protocol helper**

```kotlin
data class TyustSsoForm(
    val cryptoKeyBase64: String,
    val execution: String,
    val captchaRequired: Boolean,
    val captchaUrl: String?
)

class TyustSsoProtocolException(message: String) : Exception(message)

object TyustSsoProtocol {
    fun parseLoginPage(html: String): TyustSsoForm {
        val document = Jsoup.parse(html)
        val crypto = document.getElementById("login-croypto")?.text()?.trim().orEmpty()
        val execution = document.getElementById("login-page-flowkey")?.text()?.trim().orEmpty()
        if (crypto.isBlank() || execution.isBlank()) {
            throw TyustSsoProtocolException("统一认证登录参数缺失")
        }
        val captchaRequired = document.getElementById("recaptcha-invisible")
            ?.text()?.trim().equals("true", ignoreCase = true)
        val captchaUrl = document.getElementById("captcha-url")?.text()?.trim()?.ifBlank { null }
        return TyustSsoForm(crypto, execution, captchaRequired, captchaUrl)
    }

    fun encryptPassword(cryptoKeyBase64: String, password: String): String {
        val key = cryptoKeyBase64.decodeBase64()?.toByteArray()
            ?: throw TyustSsoProtocolException("统一认证加密参数无效")
        if (key.size != 8) throw TyustSsoProtocolException("统一认证 DES 密钥长度无效")
        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
        return cipher.doFinal(password.toByteArray(Charsets.UTF_8)).toByteString().base64()
    }
}
```

- [ ] **Step 4: Run tests and verify GREEN**

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/tyust/course/login/TyustSsoProtocol.kt app/src/test/java/com/tyust/course/login/TyustSsoProtocolTest.kt
git commit -m "feat: parse and encrypt TYUST SSO login form"
```

### Task 3: Add standards-compliant attempt-local cookie storage

**Files:**
- Create: `app/src/main/java/com/tyust/course/login/MatchingMemoryCookieJar.kt`
- Create: `app/src/test/java/com/tyust/course/login/MatchingMemoryCookieJarTest.kt`

- [ ] **Step 1: Write failing cookie tests**

```kotlin
@Test
fun doesNotSendSecureCookieOverHttp() {
    val jar = MatchingMemoryCookieJar()
    val https = "https://newjwc.tyust.edu.cn/jwglxt/".toHttpUrl()
    jar.saveFromResponse(https, listOf(Cookie.Builder()
        .name("JSESSIONID").value("test-session")
        .hostOnlyDomain("newjwc.tyust.edu.cn").path("/jwglxt").secure().build()))

    assertTrue(jar.loadForRequest("http://newjwc.tyust.edu.cn/jwglxt/".toHttpUrl()).isEmpty())
}

@Test
fun serializesOnlyCookiesMatchingTheTeachingHost() {
    val jar = MatchingMemoryCookieJar()
    jar.saveFromResponse("https://sso1.tyust.edu.cn/login".toHttpUrl(), listOf(
        Cookie.Builder().name("SESSION").value("sso")
            .hostOnlyDomain("sso1.tyust.edu.cn").path("/").secure().build()
    ))
    jar.saveFromResponse("https://newjwc.tyust.edu.cn/jwglxt/".toHttpUrl(), listOf(
        Cookie.Builder().name("JSESSIONID").value("jw")
            .hostOnlyDomain("newjwc.tyust.edu.cn").path("/jwglxt").secure().build()
    ))

    assertEquals("JSESSIONID=jw", jar.cookieHeaderFor("https://newjwc.tyust.edu.cn/jwglxt/".toHttpUrl()))
}
```

- [ ] **Step 2: Run tests and verify RED**

- [ ] **Step 3: Implement matching, expiry removal, and serialization**

```kotlin
internal class MatchingMemoryCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, incoming: List<Cookie>) {
        incoming.forEach { cookie ->
            cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            if (cookie.expiresAt > System.currentTimeMillis()) cookies += cookie
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
        return cookies.filter { it.matches(url) }
    }

    @Synchronized
    fun cookieHeaderFor(url: HttpUrl): String = loadForRequest(url)
        .joinToString("; ") { "${it.name}=${it.value}" }

    @Synchronized
    fun clear() = cookies.clear()
}
```

- [ ] **Step 4: Run tests and verify GREEN**

- [ ] **Step 5: Commit**

### Task 4: Define the common password-login gateway

**Files:**
- Create: `app/src/main/java/com/tyust/course/login/PasswordLoginGateway.kt`
- Modify: `app/src/main/java/com/tyust/course/login/PasswordLoginManager.kt:25`
- Create: `app/src/test/java/com/tyust/course/login/PasswordLoginGatewayFactoryTest.kt`

- [ ] **Step 1: Write failing factory tests**

```kotlin
@Test
fun createsTyustSsoGatewayOnlyForTyust() {
    assertTrue(PasswordLoginGatewayFactory.create(SchoolConfig("tyust", "TYUST", "newjwc.tyust.edu.cn", "https")) is TyustSsoLoginManager)
    assertTrue(PasswordLoginGatewayFactory.create(SchoolConfig("other", "Other", "jw.example.edu.cn", "https")) is PasswordLoginManager)
}
```

- [ ] **Step 2: Add the interface and factory**

```kotlin
interface PasswordLoginGateway {
    fun login(school: SchoolConfig, username: String, password: String, callback: PasswordLoginCallback)
    fun submitCaptcha(captchaCode: String, callback: PasswordLoginCallback)
    fun refreshCaptcha(callback: (ByteArray?) -> Unit)
    fun clearSensitiveState()
}

object PasswordLoginGatewayFactory {
    fun create(school: SchoolConfig): PasswordLoginGateway =
        if (school.id == "tyust") TyustSsoLoginManager() else PasswordLoginManager()
}
```

- [ ] **Step 3: Make `PasswordLoginManager` implement the interface**

Add `override` to the three existing methods and implement `clearSensitiveState()` by clearing username, raw password, encrypted password, CSRF, hidden fields, modulus, exponent, and school references.

- [ ] **Step 4: Run tests and verify GREEN after the TYUST manager stub exists**

- [ ] **Step 5: Commit**

### Task 5: Implement the TYUST SSO/CAS state machine

**Files:**
- Create: `app/src/main/java/com/tyust/course/login/TyustSsoLoginManager.kt`
- Create: `app/src/test/java/com/tyust/course/login/TyustSsoLoginManagerTest.kt`

- [ ] **Step 1: Write a failing MockWebServer test for the request body**

Inject test endpoints into the manager and enqueue a sanitized login form followed by a successful redirect chain. Assert that the POST body contains exactly:

```text
username
password
croypto
type=UsernamePassword
_eventId=submit
geolocation
execution
captcha_code
```

Assert that the plaintext password is absent.

- [ ] **Step 2: Write a failing redirect-chain test**

Use two MockWebServer instances to represent SSO and Zhengfang. Enqueue:

```text
GET login form -> 200 + SESSION
POST login -> 302 to teaching service with ticket
GET service?ticket -> 302 to service
GET service -> 302 to ticketlogin
GET ticketlogin -> 302 to login_slogin
GET login_slogin -> 302 to index_initMenu
GET index_initMenu -> 200 + authenticated HTML + JSESSIONID/route
```

Assert callback success returns only `JSESSIONID` and `route`, never `SESSION`.

- [ ] **Step 3: Run both tests and verify RED**

- [ ] **Step 4: Implement configurable endpoints and a safe client**

```kotlin
internal data class TyustSsoEndpoints(
    val ssoBase: HttpUrl,
    val teachingService: HttpUrl,
    val teachingBase: HttpUrl
) {
    companion object {
        fun production() = TyustSsoEndpoints(
            "https://sso1.tyust.edu.cn/".toHttpUrl(),
            "https://newjwc.tyust.edu.cn/sso/jasiglogin/jwglxt".toHttpUrl(),
            "https://newjwc.tyust.edu.cn/".toHttpUrl()
        )
    }
}
```

Construct `OkHttpClient` with the attempt-local CookieJar, `followRedirects(false)`, normal TLS defaults, and 30-second timeouts.

- [ ] **Step 5: Implement bounded manual redirect handling**

For 301/302/303 responses, issue GET to the resolved `Location`. For 307/308, preserve method and body. Reject non-HTTPS production redirects, hosts outside `sso1.tyust.edu.cn` and `newjwc.tyust.edu.cn`, missing locations, and chains longer than 12 hops.

- [ ] **Step 6: Implement final success detection**

Success requires:

```kotlin
response.request.url.host == endpoints.teachingBase.host &&
response.request.url.encodedPath.contains("/xtgl/index_initMenu.html") &&
!body.contains("用户登录") &&
cookieJar.cookieHeaderFor(endpoints.teachingBase.newBuilder().addPathSegment("jwglxt").build()).isNotBlank()
```

Call `onSuccess(cookieHeader)` once, close responses, and clear SSO form/password state.

- [ ] **Step 7: Run tests and verify GREEN**

- [ ] **Step 8: Commit**

### Task 6: Add invalid-credential and CAPTCHA behavior

**Files:**
- Modify: `app/src/main/java/com/tyust/course/login/TyustSsoProtocol.kt`
- Modify: `app/src/main/java/com/tyust/course/login/TyustSsoLoginManager.kt`
- Modify: `app/src/test/java/com/tyust/course/login/TyustSsoLoginManagerTest.kt`

- [ ] **Step 1: Write failing invalid-credential tests**

Return a sanitized SSO login page containing error code `1030023` and assert `onInvalidCredentials()` is called exactly once with no Cookie result.

- [ ] **Step 2: Write failing CAPTCHA tests**

Return CAPTCHA metadata from the SSO response, enqueue image bytes, and assert `onCaptchaRequired(bytes)`. Then submit a code, assert `captcha_code` is present, and map a CAPTCHA error page to `onCaptchaInvalid()`.

- [ ] **Step 3: Implement response classification**

```kotlin
sealed interface TyustSsoLoginPageResult {
    data object InvalidCredentials : TyustSsoLoginPageResult
    data class CaptchaRequired(val url: String) : TyustSsoLoginPageResult
    data object CaptchaInvalid : TyustSsoLoginPageResult
    data class OtherError(val message: String) : TyustSsoLoginPageResult
}
```

Classify known code `1030023` and Chinese username/password messages as invalid credentials. Classify CAPTCHA messages based on whether a code was submitted. Keep the latest execution/crypto values for a bounded CAPTCHA retry.

- [ ] **Step 4: Ensure refresh failures return `null` without retry loops**

Do not recursively restart password login when the CAPTCHA endpoint fails.

- [ ] **Step 5: Run tests and verify GREEN**

- [ ] **Step 6: Commit**

### Task 7: Integrate with login, auto-relogin, and persistence

**Files:**
- Modify: `app/src/main/java/com/tyust/course/LoginActivity.kt:38-54,388-529`
- Modify: `app/src/main/java/com/tyust/course/ui/route/GradesRoute.kt:79-130`
- Modify: `app/src/main/java/com/tyust/course/ui/route/SettingsRoute.kt:264-340`
- Create: `app/src/test/java/com/tyust/course/login/PasswordLoginRoutingTest.kt`

- [ ] **Step 1: Add a failing routing test**

Verify TYUST selects `TyustSsoLoginManager`, while a non-TYUST school selects `PasswordLoginManager`.

- [ ] **Step 2: Replace the fixed manager in `LoginActivity`**

Use:

```kotlin
private var activePasswordLoginGateway: PasswordLoginGateway? = null
private var pendingPasswordUsername = ""
private var pendingPasswordValue = ""
```

At password login start:

```kotlin
pendingPasswordUsername = username
pendingPasswordValue = password
activePasswordLoginGateway?.clearSensitiveState()
activePasswordLoginGateway = PasswordLoginGatewayFactory.create(school)
activePasswordLoginGateway!!.login(school, username, password, callback)
```

Use the active gateway for CAPTCHA submit/refresh.

- [ ] **Step 3: Defer persistence until all validation and binding checks pass**

Remove both early calls to `savePasswordLogin`. In `proceedToMain`, replace the password branch with:

```kotlin
userManager.savePasswordLogin(
    pendingPasswordUsername,
    cookieStr.trim(),
    pendingPasswordValue
)
```

Then clear pending Activity fields and gateway sensitive state.

- [ ] **Step 4: Route automatic and manual relogin through the factory**

Replace `PasswordLoginManager().login(...)` in `GradesRoute` and `SettingsRoute` with:

```kotlin
PasswordLoginGatewayFactory.create(school).login(school, username, password, callback)
```

Do not modify unrelated feedback/community work already present in `SettingsRoute`.

- [ ] **Step 5: Run all unit tests and verify GREEN**

```powershell
.\gradlew.bat testDebugUnitTest
```

- [ ] **Step 6: Commit**

### Task 8: Verify build, live protocol login, and secret hygiene

**Files:**
- Create: `app/src/test/java/com/tyust/course/login/TyustSsoLiveTest.kt`
- Modify only if verification exposes a defect in files from Tasks 2-7.

- [ ] **Step 1: Add an opt-in live test**

The test reads `TYUST_TEST_USERNAME` and `TYUST_TEST_PASSWORD` from environment variables and skips when either is absent. It must not print inputs, Cookie values, tickets, or encrypted fields.

- [ ] **Step 2: Run unit tests**

```powershell
.\gradlew.bat testDebugUnitTest
```

Expected: all deterministic tests pass; live test is skipped without environment variables.

- [ ] **Step 3: Run the authorized live test with process-only credentials**

Run the single live test with environment variables set only for that process. Expected: callback success, final host `newjwc.tyust.edu.cn`, authenticated student-information response, and a non-empty Cookie header. Never write the values to a file.

- [ ] **Step 4: Build the debug APK**

```powershell
.\gradlew.bat assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Scan source and test output for secrets**

```powershell
Select-String -Path app/src/main/java/**/*.kt,app/src/test/java/**/*.kt -Pattern 'password=.*|cookie=.*|ticket=.*|Set-Cookie|captchaWas|submitted=' -CaseSensitive:$false
```

Expected: no logging of credential or session values in the new TYUST implementation. Existing unrelated findings must not be copied into the new code.

- [ ] **Step 6: Confirm user changes remain isolated**

```powershell
git diff -- app/src/main/java/com/tyust/course/ui/route/SettingsRoute.kt app/src/main/java/com/tyust/course/ui/screen/SettingsScreen.kt
git status --short
```

Expected: pre-existing feedback/community modifications remain intact; only the targeted factory call is added to `SettingsRoute`.

- [ ] **Step 7: Commit verification fixes and report results**

```powershell
git add app/src/main app/src/test app/build.gradle
git commit -m "feat: support TYUST SSO password login"
```
