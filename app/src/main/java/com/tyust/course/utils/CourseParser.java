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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    // 从学生信息页面解析学号
    public static String parseStudentId(String html) {
        try {
            Document doc = Jsoup.parse(html);

            // 方法1: 查找input[name="xh"]
            Element idInput = doc.selectFirst("input[name=\"xh\"]");
            if (idInput != null) {
                String value = idInput.attr("value");
                if (value != null && !value.trim().isEmpty()) {
                    Log.d(TAG, "Found ID from input[name=xh]: " + value);
                    return value.trim();
                }
            }

            // 方法2: 查找特定的学号容器
            String[] selectors = {
                    "span[name=\"xh\"]",
                    "div[name=\"xh\"]",
                    ".student-id",
                    "#xh",
                    ".user-id"
            };

            for (String selector : selectors) {
                Element el = doc.selectFirst(selector);
                if (el != null) {
                    String text = el.text().trim();
                    if (!text.isEmpty()) {
                        Log.d(TAG, "Found ID from " + selector + ": " + text);
                        return text;
                    }
                }
            }

            return null;
        } catch (Exception e) {
            Log.e(TAG, "Error parsing student ID: " + e.getMessage());
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

    /**
     * 从自主选课页面 HTML 中解析筛选分类和选项。
     * 筛选项以页面实际返回的 DOM 为准；解析不到时返回空列表，由界面保持加载/空状态，不用固定分类顶替。
     */
    public static List<FilterCategory> parseFilterOptions(String html) {
        List<FilterCategory> categories = new ArrayList<>();
        if (html == null || html.trim().isEmpty()) {
            return categories;
        }

        try {
            Document doc = Jsoup.parse(html);
            Map<String, FilterCategoryBuilder> builders = new LinkedHashMap<>();

            Elements rows = doc.select("div.condition-row, div[class*=condition], div[class*=tj], div[class*=filter]");
            for (Element row : rows) {
                appendIndexedFilterOptions(builders, row);

                Element namedContainer = row.selectFirst("[name$=_list]");
                if (namedContainer == null) continue;

                String paramName = namedContainer.attr("name").trim();
                if (!isSupportedFilterParam(paramName)) continue;

                String categoryName = extractFilterCategoryName(row, paramName);
                FilterCategoryBuilder builder = builders.get(paramName);
                if (builder == null) {
                    builder = new FilterCategoryBuilder(categoryName, paramName);
                    builders.put(paramName, builder);
                }
                appendFilterOptions(builder, namedContainer, paramName);
            }

            Elements namedContainers = doc.select("[name$=_list]");
            for (Element container : namedContainers) {
                String paramName = container.attr("name").trim();
                if (!isSupportedFilterParam(paramName)) continue;
                if (builders.containsKey(paramName)) continue;

                FilterCategoryBuilder builder = new FilterCategoryBuilder(filterTitleForParam(paramName), paramName);
                appendFilterOptions(builder, container, paramName);
                if (!builder.options.isEmpty()) {
                    builders.put(paramName, builder);
                }
            }

            Elements indexedOptions = doc.select("li[index], a[index], span[index]");
            for (Element optionEl : indexedOptions) {
                String paramName = extractFilterParamNameFromIndex(optionEl.attr("index").trim());
                if (!isSupportedFilterParam(paramName) || builders.containsKey(paramName)) continue;

                Element row = optionEl.closest("div.condition-row, div[class*=condition], div[class*=tj], div[class*=filter]");
                FilterCategoryBuilder builder = new FilterCategoryBuilder(
                        row != null ? extractFilterCategoryName(row, paramName) : filterTitleForParam(paramName),
                        paramName
                );
                builders.put(paramName, builder);
                Element container = row != null ? row : doc;
                appendIndexedFilterOptionsForParam(builder, container, paramName);
            }

            for (FilterCategoryBuilder builder : builders.values()) {
                if (!builder.options.isEmpty()) {
                    categories.add(new FilterCategory(builder.name, builder.paramName, builder.options));
                    Log.d(TAG, "Parsed filter: " + builder.name + " (" + builder.paramName + ") = " + builder.options.size() + " options");
                }
            }

            if (categories.isEmpty()) {
                Log.w(TAG, "No dynamic filter categories parsed from page HTML");
            }

            Log.d(TAG, "Total filter categories parsed: " + categories.size());
        } catch (Exception e) {
            Log.e(TAG, "Error parsing filter options: " + e.getMessage());
            categories.clear();
        }
        return categories;
    }

    public static List<FilterOption> parseFilterOptionsFromJson(String json, String keyField, String labelField) {
        List<FilterOption> options = new ArrayList<>();
        if (json == null || json.trim().isEmpty() || keyField == null || labelField == null) {
            return options;
        }

        try {
            JSONArray array = null;
            String trimmed = json.trim();
            if (trimmed.startsWith("[")) {
                array = new JSONArray(trimmed);
            } else if (trimmed.startsWith("{")) {
                JSONObject obj = new JSONObject(trimmed);
                array = obj.optJSONArray("items");
                if (array == null) array = obj.optJSONArray("rows");
                if (array == null) array = obj.optJSONArray("list");
                if (array == null) {
                    Object data = obj.opt("data");
                    if (data instanceof JSONArray) {
                        array = (JSONArray) data;
                    } else if (data instanceof JSONObject) {
                        JSONObject dataObj = (JSONObject) data;
                        array = dataObj.optJSONArray("items");
                        if (array == null) array = dataObj.optJSONArray("rows");
                        if (array == null) array = dataObj.optJSONArray("list");
                    }
                }
            }

            if (array == null) {
                Log.w(TAG, "No option array found in filter JSON");
                return options;
            }

            Map<String, Boolean> seenKeys = new LinkedHashMap<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;

                String key = item.optString(keyField, "").trim();
                String label = item.optString(labelField, "").trim();
                if (key.isEmpty()) continue;
                if (label.isEmpty()) label = key;
                if ("全部".equals(label) || "确定".equals(label) || "取消".equals(label)) continue;
                if (seenKeys.containsKey(key)) continue;

                seenKeys.put(key, true);
                options.add(new FilterOption(key, label));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing filter JSON options: " + e.getMessage());
            options.clear();
        }
        return options;
    }

    private static void appendFilterOptions(FilterCategoryBuilder builder, Element container, String paramName) {
        Elements optionElements = container.select("li[index], a[index], span[index], option[value], input[value]");
        for (Element optionEl : optionElements) {
            String key = extractFilterOptionKey(optionEl, paramName);
            String label = extractFilterOptionLabel(optionEl);
            if (key.isEmpty() || label.isEmpty()) continue;
            if (label.equals("确定") || label.equals("取消") || label.equals("全部")) continue;
            builder.addOption(key, label);
        }
    }

    private static void appendIndexedFilterOptions(Map<String, FilterCategoryBuilder> builders, Element container) {
        Elements optionElements = container.select("li[index], a[index], span[index]");
        for (Element optionEl : optionElements) {
            String paramName = extractFilterParamNameFromIndex(optionEl.attr("index").trim());
            if (!isSupportedFilterParam(paramName)) continue;

            FilterCategoryBuilder builder = builders.get(paramName);
            if (builder == null) {
                builder = new FilterCategoryBuilder(extractFilterCategoryName(container, paramName), paramName);
                builders.put(paramName, builder);
            }

            String key = extractFilterOptionKey(optionEl, paramName);
            String label = extractFilterOptionLabel(optionEl);
            if (key.isEmpty() || label.isEmpty()) continue;
            if (label.equals("确定") || label.equals("取消") || label.equals("全部")) continue;
            builder.addOption(key, label);
        }
    }

    private static void appendIndexedFilterOptionsForParam(FilterCategoryBuilder builder, Element container, String paramName) {
        Elements optionElements = container.select("li[index], a[index], span[index]");
        for (Element optionEl : optionElements) {
            String optionParamName = extractFilterParamNameFromIndex(optionEl.attr("index").trim());
            if (!paramName.equals(optionParamName)) continue;

            String key = extractFilterOptionKey(optionEl, paramName);
            String label = extractFilterOptionLabel(optionEl);
            if (key.isEmpty() || label.isEmpty()) continue;
            if (label.equals("确定") || label.equals("取消") || label.equals("全部")) continue;
            builder.addOption(key, label);
        }
    }

    private static String extractFilterParamNameFromIndex(String index) {
        if (index == null || index.isEmpty()) return "";
        String[] supportedParams = {
                "kkbm_id_list", "njdm_id_list", "jg_id_list", "zyh_id_list",
                "kclb_id_list", "kcxzdm_list", "kcgs_list", "jxms_list",
                "sksj_list", "skjc_list", "jxbmc_list", "cxbj_list", "yl_list"
        };
        for (String paramName : supportedParams) {
            if (index.equals(paramName) || index.startsWith(paramName + "_")) {
                return paramName;
            }
        }
        return "";
    }

    private static String extractFilterOptionKey(Element optionEl, String paramName) {
        String index = optionEl.attr("index").trim();
        if (!index.isEmpty()) {
            if (index.startsWith(paramName + "_")) {
                return index.substring(paramName.length() + 1);
            }
            int lastUnderscore = index.lastIndexOf('_');
            if (lastUnderscore >= 0 && lastUnderscore < index.length() - 1) {
                return index.substring(lastUnderscore + 1);
            }
            return index;
        }

        String value = optionEl.attr("value").trim();
        if (!value.isEmpty()) return value;

        String dataValue = optionEl.attr("data-value").trim();
        if (!dataValue.isEmpty()) return dataValue;

        return optionEl.attr("data-key").trim();
    }

    private static String extractFilterOptionLabel(Element optionEl) {
        String label = optionEl.attr("title").trim();
        if (!label.isEmpty()) return label;

        label = optionEl.attr("label").trim();
        if (!label.isEmpty()) return label;

        label = optionEl.text().trim();
        if (!label.isEmpty()) return label;

        return optionEl.attr("value").trim();
    }

    private static String extractFilterCategoryName(Element row, String paramName) {
        Element titleEl = row.selectFirst("label.title, .title, label, dt, .condition-title, .filter-title");
        if (titleEl != null) {
            String title = titleEl.text().replace("：", "").replace(":", "").trim();
            if (!title.isEmpty()) return title;
        }
        return filterTitleForParam(paramName);
    }

    private static boolean isSupportedFilterParam(String paramName) {
        return "kkbm_id_list".equals(paramName)
                || "njdm_id_list".equals(paramName)
                || "jg_id_list".equals(paramName)
                || "zyh_id_list".equals(paramName)
                || "kclb_id_list".equals(paramName)
                || "kcxzdm_list".equals(paramName)
                || "kcgs_list".equals(paramName)
                || "jxms_list".equals(paramName)
                || "sksj_list".equals(paramName)
                || "skjc_list".equals(paramName)
                || "jxbmc_list".equals(paramName)
                || "cxbj_list".equals(paramName)
                || "yl_list".equals(paramName);
    }

    private static String filterTitleForParam(String paramName) {
        switch (paramName) {
            case "kkbm_id_list": return "开课学院";
            case "njdm_id_list": return "年级";
            case "jg_id_list": return "学院";
            case "zyh_id_list": return "专业";
            case "kclb_id_list": return "课程类别";
            case "kcxzdm_list": return "课程性质";
            case "kcgs_list": return "课程归属";
            case "jxms_list": return "教学模式";
            case "sksj_list": return "上课星期";
            case "skjc_list": return "上课节次";
            case "jxbmc_list": return "教学班";
            case "cxbj_list": return "是否重修";
            case "yl_list": return "有无余量";
            default: return "筛选条件";
        }
    }

    private static class FilterCategoryBuilder {
        final String name;
        final String paramName;
        final List<FilterOption> options = new ArrayList<>();
        final Map<String, Boolean> seenKeys = new LinkedHashMap<>();

        FilterCategoryBuilder(String name, String paramName) {
            this.name = name;
            this.paramName = paramName;
        }

        void addOption(String key, String label) {
            if (seenKeys.containsKey(key)) return;
            seenKeys.put(key, true);
            options.add(new FilterOption(key, label));
        }
    }

    /** 筛选分类数据结构 */
    public static class FilterCategory {
        public final String name;       // 分类名称，如"课程类别"
        public final String paramName;  // POST 参数名，如"kclb_id_list"
        public final List<FilterOption> options;

        public FilterCategory(String name, String paramName, List<FilterOption> options) {
            this.name = name;
            this.paramName = paramName;
            this.options = options;
        }
    }

    /** 筛选选项数据结构 */
    public static class FilterOption {
        public final String key;    // POST 参数值，如"01"
        public final String label;  // 显示文本，如"必修"

        public FilterOption(String key, String label) {
            this.key = key;
            this.label = label;
        }
    }
}
