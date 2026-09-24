package com.tyust.course.academic.plugin

import android.content.Context
import com.tyust.course.academic.*
import com.tyust.course.manager.UserManager
import com.tyust.course.schedule.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Shared data always carries the account, provider and revision that produced it. */
class PluginAcademicData(private val app: Context, private val caller: PluginPackage, private val active: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val readerFactory: (com.tyust.course.model.SchoolConfig, String, com.tyust.course.manager.SessionToken) -> AcademicStudyAdapter = AcademicStudyBridge::reader) {
    private val user = UserManager.getInstance()
    private val token = user.sessionState.token
    private val school = user.currentSchool
    private val account = user.currentAccountStorageKey
    private val provider = school?.let { AcademicProviderRegistry.resolve(it)?.digest ?: "legacy:${it.academicSystem}" }.orEmpty()
    private val providerId = school?.let { AcademicProviderRegistry.resolve(it)?.manifest?.id }
    // A generic protocol is bound to school configuration at runtime. Its bound
    // digest detects config changes; the original digest detects package switches.
    private val packageDigest = providerId?.let { AcademicProviderRegistry.knownPackage(it)?.digest }
    private val scope = PluginJson.sha256("$account\u0000${school?.id}\u0000$provider".toByteArray())
    private val cachePrefs = app.getSharedPreferences("schedule_cache", Context.MODE_PRIVATE)
    private val dataPrefs = app.getSharedPreferences("plugin-academic-snapshots", Context.MODE_PRIVATE)
    private val imports = app.getSharedPreferences("plugin-import-previews", Context.MODE_PRIVATE)
    private val schema = PluginSchema(PluginJson.parse(app.assets.open("academic-plugin/contract.schema.json").bufferedReader().use { it.readText() }))

    private fun checkActive() {
        if (!active() || school == null || account.isBlank() || !user.isLoggedIn || !user.sessionState.isCurrent(token) ||
            (AcademicProviderRegistry.resolve(school)?.digest ?: "legacy:${school.academicSystem}") != provider ||
            providerId != null && (packageDigest == null || !AcademicProviderRegistry.isCurrentPackage(providerId, packageDigest)))
            throw PluginException(PluginErrorCode.STALE_CONTEXT, "请先登录教务账号；账号或适配变化后需要重新读取数据")
    }
    private fun term(input: JSONObject): AcademicTerm {
        val value = input.optString("termId")
        return if (value.isBlank()) ScheduleCacheStore(cachePrefs).currentTerm(account, school!!.id) else AcademicTerm(value)
    }
    private fun entry(item: AcademicScheduleEntry) = JSONObject().put("id", item.sourceId.ifBlank { PluginJson.sha256(item.toString().toByteArray()) })
        .put("name", item.name).put("teacher", item.teacher).put("location", item.location)
        .put("day", item.day).put("startPeriod", item.startPeriod).put("endPeriod", item.endPeriod).put("weeks", JSONArray(ScheduleWeeks.parse(item.weeks).weeks.toList()))
    private fun entries(term: AcademicTerm): JSONArray = JSONArray(ScheduleRepository(app).snapshot(account, school!!.id, term.id).courses.filterNot { it.custom }.map {
        entry(AcademicScheduleEntry(it.name, it.teacher, it.location, it.day, it.startPeriod, it.endPeriod, it.weeks, it.id))
    })
    private fun currentRevision(term: AcademicTerm) = PluginJson.sha256(PluginJson.canonical(entries(term)).toByteArray())
    private fun <T> commit(action: () -> T): T = synchronized(user.sessionState) { synchronized(AcademicProviderRegistry) {
        val lease = providerId?.let(PluginVersionLeases::acquire)
        try { checkActive(); action() } finally { lease?.close() }
    } }
    private fun importedKey(term: AcademicTerm) = "plugin_import_entries:${account}_${school!!.id}_${term.id}"
    private fun courses(rows: JSONArray) = PluginJson.objects(rows).map { item -> AcademicScheduleEntry(item.getString("name"), item.optString("teacher"), item.optString("location"),
        item.getInt("day"), item.getInt("startPeriod"), item.getInt("endPeriod"), (0 until item.getJSONArray("weeks").length()).joinToString(",") { item.getJSONArray("weeks").getInt(it).toString() }, item.getString("id")) }
    private fun applyCalendar(term: AcademicTerm) {
        val key = "plugin_import_calendar:$scope/${term.id}"
        val calendar = cachePrefs.getString(key, null)?.let(::JSONObject) ?: return
        val periods = PluginJson.objects(calendar.getJSONArray("periods"))
        ScheduleCalendarStore(app.getSharedPreferences("course_reminders", Context.MODE_PRIVATE)).write(account, term.id,
            ScheduleTimeBase(calendar.optString("startDate"), periods.associate { it.getInt("number") to it.getString("start") }, periods.associate { it.getInt("number") to it.getString("end") }))
        check(cachePrefs.edit().remove(key).commit())
    }

