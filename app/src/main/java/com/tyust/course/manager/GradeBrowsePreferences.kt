package com.tyust.course.manager

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.security.MessageDigest

/** UI preferences only. Session handles and grade results never enter this store. */
data class GradeBrowseSelection(val termId: String = "", val termLabel: String = "", val tab: Int = 0)

data class GradeBrowseScope(val account: String, val school: String, val provider: String, val termFormat: String) {
    val key: String get() = accountPrefix(account) + digest(listOf(school, provider, termFormat))
    companion object {
        private fun digest(parts: List<String>): String = MessageDigest.getInstance("SHA-256")
            .digest(parts.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        internal fun accountPrefix(account: String) = digest(listOf(account)) + ":"
    }
}

class GradeBrowsePreferences(private val preferences: SharedPreferences) {
    fun read(scope: GradeBrowseScope): GradeBrowseSelection = runCatching {
        val json = JSONObject(preferences.getString(scope.key, "{}").orEmpty())
        GradeBrowseSelection(json.optString("termId"), json.optString("termLabel"), json.optInt("tab").coerceIn(0, 2))
    }.getOrDefault(GradeBrowseSelection())

    fun write(scope: GradeBrowseScope, selection: GradeBrowseSelection) {
        preferences.edit().putString(scope.key, JSONObject().put("termId", selection.termId)
            .put("termLabel", selection.termLabel).put("tab", selection.tab.coerceIn(0, 2)).toString()).apply()
    }

    fun removeAccount(account: String) {
        val prefix = GradeBrowseScope.accountPrefix(account)
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith(prefix) }.forEach(editor::remove)
        editor.apply()
    }

    companion object {
        @JvmStatic fun from(context: Context) = GradeBrowsePreferences(
            context.getSharedPreferences("grade_browse_preferences", Context.MODE_PRIVATE))
    }
}
