package com.tyust.course.academic.plugin

import android.content.Context
import com.tyust.course.academic.AcademicSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Protected protocol state runs outside reducers. Only schema-bound status reaches the page. */
internal object PluginServiceAuthentication {
    suspend fun execute(app: Context, pkg: PluginPackage, session: AcademicSession, input: JSONObject, active: () -> Boolean): JSONObject =
        executeWithHook(app, pkg, session, input, active) { method, args ->
            NativePluginRunner.invoke(app, pkg, session, method, args, JSONObject(), active = active)
        }

    /** Keep dispatch/lifecycle validation testable independently of the isolated Binder runtime. */
    internal suspend fun executeWithHook(app: Context, pkg: PluginPackage, session: AcademicSession, input: JSONObject,
        active: () -> Boolean, invokeHook: suspend (String, JSONObject) -> JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val action = input.getString("action")
        if (action !in setOf("start", "resume", "validate", "logout") || "auth" !in pkg.manifest.permissions)
            throw PluginException(PluginErrorCode.PERMISSION_DENIED, "服务认证处理器未授权")
        val serverId = input.getString("serverId")
        if (input.has("credential") && input.optString("scopeGrant").isBlank())
            throw PluginException(PluginErrorCode.PERMISSION_DENIED, "输入认证材料前需要授权所选学校网络")
        PluginServiceAccounts(app).server(pkg, serverId)
        if (session.key.schoolId != "service:${pkg.manifest.id}:$serverId" || !PluginServiceAccounts(app).current(pkg, session))
            throw PluginException(PluginErrorCode.STALE_CONTEXT, "请先选择独立服务账号")
        val epoch = session.epoch
        try {
            val args = JSONObject(input.toString()).apply { remove("action") }
            input.optString("scopeGrant").takeIf(String::isNotBlank)?.let { grant ->
                val operation = PluginOperation(session, pkg.manifest, "serviceAuth.$action", development = !pkg.official, packageDigest = pkg.digest, scopeStillActive = active)
                try {
                    if (PluginNetworkScopes.requireGrant(operation, grant).tenantId != input.getString("tenantId"))
                        throw PluginException(PluginErrorCode.PERMISSION_DENIED, "学校授权与认证学校不一致")
                } finally { operation.close() }
            }
            val result = invokeHook("serviceAuth.$action", args)
            val allowed = setOf("state", "continuationId", "scopes", "message", "tenantId", "schoolName", "expiresInSeconds", "officialUrl")
            if (result.keys().asSequence().any { it !in allowed } || result.optString("state") !in setOf("scope_required", "otp_required", "ready", "official_required", "signed_out"))
                throw PluginException(PluginErrorCode.VALIDATION_FAILED, "服务认证状态包含未允许的数据")
            result
        } finally {
            if (action == "logout") synchronized(PluginServiceAccounts.lock) {
                // Expire in-flight operations before clearing their private state. A late logout
                // from an older epoch must not clear a newer login or selected account.
                if (session.epoch == epoch && PluginServiceAccounts(app).current(pkg, session)) {
                    session.invalidate()
                    PluginNetworkScopes.revoke(session)
                    PluginHost.clearTemporaryState(session)
                    NativePluginVault(app, PluginStorageScope.session(session, pkg.manifest.id, !pkg.official)).clearEphemeral()
                }
            }
        }
    }
}
