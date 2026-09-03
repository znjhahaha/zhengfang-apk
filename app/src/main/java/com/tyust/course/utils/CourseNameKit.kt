package com.tyust.course.utils

/**
 * 课程名与选课参数工具集。
 *
 * 1. 全半角括号归一化：教务库课程名通常为半角括号（如"大学体育(三)"），
 *    而中文输入法默认输入全角括号（"大学体育（三）"），直接字符串比较会匹配失败。
 * 2. xkkz 参数名自适应：正方教务存在两套选课接口参数名——
 *    旧版用 xkkz_id（Index 页面 hidden input 为 firstXkkzId），
 *    新版 V9（如 mnust）用 xkkz_xh（Index 页面 hidden input 为 firstXkkzXh）。
 *    程序根据 Index 页面解析出的参数键自动判断该校使用哪套。
 */
object CourseNameKit {

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
     * 检测该校选课接口使用的 xkkz 参数名。
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
     * 从 Index 页面参数中解析首个有效的 xkkz 值。
     * 候选顺序：firstXkkzId / firstXkkzXh / xkkz_id / xkkz_xh。
     */
    @JvmStatic
    fun resolveIndexXkkz(params: Map<String, String>?): String {
        if (params == null) return ""
        for (key in listOf("firstXkkzId", "firstXkkzXh", "xkkz_id", "xkkz_xh")) {
            params[key]?.let { v ->
                if (v.trim().isNotEmpty()) return v.trim()
            }
        }
        return ""
    }
}
