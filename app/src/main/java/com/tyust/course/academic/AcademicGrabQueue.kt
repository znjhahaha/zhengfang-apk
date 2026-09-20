package com.tyust.course.academic

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

data class AcademicGrabRuntime(val running: Boolean = false, val success: Int = 0, val failed: Int = 0, val retries: Int = 0)

object AcademicGrabRuntimeStore {
    private val accounts = ConcurrentHashMap<String, AcademicGrabRuntime>()
    fun get(account: String): AcademicGrabRuntime = accounts[account] ?: AcademicGrabRuntime()
    fun update(account: String, state: AcademicGrabRuntime) { if (account.isNotBlank()) accounts[account] = state }
}

data class AcademicGrabItem(
    val accountStorageKey: String,
    val schoolId: String,
    val courseName: String,
    val teacher: String = "",
    val time: String = "",
    val stableCourseId: String = "",
    val stableSectionId: String = "",
    val scopeId: String = "",
    val enabled: Boolean = true,
    val useExactMatch: Boolean = stableSectionId.isNotBlank(),
    val sectionName: String = "",
    val sectionIdentityKnown: Boolean = false
) {
    // 没有教学班条件时沿用旧 key，保留已有队列状态。
    val key: String get() = listOf(schoolId, scopeId, stableCourseId, stableSectionId, courseName, teacher, time)
        .let { if (sectionName.isBlank()) it else it + sectionName }
        .joinToString("|") { "${it.length}:$it" }
}

class AcademicGrabQueueStore(private val context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("academic_grab_queue", Context.MODE_PRIVATE)
    fun items(accountStorageKey: String): List<AcademicGrabItem> = synchronized(lock) { load(accountStorageKey) }
    fun replace(accountStorageKey: String, items: List<AcademicGrabItem>) = synchronized(lock) { save(accountStorageKey, items) }
    fun add(item: AcademicGrabItem): Boolean = synchronized(lock) {
        val current = items(item.accountStorageKey).toMutableList()
        if (current.any { existing -> existing.schoolId == item.schoolId && existing.scopeId == item.scopeId &&
            if (item.stableSectionId.isNotBlank()) existing.stableSectionId == item.stableSectionId && existing.stableCourseId == item.stableCourseId
            else existing.stableSectionId.isBlank() && existing.courseName == item.courseName && existing.teacher == item.teacher &&
                existing.time == item.time && existing.sectionName == item.sectionName
        }) return@synchronized false
        current += item; replace(item.accountStorageKey, current); true
    }

    fun remove(item: AcademicGrabItem) = synchronized(lock) { replace(item.accountStorageKey, items(item.accountStorageKey) - item) }

    fun target(account: String): AcademicGrabItem? = synchronized(lock) {
        runCatching { prefs.getString("target:$account", null)?.let { fromJson(account, JSONObject(it)) } }.getOrNull()
    }
    fun setTarget(account: String, item: AcademicGrabItem?) = synchronized(lock) {
        require(item == null || item.accountStorageKey == account)
        prefs.edit().apply { if (item == null) remove("target:$account") else putString("target:$account", toJson(item).toString()) }.apply()
    }
    fun statuses(account: String): Map<String, String> = synchronized(lock) {
        val json = runCatching { JSONObject(prefs.getString("statuses:$account", "{}").orEmpty()) }.getOrDefault(JSONObject())
        json.keys().asSequence().associateWith { json.optString(it, "WAITING") }
    }
    fun resetStatuses(account: String, items: List<AcademicGrabItem>) = synchronized(lock) {
        prefs.edit().putString("statuses:$account", JSONObject(items.associate { it.key to "WAITING" }).toString()).apply()
    }
    fun setStatus(account: String, item: AcademicGrabItem, status: String) = synchronized(lock) {
        prefs.edit().putString("statuses:$account", JSONObject(statuses(account) + (item.key to status)).toString()).apply()
    }

    private fun load(key: String): List<AcademicGrabItem> {
        val array = runCatching { JSONArray(prefs.getString(key, "[]")) }.getOrDefault(JSONArray())
        return (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let { json ->
            fromJson(key, json)
        } }
    }

    private fun save(key: String, items: List<AcademicGrabItem>) {
        require(items.all { it.accountStorageKey == key })
        val array = JSONArray(); items.forEach { item -> array.put(toJson(item)) }
        prefs.edit().putString(key, array.toString()).apply()
    }
    private fun fromJson(account: String, json: JSONObject) = AcademicGrabItem(account, json.optString("schoolId"), json.optString("courseName"),
        json.optString("teacher"), json.optString("time"), json.optString("stableCourseId"), json.optString("stableSectionId"),
        json.optString("scopeId"), json.optBoolean("enabled", true), json.optBoolean("useExactMatch", json.optString("stableSectionId").isNotBlank()),
        json.optString("sectionName"), json.optBoolean("sectionIdentityKnown", false))
    private fun toJson(item: AcademicGrabItem) = JSONObject().apply {
        put("schoolId", item.schoolId); put("courseName", item.courseName); put("teacher", item.teacher); put("time", item.time)
        put("stableCourseId", item.stableCourseId); put("stableSectionId", item.stableSectionId); put("scopeId", item.scopeId)
        put("enabled", item.enabled); put("useExactMatch", item.useExactMatch); put("sectionName", item.sectionName)
        put("sectionIdentityKnown", item.sectionIdentityKnown)
    }
    companion object { private val lock = Any() }
}
