package com.tyust.course.survey

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.UUID

private class MemorySurveyStore(var value: SurveySavedData = SurveySavedData()) : SurveyStore {
    override suspend fun read() = SurveyJson.decode(SurveyJson.encode(value))
    override suspend fun write(data: SurveySavedData) { value = SurveyJson.decode(SurveyJson.encode(data)) }
}
private class FakeSurveyTransport(var result: SurveyFeedResult) : SurveyTransport {
    var calls = 0
    val clicks = mutableListOf<String>()
    var offline = false
    var unavailable = false
    var nextList: CompletableDeferred<SurveyFeedResult>? = null
    override suspend fun list(schoolHost: String): SurveyFeedResult { calls++; if (offline) throw IOException("offline"); return nextList?.await() ?: result }
    override suspend fun detail(id: String, schoolHost: String): SurveyDetailResult {
        if (offline) throw IOException("offline")
        if (unavailable) throw SurveyUnavailableException("问卷已下架")
        return SurveyDetailResult(result.surveys.first { it.id == id }, result.serverTime)
    }
    override suspend fun click(id: String, schoolHost: String, requestId: String) { clicks += requestId }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SurveyRepositoryTest {
    private val now = 1_789_500_000_000L
    private fun survey(id: String = "survey-1", important: Boolean = false) = Survey(id, "校园体验调查", url = "https://www.wjx.cn/vm/a?from=app%2Btest", important = important)
    @Test fun personalRecordsPersistAndRemainIsolatedByAccount() = runTest {
        val first = MemorySurveyStore(); val other = MemorySurveyStore()
        val api = FakeSurveyTransport(SurveyFeedResult(listOf(survey()), now))
        fun repo(store: SurveyStore) = SurveyRepository(store, api, "jw.example.edu.cn", backgroundScope, { now })
        val a = repo(first)
        a.refresh(); a.viewed(survey()); a.toggleFavorite(survey()); a.setCompleted(survey(), true)
        val restored = repo(first).also { it.awaitReady() }; val b = repo(other).also { it.refresh() }
        assertTrue(restored.state.value.local("survey-1").favorite)
        assertEquals(now, restored.state.value.local("survey-1").lastViewedAt)
        assertEquals(now, restored.state.value.local("survey-1").completedAt)
        assertEquals(SurveyLocalState(), b.state.value.local("survey-1"))
        restored.setCompleted(survey(), false); restored.toggleFavorite(survey())
        val again = repo(first).also { it.awaitReady() }
        assertNull(again.state.value.local("survey-1").completedAt)
        assertFalse(again.state.value.local("survey-1").favorite)
        assertEquals(now, again.state.value.local("survey-1").lastViewedAt)
        assertNotEquals(SurveyLinks.accountFileKey("account-a"), SurveyLinks.accountFileKey("account-b"))
    }
    @Test fun clearingHistoryKeepsOtherFlags() = runTest {
        val store = MemorySurveyStore(); val repo = SurveyRepository(store, FakeSurveyTransport(SurveyFeedResult(listOf(survey()), now)), "", backgroundScope, { now })
        repo.refresh(); repo.viewed(survey()); repo.toggleFavorite(survey()); repo.setCompleted(survey(), true); repo.markReminded(survey()); repo.clearHistory()
        val value = store.value.local.getValue("survey-1")
        assertNull(value.lastViewedAt); assertTrue(value.favorite && value.seen && value.reminded)
        assertNotNull(value.completedAt); assertNotNull(store.value.records["survey-1"])
    }
    @Test fun foregroundRefreshIsThrottledEvenAfterFailureAndNeverPollsWhileIdle() = runTest {
        val api = FakeSurveyTransport(SurveyFeedResult(listOf(survey()), now))
        val repo = SurveyRepository(MemorySurveyStore(), api, "", backgroundScope, { now + testScheduler.currentTime }, { testScheduler.currentTime })
        repeat(5) { repo.refresh() }; assertEquals(1, api.calls)
        advanceTimeBy(120_000); runCurrent(); assertEquals(1, api.calls)
        advanceTimeBy(180_000); repo.refresh(); assertEquals(2, api.calls)
        api.offline = true; repo.refresh(force = true)
        repeat(3) { repo.refresh() }; assertEquals(3, api.calls)
        assertEquals(1, repo.state.value.saved.surveys.size); assertNotNull(repo.state.value.error)
        api.offline = false; repo.refresh(force = true); assertEquals(4, api.calls); assertNull(repo.state.value.error)
    }
    @Test fun remindersAggregateUnfinishedSurveysOncePerDayAndIgnoreSeenState() = runTest {
        val one = survey("one", true); val two = survey("two", true)
        val store = MemorySurveyStore(); val api = FakeSurveyTransport(SurveyFeedResult(listOf(one, two), now))
        val repo = SurveyRepository(store, api, "", backgroundScope, { now }); repo.refresh()
        val budget = SurveyReminderBudget()
        assertEquals("one", repo.reminderCandidate()?.id); assertTrue(budget.claim()); repo.markReminded(one)
        assertFalse(budget.claim()); assertEquals("two", repo.reminderCandidate()?.id)
        budget.beginVisit(); assertTrue(budget.claim()); repo.setRemindersEnabled(false)
        assertNull(repo.reminderCandidate()); assertEquals(2, repo.state.value.unreadCount(now))
        repo.setRemindersEnabled(true); repo.markSeen(listOf(two.id))
        assertEquals("two", repo.reminderCandidate()?.id); assertEquals(1, repo.state.value.unreadCount(now))
        repo.markReminded(repo.reminderCandidates())
        val restored = SurveyRepository(store, api, "", backgroundScope, { now }).also { it.refresh() }
        assertNull(restored.reminderCandidate())
    }
    @Test fun dailyReminderIncludesOrdinarySurveysAndStopsForCompletedExpiredOrFutureOnes() = runTest {
        var clock = now
        val ordinary = survey("ordinary")
        val complete = survey("complete", true)
        val expiring = survey("expires").copy(endsAt = now + 60_000)
        val future = survey("future").copy(startsAt = now + 172_800_000)
        val api = FakeSurveyTransport(SurveyFeedResult(listOf(ordinary, complete, expiring, future), now))
        val repo = SurveyRepository(MemorySurveyStore(), api, "", backgroundScope, { clock })
        repo.refresh(); repo.setCompleted(complete, true); repo.viewed(ordinary)
        assertEquals(setOf("ordinary", "expires"), repo.reminderCandidates().map { it.id }.toSet())
        repo.markReminded(repo.reminderCandidates()); assertTrue(repo.reminderCandidates().isEmpty())
        clock += 86_400_000
        assertEquals(listOf("ordinary"), repo.reminderCandidates().map { it.id })
        repo.setCompleted(ordinary, true); assertTrue(repo.reminderCandidates().isEmpty())
    }
    @Test fun oldReminderFlagsMigrateToTheMigrationDayWithoutLosingCompletionOrPreferences() {
        val original = SurveySavedData(remindersEnabled = false, local = mapOf("one" to SurveyLocalState(favorite = true, reminded = true, completedAt = now)))
        val json = org.json.JSONObject(SurveyJson.encode(original)).put("version", 1)
        json.getJSONObject("local").getJSONObject("one").remove("remindedOn")
        val migrated = SurveyJson.decode(json.toString(), now)
        assertFalse(migrated.remindersEnabled)
        assertEquals(surveyReminderDay(now), migrated.local.getValue("one").remindedOn)
        assertEquals(now, migrated.local.getValue("one").completedAt)
        assertTrue(migrated.local.getValue("one").favorite)
        assertEquals(migrated, SurveyJson.decode(SurveyJson.encode(migrated), now + 86_400_000))
    }
    @Test fun cacheAndFailedRefreshDoNotTriggerReminders() = runTest {
        val store = MemorySurveyStore(SurveySavedData(surveys = listOf(survey(important = true)), fetchedAt = now, serverTime = now))
        val api = FakeSurveyTransport(SurveyFeedResult(store.value.surveys, now)).apply { offline = true }
        val repo = SurveyRepository(store, api, "", backgroundScope, { now }); repo.awaitReady()
        assertNull(repo.reminderCandidate()); repo.refresh(); assertNull(repo.reminderCandidate()); assertEquals(1, repo.state.value.unreadCount(now))
    }
    @Test fun fillingRevalidatesAndNeverReturnsCachedUrlsWhenOfflinePausedOrEnded() = runTest {
        val api = FakeSurveyTransport(SurveyFeedResult(listOf(survey()), now)); val repo = SurveyRepository(MemorySurveyStore(), api, "", backgroundScope, { now }); repo.refresh()
        api.offline = true
        try { repo.prepareOpen("survey-1"); fail("offline launch") } catch (error: SurveyUnavailableException) { assertTrue(error.message!!.contains("联网")) }
        api.offline = false; api.unavailable = true
        try { repo.prepareOpen("survey-1"); fail("paused launch") } catch (_: SurveyUnavailableException) { }
        assertTrue(repo.state.value.saved.surveys.isEmpty()); api.unavailable = false
        api.result = SurveyFeedResult(listOf(survey().copy(endsAt = now - 1)), now)
        try { repo.prepareOpen("survey-1"); fail("ended launch") } catch (error: SurveyUnavailableException) { assertTrue(error.message!!.contains("结束")) }
        assertTrue(repo.state.value.survey("survey-1")!!.isEnded(now))
        api.result = SurveyFeedResult(listOf(survey()), now)
        assertEquals(survey().url, repo.prepareOpen("survey-1").url); assertTrue(api.clicks.isEmpty())
    }
    @Test fun telemetryUsesCurrentConsentAndRandomOpeningRequest() = runTest {
        val api = FakeSurveyTransport(SurveyFeedResult(listOf(survey()), now)); val repo = SurveyRepository(MemorySurveyStore(), api, "", backgroundScope, { now })
        val request = UUID.randomUUID().toString(); var consent = false
        repo.reportClick("survey-1", request) { consent }; assertTrue(api.clicks.isEmpty())
        consent = true; repo.reportClick("survey-1", request) { consent }; assertEquals(listOf(request), api.clicks)
    }
    @Test fun historySurvivesUnpublishingAndCopiesDoNotInheritFlags() = runTest {
        val api = FakeSurveyTransport(SurveyFeedResult(listOf(survey()), now)); val repo = SurveyRepository(MemorySurveyStore(), api, "", backgroundScope, { now })
        repo.refresh(); repo.viewed(survey()); repo.setCompleted(survey(), true)
        api.result = SurveyFeedResult(listOf(survey("copy")), now); repo.refresh(force = true)
        assertEquals("survey-1", filterSurveys(repo.state.value, "", "", SurveyStatusFilter.All, SurveyCollection.History, now).single().id)
        assertNull(repo.state.value.local("copy").completedAt); assertNull(repo.state.value.local("copy").lastViewedAt)
    }
    @Test fun outstandingPreviousAccountRequestsCannotChangeTheCurrentAccountStore() = runTest {
        val slow = FakeSurveyTransport(SurveyFeedResult(emptyList(), now)).apply { nextList = CompletableDeferred() }
        val firstStore = MemorySurveyStore(); val otherStore = MemorySurveyStore()
        val first = SurveyRepository(firstStore, slow, "jw.a.edu.cn", backgroundScope, { now })
        val pending = launch { first.refresh() }; runCurrent()
        val other = SurveyRepository(otherStore, FakeSurveyTransport(SurveyFeedResult(listOf(survey("other")), now)), "jw.b.edu.cn", backgroundScope, { now })
        other.refresh(); slow.nextList!!.complete(SurveyFeedResult(listOf(survey("first")), now)); pending.join()
        assertEquals(listOf("other"), otherStore.value.surveys.map { it.id }); assertEquals(listOf("first"), firstStore.value.surveys.map { it.id })
    }
    @Test fun cancellationDoesNotBecomeAnOfflineError() = runTest {
        val api = FakeSurveyTransport(SurveyFeedResult(emptyList(), now)).apply { nextList = CompletableDeferred() }; val repo = SurveyRepository(MemorySurveyStore(), api, "", backgroundScope, { now })
        val pending = launch { repo.refresh() }; runCurrent(); assertTrue(repo.state.value.refreshing)
        pending.cancel(); pending.join(); assertNull(repo.state.value.error); assertFalse(repo.state.value.refreshing)
    }
    @Test fun filtersSupportTextTagsCategoryStatusAndHistoryOrder() {
        val first = survey("first").copy(category = "校园生活", publisher = "学生会", pinned = true)
        val second = survey("second").copy(category = "教学", tags = listOf("期末"), endsAt = now - 1)
        val state = SurveyFeedState(SurveySavedData(surveys = listOf(second, first), records = mapOf(first.id to first, second.id to second),
            local = mapOf(first.id to SurveyLocalState(lastViewedAt = now - 100), second.id to SurveyLocalState(lastViewedAt = now))))
        assertEquals("first", filterSurveys(state, "学生会", "校园生活", SurveyStatusFilter.Active, SurveyCollection.All, now).single().id)
        assertEquals("second", filterSurveys(state, "期末", "", SurveyStatusFilter.Ended, SurveyCollection.All, now).single().id)
        assertEquals(listOf("second", "first"), filterSurveys(state, "", "", SurveyStatusFilter.All, SurveyCollection.History, now).map { it.id })
    }
    @Test fun aSchoolCacheDoesNotAppearUnderAnotherSchool() = runTest {
        val store = MemorySurveyStore(SurveySavedData(schoolHost = "jw.first.edu.cn", surveys = listOf(survey()), fetchedAt = now))
        val repo = SurveyRepository(store, FakeSurveyTransport(SurveyFeedResult(emptyList(), now)), "jw.second.edu.cn", backgroundScope, { now }); repo.awaitReady()
        assertTrue(repo.state.value.saved.surveys.isEmpty()); assertEquals(0L, repo.state.value.saved.fetchedAt)
    }
    @Test fun officialLinksAndStoredPayloadsKeepParameters() {
        listOf("https://wjx.cn/vm/a", "https://V.WJX.TOP/vm/a?q=a%2Bb#here", "http://sub.wjx.com/x").forEach { assertTrue(it, SurveyLinks.isAllowedInitialUrl(it)) }
        listOf("https://wjx.cn.evil.test/a", "https://notwjx.cn/a", "https://wjx.cn@evil.test", "https://user@wjx.cn/a", "javascript:alert(1)", "https://wjx.cn:444/a").forEach { assertFalse(it, SurveyLinks.isAllowedInitialUrl(it)) }
        assertEquals("jw.example.edu.cn", SurveyLinks.schoolHost("https://JW.example.edu.cn:8443/path?key=1"))
        val saved = SurveySavedData(surveys = listOf(survey().copy(endsAt = now)), records = mapOf("survey-1" to survey()))
        assertEquals(saved, SurveyJson.decode(SurveyJson.encode(saved)))
    }
}
