package com.tyust.course.network

import android.content.Context
import android.util.Base64
import com.tyust.course.manager.UserManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID

data class SchoolAdaptationRequest(
    val schoolName: String,
    val academicSystemUrl: String,
    val ssoUrl: String,
    val testUsername: String,
    val temporaryPassword: String,
    val contact: String,
    val notes: String,
    val requestId: String = UUID.randomUUID().toString()
)

enum class AdaptationStatus(val label: String) {
    SUBMITTED("已提交"),
    PENDING_VERIFICATION("待验证"),
    ANALYZING_LOGIN("分析登录流程"),
    ADAPTING("适配中"),
    WAITING_FOR_USER("等待补充"),
    TESTING("测试中"),
    COMPLETED("已完成"),
    UNSUPPORTED("无法适配"),
    AUTHOR_REPLIED("作者已回复")
}

data class AdaptedSchoolItem(
    val id: String,
    val schoolName: String,
    val academicSystemUrl: String,
    val ssoUrl: String,
    val description: String,
    val publishedAt: String
)

data class SchoolAdaptationItem(
    val id: String,
    val requestId: String,
    val schoolName: String,
    val academicSystemUrl: String,
    val ssoUrl: String,
    val createdAt: String,
    val status: AdaptationStatus,
    val replyMessage: String?,
    val repliedAt: String?
)

object SchoolAdaptationManager {
    const val CONTENT_MARKER = "[UNIFIED_LOGIN_ADAPTATION_V1]"

    private const val PREFS_NAME = "school_adaptation_prefs"
    private const val KEY_REQUESTS = "suggestion_requests_v2"
    private const val KEY_PENDING_TOKEN_PREFIX = "pending_query_token_"
    private const val KEY_CONFIRMED_PASSWORD_CHANGES = "confirmed_password_changes"

    private data class StoredRequest(
        val suggestionId: String,
        val clientRequestId: String,
        val queryToken: String,
        val accountScope: String,
        val usernameHash: String,
        val schoolHost: String,
        val schoolNameHash: String
    )

    suspend fun submit(
        context: Context,
        request: SchoolAdaptationRequest
    ): Result<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pendingTokenKey = KEY_PENDING_TOKEN_PREFIX + request.requestId
        val queryToken = prefs.getString(pendingTokenKey, null)
            ?: generateQueryToken().also { prefs.edit().putString(pendingTokenKey, it).apply() }

