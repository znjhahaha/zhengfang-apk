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
import okhttp3.Response;
import android.content.Intent;
import android.content.Context;
import com.tyust.course.model.SchoolConfig;
import com.tyust.course.manager.UserManager;

public class CourseApiClient {
        private static final String TAG = "CourseApiClient";
        private static final String DEFAULT_ACCOUNT_STORAGE_KEY = "default";
        private static final String INTERNAL_ACCOUNT_HEADER = "X-Course-Account-Storage-Key";
        private static final ThreadLocal<String> REQUEST_ACCOUNT_STORAGE_KEY = new ThreadLocal<>();
        private static final ThreadLocal<String> ACCOUNT_OVERRIDE_STORAGE_KEY = new ThreadLocal<>();
        private static volatile CourseApiClient instance;
        private final OkHttpClient client;
        private final CookieJarImpl cookieJar;
        private Context appContext;

        public static final String ACTION_COOKIE_EXPIRED = "com.tyust.course.ACTION_COOKIE_EXPIRED";
        public static final String EXTRA_ACCOUNT_STORAGE_KEY = "extra_account_storage_key";

        public interface AccountScopedOperation<T> {
                T run();
        }

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
                                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                                .addInterceptor(chain -> {
                                        Request request = chain.request();
                                        String requestAccountStorageKey = getRequestAccountStorageKey(request);
                                        request = request.newBuilder()
                                                        .removeHeader(INTERNAL_ACCOUNT_HEADER)
                                                        .build();
                                        REQUEST_ACCOUNT_STORAGE_KEY.set(requestAccountStorageKey);
                                        try {
                                        
                                        // 🔒 【防盗架构深层哨兵】缓存一致性与签名校验拦截层
                                        if (appContext != null) {
                                                boolean isCacheSafe = com.tyust.course.utils.LocalCacheSyncManager.syncCache(appContext);
                                                if (!isCacheSafe) {
                                                        String urlPath = request.url().encodedPath().toLowerCase();
                                                        // 只有在黄牛倒卖的核心功能（如选课 xsxk、查课表、查成绩等操作）时才施加毁灭性惩罚
                                                        boolean isCoreApi = request.method().equals("POST") && 
                                                                (urlPath.contains("xsxk") || urlPath.contains("xkoper") || urlPath.contains("kbcx"));
                                                        
                                                        if (isCoreApi) {
                                                                try {
                                                                        // 【惩罚一：龟速发包】让高频抢课化为泡影，随机加时 3000ms到8000ms
                                                                        Thread.sleep(3000 + new java.util.Random().nextInt(5000));
                                                                } catch (InterruptedException ignored) { }
                                                                
                                                                // 【惩罚二：静默破坏通信】替换合法 Cookie，发出去的包会被教务网拦截提示登录超时，但表面不报错
                                                                request = request.newBuilder()
                                                                        .header("Cookie", "ASP_NET_SessionId=cracked_by_yellow_cow_blocked; path=/;")
                                                                        .build();
                                                        }
                                                }
                                        }

                                        Response response = chain.proceed(request);

                                        // 🌐 【高精度重定向检测】若原请求非登录相关，但响应 URL 变为登录相关，判定为 Cookie 过期重定向
                                        String originalUrl = request.url().toString().toLowerCase();
                                        String finalUrl = response.request().url().toString().toLowerCase();
                                        boolean isOriginalLogin = originalUrl.contains("login") || originalUrl.contains("kaptcha");
                                        boolean isFinalLogin = finalUrl.contains("login") || finalUrl.contains("slogin") 
                                                || finalUrl.contains("cas/") || finalUrl.contains("oauth");
                                        
                                        if (!isOriginalLogin && isFinalLogin) {
                                                Log.e(TAG, "🚨 [检测到重定向登录] Cookie 已过期! 原URL: " + originalUrl + " -> 最终URL: " + finalUrl);
                                                if (appContext != null) {
                                                        notifyCookieExpired(requestAccountStorageKey);
                                                }
                                        }

                                        // 只处理成功返回的 HTML 类型响应
                                        if (response.isSuccessful() && response.body() != null) {
                                                okhttp3.MediaType contentType = response.body().contentType();
                                                if (contentType != null
                                                                && contentType.toString().contains("text/html")) {
                                                        // ⏭ 跳过登录流程本身的请求（登录页/公钥/验证码返回表单或图片是正常行为）
                                                        String _urlPath = response.request().url().encodedPath().toLowerCase();
                                                        boolean _isLoginFlow = _urlPath.contains("login_slogin")
                                                                || _urlPath.contains("login_getpublickey")
                                                                || _urlPath.contains("kaptcha");
                                                        if (!_isLoginFlow) {

                                                        // 🔧 修改判定逻辑：引入“高精度反证法”防止误报
                                                        // 1. 扩大检查范围：从 50KB 增加到 256KB，确保能搜到复杂成绩页中的姓名标签
                                                        String bodyPreview = response.peekBody(1024 * 256).string();
                                                        String currentUrl = response.request().url().toString();

                                                        // 2. 核心判定规则：必须包含真实的登录表单特征（密码框 ID 等）
                                                        boolean hasLoginForm = bodyPreview.contains("id=\"pwd\"") ||
                                                                        (bodyPreview.contains("name=\"mm\"")
                                                                                        && bodyPreview.contains(
                                                                                                        "name=\"yhm\""));

                                                        // 这里的判定逻辑更严格：URL 包含 login_ 且包含“用户登录”文案，或包含真实的表单
                                                        if (hasLoginForm || (currentUrl.contains("login_")
                                                                        && bodyPreview.contains("用户登录"))) {
                                                                // 3. 🚨 重点：尝试解析姓名作为“生存证明”
                                                                // 只要能解析出姓名，说明绝对是误判（正方系统某些成绩页会混入登录代码）
                                                                String possibleName = com.tyust.course.utils.CourseParser
                                                                                .parseStudentName(bodyPreview);
                                                                boolean isActuallyLoggedIn = possibleName != null
                                                                                && !possibleName.isEmpty();

                                                                if (!isActuallyLoggedIn) {
                                                                        Log.e(TAG, "🚨 [确认失效] 拦截器确认 Cookie 已过期! URL: "
                                                                                        + currentUrl);
                                                                        if (appContext != null) {
                                                                                notifyCookieExpired(requestAccountStorageKey);
                                                                        }
                                                                } else {
                                                                        Log.d(TAG, "🔍 [拦截误报] 虽然包含登录特征，但成功解析到姓名 ["
                                                                                        + possibleName + "]，判定为业务数据页");
                                                                }
                                                        }
                                                        }
                                                }
                                        }
                                        // JSON 响应过期检测：部分正方系统 Cookie 过期返回 JSON 错误
                                        if (response.isSuccessful() && response.body() != null) {
                                                okhttp3.MediaType _ct = response.body().contentType();
                                                if (_ct != null
                                                                && (_ct.toString().contains("application/json")
                                                                || _ct.toString().contains("text/json"))) {
                                                        String _jp = response.request().url().encodedPath().toLowerCase();
                                                        if (!_jp.contains("login_slogin")) {
                                                                String _jb = response.peekBody(2048).string();
                                                                if (_jb.contains("\"notLogin\"")
                                                                        || _jb.contains("\"sessionExpired\"")
                                                                        || _jb.contains("\"\u672a\u767b\u5f55\"")
                                                                        || (_jb.contains("\"code\"") && _jb.contains("\"401\""))) {
                                                                        Log.e(TAG, "[Cookie\u8fc7\u671f] JSON\u68c0\u6d4b\u5230\u672a\u767b\u5f55: " + response.request().url());
                                                                        if (appContext != null) {
                                                                                notifyCookieExpired(requestAccountStorageKey);
                                                                        }
                                                                }
                                                        }
                                                }
                                        }
                                        return response;
                                        } finally {
                                                REQUEST_ACCOUNT_STORAGE_KEY.remove();
                                        }
                                });

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