    suspend fun read(input: JSONObject, refresh: Boolean): JSONObject = withContext(Dispatchers.IO) {
        checkActive()
        var term = term(input)
        commit { applyCalendar(term) }
        val value = if (!refresh) dataPrefs.getString("$scope/${term.id}", null)?.let(::JSONObject)
            ?: JSONObject().put("schedule", JSONObject().put("termId", term.id).put("entries", entries(term)).put("maxWeeks", 25))
        else {
            val reader = readerFactory(school!!, account, token)
            if (input.optString("termId").isBlank()) term = reader.catalog().currentTerm
            val remoteCourses = reader.schedule(term)
            val calendar = reader.calendar(term)
            val grades = try { reader.grades(term) } catch (e: AcademicException) { if (e.status == AcademicStatus.UNSUPPORTED) null else throw e }
            checkActive()
            val rows = PluginScheduleImport.merge(JSONArray(remoteCourses.map(::entry)), JSONArray(cachePrefs.getString(importedKey(term), "[]"))).first
            val snapshot = JSONObject().put("schedule", JSONObject().put("termId", term.id).put("entries", rows).put("maxWeeks", 25))
            calendar?.let { snapshot.put("calendar", it) }
            grades?.let { report -> snapshot.put("grades", JSONObject().put("items", JSONArray(report.grades.map { grade ->
                JSONObject().put("id", grade.id).put("name", grade.name).put("score", grade.score).put("credits", grade.credits).put("gradePoint", grade.gradePoint).put("termId", grade.term)
            })).put("gradePointAverage", report.gradePointAverage).put("totalCredits", report.totalCredits)) }
            commit {
                ScheduleCacheStore(cachePrefs).save(account, school.id, CachedSchedule(term, term, AcademicStudyBridge.scheduleJson(courses(rows)), false, calendar))
            }
            snapshot
        }
        checkActive()
        value.put("scope", scope).put("termId", term.id)
        // Native refresh/import can change the common cache after a plugin snapshot.
        value.put("schedule", JSONObject().put("termId", term.id).put("entries", entries(term)).put("maxWeeks", 25))
        if (refresh || !value.has("updatedAt")) value.put("updatedAt", if (refresh) now() else 0)
        value.put("revision", PluginJson.sha256(PluginJson.canonical(value.getJSONObject("schedule")).toByteArray()))
        if (refresh) commit { check(dataPrefs.edit().putString("$scope/${term.id}", value.toString()).commit()) }
        value
    }

