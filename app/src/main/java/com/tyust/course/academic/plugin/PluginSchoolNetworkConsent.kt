package com.tyust.course.academic.plugin

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/** Durable scope consent, never durable runtime authority or authentication material. */
internal class PluginSchoolNetworkConsent(app: Context, private val pkg: PluginPackage) {
    private val prefs = preferences(app)
    private val plugin = hash(pkg.manifest.id)
    private val generation = synchronized(lock) { prefs.getLong("generation:$plugin", 0L) }
    private val accounts = PluginServiceAccounts(app)

    suspend fun authorize(operation: PluginOperation, serverId: String, tenantId: String, rules: JSONArray,
        active: () -> Unit, confirm: suspend (String) -> Unit): String {
        val context = currentCoroutineContext()
        fun requireCurrent() {
            if (prefs.getLong("generation:$plugin", 0L) != generation)
                throw PluginException(PluginErrorCode.PERMISSION_DENIED, "学校网络授权已撤销，请重新打开插件")
        }
        fun guard() {
            context.ensureActive()
            active()
            operation.requireActive()
            requireCurrent()
        }
        guard()
        if (operation.manifest.id != pkg.manifest.id || operation.packageDigest != pkg.digest ||
            operation.development != !pkg.official ||
            operation.session.key.schoolId != "service:${pkg.manifest.id}:$serverId")
            throw PluginException(PluginErrorCode.PERMISSION_DENIED, "此插件或账号不能授权学校网络")
        accounts.server(pkg, serverId)
        val snapshot = PluginNetworkScopes.validateAuthorization(operation, tenantId, rules)
        val desired = snapshot.map { Rule.fromRuntime(it) }
        val owner = hash(PluginJson.canonical(JSONObject().put("plugin", pkg.manifest.id).put("digest", pkg.digest)
            .put("server", serverId).put("account", operation.session.key.accountKey).put("tenant", tenantId)
            .put("development", !pkg.official)))
        val record = Record(plugin, account(serverId, operation.session.key.accountKey), owner, desired)
        val scopeKey = hash(PluginJson.canonical(JSONArray(desired.map(Rule::json))))
        val coordinationKey = "school-network:$owner:$generation:${operation.session.instanceId}:${operation.epoch}:$scopeKey"
        var recorded = false

        fun covered(records: List<Record>) = records.any { it.plugin == plugin && it.owner == owner && covers(it.rules, desired) }
        fun issue(remember: Boolean): String = synchronized(PluginServiceAccounts.lock) {
            // Account switching takes the registry lock first; invalidation takes the session
            // lock. Never hold any of these locks while waiting for the visible prompt.
            synchronized(lock) {
                synchronized(operation.session) {
                    guard()
                    val records = read(prefs)
                    val approved = covered(records)
                    if (!remember && !approved) throw PluginException(PluginErrorCode.PERMISSION_DENIED, "学校网络范围尚未授权")
                    val previous = prefs.getString("approvals", null)
                    var wrote = false
                    val grant = PluginNetworkScopes.authorize(operation, tenantId, snapshot)
                    try {
                        guard()
                        if (remember && !approved) {
                            wrote = true
                            save(prefs, records + record)
                            recorded = true
                        }
                        guard()
                        grant
                    } catch (error: Throwable) {
                        try {
                            // A concurrent/reentrant explicit revocation must never be undone.
                            if (wrote && prefs.getLong("generation:$plugin", 0L) == generation) {
                                val edit = prefs.edit()
                                if (previous == null) edit.remove("approvals") else edit.putString("approvals", previous)
                                check(edit.commit())
                                recorded = false
                            }
                        } finally { PluginNetworkScopes.revokeGrant(operation, grant) }
                        throw error
                    }
                }
            }
        }

        var issued: String? = null
        try {
            PluginExecutionBudget.userInput {
                PluginConsentCoordinator.request(coordinationKey) {
                    guard()
                    // No parent/account callbacks under this lock alone: revocation takes
                    // the account-registry lock before this one.
                    val remembered = synchronized(lock) { context.ensureActive(); requireCurrent(); covered(read(prefs)) }
                    guard()
                    if (!remembered) {
                        guard()
                        confirm(message(tenantId, snapshot))
                        guard()
                    }
                    issued = issue(remember = !remembered)
                    // Followers share the consent decision, never the leader's grant.
                    JSONObject()
                }
            }
            guard()
            return issued ?: issue(remember = false)
        } catch (error: Throwable) {
            try {
                if (recorded) synchronized(PluginServiceAccounts.lock) {
                    synchronized(lock) {
                        if (prefs.getLong("generation:$plugin", 0L) == generation)
                            save(prefs, read(prefs).filter { it != record })
                    }
                }
            } finally { issued?.let { PluginNetworkScopes.revokeGrant(operation, it) } }
            throw error
        }
    }

