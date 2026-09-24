package com.tyust.course.manager

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SessionToken(val accountStorageKey: String, val generation: Long)
enum class RequestFeedback { Interactive, Silent }
data class SessionState(val token: SessionToken, val expired: Boolean = false,
                        val expiryFeedback: RequestFeedback = RequestFeedback.Interactive)

/** A successful login or account switch invalidates every callback from the preceding session. */
class SessionStateStore {
    private val mutableState = MutableStateFlow(SessionState(SessionToken("default", 0)))
    val state: StateFlow<SessionState> = mutableState.asStateFlow()
    val token: SessionToken get() = state.value.token

    @Synchronized
    fun replace(accountStorageKey: String): SessionToken {
        val next = SessionToken(accountStorageKey, token.generation + 1)
        mutableState.value = SessionState(next)
        return next
    }

    fun isCurrent(expected: SessionToken?): Boolean = expected != null && token == expected

    @Synchronized
    @JvmOverloads
    fun expire(expected: SessionToken, feedback: RequestFeedback = RequestFeedback.Interactive): Boolean {
        if (!isCurrent(expected)) return false
        val previous = state.value
        if (previous.expired && (previous.expiryFeedback == RequestFeedback.Interactive || feedback == RequestFeedback.Silent)) return false
        mutableState.value = SessionState(expected, expired = true, expiryFeedback = feedback)
        return true
    }
}
