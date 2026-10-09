package com.tyust.course.academic.plugin

/** Register before sending: a Main.immediate consumer may run inside trySend. */
internal class NativeInputEvents<T : Any>(private val merge: (T, T) -> Unit) {
    private val waiting = mutableMapOf<String, T>()

    fun offer(key: String?, value: T, send: (T) -> Boolean): Boolean {
        if (key != null) {
            waiting[key]?.let { merge(it, value); return true }
            waiting[key] = value
        }
        val accepted = send(value)
        if (!accepted && key != null && waiting[key] === value) waiting.remove(key)
        return accepted
    }

    fun consumed(value: T) { waiting.entries.removeAll { it.value === value } }
    fun clear() = waiting.clear()
}