    private fun message(tenantId: String, rules: List<JSONObject>): String =
        "学校：$tenantId\n" + rules.joinToString("\n\n") { rule ->
            buildString {
                append(rule.getString("origin")); append(rule.getString("pathPrefix")); append('\n')
                append(PluginJson.strings(rule.getJSONArray("methods")).joinToString("、")); append(" · ")
                append(PluginJson.strings(rule.getJSONArray("purposes")).joinToString("、") { purpose ->
                    when (purpose) { "auth" -> "登录认证"; "query" -> "读取任务与要求"; else -> "提交业务数据" }
                })
                for ((field, title) in listOf("requiredQuery" to "查询约束", "requiredForm" to "表单约束",
                    "authHeader" to "认证请求头", "userAgent" to "User-Agent")) {
                    if (rule.has(field)) { append('\n'); append(title); append("："); append(PluginJson.canonical(rule.get(field))) }
                }
            }
        } + "\n\n允许后记住此插件、安装包、服务账号和学校的这些网络范围，关闭页面或重启 App 后可复用。" +
            "提交业务数据仍需确认。可在插件详情的“权限与数据”中选择“撤销学校网络授权”。"

    private data class Rule(val origin: String, val path: String, val methods: Set<String>, val purposes: Set<String>,
        val constraints: String, val authHeader: Boolean, val userAgent: String?) {
        fun json() = JSONObject().put("origin", origin).put("pathPrefix", path)
            .put("methods", JSONArray(methods.sorted())).put("purposes", JSONArray(purposes.sorted()))
            .put("constraints", constraints).put("authHeader", authHeader).put("userAgent", userAgent ?: JSONObject.NULL)
        fun covers(other: Rule) = origin == other.origin && under(other.path, path) &&
            methods.containsAll(other.methods) && purposes.containsAll(other.purposes) &&
            constraints == other.constraints && authHeader == other.authHeader && userAgent == other.userAgent
        fun overrides(other: Rule) = origin == other.origin && (under(path, other.path) || under(other.path, path)) &&
            methods.any { it in other.methods } && purposes.any { it in other.purposes } &&
            (authHeader != other.authHeader || userAgent != other.userAgent)

        companion object {
            fun fromRuntime(value: JSONObject) = Rule(value.getString("origin"), value.getString("pathPrefix"),
                PluginJson.strings(value.getJSONArray("methods")).toSet(), PluginJson.strings(value.getJSONArray("purposes")).toSet(),
                hash(PluginJson.canonical(JSONObject().put("query", value.optJSONObject("requiredQuery") ?: JSONObject())
                    .put("form", value.optJSONObject("requiredForm") ?: JSONObject()))),
                value.has("authHeader"), if (value.has("userAgent")) hash(value.getString("userAgent")) else null)

            fun fromStored(value: JSONObject): Rule {
                check(value.keys().asSequence().toSet() == setOf("origin", "pathPrefix", "methods", "purposes", "constraints", "authHeader", "userAgent"))
                check(value.opt("authHeader") is Boolean && value.opt("constraints") is String && digest(value.getString("constraints")))
                check(value.isNull("userAgent") || value.opt("userAgent") is String)
                val agent = if (value.isNull("userAgent")) null else value.getString("userAgent").also { check(digest(it)) }
                val base = JSONObject().put("origin", value.get("origin")).put("pathPrefix", value.get("pathPrefix"))
                    .put("methods", value.get("methods")).put("purposes", value.get("purposes"))
                if (value.getBoolean("authHeader")) base.put("authHeader", "X-Token")
                // Reuse strict scope syntax on cold reads; constraints/UA are fingerprints only.
                PluginNetworkScopes.validateRule(base)
                return Rule(base.getString("origin"), base.getString("pathPrefix"),
                    PluginJson.strings(base.getJSONArray("methods")).toSet(), PluginJson.strings(base.getJSONArray("purposes")).toSet(),
                    value.getString("constraints"), value.getBoolean("authHeader"), agent)
            }
        }
    }

