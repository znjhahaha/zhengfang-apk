package com.tyust.course.model;

import org.json.JSONObject;

public class SchoolConfig {
    public String id;
    public String name;
    public String domain;
    public String protocol;
    public String description;

    // gnmkdm 参数配置 (可自定义)
    public String gradeGnmkdm = "N305005";
    public String courseGnmkdm = "N253512";
    public String scheduleGnmkdm = "N253508";

    // URL 路径模板 (可自定义) - 默认为正方教务系统标准路径
    public String basePath = "/jwglxt"; // 基础路径，如 /jwglxt 或 /jwxt
    public String studentInfoPath = "/xtgl/index_cxYhxxIndex.html";
    public String courseIndexPath = "/xsxk/zzxkyzb_cxZzxkYzbIndex.html";
    public String courseDisplayPath = "/xsxk/zzxkyzb_cxZzxkYzbDisplay.html";
    public String courseListPath = "/xsxk/zzxkyzb_cxZzxkYzbPartDisplay.html";
    public String selectedCoursesPath = "/xsxk/zzxkyzb_cxZzxkYzbChoosedDisplay.html";
    public String selectCoursePath = "/xsxk/zzxkyzbjk_xkBcZyZzxkYzb.html";
    public String courseDetailsPath = "/xsxk/zzxkyzbjk_cxJxbWithKchZzxkYzb.html";
    public String schedulePath = "/kbcx/xskbcx_cxXsKb.html";
    public String gradesPath = "/cjcx/cjcx_cxDgXscj.html";
    public String overallGradesIndexPath = "/xsxy/xsxyqk_cxXsxyqkIndex.html";
    public String overallGradesDataPath = "/xsxy/xsxyqk_cxJxzxjhxfyqKcxx.html";

    // 登录相关路径
    public String captchaPath = "/kaptcha";
    public String publicKeyPath = "/xtgl/login_getPublicKey.html";
    public String loginPagePath = "/xtgl/login_slogin.html";

    public SchoolConfig(String id, String name, String domain, String protocol) {
        this.id = id;
        this.name = name;
        this.domain = domain;
        this.protocol = protocol;
    }

    public String getBaseUrl() {
        return protocol + "://" + domain;
    }

    public String getFullBasePath() {
        return getBaseUrl() + basePath;
    }

    // 生成学生信息验证URL
    public String getStudentInfoUrl() {
        return getFullBasePath() + studentInfoPath + "?xt=jw&localeKey=zh_CN&_="
                + System.currentTimeMillis() + "&gnmkdm=index";
    }

    // 生成选课参数页面URL
    public String getCourseSelectionParamsUrl() {
        return getFullBasePath() + courseIndexPath + "?gnmkdm=" + courseGnmkdm + "&layout=default&su=" + domain;
    }

    // 生成可选课程列表URL
    public String getAvailableCoursesUrl() {
        return getFullBasePath() + courseListPath + "?gnmkdm=" + courseGnmkdm;
    }

    // 生成已选课程列表URL
    public String getSelectedCoursesUrl() {
        return getFullBasePath() + selectedCoursesPath + "?gnmkdm=" + courseGnmkdm;
    }

    // 生成选课执行URL
    public String getSelectCourseUrl() {
        return getFullBasePath() + selectCoursePath + "?gnmkdm=" + courseGnmkdm;
    }

    // 生成选课详情URL
    public String getCourseSelectionDetailsUrl() {
        return getFullBasePath() + courseDetailsPath + "?gnmkdm=" + courseGnmkdm;
    }

    // 生成Referer头
    public String getCourseReferer() {
        return getFullBasePath() + courseIndexPath + "?gnmkdm=" + courseGnmkdm + "&layout=default&su=" + domain;
    }

    // 生成课表URL
    public String getScheduleUrl() {
        return getFullBasePath() + schedulePath + "?gnmkdm=" + scheduleGnmkdm + "&su=" + domain;
    }

    // 生成成绩查询URL
    public String getGradesUrl(String semester) {
        String xnm = "2024";
        String xqm = "1";

        if (semester != null && semester.contains("-")) {
            String[] parts = semester.split("-");
            if (parts.length >= 3) {
                xnm = parts[0];
                xqm = parts[2].equals("1") ? "3" : "12";
            }
        }

        return getFullBasePath() + gradesPath + "?gnmkdm=" + gradeGnmkdm
                + "&doType=query&xnm=" + xnm + "&xqm=" + xqm
                + "&queryModel.showCount=1500&queryModel.currentPage=1";
    }

