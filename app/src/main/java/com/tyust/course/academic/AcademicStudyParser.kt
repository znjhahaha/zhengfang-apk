package com.tyust.course.academic

import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

internal object AcademicStudyParser {
    fun term(value: String): AcademicTerm? = Regex("""(\d{4})\D+(\d{4})\D+([123])(?:\D|$)""")
        .find(value)?.let { AcademicTerm("${it.groupValues[1]}-${it.groupValues[2]}-${it.groupValues[3]}") }

    fun terms(html: String): List<AcademicTerm> {
        val document = Jsoup.parse(html)
        val combined = document.select("select option:not([disabled])").mapNotNull { term(it.attr("value")) ?: term(it.text()) }
        if (combined.isNotEmpty()) return combined.distinctBy { it.id }
        val selects = document.select("select[name]")
        val years = selects.filter { it.attr("name").substringAfterLast('$') in setOf("xnm", "xnd", "ddlXN", "xn") }
            .flatMap { it.select("option:not([disabled])") }.mapNotNull { it.attr("value").take(4).toIntOrNull() }.distinct()
        val semesters = selects.filter { it.attr("name").substringAfterLast('$') in setOf("xqm", "xqd", "ddlXQ", "xq") }
            .flatMap { select -> select.select("option:not([disabled])").mapNotNull { semester(select.attr("name"), it.attr("value")) } }.distinct()
        return years.flatMap { year -> semesters.map { AcademicTerm("$year-${year + 1}-$it") } }
    }

    fun selectedTerm(html: String): AcademicTerm? {
        val document = Jsoup.parse(html)
        document.select("select option[selected]").firstNotNullOfOrNull { term(it.attr("value")) ?: term(it.text()) }?.let { return it }
        fun field(vararg names: String): Pair<String, String>? = document.select("select[name],input[name]").firstOrNull {
            it.attr("name").substringAfterLast('$') in names
        }?.let { element -> element.attr("name") to if (element.tagName() == "select")
            (element.selectFirst("option[selected]") ?: element.selectFirst("option"))?.attr("value").orEmpty() else element.attr("value") }
        val year = field("xnm", "xnd", "ddlXN", "xn")?.second?.take(4)?.toIntOrNull() ?: return null
        val part = field("xqm", "xqd", "ddlXQ", "xq")?.let { semester(it.first, it.second) } ?: return null
        return AcademicTerm("$year-${year + 1}-$part")
    }

    private fun semester(name: String, value: String): Int? = if (name.substringAfterLast('$') == "xqm")
        when (value) { "1", "3" -> 1; "2", "12" -> 2; "16" -> 3; else -> null }
    else value.toIntOrNull()?.takeIf { it in 1..3 }

    fun jsonGrades(body: String): List<AcademicGrade> = AcademicJson.objects(body, "items", "data").mapNotNull { row ->
        val name = text(row, "kc_mc", "kcmc", "KCMC", "courseName")
        if (name.isBlank()) return@mapNotNull null
        val termId = text(row, "xnxqid", "xnxq01id", "xnxq", "term").let { term(it)?.id }.orEmpty().ifBlank {
            val year = text(row, "xnm", "xn", "xnmc").take(4).toIntOrNull()
            val part = when (text(row, "xqm", "xq", "xqmc")) { "3", "1", "第一学期" -> "1"; "12", "2", "第二学期" -> "2"; else -> "" }
            if (year != null && part.isNotBlank()) "$year-${year + 1}-$part" else ""
        }
        AcademicGrade(name, text(row, "zcjstr", "cj", "zcj", "CJ"), text(row, "xf", "XF"), text(row, "jd", "xfjd", "JD"),
            text(row, "kcsx", "kcxzmc", "kcxz", "kclbmc"), termId, text(row, "kch", "kch_id", "KCH"),
            text(row, "ksdw", "kkbmmc", "jgmc"), text(row, "jx0404id", "jxb_id"),
            (listOf("平时" to text(row, "pscj", "PSCJ"), "期中" to text(row, "qzcj", "QZCJ"),
                "期末" to text(row, "qmcj", "QMCJ"), "实验" to text(row, "sycj", "SYCJ"), "实践" to text(row, "sjcj", "SJCJ"))
                .filter { it.second.isNotBlank() }.map { "${it.first}: ${it.second}" } +
                listOf(text(row, "ksxz"), text(row, "ksfs"), text(row, "cjbs")).filter(String::isNotBlank)).joinToString(" | "))
    }

