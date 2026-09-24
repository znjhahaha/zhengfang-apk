package com.tyust.course.academic.plugin

import com.tyust.course.academic.AcademicSession

/** Host-selected identities are stable across package updates, isolated across origins and trust. */
object PluginStorageScope {
    fun base(school: String, account: String, plugin: String, development: Boolean) =
        listOf(school, account, plugin, development.toString()).joinToString("\u0000")
    fun session(session: AcademicSession, plugin: String, development: Boolean): String =
        base(session.key.schoolId, session.key.accountKey, plugin, development) +
            if (session.key.schoolId.startsWith("service:")) "\u0000" + session.baseUrl.trimEnd('/') else ""
    fun hash(value: String) = PluginJson.sha256(value.toByteArray())
    fun setting(plugin: String, id: String, scope: String, identity: String, development: Boolean) =
        "$plugin/$id/" + hash(listOf(scope, identity, development.toString()).joinToString("\u0000"))
}
