package com.tyust.course.schedule

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tyust.course.manager.SessionToken
import com.tyust.course.manager.UserManager
import kotlinx.coroutines.*

internal data class ScheduleRefreshKey(val token: SessionToken, val school: String, val term: String)
internal enum class ScheduleLoadMode { Automatic, Manual }

/** A request survives tab transitions, but never a replaced account/session. */
internal class ScheduleRefreshCoordinator(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val cooldownMillis: Long = 2_000
) {
    private class Entry(val result: Deferred<CachedSchedule>, var finishedAt: Long? = null)
    private val requests = mutableMapOf<ScheduleRefreshKey, Entry>()
    private var activeSession: SessionToken? = null

    fun retainSession(token: SessionToken) = synchronized(requests) {
        activeSession = token
        val obsolete = requests.keys.filter { it.token != token }
        obsolete.forEach { requests.remove(it)?.result?.cancel() }
    }

    suspend fun load(key: ScheduleRefreshKey, mode: ScheduleLoadMode, fetch: suspend () -> CachedSchedule): CachedSchedule {
        val entry = synchronized(requests) {
            if (activeSession != null && activeSession != key.token) throw CancellationException("Session replaced")
            val previous = requests[key]
            if (previous != null && (!previous.result.isCompleted ||
                    mode == ScheduleLoadMode.Automatic && previous.finishedAt?.let { now() - it < cooldownMillis } == true)) previous
            else Entry(scope.async(start = CoroutineStart.LAZY) { fetch() }).also { current ->
                requests[key] = current
                current.result.invokeOnCompletion { synchronized(requests) { current.finishedAt = now() } }
            }
        }
        entry.result.start()
        return entry.result.await()
    }
}

internal class ScheduleSyncViewModel : ViewModel() {
    val refresh = ScheduleRefreshCoordinator(viewModelScope, SystemClock::elapsedRealtime)
    init {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            UserManager.getInstance().sessionState.state.collect { refresh.retainSession(it.token) }
        }
    }
}
