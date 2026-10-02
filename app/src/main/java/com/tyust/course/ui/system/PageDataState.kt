package com.tyust.course.ui.system

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel

/** Large, non-sensitive results stay in memory; small UI state uses saved state. */
class PageDataState : AutoCloseable {
    override fun close() { values.values.forEach { (it.value as? AutoCloseable)?.close() }; values.clear() }
    private val values = mutableMapOf<String, MutableState<*>>()
    val hasCachedContent: Boolean get() = values.values.any { (it.value as? Collection<*>)?.isNotEmpty() == true } ||
        values["content.available"]?.value == true

    @Suppress("UNCHECKED_CAST")
    fun <T> state(key: String, initial: () -> T): MutableState<T> {
        if (key.startsWith("academic.browser.")) {
            values.keys.filter { it.startsWith("academic.browser.") && it != key }.forEach { previous ->
                (values.remove(previous)?.value as? AutoCloseable)?.close()
            }
        }
        return values.getOrPut(key) { mutableStateOf(initial()) } as MutableState<T>
    }
}

@Composable
fun ReportPageContent(available: Boolean) {
    val store = LocalPageDataState.current
    androidx.compose.runtime.SideEffect { if (available) store?.state("content.available") { false }?.value = true }
}

class PageDataViewModel : ViewModel() {
    private val accounts = mutableMapOf<String, PageDataState>()
    private var activeAccount: String? = null

    override fun onCleared() { accounts.values.forEach { it.close() }; accounts.clear() }

    fun forAccount(key: String): PageDataState {
        // A page result is scoped to the currently active account. Retire the
        // previous account's in-memory store so late callbacks cannot mutate
        // a state object that can be reused after an account switch.
        val previous = activeAccount
        if (previous != null && previous != key) accounts.remove(previous)?.close()
        activeAccount = key
        return accounts.getOrPut(key) { PageDataState() }
    }
}

val LocalPageDataState = staticCompositionLocalOf<PageDataState?> { null }

@Composable
fun <T> rememberPageData(key: String, initial: () -> T): MutableState<T> {
    val store = LocalPageDataState.current
    return remember(store, key) { store?.state(key, initial) ?: mutableStateOf(initial()) }
}