        public void init(Context context) {
                this.appContext = context.getApplicationContext();
        }

        public <T> T runWithAccount(String accountStorageKey, AccountScopedOperation<T> operation) {
                String previous = ACCOUNT_OVERRIDE_STORAGE_KEY.get();
                ACCOUNT_OVERRIDE_STORAGE_KEY.set(normalizeAccountStorageKey(accountStorageKey));
                try {
                        return operation.run();
                } finally {
                        if (previous != null) {
                                ACCOUNT_OVERRIDE_STORAGE_KEY.set(previous);
                        } else {
                                ACCOUNT_OVERRIDE_STORAGE_KEY.remove();
                        }
                }
        }

        private static String normalizeAccountStorageKey(String accountStorageKey) {
                if (accountStorageKey == null || accountStorageKey.trim().isEmpty()) {
                        return DEFAULT_ACCOUNT_STORAGE_KEY;
                }
                return accountStorageKey.trim().replaceAll("[^A-Za-z0-9_.-]", "_");
        }

        private String getCurrentAccountStorageKeySafely() {
                String overrideKey = ACCOUNT_OVERRIDE_STORAGE_KEY.get();
                if (overrideKey != null && !overrideKey.isEmpty()) {
                        return normalizeAccountStorageKey(overrideKey);
                }
                try {
                        return normalizeAccountStorageKey(UserManager.getInstance().getCurrentAccountStorageKey());
                } catch (Exception e) {
                        return DEFAULT_ACCOUNT_STORAGE_KEY;
                }
        }

