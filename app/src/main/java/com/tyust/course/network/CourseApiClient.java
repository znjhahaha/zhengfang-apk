package com.tyust.course.network;

import android.util.Log;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import com.tyust.course.model.SchoolConfig;

public class CourseApiClient {
        private static final String TAG = "CourseApiClient";
        private static volatile CourseApiClient instance;
        private final OkHttpClient client;
        private final CookieJarImpl cookieJar;

        // ============= Web版兼容: Display参数缓存 (按xkkz_id) =============
        private final Map<String, Map<String, String>> displayParamsCache = new ConcurrentHashMap<>();

        private CourseApiClient() {
                cookieJar = new CookieJarImpl();

                // 创建信任所有证书的 TrustManager (解决部分学校证书问题)
                javax.net.ssl.TrustManager[] trustAllCerts = new javax.net.ssl.TrustManager[] {
                                new javax.net.ssl.X509TrustManager() {
                                        @Override
                                        public void checkClientTrusted(java.security.cert.X509Certificate[] chain,
                                                        String authType) {
                                        }

                                        @Override
                                        public void checkServerTrusted(java.security.cert.X509Certificate[] chain,
                                                        String authType) {
                                        }

                                        @Override
                                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                                                return new java.security.cert.X509Certificate[] {};
                                        }
                                }
                };

