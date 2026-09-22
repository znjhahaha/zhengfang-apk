package com.tyust.course.survey

import java.net.URI
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class Survey(
    val id: String,
    val title: String,
    val description: String = "",
    val url: String,
    val category: String = "其他",
    val tags: List<String> = emptyList(),
    val publisher: String = "",
    val estimatedMinutes: Int = 3,
    val coverUrl: String = "",
    val important: Boolean = false,
    val pinned: Boolean = false,
    val sortOrder: Int = 0,
    val startsAt: Long? = null,
    val endsAt: Long? = null,
    val publishedAt: Long = 0,
    val updatedAt: Long = 0,
    val availability: String = "active"
) {
    fun isEnded(now: Long): Boolean = availability == "ended" || (endsAt != null && endsAt <= now)
    fun isActive(now: Long): Boolean = availability == "active" && !isEnded(now) && (startsAt == null || startsAt <= now)
}

/** All flags are local to the current account. Completion is always manual. */
data class SurveyLocalState(
    val favorite: Boolean = false,
    val lastViewedAt: Long? = null,
    val completedAt: Long? = null,
    val seen: Boolean = false,
    val reminded: Boolean = false,
    val remindedOn: String? = null
)

internal fun surveyReminderDay(now: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(java.util.Date(now))

data class SurveySavedData(
    val schoolHost: String = "",
    val surveys: List<Survey> = emptyList(),
    val records: Map<String, Survey> = emptyMap(),
    val local: Map<String, SurveyLocalState> = emptyMap(),
    val fetchedAt: Long = 0,
    val serverTime: Long = 0,
    val remindersEnabled: Boolean = true
)

data class SurveyFeedState(
    val saved: SurveySavedData = SurveySavedData(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null
) {
    fun serverNow(deviceNow: Long): Long = if (saved.serverTime > 0) saved.serverTime + (deviceNow - saved.fetchedAt).coerceAtLeast(0) else deviceNow
    fun unreadCount(deviceNow: Long): Int = saved.surveys.count { it.isActive(serverNow(deviceNow)) && saved.local[it.id]?.seen != true }
    fun local(id: String) = saved.local[id] ?: SurveyLocalState()
    fun survey(id: String): Survey? = saved.surveys.firstOrNull { it.id == id } ?: saved.records[id]
}

enum class SurveyCollection(val label: String) { All("全部问卷"), Favorites("我的收藏"), History("浏览历史"), Completed("已填写") }
enum class SurveyStatusFilter(val label: String) { Active("进行中"), Ended("已结束"), All("全部状态") }

fun filterSurveys(state: SurveyFeedState, query: String, category: String, status: SurveyStatusFilter, collection: SurveyCollection, now: Long): List<Survey> {
    val source = if (collection == SurveyCollection.All) state.saved.surveys
        else (state.saved.records.values + state.saved.surveys).associateBy { it.id }.values.toList()
    val remoteNow = state.serverNow(now)
    val filtered = source.filter { survey ->
        val local = state.local(survey.id)
        (when (collection) {
            SurveyCollection.All -> true
            SurveyCollection.Favorites -> local.favorite
            SurveyCollection.History -> local.lastViewedAt != null
            SurveyCollection.Completed -> local.completedAt != null
        }) && (category.isEmpty() || survey.category == category) &&
            (status == SurveyStatusFilter.All || (status == SurveyStatusFilter.Ended) == survey.isEnded(remoteNow)) &&
            (query.isBlank() || (listOf(survey.title, survey.description, survey.publisher) + survey.tags).any { it.contains(query.trim(), ignoreCase = true) })
    }
    return when (collection) {
        SurveyCollection.History -> filtered.sortedByDescending { state.local(it.id).lastViewedAt }
        SurveyCollection.Completed -> filtered.sortedByDescending { state.local(it.id).completedAt }
        else -> filtered.sortedWith(compareByDescending<Survey> { it.pinned }.thenByDescending { it.sortOrder }.thenByDescending { it.publishedAt }.thenBy { it.id })
    }
}

object SurveyLinks {
    fun isAllowedInitialUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) && uri.userInfo == null &&
            (uri.port == -1 || uri.port == if (uri.scheme.equals("https", true)) 443 else 80) &&
            listOf("wjx.cn", "wjx.top", "wjx.com").any { host == it || host.endsWith(".$it") }
    }.getOrDefault(false)

    fun schoolHost(value: String?): String = runCatching {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank()) return ""
        val uri = URI(if (raw.contains("://")) raw else "https://$raw")
        if (uri.userInfo != null || !(uri.scheme.equals("https", true) || uri.scheme.equals("http", true))) return ""
        uri.host?.lowercase(Locale.ROOT)?.trimEnd('.').orEmpty()
    }.getOrDefault("")

    fun accountFileKey(accountKey: String): String = MessageDigest.getInstance("SHA-256")
        .digest(accountKey.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

internal fun parseSurveyTime(value: String): Long? {
    if (value.isBlank() || value == "null") return null
    for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")) {
        val time = runCatching { SimpleDateFormat(pattern, Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC"); isLenient = false
        }.parse(value)?.time }.getOrNull()
        if (time != null) return time
    }
    return null
}

/** One reminder budget per foreground visit, shared across account switches. */
class SurveyReminderBudget {
    private var used = false
    fun beginVisit() { used = false }
    fun claim(): Boolean = if (used) false else { used = true; true }
}