        return SchoolSuggestionApi.create(request, queryToken).map { suggestion ->
            val record = StoredRequest(
                suggestionId = suggestion.id,
                clientRequestId = request.requestId,
                queryToken = queryToken,
                accountScope = currentAccountScope(),
                usernameHash = identityHash(request.testUsername),
                schoolHost = hostOf(request.academicSystemUrl),
                schoolNameHash = identityHash(request.schoolName)
            )
            saveStoredRequests(context, loadStoredRequests(context).replaceByClientRequest(record))
            prefs.edit().remove(pendingTokenKey).apply()
            "发送成功"
        }
    }

    suspend fun getAdaptedSchools(): Result<List<AdaptedSchoolItem>> =
        SchoolSuggestionApi.getAdaptedSchools().map(::mapAdaptedSchools)

    fun mapAdaptedSchools(schools: List<SchoolSuggestionApi.AdaptedSchool>): List<AdaptedSchoolItem> =
        schools
            .map { school ->
                AdaptedSchoolItem(
                    id = school.id,
                    schoolName = school.schoolName.ifBlank { "未命名学校" },
                    academicSystemUrl = school.academicSystemUrl,
                    ssoUrl = school.ssoUrl,
                    description = school.description,
                    publishedAt = school.publishedAt
                )
            }
            .filter { hostOf(it.academicSystemUrl).isNotBlank() }
            .distinctBy { hostOf(it.academicSystemUrl) }

    suspend fun getMyRequests(context: Context): Result<List<SchoolAdaptationItem>> = runCatching {
        val allRecords = loadStoredRequests(context)
        val currentScope = currentAccountScope()
        val visibleRecords = allRecords.filter { record ->
            record.accountScope == currentScope || matchesCurrentLoggedInAccount(record)
        }
        if (visibleRecords.isEmpty()) return@runCatching emptyList()

        val migratedIds = visibleRecords
            .filter { it.accountScope != currentScope }
            .mapTo(mutableSetOf()) { it.clientRequestId }
        if (migratedIds.isNotEmpty()) {
            saveStoredRequests(
                context,
                allRecords.map { record ->
                    if (record.clientRequestId in migratedIds) record.copy(accountScope = currentScope) else record
                }
            )
        }

        visibleRecords.map { record ->
            SchoolSuggestionApi.get(record.suggestionId, record.queryToken)
                .getOrThrow()
                .toAdaptationItem()
        }.sortedByDescending { it.createdAt }
    }

    fun isAdaptationFeedback(content: String): Boolean =
        content.lineSequence().firstOrNull()?.trim() == CONTENT_MARKER

    fun statusFromApi(value: String): AdaptationStatus = when (value) {
        "submitted" -> AdaptationStatus.SUBMITTED
        "pending_verification" -> AdaptationStatus.PENDING_VERIFICATION
        "analyzing_login" -> AdaptationStatus.ANALYZING_LOGIN
        "adapting" -> AdaptationStatus.ADAPTING
        "waiting_for_user" -> AdaptationStatus.WAITING_FOR_USER
        "testing" -> AdaptationStatus.TESTING
        "completed" -> AdaptationStatus.COMPLETED
        "unsupported" -> AdaptationStatus.UNSUPPORTED
        else -> AdaptationStatus.AUTHOR_REPLIED
    }

    fun SchoolSuggestionApi.Suggestion.toAdaptationItem(): SchoolAdaptationItem =
        SchoolAdaptationItem(
            id = id,
            requestId = clientRequestId,
            schoolName = schoolName.ifBlank { "未命名学校" },
            academicSystemUrl = academicSystemUrl,
            ssoUrl = ssoUrl,
            createdAt = createdAt,
            status = statusFromApi(status),
            replyMessage = reply,
            repliedAt = repliedAt
        )

    fun pendingPasswordChangeItems(
        context: Context,
        items: List<SchoolAdaptationItem>
    ): List<SchoolAdaptationItem> {
        val confirmedIds = confirmedPasswordChangeIds(context)
        return items.filter { it.status == AdaptationStatus.COMPLETED && it.id !in confirmedIds }
    }

    fun markPasswordChanged(context: Context, suggestionIds: Collection<String>) {
        if (suggestionIds.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val updated = confirmedPasswordChangeIds(context).toMutableSet().apply {
            addAll(suggestionIds.filter(String::isNotBlank))
        }
        prefs.edit().putStringSet(KEY_CONFIRMED_PASSWORD_CHANGES, updated).apply()
    }

    private fun List<StoredRequest>.replaceByClientRequest(record: StoredRequest): List<StoredRequest> =
        filterNot { it.clientRequestId == record.clientRequestId } + record

    private fun loadStoredRequests(context: Context): List<StoredRequest> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_REQUESTS, null)
            ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val record = StoredRequest(
                        suggestionId = item.getString("suggestionId"),
                        clientRequestId = item.getString("clientRequestId"),
                        queryToken = item.getString("queryToken"),
                        accountScope = item.optString("accountScope", "default"),
                        usernameHash = item.optString("usernameHash", ""),
                        schoolHost = item.optString("schoolHost", ""),
                        schoolNameHash = item.optString("schoolNameHash", "")
                    )
                    if (record.suggestionId.isNotBlank() && record.queryToken.length >= 32) add(record)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveStoredRequests(context: Context, records: List<StoredRequest>) {
        val array = JSONArray()
        records.forEach { record ->
            array.put(JSONObject().apply {
                put("suggestionId", record.suggestionId)
                put("clientRequestId", record.clientRequestId)
                put("queryToken", record.queryToken)
                put("accountScope", record.accountScope)
                put("usernameHash", record.usernameHash)
                put("schoolHost", record.schoolHost)
                put("schoolNameHash", record.schoolNameHash)
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_REQUESTS, array.toString())
            .apply()
    }

    private fun matchesCurrentLoggedInAccount(record: StoredRequest): Boolean {
        val userManager = UserManager.getInstance()
        if (!userManager.isLoggedIn) return false
        val currentIdentityHashes = listOf(
            userManager.username,
            userManager.studentId,
            userManager.studentName
        ).map(::identityHash).filter(String::isNotBlank)
        if (record.usernameHash.isBlank() || record.usernameHash !in currentIdentityHashes) return false

        val school = userManager.currentSchool ?: return false
        val hostMatches = record.schoolHost.isNotBlank() && record.schoolHost == hostOf(school.baseUrl)
        val nameMatches = record.schoolNameHash.isNotBlank() && record.schoolNameHash == identityHash(school.name)
        return hostMatches || nameMatches
    }

    private fun currentAccountScope(): String =
        UserManager.getInstance().currentAccountStorageKey

    private fun generateQueryToken(): String {
        val bytes = ByteArray(48).also(SecureRandom()::nextBytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun hostOf(value: String): String = runCatching {
        URI(value.trim()).host?.lowercase(Locale.ROOT).orEmpty()
    }.getOrDefault("")

    private fun identityHash(value: String): String {
        val normalized = value.trim().lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
        if (normalized.isBlank()) return ""
        return MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun confirmedPasswordChangeIds(context: Context): Set<String> =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_CONFIRMED_PASSWORD_CHANGES, emptySet())
            ?.toSet()
            .orEmpty()
}