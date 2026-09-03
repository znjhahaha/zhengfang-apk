package com.tyust.course.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.tyust.course.model.Course;
import com.tyust.course.model.SchoolConfig;
import com.tyust.course.network.CourseApiClient;
import com.tyust.course.utils.CourseNameKit;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

public class SmartSelector {
    private static final String TAG = "SmartSelector";
    private static final String PREFS_NAME = "smart_selector_prefs";
    private static final String KEY_TARGET_COURSE = "target_course_json";
    private static final String KEY_COURSE_QUEUE = "course_queue_json";

    private String accountStorageKey() {
        return UserManager.getInstance().getCurrentAccountStorageKey();
    }

    private String scopedKey(String key) {
        return key + "_" + accountStorageKey();
    }

    private static SmartSelector instance;
    private boolean isRunning = false;
    private Handler handler = new Handler(Looper.getMainLooper());
    private int successCount = 0;
    private int failCount = 0;
    private int retryCount = 0;
    private Course targetCourse;
    private SchoolConfig currentSchool;
    private OnStatusUpdateListener listener;
    private Map<String, String> courseParams;
    private Context appContext;

    // 多课程队列功能
    private ArrayList<Course> courseQueue = new ArrayList<>();
    private int currentQueueIndex = 0;

    // 🔧 模糊匹配捡漏模式
    private String fuzzyMatchCourseId = null; // 监控的课程类别ID (courseId)
    private String fuzzyMatchCourseName = null; // 课程名称（用于显示）
    private String fuzzyMatchXkkzId = null; // 选课控制ID（请求详情必需）
    private String fuzzyMatchKklxdm = null; // 课程类型代码（01=专业课, 10=公选课等）
    private boolean fuzzyMatchEnabled = false; // 是否启用模糊匹配模式
    private java.util.Map<String, Integer> lastSelectedSnapshot = new java.util.HashMap<>(); // 上次人数快照

    // Configurable settings
    private int interval = 1500; // ms
    private int maxRetry = 100;

    public interface OnStatusUpdateListener {
        void onUpdate(String message);

        void onSuccess(String courseName);

        void onQueueProgress(int current, int total, String courseName); // 新增
    }

    public static synchronized SmartSelector getInstance() {
        if (instance == null) {
            instance = new SmartSelector();
        }
        return instance;
    }

    // 初始化 Context (需要在 Application 或 MainActivity 中调用)
    public void init(Context context) {
        this.appContext = context.getApplicationContext();
        reloadForCurrentAccount();
    }

    public void reloadForCurrentAccount() {
        if (isRunning) {
            stop();
        }
        this.targetCourse = null;
        this.courseQueue.clear();
        this.currentQueueIndex = 0;
        this.courseParams = null;
        this.fuzzyMatchCourseId = null;
        this.fuzzyMatchCourseName = null;
        this.fuzzyMatchXkkzId = null;
        this.fuzzyMatchKklxdm = null;
        this.fuzzyMatchEnabled = false;
        this.lastSelectedSnapshot.clear();
        restoreTargetCourse();
        restoreCourseQueue();
        restoreFuzzyMatchSettings();
    }

    public void setListener(OnStatusUpdateListener listener) {
        this.listener = listener;
    }

    public void setCourseParams(Map<String, String> params) {
        this.courseParams = params;
    }

    public Map<String, String> getCourseParams() {
        return this.courseParams;
    }

    public void setInterval(int interval) {
        this.interval = Math.max(500, interval); // Min 500ms
    }

    public void setMaxRetry(int maxRetry) {
        this.maxRetry = Math.max(1, maxRetry);
    }

    public Course getTargetCourse() {
        return targetCourse;
    }

    public void setTargetCourse(Course course) {
        this.targetCourse = course;
        saveTargetCourse();
    }

    // ============ 队列管理方法 ============

    public List<Course> getQueue() {
        return new ArrayList<>(courseQueue);
    }

    public int getQueueSize() {
        return courseQueue.size();
    }

    public int getCurrentQueueIndex() {
        return currentQueueIndex;
    }

    public boolean addToQueue(Course course) {
        if (course == null)
            return false;

        // 🔧 优先使用 classId（教学班唯一ID）进行判重
        // 因为 time 和 teacher 字段可能在详情加载前为空，导致误判为相同课程
        String newClassId = course.classId;

        for (Course c : courseQueue) {
            // 如果 classId 都非空，直接用 classId 判断
            if (newClassId != null && !newClassId.isEmpty() && c.classId != null && !c.classId.isEmpty()) {
                if (newClassId.equals(c.classId)) {
                    Log.d(TAG, "课程已在队列中(classId匹配): " + course.name + " | " + course.classId);
                    return false;
                }
            } else {
                // 降级方案：如果 classId 不可用，使用 name + teacher + time 组合
                boolean nameMatch = c.name != null && c.name.equals(course.name);
                boolean teacherMatch = (c.teacher == null && course.teacher == null) ||
                        (c.teacher != null && c.teacher.equals(course.teacher));
                boolean timeMatch = (c.time == null && course.time == null) ||
                        (c.time != null && c.time.equals(course.time));

                if (nameMatch && teacherMatch && timeMatch) {
                    Log.d(TAG, "课程已在队列中(字段匹配): " + course.name + " | " + course.teacher);
                    return false;
                }
            }
        }
        courseQueue.add(course);
        saveCourseQueue();
        Log.d(TAG, "添加到队列: " + course.name + " | " + course.teacher + " | classId=" + course.classId + ", 队列长度: "
                + courseQueue.size());
        return true;
    }

