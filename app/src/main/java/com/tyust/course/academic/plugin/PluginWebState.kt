package com.tyust.course.academic.plugin

import android.os.Bundle
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel

/** Keeps history in memory, never a WebView/Activity or POST body in persistent preferences. */
internal class PluginWebState {
    var history: Bundle? = null
    var scrollX = 0
    var scrollY = 0
    @Volatile var canReplay = true
    var firstContent by mutableStateOf(false)
    var loading by mutableStateOf(true)
    var problem by mutableStateOf("")
    var externalUrl by mutableStateOf<String?>(null)
    var back by mutableStateOf(false)
    var forward by mutableStateOf(false)
    var url by mutableStateOf("")
    fun navigation(url: String, method: String = "GET") {
        this.url = url; loading = true; problem = ""; externalUrl = null
        canReplay = method.equals("GET", true)
    }
    fun fail(message: String) { problem = message; loading = false; firstContent = true }
    fun ready() { loading = false; firstContent = true }
}
internal class PluginWebRetainer : ViewModel() {
    private val states = linkedMapOf<String, PluginWebState>()
    fun obtain(key: String): PluginWebState = states.getOrPut(key) {
        if (states.size >= 8) states.remove(states.keys.first())
        PluginWebState()
    }
    fun remove(key: String) { states.remove(key) }
    override fun onCleared() { states.clear() }
}
