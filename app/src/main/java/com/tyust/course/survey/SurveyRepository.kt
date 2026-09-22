package com.tyust.course.survey

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SurveyFeedResult(val surveys: List<Survey>, val serverTime: Long)
data class SurveyDetailResult(val survey: Survey, val serverTime: Long)

interface SurveyTransport {
    suspend fun list(schoolHost: String): SurveyFeedResult
    suspend fun detail(id: String, schoolHost: String): SurveyDetailResult
    suspend fun click(id: String, schoolHost: String, requestId: String)
}

interface SurveyStore {
    suspend fun read(): SurveySavedData
    suspend fun write(data: SurveySavedData)
}

class SurveyUnavailableException(message: String) : Exception(message)

class SurveyRepository(
    private val store: SurveyStore,
    private val transport: SurveyTransport,
    val schoolHost: String,
    scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val elapsedClock: () -> Long = clock
) {
    private val mutableState = MutableStateFlow(SurveyFeedState())
    val state = mutableState.asStateFlow()
    private val writes = Mutex()
    private val refreshLock = Mutex()
    private var lastAttempt: Long? = null
    private val ready = scope.async {
        val saved = store.read()
        mutableState.value = SurveyFeedState(saved = if (saved.schoolHost == schoolHost) saved else
            saved.copy(schoolHost = schoolHost, surveys = emptyList(), fetchedAt = 0, serverTime = 0), loading = false)
    }

    suspend fun awaitReady() { ready.await() }

    /** Only foreground transitions call this automatically; there is no polling job. */
    suspend fun refresh(force: Boolean = false): Boolean {
        ready.await()
        if (!refreshLock.tryLock()) return false
        try {
            val elapsed = elapsedClock()
            if (!force && lastAttempt?.let { elapsed - it in 0 until MIN_REFRESH_INTERVAL } == true) return false
            lastAttempt = elapsed
            mutableState.update { it.copy(refreshing = true, error = null) }
            val result = transport.list(schoolHost)
            mutate { current -> current.copy(schoolHost = schoolHost, surveys = result.surveys,
                records = retainRecords(current.records + result.surveys.associateBy { it.id }, current.local),
                fetchedAt = clock(), serverTime = result.serverTime) }
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.update { it.copy(error = "暂时无法获取问卷，请检查网络后重试") }
            return false
        } finally {
            mutableState.update { it.copy(refreshing = false) }
            refreshLock.unlock()
        }
    }

    private fun retainRecords(records: Map<String, Survey>, local: Map<String, SurveyLocalState>): Map<String, Survey> =
        records.filterKeys { id -> local[id]?.let { it.favorite || it.completedAt != null || it.lastViewedAt != null } == true }

    private suspend fun mutate(change: (SurveySavedData) -> SurveySavedData) {
        ready.await()
        writes.withLock {
            val next = change(mutableState.value.saved)
            if (next == mutableState.value.saved) return@withLock
            store.write(next)
            mutableState.update { it.copy(saved = next) }
        }
    }

    private suspend fun local(survey: Survey, change: (SurveyLocalState) -> SurveyLocalState) = mutate { current ->
        val states = current.local + (survey.id to change(current.local[survey.id] ?: SurveyLocalState()))
        current.copy(local = states, records = retainRecords(current.records + (survey.id to survey), states))
    }

    suspend fun toggleFavorite(survey: Survey) = local(survey) { it.copy(favorite = !it.favorite) }
    suspend fun setCompleted(survey: Survey, completed: Boolean) = local(survey) { it.copy(completedAt = if (completed) clock() else null) }
    suspend fun viewed(survey: Survey) {
        local(survey) { it.copy(lastViewedAt = clock(), seen = true) }
        mutate { current ->
            val oldest = current.local.entries.filter { it.value.lastViewedAt != null }
                .sortedByDescending { it.value.lastViewedAt }.drop(MAX_HISTORY).map { it.key }.toSet()
            if (oldest.isEmpty()) current else {
                val states = current.local.mapValues { (id, state) -> if (id in oldest) state.copy(lastViewedAt = null) else state }
                current.copy(local = states, records = retainRecords(current.records, states))
            }
        }
    }

    suspend fun markSeen(ids: Collection<String>) = mutate { current ->
        val states = current.local.toMutableMap()
        ids.forEach { id -> states[id] = (states[id] ?: SurveyLocalState()).copy(seen = true) }
        current.copy(local = states)
    }

    suspend fun clearHistory() = mutate { current ->
        val states = current.local.mapValues { (_, state) -> state.copy(lastViewedAt = null) }
        current.copy(local = states, records = retainRecords(current.records, states))
    }

    suspend fun setRemindersEnabled(enabled: Boolean) = mutate { it.copy(remindersEnabled = enabled) }

    fun reminderCandidates(): List<Survey> {
        val value = mutableState.value
        // Never announce a cached result when the current publication status is unknown.
        if (value.loading || value.refreshing || value.error != null || !value.saved.remindersEnabled || lastAttempt == null) return emptyList()
        val day = surveyReminderDay(clock())
        return value.saved.surveys.filter { survey ->
            val local = value.local(survey.id)
            survey.isActive(value.serverNow(clock())) && local.completedAt == null && local.remindedOn != day
        }.sortedWith(compareByDescending<Survey> { it.important }.thenByDescending { it.pinned }.thenBy { it.id })
    }
    fun reminderCandidate(): Survey? = reminderCandidates().firstOrNull()
    suspend fun markReminded(survey: Survey) = markReminded(listOf(survey))
    suspend fun markReminded(surveys: Collection<Survey>) = mutate { current ->
        val day = surveyReminderDay(clock())
        val local = current.local.toMutableMap()
        surveys.forEach { survey -> local[survey.id] = (local[survey.id] ?: SurveyLocalState()).copy(reminded = true, remindedOn = day) }
        current.copy(local = local)
    }

    /** A cached URL is never sufficient to start filling. The server must approve now. */
    suspend fun prepareOpen(id: String): Survey {
        ready.await()
        val result = try { transport.detail(id, schoolHost) } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: SurveyUnavailableException) {
            mutate { current -> current.copy(surveys = current.surveys.filterNot { it.id == id }) }
            throw error
        } catch (_: Exception) {
            throw SurveyUnavailableException("暂时无法确认问卷状态，请联网后重试")
        }
        if (!SurveyLinks.isAllowedInitialUrl(result.survey.url)) throw SurveyUnavailableException("问卷链接无效")
        // Revalidation may reveal a changed deadline or presentation while the list is open.
        mutate { current -> current.copy(
            surveys = current.surveys.filterNot { it.id == id } + result.survey,
            records = retainRecords(current.records + (id to result.survey), current.local)
        ) }
        if (!result.survey.isActive(result.serverTime)) throw SurveyUnavailableException("这份问卷已结束")
        viewed(result.survey)
        return result.survey
    }

    suspend fun reportClick(id: String, requestId: String, consent: () -> Boolean) {
        if (!consent()) return
        try { transport.click(id, schoolHost, requestId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Entry telemetry must never interrupt filling. */ }
    }

    companion object {
        const val MIN_REFRESH_INTERVAL = 5 * 60 * 1000L
        const val MAX_HISTORY = 200
    }
}