                OkHttpClient.Builder builder = new OkHttpClient.Builder()
                                .cookieJar(cookieJar)
                                .followRedirects(true)
                                .followSslRedirects(true)
                                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS);

                try {
                        javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getInstance("TLS");
                        sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
                        builder.sslSocketFactory(sslContext.getSocketFactory(),
                                        (javax.net.ssl.X509TrustManager) trustAllCerts[0]);
                        builder.hostnameVerifier((hostname, session) -> true);
                } catch (Exception e) {
                        Log.e(TAG, "Failed to setup SSL bypass: " + e.getMessage());
                }

                client = builder.build();
        }

        public static CourseApiClient getInstance() {
                if (instance == null) {
                        synchronized (CourseApiClient.class) {
                                if (instance == null) {
                                        instance = new CourseApiClient();
                                }
                        }
                }
                return instance;
        }

        // ============= Display参数缓存方法 =============
        public Map<String, String> getDisplayParamsFromCache(String xkkz_id) {
                return displayParamsCache.get(xkkz_id);
        }

        public void setDisplayParamsCache(String xkkz_id, Map<String, String> params) {
                displayParamsCache.put(xkkz_id, new HashMap<>(params));
                Log.d(TAG, "Cached display params for xkkz_id=" + xkkz_id + ", count=" + params.size());
        }

        public void clearDisplayParamsCache() {
                displayParamsCache.clear();
                Log.d(TAG, "Cleared display params cache");
        }

        // 设置原始 Cookie 字符串 (e.g., "ASP.NET_SessionId=xyz; JSESSIONID=abc")
        public void setCookie(String baseUrl, String cookieString) {
                HttpUrl url = HttpUrl.parse(baseUrl);
                if (url == null)
                        return;

                cookieJar.clear(); // 清除旧的

                // Sanitize: remove newlines, carriage returns, and other control characters
                String sanitized = cookieString
                                .replace("\n", "")
                                .replace("\r", "")
                                .replace("\t", " ")
                                .trim();

                String[] parts = sanitized.split(";");
                for (String part : parts) {
                        String[] pair = part.trim().split("=", 2);
                        if (pair.length == 2) {
                                String name = pair[0].trim();
                                String value = pair[1].trim();
                                // Skip empty names or values
                                if (name.isEmpty() || value.isEmpty())
                                        continue;

                                try {
                                        Cookie cookie = new Cookie.Builder()
                                                        .name(name)
                                                        .value(value)
                                                        .domain(url.host())
                                                        .path("/")
                                                        .build();
                                        cookieJar.addCookie(url, cookie);
                                        Log.d(TAG, "Added cookie: " + name + "=" + value);
                                } catch (Exception e) {
                                        Log.w(TAG, "Skipped invalid cookie: " + name + " - " + e.getMessage());
                                }
                        }
                }
        }

        // 创建带有正确请求头的Request.Builder
        private Request.Builder createRequestBuilder(SchoolConfig school) {
                return new Request.Builder()
                                .header("Accept",
                                                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
                                .header("Accept-Language", "zh-CN,zh;q=0.9")
                                .header("User-Agent",
                                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36")
                                .header("Origin", school.getBaseUrl())
                                .header("Referer", school.getCourseReferer());
        }

        // 验证 Cookie 是否有效（尝试获取学生信息页面）
        public void validateCookie(SchoolConfig school, Callback callback) {
                String url = school.getStudentInfoUrl();
                Log.d(TAG, "Validating cookie with URL: " + url);

                Request request = createRequestBuilder(school)
                                .url(url)
                                .build();
                client.newCall(request).enqueue(callback);
        }

        // 获取选课页面参数 (Index页面) - 强制网络刷新
        public void fetchCourseParams(SchoolConfig school, Callback callback) {
                String url = school.getCourseSelectionParamsUrl();
                Log.d(TAG, "Fetching course params from: " + url);

                Request request = createRequestBuilder(school)
                                .url(url)
                                .cacheControl(okhttp3.CacheControl.FORCE_NETWORK) // Prevent caching
                                .build();
                client.newCall(request).enqueue(callback);
        }

        // 获取完整参数 (Display页面) - Web版本的 getCompleteParameters
        public void fetchCourseDisplayParams(SchoolConfig school, String xkkz_id, String kklxdm,
                        String njdm_id, String zyh_id, Callback callback) {
                // URL: zzxkyzb_cxZzxkYzbDisplay.html
                String url = school.getFullBasePath() + school.courseDisplayPath + "?gnmkdm=" + school.courseGnmkdm;
                Log.d(TAG, "Fetching display params from: " + url);

                // 构建POST参数 (与Web版相同)
                String postBody = "xkkz_id=" + (xkkz_id != null ? xkkz_id : "") +
                                "&kklxdm=" + (kklxdm != null ? kklxdm : "01") +
                                "&xszxzt=1" +
                                "&njdm_id=" + (njdm_id != null ? njdm_id : "2024") +
                                "&zyh_id=" + (zyh_id != null ? zyh_id : "") +
                                "&kspage=0" +
                                "&jspage=0";

                Log.d(TAG, "Display POST body: " + postBody);

                Request request = createRequestBuilder(school)
                                .url(url)
                                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                                .post(okhttp3.RequestBody.create(postBody,
                                                okhttp3.MediaType.parse("application/x-www-form-urlencoded")))
                                .build();
                client.newCall(request).enqueue(callback);
        }

        // 获取可选课程列表
        public void fetchAvailableCourses(SchoolConfig school, String postBody, Callback callback) {
                String url = school.getAvailableCoursesUrl();
                Log.d(TAG, "Fetching available courses from: " + url);

                Request.Builder builder = createRequestBuilder(school)
                                .url(url)
                                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                                .header("X-Requested-With", "XMLHttpRequest");

                if (postBody != null && !postBody.isEmpty()) {
                        builder.post(okhttp3.RequestBody.create(postBody,
                                        okhttp3.MediaType.parse("application/x-www-form-urlencoded")));
                }

                client.newCall(builder.build()).enqueue(callback);
        }

        // 获取已选课程列表
        public void fetchSelectedCourses(SchoolConfig school, String postBody, Callback callback) {
                String url = school.getSelectedCoursesUrl();
                Log.d(TAG, "Fetching selected courses from: " + url);

                Request.Builder builder = createRequestBuilder(school)
                                .url(url)
                                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                                .header("X-Requested-With", "XMLHttpRequest");

                if (postBody != null && !postBody.isEmpty()) {
                        builder.post(okhttp3.RequestBody.create(postBody,
                                        okhttp3.MediaType.parse("application/x-www-form-urlencoded")));
                }

                client.newCall(builder.build()).enqueue(callback);
        }

        // 执行选课 (Step 3: 使用加密的jxb_ids)
        public void selectCourse(SchoolConfig school, String postBody, Callback callback) {
                String url = school.getSelectCourseUrl();
                Log.d(TAG, "Selecting course at: " + url);
                Log.d(TAG, "POST body: " + postBody);

                Request request = createRequestBuilder(school)
                                .url(url)
                                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                                .header("X-Requested-With", "XMLHttpRequest")
                                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                                .post(okhttp3.RequestBody.create(postBody,
                                                okhttp3.MediaType.parse("application/x-www-form-urlencoded")))
                                .build();

                client.newCall(request).enqueue(callback);
        }

        // 获取选课详情 (Step 2: 获取加密的do_jxb_id) - 完整参数版本
        public void fetchCourseSelectionDetails(SchoolConfig school, String postBody, Callback callback) {
                String url = school.getCourseSelectionDetailsUrl();
                Log.d(TAG, "Fetching course selection details from: " + url);
                Log.d(TAG, "Details POST body: " + postBody);

                Request request = createRequestBuilder(school)
                                .url(url)
                                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                                .header("X-Requested-With", "XMLHttpRequest")
                                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                                .post(okhttp3.RequestBody.create(postBody,
                                                okhttp3.MediaType.parse("application/x-www-form-urlencoded")))
                                .build();

                client.newCall(request).enqueue(callback);
        }

        // 获取选课详情 (Step 2: 获取加密的do_jxb_id) - 简化参数版本 (旧版兼容)
        public void fetchCourseSelectionDetails(SchoolConfig school, String kch_id, String xkkz_id,
                        String njdm_id, String zyh_id, String kklxdm, String xqh_id, String jg_id,
                        String rwlx, String xklc, Callback callback) {
                String url = school.getCourseSelectionDetailsUrl();
                Log.d(TAG, "Fetching course selection details from: " + url);

                // 构建POST参数
                String postBody = "kch_id=" + kch_id +
                                "&xkkz_id=" + (xkkz_id != null ? xkkz_id : "") +
                                "&njdm_id=" + (njdm_id != null ? njdm_id : "2024") +
                                "&zyh_id=" + (zyh_id != null ? zyh_id : "") +
                                "&kklxdm=" + (kklxdm != null ? kklxdm : "01") +
                                "&xqh_id=" + (xqh_id != null ? xqh_id : "") +
                                "&jg_id=" + (jg_id != null ? jg_id : "") +
                                "&rwlx=" + (rwlx != null ? rwlx : "1") +
                                "&xklc=" + (xklc != null ? xklc : "2");

                Log.d(TAG, "Details POST body: " + postBody);

                Request request = createRequestBuilder(school)
                                .url(url)
                                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                                .header("X-Requested-With", "XMLHttpRequest")
                                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                                .post(okhttp3.RequestBody.create(postBody,
                                                okhttp3.MediaType.parse("application/x-www-form-urlencoded")))
                                .build();

                client.newCall(request).enqueue(callback);
        }

        // 获取课表 (POST with xnm/xqm params)
        public void fetchSchedule(SchoolConfig school, String postBody, Callback callback) {
                String url = school.getScheduleUrl();
                Log.d(TAG, "Fetching schedule from: " + url);
                Log.d(TAG, "Schedule POST body: " + postBody);

                Request request = createRequestBuilder(school)
                                .url(url)
                                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                                .header("X-Requested-With", "XMLHttpRequest")
                                .post(okhttp3.RequestBody.create(postBody,
                                                okhttp3.MediaType.parse("application/x-www-form-urlencoded")))
                                .build();
                client.newCall(request).enqueue(callback);
        }

        // 获取成绩 (单学期)
        public void fetchGrades(SchoolConfig school, String semester, Callback callback) {
                String url = school.getGradesUrl(semester);
                Log.d(TAG, "Fetching grades from: " + url);

                Request request = createRequestBuilder(school)
                                .url(url)
                                .header("X-Requested-With", "XMLHttpRequest")
                                .build();
                client.newCall(request).enqueue(callback);
        }

        // 获取考试安排
        public void fetchExamSchedule(SchoolConfig school, String xnm, String xqm, Callback callback) {
                String url = school.getBaseUrl() + "/kwgl/kscx_cxXsksxxIndex.html?doType=query&gnmkdm=N358105";
                Log.d(TAG, "Fetching exam schedule from: " + url);

                String postBody = "xnm=" + xnm + "&xqm=" + xqm;
                Log.d(TAG, "Exam schedule POST body: " + postBody);

                Request request = createRequestBuilder(school)
                                .url(url)
                                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                                .header("X-Requested-With", "XMLHttpRequest")
                                .post(okhttp3.RequestBody.create(postBody,
                                                okhttp3.MediaType.parse("application/x-www-form-urlencoded")))
                                .build();
                client.newCall(request).enqueue(callback);
        }

        // 获取总体成绩参数页面 (Step 1: GET HTML page to extract xfyqjd_id)
        public void fetchOverallGradesIndex(SchoolConfig school, Callback callback) {
                String url = school.getOverallGradesUrl();
                Log.d(TAG, "Fetching overall grades index from: " + url);

                Request request = createRequestBuilder(school)
                                .url(url)
                                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                                .build();
                client.newCall(request).enqueue(callback);
        }

        // 获取总体成绩数据 (Step 2: POST with xfyqjd_id to get grades)
        public void fetchOverallGradesData(SchoolConfig school, String postBody, Callback callback) {
                String url = school.getOverallGradesDataUrl();
                Log.d(TAG, "Fetching overall grades data from: " + url);
                Log.d(TAG, "POST body: " + postBody);

                Request request = createRequestBuilder(school)
                                .url(url)
                                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                                .header("X-Requested-With", "XMLHttpRequest")
                                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                                .post(okhttp3.RequestBody.create(postBody,
                                                okhttp3.MediaType.parse("application/x-www-form-urlencoded")))
                                .build();
                client.newCall(request).enqueue(callback);
        }

        // 旧的接口 - 兼容性 (已弃用)
        public void fetchCourses(String baseUrl, String studentId, String name, Callback callback) {
                Log.d(TAG, "Fetching courses for: " + studentId);
                Request request = new Request.Builder()
                                .url(baseUrl + "/jwglxt/xsxk/zzxkyzb_cxZzxkYzbIndex.html?gnmkdm=N253512")
                                .header("User-Agent", "Mozilla/5.0")
                                .build();
                client.newCall(request).enqueue(callback);
        }

        // 内部类 CookieJar (线程安全版)
        private static class CookieJarImpl implements CookieJar {
                private final HashMap<String, List<Cookie>> cookieStore = new HashMap<>();
                private final Object lock = new Object();

                @Override
                public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
                        synchronized (lock) {
                                List<Cookie> existing = cookieStore.get(url.host());
                                if (existing == null) {
                                        existing = new ArrayList<>();
                                        cookieStore.put(url.host(), existing);
                                }
                                for (Cookie cookie : cookies) {
                                        // 使用 Iterator 避免 ConcurrentModificationException
                                        java.util.Iterator<Cookie> it = existing.iterator();
                                        while (it.hasNext()) {
                                                if (it.next().name().equals(cookie.name())) {
                                                        it.remove();
                                                }
                                        }
                                        existing.add(cookie);
                                }
                        }
                }

                @Override
                public List<Cookie> loadForRequest(HttpUrl url) {
                        synchronized (lock) {
                                List<Cookie> cookies = cookieStore.get(url.host());
                                return cookies != null ? new ArrayList<>(cookies) : new ArrayList<>();
                        }
                }

                public void addCookie(HttpUrl url, Cookie cookie) {
                        synchronized (lock) {
                                List<Cookie> cookies = cookieStore.get(url.host());
                                if (cookies == null) {
                                        cookies = new ArrayList<>();
                                        cookieStore.put(url.host(), cookies);
                                }
                                java.util.Iterator<Cookie> it = cookies.iterator();
                                while (it.hasNext()) {
                                        if (it.next().name().equals(cookie.name())) {
                                                it.remove();
                                        }
                                }
                                cookies.add(cookie);
                        }
                }

                public void clear() {
                        synchronized (lock) {
                                cookieStore.clear();
                        }
                }
        }

        // ============================================
        // 同步方法（用于批量抢课）
        // ============================================

        // 同步获取选课详情 - 完整参数版本
        public String fetchCourseSelectionDetailsSync(SchoolConfig school, String postBody) {
                String url = school.getCourseSelectionDetailsUrl();
                Log.d(TAG, "Sync fetching course selection details from: " + url);
                Log.d(TAG, "Details POST body: " + postBody);

                Request request = createRequestBuilder(school)
                                .url(url)
                                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                                .header("X-Requested-With", "XMLHttpRequest")
                                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                                .post(okhttp3.RequestBody.create(postBody,
                                                okhttp3.MediaType.parse("application/x-www-form-urlencoded")))
                                .build();

                try {
                        okhttp3.Response response = client.newCall(request).execute();
                        if (response.body() != null) {
                                return response.body().string();
                        }
                } catch (Exception e) {
                        Log.e(TAG, "fetchCourseSelectionDetailsSync error: " + e.getMessage());
                }
                return null;
        }

        // 同步获取选课详情 - 简化参数版本 (旧版兼容)
        public String fetchCourseSelectionDetailsSync(SchoolConfig school, String kch_id, String xkkz_id,
                        String njdm_id, String zyh_id, String kklxdm, String xqh_id, String jg_id,
                        String rwlx, String xklc) {
                String url = school.getCourseSelectionDetailsUrl();
                Log.d(TAG, "Sync fetching course selection details from: " + url);

                String postBody = "kch_id=" + kch_id +
                                "&xkkz_id=" + (xkkz_id != null ? xkkz_id : "") +
                                "&njdm_id=" + (njdm_id != null ? njdm_id : "2024") +
                                "&zyh_id=" + (zyh_id != null ? zyh_id : "") +
                                "&kklxdm=" + (kklxdm != null ? kklxdm : "01") +
                                "&xqh_id=" + (xqh_id != null ? xqh_id : "") +
                                "&jg_id=" + (jg_id != null ? jg_id : "") +
                                "&rwlx=" + (rwlx != null ? rwlx : "1") +
                                "&xklc=" + (xklc != null ? xklc : "2");

                Request request = createRequestBuilder(school)
                                .url(url)
                                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                                .header("X-Requested-With", "XMLHttpRequest")
                                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                                .post(okhttp3.RequestBody.create(postBody,
                                                okhttp3.MediaType.parse("application/x-www-form-urlencoded")))
                                .build();

                try {
                        okhttp3.Response response = client.newCall(request).execute();
                        if (response.body() != null) {
                                return response.body().string();
                        }
                } catch (Exception e) {
                        Log.e(TAG, "fetchCourseSelectionDetailsSync error: " + e.getMessage());
                }
                return null;
        }

        // 同步执行选课
        public String selectCourseSync(SchoolConfig school, String postBody) {
                String url = school.getSelectCourseUrl();
                Log.d(TAG, "Sync selecting course at: " + url);

                Request request = createRequestBuilder(school)
                                .url(url)
                                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                                .header("X-Requested-With", "XMLHttpRequest")
                                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                                .post(okhttp3.RequestBody.create(postBody,
                                                okhttp3.MediaType.parse("application/x-www-form-urlencoded")))
                                .build();

                try {
                        okhttp3.Response response = client.newCall(request).execute();
                        if (response.body() != null) {
                                return response.body().string();
                        }
                } catch (Exception e) {
                        Log.e(TAG, "selectCourseSync error: " + e.getMessage());
                }
                return null;
        }

        // ============================================
        // Web版兼容方法 - 获取页面隐藏参数和验证选课
        // ============================================

        // 同步获取页面隐藏参数 (Web版 getPageHiddenParams)
        public String fetchPageHiddenParamsSync(SchoolConfig school) {
                String url = school.getCourseSelectionParamsUrl();
                Log.d(TAG, "Sync fetching page hidden params from: " + url);

                Request request = createRequestBuilder(school)
                                .url(url)
                                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                                .get()
                                .build();

                try {
                        okhttp3.Response response = client.newCall(request).execute();
                        if (response.body() != null) {
                                return response.body().string();
                        }
                } catch (Exception e) {
                        Log.e(TAG, "fetchPageHiddenParamsSync error: " + e.getMessage());
                }
                return null;
        }

        // 同步获取已选课程 (用于验证选课是否成功)
        public String fetchSelectedCoursesSync(SchoolConfig school, String postBody) {
                String url = school.getSelectedCoursesUrl();
                Log.d(TAG, "Sync fetching selected courses from: " + url);

                Request.Builder builder = createRequestBuilder(school)
                                .url(url)
                                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                                .header("X-Requested-With", "XMLHttpRequest")
                                .header("Accept", "application/json, text/javascript, */*; q=0.01");

                if (postBody != null && !postBody.isEmpty()) {
                        builder.post(okhttp3.RequestBody.create(postBody,
                                        okhttp3.MediaType.parse("application/x-www-form-urlencoded")));
                } else {
                        builder.get();
                }

                try {
                        okhttp3.Response response = client.newCall(builder.build()).execute();
                        if (response.body() != null) {
                                return response.body().string();
                        }
                } catch (Exception e) {
                        Log.e(TAG, "fetchSelectedCoursesSync error: " + e.getMessage());
                }
                return null;
        }

        // 同步退课 (Drop course synchronously)
        public String dropCourseSync(SchoolConfig school, String kchId, String jxbIds, String xkxnm, String xkxqm) {
                // URL: /xsxk/zzxkyzb_tuikBcZzxkYzb.html?gnmkdm=N253512
                String url = school.getFullBasePath() + "/xsxk/zzxkyzb_tuikBcZzxkYzb.html?gnmkdm="
                                + school.courseGnmkdm;
                Log.d(TAG, "Dropping course at: " + url);

                String postBody = "kch_id=" + kchId + "&jxb_ids=" + jxbIds + "&xkxnm=" + xkxnm + "&xkxqm=" + xkxqm
                                + "&txbsfrl=0";
                Log.d(TAG, "Drop course POST body: " + postBody);

                Request request = createRequestBuilder(school)
                                .url(url)
                                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                                .header("X-Requested-With", "XMLHttpRequest")
                                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                                .post(okhttp3.RequestBody.create(postBody,
                                                okhttp3.MediaType.parse("application/x-www-form-urlencoded")))
                                .build();

                try {
                        okhttp3.Response response = client.newCall(request).execute();
                        if (response.body() != null) {
                                String result = response.body().string();
                                Log.d(TAG, "Drop course response: " + result);
                                return result;
                        }
                } catch (Exception e) {
                        Log.e(TAG, "dropCourseSync error: " + e.getMessage());
                }
                return null;
        }
}
