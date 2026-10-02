package com.tyust.course.academic

import com.tyust.course.model.Course
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Display state never serves as authority for a selection write. */
data class CourseDetails(val phase: String = "pending", val count: Int? = null, val error: String = "")
data class CourseBrowserState(
    val courses: List<Course> = emptyList(), val context: CourseContext? = null,
    val loading: Boolean = false, val loadingMore: Boolean = false, val hasMore: Boolean = false,
    val error: String = "", val pageError: String = "", val filters: CourseFilters? = null,
    val filterError: String = "", val details: Map<String, CourseDetails> = emptyMap(),
    val expanded: Set<String> = emptySet(), val bulk: Set<String> = emptySet(), val bulkRunning: Boolean = false
)
internal interface CourseBrowserSource {
    fun requireCurrent()
    suspend fun context(): CourseContext
    suspend fun page(context: CourseContext, query: CourseQuery, position: CoursePosition): ScopedCoursePage
    suspend fun filters(context: CourseContext, query: CourseQuery): CourseFilters?
    suspend fun sections(offer: CourseOffer, cursor: String?): AcademicPage<CourseSection>
    fun display(offer: CourseOffer, section: CourseSection? = null): Course
}

/** A single queue yields between detail pages. Explicit work outranks speculation. */
internal class AcademicCourseBrowser(
    private val source: CourseBrowserSource,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val io: CoroutineDispatcher = Dispatchers.IO
) : AutoCloseable {
    private val mutable = MutableStateFlow(CourseBrowserState())
    val state: StateFlow<CourseBrowserState> = mutable
    private var query: CourseQuery? = null
    private var generation = 0L
    private var position: CoursePosition? = CoursePosition()
    private var history = CoursePageHistory()
    private val offers = linkedMapOf<String, CourseOffer>()
    private val partial = mutableMapOf<String, LinkedHashMap<String, CourseSection>>()
    private val detailCursors = mutableMapOf<String, String>()
    private val visited = mutableMapOf<String, MutableSet<String>>()
    private val manual = linkedSetOf<String>()
    private var visible = emptySet<String>()
    private var active = false
    private var busy = false
    private var pageRequested = false
    private var filtersRequested = false
    private var halted = false
    private var pump: Job? = null
    private fun update(f: (CourseBrowserState) -> CourseBrowserState) { mutable.value = f(mutable.value) }

    fun open(value: CourseQuery, refresh: Boolean = false) {
        if (query == value && !refresh) { start(); return }
        generation++; pump?.cancel(); pump = null
        val retained = if (query == value) state.value.courses else emptyList()
        query = value; position = CoursePosition(); history = CoursePageHistory()
        offers.clear(); partial.clear(); detailCursors.clear(); visited.clear(); manual.clear(); visible = emptySet()
        halted = false; pageRequested = true; filtersRequested = false
        mutable.value = CourseBrowserState(courses = retained, loading = true)
        start()
    }
    fun loadMore() {
        if (query == null || state.value.loading || state.value.loadingMore || position == null || halted) return
        pageRequested = true; update { it.copy(loadingMore = true, pageError = "") }; start()
    }
    // Keep the viewport snapshot while hidden; visibility alone gates automatic work.
    // A resumed page need not scroll before its remaining visible rows can load.
    fun setActive(value: Boolean) { active = value; start() }
    fun setBusy(value: Boolean) { busy = value; start() }
    fun visible(keys: Set<String>) { visible = keys; start() }
    fun toggle(key: String) {
        if (state.value.details[key]?.phase == "failed") { requestDetail(key); return }
        if (key in state.value.expanded) update { it.copy(expanded = it.expanded - key) }
        else {
            update { it.copy(expanded = it.expanded + key) }
            requestDetail(key)
        }
    }
    private fun requestDetail(key: String) {
        if (key !in offers || state.value.details[key]?.phase == "ready") return
        if (state.value.details[key]?.phase == "failed") {
            partial.remove(key); detailCursors.remove(key); visited.remove(key)
            update { it.copy(details = it.details + (key to CourseDetails())) }
        }
        manual += key; start()
    }
    fun expandAll(keys: Set<String>) {
        val available = keys.intersect(offers.keys)
        update { it.copy(expanded = it.expanded + available, bulk = available, bulkRunning = available.any(::needs)) }
        start()
    }
    fun collapseAll() { update { it.copy(expanded = emptySet()) } }
    fun stopBulk() { update { it.copy(bulkRunning = false) } }
    fun retryFailed() { state.value.bulk.filter { state.value.details[it]?.phase == "failed" }.forEach(::requestDetail) }
    private fun needs(key: String) = key in offers && state.value.details[key]?.phase !in setOf("ready", "failed")
    private fun detailTask(): String? = manual.firstOrNull(::needs)
        ?: if (!active) null else visible.firstOrNull(::needs)
            ?: if (state.value.bulkRunning) state.value.bulk.firstOrNull(::needs) else null
    private fun start() {
        if (pump?.isActive == true || query == null || halted || busy) return
        val epoch = generation
        pump = scope.launch {
            try {
                while (epoch == generation && !busy && !halted) {
                    source.requireCurrent()
                    when {
                        pageRequested -> fetchPage(epoch)
                        active && filtersRequested -> fetchFilters(epoch)
                        else -> { val key = detailTask() ?: break; fetchDetail(key, epoch) }
                    }
                    yield()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (epoch == generation) { halted = true; update { it.copy(loading = false, loadingMore = false, error = e.message ?: "课程上下文已失效，请刷新") } } }
            finally { if (epoch == generation) pump = null }
        }
    }
    private suspend fun fetchPage(epoch: Long) {
        pageRequested = false
        val first = state.value.context == null
        val requested = position ?: return
        try {
            val q = query!!
            val context = state.value.context ?: withContext(io) { source.requireCurrent(); source.context() }
            val page = withContext(io) { source.requireCurrent(); source.page(context, q, requested) }
            source.requireCurrent(); if (epoch != generation) return
            val rows = history.accept(requested, page)
            val displayed = rows.map { offer -> source.display(offer).also { offers[it.catalogGroupKey()] = offer } }
            position = page.next
            filtersRequested = first
            update { it.copy(context = context, courses = (if (first) emptyList() else it.courses) + displayed,
                loading = false, loadingMore = false, hasMore = position != null, error = "", pageError = "") }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (epoch != generation) return
            if (e is AcademicException && e.status == AcademicStatus.PAGE_CHANGED) position = null
            if (e is AcademicException && e.status == AcademicStatus.SESSION_EXPIRED) halted = true
            update { it.copy(loading = false, loadingMore = false, hasMore = position != null && !first,
                error = if (first) e.message.orEmpty() else it.error, pageError = if (first) "" else e.message.orEmpty()) }
        }
    }
    private suspend fun fetchFilters(epoch: Long) {
        filtersRequested = false
        try {
            val context = state.value.context!!
            val request = query!!
            val result = withContext(io) { source.requireCurrent(); source.filters(context, request) }
            source.requireCurrent(); if (epoch == generation) update { it.copy(filters = result, filterError = "") }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (epoch == generation) update { it.copy(filterError = e.message ?: "筛选条件加载失败") } }
    }
    private suspend fun fetchDetail(key: String, epoch: Long) {
        val offer = offers[key] ?: return
        update { it.copy(details = it.details + (key to CourseDetails("loading"))) }
        try {
            val cursor = detailCursors[key]
            val page = withContext(io) { source.requireCurrent(); source.sections(offer, cursor) }
            source.requireCurrent(); if (epoch != generation) return
            if (page.items.any { it.courseStableId != offer.stableId }) throw AcademicException(AcademicStatus.PAGE_CHANGED, "教学班课程不匹配")
            val rows = partial.getOrPut(key) { linkedMapOf() }
            val fresh = page.items.distinctBy { it.stableId }.filter { it.stableId !in rows }
            val seen = visited.getOrPut(key) { mutableSetOf() }
            if (page.items.isNotEmpty() && fresh.isEmpty() || rows.size + fresh.size > 10000 ||
                page.nextCursor != null && (!seen.add(page.nextCursor) || seen.size >= 100))
                throw AcademicException(AcademicStatus.PAGE_CHANGED, "教学班分页重复或超过上限")
            fresh.forEach { rows[it.stableId] = it }
            if (page.nextCursor != null) { detailCursors[key] = page.nextCursor; return }
            val sections = rows.values.map { source.display(offer, it) }
            val placeholder = source.display(offer)
            update { it.copy(courses = AcademicCourseBridge.replaceCourseSections(it.courses, placeholder, sections),
                details = it.details + (key to CourseDetails("ready", sections.size))) }
            manual -= key; partial.remove(key); visited.remove(key); detailCursors.remove(key)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (epoch != generation) return
            manual -= key
            if (e is AcademicException && e.status == AcademicStatus.SESSION_EXPIRED) {
                halted = true; update { it.copy(error = e.message ?: "登录已失效，请重新登录") }
            }
            update { it.copy(details = it.details + (key to CourseDetails("failed", error = e.message ?: "教学班加载失败"))) }
        }
        if (state.value.bulkRunning && state.value.bulk.none(::needs)) update { it.copy(bulkRunning = false) }
    }
    override fun close() { generation++; scope.cancel() }
}
