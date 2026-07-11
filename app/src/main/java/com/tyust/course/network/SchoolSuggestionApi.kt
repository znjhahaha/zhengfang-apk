package com.tyust.course.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

object SchoolSuggestionApi {
    private const val BASE_URL = "https://school-suggestion-service.822069905.workers.dev"
    private val client = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    data class AdaptedSchool(
        val id: String,
        val schoolName: String,
        val academicSystemUrl: String,
        val ssoUrl: String,
        val description: String,
        val publishedAt: String,
        val updatedAt: String
    )

    data class Suggestion(
        val id: String,
        val clientRequestId: String,
        val schoolName: String,
        val academicSystemUrl: String,
        val ssoUrl: String,
        val status: String,
        val reply: String?,
        val createdAt: String,
        val updatedAt: String,
        val repliedAt: String?,
        val credentialsDeletedAt: String?
    )

    suspend fun create(
        request: SchoolAdaptationRequest,
        queryToken: String
    ): Result<Suggestion> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject().apply {
                put("clientRequestId", request.requestId)
                put("queryToken", queryToken)
                put("schoolName", request.schoolName.trim())
                put("academicSystemUrl", request.academicSystemUrl.trim())
                put("ssoUrl", request.ssoUrl.trim())
                put("testUsername", request.testUsername.trim())
                put("temporaryPassword", request.temporaryPassword)
                put("contact", request.contact.trim())
                put("notes", request.notes.trim())
            }
            val httpRequest = Request.Builder()
                .url("$BASE_URL/v1/suggestions")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(httpRequest).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw SchoolSuggestionApiException(response.code)
                }
                parseSuggestion(JSONObject(body).getJSONObject("suggestion"))
            }
        }
    }

    suspend fun get(
        suggestionId: String,
        queryToken: String
    ): Result<Suggestion> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$BASE_URL/v1/suggestions/$suggestionId".toHttpUrl().newBuilder()
                .addQueryParameter("token", queryToken)
                .build()
            val httpRequest = Request.Builder().url(url).get().build()

            client.newCall(httpRequest).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw SchoolSuggestionApiException(response.code)
                }
                parseSuggestion(JSONObject(body).getJSONObject("suggestion"))
            }
        }
    }

    suspend fun getAdaptedSchools(): Result<List<AdaptedSchool>> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("$BASE_URL/v1/adapted-schools")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw SchoolSuggestionApiException(response.code)
                val schools = JSONObject(body).optJSONArray("schools") ?: JSONArray()
                buildList {
                    for (index in 0 until schools.length()) {
                        add(parseAdaptedSchool(schools.getJSONObject(index)))
                    }
                }
            }
        }
    }

    fun parseAdaptedSchool(json: JSONObject): AdaptedSchool = AdaptedSchool(
        id = json.getString("id"),
        schoolName = json.optString("schoolName", "未命名学校"),
        academicSystemUrl = json.optString("academicSystemUrl", ""),
        ssoUrl = json.optString("ssoUrl", ""),
        description = json.optString("description", ""),
        publishedAt = json.optString("publishedAt", ""),
        updatedAt = json.optString("updatedAt", "")
    )

    fun parseSuggestion(json: JSONObject): Suggestion = Suggestion(
        id = json.getString("id"),
        clientRequestId = json.optString("clientRequestId", ""),
        schoolName = json.optString("schoolName", "未命名学校"),
        academicSystemUrl = json.optString("academicSystemUrl", ""),
        ssoUrl = json.optString("ssoUrl", ""),
        status = json.optString("status", "submitted"),
        reply = nullableString(json, "reply"),
        createdAt = json.optString("createdAt", ""),
        updatedAt = json.optString("updatedAt", ""),
        repliedAt = nullableString(json, "repliedAt"),
        credentialsDeletedAt = nullableString(json, "credentialsDeletedAt")
    )

    private fun nullableString(json: JSONObject, key: String): String? =
        if (json.isNull(key)) null else json.optString(key).ifBlank { null }

    class SchoolSuggestionApiException(val statusCode: Int) : Exception("HTTP $statusCode")
}