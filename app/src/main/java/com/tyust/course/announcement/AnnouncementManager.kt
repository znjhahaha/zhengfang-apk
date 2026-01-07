package com.tyust.course.announcement

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 公告管理器（支持多条公告）
 * 从 Gitee 获取公告列表并逐条显示
 */
object AnnouncementManager {
    private const val TAG = "AnnouncementManager"
    private const val PREFS_NAME = "announcement_prefs"
    private const val KEY_READ_IDS = "read_announcement_ids"
    
    // 公告 JSON 地址（Gitee Raw）
    private const val ANNOUNCEMENT_URL = "https://gitee.com/znj12345/zhengfang/raw/main/announcement.json"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    /**
     * 公告数据类
     */
    data class Announcement(
        val id: String,
        val title: String,
        val content: String,
        val type: String,  // info, warning, important
        val showOnce: Boolean
    )
    
    /**
     * 获取所有未读公告
     * 返回未读公告列表
     */
    suspend fun fetchUnreadAnnouncements(context: Context): List<Announcement> {
        val allAnnouncements = fetchAllAnnouncements()
        // 过滤出未读公告
        return allAnnouncements.filter { shouldShow(context, it) }
    }

    /**
     * 获取所有公告（包括已读和未读）
     */
    suspend fun fetchAllAnnouncements(): List<Announcement> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(ANNOUNCEMENT_URL)
                    .header("Cache-Control", "no-cache")
                    .build()
                
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.e(TAG, "获取公告失败: HTTP ${response.code}")
                    return@withContext emptyList()
                }
                
                val json = response.body?.string() ?: return@withContext emptyList()
                parseAnnouncements(json)
            } catch (e: Exception) {
                Log.e(TAG, "获取公告失败: ${e.message}")
                emptyList()
            }
        }
    }
    
    /**
     * 获取第一条未读公告（兼容旧接口）
     */
    suspend fun fetchAnnouncement(context: Context): Announcement? {
        return fetchUnreadAnnouncements(context).firstOrNull()
    }
    
    /**
     * 解析公告 JSON（支持单条和多条格式）
     */
    private fun parseAnnouncements(json: String): List<Announcement> {
        return try {
            val trimmedJson = json.trim()
            when {
                // 新格式：包含 announcements 数组
                trimmedJson.startsWith("{") && trimmedJson.contains("\"announcements\"") -> {
                    val obj = JSONObject(trimmedJson)
                    val arr = obj.optJSONArray("announcements") ?: return emptyList()
                    parseAnnouncementArray(arr)
                }
                // 数组格式
                trimmedJson.startsWith("[") -> {
                    val arr = JSONArray(trimmedJson)
                    parseAnnouncementArray(arr)
                }
                // 单条公告格式（兼容旧格式）
                trimmedJson.startsWith("{") -> {
                    val announcement = parseSingleAnnouncement(JSONObject(trimmedJson))
                    if (announcement != null) listOf(announcement) else emptyList()
                }
                else -> emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析公告失败: ${e.message}")
            emptyList()
        }
    }
    
    private fun parseAnnouncementArray(arr: JSONArray): List<Announcement> {
        val result = mutableListOf<Announcement>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            parseSingleAnnouncement(obj)?.let { result.add(it) }
        }
        return result
    }
    
    private fun parseSingleAnnouncement(obj: JSONObject): Announcement? {
        return try {
            val id = obj.optString("id", "")
            val title = obj.optString("title", "")
            val content = obj.optString("content", "")
            
            // 跳过空公告
            if (id.isEmpty() || title.isEmpty() || content.isEmpty()) {
                return null
            }
            
            Announcement(
                id = id,
                title = title,
                content = content,
                type = obj.optString("type", "info"),
                showOnce = obj.optBoolean("showOnce", true)
            )
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 判断是否需要显示该公告
     */
    private fun shouldShow(context: Context, announcement: Announcement): Boolean {
        if (!announcement.showOnce) {
            // 每次都显示
            return true
        }
        
        val readIds = getReadIds(context)
        return announcement.id !in readIds
    }
    
    /**
     * 获取已读公告 ID 列表
     */
    private fun getReadIds(context: Context): Set<String> {
        return getPrefs(context).getStringSet(KEY_READ_IDS, emptySet()) ?: emptySet()
    }
    
    /**
     * 标记公告已读
     */
    fun markAsRead(context: Context, announcementId: String) {
        val readIds = getReadIds(context).toMutableSet()
        readIds.add(announcementId)
        
        // 只保留最近100条已读记录，避免无限增长
        val trimmedIds = if (readIds.size > 100) {
            readIds.toList().takeLast(100).toSet()
        } else {
            readIds
        }
        
        getPrefs(context).edit()
            .putStringSet(KEY_READ_IDS, trimmedIds)
            .apply()
    }
}
