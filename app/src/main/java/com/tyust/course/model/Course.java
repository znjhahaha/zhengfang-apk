package com.tyust.course.model;

import java.util.HashMap;
import java.util.Map;

public class Course {
    // 基本信息
    public String name = ""; // 课程名称 (kcmc)
    public String courseId = ""; // 课程代码 (kch_id)
    public String classId = ""; // 教学班ID (jxb_id)
    public String doJxbId = ""; // 选课用的教学班ID (do_jxb_id)

    // 显示信息
    public String teacher = ""; // 教师姓名 (jsxm)
    public String jxbmc = ""; // 教学班名称 (jxbmc) 🔧 新增
    public String time = ""; // 上课时间 (sksj)
    public String location = ""; // 上课地点 (jxdd)
    public String credit = ""; // 学分 (xf)

    // 容量信息
    public int capacity = 0; // 课程容量 (jxbrl)
    public int selected = 0; // 已选人数 (yxzrs)

    // 状态
    public boolean isSelected = false; // 是否已选

    // 🔧 抢课模式标记
    // true = 精确模式（使用保存的 classId/doJxbId，适用于本次抢课）
    // false = 智能模式（按课程名+老师+时间重新匹配，适用于跨轮次抢课）
    public boolean useExactMatch = true; // 默认使用精确模式

    // 用于选课的额外参数
    public String xkid = ""; // 选课ID
    public String kklxdm = ""; // 课程类型代码

    // ============= Web版兼容字段 (用于3步选课流程) =============
    // 这些字段保存获取课程列表时使用的参数，用于后续选课
    public String _rwlx = ""; // 获取课程列表时使用的 rwlx 参数
    public String _xklc = ""; // 获取课程列表时使用的 xklc 参数
    public String _xkly = ""; // 获取课程列表时使用的 xkly 参数
    public String _xkkz_id = ""; // 获取课程列表时使用的 xkkz_id 参数

    // 从 Display 页面提取的关键参数
    public String _sfkxq = ""; // 是否开学前 (sfkxq)
    public String _xkxskcgskg = ""; // 选课学生课程改时开关 (xkxskcgskg)

    // 完整参数存储 (用于选课时传递)
    public Map<String, String> completeParams = new HashMap<>();

    // ============= 从 Display 页面获取的额外参数 =============
    public String njdm_id = ""; // 年级代码
    public String zyh_id = ""; // 专业号
    public String xqh_id = ""; // 校区号
    public String jg_id = ""; // 机构ID
    public String rlkz = "0"; // 容量控制
    public String rlzlkz = "1"; // 容量总量控制
    public String sxbj = "1"; // 上下班级
    public String xxkbj = "0"; // 选修课班级
    public String cxbj = "0"; // 重修班级
    public String xkxnm = ""; // 学年
    public String xkxqm = ""; // 学期
    public String jcxx_id = ""; // 基础信息ID (Web版使用)

    // 获取状态显示文本
    public String getStatus() {
        if (isSelected)
            return "已选";
        if (capacity > 0 && selected >= capacity)
            return "已满";
        return selected + "/" + capacity;
    }

    // 获取剩余名额
    public int getAvailable() {
        return Math.max(0, capacity - selected);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        Course course = (Course) o;
        return (name != null ? name.equals(course.name) : course.name == null) &&
                (teacher != null ? teacher.equals(course.teacher) : course.teacher == null) &&
                (time != null ? time.equals(course.time) : course.time == null);
    }

    @Override
    public int hashCode() {
        int result = name != null ? name.hashCode() : 0;
        result = 31 * result + (teacher != null ? teacher.hashCode() : 0);
        result = 31 * result + (time != null ? time.hashCode() : 0);
        return result;
    }

    // 🔧 唯一标识符 (用于 UI 动画)
    // 默认直接初始化，确保新创建的对象立即拥有稳定 ID
    public String uuid = java.util.UUID.randomUUID().toString();

    public String getUuid() {
        // 如果是从 JSON 反序列化得到的对象，且字段不在 JSON 中，uuid 可能是 null
        if (uuid == null || uuid.isEmpty()) {
            uuid = java.util.UUID.randomUUID().toString();
        }
        return uuid;
    }

    /**
     * 🔧 深拷贝方法 - 用于确保 Compose 能够检测到对象变化
     */
    public Course copy() {
        Course copy = new Course();
        // 保持 uuid 不变，因为代表同一个实体的不同状态
        copy.uuid = this.getUuid();

        copy.name = this.name;
        copy.courseId = this.courseId;
        copy.classId = this.classId;
        copy.doJxbId = this.doJxbId;
        copy.teacher = this.teacher;
        copy.jxbmc = this.jxbmc;
        copy.time = this.time;
        copy.location = this.location;
        copy.credit = this.credit;
        copy.capacity = this.capacity;
        copy.selected = this.selected;
        copy.isSelected = this.isSelected;
        copy.useExactMatch = this.useExactMatch;
        copy.xkid = this.xkid;
        copy.kklxdm = this.kklxdm;
        copy._rwlx = this._rwlx;
        copy._xklc = this._xklc;
        copy._xkly = this._xkly;
        copy._xkkz_id = this._xkkz_id;
        copy._sfkxq = this._sfkxq;
        copy._xkxskcgskg = this._xkxskcgskg;
        copy.completeParams = new HashMap<>(this.completeParams);
        copy.njdm_id = this.njdm_id;
        copy.zyh_id = this.zyh_id;
        copy.xqh_id = this.xqh_id;
        copy.jg_id = this.jg_id;
        copy.rlkz = this.rlkz;
        copy.rlzlkz = this.rlzlkz;
        copy.sxbj = this.sxbj;
        copy.xxkbj = this.xxkbj;
        copy.cxbj = this.cxbj;
        copy.xkxnm = this.xkxnm;
        copy.xkxqm = this.xkxqm;
        copy.jcxx_id = this.jcxx_id;
        return copy;
    }
}