    fun htmlGrades(body: String, url: String): List<AcademicGrade> {
        requireTable(body, "成绩")
        return AcademicTables.rows(body, url).map { row ->
            val year = row.value("学年", "开课学年").take(4).toIntOrNull()
            val semester = row.value("学期", "开课学期")
            val termId = term(semester)?.id ?: if (year != null && semester in setOf("1", "2", "3")) "$year-${year + 1}-$semester" else ""
            AcademicGrade(row.value("课程名称", "课程名"), row.value("成绩", "总评成绩", "总成绩", "最终成绩").ifBlank {
                row.values.entries.firstOrNull { it.key.startsWith("成绩（") || it.key.startsWith("成绩(") }?.value.orEmpty()
            }, row.value("学分"), row.value("绩点", "学分绩点"), row.value("课程性质", "课程属性", "课程类别"),
                termId, row.value("课程代码", "课程编号"), row.value("开课学院", "开课单位"),
                detail = listOf("平时" to row.value("平时成绩"), "期中" to row.value("期中成绩"),
                    "期末" to row.value("期末成绩"), "实验" to row.value("实验成绩"), "实践" to row.value("实践成绩"))
                    .filter { it.second.isNotBlank() }.joinToString(" | ") { "${it.first}: ${it.second}" })
        }
    }

    fun jsonExams(body: String): List<AcademicExam> = AcademicJson.objects(body, "items", "data").mapNotNull { row ->
        val name = text(row, "kskcmc", "kcmc", "kc_mc")
        if (name.isBlank()) null else AcademicExam(name, text(row, "kssj", "ksrq", "kssjms"),
            listOf(text(row, "ksxq", "xqmc"), text(row, "js_mc", "cdmc", "ksdd")).filter(String::isNotBlank).joinToString(" "),
            text(row, "zwh", "zwh_mc"), text(row, "ksccmc", "ksmc", "ksxz"), text(row, "jsxm", "xm"))
    }

    fun htmlExams(body: String, url: String): List<AcademicExam> {
        requireTable(body, "考试")
        return AcademicTables.rows(body, url).map { row -> AcademicExam(row.value("课程名称", "课程名"),
            row.value("考试时间", "考试日期"), listOf(row.value("考试校区", "校区"), row.value("考场", "考试地点", "考试教室")).filter(String::isNotBlank).joinToString(" "),
            row.value("座位号", "座号"), row.value("考试场次", "考试名称", "考试性质"), row.value("授课教师", "教师")) }
    }

    fun jsonSchedule(body: String): List<AcademicScheduleEntry> = AcademicJson.objects(body, "kbList", "data").flatMap { row ->
        val name = text(row, "kcmc", "KCMC")
        if (name.isBlank()) return@flatMap emptyList()
        val day = text(row, "xqj", "XQJ").toIntOrNull()
        val periods = periods(text(row, "jcs", "JCS", "jcor", "JCOR"))
        if (day !in 1..7 || periods.isEmpty()) throw AcademicException(AcademicStatus.PAGE_CHANGED, "课表缺少可识别的星期或节次")
        periods.map { (start, end) -> AcademicScheduleEntry(name, text(row, "xm", "XM", "jsxm"),
            listOf(text(row, "xqmc", "cdxqmc"), text(row, "cdmc", "CDMC", "jxcdmc")).filter(String::isNotBlank).joinToString(" "),
            day!!, start, end, text(row, "zcd", "ZCD", "weeks"),
            text(row, "schedule_source_id", "kcb_id", "kb_id", "jxb_id", "jx0404id", "kch_id")) }
    }

