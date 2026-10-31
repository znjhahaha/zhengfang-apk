package com.tyust.course.study

import android.content.Context
import android.content.SharedPreferences
import com.tyust.course.manager.SessionStateStore
import com.tyust.course.manager.SessionToken
import com.tyust.course.model.SchoolConfig
import org.json.JSONObject
import java.security.MessageDigest
import java.util.WeakHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AcademicDataScope(val account: String, val school: String, val endpoint: String,
    val provider: String = "", val termFormat: String = "", val configuration: String = "") {
    internal val prefix get() = accountPrefix(account) + digest(listOf(school, endpoint, provider, termFormat, configuration)) + ":"
    fun manualScope() = copy(provider = "", termFormat = "", configuration = "")
    internal fun pageCacheKey(packageIdentity: String) = "academic.grades:$prefix" + digest(listOf(packageIdentity))
    companion object {
        internal fun digest(parts: List<String>): String = MessageDigest.getInstance("SHA-256")
            .digest(parts.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        internal fun accountPrefix(account: String) = digest(listOf(account)) + ":"
        fun forSchool(account: String, school: SchoolConfig, provider: String = "", format: String = "") =
            AcademicDataScope(account, school.id, school.fullBasePath, provider, format,
                digest(listOf(com.tyust.course.academic.plugin.PluginJson.canonical(school.toJson()))))
    }
}

data class AcademicSnapshot(val data: JSONObject, val syncedAt: Long)

/** Latest successful results, separate from browsing preferences and login sessions. */
class AcademicSnapshotStore(private val preferences: SharedPreferences, private val sessions: SessionStateStore,
    private val now: () -> Long = System::currentTimeMillis) {
    private fun key(scope: AcademicDataScope, kind: String, term: String) =
        scope.prefix + AcademicDataScope.digest(listOf(kind, term))

    fun read(scope: AcademicDataScope, kind: String, term: String = ""): AcademicSnapshot? = runCatching {
        val text = preferences.getString(key(scope, kind, term), null) ?: return null
        val json = JSONObject(text)
        if (json.getInt("version") != 1) return null
        AcademicSnapshot(json.getJSONObject("data"), json.getLong("syncedAt"))
    }.getOrNull()

    val revision get() = changes.asStateFlow()
    fun save(scope: AcademicDataScope, kind: String, term: String, data: JSONObject, expected: SessionToken,
        allowed: () -> Boolean = { true }): Boolean =
        synchronized(sessions) {
            val removed = synchronized(deletions) { deletions[sessions]?.get(scope.account) }
            if (!allowed() || expected.accountStorageKey != scope.account || !sessions.isCurrent(expected) ||
                (removed != null && expected.generation <= removed)) return@synchronized false
            preferences.edit().putString(key(scope, kind, term), JSONObject().put("version", 1)
                .put("kind", kind).put("term", term).put("syncedAt", now()).put("data", data).toString()).commit()
        }

    fun removeAccount(account: String) = synchronized(sessions) {
        synchronized(deletions) { deletions.getOrPut(sessions) { mutableMapOf() }[account] = sessions.token.generation }
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith(AcademicDataScope.accountPrefix(account)) }.forEach(editor::remove)
        editor.commit()
        changes.value++
        Unit
    }

    fun manual(scope: AcademicDataScope, term: String): List<ExamRecord> = runCatching {
        read(scope.manualScope(), "manual", term)?.data?.let(ExamRecords::fromJson).orEmpty()
            .filter { it.manual && it.termId == term }
    }.getOrDefault(emptyList())

    fun manualTerms(scope: AcademicDataScope): List<String> = preferences.all
        .filterKeys { it.startsWith(scope.manualScope().prefix) }.values.flatMap { raw -> runCatching {
            val row = JSONObject(raw as String)
            if (row.optString("kind") != "manual") emptyList() else ExamRecords.fromJson(row.getJSONObject("data"))
                .filter { it.manual }.map { it.termId }
        }.getOrDefault(emptyList()) }.distinct().sortedDescending()

    fun saveManual(scope: AcademicDataScope, term: String, records: List<ExamRecord>, expected: SessionToken,
        allowed: () -> Boolean = { true }): Boolean {
        require(records.all { it.manual && it.termId == term })
        val saved = save(scope.manualScope(), "manual", term, ExamRecords.toJson(records), expected, allowed)
        if (saved) changes.value++
        return saved
    }

    companion object {
        private val deletions = WeakHashMap<SessionStateStore, MutableMap<String, Long>>()
        private val changes = MutableStateFlow(0L)
        @JvmStatic fun from(context: Context, sessions: SessionStateStore) = AcademicSnapshotStore(
            context.getSharedPreferences("academic_snapshots", Context.MODE_PRIVATE), sessions)
    }
}