    private data class Record(val plugin: String, val account: String, val owner: String, val rules: List<Rule>) {
        fun json() = JSONObject().put("plugin", plugin).put("account", account).put("owner", owner).put("rules", JSONArray(rules.map(Rule::json)))
        companion object {
            fun fromStored(value: JSONObject): Record {
                check(value.keys().asSequence().toSet() == setOf("plugin", "account", "owner", "rules"))
                for (field in listOf("plugin", "account", "owner")) check(value.opt(field) is String && digest(value.getString(field)))
                val rules = value.getJSONArray("rules")
                check(rules.length() in 1..32)
                return Record(value.getString("plugin"), value.getString("account"), value.getString("owner"),
                    (0 until rules.length()).map { Rule.fromStored(rules.getJSONObject(it)) })
            }
        }
    }

    companion object {
        private const val MAX_APPROVALS = 128
        private const val MAX_BYTES = 256 * 1024
        private val lock = Any()
        private fun preferences(app: Context) = app.getSharedPreferences("plugin-school-network-consent", Context.MODE_PRIVATE)
        private fun hash(value: String) = PluginJson.sha256(value.toByteArray(Charsets.UTF_8))
        private fun digest(value: String) = value.matches(Regex("[a-f0-9]{64}"))
        private fun account(serverId: String, accountKey: String) = hash(PluginJson.canonical(JSONObject().put("server", serverId).put("account", accountKey)))
        private fun under(path: String, prefix: String) = path == prefix || path.startsWith(prefix.trimEnd('/') + "/")
        private fun covers(approved: List<Rule>, requested: List<Rule>): Boolean {
            if (approved == requested) return true
            // Removing a more specific no-header rule can broaden token access. Likewise,
            // reordering conflicting User-Agents changes the policy's tie-breaking behavior.
            return requested.all { wanted -> approved.any { it.covers(wanted) } && approved.none { it.overrides(wanted) } }
        }
        private fun read(prefs: android.content.SharedPreferences): List<Record> = runCatching {
            val raw = prefs.getString("approvals", "[]")!!
            check(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            PluginJson.requireValid(raw)
            val rows = JSONArray(raw)
            check(rows.length() <= MAX_APPROVALS)
            (0 until rows.length()).map { Record.fromStored(rows.getJSONObject(it)) }
        }.getOrDefault(emptyList())

        private fun save(prefs: android.content.SharedPreferences, records: List<Record>) {
            val retained = records.takeLast(MAX_APPROVALS).toMutableList()
            var json = JSONArray(retained.map(Record::json)).toString()
            while (json.toByteArray(Charsets.UTF_8).size > MAX_BYTES && retained.size > 1) {
                retained.removeAt(0)
                json = JSONArray(retained.map(Record::json)).toString()
            }
            if (json.toByteArray(Charsets.UTF_8).size > MAX_BYTES)
                throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "学校网络授权记录超过大小限制")
            check(prefs.edit().putString("approvals", json).commit())
        }

        fun remembered(app: Context, pluginId: String): Boolean = synchronized(lock) {
            read(preferences(app)).any { it.plugin == hash(pluginId) }
        }

        fun revoke(app: Context, pluginId: String) = synchronized(PluginServiceAccounts.lock) {
            synchronized(lock) {
                val prefs = preferences(app); val plugin = hash(pluginId)
                val remaining = read(prefs).filter { it.plugin != plugin }
                try {
                    check(prefs.edit().putString("approvals", JSONArray(remaining.map(Record::json)).toString())
                        .putLong("generation:$plugin", prefs.getLong("generation:$plugin", 0L) + 1L).commit())
                } finally { PluginNetworkScopes.revokePlugin(pluginId) }
            }
        }

        fun removeAccount(app: Context, pluginId: String, serverId: String, accountKey: String) = synchronized(PluginServiceAccounts.lock) {
            synchronized(lock) {
                val prefs = preferences(app); val plugin = hash(pluginId); val account = account(serverId, accountKey)
                save(prefs, read(prefs).filter { it.plugin != plugin || it.account != account })
            }
        }
    }
}
