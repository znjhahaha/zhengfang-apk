package com.tyust.course.academic.plugin

/** A document connection alone does not mean that upstream controls exist. */
internal object PluginScriptPresentation {
    const val PREPARATION_TIMEOUT_MILLIS = 45_000L
    data class Document(val stage: String, val available: Boolean, val running: Boolean)
    data class State(val status: String, val message: String, val available: Boolean)

    fun resolve(documents: Collection<Document>, elapsedMillis: Long): State {
        if (documents.any { it.stage == "interaction" }) return State("needs_input", "请在下方处理任务提示，完成后继续", false)
        if (documents.any { it.stage == "error" }) return State("error", "任务请求未完成，请查看运行日志，停止后重试", false)
        if (documents.any { it.running || it.stage == "running" }) return State("running", "课程任务正在运行", true)
        if (documents.any { it.stage == "ready" && it.available }) return State("ready", "原脚本任务页已就绪", true)
        if (documents.any { it.stage == "opening" }) return if (elapsedMillis < PREPARATION_TIMEOUT_MILLIS)
            State("loading", "正在进入原脚本任务页", false)
        else State("unavailable", "连接课程任务超时，可停止后重新尝试", false)
        if (documents.any { it.stage == "entry" && it.available }) return State("entry", "课程入口已就绪，正在准备原脚本任务页", true)
        if (elapsedMillis < PREPARATION_TIMEOUT_MILLIS) return State("loading", "正在加载课程页面、题库连接与原脚本", false)
        return State("unavailable", "课程任务未就绪，请检查账号、答题密钥或脚本更新后重试", false)
    }
}