    public void removeFromQueue(int index) {
        if (index >= 0 && index < courseQueue.size()) {
            Course removed = courseQueue.remove(index);
            saveCourseQueue();
            Log.d(TAG, "从队列移除: " + removed.name);
        }
    }

    public void removeFromQueue(Course course) {
        if (course == null)
            return;
        boolean removed = false;
        for (int i = 0; i < courseQueue.size(); i++) {
            if (courseQueue.get(i).equals(course)) {
                courseQueue.remove(i);
                removed = true;
                break;
            }
        }
        if (removed) {
            saveCourseQueue();
            Log.d(TAG, "从队列移除: " + course.name);
        }
    }

    public void clearQueue() {
        courseQueue.clear();
        currentQueueIndex = 0;
        saveCourseQueue();
        Log.d(TAG, "队列已清空");
    }

    // 🔧 切换队列中课程的匹配模式
    public void toggleExactMatchMode(int index) {
        if (index >= 0 && index < courseQueue.size()) {
            Course course = courseQueue.get(index);
            course.useExactMatch = !course.useExactMatch;
            saveCourseQueue();
            Log.d(TAG, "切换模式: " + course.name + " -> " +
                    (course.useExactMatch ? "精确模式🔒" : "智能模式🔄"));
        }
    }

    // 🔧 批量设置队列中所有课程的匹配模式
    public void setAllExactMatchMode(boolean exact) {
        for (Course course : courseQueue) {
            course.useExactMatch = exact;
        }
        saveCourseQueue();
        Log.d(TAG, "批量切换模式 -> " + (exact ? "精确模式🔒" : "智能模式🔄"));
    }

    public void moveInQueue(int fromIndex, int toIndex) {
        if (fromIndex >= 0 && fromIndex < courseQueue.size() &&
                toIndex >= 0 && toIndex < courseQueue.size()) {
            Course course = courseQueue.remove(fromIndex);
            courseQueue.add(toIndex, course);
            saveCourseQueue();
        }
    }

    // 保存队列到 SharedPreferences
    public void saveCourseQueue() {
        if (appContext == null)
            return;
        try {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            JSONArray jsonArray = new JSONArray();
            for (Course course : courseQueue) {
                JSONObject json = courseToJson(course);
                jsonArray.put(json);
            }
            prefs.edit()
                    .putString(scopedKey(KEY_COURSE_QUEUE), jsonArray.toString())
                    .remove(KEY_COURSE_QUEUE)
                    .apply();
            Log.d(TAG, "✅ 当前账号队列已保存, 共 " + courseQueue.size() + " 门课程");
        } catch (Exception e) {
            Log.e(TAG, "保存队列失败: " + e.getMessage());
        }
    }