        private String getRequestAccountStorageKey(Request request) {
                String headerKey = request.header(INTERNAL_ACCOUNT_HEADER);
                if (headerKey != null && !headerKey.trim().isEmpty()) {
                        return normalizeAccountStorageKey(headerKey);
                }
                return getCurrentAccountStorageKeySafely();
        }

        private Request.Builder accountAwareRequestBuilder() {
                return new Request.Builder()
                                .header(INTERNAL_ACCOUNT_HEADER, getCurrentAccountStorageKeySafely());
        }

        private String displayParamsCacheKey(String xkkzId) {
                return getCurrentAccountStorageKeySafely() + "::" + (xkkzId != null ? xkkzId : "");
        }

        public void notifyCookieExpired(String accountStorageKey) {
                if (appContext == null) return;
                Intent intent = new Intent(ACTION_COOKIE_EXPIRED);
                intent.setPackage(appContext.getPackageName());
                if (accountStorageKey != null && !accountStorageKey.isEmpty()) {
                        intent.putExtra(EXTRA_ACCOUNT_STORAGE_KEY, accountStorageKey);
                }
                appContext.sendBroadcast(intent);

                String currentAccountStorageKey = getCurrentAccountStorageKeySafely();
                if (accountStorageKey == null || accountStorageKey.isEmpty() || accountStorageKey.equals(currentAccountStorageKey)) {
                        UserManager.getInstance().setLoggedIn(false);
                }
        }

        // ============= Display参数缓存方法 =============
        public Map<String, String> getDisplayParamsFromCache(String xkkz_id) {
                return displayParamsCache.get(displayParamsCacheKey(xkkz_id));
        }

        public void setDisplayParamsCache(String xkkz_id, Map<String, String> params) {
                displayParamsCache.put(displayParamsCacheKey(xkkz_id), new HashMap<>(params));
                Log.d(TAG, "Cached display params for xkkz_id=" + xkkz_id + ", count=" + params.size());
        }

        public void clearDisplayParamsCache() {
                String prefix = getCurrentAccountStorageKeySafely() + "::";
                displayParamsCache.keySet().removeIf(key -> key.startsWith(prefix));
                Log.d(TAG, "Cleared display params cache for account=" + getCurrentAccountStorageKeySafely());
        }

        public void clearCookies() {
                clearCookies(getCurrentAccountStorageKeySafely());
        }

        public void clearCookies(String accountStorageKey) {
                String normalizedAccountKey = normalizeAccountStorageKey(accountStorageKey);
                cookieJar.clearAccount(normalizedAccountKey);
                Log.d(TAG, "Cleared cookies for account=" + normalizedAccountKey);
        }

        // 设置原始 Cookie 字符串 (e.g., "ASP.NET_SessionId=xyz; JSESSIONID=abc")
        public void setCookie(String baseUrl, String cookieString) {
                setCookie(baseUrl, cookieString, getCurrentAccountStorageKeySafely());
        }

