package com.tyust.course.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SchoolAdaptationManagerTest {

    @Test
    fun mapsServiceStatusesToDisplayStatuses() {
        assertEquals(AdaptationStatus.SUBMITTED, SchoolAdaptationManager.statusFromApi("submitted"))
        assertEquals(
            AdaptationStatus.PENDING_VERIFICATION,
            SchoolAdaptationManager.statusFromApi("pending_verification")
        )
        assertEquals(AdaptationStatus.ANALYZING_LOGIN, SchoolAdaptationManager.statusFromApi("analyzing_login"))
        assertEquals(AdaptationStatus.ADAPTING, SchoolAdaptationManager.statusFromApi("adapting"))
        assertEquals(AdaptationStatus.WAITING_FOR_USER, SchoolAdaptationManager.statusFromApi("waiting_for_user"))
        assertEquals(AdaptationStatus.TESTING, SchoolAdaptationManager.statusFromApi("testing"))
        assertEquals(AdaptationStatus.COMPLETED, SchoolAdaptationManager.statusFromApi("completed"))
        assertEquals(AdaptationStatus.UNSUPPORTED, SchoolAdaptationManager.statusFromApi("unsupported"))
        assertEquals(AdaptationStatus.AUTHOR_REPLIED, SchoolAdaptationManager.statusFromApi("future_status"))
    }

    @Test
    fun serviceSuggestionProducesDisplaySafeItem() {
        val suggestion = SchoolSuggestionApi.Suggestion(
            id = "suggestion-1",
            clientRequestId = "request-1",
            schoolName = "测试大学",
            academicSystemUrl = "https://jw.example.edu.cn",
            ssoUrl = "https://sso.example.edu.cn",
            status = "testing",
            reply = "已完成登录流程分析",
            createdAt = "2026-07-10T12:00:00Z",
            updatedAt = "2026-07-11T12:00:00Z",
            repliedAt = "2026-07-11T12:00:00Z",
            credentialsDeletedAt = null
        )

        val item = with(SchoolAdaptationManager) { suggestion.toAdaptationItem() }

        assertEquals("suggestion-1", item.id)
        assertEquals("request-1", item.requestId)
        assertEquals("测试大学", item.schoolName)
        assertEquals(AdaptationStatus.TESTING, item.status)
        assertEquals("已完成登录流程分析", item.replyMessage)
        assertFalse(item.toString().contains("password", ignoreCase = true))
        assertFalse(item.toString().contains("token", ignoreCase = true))
    }

    @Test
    fun completedSuggestionKeepsPasswordChangeReminderData() {
        val suggestion = SchoolSuggestionApi.Suggestion(
            id = "suggestion-completed",
            clientRequestId = "request-completed",
            schoolName = "测试大学",
            academicSystemUrl = "https://jw.example.edu.cn",
            ssoUrl = "",
            status = "completed",
            reply = null,
            createdAt = "2026-07-10T12:00:00Z",
            updatedAt = "2026-07-11T12:00:00Z",
            repliedAt = null,
            credentialsDeletedAt = "2026-07-11T12:00:00Z"
        )

        val item = with(SchoolAdaptationManager) { suggestion.toAdaptationItem() }

        assertEquals(AdaptationStatus.COMPLETED, item.status)
        assertNull(item.replyMessage)
    }

    @Test
    fun adaptedSchoolCatalogDropsInvalidAndDuplicateHosts() {
        val schools = listOf(
            SchoolSuggestionApi.AdaptedSchool(
                id = "school-1",
                schoolName = "测试大学",
                academicSystemUrl = "https://jw.example.edu.cn/jwglxt",
                ssoUrl = "https://sso.example.edu.cn",
                description = "已支持统一登录",
                publishedAt = "2026-07-10T12:00:00Z",
                updatedAt = "2026-07-10T12:00:00Z"
            ),
            SchoolSuggestionApi.AdaptedSchool(
                id = "school-duplicate",
                schoolName = "重复学校",
                academicSystemUrl = "https://jw.example.edu.cn/other",
                ssoUrl = "",
                description = "",
                publishedAt = "2026-07-09T12:00:00Z",
                updatedAt = "2026-07-09T12:00:00Z"
            ),
            SchoolSuggestionApi.AdaptedSchool(
                id = "school-invalid",
                schoolName = "无效学校",
                academicSystemUrl = "not-a-url",
                ssoUrl = "",
                description = "",
                publishedAt = "",
                updatedAt = ""
            )
        )

        val items = SchoolAdaptationManager.mapAdaptedSchools(schools)

        assertEquals(1, items.size)
        assertEquals("school-1", items.single().id)
    }

    @Test
    fun legacyFeedbackMarkerRemainsFilteredFromOrdinaryHistory() {
        assertTrue(
            SchoolAdaptationManager.isAdaptationFeedback(
                "${SchoolAdaptationManager.CONTENT_MARKER}\n学校名称：测试大学"
            )
        )
        assertFalse(SchoolAdaptationManager.isAdaptationFeedback("普通问题反馈"))
    }
}