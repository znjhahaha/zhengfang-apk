package com.tyust.course.academic

/** Compatibility decoding belongs to native providers, never to screens or queue matching. */
internal object BuiltinCourseData {
    fun identity(stableId: String, raw: Map<String, String>): AcademicCourseIdentity {
        val section = sequenceOf("jxb_id", "jx0404id", "sectionId", "do_jxb_id").mapNotNull { raw[it]?.takeIf(String::isNotBlank) }.firstOrNull().orEmpty()
        return AcademicCourseIdentity(raw["kch_id"] ?: raw["kcid"] ?: stableId, section,
            raw["do_jxb_id"].orEmpty().ifBlank { section }, raw["jxbmc"].orEmpty(),
            section.isNotBlank() && (raw["academic_system"] != "zf" || raw["jxb_id"] == section),
            raw["isSelected"] == "true" || raw["sfxz"] == "1", !raw["popupUrl"].isNullOrBlank())
    }
    fun enrollment(raw: Map<String, String>) = AcademicEnrollmentPresentation(
        raw["sksj"] ?: raw["sksjmc"].orEmpty(), raw["skdd"] ?: raw["jxdd"].orEmpty(), raw["xf"].orEmpty(), raw["jxbmc"].orEmpty(), raw["do_jxb_id"].orEmpty())
}