    // 生成分项成绩详情URL (接口A - 兜底用)
    public String getGradeDetailUrl() {
        return getFullBasePath() + "/cjcx/cjjdcx_cxXsjdxmcjIndex.html?doType=query&gnmkdm=N305099";
    }

    // 从 semester 字符串提取 xnm/xqm
    public String[] parseSemester(String semester) {
        String xnm = "2024";
        String xqm = "3";
        if (semester != null && semester.contains("-")) {
            String[] parts = semester.split("-");
            if (parts.length >= 3) {
                xnm = parts[0];
                xqm = parts[2].equals("1") ? "3" : "12";
            }
        }
        return new String[]{xnm, xqm};
    }

    // 生成总体成绩查询URL
    public String getOverallGradesUrl() {
        return getFullBasePath() + overallGradesIndexPath + "?gnmkdm=N105515&layout=default";
    }

    // 生成总体成绩数据URL
    public String getOverallGradesDataUrl() {
        return getFullBasePath() + overallGradesDataPath + "?gnmkdm=N105515";
    }

    // 序列化为 JSON
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("id", id);
            json.put("name", name);
            json.put("domain", domain);
            json.put("protocol", protocol);
            json.put("basePath", basePath);
            json.put("gradeGnmkdm", gradeGnmkdm);
            json.put("courseGnmkdm", courseGnmkdm);
            json.put("scheduleGnmkdm", scheduleGnmkdm);
            json.put("studentInfoPath", studentInfoPath);
            json.put("courseIndexPath", courseIndexPath);
            json.put("courseDisplayPath", courseDisplayPath);
            json.put("courseListPath", courseListPath);
            json.put("selectedCoursesPath", selectedCoursesPath);
            json.put("selectCoursePath", selectCoursePath);
            json.put("courseDetailsPath", courseDetailsPath);
            json.put("schedulePath", schedulePath);
            json.put("gradesPath", gradesPath);
            json.put("overallGradesIndexPath", overallGradesIndexPath);
            json.put("overallGradesDataPath", overallGradesDataPath);
            json.put("captchaPath", captchaPath);
            json.put("publicKeyPath", publicKeyPath);
            json.put("loginPagePath", loginPagePath);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return json;
    }

    // 从 JSON 反序列化
    public static SchoolConfig fromJson(JSONObject json) {
        try {
            SchoolConfig config = new SchoolConfig(
                    json.optString("id", ""),
                    json.optString("name", ""),
                    json.optString("domain", ""),
                    json.optString("protocol", "https"));
            config.basePath = json.optString("basePath", "/jwglxt");
            config.gradeGnmkdm = json.optString("gradeGnmkdm", "N305005");
            config.courseGnmkdm = json.optString("courseGnmkdm", "N253512");
            config.scheduleGnmkdm = json.optString("scheduleGnmkdm", "N253508");
            config.studentInfoPath = json.optString("studentInfoPath", "/xtgl/index_cxYhxxIndex.html");
            config.courseIndexPath = json.optString("courseIndexPath", "/xsxk/zzxkyzb_cxZzxkYzbIndex.html");
            config.courseDisplayPath = json.optString("courseDisplayPath", "/xsxk/zzxkyzb_cxZzxkYzbDisplay.html");
            config.courseListPath = json.optString("courseListPath", "/xsxk/zzxkyzb_cxZzxkYzbPartDisplay.html");
            config.selectedCoursesPath = json.optString("selectedCoursesPath",
                    "/xsxk/zzxkyzb_cxZzxkYzbChoosedDisplay.html");
            config.selectCoursePath = json.optString("selectCoursePath", "/xsxk/zzxkyzbjk_xkBcZyZzxkYzb.html");
            config.courseDetailsPath = json.optString("courseDetailsPath", "/xsxk/zzxkyzbjk_cxJxbWithKchZzxkYzb.html");
            config.schedulePath = json.optString("schedulePath", "/kbcx/xskbcx_cxXsKb.html");
            config.gradesPath = json.optString("gradesPath", "/cjcx/cjcx_cxDgXscj.html");
            config.overallGradesIndexPath = json.optString("overallGradesIndexPath", "/xsxy/xsxyqk_cxXsxyqkIndex.html");
            config.overallGradesDataPath = json.optString("overallGradesDataPath",
                    "/xsxy/xsxyqk_cxJxzxjhxfyqKcxx.html");
            config.captchaPath = json.optString("captchaPath", "/kaptcha");
            config.publicKeyPath = json.optString("publicKeyPath", "/xtgl/login_getPublicKey.html");
            config.loginPagePath = json.optString("loginPagePath", "/xtgl/login_slogin.html");
            return config;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
