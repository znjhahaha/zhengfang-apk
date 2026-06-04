package com.tyust.course.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import com.tyust.course.model.SchoolConfig;
import com.tyust.course.model.Course;
import com.tyust.course.network.CourseApiClient;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class UserManager {
    private static final String TAG = "UserManager";
    private static UserManager instance;
    private SchoolConfig currentSchool;
    private String studentName;
    private String studentId;
    private boolean isLoggedIn = false;
    private boolean isDemoMode = false;
    private String savedCookie = "";
    private String sessionPassword = ""; // 内存中保存，用于会话期间自动刷新Cookie，不持久化
    private List<Course> selectedCourses = new ArrayList<>();

    private static final String PREFS_NAME = "course_selector_prefs";
    private static final String KEY_CUSTOM_SCHOOLS = "custom_schools";
    private static final String KEY_COOKIE = "saved_cookie";
    private static final String KEY_LOGGED_IN = "is_logged_in";
    private static final String KEY_STUDENT_NAME = "student_name";
    private static final String KEY_STUDENT_ID = "student_id";
    private static final String KEY_CURRENT_SCHOOL_ID = "current_school_id";
    private static final String KEY_COOKIE_SAVE_TIME = "cookie_save_time";
    private static final String KEY_USERNAME = "saved_username";
    private static final String KEY_LOGIN_MODE = "login_mode";

    private Context appContext;

    // 默认学校列表
    private final List<SchoolConfig> defaultSchools = new ArrayList<>();
    // 自定义学校列表
    private final List<SchoolConfig> customSchools = new ArrayList<>();

    private UserManager() {
        // 初始化默认学校
        defaultSchools.add(new SchoolConfig("tyust", "太原科技大学", "newjwc.tyust.edu.cn", "https"));
        defaultSchools.add(new SchoolConfig("zjut", "浙江工业大学", "www.gdjw.zjut.edu.cn", "http"));
        // 🔧 重要修复：这里不要直接赋值 currentSchool，等待 init() 时从 SharedPreferences 加载
    }

    public static synchronized UserManager getInstance() {
        if (instance == null) {
            instance = new UserManager();
        }
        return instance;
    }

    // 初始化 Context（在 Application 或 Activity 中调用）
    public void init(Context context) {
        this.appContext = context.getApplicationContext();
        loadCustomSchools();
        loadLoginState(); // 加载保存的登录状态
    }

    public void setCurrentSchool(SchoolConfig school) {
        this.currentSchool = school;
        saveLoginState(); // 保存学校选择
    }

    public SchoolConfig getCurrentSchool() {
        return currentSchool;
    }

    // 获取所有学校（默认 + 自定义）
    public List<SchoolConfig> getSupportedSchools() {
        List<SchoolConfig> all = new ArrayList<>();
        all.addAll(defaultSchools);
        all.addAll(customSchools);
        return all;
    }

    // 根据ID查找学校
    public SchoolConfig getSchoolById(String schoolId) {
        for (SchoolConfig school : getSupportedSchools()) {
            if (school.id.equals(schoolId)) {
                return school;
            }
        }
        return null;
    }

    // 添加自定义学校
    public void addCustomSchool(SchoolConfig school) {
        // 检查是否已存在
        for (SchoolConfig s : getSupportedSchools()) {
            if (s.domain.equals(school.domain)) {
                return; // 已存在，不添加
            }
        }
        customSchools.add(school);
        saveCustomSchools();
    }

    // 删除自定义学校
    public void removeCustomSchool(String schoolId) {
        customSchools.removeIf(s -> s.id.equals(schoolId));
        saveCustomSchools();
    }

    // 更新学校配置
    public void updateSchoolConfig(SchoolConfig updatedSchool) {
        // 更新自定义学校
        for (int i = 0; i < customSchools.size(); i++) {
            if (customSchools.get(i).id.equals(updatedSchool.id)) {
                customSchools.set(i, updatedSchool);
                saveCustomSchools();
                // 同时更新 currentSchool
                if (currentSchool != null && currentSchool.id.equals(updatedSchool.id)) {
                    currentSchool = updatedSchool;
                }
                return;
            }
        }

        // 更新默认学校
        for (int i = 0; i < defaultSchools.size(); i++) {
            if (defaultSchools.get(i).id.equals(updatedSchool.id)) {
                defaultSchools.set(i, updatedSchool);
                // 同时更新 currentSchool
                if (currentSchool != null && currentSchool.id.equals(updatedSchool.id)) {
                    currentSchool = updatedSchool;
                }
                return;
            }
        }
    }

    // 保存自定义学校到 SharedPreferences
    private void saveCustomSchools() {
        if (appContext == null)
            return;

        try {
            JSONArray arr = new JSONArray();
            for (SchoolConfig school : customSchools) {
                arr.put(school.toJson());
            }

            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(KEY_CUSTOM_SCHOOLS, arr.toString()).apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 从 SharedPreferences 加载自定义学校
    private void loadCustomSchools() {
        if (appContext == null)
            return;

        customSchools.clear();
        try {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String json = prefs.getString(KEY_CUSTOM_SCHOOLS, "[]");
            Log.d(TAG, "加载自定义学校 JSON: " + json);

            JSONArray arr = new JSONArray(json);

            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                SchoolConfig school = SchoolConfig.fromJson(obj);
                if (school != null && !school.domain.isEmpty()) {
                    customSchools.add(school);
                    Log.d(TAG, "加载自定义学校: id=" + school.id + ", name=" + school.name);
                }
            }
            Log.d(TAG, "自定义学校加载完成, 共 " + customSchools.size() + " 个");
        } catch (Exception e) {
            Log.e(TAG, "加载自定义学校失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ========== Cookie 和登录状态持久化 ==========

    // 保存登录状态到 SharedPreferences
    public void saveLoginState() {
        if (appContext == null)
            return;

        try {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();

            editor.putBoolean(KEY_LOGGED_IN, isLoggedIn);
            editor.putString(KEY_STUDENT_NAME, studentName != null ? studentName : "");
            editor.putString(KEY_STUDENT_ID, studentId != null ? studentId : "");
            editor.putString(KEY_COOKIE, savedCookie != null ? savedCookie : "");
            editor.putLong(KEY_COOKIE_SAVE_TIME, System.currentTimeMillis());

            if (currentSchool != null) {
                editor.putString(KEY_CURRENT_SCHOOL_ID, currentSchool.id);
            }

            editor.apply();
            Log.d(TAG, "登录状态已保存: isLoggedIn=" + isLoggedIn + ", student=" + studentName);
        } catch (Exception e) {
            Log.e(TAG, "保存登录状态失败: " + e.getMessage());
        }
    }

    // 从 SharedPreferences 加载登录状态
    public void loadLoginState() {
        if (appContext == null)
            return;

        try {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

            isLoggedIn = prefs.getBoolean(KEY_LOGGED_IN, false);
            studentName = prefs.getString(KEY_STUDENT_NAME, "");
            studentId = prefs.getString(KEY_STUDENT_ID, "");
            savedCookie = prefs.getString(KEY_COOKIE, "");

            String schoolId = prefs.getString(KEY_CURRENT_SCHOOL_ID, "");
            Log.d(TAG, "正在恢复学校, 保存的 schoolId=" + schoolId);

            if (!schoolId.isEmpty()) {
                SchoolConfig school = getSchoolById(schoolId);
                if (school != null) {
                    currentSchool = school;
                    Log.d(TAG, "学校恢复成功: " + currentSchool.name);
                } else {
                    Log.w(TAG, "找不到保存的学校 ID: " + schoolId + ", 可用学校: " + listSchoolIds());
                    // 保持默认学校不变
                }
            } else {
                Log.d(TAG, "没有保存的学校 ID，使用默认学校");
            }

            Log.d(TAG, "登录状态已加载: isLoggedIn=" + isLoggedIn + ", student=" + studentName + ", school="
                    + (currentSchool != null ? currentSchool.name : "null"));
        } catch (Exception e) {
            Log.e(TAG, "加载登录状态失败: " + e.getMessage());
        }
    }

    // 辅助方法：列出所有可用学校 ID（用于调试）
    private String listSchoolIds() {
        StringBuilder sb = new StringBuilder();
        for (SchoolConfig s : getSupportedSchools()) {
            if (sb.length() > 0)
                sb.append(", ");
            sb.append(s.id);
        }
        return sb.toString();
    }

    // 保存 Cookie
    public void saveCookie(String cookie) {
        this.savedCookie = cookie;
        saveLoginState();
    }

    public void savePasswordLogin(String username, String cookie, String password) {
        this.savedCookie = cookie;
        this.sessionPassword = password != null ? password : "";
        if (appContext != null) {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                    .putString(KEY_USERNAME, username)
                    .putString(KEY_LOGIN_MODE, "password")
                    .apply();
        }
        saveLoginState();
    }

    /** 获取会话期间保存的密码（仅内存，不持久化） */
    public String getSessionPassword() { return sessionPassword; }

    /** 是否可以通过密码模式自动刷新 Cookie */
    public boolean canAutoRelogin() {
        return "password".equals(getLoginMode())
                && !getUsername().isEmpty()
                && !sessionPassword.isEmpty()
                && currentSchool != null;
    }

    public String getUsername() {
        if (appContext == null) return "";
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_USERNAME, "");
    }

    public String getLoginMode() {
        if (appContext == null) return "cookie";
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_LOGIN_MODE, "cookie");
    }

    // 获取已保存的 Cookie
    public String getSavedCookie() {
        return savedCookie != null ? savedCookie : "";
    }

    // 检查是否有保存的 Cookie
    public boolean hasSavedCookie() {
        return savedCookie != null && !savedCookie.isEmpty();
    }

    // 清除登录状态（退出登录时调用）
    public void clearLoginState() {
        isLoggedIn = false;
        studentName = "";
        studentId = "";
        savedCookie = "";
        sessionPassword = "";

        if (appContext != null) {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                    .remove(KEY_LOGGED_IN)
                    .remove(KEY_STUDENT_NAME)
                    .remove(KEY_STUDENT_ID)
                    .remove(KEY_COOKIE)
                    .remove(KEY_COOKIE_SAVE_TIME)
                    .remove(KEY_USERNAME)
                    .remove(KEY_LOGIN_MODE)
                    .apply(); // 🔧 注意：这里不再 remove KEY_CURRENT_SCHOOL_ID，实现学校记忆
        }

        Log.d(TAG, "登录状态已清除");
    }

    // 🔧 别名方法，方便 Kotlin 调用
    public void logout() {
        clearLoginState();
    }

    // ========== 原有方法 ==========

    public void setLoggedIn(boolean loggedIn) {
        this.isLoggedIn = loggedIn;
        if (loggedIn) {
            saveLoginState();
        }
    }

    public boolean isLoggedIn() {
        return isLoggedIn;
    }

    public void setDemoMode(boolean demoMode) {
        this.isDemoMode = demoMode;
    }

    public boolean isDemoMode() {
        return isDemoMode;
    }

    public String getStudentName() {
        return studentName;
    }

    public void setStudentName(String studentName) {
        this.studentName = studentName;
        saveLoginState();
    }

    public String getStudentId() {
        return studentId;
    }

    public void setStudentId(String studentId) {
        this.studentId = studentId;
        saveLoginState();
    }
}
