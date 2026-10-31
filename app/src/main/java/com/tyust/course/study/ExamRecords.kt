package com.tyust.course.study

import com.tyust.course.ui.screen.ExamItemUi
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

data class ExamRecord(
    val id: String, val termId: String, val exam: ExamItemUi, val manual: Boolean,
    val sourceId: String = "", val overridesId: String = "", val matchKey: String = "",
    val needsRelink: Boolean = false, val associationKey: String = ""
) {
    fun overrideWith(value: ExamItemUi): ExamRecord = if (manual) copy(exam = value, needsRelink = false)
        else ExamRecord(UUID.randomUUID().toString(), termId, value, true, sourceId, id, matchKey, associationKey = associationKey)

    fun toJson() = JSONObject().put("id", id).put("term", termId).put("manual", manual)
        .put("sourceId", sourceId).put("overridesId", overridesId).put("matchKey", matchKey).put("associationKey", associationKey)
        .put("exam", JSONObject().put("courseName", exam.courseName).put("examTime", exam.examTime)
            .put("location", exam.location).put("seatNumber", exam.seatNumber)
            .put("examName", exam.examName).put("teacher", exam.teacher))

    companion object {
        private fun hash(values: List<String>) = MessageDigest.getInstance("SHA-256")
            .digest(values.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        private fun normalized(value: String) = value.trim().replace(Regex("\\s+"), " ").lowercase(java.util.Locale.ROOT)
        fun automatic(term: String, exam: ExamItemUi, source: String = ""): ExamRecord {
            val key = hash(listOf(term, normalized(exam.courseName), normalized(exam.examName)))
            val content = hash(listOf(term, exam.courseName, exam.examName, exam.examTime, exam.location, exam.seatNumber, exam.teacher))
            val id = if (source.isNotBlank()) hash(listOf(term, source, content)) else content
            return ExamRecord(id, term, exam, false, source, matchKey = key, associationKey = content)
        }
        fun manual(term: String, exam: ExamItemUi) = ExamRecord(UUID.randomUUID().toString(), term, exam, true)
        fun fromJson(json: JSONObject): ExamRecord {
            val exam = json.getJSONObject("exam")
            val term = json.getString("term")
            val matchKey = json.optString("matchKey")
            var sourceId = com.tyust.course.academic.examSourceIdentity(json.optString("sourceId"),
                exam.getString("courseName"), exam.getString("examTime"), exam.optString("examName"))
            if (json.getBoolean("manual") && json.optString("overridesId").isNotBlank() && matchKey.matches(Regex("[0-9a-f]{64}"))) {
                val original = com.tyust.course.academic.legacyExamSourceParts(sourceId)
                // JSON keys contain the original type too. HTML keys omit it, but still verify
                // their original content hash. Never infer an identity from an ordinal or prefix.
                if (original != null && (original.size == 2 ||
                    matchKey == hash(listOf(term, normalized(original[0]), normalized(original[2]))))) sourceId = ""
            }
            return ExamRecord(json.getString("id"), json.getString("term"),
                ExamItemUi(exam.getString("courseName"), exam.getString("examTime"), exam.optString("location"),
                    exam.optString("seatNumber"), exam.optString("examName"), exam.optString("teacher")),
                json.getBoolean("manual"), sourceId, json.optString("overridesId"), matchKey,
                associationKey = json.optString("associationKey"))
        }
    }
}

object ExamRecords {
    fun merge(automatic: List<ExamRecord>, manual: List<ExamRecord>): List<ExamRecord> {
        val hidden = mutableSetOf<String>()
        val edited = manual.map { record ->
            if (record.overridesId.isBlank()) return@map record
            val candidates = automatic.filter { it.termId == record.termId &&
                if (record.sourceId.isNotBlank()) it.sourceId == record.sourceId else it.matchKey == record.matchKey }
            // Original content can refine a duplicate source. A response-order suffix cannot.
            val target = candidates.singleOrNull() ?: if (record.associationKey.isNotBlank())
                candidates.filter { it.associationKey == record.associationKey }.singleOrNull() else null
            if (target != null) {
                hidden.add(target.id)
                record.copy(needsRelink = false)
            } else record.copy(needsRelink = true)
        }
        return edited + automatic.filter { it.id !in hidden }
    }
    fun toJson(records: List<ExamRecord>) = JSONObject().put("records", JSONArray().apply { records.forEach { put(it.toJson()) } })
    fun automatic(term: String, values: List<ExamItemUi>): List<ExamRecord> {
        val rows = values.map { ExamRecord.automatic(term, it, it.sourceId) }
        val counts = rows.groupingBy { it.id }.eachCount()
        val occurrences = mutableMapOf<String, Int>()
        return rows.map { row ->
            if (counts.getValue(row.id) == 1) row else {
                val index = occurrences.getOrDefault(row.id, 0)
                occurrences[row.id] = index + 1
                row.copy(id = "${row.id}#$index")
            }
        }
    }
    fun fromJson(json: JSONObject): List<ExamRecord> = json.getJSONArray("records").let { rows ->
        (0 until rows.length()).map { ExamRecord.fromJson(rows.getJSONObject(it)) }
    }
}
