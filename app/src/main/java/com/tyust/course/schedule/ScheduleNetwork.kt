package com.tyust.course.schedule

import com.tyust.course.academic.*
import com.tyust.course.manager.RequestFeedback
import com.tyust.course.manager.SessionToken
import com.tyust.course.model.SchoolConfig
import com.tyust.course.network.CourseApiClient
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal suspend fun fetchLegacySchedule(
    school: SchoolConfig, current: AcademicTerm, term: AcademicTerm,
    token: SessionToken, feedback: RequestFeedback
): CachedSchedule = suspendCancellableCoroutine { continuation ->
    val body = "xnm=" + term.year + "&xqm=" + if (term.semester == 1) "3" else "12"
    val call = CourseApiClient.getInstance().fetchSchedule(school, body, token, feedback, object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            val loaded = runCatching {
                response.use {
                    val json = it.body?.string().orEmpty()
                    if (it.code in setOf(401, 403) || AcademicHtml.isLoginPage(json) ||
                        json.contains("用户登录") || json.contains("\"notLogin\"") || json.contains("\"sessionExpired\""))
                        throw AcademicException(AcademicStatus.SESSION_EXPIRED, "登录已失效，请重新登录")
                    if (!it.isSuccessful || ScheduleJson.parse(json) == null)
                        throw AcademicException(AcademicStatus.PAGE_CHANGED, "课表响应无效，请重试")
                    CachedSchedule(current, term, json, false)
                }
            }
            if (continuation.isActive) loaded.fold(continuation::resume, continuation::resumeWithException)
        }
    })
    continuation.invokeOnCancellation { call.cancel() }
}
