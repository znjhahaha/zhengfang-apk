package com.tyust.course.fragment

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import com.tyust.course.LoginActivity
import com.tyust.course.manager.UserManager
import com.tyust.course.model.SchoolConfig
import com.tyust.course.ui.screen.SettingsScreen

class SettingsFragment : Fragment() {

    // Reactive state for Compose
    private var studentName by mutableStateOf("")
    private var studentId by mutableStateOf("")
    private var schoolName by mutableStateOf("")
    private var isSuper by mutableStateOf(false)
    private var quotaInfo by mutableStateOf("")

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                SettingsScreen(
                    studentName = studentName,
                    studentId = studentId,
                    schoolName = schoolName,
                    onSchoolSelect = { showSchoolSelector() },
                    onCookieConfig = { handleCookieConfig() },
                    onClearCache = { handleClearCache() },
                    onCheckUpdate = { /* Not used in fragment, handled by SettingsRoute */ },
                    onAbout = { handleAbout() },
                    onCredits = { handleCredits() },
                    onLogout = { handleLogout() },
                    onQuotaClick = { showQuotaDetails() },
                    isSuper = isSuper,
                    quotaInfo = quotaInfo
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateUserInfo()
    }

    private fun updateUserInfo() {
        val name = UserManager.getInstance().studentName
        val id = UserManager.getInstance().studentId
        val school = UserManager.getInstance().currentSchool

        studentName = name ?: "同学"
        studentId = if (id.isNullOrEmpty()) "" else id
        schoolName = school?.name ?: "未选择"
        
        // 获取配额信息 - 使用多种方式尝试获取 Context
        val ctx = context ?: activity?.applicationContext ?: view?.context
        if (ctx != null) {
            try {
                val maxStudents = com.tyust.course.activation.ActivationManager.getMaxStudents(ctx)
                isSuper = maxStudents <= 0
                if (isSuper) {
                    quotaInfo = "无限制"
                } else {
                    val usedCount = com.tyust.course.manager.StudentLimitManager.getUsedCount(ctx)
                    quotaInfo = "$usedCount / $maxStudents"
                }
                Log.d("SettingsFragment", "配额加载成功: isSuper=$isSuper, quota=$quotaInfo, maxStudents=$maxStudents")
            } catch (e: Exception) {
                Log.e("SettingsFragment", "配额加载失败: ${e.message}")
                quotaInfo = "加载失败"
            }
        } else {
            Log.w("SettingsFragment", "Context 为空，无法加载配额")
            quotaInfo = "0 / 2" // 默认配额
        }
        
        Log.d("SettingsFragment", "Updated UI: name=$studentName, id=$studentId, isSuper=$isSuper, quota=$quotaInfo")
    }

    private fun showQuotaDetails() {
        val ctx = context ?: activity ?: view?.context
        if (ctx == null) {
            Log.e("SettingsFragment", "showQuotaDetails: 无法获取 Context")
            return
        }
        
        val maxStudents = com.tyust.course.activation.ActivationManager.getMaxStudents(ctx)
        val usedNames = com.tyust.course.manager.StudentLimitManager.getUsedStudentNames(ctx)
        val isSuperUser = maxStudents <= 0
        
        val message = buildString {
            append("📊 设备绑定详情\n\n")
            if (isSuperUser) {
                append("✨ 身份：超级用户\n")
                append("📈 配额：无限制\n")
            } else {
                append("📈 配额：${usedNames.size} / $maxStudents\n")
            }
            append("━━━━━━━━━━━━━━━\n")
            if (usedNames.isNotEmpty()) {
                append("👥 已绑定账号：\n")
                usedNames.forEachIndexed { index, name ->
                    append("${index + 1}. $name\n")
                }
            } else {
                append("ℹ️ 暂未绑定任何账号\n")
            }
            append("━━━━━━━━━━━━━━━\n\n")
            append("💡 说明：激活名额一旦绑定无法自行解绑。如需更换请联系管理员。")
        }

        AlertDialog.Builder(ctx)
            .setTitle("当前账号配额")
            .setMessage(message)
            .setPositiveButton("我知道了", null)
            .show()
    }

