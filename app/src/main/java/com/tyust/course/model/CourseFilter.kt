package com.tyust.course.model

import java.net.URLEncoder

/**
 * 正方教务系统"自主选课"页面的服务端筛选条件。
 * 所有参数名和值均通过浏览器实际抓包验证。
 */
data class CourseFilter(
    // 开课学院 ID，来自自主选课页面动态筛选项
    val kkbmIdList: List<String>? = null,
    // 年级，来自自主选课页面动态筛选项
    val njdmIdList: List<String>? = null,
    // 学院，来自自主选课页面动态筛选项
    val jgIdList: List<String>? = null,
    // 专业，来自自主选课页面动态筛选项
    val zyhIdList: List<String>? = null,
    // 课程类别，来自自主选课页面动态筛选项
    val kclbIdList: List<String>? = null,
    // 课程性质，来自自主选课页面动态筛选项
    val kcxzdmList: List<String>? = null,
    // 课程归属，来自自主选课页面动态筛选项
    val kcgsList: List<String>? = null,
    // 教学模式，来自自主选课页面动态筛选项
    val jxmsList: List<String>? = null,
    // 上课星期，来自自主选课页面动态筛选项
    val sksjList: List<String>? = null,
    // 上课节次: "1"~"10"
    val skjcList: List<String>? = null,
    // 是否重修: "0"=否, "1"=是
    val cxbjList: List<String>? = null,
    // 有无余量: "0"=无, "1"=有
    val ylList: List<String>? = null,
    // 教学班名称（文本输入）
    val jxbmcList: List<String>? = null,
    // 关键词搜索
    val searchInput: String? = null
) {
    /**
     * 将筛选条件转为 URL-encoded POST body 片段。
     * 与自主选课页面查询按钮一致：只发送 searchBox.getConditions() 形态的数组字段。
     */
    fun toPostParams(): String {
        val parts = mutableListOf<String>()
        appendListParam(parts, "kkbm_id_list", kkbmIdList)
        appendListParam(parts, "njdm_id_list", njdmIdList)
        appendListParam(parts, "jg_id_list", jgIdList)
        appendListParam(parts, "zyh_id_list", zyhIdList)
        appendListParam(parts, "kclb_id_list", kclbIdList)
        appendListParam(parts, "kcxzdm_list", kcxzdmList)
        appendListParam(parts, "kcgs_list", kcgsList)
        appendListParam(parts, "jxms_list", jxmsList)
        appendListParam(parts, "sksj_list", sksjList)
        appendListParam(parts, "skjc_list", skjcList)
        appendListParam(parts, "cxbj_list", cxbjList)
        appendListParam(parts, "yl_list", ylList)
        appendListParam(parts, "jxbmc_list", jxbmcList)
        if (!searchInput.isNullOrBlank()) {
            parts.add("searchInput=${enc(searchInput)}")
        }
        return parts.joinToString("&")
    }

    fun isEmpty(): Boolean =
        kkbmIdList.isNullOrEmpty() &&
        njdmIdList.isNullOrEmpty() &&
        jgIdList.isNullOrEmpty() &&
        zyhIdList.isNullOrEmpty() &&
        kclbIdList.isNullOrEmpty() &&
        kcxzdmList.isNullOrEmpty() &&
        kcgsList.isNullOrEmpty() &&
        jxmsList.isNullOrEmpty() &&
        sksjList.isNullOrEmpty() &&
        skjcList.isNullOrEmpty() &&
        cxbjList.isNullOrEmpty() &&
        ylList.isNullOrEmpty() &&
        jxbmcList.isNullOrEmpty() &&
        searchInput.isNullOrBlank()

    companion object {
        private fun appendListParam(parts: MutableList<String>, key: String, list: List<String>?) {
            val values = list.orEmpty().filter { it.isNotBlank() }
            values.forEachIndexed { i, v ->
                parts.add("${enc("$key[$i]")}=${enc(v)}")
            }
        }

        private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    }
}

data class FilterOption(val key: String, val label: String)