package com.tyust.course.academic

import com.tyust.course.model.SchoolConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Optional operator-run read-only check. CI has no credentials and skips it. No course writes exist here. */
class HutReadOnlyIntegrationTest {
    @Test fun casLoginAndTimetableQuery() = runBlocking {
        val username = System.getenv("HUT_VERIFY_USERNAME").orEmpty()
        val password = System.getenv("HUT_VERIFY_PASSWORD").orEmpty()
        assumeTrue("No local integration credentials supplied", username.isNotBlank() && password.isNotBlank())
        val school = SchoolConfig("hut-read-only-check", "HUT", "jwxt.hut.edu.cn", "http").apply {
            basePath = "/jsxsd"; academicSystem = AcademicSystem.QZ.id
        }
        val session = AcademicSession(AcademicSessionKey(school.id, "read-only-check"), school.fullBasePath)
        val http = AcademicHttpTransport(school, session)
        val adapter = QzAcademicAdapter(school, session, http)
        try {
            withTimeout(90_000) {
                val result = adapter.login(Credentials(username, password))
                println("HUT read-only login status: ${result.status}")
                assumeTrue("Interactive verification required: ${result.status}", result.status !in
                    setOf(AcademicStatus.CAPTCHA_REQUIRED, AcademicStatus.HUMAN_VERIFICATION_REQUIRED))
                assertEquals(AcademicStatus.SUCCESS, result.status)
                val reader = AcademicStudyReader(school, session, http)
                val term = reader.catalog().currentTerm
                val courses = reader.schedule(term)
                println("HUT read-only schedule query: term=${term.id}, course entries=${courses.size}")
            }
        } finally {
            adapter.clearLoginState()
            session.retire()
        }
    }
}