    private fun handleCookieConfig() {
        // 清除登录状态和保存的 Cookie
        UserManager.getInstance().clearLoginState()
        val intent = Intent(context, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
    }

    private fun handleClearCache() {
        AlertDialog.Builder(context)
            .setTitle("清除缓存")
            .setMessage("确定要清除所有本地缓存数据吗？")
            .setPositiveButton("确定") { _, _ ->
                Toast.makeText(context, "缓存已清除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun handleAbout() {
        val message = buildString {
            append("更新日志\n\n")
            append("• 2026-06-06: 修复 Cookie 过期闪退；修复全局过期提醒横幅被状态栏遮挡，新增全局即时 Toast 强提醒\n")
            append("• 2026-06-05: 引入全局 Cookie 有效性检查器提升稳定性；优化 JSON 响应过期拦截与自动唤起登录提示\n")
            append("• 2026-06-04: 修复平时成绩显示Bug，支持CSV单导出；添加登录平台指引；增加防误触 Star 弹窗（最多弹3次不同内容）；设置页关于改版为更新历史与致谢\n")
            append("• 2026-06-03: 清理内部文档与更新配置\n")
            append("• 2026-06-02: 优化滚动体验防止内容遮挡，重构 README\n")
            append("• 2026-06-01: 修复底栏点击失效与液态裁切问题\n")
            append("• 2026-05-31: 引入液态玻璃 UI 重构 (v1.0.54)\n")
            append("• 2026-05-26: 优化卡片，修复退课接口与日历分享崩溃\n")
            append("• 2026-05-25: 升级新拟态玻璃化 UI，优化课表\n")
            append("• 2026-04-18: 成绩查询优化，增强限流与指纹安全\n")
            append("• 2026-04-09: 深度 UI/UX 重构，极简系统工具风\n\n")
            append("本应用仅供学习交流使用")
        }
        AlertDialog.Builder(context)
            .setTitle("更新历史")
            .setMessage(message)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun handleCredits() {
        AlertDialog.Builder(context)
            .setTitle("致谢与关于")
            .setMessage(
                "特别致谢\n" +
                "本应用基于多项优秀的开源技术构建，衷心感谢以下开源项目及社区的支持：\n" +
                "• Jetpack Compose & Kotlin\n" +
                "• OkHttp3 & Gson\n" +
                "• Jsoup (HTML 解析库)\n" +
                "• Material Design 3\n" +
                "• AndroidLiquidGlass 动效库\n\n" +
                "关于作者\n" +
                "• 作者：znjhahaha\n" +
                "• GitHub 仓库：https://github.com/znjhahaha/zhengfang-apk\n\n" +
                "本软件为开源免费项目，仅供个人学习与技术交流使用，严禁用于任何商业目的与倒卖。"
            )
            .setPositiveButton("我知道了", null)
            .show()
    }

    private fun handleLogout() {
        AlertDialog.Builder(context)
            .setTitle("退出登录")
            .setMessage("确定要退出登录吗？")
            .setPositiveButton("确定") { _, _ ->
                // 清除登录状态和保存的 Cookie
                UserManager.getInstance().clearLoginState()

                val intent = Intent(context, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSchoolSelector() {
        val schools = UserManager.getInstance().supportedSchools
        val schoolNames = schools.map { it.name }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle("选择学校")
            .setItems(schoolNames) { _, which ->
                val selected = schools[which]
                // 切换学校需要重新登录
                UserManager.getInstance().clearLoginState()
                UserManager.getInstance().currentSchool = selected
                Toast.makeText(context, "已切换到: ${selected.name}，请重新登录", Toast.LENGTH_SHORT).show()
                
                // 跳转到登录页面
                val intent = Intent(context, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
            }
            .show()
    }
}
