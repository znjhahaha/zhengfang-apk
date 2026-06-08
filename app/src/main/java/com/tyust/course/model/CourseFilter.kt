package com.tyust.course.model

import java.net.URLEncoder

/**
 * 正方教务系统"自主选课"页面的服务端筛选条件。
 * 所有参数名和值均通过浏览器实际抓包验证。
 */
data class CourseFilter(
    // 开课学院 ID（如 "10011G", "10", "19", "20"）
    val kkbmIdList: List<String>? = null,
    // 课程类别代码: "01"=必修, "02"=限选, "03"=任选, "04"=辅修, "05"=实践, "06"=其他
    val kclbIdList: List<String>? = null,
    // 课程性质代码: "10"=通识选修课, "11"=微专业必修课, "3"=专业选修课, "6"=专业必修课, "7"=教学环节, "8"=通识必修课
    val kcxzdmList: List<String>? = null,
    // 课程归属代码: "1"=公共选修课, "2"=院级公共选修课
    val kcgsList: List<String>? = null,
    // 教学模式代码: "1"=双语教学, "2"=中文教学
    val jxmsList: List<String>? = null,
    // 上课星期: "1"~"7" (周一~周日)
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
     * 同时发送数组字段和合并字段，兼容页面里的 getConditions/getMergeConditions 两种形态。
     */
    fun toPostParams(): String {
        val parts = mutableListOf<String>()
        appendListParam(parts, "kkbm_id_list", kkbmIdList)
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

    /** 生成用于 UI 展示的筛选标签列表 */
    fun toDisplayTags(): List<String> {
        val tags = mutableListOf<String>()
        kclbIdList?.forEach { tags.add(CATEGORY_MAP[it] ?: it) }
        kcxzdmList?.forEach { tags.add(NATURE_MAP[it] ?: it) }
        sksjList?.forEach { tags.add(DAY_MAP[it] ?: "周$it") }
        skjcList?.forEach { tags.add("第${it}节") }
        ylList?.firstOrNull()?.let { if (it == "1") tags.add("有余量") else tags.add("无余量") }
        cxbjList?.firstOrNull()?.let { if (it == "1") tags.add("重修") else tags.add("非重修") }
        jxmsList?.forEach { tags.add(TEACHING_MODE_MAP[it] ?: it) }
        searchInput?.let { if (it.isNotBlank()) tags.add("\"$it\"") }
        return tags
    }

    companion object {
        val CATEGORY_MAP = mapOf(
            "01" to "必修", "02" to "限选", "03" to "任选",
            "04" to "辅修", "05" to "实践", "06" to "其他"
        )
        val NATURE_MAP = mapOf(
            "10" to "通识选修课", "11" to "微专业必修课", "3" to "专业选修课",
            "6" to "专业必修课", "7" to "教学环节", "8" to "通识必修课"
        )
        val DAY_MAP = mapOf(
            "1" to "周一", "2" to "周二", "3" to "周三",
            "4" to "周四", "5" to "周五", "6" to "周六", "7" to "周日"
        )
        val TEACHING_MODE_MAP = mapOf(
            "1" to "双语教学", "2" to "中文教学"
        )
        val BELONGING_MAP = mapOf(
            "1" to "公共选修课", "2" to "院级公共选修课"
        )

        // 所有可选分类的完整列表
        val ALL_CATEGORIES = CATEGORY_MAP.entries.map { FilterOption(it.key, it.value) }
        val ALL_NATURES = NATURE_MAP.entries.map { FilterOption(it.key, it.value) }
        val ALL_DAYS = DAY_MAP.entries.map { FilterOption(it.key, it.value) }
        val ALL_PERIODS = (1..10).map { FilterOption(it.toString(), it.toString()) }
        val ALL_TEACHING_MODES = TEACHING_MODE_MAP.entries.map { FilterOption(it.key, it.value) }
        val YES_NO_AVAILABILITY = listOf(FilterOption("1", "有"), FilterOption("0", "无"))
        val YES_NO_RETAKE = listOf(FilterOption("0", "否"), FilterOption("1", "是"))
        val ALL_BELONGINGS = BELONGING_MAP.entries.map { FilterOption(it.key, it.value) }

        private fun appendListParam(parts: MutableList<String>, key: String, list: List<String>?) {
            val values = list.orEmpty().filter { it.isNotBlank() }
            values.forEachIndexed { i, v ->
                parts.add("${enc(key)}[${i}]=${enc(v)}")
            }
            if (values.isNotEmpty()) {
                parts.add("${enc(key)}=${enc(values.joinToString(","))}")
            }
        }

        private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    }
}

data class FilterOption(val key: String, val label: String)