    private data class Cell(val row: Int, val col: Int, val element: Element)

    private fun grid(table: Element): List<Cell> {
        val occupied = mutableSetOf<Pair<Int, Int>>()
        val result = mutableListOf<Cell>()
        table.select("tr").filter { it.closest("table") === table }.forEachIndexed { row, tr ->
            var col = 0
            tr.children().filter { it.tagName() in setOf("td", "th") }.forEach { cell ->
                while (row to col in occupied) col++
                result += Cell(row, col, cell)
                val rows = (cell.attr("rowspan").toIntOrNull() ?: 1).coerceIn(1, 40)
                val cols = (cell.attr("colspan").toIntOrNull() ?: 1).coerceIn(1, 20)
                for (r in row until row + rows) for (c in col until col + cols) occupied += r to c
                col += cols
            }
        }
        return result
    }

    fun htmlSchedule(body: String): List<AcademicScheduleEntry> {
        if (AcademicHtml.isLoginPage(body)) throw AcademicException(AcademicStatus.SESSION_EXPIRED, "登录已失效，请重新登录")
        val document = Jsoup.parse(body)
        val table = document.select("table").lastOrNull { table -> table.select("tr").filter { it.closest("table") === table }
            .flatMap { it.children() }.any { it.text().trim() in setOf("星期一", "周一") } }
            ?: throw AcademicException(AcademicStatus.PAGE_CHANGED, "学校未返回可识别的课表")
        val grid = grid(table)
        val dayLabels = listOf("一", "二", "三", "四", "五", "六", "日")
        val headingRow = grid.first { cell -> dayLabels.any { cell.element.text().trim() == "星期$it" || cell.element.text().trim() == "周$it" } }.row
        val days = grid.filter { it.row == headingRow }.mapNotNull { cell ->
            val text = cell.element.text().trim().replace("天", "日")
            dayLabels.indexOfFirst { text == "星期$it" || text == "周$it" }.takeIf { it >= 0 }?.let { cell.col to it + 1 }
        }.toMap()
        val result = mutableListOf<AcademicScheduleEntry>()
        for (cell in grid.filter { it.row > headingRow && it.col in days }) {
            val modern = cell.element.select(".courselists-item")
            val legacy = cell.element.select(".kbcontent")
            val visibleLegacy = cell.element.select(".kbcontent1")
            val entries = when {
                modern.isNotEmpty() -> modern
                visibleLegacy.isNotEmpty() -> QzTimetableCells.entries(cell.element,
                    grid.filter { it.row == cell.row && it.col < days.keys.min() }.map { it.element.text() })
                legacy.isNotEmpty() -> legacy
                else -> legacyBlocks(cell.element)
            }
            for (entry in entries) {
                val abbreviation = entry.selectFirst(".qz-hasCourse-abbrinfo")
                val lines = if (abbreviation != null) listOf(entry.selectFirst(".qz-hasCourse-title")?.text().orEmpty(), abbreviation.text()) else lines(entry)
                val content = lines.joinToString("\n")
                if (content.isBlank() || content in setOf(" ", "-")) continue
                val periodText = Regex("""(?:第|\[)?(\d+(?:\s*[-—,，、]\s*\d+)*)\s*节""").find(content)?.groupValues?.get(1)
                val spans = periods(periodText.orEmpty())
                if (spans.isEmpty()) {
                    if (modern.isNotEmpty() || legacy.isNotEmpty() || visibleLegacy.isNotEmpty()) throw AcademicException(AcademicStatus.PAGE_CHANGED, "学校课表缺少可识别的节次")
                    continue
                }
                val name = entry.selectFirst(".qz-hasCourse-title")?.text()?.trim().orEmpty().ifBlank { lines.firstOrNull().orEmpty() }
                val timeLine = lines.indexOfFirst { Regex("""\d.*节""").containsMatchIn(it) }
                val teacher = labelled(content, "老师", "教师").ifBlank { entry.select("[title=老师],[title=教师]").text() }
                    .ifBlank { if (timeLine >= 0) lines.getOrNull(timeLine + 1).orEmpty() else "" }.removeSuffix("未定义").trim()
                val location = labelled(content, "地点", "教室").ifBlank { entry.select("[title=教室],[title=上课地点]").text() }
                    .ifBlank { if (timeLine >= 0) lines.getOrNull(timeLine + 2).orEmpty() else "" }
                val normalizedWeeks = labelled(content, "时间").ifBlank { content }
                    .replace("(周)", "周").replace("（周）", "周").replace('（', '(').replace('）', ')')
                val weeks = Regex("""(?:第)?(\d+(?:\s*[-—,，、]\s*\d+)*\s*(?:\([单双]\))?\s*周(?:\([单双]\))?)""")
                    .findAll(normalizedWeeks).map { it.groupValues[1] }.distinct().joinToString(",")
                spans.forEach { (start, end) -> result += AcademicScheduleEntry(name, teacher, location, days.getValue(cell.col), start, end, weeks) }
            }
        }
        if (result.isEmpty() && document.select(".courselists-item,.kbcontent,.kbcontent1").any { it.text().isNotBlank() })
            throw AcademicException(AcademicStatus.PAGE_CHANGED, "学校课表格式已变化，暂时无法读取节次")
        return result.distinct()
    }

