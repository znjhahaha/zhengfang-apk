package com.tyust.course.academic

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Older QZ pages keep weeks in the visible cell and teacher/class data in a tooltip. */
internal object QzTimetableCells {
    fun entries(cell: Element, periodLabels: List<String>): List<Element> {
        val visible = cell.select(".kbcontent1").flatMap(::blocks)
        val tooltips = cell.select(".kbcontent").flatMap(::blocks)
        if (visible.isEmpty()) {
            if (tooltips.isNotEmpty()) changed("学校课表缺少可见的课程时间")
            return emptyList()
        }
        return visible.mapIndexed { index, entry ->
            val name = firstLine(entry)
            val tooltip = tooltips.takeIf { it.size == visible.size }?.get(index)?.takeIf {
                firstLine(it).matches(Regex(Regex.escape(name) + "(?:\\[[^\\]]*\\])*"))
            }
            val time = entry.selectFirst("[title=周次(节次)]")
            if (time != null && !Regex("\\d[^\\n]*节").containsMatchIn(time.text())) {
                val classes = tooltip?.selectFirst("[title=班级]")?.clone()?.apply { select("span").remove() }
                    ?.text()?.replace(Regex("\\s+"), "").orEmpty()
                val raw = time.text().replace(Regex("\\s+"), "")
                val weekText = when {
                    classes.isNotBlank() && raw.startsWith(classes) -> raw.removePrefix(classes)
                    // A standalone, explicitly marked week range may accompany a class tooltip.
                    raw.matches(Regex("^[0-9,，、\\-—]+[（(]周[）)]$")) -> raw
                    else -> changed("学校课表班级与周次无法对应")
                }
                val weeks = weeks(weekText)
                val periods = periodLabels.mapNotNull(::periods).distinct().singleOrNull()
                    ?: changed("学校课表缺少可识别的行节次")
                time.text("$weeks [$periods 节]")
            }
            if (entry.select("[title=老师],[title=教师]").isEmpty()) {
                tooltip?.selectFirst("[title=老师],[title=教师]")?.let { entry.appendChild(it.clone()) }
            }
            entry
        }
    }

    private fun blocks(container: Element): List<Element> = container.html()
        .split(Regex("(?i)(?:<br\\s*/?>\\s*)?-{3,}(?:\\s*<br\\s*/?>)?|<hr[^>]*>"))
        .map { Jsoup.parseBodyFragment(it).body() }.filter { it.text().isNotBlank() }

    private fun firstLine(element: Element): String = Jsoup.parse(
        element.html().replace(Regex("(?i)<br\\s*/?>"), "\n")
    ).wholeText().lineSequence().map(String::trim).firstOrNull(String::isNotBlank).orEmpty()

    private fun weeks(value: String): String {
        val normalized = value.replace('（', '(').replace('）', ')').replace('—', '-').replace('，', ',').replace('、', ',')
        val match = Regex("^([0-9,\\-]+)\\((全部|单|双|单周|双周|周)\\)$").matchEntire(normalized)
            ?: changed("学校课表周次格式已变化")
        val ranges = match.groupValues[1]
        for (range in ranges.split(',')) {
            val numbers = range.split('-').map { it.toIntOrNull() ?: changed("学校课表周次无效") }
            if (numbers.size !in 1..2 || numbers.any { it !in 1..60 } || numbers.first() > numbers.last())
                changed("学校课表周次无效")
        }
        val mode = match.groupValues[2]
        return ranges + "周" + when { mode.startsWith("单") -> "(单)"; mode.startsWith("双") -> "(双)"; else -> "" }
    }

    private fun periods(label: String): String? {
        val value = label.replace(Regex("\\s+"), "")
        // Newer legacy templates append clock times after an explicit small-period group.
        // Read only that group, so 09:00-10:30 cannot become section numbers.
        val smallPeriods = Regex("""[（(](\d{1,2}(?:[,，]\d{1,2})*)小节[）)]""").findAll(value).toList()
        if (smallPeriods.isNotEmpty()) return smallPeriods.singleOrNull()?.groupValues?.get(1)?.let(::periods)
        val paired = mapOf("一二" to "1-2", "三四" to "3-4", "五六" to "5-6", "七八" to "7-8", "九十" to "9-10")
        paired[value]?.let { return it }
        val chinese = Regex("^第([一二三四五六七八九十零〇0-9]+)节$").matchEntire(value)?.groupValues?.get(1)
            ?: value.takeIf { it.matches(Regex("[一二三四五六七八九十零〇]+")) }
        if (chinese != null) {
            val token = chinese
            val digits = "零一二三四五六七八九"
            val number = token.toIntOrNull() ?: if (token.contains('十')) {
                val parts = token.split('十')
                if (parts.size != 2 || parts.any { it.length > 1 }) return null
                val tens = if (parts[0].isEmpty()) 1 else digits.indexOf(parts[0].single())
                val units = if (parts[1].isEmpty()) 0 else digits.indexOf(parts[1].single())
                if (tens !in 1..3 || units !in 0..9) return null
                tens * 10 + units
            } else if (token.length == 1) digits.indexOf(token.single()) else return null
            return number.takeIf { it in 1..30 }?.toString()
        }
        val raw = if (value.matches(Regex("(?:[0-2][0-9]|30){2,15}"))) {
            val items = value.chunked(2).map(String::toInt)
            if (items.any { it !in 1..30 } || items != items.distinct().sorted()) return null
            items.joinToString(",")
        } else value
        if (!raw.matches(Regex("\\d{1,2}(?:[-,，、]\\d{1,2})*"))) return null
        return AcademicStudyParser.periods(raw).takeIf { it.isNotEmpty() }?.joinToString(",") { (start, end) ->
            if (start == end) start.toString() else "$start-$end"
        }
    }

    private fun changed(message: String): Nothing = throw AcademicException(AcademicStatus.PAGE_CHANGED, message)
}
