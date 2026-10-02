package com.tyust.course.academic

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CoursePaginationTest {
    private fun offer(id: String, scope: String = "r") = CourseOffer(id, "相同名称", scopeId = scope, sectionCount = 10)
    private class Source : CourseBrowserSource {
        var calls = 0; var contexts = 0; var sectionCalls = 0; var valid = true
        var pageFailure = false; var detailFailure = false
        var gate: CompletableDeferred<Unit>? = null
        var onSection: (() -> Unit)? = null
        var sectionResult: ((CourseOffer, String?) -> AcademicPage<CourseSection>)? = null
        val order = mutableListOf<String>()
        override fun requireCurrent() { if (!valid) throw CancellationException("changed") }
        override suspend fun context(): CourseContext { contexts++; return CourseContext(1, listOf(CourseScope("r", "模拟"))) }
        override suspend fun page(context: CourseContext, query: CourseQuery, position: CoursePosition): ScopedCoursePage {
            calls++; order += "page"; gate?.await()
            if (pageFailure) throw AcademicException(AcademicStatus.NETWORK_RETRYABLE, "模拟超时")
            val start = position.cursor?.toInt() ?: 0
            return ScopedCoursePage((start until minOf(start + 10, 29)).map { CourseOffer("c$it", query.keyword.ifBlank { "课程$it" }, scopeId = "r", sectionCount = 10) },
                if (start + 10 < 29) CoursePosition(0, (start + 10).toString()) else null)
        }
        override suspend fun filters(context: CourseContext, query: CourseQuery): CourseFilters? = null
        override suspend fun sections(offer: CourseOffer, cursor: String?): AcademicPage<CourseSection> {
            sectionCalls++; order += "detail"; onSection?.invoke()
            if (detailFailure) throw AcademicException(AcademicStatus.NETWORK_RETRYABLE, "详情超时")
            sectionResult?.let { return it(offer, cursor) }
            return AcademicPage(listOf(CourseSection(offer.stableId+"s", offer.stableId, capacity = 0, selected = 0)))
        }
        override fun display(offer: CourseOffer, section: CourseSection?) = AcademicCourseBridge.toCourse(offer, section).apply { completeParams["academic_system"] = "zf" }
    }
    @Test fun initialPageAndTwoExplicitClicksYield29WithoutHiddenPaging() = runTest {
        val source = Source(); val browser = AcademicCourseBrowser(source, backgroundScope, StandardTestDispatcher(testScheduler))
        browser.open(CourseQuery()); runCurrent()
        assertEquals(10, browser.state.value.courses.size); assertEquals(1, source.calls); assertEquals(0, source.sectionCalls)
        browser.loadMore(); browser.loadMore(); runCurrent(); assertEquals(20, browser.state.value.courses.size); assertEquals(2, source.calls)
        browser.loadMore(); runCurrent(); assertEquals(29, browser.state.value.courses.size); assertFalse(browser.state.value.hasMore)
        assertEquals(1, source.contexts)
    }
    @Test fun failedNextPageRetainsListAndRetriesOnlyThatPage() = runTest {
        val source = Source(); val browser = AcademicCourseBrowser(source, backgroundScope, StandardTestDispatcher(testScheduler))
        browser.open(CourseQuery()); runCurrent(); source.pageFailure = true; browser.loadMore(); runCurrent()
        assertEquals(10, browser.state.value.courses.size); assertTrue(browser.state.value.pageError.isNotBlank())
        source.pageFailure = false; browser.loadMore(); runCurrent(); assertEquals(20, browser.state.value.courses.size)
    }
    @Test fun visibleDetailsAreDeduplicatedWithoutReloadingCoursesAndHiddenPageStopsSpeculation() = runTest {
        val source = Source(); val browser = AcademicCourseBrowser(source, backgroundScope, StandardTestDispatcher(testScheduler))
        browser.open(CourseQuery()); runCurrent()
        val keys = browser.state.value.courses.take(3).map { it.catalogGroupKey() }.toSet()
        browser.visible(keys); runCurrent(); assertEquals(0, source.sectionCalls)
        browser.setActive(true); browser.visible(keys); runCurrent(); browser.visible(keys); runCurrent()
        assertEquals(3, source.sectionCalls); assertEquals(1, source.calls); assertEquals(1, source.contexts)
        browser.setActive(false); browser.visible(browser.state.value.courses.map { it.catalogGroupKey() }.toSet()); runCurrent()
        assertEquals(3, source.sectionCalls)
    }
    @Test fun failedAutomaticDetailsWaitForExplicitRetry() = runTest {
        val source = Source(); source.detailFailure = true
        val browser = AcademicCourseBrowser(source, backgroundScope, StandardTestDispatcher(testScheduler))
        browser.open(CourseQuery()); runCurrent(); val key = browser.state.value.courses.first().catalogGroupKey()
        browser.setActive(true); browser.visible(setOf(key)); runCurrent(); browser.visible(emptySet()); browser.visible(setOf(key)); runCurrent()
        assertEquals(1, source.sectionCalls); assertEquals("failed", browser.state.value.details[key]?.phase)
        source.detailFailure = false; browser.toggle(key); runCurrent(); assertEquals("ready", browser.state.value.details[key]?.phase)
        assertEquals(0, browser.state.value.courses.first().capacity)
    }
    @Test fun collapseDuringBulkNeverReopensAndDoesNotFetchCoursePages() = runTest {
        val source = Source(); val browser = AcademicCourseBrowser(source, backgroundScope, StandardTestDispatcher(testScheduler))
        browser.open(CourseQuery()); runCurrent(); browser.setActive(true)
        source.onSection = { browser.collapseAll() }
        browser.expandAll(browser.state.value.courses.take(3).map { it.catalogGroupKey() }.toSet()); runCurrent()
        assertEquals(3, source.sectionCalls); assertEquals(1, source.calls); assertTrue(browser.state.value.expanded.isEmpty())
        assertFalse(browser.state.value.bulkRunning)
    }
    @Test fun userPagingOutranksQueuedAutomaticDetails() = runTest {
        val source = Source(); val browser = AcademicCourseBrowser(source, backgroundScope, StandardTestDispatcher(testScheduler))
        browser.open(CourseQuery()); runCurrent(); browser.setBusy(true); browser.setActive(true)
        browser.visible(browser.state.value.courses.take(2).map { it.catalogGroupKey() }.toSet()); browser.loadMore()
        browser.setBusy(false); runCurrent(); assertEquals(listOf("page", "page", "detail", "detail"), source.order)
    }
    @Test fun changedSearchRejectsCancelledLateFirstPage() = runTest {
        val source = Source(); val browser = AcademicCourseBrowser(source, backgroundScope, StandardTestDispatcher(testScheduler))
        source.gate = CompletableDeferred(); browser.open(CourseQuery(keyword = "old")); runCurrent()
        source.gate = null; browser.open(CourseQuery(keyword = "new")); runCurrent()
        assertTrue(browser.state.value.courses.all { it.name == "new" }); assertEquals(10, browser.state.value.courses.size)
    }
    @Test fun unchangedQueryReentryDoesNotRefetch() = runTest {
        val source = Source(); val browser = AcademicCourseBrowser(source, backgroundScope, StandardTestDispatcher(testScheduler))
        browser.open(CourseQuery()); runCurrent(); browser.setActive(false); browser.open(CourseQuery()); runCurrent()
        assertEquals(1, source.calls)
    }
    @Test fun resumedPageContinuesVisibleDetailsWithoutRequiringAnotherScroll() = runTest {
        val source = Source(); val browser = AcademicCourseBrowser(source, backgroundScope, StandardTestDispatcher(testScheduler))
        browser.open(CourseQuery()); runCurrent(); browser.setActive(true)
        source.onSection = { browser.setActive(false) }
        browser.visible(browser.state.value.courses.take(2).map { it.catalogGroupKey() }.toSet()); runCurrent()
        assertEquals(1, source.sectionCalls)
        source.onSection = null; browser.setActive(true); runCurrent()
        assertEquals(2, source.sectionCalls); assertEquals(1, source.calls)
    }
    @Test fun expandingAlreadyLoadedCoursesDoesNotLeaveABulkTaskRunning() = runTest {
        val source = Source(); val browser = AcademicCourseBrowser(source, backgroundScope, StandardTestDispatcher(testScheduler))
        browser.open(CourseQuery()); runCurrent(); browser.setActive(true)
        val keys = browser.state.value.courses.take(2).map { it.catalogGroupKey() }.toSet()
        browser.expandAll(keys); runCurrent(); browser.collapseAll(); browser.expandAll(keys); runCurrent()
        assertFalse(browser.state.value.bulkRunning); assertEquals(keys, browser.state.value.expanded)
        assertEquals(2, source.sectionCalls)
    }
    @Test fun detailsAccumulatePagesAndYieldToUserPagingBeforePublishingCompleteCount() = runTest {
        val source = Source(); val browser = AcademicCourseBrowser(source, backgroundScope, StandardTestDispatcher(testScheduler))
        browser.open(CourseQuery()); runCurrent(); browser.setActive(true)
        val key = browser.state.value.courses.first().catalogGroupKey()
        source.sectionResult = { offer, cursor ->
            if (cursor == null) {
                browser.setBusy(true); browser.loadMore()
                AcademicPage(listOf(CourseSection("s1", offer.stableId, capacity = 0, selected = 0)), "second")
            } else {
                assertEquals("second", cursor)
                AcademicPage(listOf(CourseSection("s2", offer.stableId)))
            }
        }
        browser.toggle(key); runCurrent()
        assertEquals("loading", browser.state.value.details[key]?.phase)
        browser.setBusy(false); runCurrent()
        assertEquals(listOf("page", "detail", "page", "detail"), source.order)
        assertEquals(CourseDetails("ready", 2), browser.state.value.details[key])
        assertEquals(setOf("s1", "s2"), browser.state.value.courses.filter { it.catalogGroupKey() == key }.map { it.classId }.toSet())
    }
    @Test fun expiredSessionStopsAllPendingPreloads() = runTest {
        val source = Source(); val browser = AcademicCourseBrowser(source, backgroundScope, StandardTestDispatcher(testScheduler))
        browser.open(CourseQuery()); runCurrent(); browser.setActive(true)
        source.sectionResult = { _, _ -> throw AcademicException(AcademicStatus.SESSION_EXPIRED, "请重新登录") }
        browser.expandAll(browser.state.value.courses.map { it.catalogGroupKey() }.toSet()); runCurrent()
        assertEquals(1, source.sectionCalls); assertEquals("请重新登录", browser.state.value.error)
        browser.retryFailed(); browser.loadMore(); runCurrent()
        assertEquals(1, source.calls); assertEquals(1, source.sectionCalls)
    }
    @Test fun emptyFilteredPageWithFreshCursorCanContinue() {
        val history = CoursePageHistory()
        assertTrue(history.accept(CoursePosition(), ScopedCoursePage(emptyList(), CoursePosition(0,"10"))).isEmpty())
        assertEquals(1, history.accept(CoursePosition(0,"10"), ScopedCoursePage(listOf(offer("x")),null)).size)
    }
    @Test fun repeatedCursorAndRepeatedNonemptyPageAreRejected() {
        val history = CoursePageHistory()
        history.accept(CoursePosition(), ScopedCoursePage(listOf(offer("x")), CoursePosition(0,"next")))
        assertThrows(AcademicException::class.java) { history.accept(CoursePosition(0,"next"),ScopedCoursePage(listOf(offer("y")),CoursePosition())) }
        assertThrows(AcademicException::class.java) { history.accept(CoursePosition(0,"next"),ScopedCoursePage(listOf(offer("x")),null)) }
    }
    @Test fun sameNameDifferentCourseAndRoundDoNotDeduplicate() {
        val history = CoursePageHistory()
        val rows = listOf(offer("a"),offer("b"),offer("a","other"))
        assertEquals(3,history.accept(CoursePosition(),ScopedCoursePage(rows,null)).size)
    }
    @Test fun completeDetailReplacementRemovesOldSectionsOnlyInThatCourse() {
        val a = AcademicCourseBridge.toCourse(offer("a"),CourseSection("old","a"))
        val b = AcademicCourseBridge.toCourse(offer("b"),CourseSection("other","b"))
        val replacement = AcademicCourseBridge.toCourse(offer("a"),CourseSection("new","a"))
        val result = AcademicCourseBridge.replaceCourseSections(listOf(a,b),a,listOf(replacement))
        assertEquals(listOf("new","other"),result.map { it.classId })
    }
}
