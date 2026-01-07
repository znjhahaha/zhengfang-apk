package com.tyust.course.model

/**
 * 考试安排信息
 */
data class ExamInfo(
    val kcmc: String,       // 课程名称
    val kssj: String,       // 考试时间
    val cdmc: String,       // 考场名称
    val zwh: String,        // 座位号
    val ksmc: String,       // 考试名称 (期末/期中)
    val khfs: String,       // 考核方式
    val xf: String,         // 学分
    val jsxx: String,       // 教师信息
    val jxbmc: String,      // 教学班名称
    val sksj: String,       // 上课时间
    val jxdd: String,       // 教学地点
    val cxbj: String,       // 重修标记
    val zxbj: String        // 缓考标记
)
