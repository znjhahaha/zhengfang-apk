package com.tyust.course.academic.plugin

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.crypto.SecretKey

/** Stable, account-scoped pages. Payloads use the credential vault's AEAD key, not plugin state. */
internal class NativePluginRecords(app: Context, namespace: String, keyProvider: (() -> SecretKey)? = null, private val guard: () -> Unit = {}) {
    private val hash = PluginStorageScope.hash(namespace)
    private val path = File(app.noBackupFilesDir, "native-plugin-records/$hash.db")
    private val vault = NativePluginVault(app, namespace, keyProvider = keyProvider, guard = guard)
    private fun <T> access(block: (SQLiteDatabase) -> T): T = synchronized(PluginServiceAccounts.lock) {
        guard()
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("CREATE TABLE IF NOT EXISTS records (seq INTEGER PRIMARY KEY AUTOINCREMENT, collection TEXT NOT NULL, record_key TEXT NOT NULL, at INTEGER NOT NULL, payload BLOB NOT NULL, UNIQUE(collection,record_key))")
            block(db)
        }
    }
    private fun id(value: String): String = value.also {
        if (!it.matches(Regex("[a-zA-Z0-9_.:-]{1,128}"))) throw PluginException(PluginErrorCode.VALIDATION_FAILED, "记录标识无效")
    }
    fun upsert(input: JSONObject): JSONObject = access { db ->
        val collection = id(input.getString("collection"))
        val items = PluginJson.objects(input.getJSONArray("items"))
        if (items.size !in 1..100) throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "每批最多保存 100 条记录")
        db.beginTransaction()
        var written = 0
        try {
            for (item in items) {
                val key = id(item.getString("key"))
                val value = JSONArray().put(item.get("value")).toString().drop(1).dropLast(1).toByteArray()
                if (value.size > 8192) throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "单条记录不能超过 8 KiB")
                val values = ContentValues().apply {
                    put("collection", collection); put("record_key", key); put("at", System.currentTimeMillis())
                    put("payload", vault.sealRecord("$collection/$key", value))
                }
                if (input.optBoolean("ifAbsent")) {
                    if (db.insertWithOnConflict("records", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) written++
                } else {
                    if (db.update("records", values, "collection=? AND record_key=?", arrayOf(collection, key)) == 0) db.insertOrThrow("records", null, values)
                    written++
                }
            }
            db.rawQuery("SELECT COALESCE(SUM(length(payload)),0) FROM records", null).use {
                it.moveToFirst()
                if (it.getLong(0) > 32L * 1024 * 1024) throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "本地记录达到 32 MiB 上限，请导出或清理插件数据")
            }
            guard(); db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        JSONObject().put("written", written)
    }
    fun query(input: JSONObject): JSONObject = access { db ->
        val collection = id(input.getString("collection"))
        val limit = input.optInt("limit", 50).coerceIn(1, 100)
        val keys = input.optJSONArray("keys")?.let(PluginJson::strings)?.map(::id)
        if (keys != null && input.optString("cursor").isNotEmpty()) throw PluginException(PluginErrorCode.VALIDATION_FAILED, "按标识查询不能同时传入分页游标")
        if (keys != null && keys.isEmpty()) return@access JSONObject().put("items", JSONArray()).put("nextCursor", "").put("total", 0)
        if ((keys?.size ?: 0) > 100) throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "最多查询 100 个记录标识")
        val snapshot = db.rawQuery("SELECT COALESCE(MAX(seq),0) FROM records", null).use { it.moveToFirst(); it.getLong(0) }
        var maximum = snapshot; var before = Long.MAX_VALUE
        val token = input.optString("cursor")
        if (token.isNotEmpty()) {
            try {
                val cursor = JSONObject(String(Base64.decode(token, Base64.URL_SAFE or Base64.NO_WRAP)))
                require(cursor.getString("scope") == hash && cursor.getString("collection") == collection && keys == null)
                maximum = cursor.getLong("maximum"); before = cursor.getLong("before")
                require(maximum >= 0 && before > 0)
            } catch (_: Exception) { throw PluginException(PluginErrorCode.VALIDATION_FAILED, "记录分页游标无效") }
        }
        val filter = "collection=? AND seq<=?" + if (keys == null) "" else " AND record_key IN (${keys.joinToString(",") { "?" }})"
        val args = arrayOf(collection, maximum.toString(), *(keys?.toTypedArray() ?: emptyArray()))
        val total = db.rawQuery("SELECT COUNT(*) FROM records WHERE $filter", args).use { it.moveToFirst(); it.getInt(0) }
        val items = JSONArray(); var last = before; var more = false
        db.rawQuery("SELECT seq,record_key,at,payload FROM records WHERE $filter AND seq<? ORDER BY seq DESC LIMIT ?", args + arrayOf(before.toString(), (limit + 1).toString())).use { rows ->
            while (rows.moveToNext()) {
                if (items.length() == limit) { more = true; break }
                last = rows.getLong(0); val key = rows.getString(1)
                val value = org.json.JSONTokener(String(vault.openRecord("$collection/$key", rows.getBlob(3)))).nextValue()
                items.put(JSONObject().put("key", key).put("value", value).put("at", rows.getLong(2)))
            }
        }
        val cursor = if (more && keys == null) Base64.encodeToString(JSONObject().put("scope", hash).put("collection", collection).put("maximum", maximum).put("before", last).toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP) else ""
        JSONObject().put("items", items).put("nextCursor", cursor).put("total", total)
    }
    companion object {
        fun clear(app: Context, namespace: String) = synchronized(PluginServiceAccounts.lock) {
            SQLiteDatabase.deleteDatabase(File(app.noBackupFilesDir, "native-plugin-records/${PluginStorageScope.hash(namespace)}.db")); Unit
        }
    }
}
