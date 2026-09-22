package com.tyust.course.survey

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.AtomicFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tyust.course.manager.UserManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal object SurveyJson {
    private fun strings(json: JSONArray?): List<String> = json?.let { values -> List(values.length()) { values.getString(it) } }.orEmpty()
    private fun common(json: JSONObject): Survey {
        val id = json.getString("id")
        val url = json.getString("url")
        require(id.matches(Regex("[A-Za-z0-9-]{1,100}")) && SurveyLinks.isAllowedInitialUrl(url))
        return Survey(id = id, title = json.getString("title"), description = json.optString("description"), url = url,
            category = json.optString("category", "其他"), tags = strings(json.optJSONArray("tags")),
            publisher = json.optString("publisher"), estimatedMinutes = json.optInt("estimatedMinutes", 3),
            coverUrl = json.optString("coverUrl"), important = json.optBoolean("important"), pinned = json.optBoolean("pinned"),
            sortOrder = json.optInt("sortOrder"), availability = json.optString("availability", "active"))
    }
    fun fromApi(json: JSONObject): Survey = common(json).copy(
        startsAt = parseSurveyTime(json.optString("startsAt")), endsAt = parseSurveyTime(json.optString("endsAt")),
        publishedAt = parseSurveyTime(json.optString("firstPublishedAt")) ?: 0,
        updatedAt = parseSurveyTime(json.optString("updatedAt")) ?: 0)

    private fun fromDisk(json: JSONObject): Survey = common(json).copy(
        startsAt = if (json.isNull("startsAt")) null else json.optLong("startsAt"),
        endsAt = if (json.isNull("endsAt")) null else json.optLong("endsAt"),
        publishedAt = json.optLong("publishedAt"), updatedAt = json.optLong("updatedAt"))

    private fun surveyJson(survey: Survey) = JSONObject().apply {
        put("id", survey.id); put("title", survey.title); put("url", survey.url); put("description", survey.description)
        put("category", survey.category); put("tags", JSONArray(survey.tags)); put("publisher", survey.publisher)
        put("estimatedMinutes", survey.estimatedMinutes); put("coverUrl", survey.coverUrl); put("important", survey.important)
        put("pinned", survey.pinned); put("sortOrder", survey.sortOrder); put("availability", survey.availability)
        put("startsAt", survey.startsAt ?: JSONObject.NULL); put("endsAt", survey.endsAt ?: JSONObject.NULL)
        put("publishedAt", survey.publishedAt); put("updatedAt", survey.updatedAt)
    }

    fun encode(data: SurveySavedData): String = JSONObject().apply {
        put("version", 2); put("schoolHost", data.schoolHost); put("fetchedAt", data.fetchedAt); put("serverTime", data.serverTime)
        put("remindersEnabled", data.remindersEnabled)
        put("surveys", JSONArray().apply { data.surveys.forEach { put(surveyJson(it)) } })
        put("records", JSONArray().apply { data.records.values.forEach { put(surveyJson(it)) } })
        put("local", JSONObject().apply { data.local.forEach { (id, state) -> put(id, JSONObject().apply {
            put("favorite", state.favorite); put("seen", state.seen); put("reminded", state.reminded)
            put("remindedOn", state.remindedOn ?: JSONObject.NULL)
            put("viewed", state.lastViewedAt ?: JSONObject.NULL); put("completed", state.completedAt ?: JSONObject.NULL)
        }) } })
    }.toString()

    fun decode(value: String, now: Long = System.currentTimeMillis()): SurveySavedData {
        val json = JSONObject(value)
        require(json.optInt("version") in 1..2)
        fun surveys(key: String): List<Survey> = json.optJSONArray(key)?.let { array ->
            List(array.length()) { fromDisk(array.getJSONObject(it)) }
        }.orEmpty()
        val localJson = json.optJSONObject("local") ?: JSONObject()
        val local = buildMap {
            localJson.keys().forEach { id ->
                val state = localJson.getJSONObject(id)
                put(id, SurveyLocalState(state.optBoolean("favorite"),
                    if (state.isNull("viewed")) null else state.optLong("viewed"),
                    if (state.isNull("completed")) null else state.optLong("completed"), state.optBoolean("seen"), state.optBoolean("reminded"),
                    if (state.has("remindedOn") && !state.isNull("remindedOn")) state.getString("remindedOn")
                    else if (json.optInt("version") == 1 && state.optBoolean("reminded")) surveyReminderDay(now) else null))
            }
        }
        return SurveySavedData(schoolHost = json.optString("schoolHost"), surveys = surveys("surveys"),
            records = surveys("records").associateBy { it.id }, local = local, fetchedAt = json.optLong("fetchedAt"),
            serverTime = json.optLong("serverTime"), remindersEnabled = json.optBoolean("remindersEnabled", true))
    }
}

/** Private, atomic account files are excluded from Android/cloud backups. */
class SurveyFileStore(context: Context, accountKey: String) : SurveyStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "surveys-${SurveyLinks.accountFileKey(accountKey)}.json"))
    override suspend fun read(): SurveySavedData = withContext(Dispatchers.IO) {
        runCatching { file.openRead().bufferedReader(Charsets.UTF_8).use { SurveyJson.decode(it.readText()) } }.getOrDefault(SurveySavedData())
    }
    override suspend fun write(data: SurveySavedData) = withContext(Dispatchers.IO) {
        val stream = file.startWrite()
        try {
            stream.write(SurveyJson.encode(data).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) { file.failWrite(stream); throw error }
    }
}

class SurveyViewModel(application: Application) : AndroidViewModel(application) {
    private val repositories = mutableMapOf<String, SurveyRepository>()
    fun forAccount(account: String, schoolUrl: String?): SurveyRepository {
        val host = SurveyLinks.schoolHost(schoolUrl)
        val key = "$account\n$host"
        return repositories.getOrPut(key) {
            SurveyRepository(SurveyFileStore(getApplication(), key), transportFactory(UserManager.getInstance().isDemoMode),
                host, viewModelScope, elapsedClock = android.os.SystemClock::elapsedRealtime)
        }
    }
    companion object {
        internal var transportFactory: (Boolean) -> SurveyTransport = { demo ->
            if (!demo) SurveyApi() else object : SurveyTransport {
                override suspend fun list(schoolHost: String) = SurveyFeedResult(emptyList(), System.currentTimeMillis())
                override suspend fun detail(id: String, schoolHost: String): SurveyDetailResult = throw SurveyUnavailableException("本地演示模式暂无问卷")
                override suspend fun click(id: String, schoolHost: String, requestId: String) = Unit
            }
        }
    }
}

/** Activity-to-activity navigation and configuration changes do not start a new visit. */
object SurveyVisitTracker {
    val budget = SurveyReminderBudget()
    private val mutableVisit = MutableStateFlow(0L)
    val visit = mutableVisit.asStateFlow()
    private var initialized = false
    fun initialize(application: Application) {
        if (initialized) return
        initialized = true
        var started = 0
        var changingConfiguration = false
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (started == 0 && !changingConfiguration) { budget.beginVisit(); mutableVisit.value++ }
                started++
            }
            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                changingConfiguration = activity.isChangingConfigurations
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
