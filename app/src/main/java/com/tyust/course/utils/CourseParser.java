package com.tyust.course.utils;

import android.util.Log;
import com.tyust.course.model.Course;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CourseParser {
    private static final String TAG = "CourseParser";

    // 从学生信息页面解析学生姓名
    public static String parseStudentName(String html) {
        try {
            Document doc = Jsoup.parse(html);

            // 方法1: 查找input[name="xm"]
            Element nameInput = doc.selectFirst("input[name=\"xm\"]");
            if (nameInput != null) {
                String value = nameInput.attr("value");
                if (value != null && !value.trim().isEmpty()) {
                    Log.d(TAG, "Found name from input[name=xm]: " + value);
                    return value.trim();
                }
            }

            // 方法2: 查找h4.media-heading (Python版本的方法)
            Element nameElement = doc.selectFirst("h4.media-heading");
            if (nameElement != null) {
                String text = nameElement.text().trim();
                if (!text.isEmpty()) {
                    // 移除"学生"后缀
                    String name = text.replaceAll("\\s*学生\\s*$", "").trim();
                    Log.d(TAG, "Found name from h4.media-heading: " + name);
                    return name;
                }
            }

            // 方法3: 查找其他可能的姓名元素
            String[] selectors = {
                    "span[name=\"xm\"]",
                    "div[name=\"xm\"]",
                    ".user-name",
                    ".student-name",
                    "#xhxm"
            };

            for (String selector : selectors) {
                Element el = doc.selectFirst(selector);
                if (el != null) {
                    String text = el.text().trim();
                    if (!text.isEmpty()) {
                        // 清理可能的后缀
                        String name = text.replaceAll("\\s*同学\\s*$", "")
                                .replaceAll("\\s*学生\\s*$", "")
                                .trim();
                        Log.d(TAG, "Found name from " + selector + ": " + name);
                        return name;
                    }
                }
            }

            Log.w(TAG, "Could not parse student name from HTML");
            return null;

        } catch (Exception e) {
            Log.e(TAG, "Error parsing student name: " + e.getMessage());
            return null;
        }
    }

    // 从选课页面解析隐藏参数
    public static java.util.Map<String, String> parseCourseParams(String html) {
        java.util.Map<String, String> params = new java.util.HashMap<>();
        try {
            Document doc = Jsoup.parse(html);

            // 查找所有 type="hidden" 的 input 元素
            Elements hiddenInputs = doc.select("input[type=\"hidden\"]");
            for (Element input : hiddenInputs) {
                String name = input.attr("name");
                String value = input.attr("value");
                if (name != null && !name.isEmpty()) {
                    params.put(name, value != null ? value : "");
                    Log.d(TAG, "Found param: " + name + " = " + value);
                }
            }

            // 也查找所有没有明确type的input
            Elements allInputs = doc.select("input:not([type])");
            for (Element input : allInputs) {
                String name = input.attr("name");
                String value = input.attr("value");
                if (name != null && !name.isEmpty() && !params.containsKey(name)) {
                    params.put(name, value != null ? value : "");
                    Log.d(TAG, "Found param (no type): " + name + " = " + value);
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error parsing course params: " + e.getMessage());
        }
        return params;
    }

    // 解析课程列表（从JSON响应）- 支持传入表单参数用于保存
    public static List<Course> parseCourseListFromJson(String json) {
        return parseCourseListFromJson(json, null, null);
    }

    // 解析课程列表（从JSON响应）- 完整版本，支持保存请求参数
    public static List<Course> parseCourseListFromJson(String json,
            java.util.Map<String, String> formParams,
            java.util.Map<String, String> completeParams) {
        List<Course> courses = new ArrayList<>();
        try {
            // 🔧 调试：打印前500个字符的原始JSON，确认内容
            if (json.length() > 500) {
                Log.d(TAG, "Course List Raw JSON (First 500): " + json.substring(0, 500));
            } else {
                Log.d(TAG, "Course List Raw JSON: " + json);
            }

            // 尝试直接解析为数组
            JSONArray array;
            if (json.trim().startsWith("[")) {
                array = new JSONArray(json);
            } else {
                // 尝试从对象中提取数组
                JSONObject obj = new JSONObject(json);
                if (obj.has("tmpList")) {
                    array = obj.getJSONArray("tmpList");
                } else if (obj.has("courses")) {
                    array = obj.getJSONArray("courses");
                } else {
                    Log.w(TAG, "Unknown JSON structure");
                    return courses;
                }
            }

            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                Course course = new Course();

                // 课程基本信息
                course.name = item.optString("kcmc", "");
                course.courseId = item.optString("kch_id", item.optString("kch", ""));
                course.classId = item.optString("jxb_id", "");
                course.doJxbId = item.optString("do_jxb_id", course.classId);

                // 教师信息
                course.teacher = item.optString("jsxm", "");
                if (course.teacher.isEmpty()) {
                    String jsxx = item.optString("jsxx", "");
                    if (jsxx.contains("/")) {
                        course.teacher = jsxx.split("/")[1];
                    }
                }

                // 🔧 教学班名称 (jxbmc)
                course.jxbmc = item.optString("jxbmc", "");
                if (!course.jxbmc.isEmpty()) {
                    Log.d(TAG, "✅ Parsed jxbmc for " + course.name + ": " + course.jxbmc);
                }

                // 时间地点
                course.time = item.optString("sksj", "--").replace("<br>", ", ").replace("<br/>", ", ");
                course.location = item.optString("jxdd", "--").replace("<br>", ", ").replace("<br/>", ", ");

                // 学分
                course.credit = item.optString("xf", item.optString("jxbxf", ""));

                // 容量信息
                int capacity = item.optInt("jxbrl", item.optInt("jxbrs", 0));
                int selected = item.optInt("yxzrs", 0);
                course.capacity = capacity;
                course.selected = selected;

                // 状态判断
                String sfxkbj = item.optString("sfxkbj", "");
                if ("1".equals(sfxkbj)) {
                    course.isSelected = true;
                }

                // 用于选课的额外参数
                course.xkid = course.doJxbId; // 选课用的ID
                course.kklxdm = item.optString("kklxdm", "");

                // ============= Web版兼容: 保存请求参数 =============
                // 从formParams中获取（获取课程列表时使用的参数）
                if (formParams != null) {
                    course._rwlx = formParams.getOrDefault("rwlx", "");
                    course._xklc = formParams.getOrDefault("xklc", "");
                    course._xkly = formParams.getOrDefault("xkly", "0");
                    course._xkkz_id = formParams.getOrDefault("xkkz_id", "");
                    course.njdm_id = formParams.getOrDefault("njdm_id", "");
                    course.zyh_id = formParams.getOrDefault("zyh_id", "");
                    course.xqh_id = formParams.getOrDefault("xqh_id", "");
                    course.jg_id = formParams.getOrDefault("jg_id", "");
                    course.rlkz = formParams.getOrDefault("rlkz", "0");
                    course.rlzlkz = formParams.getOrDefault("rlzlkz", "1");
                    course.sxbj = formParams.getOrDefault("sxbj", "1");
                    course.xxkbj = formParams.getOrDefault("xxkbj", "0");
                    course.cxbj = formParams.getOrDefault("cxbj", "0");
                    course.xkxnm = formParams.getOrDefault("xkxnm", "");
                    course.xkxqm = formParams.getOrDefault("xkxqm", "");
                }

                // 从completeParams中获取（Display页面提取的参数）
                if (completeParams != null) {
                    course._sfkxq = completeParams.getOrDefault("sfkxq", "");
                    course._xkxskcgskg = completeParams.getOrDefault("xkxskcgskg", "");
                    // 保存完整参数供选课时使用
                    course.completeParams.putAll(completeParams);
                }

                // 如果formParams中没有，尝试从JSON响应中获取
                if (course._xkkz_id.isEmpty()) {
                    course._xkkz_id = item.optString("xkkz_id", "");
                }
                if (course.njdm_id.isEmpty()) {
                    course.njdm_id = item.optString("njdm_id", "");
                }
                if (course.zyh_id.isEmpty()) {
                    course.zyh_id = item.optString("zyh_id", "");
                }

                if (!course.name.isEmpty()) {
                    courses.add(course);
                }
            }

            Log.d(TAG, "Parsed " + courses.size() + " courses from JSON");

            // 验证参数保存
            if (courses.size() > 0 && formParams != null) {
                Course first = courses.get(0);
                Log.d(TAG, "First course params: _rwlx=" + first._rwlx +
                        ", _xklc=" + first._xklc + ", _xkkz_id=" + first._xkkz_id);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error parsing course list from JSON: " + e.getMessage());
        }
        return courses;
    }

    // 从HTML表格解析课程列表（备用方法）
    public static List<Course> parseCourseList(String html) {
        List<Course> courses = new ArrayList<>();
        try {
            // 首先尝试作为JSON解析
            if (html.trim().startsWith("[") || html.trim().startsWith("{")) {
                return parseCourseListFromJson(html);
            }

            Document doc = Jsoup.parse(html);

            // 查找课程表格
            Elements rows = doc.select("table tbody tr");

            for (int i = 0; i < rows.size(); i++) {
                Element row = rows.get(i);
                Elements cells = row.select("td");

                if (cells.size() >= 6) {
                    Course course = new Course();
                    course.name = cells.get(1).text().trim();
                    course.teacher = cells.get(2).text().trim();
                    course.location = cells.get(3).text().trim();
                    course.time = cells.get(4).text().trim();
                    course.credit = cells.get(5).text().trim();

                    // 尝试从第一列获取选课ID
                    Element input = cells.get(0).selectFirst("input");
                    if (input != null) {
                        course.xkid = input.attr("value");
                    }

                    if (!course.name.isEmpty()) {
                        courses.add(course);
                    }
                }
            }

            Log.d(TAG, "Parsed " + courses.size() + " courses from HTML");

        } catch (Exception e) {
            Log.e(TAG, "Error parsing course list: " + e.getMessage());
        }
        return courses;
    }
}
