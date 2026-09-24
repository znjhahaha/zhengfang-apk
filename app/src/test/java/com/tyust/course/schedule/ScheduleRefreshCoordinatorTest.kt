package com.tyust.course.schedule

import com.tyust.course.academic.AcademicTerm
import com.tyust.course.manager.SessionToken
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleRefreshCoordinatorTest {
    private val token = SessionToken("account", 1)
    private val key = ScheduleRefreshKey(token, "school", "2026-2027-1")
    private val empty = CachedSchedule(AcademicTerm(key.term), AcademicTerm(key.term), "{\"kbList\":[]}", false)

    @Test fun entriesAndManualSyncJoinOneInFlightRequestEvenIfTheFirstPageLeaves() = runTest {
        val coordinator = ScheduleRefreshCoordinator(backgroundScope, { testScheduler.currentTime })
        val response = CompletableDeferred<CachedSchedule>()
        var calls = 0
        val first = async { coordinator.load(key, ScheduleLoadMode.Automatic) { calls++; response.await() } }
        runCurrent()
        first.cancelAndJoin()
        val second = async { coordinator.load(key, ScheduleLoadMode.Manual) { calls++; empty } }
        runCurrent()
        assertEquals(1, calls)
        response.complete(empty)
        assertSame(empty, second.await())
    }

    @Test fun validEmptyDataIsDeduplicatedForTwoSecondsButManualRefreshBypassesCooldown() = runTest {
        val coordinator = ScheduleRefreshCoordinator(backgroundScope, { testScheduler.currentTime })
        var calls = 0
        suspend fun fetch(mode: ScheduleLoadMode = ScheduleLoadMode.Automatic) = coordinator.load(key, mode) { calls++; empty }
        fetch(); advanceTimeBy(1_999); fetch()
        assertEquals(1, calls)
        advanceTimeBy(1); fetch()
        assertEquals(2, calls)
        fetch(ScheduleLoadMode.Manual)
        assertEquals(3, calls)
    }

    @Test fun schoolAndTermAreIndependentAndReplacedSessionsCannotCommitLateResults() = runTest {
        val coordinator = ScheduleRefreshCoordinator(backgroundScope, { testScheduler.currentTime })
        coordinator.retainSession(token)
        var committed = false
        val old = async { coordinator.load(key, ScheduleLoadMode.Automatic) { delay(10_000); committed = true; empty } }
        runCurrent()
        assertSame(empty, coordinator.load(key.copy(term = "2026-2027-2"), ScheduleLoadMode.Automatic) { empty })
        assertSame(empty, coordinator.load(key.copy(school = "other"), ScheduleLoadMode.Automatic) { empty })
        coordinator.retainSession(token.copy(generation = 2))
        assertTrue(runCatching { old.await() }.exceptionOrNull() is CancellationException)
        advanceTimeBy(10_001)
        assertFalse(committed)
        assertTrue(runCatching { coordinator.load(key, ScheduleLoadMode.Automatic) { empty } }.exceptionOrNull() is CancellationException)
    }

    @Test fun failedRefreshDoesNotRemoveAPreviouslySavedEmptyCache() = runTest {
        val cache = ScheduleCacheStore(MemoryPreferences()) { empty.currentTerm }
        cache.save("account", "school", empty)
        val requestScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val coordinator = ScheduleRefreshCoordinator(requestScope, { testScheduler.currentTime })
            var calls = 0
            repeat(2) {
                assertTrue(runCatching { coordinator.load(key, ScheduleLoadMode.Automatic) { calls++; error("offline") } }.isFailure)
            }
            assertEquals(1, calls)
            assertEquals(empty.json, cache.selected("account", "school", false)?.json)
        } finally { requestScope.cancel() }
    }
}
