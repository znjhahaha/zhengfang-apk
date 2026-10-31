package com.tyust.course.study

import com.tyust.course.ui.screen.ExamItemUi
import org.junit.Assert.*
import org.junit.Test

class ExamRecordsTest {
    private val term = "2026-2027-1"
    private fun automatic(time: String = "2026-12-01 08:00", source: String = "") =
        ExamRecord.automatic(term, ExamItemUi("数学", time, "A101", "1", "期末", "教师"), source)

    @Test fun manualOverrideSurvivesRefreshAndItsDeletionRestoresLatestAutomaticExam() {
        val old = automatic(source = "exam-1")
        val edit = old.overrideWith(old.exam.copy(location = "B202"))
        val fresh = automatic("2026-12-02 09:00", "exam-1")
        val merged = ExamRecords.merge(listOf(fresh), listOf(edit))
        assertEquals(1, merged.size)
        assertEquals("B202", merged.single().exam.location)
        assertTrue(merged.single().manual)
        assertEquals(fresh, ExamRecords.merge(listOf(fresh), emptyList()).single())
    }

    @Test fun vanishedAutomaticExamDoesNotEraseManualOverride() {
        val edited = automatic().overrideWith(automatic().exam.copy(seatNumber = "23"))
        assertEquals(listOf(edited.copy(needsRelink = true)), ExamRecords.merge(emptyList(), listOf(edited)))
    }

    @Test fun uniqueFallbackIdentityTracksAChangedAutomaticTimeWithoutSourceId() {
        val old = automatic()
        val edited = old.overrideWith(old.exam.copy(location = "B202"))
        assertEquals(listOf(edited), ExamRecords.merge(listOf(automatic("2026-12-03 09:00")), listOf(edited)))
    }

    @Test fun ambiguousFallbackDoesNotHideAnotherExamAndMarksRelinking() {
        val old = automatic()
        val edited = old.overrideWith(old.exam.copy(location = "B202"))
        val merged = ExamRecords.merge(listOf(automatic("2026-12-02"), automatic("2026-12-03")), listOf(edited))
        assertEquals(3, merged.size)
        assertTrue(merged.first { it.manual }.needsRelink)
    }

    @Test fun independentManualRecordCoexistsAndTermsDoNotCrossMatch() {
        val custom = ExamRecord.manual(term, automatic().exam.copy(courseName = "英语"))
        val other = automatic().copy(termId = "2026-2027-2")
        assertEquals(2, ExamRecords.merge(listOf(other), listOf(custom)).size)
        assertEquals(2, ExamRecords.merge(listOf(other), listOf(automatic().overrideWith(automatic().exam))).size)
    }

    @Test fun providerIdentitySurvivesTheBridgeAndDuplicateFallbackRowsHaveUniqueKeys() {
        val domain = com.tyust.course.academic.AcademicExam("数学", "2026-12-01", "A101", sourceId = "school-id")
        val ui = com.tyust.course.academic.AcademicStudyBridge.exam(domain)
        assertEquals("school-id", ui.sourceId)
        val first = automatic().exam
        val rows = ExamRecords.automatic(term, listOf(first, first))
        assertEquals(2, rows.map { it.id }.distinct().size)
        assertEquals(rows, ExamRecords.automatic(term, listOf(first, first)))
    }

    @Test fun changedCourseIdentityCanBeExplicitlyRelinkedWithoutHidingOtherRows() {
        val old = automatic()
        val edited = old.overrideWith(old.exam.copy(location = "B202"))
        val fresh = ExamRecord.automatic(term, old.exam.copy(courseName = "高等数学"))
        assertTrue(ExamRecords.merge(listOf(fresh), listOf(edited)).first { it.manual }.needsRelink)
        val relinked = fresh.overrideWith(edited.exam).copy(id = edited.id)
        assertEquals(listOf(relinked), ExamRecords.merge(listOf(fresh), listOf(relinked)))
    }

    @Test fun contentDerivedProtocolIdDoesNotBlockUniqueReschedulingFallback() {
        fun parsed(id: String, time: String) = com.tyust.course.academic.plugin.decodeAcademicExam(
            org.json.JSONObject().put("id", id).put("name", "数学").put("time", time).put("examName", "期末"))
        val before = com.tyust.course.academic.AcademicStudyBridge.exam(parsed("content:old", "2026-12-01"))
        val after = com.tyust.course.academic.AcademicStudyBridge.exam(parsed("content:new", "2026-12-03"))
        assertEquals("", before.sourceId)
        val edit = ExamRecords.automatic(term, listOf(before)).single().overrideWith(before.copy(location = "B202"))
        assertEquals(listOf(edit), ExamRecords.merge(ExamRecords.automatic(term, listOf(after)), listOf(edit)))
        assertEquals("backend-42", parsed("backend-42", "2026-12-03").sourceId)
    }