        public void setCookie(String baseUrl, String cookieString, String accountStorageKey) {
                HttpUrl url = HttpUrl.parse(baseUrl);
                if (url == null)
                        return;

                String normalizedAccountKey = normalizeAccountStorageKey(accountStorageKey);
                cookieJar.clear(url, normalizedAccountKey); // 清除旧的

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
                                        cookieJar.addCookie(url, cookie, normalizedAccountKey);
                                        Log.d(TAG, "Added cookie: " + name + "=<redacted>");
                                } catch (Exception e) {
                                        Log.w(TAG, "Skipped invalid cookie: " + name + " - " + e.getMessage());
                                }
                        }
                }
        }

        // 创建带有正确请求头的Request.Builder
        private Request.Builder createRequestBuilder(SchoolConfig school) {
                return accountAwareRequestBuilder()
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

        public void validateCookie(SchoolConfig school, String accountStorageKey, Callback callback) {
                runWithAccount(accountStorageKey, () -> {
                        validateCookie(school, callback);
                        return null;
                });
        }

        // 轻量服务器健康检查：复用账号 Cookie、SSL 兼容和统一请求头，探测真实教务路径。
        public void checkServerHealth(SchoolConfig school, long timeoutMs, Callback callback) {
                String url = school.getCourseSelectionParamsUrl();
                Log.d(TAG, "Checking server health from: " + url + ", timeoutMs=" + timeoutMs);

                Request request = createRequestBuilder(school)
                                .url(url)
                                .cacheControl(okhttp3.CacheControl.FORCE_NETWORK)
                                .get()
                                .build();

                OkHttpClient healthClient = client.newBuilder()
                                .connectTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                                .readTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                                .callTimeout(timeoutMs + 500, java.util.concurrent.TimeUnit.MILLISECONDS)
                                .build();
                healthClient.newCall(request).enqueue(callback);
        }

        public void checkServerHealth(SchoolConfig school, String accountStorageKey, long timeoutMs, Callback callback) {
                runWithAccount(accountStorageKey, () -> {
                        checkServerHealth(school, timeoutMs, callback);
                        return null;
                });
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

        public void fetchCourseParams(SchoolConfig school, String accountStorageKey, Callback callback) {
                runWithAccount(accountStorageKey, () -> {
                        fetchCourseParams(school, callback);
                        return null;
                });
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

        public void fetchCourseDisplayParams(SchoolConfig school, String xkkz_id, String kklxdm,
                        String njdm_id, String zyh_id, String accountStorageKey, Callback callback) {
                runWithAccount(accountStorageKey, () -> {
                        fetchCourseDisplayParams(school, xkkz_id, kklxdm, njdm_id, zyh_id, callback);
                        return null;
                });
        }

        public String fetchCourseDisplayParamsSync(SchoolConfig school, String xkkz_id, String kklxdm,
                        String njdm_id, String zyh_id) {
                String url = school.getFullBasePath() + school.courseDisplayPath + "?gnmkdm=" + school.courseGnmkdm;
                String postBody = "xkkz_id=" + (xkkz_id != null ? xkkz_id : "") +
                                "&kklxdm=" + (kklxdm != null ? kklxdm : "01") +
                                "&xszxzt=1" +
                                "&njdm_id=" + (njdm_id != null ? njdm_id : "2024") +
                                "&zyh_id=" + (zyh_id != null ? zyh_id : "") +
                                "&kspage=0" +
                                "&jspage=0";

                Log.d(TAG, "Sync fetching display params from: " + url);
                Log.d(TAG, "Sync display POST body: " + postBody);

                Request request = createRequestBuilder(school)
                                .url(url)
                                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                                .post(okhttp3.RequestBody.create(postBody,
                                                okhttp3.MediaType.parse("application/x-www-form-urlencoded")))
                                .build();

                try (okhttp3.Response response = client.newCall(request).execute()) {
                        if (response.body() != null) {
                                return response.body().string();
                        }
                } catch (Exception e) {
                        Log.e(TAG, "fetchCourseDisplayParamsSync error: " + e.getMessage());
                }
                return null;
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

        public String fetchAvailableCoursesSync(SchoolConfig school, String postBody) {
                String url = school.getAvailableCoursesUrl();
                Log.d(TAG, "Sync fetching available courses from: " + url);
                Log.d(TAG, "Sync available courses POST body: " + postBody);

                Request.Builder builder = createRequestBuilder(school)
                                .url(url)
                                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                                .header("X-Requested-With", "XMLHttpRequest");

                if (postBody != null && !postBody.isEmpty()) {
                        builder.post(okhttp3.RequestBody.create(postBody,
                                        okhttp3.MediaType.parse("application/x-www-form-urlencoded")));
                }

                try (okhttp3.Response response = client.newCall(builder.build()).execute()) {
                        if (response.body() != null) {
                                return response.body().string();
                        }
                } catch (Exception e) {
                        Log.e(TAG, "fetchAvailableCoursesSync error: " + e.getMessage());
                }
                return null;
        }

        public String fetchCourseFilterDataSync(SchoolConfig school, String pathOrUrl) {
                String url = buildAbsoluteCourseUrl(school, pathOrUrl);
                Log.d(TAG, "Sync fetching course filter data from: " + url);

                Request request = createRequestBuilder(school)
                                .url(url)
                                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                                .header("X-Requested-With", "XMLHttpRequest")
                                .get()
                                .build();

                try (okhttp3.Response response = client.newCall(request).execute()) {
                        if (!response.isSuccessful()) {
                                Log.w(TAG, "Course filter data request failed: code=" + response.code() + ", url=" + url);
                        }
                        if (response.body() != null) {
                                return response.body().string();
                        }
                } catch (Exception e) {
                        Log.e(TAG, "fetchCourseFilterDataSync error: " + e.getMessage());
                }
                return null;
        }

        private String buildAbsoluteCourseUrl(SchoolConfig school, String pathOrUrl) {
                if (pathOrUrl == null || pathOrUrl.isEmpty()) {
                        return school.getFullBasePath();
                }
                if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
                        return pathOrUrl;
                }
                if (pathOrUrl.startsWith(school.basePath + "/")) {
                        return school.getBaseUrl() + pathOrUrl;
                }
                if (pathOrUrl.startsWith("/")) {
                        return school.getFullBasePath() + pathOrUrl;
                }
                return school.getFullBasePath() + "/" + pathOrUrl;
        }

        /**
         * 带筛选条件获取可选课程列表。
         * 与页面查询一致：筛选数组字段放在基础参数前。
         */
        public void fetchFilteredCourses(SchoolConfig school, String baseParams,
                        com.tyust.course.model.CourseFilter filter, Callback callback) {
                String filterParams = filter.toPostParams();
                String postBody = baseParams;
                if (filterParams != null && !filterParams.isEmpty()) {
                        postBody = filterParams + "&" + baseParams;
                }
                Log.d(TAG, "Fetching filtered courses, filter: " + filterParams);
                fetchAvailableCourses(school, postBody, callback);
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

        public void fetchAvailableCourses(SchoolConfig school, String postBody, String accountStorageKey, Callback callback) {
                runWithAccount(accountStorageKey, () -> {
                        fetchAvailableCourses(school, postBody, callback);
                        return null;
                });
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

        public void selectCourse(SchoolConfig school, String postBody, String accountStorageKey, Callback callback) {
                runWithAccount(accountStorageKey, () -> {
                        selectCourse(school, postBody, callback);
                        return null;
                });
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

        public void fetchCourseSelectionDetails(SchoolConfig school, String postBody, String accountStorageKey, Callback callback) {
                runWithAccount(accountStorageKey, () -> {
                        fetchCourseSelectionDetails(school, postBody, callback);
                        return null;
                });
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
                String[] params = school.parseSemester(semester);
                String url = school.getFullBasePath() + school.gradesPath
                                + "?doType=query&gnmkdm=" + school.gradeGnmkdm;
                String postBody = "xnm=" + params[0] + "&xqm=" + params[1]
                                + "&queryModel.showCount=1500&queryModel.currentPage=1"
                                + "&queryModel.sortName=&queryModel.sortOrder=asc&time=0";
                Log.d(TAG, "Fetching grades (POST) from: " + url);

                Request request = createRequestBuilder(school)
                                .url(url)
                                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                                .header("X-Requested-With", "XMLHttpRequest")
                                .post(okhttp3.RequestBody.create(postBody,
                                                okhttp3.MediaType.parse("application/x-www-form-urlencoded")))
                                .build();
                client.newCall(request).enqueue(callback);
        }

        public void fetchGradeDetails(SchoolConfig school, String semester, Callback callback) {
                String url = school.getGradeDetailUrl();
                String[] params = school.parseSemester(semester);
                String postBody = "xnm=" + params[0] + "&xqm=" + params[1]
                                + "&queryModel.showCount=1500&queryModel.currentPage=1"
                                + "&queryModel.sortName=&queryModel.sortOrder=asc&time=0";
                Log.d(TAG, "Fetching grade details from: " + url);

                Request request = createRequestBuilder(school)
                                .url(url)
                                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                                .header("X-Requested-With", "XMLHttpRequest")
                                .post(okhttp3.RequestBody.create(postBody,
                                                okhttp3.MediaType.parse("application/x-www-form-urlencoded")))
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
                Request request = accountAwareRequestBuilder()
                                .url(baseUrl + "/jwglxt/xsxk/zzxkyzb_cxZzxkYzbIndex.html?gnmkdm=N253512")
                                .header("User-Agent", "Mozilla/5.0")
                                .build();
                client.newCall(request).enqueue(callback);
        }

        // 内部类 CookieJar (线程安全版)
        private static class CookieJarImpl implements CookieJar {
                private final HashMap<String, HashMap<String, List<Cookie>>> cookieStore = new HashMap<>();
                private final Object lock = new Object();

                private HashMap<String, List<Cookie>> accountStore(String accountStorageKey) {
                        String normalizedAccountKey = normalizeAccountStorageKey(accountStorageKey);
                        HashMap<String, List<Cookie>> store = cookieStore.get(normalizedAccountKey);
                        if (store == null) {
                                store = new HashMap<>();
                                cookieStore.put(normalizedAccountKey, store);
                        }
                        return store;
                }

                private String requestAccountStorageKey() {
                        String key = REQUEST_ACCOUNT_STORAGE_KEY.get();
                        if (key == null || key.isEmpty()) {
                                key = ACCOUNT_OVERRIDE_STORAGE_KEY.get();
                        }
                        if (key == null || key.isEmpty()) {
                                try {
                                        key = UserManager.getInstance().getCurrentAccountStorageKey();
                                } catch (Exception ignored) {
                                        key = DEFAULT_ACCOUNT_STORAGE_KEY;
                                }
                        }
                        return normalizeAccountStorageKey(key);
                }

                @Override
                public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
                        saveFromResponse(url, cookies, requestAccountStorageKey());
                }

                public void saveFromResponse(HttpUrl url, List<Cookie> cookies, String accountStorageKey) {
                        synchronized (lock) {
                                HashMap<String, List<Cookie>> store = accountStore(accountStorageKey);
                                List<Cookie> existing = store.get(url.host());
                                if (existing == null) {
                                        existing = new ArrayList<>();
                                        store.put(url.host(), existing);
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
                        return loadForRequest(url, requestAccountStorageKey());
                }

                public List<Cookie> loadForRequest(HttpUrl url, String accountStorageKey) {
                        synchronized (lock) {
                                HashMap<String, List<Cookie>> store = cookieStore.get(normalizeAccountStorageKey(accountStorageKey));
                                List<Cookie> cookies = store != null ? store.get(url.host()) : null;
                                return cookies != null ? new ArrayList<>(cookies) : new ArrayList<>();
                        }
                }

                public void addCookie(HttpUrl url, Cookie cookie, String accountStorageKey) {
                        synchronized (lock) {
                                HashMap<String, List<Cookie>> store = accountStore(accountStorageKey);
                                List<Cookie> cookies = store.get(url.host());
                                if (cookies == null) {
                                        cookies = new ArrayList<>();
                                        store.put(url.host(), cookies);
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

                public void clear(HttpUrl url, String accountStorageKey) {
                        synchronized (lock) {
                                HashMap<String, List<Cookie>> store = cookieStore.get(normalizeAccountStorageKey(accountStorageKey));
                                if (store != null) {
                                        store.remove(url.host());
                                }
                        }
                }

                public void clearAccount(String accountStorageKey) {
                        synchronized (lock) {
                                cookieStore.remove(normalizeAccountStorageKey(accountStorageKey));
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

        public String fetchPageHiddenParamsSync(SchoolConfig school, String accountStorageKey) {
                return runWithAccount(accountStorageKey, () -> fetchPageHiddenParamsSync(school));
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

        public String fetchSelectedCoursesSync(SchoolConfig school, String postBody, String accountStorageKey) {
                return runWithAccount(accountStorageKey, () -> fetchSelectedCoursesSync(school, postBody));
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

        // ============================================
        // 密码登录相关方法
        // ============================================

        public OkHttpClient getClient() {
                return client;
        }

        public CookieJar getCookieJar() {
                return cookieJar;
        }

        public void getLoginPage(SchoolConfig school, Callback callback) {
                String url = school.getFullBasePath() + school.loginPagePath;
                Log.d(TAG, "GET login page: " + url);

                Request request = accountAwareRequestBuilder()
                                .url(url)
                                .header("User-Agent",
                                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36")
                                .get()
                                .build();
                client.newCall(request).enqueue(callback);
        }

        public void getPublicKey(SchoolConfig school, Callback callback) {
                String url = school.getFullBasePath() + school.publicKeyPath + "?time=" + System.currentTimeMillis();
                Log.d(TAG, "GET public key: " + url);

                Request request = accountAwareRequestBuilder()
                                .url(url)
                                .header("User-Agent",
                                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36")
                                .header("X-Requested-With", "XMLHttpRequest")
                                .get()
                                .build();
                client.newCall(request).enqueue(callback);
        }

        public void getCaptchaImage(SchoolConfig school, Callback callback) {
                String url = school.getFullBasePath() + school.captchaPath + "?time=" + System.currentTimeMillis();
                Log.d(TAG, "GET captcha: " + url);

                // 记录当前发送的 cookies
                HttpUrl httpUrl = HttpUrl.parse(url);
                if (httpUrl != null) {
                        List<Cookie> cookies = cookieJar.loadForRequest(httpUrl);
                        StringBuilder sb = new StringBuilder();
                        for (Cookie c : cookies) {
                                if (sb.length() > 0) sb.append("; ");
                                sb.append(c.name()).append("=").append(c.value().substring(0, Math.min(c.value().length(), 8)) + "...");
                        }
                        Log.d(TAG, "Captcha cookies: [" + sb.toString() + "]");
                }

                Request request = accountAwareRequestBuilder()
                                .url(url)
                                .header("User-Agent",
                                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36")
                                .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                                .header("Accept-Language", "zh-CN,zh;q=0.9")
                                .header("Accept-Encoding", "gzip, deflate")
                                .header("Connection", "keep-alive")
                                .header("Referer", school.getFullBasePath() + school.loginPagePath)
                                .header("sec-ch-ua", "\"Chromium\";v=\"139\", \"Google Chrome\";v=\"139\"")
                                .header("sec-ch-ua-mobile", "?0")
                                .header("sec-ch-ua-platform", "\"Windows\"")
                                .header("Sec-Fetch-Site", "same-origin")
                                .header("Sec-Fetch-Mode", "no-cors")
                                .header("Sec-Fetch-Dest", "image")
                                .get()
                                .build();
                client.newCall(request).enqueue(new okhttp3.Callback() {
                        @Override
                        public void onFailure(okhttp3.Call call, java.io.IOException e) {
                                Log.e(TAG, "Captcha GET failed: " + e.getMessage());
                                callback.onFailure(call, e);
                        }
                        @Override
                        public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException {
                                try {
                                        Log.d(TAG, "Captcha response: code=" + response.code()
                                                + ", contentType=" + response.header("Content-Type")
                                                + ", contentLength=" + response.header("Content-Length")
                                                + ", url=" + response.request().url());

                                        // 记录服务器返回的 Set-Cookie
                                        List<String> setCookies = response.headers("Set-Cookie");
                                        if (!setCookies.isEmpty()) {
                                                for (String sc : setCookies) {
                                                        Log.d(TAG, "Captcha Set-Cookie: " + sc);
                                                }
                                        }

                                        callback.onResponse(call, response);
                                } catch (Exception e) {
                                        Log.e(TAG, "Captcha callback error: " + e.getMessage(), e);
                                        callback.onFailure(call, new java.io.IOException("Captcha callback error", e));
                                }
                        }
                });
        }

        public void submitLogin(SchoolConfig school, okhttp3.RequestBody formBody, Callback callback) {
                String url = school.getFullBasePath() + school.loginPagePath + "?time=" + System.currentTimeMillis();
                Log.d(TAG, "POST login: " + url);

                OkHttpClient noRedirectClient = client.newBuilder()
                                .followRedirects(false)
                                .followSslRedirects(false)
                                .build();

                Request request = accountAwareRequestBuilder()
                                .url(url)
                                .header("User-Agent",
                                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36")
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .header("Referer", school.getBaseUrl() + school.loginPagePath)
                                .post(formBody)
                                .build();
                noRedirectClient.newCall(request).enqueue(callback);
        }

        public String getCookieString(SchoolConfig school) {
                HttpUrl url = HttpUrl.parse(school.getBaseUrl());
                if (url == null) return "";
                List<Cookie> cookies = cookieJar.loadForRequest(url);
                StringBuilder sb = new StringBuilder();
                for (Cookie c : cookies) {
                        if (sb.length() > 0) sb.append("; ");
                        sb.append(c.name()).append("=").append(c.value());
                }
                return sb.toString();
        }
}
