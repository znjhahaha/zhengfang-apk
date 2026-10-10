package com.tyust.course.academic.plugin

/** A slow reducer may acknowledge an older edit while the user is still typing or dragging. */
internal class NativeInputReconciliation {
    data class Edit(val nodeId: String, val sequence: Long, val value: Any)
    private var sequence = 0L
    private val edits = mutableMapOf<String, Edit>()
    val values: Map<String, Any> get() = edits.mapValues { it.value.value }

    fun edit(nodeId: String, value: Any): Edit = Edit(nodeId, ++sequence, value).also { edits[nodeId] = it }
    fun acknowledge(edit: Edit?) {
        if (edit != null && edits[edit.nodeId]?.sequence == edit.sequence) edits.remove(edit.nodeId)
    }
    fun clear() = edits.clear()
}
