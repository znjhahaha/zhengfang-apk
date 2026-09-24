package com.tyust.course.academic

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class AcademicRequestPacingTest {
    @Test fun concurrentReadersShareTheAccountSessionRequestInterval() = runBlocking {
        val session = AcademicSession(AcademicSessionKey("fixture", "account"), "https://school.test/")
        try {
            val starts = (1..3).map { async { session.paceRequest(40); System.nanoTime() } }.awaitAll().sorted()
            assertTrue(starts.zipWithNext().all { (previous, next) -> next - previous >= 35_000_000 })
        } finally { session.retire() }
    }
}