    @Test fun persistedLegacyGeneratedSourceUsesUniqueFallbackAfterRescheduling() {
        val exam = ExamItemUi("Math", "2026-12-01 08:00", "A", "", "final", "")
        val old = ExamRecord.automatic(term, exam, "6fceee05:Math|2026-12-01 08:00|final")
        val edit = old.overrideWith(exam.copy(location = "Manual"))
        val restored = ExamRecord.fromJson(edit.toJson())
        assertEquals("", restored.sourceId)
        val fresh = ExamRecord.automatic(term, exam.copy(examTime = "2026-12-02 09:00"))
        assertEquals(listOf(restored), ExamRecords.merge(listOf(fresh), listOf(restored)))
        assertEquals(3, ExamRecords.merge(listOf(fresh, fresh.copy(id = "other")), listOf(restored)).size)
    }

    @Test fun legacyGeneratedIdentitySurvivesEditsToTimeNameAndTypeUsingTheOriginalAssociation() {
        val original = ExamItemUi("Math", "2026-12-01 08:00", "A", "", "final", "")
        val automatic = ExamRecord.automatic(term, original, "6fceee05:Math|2026-12-01 08:00|final")
        val changes = listOf(original.copy(examTime = "2026-12-02 09:00"), original.copy(courseName = "My Math"),
            original.copy(examName = "My exam"), original.copy(courseName = "My Math", examTime = "2026-12-02 09:00", examName = "My exam"))
        for (edit in changes) {
            val json = automatic.overrideWith(edit).toJson().apply { remove("associationKey") }
            val restored = ExamRecord.fromJson(json)
            assertEquals("", restored.sourceId)
            assertEquals(edit.courseName, restored.exam.courseName)
            assertEquals(edit.examTime, restored.exam.examTime)
            assertEquals(edit.examName, restored.exam.examName)
            val fresh = ExamRecord.automatic(term, original.copy(examTime = "2026-12-03 11:00"))
            assertEquals(listOf(restored), ExamRecords.merge(listOf(fresh), listOf(restored)))
            val ambiguous = ExamRecords.merge(listOf(fresh, fresh.copy(id = "second")), listOf(restored))
            assertEquals(3, ambiguous.size); assertTrue(ambiguous.first().needsRelink)
        }
    }

    @Test fun opaqueOrUnverifiableLegacyLookingBackendIdentitiesArePreserved() {
        val original = ExamItemUi("Math", "2026-12-01 08:00", "A", "", "final", "")
        for (source in listOf("backend:42|exam", "deadbeef:Math|2026-12-01 08:00|final", "6fceee05:Math|2026-12-01 08:00|final-truncated")) {
            val old = ExamRecord.automatic(term, original, source)
            val restored = ExamRecord.fromJson(old.overrideWith(original.copy(courseName = "My Math", examTime = "2027-01-01", examName = "My exam")).toJson().apply { remove("associationKey") })
            assertEquals(source, restored.sourceId)
            assertEquals(1, ExamRecords.merge(listOf(old), listOf(restored)).size)
        }
    }

    @Test fun duplicateBackendIdsRefineOriginalContentWithoutUsingResponseOrder() {
        val a = automatic().exam.copy(sourceId = "duplicate", location = "A", examName = "期末")
        val b = a.copy(location = "B", examName = "补考")
        val initial = ExamRecords.automatic(term, listOf(a, b))
        val edit = initial.first().overrideWith(a.copy(location = "Manual"))
        val reversed = ExamRecords.automatic(term, listOf(b, a))
        assertEquals(initial.first().id, reversed.last().id)
        val merged = ExamRecords.merge(reversed, listOf(edit))
        assertEquals(2, merged.size)
        assertFalse(merged.first().needsRelink)
        assertEquals(b, merged.last().exam)
        val restored = ExamRecords.merge(reversed, ExamRecords.fromJson(ExamRecords.toJson(listOf(edit))))
        assertEquals(listOf("Manual", "B"), restored.map { it.exam.location })
        assertEquals(edit.id, restored.first().id)
        assertEquals("duplicate", restored.first().sourceId)
        assertFalse(restored.first().needsRelink)
    }

    @Test fun indistinguishableDuplicatesKeepBothAutomaticRowsAndRequireRelink() {
        val row = automatic().exam.copy(sourceId = "duplicate")
        val initial = ExamRecords.automatic(term, listOf(row, row))
        val edit = initial.first().overrideWith(row.copy(location = "Manual"))
        val merged = ExamRecords.merge(initial.reversed(), listOf(edit))
        assertEquals(3, merged.size)
        assertTrue(merged.first().needsRelink)
    }

    @Test fun legacyOrdinalOverrideCannotResolveAnAmbiguousSource() {
        val a = automatic().exam.copy(sourceId = "duplicate")
        val rows = ExamRecords.automatic(term, listOf(a, a.copy(location = "B")))
        val old = rows.first().overrideWith(a.copy(location = "Manual")).toJson().apply { remove("associationKey") }
        val merged = ExamRecords.merge(rows.reversed(), listOf(ExamRecord.fromJson(old)))
        assertEquals(3, merged.size)
        assertTrue(merged.first().needsRelink)
    }
}
