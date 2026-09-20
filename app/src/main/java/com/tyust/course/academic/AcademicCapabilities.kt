package com.tyust.course.academic

import com.tyust.course.model.SchoolConfig

data class AcademicSystemSupport(val system: AcademicSystem, val name: String, val login: String, val limits: String)

object AcademicCapabilities {
    val FOUR_SYSTEMS: String get() = "支持" + systems.joinToString("、") { it.name }
    const val ACCOUNT_LIMIT = "可添加不同学校，所有学校合计最多 3 个学生账号；同一时间只运行当前账号的抢课任务。"
    const val SCHOOL_LIMIT = "选课时间、名额、学分、年级专业及课程冲突由学校决定。学校未开放的操作不能提交，未公布的数据不会补造。"
    val systems = listOf(
        AcademicSystemSupport(AcademicSystem.ZF, "新正方", "支持账密、图片验证码及网页登录；定制统一认证以学校登录流程为准。",
            "支持串行或最多 2 门课程并行尝试。筛选项、成绩分项与可选教学班取自学校响应。"),
        AcademicSystemSupport(AcademicSystem.ZF_OLD, "旧正方", "支持账号密码、图片验证码获取与刷新；定制统一认证保留网页登录入口。",
            "App 为保护学校登录状态，按账号串行提交。体育课、选退课和历史学期以学校开放的入口为准。"),
        AcademicSystemSupport(AcademicSystem.QZ, "新强智", "支持直接账密登录；额外的人机验证或统一认证需在学校网页完成。",
            "App 为保护学校登录状态，按账号串行提交。轮次与课程类别来自学校，轮次未开放时仍可查询已选及学习数据。"),
        AcademicSystemSupport(AcademicSystem.QZ_OLD, "旧强智", "支持账号密码、图片验证码获取与刷新；定制统一认证保留网页登录入口。",
            "App 为保护学校登录状态，按账号串行提交。学校菜单、开放轮次与操作权限决定可用查询和选退课。")
    )
    val selectableSystems: List<AcademicSystem> = listOf(AcademicSystem.AUTO) + systems.map { it.system }
    fun selectionIndex(id: String?): Int = selectableSystems.indexOf(system(id) ?: AcademicSystem.AUTO).coerceAtLeast(0)
    fun selectionLabel(type: AcademicSystem): String = if (type == AcademicSystem.AUTO) "自动识别" else name(type.id)
    fun selectedTypeId(currentId: String, selected: AcademicSystem): String =
        if (currentId == AcademicSystem.LEGACY_ZF.id && selected == AcademicSystem.ZF) currentId else selected.id
    fun system(id: String?): AcademicSystem? = AcademicSystem.fromId(id)?.let {
        if (it == AcademicSystem.LEGACY_ZF) AcademicSystem.ZF else it
    }
    fun support(id: String?): AcademicSystemSupport? = systems.firstOrNull { it.system == system(id) }
    fun name(id: String?): String = support(id)?.name ?: "自动识别教务系统"
    fun supportsParallel(school: SchoolConfig): Boolean = !com.tyust.course.academic.plugin.AcademicProviderRegistry.hasBinding(school) && system(school.academicSystem) == AcademicSystem.ZF
    fun queueLimit(school: SchoolConfig): String = if (supportsParallel(school))
        "当前账号可串行或最多 2 门并行尝试；学校选课规则始终生效。" else
        "${name(school.academicSystem)}按账号串行提交，避免学校会话或表单参数互相覆盖。"
}
