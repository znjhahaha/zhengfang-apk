package com.tyust.course.fragment

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
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
                    onAnnouncementHistory = { 
                        Toast.makeText(context, "请在主架构页面查看历史公告", Toast.LENGTH_SHORT).show()
                    },
                    onCookieConfig = { handleCookieConfig() },
                    onClearCache = { handleClearCache() },
                    onCheckUpdate = { /* Not used in fragment, handled by SettingsRoute */ },
                    onAbout = { handleAbout() },
                    onLogout = { handleLogout() }
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
        studentId = id ?: "--"
        schoolName = school?.name ?: "未选择"
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
        AlertDialog.Builder(context)
            .setTitle("关于")
            .setMessage(
                "正方教务工具 Android版\n\n" +
                "版本: 1.0.0\n\n" +
                "功能特性:\n" +
                "• 课程信息查询\n" +
                "• 智能抢课Pro+\n" +
                "• 课表查看\n" +
                "• 成绩查询\n\n" +
                "本应用仅供学习交流使用"
            )
            .setPositiveButton("确定", null)
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
