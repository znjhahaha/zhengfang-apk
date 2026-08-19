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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UserManager {
    private static final String TAG = "UserManager";
    private static UserManager instance;
    private SchoolConfig currentSchool;
    private String studentName;
    private String studentId;
    private boolean isLoggedIn = false;
    private boolean isDemoMode = false;
    private String savedCookie = "";
    // 内存缓存，避免每次续期都过一次 Keystore 解密；真正的落盘在 CredentialStore
    private String sessionPassword = "";
    private String currentAccountKey = "";
    private final Map<String, String> sessionPasswords = new HashMap<>();
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
    private static final String KEY_ACCOUNTS = "saved_accounts";
    private static final String KEY_CURRENT_ACCOUNT_KEY = "current_account_key";

    private Context appContext;

    // 默认学校列表
    private final List<SchoolConfig> defaultSchools = new ArrayList<>();
    // 自定义学校列表
    private final List<SchoolConfig> customSchools = new ArrayList<>();

    public static class AccountRecord {
        public String key = "";
        public String schoolId = "";
        public String schoolName = "";
        public String studentName = "";
        public String studentId = "";
        public String username = "";
        public String loginMode = "cookie";
        public String cookie = "";
        public long cookieSaveTime = 0L;

        public boolean isPasswordMode() {
            return "password".equals(loginMode);
        }

        public String getDisplayName() {
            if (studentName != null && !studentName.isEmpty()) return studentName;
            if (username != null && !username.isEmpty()) return username;
            if (studentId != null && !studentId.isEmpty()) return studentId;
            return "同学";
        }

        public String getAccountIdText() {
            if (studentId != null && !studentId.isEmpty()) return studentId;
            if (username != null && !username.isEmpty()) return username;
            return "未记录学号";
        }
    }

    private UserManager() {
        // 初始化默认学校
        defaultSchools.add(new SchoolConfig("tyust", "太原科技大学", "newjwc.tyust.edu.cn", "https"));
        defaultSchools.add(new SchoolConfig("zjut", "浙江工业大学", "www.gdjw.zjut.edu.cn", "http"));
        // 重要修复：这里不要直接赋值 currentSchool，等待 init() 时从 SharedPreferences 加载
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
        if (!isDemoMode) {
            loadLoginState(); // 演示会话只存活在当前进程，不允许持久化状态覆盖它
        }
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

    // ========== Cookie、登录状态与账号记录持久化 ==========

    // 保存登录状态到 SharedPreferences
    public void saveLoginState() {
        if (appContext == null || isDemoMode)
            return;

        try {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();

            editor.putBoolean(KEY_LOGGED_IN, isLoggedIn);
            editor.putString(KEY_STUDENT_NAME, studentName != null ? studentName : "");
            editor.putString(KEY_STUDENT_ID, studentId != null ? studentId : "");
            editor.putString(KEY_COOKIE, savedCookie != null ? savedCookie : "");
            editor.putLong(KEY_COOKIE_SAVE_TIME, System.currentTimeMillis());
            if (currentAccountKey != null && !currentAccountKey.isEmpty()) {
                editor.putString(KEY_CURRENT_ACCOUNT_KEY, currentAccountKey);
            }

            if (currentSchool != null) {
                editor.putString(KEY_CURRENT_SCHOOL_ID, currentSchool.id);
            }

            editor.apply();

            if (isLoggedIn && currentSchool != null && savedCookie != null && !savedCookie.isEmpty()) {
                upsertCurrentAccountRecord();
            }

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

            migrateLegacyAccountIfNeeded(prefs);

            currentAccountKey = prefs.getString(KEY_CURRENT_ACCOUNT_KEY, "");
            if (!currentAccountKey.isEmpty()) {
                AccountRecord record = findAccountRecord(currentAccountKey);
                if (record != null) {
                    applyAccountRecord(record, false);
                }
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

    // 保存 Cookie。默认只更新当前会话 Cookie，不改变登录模式。
    public void saveCookie(String cookie) {
        this.savedCookie = cookie != null ? cookie : "";
        saveLoginState();
        refreshRuntimeForCurrentAccount();
    }

    public void saveCookieLogin(String cookie) {
        this.savedCookie = cookie != null ? cookie : "";
        this.sessionPassword = "";
        currentAccountKey = buildAccountKey(currentSchool, "", studentId, studentName);
        if (appContext != null) {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                    .putString(KEY_LOGIN_MODE, "cookie")
                    .remove(KEY_USERNAME)
                    .apply();
        }
        saveLoginState();
        refreshRuntimeForCurrentAccount();
    }

    public void savePasswordLogin(String username, String cookie, String password) {
        this.savedCookie = cookie != null ? cookie : "";
        this.sessionPassword = password != null ? password : "";
        String key = buildAccountKey(currentSchool, username, studentId, studentName);
        if (!key.isEmpty()) {
            currentAccountKey = key;
            if (!this.sessionPassword.isEmpty()) {
                sessionPasswords.put(key, this.sessionPassword);
                // 落盘（Keystore 加密）：冷启动后仍能静默续期，这是"账号永久保存"的落点
                if (appContext != null) {
                    CredentialStore.INSTANCE.save(appContext, key, this.sessionPassword);
                }
            }
        }
        if (appContext != null) {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                    .putString(KEY_USERNAME, username != null ? username : "")
                    .putString(KEY_LOGIN_MODE, "password")
                    .apply();
        }
        saveLoginState();
    }

    /**
     * 当前账号的密码。内存缓存 miss 就回 {@link CredentialStore} 解密读取，
     * 所以杀进程重开之后依然拿得到 —— 会话失效时的自动续期靠的就是这一条。
     */
    public String getAccountPassword() {
        String key = currentAccountKey;
        if (key != null && !key.isEmpty()) {
            String cached = sessionPasswords.get(key);
            if (cached != null && !cached.isEmpty()) return cached;
            if (appContext != null) {
                String stored = CredentialStore.INSTANCE.load(appContext, key);
                if (stored != null && !stored.isEmpty()) {
                    sessionPasswords.put(key, stored);
                    sessionPassword = stored;
                    return stored;
                }
            }
        }
        return sessionPassword != null ? sessionPassword : "";
    }

    /** 指定账号是否有已保存的密码（账号管理界面用来决定要不要显示"删除密码"）。 */
    public boolean hasSavedPassword(String accountKey) {
        if (accountKey == null || accountKey.isEmpty()) return false;
        String cached = sessionPasswords.get(accountKey);
        if (cached != null && !cached.isEmpty()) return true;
        return appContext != null && CredentialStore.INSTANCE.has(appContext, accountKey);
    }

    /** 只删密码，账号记录与 Cookie 保留：之后仍能用这个账号，但不再自动续期。 */
    public void deletePassword(String accountKey) {
        if (accountKey == null || accountKey.isEmpty()) return;
        sessionPasswords.remove(accountKey);
        if (accountKey.equals(currentAccountKey)) {
            sessionPassword = "";
        }
        if (appContext != null) {
            CredentialStore.INSTANCE.remove(appContext, accountKey);
        }
        Log.d(TAG, "已删除账号密码: " + accountKey);
    }

    /**
     * 彻底删除一个账号：账号记录、已存密码、运行期 Cookie。
     *
     * @return 该账号的 storage key，方便调用方接着清它的本地缓存
     *         （{@code CourseCacheManager.clearAccountCache}）；账号不存在时返回空串。
     */
    public String deleteAccount(String accountKey) {
        if (accountKey == null || accountKey.isEmpty()) return "";
        String storageKey = toStorageKey(accountKey);

        List<AccountRecord> records = loadAccountRecords();
        boolean removed = records.removeIf(record -> accountKey.equals(record.key));
        if (removed) {
            saveAccountRecords(records);
        }

        deletePassword(accountKey);

        try {
            CourseApiClient.getInstance().clearCookies(storageKey);
        } catch (Exception e) {
            Log.w(TAG, "清理账号 Cookie 失败: " + e.getMessage());
        }

        Log.d(TAG, "已删除账号: " + accountKey + " (removed=" + removed + ")");
        return storageKey;
    }

    /** 是否可以通过密码模式自动刷新 Cookie */
    public boolean canAutoRelogin() {
        return "password".equals(getLoginMode())
                && !getUsername().isEmpty()
                && !getAccountPassword().isEmpty()
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

    private void migrateLegacyAccountIfNeeded(SharedPreferences prefs) {
        try {
            if (currentSchool == null || savedCookie == null || savedCookie.isEmpty()) return;
            List<AccountRecord> accounts = loadAccountRecords();
            if (!accounts.isEmpty()) return;

            AccountRecord record = new AccountRecord();
            record.schoolId = currentSchool.id;
            record.schoolName = currentSchool.name;
            record.studentName = studentName != null ? studentName : "";
            record.studentId = studentId != null ? studentId : "";
            record.username = prefs.getString(KEY_USERNAME, "");
            record.loginMode = prefs.getString(KEY_LOGIN_MODE, "cookie");
            record.cookie = savedCookie;
            record.cookieSaveTime = prefs.getLong(KEY_COOKIE_SAVE_TIME, System.currentTimeMillis());
            record.key = buildAccountKey(currentSchool, record.username, record.studentId, record.studentName);
            if (record.key.isEmpty()) return;

            accounts.add(record);
            saveAccountRecords(accounts);
            currentAccountKey = record.key;
            prefs.edit().putString(KEY_CURRENT_ACCOUNT_KEY, currentAccountKey).apply();
            Log.d(TAG, "已迁移旧版单账号状态: " + record.studentName);
        } catch (Exception e) {
            Log.w(TAG, "迁移旧版账号状态失败: " + e.getMessage());
        }
    }

    private String buildAccountKey(SchoolConfig school, String username, String studentId, String studentName) {
        if (school == null || school.id == null || school.id.isEmpty()) return "";
        String identity = firstNotBlank(username, studentId, studentName);
        if (identity.isEmpty()) return "";
        return school.id + "::" + identity.trim();
    }

    private String firstNotBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private JSONObject accountToJson(AccountRecord record) throws Exception {
        JSONObject obj = new JSONObject();
        obj.put("key", record.key != null ? record.key : "");
        obj.put("schoolId", record.schoolId != null ? record.schoolId : "");
        obj.put("schoolName", record.schoolName != null ? record.schoolName : "");
        obj.put("studentName", record.studentName != null ? record.studentName : "");
        obj.put("studentId", record.studentId != null ? record.studentId : "");
        obj.put("username", record.username != null ? record.username : "");
        obj.put("loginMode", record.loginMode != null ? record.loginMode : "cookie");
        obj.put("cookie", record.cookie != null ? record.cookie : "");
        obj.put("cookieSaveTime", record.cookieSaveTime);
        return obj;
    }

    private AccountRecord accountFromJson(JSONObject obj) {
        AccountRecord record = new AccountRecord();
        record.key = obj.optString("key", "");
        record.schoolId = obj.optString("schoolId", "");
        record.schoolName = obj.optString("schoolName", "");
        record.studentName = obj.optString("studentName", "");
        record.studentId = obj.optString("studentId", "");
        record.username = obj.optString("username", "");
        record.loginMode = obj.optString("loginMode", "cookie");
        record.cookie = obj.optString("cookie", "");
        record.cookieSaveTime = obj.optLong("cookieSaveTime", 0L);
        if (record.key.isEmpty()) {
            SchoolConfig school = getSchoolById(record.schoolId);
            record.key = buildAccountKey(school, record.username, record.studentId, record.studentName);
        }
        return record;
    }

    private List<AccountRecord> loadAccountRecords() {
        List<AccountRecord> records = new ArrayList<>();
        if (appContext == null) return records;
        try {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String json = prefs.getString(KEY_ACCOUNTS, "[]");
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                AccountRecord record = accountFromJson(arr.getJSONObject(i));
                if (record.key != null && !record.key.isEmpty()) {
                    records.add(record);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "加载账号列表失败: " + e.getMessage());
        }
        return records;
    }

    private void saveAccountRecords(List<AccountRecord> records) {
        if (appContext == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (AccountRecord record : records) {
                arr.put(accountToJson(record));
            }
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(KEY_ACCOUNTS, arr.toString()).apply();
        } catch (Exception e) {
            Log.e(TAG, "保存账号列表失败: " + e.getMessage());
        }
    }

    private AccountRecord buildCurrentAccountRecord() {
        if (currentSchool == null) return null;
        AccountRecord record = new AccountRecord();
        record.schoolId = currentSchool.id;
        record.schoolName = currentSchool.name;
        record.studentName = studentName != null ? studentName : "";
        record.studentId = studentId != null ? studentId : "";
        record.username = getUsername();
        record.loginMode = getLoginMode();
        record.cookie = savedCookie != null ? savedCookie : "";
        record.cookieSaveTime = System.currentTimeMillis();
        record.key = buildAccountKey(currentSchool, record.username, record.studentId, record.studentName);
        return record.key.isEmpty() ? null : record;
    }

    private void upsertCurrentAccountRecord() {
        AccountRecord record = buildCurrentAccountRecord();
        if (record == null) return;
        List<AccountRecord> records = loadAccountRecords();
        boolean replaced = false;
        for (int i = 0; i < records.size(); i++) {
            if (record.key.equals(records.get(i).key)) {
                records.set(i, record);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            records.add(record);
        }
        currentAccountKey = record.key;
        saveAccountRecords(records);
        if (appContext != null) {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(KEY_CURRENT_ACCOUNT_KEY, currentAccountKey).apply();
        }
    }

    private AccountRecord findAccountRecord(String accountKey) {
        if (accountKey == null || accountKey.isEmpty()) return null;
        for (AccountRecord record : loadAccountRecords()) {
            if (accountKey.equals(record.key)) return record;
        }
        return null;
    }

    public List<AccountRecord> getSavedAccounts() {
        return new ArrayList<>(loadAccountRecords());
    }

    public List<AccountRecord> getAccountsForCurrentSchool() {
        List<AccountRecord> result = new ArrayList<>();
        if (currentSchool == null) return result;
        for (AccountRecord record : loadAccountRecords()) {
            if (currentSchool.id.equals(record.schoolId)) {
                result.add(record);
            }
        }
        return result;
    }

    public String getCurrentAccountKey() {
        if (currentAccountKey == null || currentAccountKey.isEmpty()) {
            currentAccountKey = buildAccountKey(currentSchool, getUsername(), studentId, studentName);
        }
        return currentAccountKey != null ? currentAccountKey : "";
    }

    public String getCurrentAccountStorageKey() {
        String key = getCurrentAccountKey();
        if (key.isEmpty() && currentSchool != null) {
            key = currentSchool.id + "::" + firstNotBlank(getUsername(), studentId, studentName, "default");
        }
        return toStorageKey(key);
    }

    /** 账号 key → 运行期/缓存用的 storage key。两处必须走同一条规则，否则清不干净。 */
    private String toStorageKey(String accountKey) {
        String key = accountKey != null ? accountKey : "";
        if (key.isEmpty()) key = "default";
        return key.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    public boolean switchToAccount(String accountKey) {
        AccountRecord record = findAccountRecord(accountKey);
        if (record == null) return false;
        return applyAccountRecord(record, true);
    }

    private boolean applyAccountRecord(AccountRecord record, boolean persist) {
        if (record == null) return false;
        SchoolConfig school = getSchoolById(record.schoolId);
        if (school == null) {
            Log.w(TAG, "切换账号失败，找不到学校: " + record.schoolId);
            return false;
        }

        currentSchool = school;
        studentName = record.studentName != null ? record.studentName : "";
        studentId = record.studentId != null ? record.studentId : "";
        savedCookie = record.cookie != null ? record.cookie : "";
        currentAccountKey = record.key != null ? record.key : "";
        isLoggedIn = true;
        isDemoMode = false;
        // 密码从内存缓存回填，miss 就读 Keystore —— loadLoginState() 也走这里，
        // 所以这一行就是"杀进程重开后仍能自动续期"的关键。
        String cachedPassword = sessionPasswords.get(currentAccountKey);
        if (cachedPassword == null || cachedPassword.isEmpty()) {
            if (appContext != null && !currentAccountKey.isEmpty()) {
                cachedPassword = CredentialStore.INSTANCE.load(appContext, currentAccountKey);
                if (cachedPassword != null && !cachedPassword.isEmpty()) {
                    sessionPasswords.put(currentAccountKey, cachedPassword);
                }
            }
        }
        sessionPassword = cachedPassword != null ? cachedPassword : "";

        if (persist && appContext != null) {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                    .putBoolean(KEY_LOGGED_IN, true)
                    .putString(KEY_STUDENT_NAME, studentName)
                    .putString(KEY_STUDENT_ID, studentId)
                    .putString(KEY_COOKIE, savedCookie)
                    .putString(KEY_CURRENT_SCHOOL_ID, school.id)
                    .putString(KEY_CURRENT_ACCOUNT_KEY, currentAccountKey)
                    .putString(KEY_USERNAME, record.username != null ? record.username : "")
                    .putString(KEY_LOGIN_MODE, record.loginMode != null ? record.loginMode : "cookie")
                    .putLong(KEY_COOKIE_SAVE_TIME, System.currentTimeMillis())
                    .apply();
        }

        refreshRuntimeForCurrentAccount();
        Log.d(TAG, "已切换账号: " + studentName + " @ " + school.name);
        return true;
    }

    public void refreshRuntimeForCurrentAccount() {
        try {
            CourseApiClient apiClient = CourseApiClient.getInstance();
            apiClient.clearDisplayParamsCache();
            if (currentSchool != null && savedCookie != null && !savedCookie.isEmpty()) {
                apiClient.setCookie(currentSchool.getBaseUrl(), savedCookie);
            } else {
                apiClient.clearCookies();
            }
            SmartSelector.getInstance().reloadForCurrentAccount();
        } catch (Exception e) {
            Log.w(TAG, "刷新账号运行态失败: " + e.getMessage());
        }
    }

    /**
     * 清除登录状态（退出登录时调用）。
     *
     * **故意不删 {@link CredentialStore} 里的密码**：退出登录只是结束这次会话，
     * 账号本身是用户资产，重新登录时不该再让他手打一遍密码。真要清掉，
     * 走「设置 → 账号管理」里的删除动作（{@link #deletePassword} / {@link #deleteAccount}）。
     */
    public void clearLoginState() {
        boolean wasDemoMode = isDemoMode;
        String accountStorageKeyToClear = getCurrentAccountStorageKey();
        isLoggedIn = false;
        isDemoMode = false;
        studentName = "";
        studentId = "";
        savedCookie = "";
        sessionPassword = "";
        currentAccountKey = "";
        if (wasDemoMode) {
            currentSchool = null;
            selectedCourses = new ArrayList<>();
            Log.d(TAG, "演示登录状态已清除");
            return;
        }

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
                    .remove(KEY_CURRENT_ACCOUNT_KEY)
                    .apply(); // 注意：这里不再 remove KEY_CURRENT_SCHOOL_ID，实现学校记忆
        }

        try {
            CourseApiClient.getInstance().clearCookies(accountStorageKeyToClear);
        } catch (Exception e) {
            Log.w(TAG, "清理账号运行期 Cookie 失败: " + e.getMessage());
        }

        Log.d(TAG, "登录状态已清除");
    }

    // 别名方法，方便 Kotlin 调用
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

    public void startDemoSession(SchoolConfig school) {
        currentSchool = school;
        studentName = "演示用户";
        studentId = "2024000001";
        savedCookie = "";
        sessionPassword = "";
        currentAccountKey = "demo::preview";
        selectedCourses = new ArrayList<>();
        isDemoMode = true;
        isLoggedIn = true;
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
