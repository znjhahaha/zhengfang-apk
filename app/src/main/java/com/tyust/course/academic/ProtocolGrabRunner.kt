package com.tyust.course.academic

import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.coroutineContext

data class GrabRunPolicy(val intervalMillis: Long = 1500L, val maxAttempts: Int = 100, val maxBackoffMillis: Long = maxOf(30_000L, intervalMillis))

sealed class GrabRunEvent {
    data class Attempt(val number: Int) : GrabRunEvent()
    data class Success(val item: AcademicGrabItem, val result: SelectionResult) : GrabRunEvent()
    data class Waiting(val item: AcademicGrabItem, val status: AcademicStatus, val message: String) : GrabRunEvent()
    data class Paused(val item: AcademicGrabItem, val status: AcademicStatus, val message: String) : GrabRunEvent()
    data class Exhausted(val item: AcademicGrabItem) : GrabRunEvent()
}

/** Runs only the new protocol queue. GrabService remains the compatibility runner for legacy_zf. */
class ProtocolGrabRunner(
    private var adapter: AcademicProtocolAdapter,
    private val canContinue: () -> Boolean = { true },
    private val renewSession: suspend () -> Boolean = { false }
) {
    fun replaceAdapter(current: AcademicProtocolAdapter) {
        val pinned = adapter as? com.tyust.course.academic.plugin.PluginAcademicAdapter
        adapter = if (pinned != null && current is com.tyust.course.academic.plugin.PluginAcademicAdapter)
            pinned.rebind(current.session) else current
    }
    suspend fun runOnce(item: AcademicGrabItem, confirmed: Boolean = false, candidateIntervalMillis: Long = 0): GrabRunEvent {
        return try { adapter.inSession { runOnceLocked(item, confirmed, candidateIntervalMillis) } }
        catch (e: AcademicException) {
            if (e.status in setOf(AcademicStatus.ROUND_CLOSED, AcademicStatus.NETWORK_RETRYABLE))
                GrabRunEvent.Waiting(item, e.status, e.message.orEmpty())
            else GrabRunEvent.Paused(item, e.status, e.message.orEmpty())
        }
    }

    private suspend fun runOnceLocked(item: AcademicGrabItem, confirmed: Boolean, candidateIntervalMillis: Long): GrabRunEvent {
        if (!canContinue()) throw CancellationException("Queue requires attention")
        val context = adapter.loadCourseContext()
        if (context.scopes.isEmpty()) return GrabRunEvent.Waiting(item, AcademicStatus.ROUND_CLOSED, "学校当前未开放选课轮次，等待开放")
        val candidates = if (item.useExactMatch) listOfNotNull(adapter.resolveSelection(context, item.stableCourseId, item.stableSectionId,
            item.courseName, item.teacher, item.time, item.scopeId, item.sectionName,
            allowLegacyRebind = !item.sectionIdentityKnown)) else adapter.resolveCandidates(context,
            item.stableCourseId, "", item.courseName,
            if (item.stableSectionId.isBlank()) item.teacher else "",
            if (item.stableSectionId.isBlank()) item.time else "", item.scopeId,
            if (item.stableSectionId.isBlank()) item.sectionName else "")
        if (candidates.isEmpty()) return if (item.useExactMatch)
            GrabRunEvent.Paused(item, AcademicStatus.PAGE_CHANGED, "未找到目标教学班，请刷新课程后重新确认") else
            GrabRunEvent.Waiting(item, AcademicStatus.NO_CAPACITY, "目标课程暂未返回符合条件的教学班")
        var last: GrabRunEvent = GrabRunEvent.Waiting(item, AcademicStatus.NO_CAPACITY, "当前教学班暂无可选名额")
        for ((index, candidate) in candidates.sortedBy { resolved ->
            val capacity = resolved.section.capacity ?: resolved.course.capacity
            val selected = resolved.section.selected ?: resolved.course.selected
            if (capacity != null && selected != null && selected >= capacity) 1 else 0
        }.withIndex()) {
            coroutineContext.ensureActive()
            if (index > 0 && candidateIntervalMillis > 0) delay(candidateIntervalMillis)
            if (!canContinue()) throw CancellationException("Queue requires attention")
            val result = selectResolved(item, context, candidate, confirmed)
            if (result is GrabRunEvent.Waiting && result.status == AcademicStatus.NO_CAPACITY ||
                (!item.useExactMatch && result is GrabRunEvent.Paused && result.status == AcademicStatus.CONFLICT)) {
                last = result
            } else return result
        }
        return last
    }

    private suspend fun selectResolved(item: AcademicGrabItem, context: CourseContext, resolved: ResolvedAcademicSelection, confirmed: Boolean): GrabRunEvent {
        val (offer, section) = resolved
        val result = try {
            adapter.select(SelectionTarget(offer, section, confirmed))
        } catch (e: AcademicException) {
            SelectionResult(e.status, e.message.orEmpty())
        }
        if (result.status == AcademicStatus.SUCCESS || result.status == AcademicStatus.ALREADY_SELECTED) return GrabRunEvent.Success(item, result)
        if (result.status == AcademicStatus.RESULT_UNKNOWN) {
            val selected = try { adapter.selected(context) } catch (e: CancellationException) { throw e } catch (_: Exception) {
                return GrabRunEvent.Paused(item, AcademicStatus.RESULT_UNKNOWN, "无法确认写入结果，请检查已选课程")
            }
            val confirmedSelected = selected.any { it.sectionId == section.stableId || it.stableId == section.stableId }
            if (confirmedSelected) return GrabRunEvent.Success(item, result.copy(status = AcademicStatus.SUCCESS, message = "已通过已选课程确认选课成功"))
            if (selected.any { it.courseId == offer.stableId && it.sectionId.isBlank() })
                return GrabRunEvent.Paused(item, AcademicStatus.RESULT_UNKNOWN, "已选记录缺少教学班标识，请人工确认")
            return GrabRunEvent.Paused(item, AcademicStatus.RESULT_UNKNOWN, "已选列表尚未确认此次结果，请确认后再继续")
        }
        return when (result.status) {
            AcademicStatus.NO_CAPACITY, AcademicStatus.NETWORK_RETRYABLE, AcademicStatus.ROUND_CLOSED ->
                GrabRunEvent.Waiting(item, result.status, result.message)
            else -> GrabRunEvent.Paused(item, result.status, result.message)
        }
    }

    suspend fun runUntilDone(item: AcademicGrabItem, policy: GrabRunPolicy = GrabRunPolicy(),
        onEvent: (GrabRunEvent) -> Unit): GrabRunEvent = runUntilDone(item, policy, { it() }, onEvent)

    suspend fun runUntilDone(item: AcademicGrabItem, policy: GrabRunPolicy,
        withAttempt: suspend (suspend () -> GrabRunEvent) -> GrabRunEvent,
        onEvent: (GrabRunEvent) -> Unit): GrabRunEvent {
        require(policy.intervalMillis > 0 && policy.maxAttempts > 0 && policy.maxBackoffMillis >= policy.intervalMillis)
        var attempt = 0
        var backoff = policy.intervalMillis
        var renewalAttempted = false
        suspend fun executeAttempt(): GrabRunEvent = runCatching { runOnce(item, confirmed = true, candidateIntervalMillis = policy.intervalMillis) }.getOrElse {
            if (it is CancellationException) throw it
            val status = (it as? AcademicException)?.status ?: AcademicStatus.PAGE_CHANGED
            if (status == AcademicStatus.NETWORK_RETRYABLE || status == AcademicStatus.ROUND_CLOSED)
                GrabRunEvent.Waiting(item, status, it.message.orEmpty())
            else GrabRunEvent.Paused(item, status, it.message.orEmpty())
        }
        while (attempt < policy.maxAttempts) {
            coroutineContext.ensureActive()
            attempt++
            val event = withAttempt {
                if (!canContinue()) throw CancellationException("Queue requires attention")
                onEvent(GrabRunEvent.Attempt(attempt))
                var result = executeAttempt()
                if (result is GrabRunEvent.Paused && result.status == AcademicStatus.SESSION_EXPIRED && !renewalAttempted) {
                    renewalAttempted = true
                    if (renewSession()) result = executeAttempt()
                }
                onEvent(result)
                result
            }
            when (event) {
                is GrabRunEvent.Success, is GrabRunEvent.Paused -> return event
                is GrabRunEvent.Waiting -> {
                    if (attempt == policy.maxAttempts) break
                    if (event.status != AcademicStatus.NETWORK_RETRYABLE) backoff = policy.intervalMillis
                    delay(backoff)
                    if (event.status == AcademicStatus.NETWORK_RETRYABLE) backoff = (backoff * 2).coerceAtMost(policy.maxBackoffMillis)
                }
                is GrabRunEvent.Attempt, is GrabRunEvent.Exhausted -> Unit
            }
        }
        return GrabRunEvent.Exhausted(item).also(onEvent)
    }
}