    fun preview(input: JSONObject): JSONObject = synchronized(lock) {
        checkActive()
        val schedule = input.getJSONObject("schedule")
        schema.validate(schedule, JSONObject().put("$" + "ref", "#/$" + "defs/Schedule"))
        if (PluginJson.objects(schedule.getJSONArray("entries")).any { it.getInt("startPeriod") > it.getInt("endPeriod") })
            throw PluginException(PluginErrorCode.VALIDATION_FAILED, "课程开始节次不能晚于结束节次")
        val term = AcademicTerm(schedule.getString("termId"))
        input.optJSONObject("calendar")?.let { calendar ->
            schema.validate(calendar, JSONObject().put("$" + "ref", "#/$" + "defs/Calendar"))
            if (calendar.getString("termId") != term.id) throw PluginException(PluginErrorCode.VALIDATION_FAILED, "课表和校历的学期不同")
        }
        val before = entries(term)
        val (merged, duplicates) = PluginScheduleImport.merge(before, schedule.getJSONArray("entries"))
        val manual = ScheduleRepository(app).snapshot(account, school!!.id, term.id).courses.count { it.custom }
        val id = "import_" + UUID.randomUUID().toString()
        val result = JSONObject().put("previewId", id).put("termId", term.id).put("added", merged.length() - before.length())
            .put("duplicates", duplicates).put("manualPreserved", manual).put("entries", merged)
        val record = JSONObject().put("caller", caller.manifest.id).put("digest", caller.digest).put("scope", scope)
            .put("revision", currentRevision(term)).put("createdAt", now()).put("preview", result).put("incoming", schedule.getJSONArray("entries"))
            .put("calendar", input.optJSONObject("calendar")).put("source", input.getString("source"))
        val edit = imports.edit()
        imports.all.filterValues { value -> runCatching { now() - JSONObject(value as String).getLong("createdAt") > 1_800_000 }.getOrDefault(true) }.keys.forEach(edit::remove)
        if (imports.all.size >= 100) throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "导入预览过多，请稍后重试")
        commit { check(edit.putString(id, record.toString()).commit()) }
        result
    }

    fun describe(id: String): JSONObject = owned(id).getJSONObject("preview")
    private fun owned(id: String): JSONObject {
        checkActive()
        val record = imports.getString(id, null)?.let(::JSONObject) ?: throw PluginException(PluginErrorCode.STALE_CONTEXT, "导入预览已过期，请重新预览")
        if (record.getString("caller") != caller.manifest.id || record.getString("digest") != caller.digest || record.getString("scope") != scope ||
            now() - record.getLong("createdAt") > 1_800_000) throw PluginException(PluginErrorCode.STALE_CONTEXT, "导入预览的账号或插件已改变")
        return record
    }

    fun confirm(id: String): JSONObject = synchronized(lock) {
        val record = owned(id)
        val preview = record.getJSONObject("preview")
        val term = AcademicTerm(preview.getString("termId"))
        val receiptKey = "plugin_import_receipt:$id"
        cachePrefs.getString(receiptKey, null)?.let { commit { applyCalendar(term) }; return@synchronized JSONObject(it) }
        if (record.getString("revision") != currentRevision(term)) throw PluginException(PluginErrorCode.CONFLICT, "课表已更新，请重新预览后导入")
        val courses = courses(preview.getJSONArray("entries"))
        val receipt = JSONObject().put("imported", preview.getInt("added")).put("manualPreserved", preview.getInt("manualPreserved")).put("termId", term.id)
        val base = "schedule_${account}_${school!!.id}_${term.id}"
        // The timetable and idempotency receipt commit in one preferences transaction.
        commit {
            if (record.getString("revision") != currentRevision(term)) throw PluginException(PluginErrorCode.CONFLICT, "课表已更新，请重新预览后导入")
            val imported = PluginScheduleImport.merge(JSONArray(cachePrefs.getString(importedKey(term), "[]")), record.optJSONArray("incoming") ?: JSONArray()).first
            val edit = cachePrefs.edit().putString(base, AcademicStudyBridge.scheduleJson(courses)).putLong("${base}_time", now())
                .putString(receiptKey, receipt.toString()).putString(importedKey(term), imported.toString())
            record.optJSONObject("calendar")?.let { edit.putString("plugin_import_calendar:$scope/${term.id}", it.toString()) }
            check(edit.commit())
            applyCalendar(term)
            check(dataPrefs.edit().remove("$scope/${term.id}").commit())
        }
        receipt
    }
    companion object { private val lock = Any() }
}