    fun periods(value: String): List<Pair<Int, Int>> {
        val numbers = sortedSetOf<Int>()
        value.replace('—', '-').split(',', '，', '、').forEach { part ->
            val match = Regex("""^\s*(\d+)(?:\s*-\s*(\d+))?\s*$""").matchEntire(part) ?: return@forEach
            val start = match.groupValues[1].toIntOrNull() ?: return@forEach
            val end = match.groupValues[2].toIntOrNull() ?: start
            if (start in 1..30 && end in start..30) numbers += start..end
        }
        val result = mutableListOf<Pair<Int, Int>>()
        numbers.forEach { number ->
            val previous = result.lastOrNull()
            if (previous != null && previous.second + 1 == number) result[result.lastIndex] = previous.first to number
            else result += number to number
        }
        return result
    }

    private fun labelled(content: String, vararg labels: String): String =
        Regex("(?:" + labels.joinToString("|", transform = Regex::escape) + ")[:：]\\s*([^;；\\n]+)").find(content)?.groupValues?.get(1)?.trim().orEmpty()

    private fun lines(element: Element): List<String> = Jsoup.parse(element.html().replace(Regex("(?i)<br\\s*/?>"), "\n"))
        .wholeText().lines().map(String::trim).filter(String::isNotBlank)

    private fun legacyBlocks(element: Element): List<Element> = element.html()
        .split(Regex("(?i)(?:<br\\s*/?>\\s*){2,}|<hr[^>]*>"))
        .map { Jsoup.parseBodyFragment(it).body() }.filter { it.text().isNotBlank() }

    private fun text(row: JSONObject, vararg names: String): String = Jsoup.parse(AcademicJson.string(row, *names)).text().trim()

    private fun requireTable(body: String, kind: String) {
        if (AcademicHtml.isLoginPage(body)) throw AcademicException(AcademicStatus.SESSION_EXPIRED, "登录已失效，请重新登录")
        val document = Jsoup.parse(body)
        if (document.select("table").none { it.text().contains("课程") && it.text().contains(kind) })
            throw AcademicException(AcademicStatus.PAGE_CHANGED, "学校未返回可识别的${kind}列表")
    }
}
