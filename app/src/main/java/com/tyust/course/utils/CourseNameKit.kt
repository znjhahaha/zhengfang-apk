package com.tyust.course.utils

import com.tyust.course.academic.ZfSelectionControl
import com.tyust.course.model.Course
import org.json.JSONObject

/**
 * 课程名与选课参数工具集。
 *
 * 1. 全半角括号归一化：教务库课程名通常为半角括号（如"大学体育(三)"），
 *    而中文输入法默认输入全角括号（"大学体育（三）"），直接字符串比较会匹配失败。
 * 2. 独立保存 xkkz_id 与 xkkz_xh；它们可以同时存在，不能互相代用。
 *    只接受一个控制字段的 Display 接口优先使用 ID，否则使用 XH。
 */
object CourseNameKit {

    @JvmStatic @JvmOverloads
    fun applyControlParams(target: MutableMap<String, String>, source: Map<String, String>?, course: Course? = null) {
        (if (course == null) ZfSelectionControl.from(source) else ZfSelectionControl.forCourse(course, source)).applyTo(target)
    }

    @JvmStatic fun courseControlValue(course: Course, source: Map<String, String>?): String =
        ZfSelectionControl.forCourse(course, source).primaryValue

    @JvmStatic fun saveControls(json: JSONObject, course: Course) {
        json.put("zfControlVersion", 2).put("_xkkz_xh", course._xkkz_xh)
        json.put("completeParams", JSONObject(course.completeParams.filterKeys {
            it.lowercase() !in setOf("cookie", "password", "userpassword", "csrftoken", "sessionuserkey")
        }))
    }

    @JvmStatic fun restoreControls(json: JSONObject, course: Course) {
        json.optJSONObject("completeParams")?.let { fields -> fields.keys().forEach { course.completeParams[it] = fields.optString(it) } }
        if (json.optInt("zfControlVersion") >= 2) {
            course._xkkz_xh = json.optString("_xkkz_xh")
        } else {
            val known = ZfSelectionControl.from(course.completeParams)
            known.applyTo(course)
            if (known.isEmpty) course.completeParams["zf_refresh_controls"] = "true"
        }
    }

    /** 括号归一化：全角括号转半角，仅用于比较，不改变原始存储 */
    @JvmStatic
    fun normalizeBrackets(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when (ch) {
                '（' -> sb.append('(')
                '）' -> sb.append(')')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * 为只接受一个控制字段的入口选择参数名，不用于课程/教学班上下文。
     * 判定顺序（值优先，避免 Display 空字段干扰）：
     * 1. firstXkkzId/xkkz_id 有非空值 → "xkkz_id"（旧版接口，如 tyust/zjut）
     * 2. firstXkkzXh/xkkz_xh 有非空值 → "xkkz_xh"（正方 V9，如 mnust）
     * 3. 值均空但存在 firstXkkzXh/xkkz_xh 键（如 V9 Display 响应的空字段）→ "xkkz_xh"
     * 4. 默认 "xkkz_id"（保持既有学校行为不变）
     */
    @JvmStatic
    fun detectXkkzKey(params: Map<String, String>?): String {
        if (params != null) {
            if (hasNonBlank(params, "firstXkkzId") || hasNonBlank(params, "xkkz_id")) {
                return "xkkz_id"
            }
            if (hasNonBlank(params, "firstXkkzXh") || hasNonBlank(params, "xkkz_xh")) {
                return "xkkz_xh"
            }
            if (params.containsKey("firstXkkzXh") || params.containsKey("xkkz_xh")) {
                return "xkkz_xh"
            }
        }
        return "xkkz_id"
    }

    private fun hasNonBlank(params: Map<String, String>, key: String): Boolean {
        return params[key]?.let { it.trim().isNotEmpty() } == true
    }

    /**
     * 与单字段入口的参数名配套取值，当前分类字段优先于 first*。
     */
    @JvmStatic
    fun resolveIndexXkkz(params: Map<String, String>?): String {
        return ZfSelectionControl.from(params).primaryValue.trim()
    }
}
