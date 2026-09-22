package com.tyust.course.academic

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class AcademicCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()
    private val lock = Any()
    private var retired = false

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = synchronized(lock) {
        if (retired) return@synchronized
        cookies.forEach { incoming ->
            this.cookies.removeAll { it.name == incoming.name && it.domain == incoming.domain && it.path == incoming.path }
            if (!incoming.expiresAt.let { it <= System.currentTimeMillis() }) this.cookies += incoming
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(lock) {
        this.cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
        this.cookies.filter { it.matches(url) }.toList()
    }

    fun clear() = synchronized(lock) { cookies.clear() }
    internal fun snapshot(): List<Cookie> = synchronized(lock) { cookies.filter { it.expiresAt > System.currentTimeMillis() }.toList() }
    fun retire() = synchronized(lock) { retired = true; cookies.clear() }
}

class AcademicSession internal constructor(
    val key: AcademicSessionKey,
    val baseUrl: String,
    val cookies: AcademicCookieJar = AcademicCookieJar()
) {
    internal var username: String = ""
    internal var pageCharset: java.nio.charset.Charset? = null
    private val epochCounter = AtomicLong(1L)
    private val operationMutex = Mutex()
    @Volatile var retired: Boolean = false
        private set
    var epoch: Long = epochCounter.get()
        private set

    fun invalidate() {
        synchronized(this) {
            epoch = epochCounter.incrementAndGet()
            cookies.clear()
        }
    }

    fun retire() = synchronized(this) {
        retired = true
        invalidate()
        cookies.retire()
    }

    fun requireActive() {
        if (retired) throw kotlinx.coroutines.CancellationException("Session replaced")
    }

    fun cookieHeader(): String = (baseUrl.trimEnd('/') + "/").toHttpUrlOrNull()?.let { cookies.loadForRequest(it) }
        ?.joinToString("; ") { "${it.name}=${it.value}" }.orEmpty()

    suspend fun <T> withProtocolLock(block: suspend () -> T): T {
        requireActive()
        if (coroutineContext[SessionLock]?.session === this) return block()
        return operationMutex.withLock { requireActive(); withContext(SessionLock(this)) { block() } }
    }

    private class SessionLock(val session: AcademicSession) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<SessionLock>
    }
}

class AcademicSessionStore {
    private val sessions = ConcurrentHashMap<AcademicSessionKey, AcademicSession>()

    fun session(schoolId: String, accountKey: String, baseUrl: String): AcademicSession =
        sessions.compute(AcademicSessionKey(schoolId, accountKey)) { key, previous ->
            if (previous?.baseUrl == baseUrl) previous else {
                previous?.retire()
                AcademicSession(key, baseUrl)
            }
        }!!

    fun invalidate(schoolId: String, accountKey: String) {
        sessions.remove(AcademicSessionKey(schoolId, accountKey))?.retire()
    }

    fun replace(schoolId: String, accountKey: String, baseUrl: String): AcademicSession =
        sessions.compute(AcademicSessionKey(schoolId, accountKey)) { key, previous ->
            previous?.retire()
            AcademicSession(key, baseUrl)
        }!!

    fun clear() { sessions.values.forEach { it.retire() }; sessions.clear() }
}
