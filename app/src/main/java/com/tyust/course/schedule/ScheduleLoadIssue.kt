package com.tyust.course.schedule

import com.tyust.course.academic.AcademicException
import com.tyust.course.academic.AcademicStatus
import java.io.IOException

/** App presentation state; a failure never changes the cached schedule (including an empty one). */
data class ScheduleLoadIssue(val kind: Kind, val message: String) {
    enum class Kind { LoginExpired, Network, InvalidResponse, Other }

    fun visibleWithCache(): Boolean = kind != Kind.LoginExpired
    val actionLabel: String get() = if (kind == Kind.LoginExpired) "重新登录" else "重试同步"

    companion object {
        fun expired() = ScheduleLoadIssue(Kind.LoginExpired, "登录已过期，重新登录后获取课表")
        fun from(error: Exception): ScheduleLoadIssue {
            val status = (error as? AcademicException)?.status
            if (status == AcademicStatus.SESSION_EXPIRED) return expired()
            val kind = when {
                status == AcademicStatus.PAGE_CHANGED || error is org.json.JSONException -> Kind.InvalidResponse
                status == AcademicStatus.NETWORK_RETRYABLE || error is IOException -> Kind.Network
                else -> Kind.Other
            }
            return ScheduleLoadIssue(kind, error.message?.takeIf { it.isNotBlank() } ?: when (kind) {
                Kind.Network -> "暂时无法连接学校，请稍后重试"
                Kind.InvalidResponse -> "学校返回格式无法识别"
                else -> "课表同步失败，请重试"
            })
        }
    }
}
