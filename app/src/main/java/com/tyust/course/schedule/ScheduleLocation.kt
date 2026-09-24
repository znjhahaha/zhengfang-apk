package com.tyust.course.schedule

/** Display-only shortening. Stored course data and detail views keep the full address. */
object ScheduleLocation {
    private val roomNumber = Regex("[A-Za-z]?-?\\d{2,5}[A-Za-z]?")

    fun compact(raw: String): String {
        if (raw.isBlank()) return "教室待定"
        val normalized = raw.trim().replace(Regex("[\\s　]+"), " ")
        if (normalized.length <= 12) return normalized
        // Campus prefixes and trailing notes are less useful on a narrow timetable column.
        val withoutCampus = normalized.replace(Regex("^.*?校区[ ·,，:：/\\-]*"), "")
            .replace(Regex("[（(][^）)]*[）)]"), "").trim()
        val room = roomNumber.findAll(withoutCampus).lastOrNull()
        if (room != null) {
            val prefix = withoutCampus.substring(0, room.range.first).trim().trimEnd('-', '—')
                .replace(Regex("[一二三四五六七八九十百\\d]+层$"), "").trim()
            val building = Regex("[\\p{IsHan}A-Za-z0-9]+(?:教学楼|实验楼|楼|馆|栋|座|区|教)").find(prefix)?.value
            if (building != null) return "$building ${room.value}"
        }
        return withoutCampus.ifBlank { normalized }
    }

    fun room(raw: String): String? = roomNumber.findAll(compact(raw)).lastOrNull()?.value

    /** Shorten the building first, keeping the room number intact in a narrow slot. */
    fun fit(raw: String, width: Float, lines: Int = 1, measure: (String) -> Float): String {
        val label = compact(raw)
        if (measure(label) <= width) return label
        fun elide(text: String, available: Float): String {
            if (measure(text) <= available) return text
            var head = text
            while (head.isNotEmpty() && measure("$head…") > available) head = head.dropLast(1)
            return if (measure("$head…") <= available) "$head…" else ""
        }
        val room = roomNumber.findAll(label).lastOrNull()?.takeIf { it.range.last == label.lastIndex }
            ?: return elide(label, width)
        val building = label.substring(0, room.range.first).trim()
        return if (lines > 1 && building.isNotEmpty()) elide(building, width) + "\n" + room.value
            else listOf(elide(building, width - measure(" ${room.value}")), room.value).filter(String::isNotBlank).joinToString(" ")
    }
}
