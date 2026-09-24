package com.tyust.course.academic.plugin

import android.content.Context
import com.tyust.course.academic.AcademicSession
import com.tyust.course.academic.AcademicSessionKey
import com.tyust.course.manager.UserManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Resolves the provider once; all workflow callbacks keep that immutable package. */
class PluginServices(
    private val app: Context,
    private val caller: PluginPackage,
    private val callerSession: AcademicSession,
    private val interaction: NativePluginInteraction?,
    private val active: () -> Boolean
) {
    private val accounts = PluginServiceAccounts(app)
    private val journal = PluginWorkflowJournal(PluginWorkflowFiles(app))
    private val prefs = app.getSharedPreferences("plugin-service-choices", Context.MODE_PRIVATE)
    private val scope = listOf(callerSession.key.schoolId, callerSession.key.accountKey,
        UserManager.getInstance().currentAccountStorageKey, !caller.official).joinToString("\u0000")

    private fun available(): List<PluginPackage> {
        val school = UserManager.getInstance().currentSchool
        return AcademicProviderRegistry.knownPackages().filter(PluginPages::available)
    }

    private suspend fun resolve(input: JSONObject, flow: NativeFlow): PluginService {
        val name = input.getString("name")
        val version = input.getInt("version")
        val matches = PluginServiceDirectory.discover(caller.manifest, available(), name, version)
        if (matches.isEmpty()) throw PluginException(PluginErrorCode.UNSUPPORTED, "需要安装提供 $name v$version 的插件")
        val key = PluginJson.sha256("${caller.manifest.id}\u0000$scope\u0000$name\u0000$version".toByteArray())
        val saved = prefs.getString(key, null)
        val requested = input.optString("providerId").takeIf { it.isNotBlank() }
        val previous = matches.firstOrNull { it.pkg.manifest.id == saved && (requested == null || requested == saved) }
        val selected = previous ?: if (matches.size == 1 && (requested == null || requested == matches[0].pkg.manifest.id)) matches[0] else {
            if (!flow.userGesture || interaction == null) throw PluginException(PluginErrorCode.PERMISSION_DENIED, "请先选择此功能使用的服务提供方")
            val id = withContext(Dispatchers.Main) { interaction.choose("选择服务提供方", matches.map { it.pkg.manifest.id to "${it.pkg.manifest.name} · ${it.contract.getString("title")}" }) }
                ?: throw PluginException(PluginErrorCode.CANCELLED, "已取消服务选择")
            matches.firstOrNull { it.pkg.manifest.id == id } ?: throw PluginException(PluginErrorCode.VALIDATION_FAILED, "服务提供方不可用")
        }
        requireActive()
        check(prefs.edit().putString(key, selected.pkg.manifest.id).commit())
        PluginServiceDirectory.validateInput(selected, input.get("input"))
        return selected
    }

    private fun providerSession(service: PluginService): AcademicSession = service.contract.optString("serverId").takeIf { it.isNotBlank() }
        ?.let { accounts.session(service.pkg, it) }
        ?: AcademicSession(AcademicSessionKey("plugin:${service.pkg.manifest.id}", "default"), "https://invalid.example/")

    private fun requireActive() {
        if (!active() || callerSession.retired) throw PluginException(PluginErrorCode.STALE_CONTEXT, "账号或插件上下文已改变")
    }

    suspend fun execute(name: String, input: JSONObject, flow: NativeFlow): Any = withContext(Dispatchers.IO) {
        requireActive()
        when (name) {
            "services.discover" -> JSONArray(PluginServiceDirectory.discover(caller.manifest, available(), input.getString("name"), input.getInt("version")).map { it.identity() })
            "services.call", "workflow.prepare" -> {
                val service = resolve(input, flow)
                val writes = service.contract.getString("kind") == "write"
                if ((name == "workflow.prepare") != writes) throw PluginException(PluginErrorCode.PERMISSION_DENIED,
                    if (writes) "写入服务必须通过工作流预览和确认" else "只读服务使用普通服务调用")
                val session = providerSession(service)
                val serverId = service.contract.optString("serverId")
                val providerAccount = session.key.accountKey
                val valid = { active() && AcademicProviderRegistry.isEnabled(service.pkg.manifest.id) &&
                    AcademicProviderRegistry.isCurrentPackage(service.pkg.manifest.id, service.pkg.digest) &&
                    accounts.current(service.pkg, session) && (serverId.isBlank() || accounts.selected(service.pkg.manifest.id, serverId) == providerAccount) }
                try {
                    val args = JSONObject(input.toString()).apply { remove("providerId") }
                    val result = NativePluginRunner.invoke(app, service.pkg, session,
                        if (writes) "workflow.prepare" else "services.invoke", args, JSONObject(), active = valid)
                    requireActive()
                    if (!valid()) throw PluginException(PluginErrorCode.STALE_CONTEXT, "服务提供方或账号已改变")
                    if (!writes) {
                        PluginServiceDirectory.validateOutput(service, result.get("value"))
                        JSONObject().put("value", result.get("value")).put("providerId", service.pkg.manifest.id).put("digest", service.pkg.digest)
                    } else {
                        val identity = JSONObject().put("callerId", caller.manifest.id).put("callerDigest", caller.digest).put("scope", scope)
                            .put("providerId", service.pkg.manifest.id).put("providerDigest", service.pkg.digest)
                            .put("name", service.contract.getString("name")).put("version", service.contract.getInt("version"))
                            .put("serverId", serverId).put("providerAccount", providerAccount)
                            .put("providerSchool", session.key.schoolId).put("baseUrl", session.baseUrl)
                        journal.prepare(identity, input.get("input"), result)
                    }
                } finally { if (valid()) PluginSessionCookies.save(app, service.pkg, session); session.retire() }
            }
            "workflow.list" -> journal.list(caller.manifest.id, scope)
            else -> {
                val id = input.getString("workflowId")
                val record = journal.owned(id, caller.manifest.id, scope)
                if (name == "workflow.cancel") {
                    // The durable cancellation takes precedence over optional provider cleanup.
                    val result = journal.cancel(id)
                    runCatching { invoke(record, "workflow.cancel", null) }
                    result
                } else {
                    if (name == "workflow.step" && !record.getBoolean("approved")) {
                        if (!flow.userGesture || interaction == null) throw PluginException(PluginErrorCode.PERMISSION_DENIED, "请先确认提交范围")
                        val preview = record.getString("summary") + "\n\n" + PluginJson.objects(record.getJSONArray("steps")).joinToString("\n") { "• ${it.getString("label")}" }
                        if (!withContext(Dispatchers.Main) { interaction.confirm(record.getString("title"), preview) })
                            throw PluginException(PluginErrorCode.CANCELLED, "已取消确认")
                        requireActive()
                        journal.approve(id)
                    }
                    when (name) {
                        "workflow.step" -> journal.run(id, active) { item, step -> invoke(item, "workflow.step", step) }
                        "workflow.reconcile" -> journal.reconcile(id, active) { item, step -> invoke(item, "workflow.reconcile", step) }
                        else -> throw PluginException(PluginErrorCode.UNSUPPORTED, "未知工作流操作")
                    }
                }
            }
        }
    }

    private suspend fun invoke(record: JSONObject, method: String, step: JSONObject?): JSONObject {
        requireActive()
        if (record.getString("callerDigest") != caller.digest) throw PluginException(PluginErrorCode.STALE_CONTEXT, "此工作流需要原调用方版本，请在插件管理中恢复该版本")
        val pkg = AcademicProviderRegistry.packages().readDigest(record.getString("providerDigest"))
        if (!AcademicProviderRegistry.isEnabled(pkg.manifest.id)) throw PluginException(PluginErrorCode.PERMISSION_DENIED, "服务提供方已停用")
        val serverId = record.getString("serverId")
        val providerAccount = record.getString("providerAccount")
        if (serverId.isNotBlank() && accounts.selected(pkg.manifest.id, serverId) != providerAccount)
            throw PluginException(PluginErrorCode.STALE_CONTEXT, "请恢复此工作流使用的服务账号")
        val session = if (serverId.isNotBlank()) accounts.session(pkg, serverId)
            else AcademicSession(AcademicSessionKey(record.getString("providerSchool"), providerAccount), record.getString("baseUrl"))
        fun valid() = active() && PluginPages.available(pkg) && accounts.current(pkg, session) &&
            session.baseUrl == record.getString("baseUrl") && session.key.accountKey == providerAccount
        if (!valid()) { session.retire(); throw PluginException(PluginErrorCode.STALE_CONTEXT, "工作流使用的服务或账号已改变") }
        return try {
            val args = JSONObject().put("workflowId", record.getString("workflowId")).put("name", record.getString("name")).put("version", record.getInt("version"))
            if (method != "workflow.cancel") args.put("input", record.get("input")).put("step", step)
            val result = NativePluginRunner.invoke(app, pkg, session, method, args, JSONObject(), confirmed = method == "workflow.step", active = ::valid)
            if (method != "workflow.cancel") {
                val contract = pkg.manifest.json.getJSONArray("services").let(PluginJson::objects).singleOrNull {
                    it.getString("name") == record.getString("name") && it.getInt("version") == record.getInt("version") && it.getString("kind") == "write"
                } ?: throw PluginException(PluginErrorCode.VALIDATION_FAILED, "固定的工作流服务契约不存在")
                PluginServiceDirectory.validateReceipt(PluginService(pkg, contract), result)
            }
            result
        } finally { if (valid()) PluginSessionCookies.save(app, pkg, session); session.retire() }
    }
}