    // 恢复队列
    private void restoreCourseQueue() {
        if (appContext == null)
            return;
        try {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String storageKey = scopedKey(KEY_COURSE_QUEUE);
            String jsonStr = prefs.getString(storageKey, null);
            if ((jsonStr == null || jsonStr.isEmpty()) && prefs.contains(KEY_COURSE_QUEUE)) {
                jsonStr = prefs.getString(KEY_COURSE_QUEUE, null);
                if (jsonStr != null && !jsonStr.isEmpty()) {
                    prefs.edit()
                            .putString(storageKey, jsonStr)
                            .remove(KEY_COURSE_QUEUE)
                            .apply();
                }
            }
            courseQueue.clear();
            if (jsonStr == null || jsonStr.isEmpty())
                return;

            JSONArray jsonArray = new JSONArray(jsonStr);
            courseQueue.clear();
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject json = jsonArray.getJSONObject(i);
                Course course = jsonToCourse(json);
                courseQueue.add(course);
            }
            Log.d(TAG, "✅ 队列已恢复, 共 " + courseQueue.size() + " 门课程");
        } catch (Exception e) {
            Log.e(TAG, "恢复队列失败: " + e.getMessage());
        }
    }

    // 使用队列模式启动抢课
    public void startWithQueue(SchoolConfig school) {
        if (isRunning)
            return;
        if (courseQueue.isEmpty()) {
            log("⚠️ 队列为空，无法启动");
            return;
        }

        this.currentSchool = school;
        this.currentQueueIndex = 0;
        this.successCount = 0;
        this.failCount = 0;

        processNextInQueue();
    }

    // 处理队列中的下一门课程 (动态匹配模式)
    private void processNextInQueue() {
        if (currentQueueIndex >= courseQueue.size()) {
            log("🎉 队列中所有课程处理完成！成功: " + successCount + " 门");
            isRunning = false;
            return;
        }

        Course targetMatch = courseQueue.get(currentQueueIndex);
        this.retryCount = 0;

        log("📋 开始抢第 " + (currentQueueIndex + 1) + "/" + courseQueue.size() + " 门: " + targetMatch.name);

        if (listener != null) {
            handler.post(() -> listener.onQueueProgress(currentQueueIndex + 1, courseQueue.size(), targetMatch.name));
        }

        this.isRunning = true;
        // 使用动态匹配模式
        findAndGrabCourse(targetMatch, currentSchool);
    }

    // 动态搜索并抢课 (核心逻辑)
    private void findAndGrabCourse(Course targetMatch, SchoolConfig school) {
        if (!isRunning)
            return;

        // 🔧 精确模式：直接使用保存的 classId，跳过搜索
        if (targetMatch.useExactMatch && targetMatch.classId != null && !targetMatch.classId.isEmpty()) {
            log("🔒 精确模式: 使用保存的ID直接选课");
            // 精确模式下直接获取教学班详情并选课
            fetchDetailsWithExactClassId(targetMatch, school);
            return;
        }

        // 🔄 智能模式：动态搜索课程
        log("🔄 智能模式: 搜索课程 " + targetMatch.name);

        // Step 1: 构建搜索请求参数
        StringBuilder searchBody = new StringBuilder();
        if (courseParams != null) {
            for (Map.Entry<String, String> entry : courseParams.entrySet()) {
                if (searchBody.length() > 0)
                    searchBody.append("&");
                searchBody.append(entry.getKey()).append("=").append(entry.getValue());
            }
        }
        // 按课程名搜索
        if (searchBody.length() > 0)
            searchBody.append("&");
        searchBody.append("filter_list[0]=").append(targetMatch.name);

        CourseApiClient.getInstance().fetchAvailableCourses(school, searchBody.toString(), new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log("⚠️ 搜索课程失败: " + e.getMessage());
                scheduleRetryOrNext(school, targetMatch);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";

                try {
                    // Step 2: 解析课程列表 - 🔧 兼容两种 JSON 格式
                    JSONArray items = null;

                    // 尝试直接解析为数组
                    if (body.trim().startsWith("[")) {
                        items = new JSONArray(body);
                    } else {
                        // 尝试从对象中提取数组
                        JSONObject json = new JSONObject(body);
                        if (json.has("tmpList")) {
                            items = json.optJSONArray("tmpList");
                        } else if (json.has("courses")) {
                            items = json.optJSONArray("courses");
                        }
                    }

                    if (items == null || items.length() == 0) {
                        log("⚠️ 未找到课程: " + targetMatch.name);
                        scheduleRetryOrNext(school, targetMatch);
                        return;
                    }

                    // 查找名称匹配的课程
                    Course matchedCourse = null;
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject item = items.getJSONObject(i);
                        String kcmc = item.optString("kcmc", "");

                        // 🔧 全半角括号归一化比较：用户输入"大学体育（三）"可匹配教务库"大学体育(三)"
                        if (CourseNameKit.normalizeBrackets(kcmc).equals(CourseNameKit.normalizeBrackets(targetMatch.name))) {
                            // 找到课程，提取基础参数
                            matchedCourse = new Course();
                            matchedCourse.name = kcmc;
                            matchedCourse.courseId = item.optString("kch_id", "");
                            matchedCourse.kklxdm = item.optString("kklxdm", "");
                            matchedCourse._xkkz_id = item.optString("xkkz_id", "");
                            if (matchedCourse._xkkz_id.isEmpty()) {
                                // 🔧 兼容正方 V9：JSON 字段可能是 xkkz_xh
                                matchedCourse._xkkz_id = item.optString("xkkz_xh", "");
                            }
                            matchedCourse._rwlx = item.optString("rwlx", "1");

                            log("✅ 找到可选课程: " + kcmc + " (kch_id=" + matchedCourse.courseId + ")");
                            break;
                        }
                    }

                    if (matchedCourse == null) {
                        log("⚠️ 课程名不匹配: " + targetMatch.name);
                        scheduleRetryOrNext(school, targetMatch);
                        return;
                    }

                    // Step 3: 获取课程详情，匹配具体教学班
                    fetchDetailsAndMatch(matchedCourse, targetMatch, school);

                } catch (Exception e) {
                    log("⚠️ 解析课程列表失败: " + e.getMessage());
                    scheduleRetryOrNext(school, targetMatch);
                }
            }
        });
    }

    // 🔧 精确模式：直接使用保存的 classId 获取教学班详情
    private void fetchDetailsWithExactClassId(Course targetCourse, SchoolConfig school) {
        StringBuilder detailBody = new StringBuilder();
        if (courseParams != null) {
            for (Map.Entry<String, String> entry : courseParams.entrySet()) {
                if (detailBody.length() > 0)
                    detailBody.append("&");
                detailBody.append(entry.getKey()).append("=").append(entry.getValue());
            }
        }
        if (detailBody.length() > 0)
            detailBody.append("&");
        detailBody.append("kch_id=").append(targetCourse.courseId);
        if (targetCourse._xkkz_id != null && !targetCourse._xkkz_id.isEmpty()) {
            // 🔧 xkkz 参数名自适应：completeParams 含 V9 键时用 xkkz_xh
            String xkkzKey = CourseNameKit.detectXkkzKey(targetCourse.completeParams);
            detailBody.append("&").append(xkkzKey).append("=").append(targetCourse._xkkz_id);
        }

        CourseApiClient.getInstance().fetchCourseSelectionDetails(school, detailBody.toString(), new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log("⚠️ 精确模式获取详情失败: " + e.getMessage());
                scheduleRetryOrNext(school, targetCourse);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";

                try {
                    JSONArray classes = new JSONArray(body);
                    Course matchedClass = null;

                    // 使用保存的 classId 精确匹配
                    for (int i = 0; i < classes.length(); i++) {
                        JSONObject cls = classes.getJSONObject(i);
                        String jxbId = cls.optString("jxb_id", "");
                        String doJxbId = cls.optString("do_jxb_id", "");

                        // 精确匹配 classId
                        if (jxbId.equals(targetCourse.classId) || doJxbId.equals(targetCourse.classId)) {
                            matchedClass = new Course();
                            matchedClass.name = targetCourse.name;
                            matchedClass.courseId = targetCourse.courseId;
                            matchedClass.classId = jxbId;
                            matchedClass.doJxbId = doJxbId;
                            matchedClass.teacher = cls.optString("jsxm", targetCourse.teacher);
                            matchedClass.time = cls.optString("sksj", targetCourse.time);
                            matchedClass._rwlx = targetCourse._rwlx;
                            matchedClass._xkkz_id = targetCourse._xkkz_id;
                            matchedClass.rlkz = cls.optString("rlkz", "0");
                            matchedClass.rlzlkz = cls.optString("rlzlkz", "1");
                            matchedClass.sxbj = cls.optString("sxbj", "0");
                            matchedClass.xxkbj = cls.optString("xxkbj", "0");

                            log("✅ 精确匹配成功: " + matchedClass.teacher + " | classId=" + jxbId);
                            break;
                        }
                    }

                    if (matchedClass == null) {
                        // 如果精确匹配失败，回退到第一个（兼容旧数据）
                        if (classes.length() > 0) {
                            JSONObject cls = classes.getJSONObject(0);
                            matchedClass = new Course();
                            matchedClass.name = targetCourse.name;
                            matchedClass.courseId = targetCourse.courseId;
                            matchedClass.classId = cls.optString("jxb_id", "");
                            matchedClass.doJxbId = cls.optString("do_jxb_id", "");
                            matchedClass.teacher = cls.optString("jsxm", "");
                            matchedClass.time = cls.optString("sksj", "");
                            matchedClass._rwlx = targetCourse._rwlx;
                            matchedClass._xkkz_id = targetCourse._xkkz_id;
                            matchedClass.rlkz = cls.optString("rlkz", "0");

                            log("⚠️ 精确ID未匹配，使用第一个教学班: " + matchedClass.teacher);
                        }
                    }

                    if (matchedClass != null) {
                        // 执行选课（与 fetchDetailsAndMatch 一致的调用方式）
                        SmartSelector.this.targetCourse = matchedClass;
                        runLoop(school);
                    } else {
                        log("❌ 未找到可选教学班");
                        scheduleRetryOrNext(school, targetCourse);
                    }

                } catch (Exception e) {
                    log("⚠️ 精确模式解析失败: " + e.getMessage());

                    // 🔧 Fallback: 如果解析失败（如返回"0"）但我们有保存的 doJxbId，直接尝试抢课
                    if (targetCourse != null && targetCourse.doJxbId != null && !targetCourse.doJxbId.isEmpty()) {
                        log("⚠️ 解析失败，强制使用保存的 doJxbId=" + targetCourse.doJxbId);

                        // 确保必要的参数存在 (默认值与 GrabService 保持一致)
                        if (targetCourse.rlkz == null)
                            targetCourse.rlkz = "0";
                        if (targetCourse.rlzlkz == null)
                            targetCourse.rlzlkz = "1";
                        if (targetCourse.sxbj == null)
                            targetCourse.sxbj = "0";
                        if (targetCourse.xxkbj == null)
                            targetCourse.xxkbj = "0";

                        SmartSelector.this.targetCourse = targetCourse;
                        runLoop(school);
                        return;
                    }

                    scheduleRetryOrNext(school, targetCourse);
                }
            }
        });
    }

    // 获取课程详情并匹配教学班
    private void fetchDetailsAndMatch(Course baseCourse, Course targetMatch, SchoolConfig school) {
        StringBuilder detailBody = new StringBuilder();
        if (courseParams != null) {
            for (Map.Entry<String, String> entry : courseParams.entrySet()) {
                if (detailBody.length() > 0)
                    detailBody.append("&");
                detailBody.append(entry.getKey()).append("=").append(entry.getValue());
            }
        }
        if (detailBody.length() > 0)
            detailBody.append("&");
        detailBody.append("kch_id=").append(baseCourse.courseId);
        // 🔧 xkkz 参数名自适应：completeParams 含 V9 键时用 xkkz_xh
        detailBody.append("&").append(CourseNameKit.detectXkkzKey(baseCourse.completeParams))
                .append("=").append(baseCourse._xkkz_id);

        CourseApiClient.getInstance().fetchCourseSelectionDetails(school, detailBody.toString(), new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log("⚠️ 获取课程详情失败: " + e.getMessage());
                scheduleRetryOrNext(school, targetMatch);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";

                try {
                    JSONArray classes = new JSONArray(body);
                    Course matchedClass = null;

                    for (int i = 0; i < classes.length(); i++) {
                        JSONObject cls = classes.getJSONObject(i);
                        String teacher = cls.optString("jsxm", "");
                        String time = cls.optString("sksj", "");

                        // 匹配老师和时间
                        boolean teacherMatch = targetMatch.teacher == null || targetMatch.teacher.isEmpty()
                                || teacher.contains(targetMatch.teacher);
                        boolean timeMatch = targetMatch.time == null || targetMatch.time.isEmpty()
                                || time.contains(targetMatch.time);

                        if (teacherMatch && timeMatch) {
                            matchedClass = new Course();
                            matchedClass.name = baseCourse.name;
                            matchedClass.courseId = baseCourse.courseId;
                            matchedClass.classId = cls.optString("jxb_id", "");
                            matchedClass.doJxbId = cls.optString("do_jxb_id", "");
                            matchedClass.teacher = teacher;
                            matchedClass.time = time;
                            matchedClass._rwlx = baseCourse._rwlx;
                            matchedClass._xkkz_id = baseCourse._xkkz_id;
                            matchedClass.rlkz = cls.optString("rlkz", "0");
                            matchedClass.rlzlkz = cls.optString("rlzlkz", "1");
                            matchedClass.sxbj = cls.optString("sxbj", "0");
                            matchedClass.xxkbj = cls.optString("xxkbj", "0");

                            log("✅ 匹配到教学班: " + teacher + " | " + time);
                            break;
                        }
                    }

                    if (matchedClass == null) {
                        log("⚠️ 未匹配到符合条件的教学班");
                        scheduleRetryOrNext(school, targetMatch);
                        return;
                    }

                    // Step 4: 使用动态获取的参数执行选课
                    targetCourse = matchedClass;
                    runLoop(school);

                } catch (Exception e) {
                    log("⚠️ 解析课程详情失败: " + e.getMessage());
                    scheduleRetryOrNext(school, targetMatch);
                }
            }
        });
    }

    // 重试或切换到下一门课程
    private void scheduleRetryOrNext(SchoolConfig school, Course targetMatch) {
        retryCount++;
        if (retryCount >= maxRetry) {
            log("⚠️ " + targetMatch.name + " 达到最大重试次数，切换下一门");
            currentQueueIndex++;
            handler.postDelayed(() -> processNextInQueue(), interval);
        } else {
            log("⏳ 重试 [" + retryCount + "/" + maxRetry + "] " + targetMatch.name);
            handler.postDelayed(() -> findAndGrabCourse(targetMatch, school), interval);
        }
    }

    // 持久化保存目标课程
    private void saveTargetCourse() {
        if (appContext == null)
            return;
        try {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            if (targetCourse == null) {
                prefs.edit().remove(scopedKey(KEY_TARGET_COURSE)).remove(KEY_TARGET_COURSE).apply();
                return;
            }
            JSONObject json = courseToJson(targetCourse);
            prefs.edit()
                    .putString(scopedKey(KEY_TARGET_COURSE), json.toString())
                    .remove(KEY_TARGET_COURSE)
                    .apply();
            Log.d(TAG, "✅ 当前账号目标课程已保存: " + targetCourse.name);
        } catch (Exception e) {
            Log.e(TAG, "保存目标课程失败: " + e.getMessage());
        }
    }

    private JSONObject courseToJson(Course course) throws Exception {
        JSONObject json = new JSONObject();
        // 🔧 保存所有选课必需的字段，确保队列恢复后能精确匹配教学班
        // 基本匹配信息
        json.put("name", course.name); // 课程名
        json.put("teacher", course.teacher); // 教师
        json.put("time", course.time); // 时间
        json.put("location", course.location); // 地点

        // 🔧 关键ID字段（用于精确匹配教学班）
        json.put("classId", course.classId); // 教学班ID
        json.put("courseId", course.courseId); // 课程ID (kch_id)
        json.put("doJxbId", course.doJxbId); // 加密的教学班ID

        // 选课参数
        json.put("kklxdm", course.kklxdm); // 课程类型代码
        json.put("_xkkz_id", course._xkkz_id); // 选课控制ID
        json.put("_rwlx", course._rwlx); // 任务类型
        json.put("_xklc", course._xklc); // 选课轮次
        json.put("njdm_id", course.njdm_id); // 年级代码
        json.put("zyh_id", course.zyh_id); // 专业号
        json.put("credit", course.credit); // 学分
        json.put("capacity", course.capacity); // 容量
        json.put("selected", course.selected); // 已选人数

        // 🔧 唯一标识符
        json.put("uuid", course.getUuid());

        // 🔧 抢课模式
        json.put("useExactMatch", course.useExactMatch); // 精确模式/智能模式

        return json;
    }

    private Course jsonToCourse(JSONObject json) {
        Course course = new Course();
        // 🔧 恢复所有保存的字段
        // 基本信息
        course.name = json.optString("name", "");
        course.teacher = json.optString("teacher", "");
        course.time = json.optString("time", "");
        course.location = json.optString("location", "");

        // 🔧 关键ID字段
        course.classId = json.optString("classId", "");
        course.courseId = json.optString("courseId", "");
        course.doJxbId = json.optString("doJxbId", "");

        // 选课参数
        course.kklxdm = json.optString("kklxdm", "");
        course._xkkz_id = json.optString("_xkkz_id", "");
        course._rwlx = json.optString("_rwlx", "");
        course._xklc = json.optString("_xklc", "");
        course.njdm_id = json.optString("njdm_id", "");
        course.zyh_id = json.optString("zyh_id", "");
        course.credit = json.optString("credit", "");
        course.capacity = json.optInt("capacity", 0);
        course.selected = json.optInt("selected", 0);

        // 🔧 唯一标识符
        String savedUuid = json.optString("uuid", null);
        if (savedUuid != null && !savedUuid.isEmpty()) {
            course.uuid = savedUuid;
        }

        // 🔧 抢课模式（默认精确模式）
        course.useExactMatch = json.optBoolean("useExactMatch", true);

        return course;
    }

    // 恢复目标课程
    private void restoreTargetCourse() {
        if (appContext == null)
            return;
        try {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String storageKey = scopedKey(KEY_TARGET_COURSE);
            String jsonStr = prefs.getString(storageKey, null);
            if ((jsonStr == null || jsonStr.isEmpty()) && prefs.contains(KEY_TARGET_COURSE)) {
                jsonStr = prefs.getString(KEY_TARGET_COURSE, null);
                if (jsonStr != null && !jsonStr.isEmpty()) {
                    prefs.edit()
                            .putString(storageKey, jsonStr)
                            .remove(KEY_TARGET_COURSE)
                            .apply();
                }
            }
            this.targetCourse = null;
            if (jsonStr == null || jsonStr.isEmpty())
                return;

            JSONObject json = new JSONObject(jsonStr);
            this.targetCourse = jsonToCourse(json);
            Log.d(TAG, "✅ 目标课程已恢复: " + targetCourse.name);
        } catch (Exception e) {
            Log.e(TAG, "恢复目标课程失败: " + e.getMessage());
        }
    }

    // 清除目标课程
    public void clearTargetCourse() {
        this.targetCourse = null;
        if (appContext != null) {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().remove(scopedKey(KEY_TARGET_COURSE)).remove(KEY_TARGET_COURSE).apply();
        }
    }

    public int getSuccessCount() {
        return successCount;
    }

    public int getFailCount() {
        return failCount;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void start(Course course, SchoolConfig school) {
        if (isRunning)
            return;
        this.targetCourse = course;
        this.currentSchool = school;
        this.isRunning = true;
        this.successCount = 0;
        this.failCount = 0;
        this.retryCount = 0;

        log("🚀 开始抢课: " + course.name);
        runLoop(school);
    }

    public void stop() {
        isRunning = false;
        log("⏹ 抢课已停止");
    }

    public boolean isRunning() {
        return isRunning;
    }

    private void runLoop(SchoolConfig school) {
        if (!isRunning)
            return;

        if (retryCount >= maxRetry) {
            // 达到最大重试次数，如果是队列模式则切换到下一门
            if (!courseQueue.isEmpty() && currentQueueIndex < courseQueue.size()) {
                log("⚠️ 达到最大重试次数，切换到下一门课程");
                currentQueueIndex++;
                processNextInQueue();
                return;
            }

            isRunning = false;
            log("❌ 已达到最大重试次数 (" + maxRetry + ")，自动停止");
            return;
        }

        // 构建选课POST参数
        StringBuilder postBody = new StringBuilder();

        // 添加基础参数
        if (courseParams != null) {
            for (Map.Entry<String, String> entry : courseParams.entrySet()) {
                if (postBody.length() > 0)
                    postBody.append("&");
                postBody.append(entry.getKey()).append("=").append(entry.getValue());
            }
        }

        // 添加课程相关参数 (与Web版一致)
        if (postBody.length() > 0)
            postBody.append("&");

        // 使用加密的 jxb_ids
        String jxbIds = targetCourse.doJxbId;
        if (jxbIds == null || jxbIds.isEmpty()) {
            jxbIds = targetCourse.classId; // fallback
        }

        postBody.append("jxb_ids=").append(jxbIds);
        postBody.append("&kch_id=").append(targetCourse.courseId);
        postBody.append("&kcmc=").append(targetCourse.name != null ? targetCourse.name : "");

        // 添加Web版的额外参数
        postBody.append("&rwlx=")
                .append(targetCourse._rwlx != null && !targetCourse._rwlx.isEmpty() ? targetCourse._rwlx : "1");
        postBody.append("&rlkz=").append(targetCourse.rlkz != null ? targetCourse.rlkz : "0");
        postBody.append("&rlzlkz=").append(targetCourse.rlzlkz != null ? targetCourse.rlzlkz : "1");
        postBody.append("&sxbj=").append(targetCourse.sxbj != null ? targetCourse.sxbj : "0");
        postBody.append("&xxkbj=").append(targetCourse.xxkbj != null ? targetCourse.xxkbj : "0");
        postBody.append("&qz=0"); // 固定值

        // 添加sfkxq和xkxskcgskg (关键参数)
        if (targetCourse._sfkxq != null && !targetCourse._sfkxq.isEmpty()) {
            postBody.append("&sfkxq=").append(targetCourse._sfkxq);
        }
        if (targetCourse._xkxskcgskg != null && !targetCourse._xkxskcgskg.isEmpty()) {
            postBody.append("&xkxskcgskg=").append(targetCourse._xkxskcgskg);
        }

        Log.d(TAG, "Auto-select POST body: " + postBody.toString());
        retryCount++;

        CourseApiClient.getInstance().selectCourse(school, postBody.toString(), new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                failCount++;
                log("⚠️ 请求失败: " + e.getMessage() + " [" + retryCount + "/" + maxRetry + "]");
                scheduleNext(school);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    String html = response.body().string();
                    Log.d(TAG, "Selection response: " + html);

                    // 尝试解析JSON响应 (与Web版一致)
                    boolean success = false;
                    String msg = "选课失败";

                    try {
                        org.json.JSONObject json = new org.json.JSONObject(html);
                        // 检查flag字段 (Web版验证方式)
                        String flag = json.optString("flag", "");
                        if ("1".equals(flag)) {
                            success = true;
                            msg = "选课成功";
                        } else {
                            // 提取错误信息
                            msg = json.optString("msg", json.optString("message", "选课失败"));
                        }
                    } catch (Exception jsonEx) {
                        // 非JSON响应，使用文本匹配
                        if (html.contains("成功") || html.contains("选课成功")) {
                            success = true;
                            msg = "选课成功";
                        } else if (html.contains("人数已满") || html.contains("容量已满")) {
                            msg = "人数已满，继续重试";
                        } else if (html.contains("冲突")) {
                            msg = "课程时间冲突";
                        } else if (html.contains("未开放")) {
                            msg = "选课未开放";
                        }
                    }

                    if (success) {
                        successCount++;
                        log("✅ " + msg + ": " + targetCourse.name);

                        if (listener != null) {
                            handler.post(() -> listener.onSuccess(targetCourse.name));
                        }

                        // 队列模式：成功后处理下一门
                        if (!courseQueue.isEmpty() && currentQueueIndex < courseQueue.size()) {
                            // 从队列中移除已成功的课程
                            removeFromQueue(targetCourse);
                            // 不递增 index，因为已经移除了当前课程
                            handler.postDelayed(() -> processNextInQueue(), 2000); // 等待2秒后继续
                        } else {
                            isRunning = false;
                        }
                    } else {
                        failCount++;
                        log("❌ " + msg + " [" + retryCount + "/" + maxRetry + "]");
                        scheduleNext(school);
                    }
                } catch (Exception e) {
                    failCount++;
                    Log.e(TAG, "Error processing response: " + e.getMessage());
                    scheduleNext(school);
                }
            }
        });
    }

    private void scheduleNext(SchoolConfig school) {
        if (!isRunning)
            return;
        // 使用配置的间隔时间
        handler.postDelayed(() -> runLoop(school), interval);
    }

    private void log(String msg) {
        Log.d(TAG, msg);
        if (listener != null) {
            handler.post(() -> listener.onUpdate(msg));
        }
    }

    // ============ 模糊匹配捡漏模式 ============

    /**
     * 设置模糊匹配目标课程类别
     * 
     * @param courseId   课程ID (kch_id)
     * @param courseName 课程名称（用于显示）
     */
    public void setFuzzyMatchTarget(String courseId, String courseName) {
        setFuzzyMatchTarget(courseId, courseName, null);
    }

    /**
     * 设置模糊匹配目标课程类别（包含选课控制ID）
     * 
     * @param courseId   课程ID (kch_id)
     * @param courseName 课程名称（用于显示）
     * @param xkkzId     选课控制ID (xkkz_id)，可为null
     */
    public void setFuzzyMatchTarget(String courseId, String courseName, String xkkzId) {
        setFuzzyMatchTarget(courseId, courseName, xkkzId, null);
    }

    /**
     * 设置模糊匹配目标课程类别（完整参数）
     * 
     * @param courseId   课程ID (kch_id)
     * @param courseName 课程名称（用于显示）
     * @param xkkzId     选课控制ID (xkkz_id)
     * @param kklxdm     课程类型代码 (01=专业课, 10=公选课等)
     */
    public void setFuzzyMatchTarget(String courseId, String courseName, String xkkzId, String kklxdm) {
        this.fuzzyMatchCourseId = courseId;
        this.fuzzyMatchCourseName = courseName;
        this.fuzzyMatchXkkzId = xkkzId;
        this.fuzzyMatchKklxdm = kklxdm;
        this.lastSelectedSnapshot.clear(); // 清空旧快照
        Log.d(TAG,
                "🔍 设置模糊匹配目标: " + courseName + " (id=" + courseId + ", xkkz_id=" + xkkzId + ", kklxdm=" + kklxdm + ")");

        // 持久化保存
        if (appContext != null) {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit()
                    .putString(scopedKey("fuzzy_match_course_id"), courseId)
                    .putString(scopedKey("fuzzy_match_course_name"), courseName)
                    .remove("fuzzy_match_course_id")
                    .remove("fuzzy_match_course_name");
            if (xkkzId != null) {
                editor.putString(scopedKey("fuzzy_match_xkkz_id"), xkkzId)
                        .remove("fuzzy_match_xkkz_id");
            } else {
                editor.remove(scopedKey("fuzzy_match_xkkz_id"))
                        .remove("fuzzy_match_xkkz_id");
            }
            if (kklxdm != null) {
                editor.putString(scopedKey("fuzzy_match_kklxdm"), kklxdm)
                        .remove("fuzzy_match_kklxdm");
            } else {
                editor.remove(scopedKey("fuzzy_match_kklxdm"))
                        .remove("fuzzy_match_kklxdm");
            }
            editor.apply();
        }
    }

    /**
     * 获取模糊匹配目标课程ID
     */
    public String getFuzzyMatchCourseId() {
        return fuzzyMatchCourseId;
    }

    /**
     * 获取模糊匹配目标课程名称
     */
    public String getFuzzyMatchCourseName() {
        return fuzzyMatchCourseName;
    }

    /**
     * 获取模糊匹配目标选课控制ID
     */
    public String getFuzzyMatchXkkzId() {
        return fuzzyMatchXkkzId;
    }

    /**
     * 获取模糊匹配目标课程类型代码
     */
    public String getFuzzyMatchKklxdm() {
        return fuzzyMatchKklxdm;
    }

    /**
     * 清除模糊匹配目标
     */
    public void clearFuzzyMatchTarget() {
        this.fuzzyMatchCourseId = null;
        this.fuzzyMatchCourseName = null;
        this.fuzzyMatchXkkzId = null;
        this.fuzzyMatchKklxdm = null;
        this.lastSelectedSnapshot.clear();
        this.fuzzyMatchEnabled = false;
        Log.d(TAG, "🔍 已清除模糊匹配目标");

        if (appContext != null) {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                    .remove(scopedKey("fuzzy_match_course_id"))
                    .remove(scopedKey("fuzzy_match_course_name"))
                    .remove(scopedKey("fuzzy_match_xkkz_id"))
                    .remove(scopedKey("fuzzy_match_kklxdm"))
                    .remove("fuzzy_match_course_id")
                    .remove("fuzzy_match_course_name")
                    .remove("fuzzy_match_xkkz_id")
                    .remove("fuzzy_match_kklxdm")
                    .apply();
        }
    }

    /**
     * 设置模糊匹配模式启用状态
     */
    public void setFuzzyMatchEnabled(boolean enabled) {
        this.fuzzyMatchEnabled = enabled;
        if (!enabled) {
            lastSelectedSnapshot.clear();
        }
        Log.d(TAG, "🔍 模糊匹配模式: " + (enabled ? "已启用" : "已禁用"));
    }

    /**
     * 获取模糊匹配模式启用状态
     */
    public boolean isFuzzyMatchEnabled() {
        return fuzzyMatchEnabled;
    }

    /**
     * 更新人数快照并检测是否有空位
     * 
     * @param classId         教学班ID
     * @param currentSelected 当前已选人数
     * @param capacity        容量
     * @return 如果有空位（selected < capacity）返回true
     */
    public boolean updateSnapshotAndCheckChange(String classId, int currentSelected, int capacity) {
        if (classId == null || classId.isEmpty())
            return false;

        Integer lastSelected = lastSelectedSnapshot.get(classId);
        lastSelectedSnapshot.put(classId, currentSelected);

        // 🔧 只要有空位就返回 true（不再要求人数变化）
        if (currentSelected < capacity) {
            if (lastSelected == null) {
                // 第一次检测到有空位
                Log.d(TAG, "🎯 发现有空位! classId=" + classId +
                        ", 当前: " + currentSelected + "/" + capacity +
                        ", 空位: " + (capacity - currentSelected));
            } else if (currentSelected < lastSelected) {
                // 有人退课
                Log.d(TAG, "🎯 检测到名额释放! classId=" + classId +
                        ", 人数: " + lastSelected + " -> " + currentSelected +
                        ", 空位: " + (capacity - currentSelected));
            }
            return true;
        }

        return false;
    }

    /**
     * 获取当前快照
     */
    public java.util.Map<String, Integer> getLastSelectedSnapshot() {
        return new java.util.HashMap<>(lastSelectedSnapshot);
    }

    /**
     * 恢复模糊匹配设置
     */
    public void restoreFuzzyMatchSettings() {
        if (appContext == null)
            return;
        try {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            fuzzyMatchCourseId = prefs.getString(scopedKey("fuzzy_match_course_id"), null);
            fuzzyMatchCourseName = prefs.getString(scopedKey("fuzzy_match_course_name"), null);
            fuzzyMatchXkkzId = prefs.getString(scopedKey("fuzzy_match_xkkz_id"), null);
            fuzzyMatchKklxdm = prefs.getString(scopedKey("fuzzy_match_kklxdm"), null);
            if (fuzzyMatchCourseId == null && prefs.contains("fuzzy_match_course_id")) {
                fuzzyMatchCourseId = prefs.getString("fuzzy_match_course_id", null);
                fuzzyMatchCourseName = prefs.getString("fuzzy_match_course_name", null);
                fuzzyMatchXkkzId = prefs.getString("fuzzy_match_xkkz_id", null);
                fuzzyMatchKklxdm = prefs.getString("fuzzy_match_kklxdm", null);
                SharedPreferences.Editor editor = prefs.edit()
                        .remove("fuzzy_match_course_id")
                        .remove("fuzzy_match_course_name")
                        .remove("fuzzy_match_xkkz_id")
                        .remove("fuzzy_match_kklxdm");
                if (fuzzyMatchCourseId != null) editor.putString(scopedKey("fuzzy_match_course_id"), fuzzyMatchCourseId);
                if (fuzzyMatchCourseName != null) editor.putString(scopedKey("fuzzy_match_course_name"), fuzzyMatchCourseName);
                if (fuzzyMatchXkkzId != null) editor.putString(scopedKey("fuzzy_match_xkkz_id"), fuzzyMatchXkkzId);
                if (fuzzyMatchKklxdm != null) editor.putString(scopedKey("fuzzy_match_kklxdm"), fuzzyMatchKklxdm);
                editor.apply();
            }
            if (fuzzyMatchCourseId != null) {
                Log.d(TAG, "✅ 模糊匹配目标已恢复: " + fuzzyMatchCourseName + " (xkkz_id=" + fuzzyMatchXkkzId + ", kklxdm="
                        + fuzzyMatchKklxdm + ")");
            }
        } catch (Exception e) {
            Log.e(TAG, "恢复模糊匹配设置失败: " + e.getMessage());
        }
    }
}
