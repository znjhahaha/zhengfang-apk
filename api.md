# 正方教务系统 API 技术文档 (以太原科技大学为例)

本文档详细描述了正方教务系统抢课程序中使用的各种 API 接口、请求方法、参数及响应格式。

**基础信息：**
- **学校**：太原科技大学 (TYUST)
- **基础 URL**：`https://newjwc.tyust.edu.cn`
- **认证方式**：Cookie (JSESSIONID)
- **通用请求头**：
  ```http
  User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 ...
  X-Requested-With: XMLHttpRequest
  Content-Type: application/x-www-form-urlencoded;charset=UTF-8
  ```

---

## 1. 获取学生信息

用于获取当前登录学生的姓名、学号、学院等基本信息。

- **URL**: `/jwglxt/xtgl/index_cxYhxxIndex.html`
- **Method**: `GET`
- **Query Parameters**:
  - `xt`: `jw`
  - `localeKey`: `zh_CN`
  - `_`: `[当前时间戳]`
  - `gnmkdm`: `index`
- **Headers**:
  - `Referer`: `https://newjwc.tyust.edu.cn/jwglxt/xtgl/index_initMenu.html`

**响应处理**:
返回 HTML 页面，需解析提取：
- 姓名: `input[name="xm"]` 或 `h4.media-heading`
- 学号: `input[name="xh"]`
- 学院: `input[name="jgmc"]`
- 专业: `input[name="zymc"]`
- 班级: `input[name="bh"]`

---

## 2. 获取可选课程 (选课列表)

获取当前选课轮次中可供选择的课程列表。此过程分为两步：先获取初始参数，再请求课程数据。

### 2.1 获取初始参数
- **URL**: `/jwglxt/xsxk/zzxkyzb_cxZzxkYzbIndex.html`
- **Method**: `GET`
- **Query Parameters**:
  - `gnmkdm`: `N253512` (课程功能代码)
  - `layout`: `default`
  - `su`: `newjwc.tyust.edu.cn`

**响应处理**:
解析 HTML 中的隐藏域 (`input[type="hidden"]`) 和 `onclick` 事件，提取关键参数：
- `rwlx`, `xkly`, `bklx_id`, `sfkxq`, `sfkcf`, `xkkz_id`, `njdm_id`, `zyh_id`

### 2.2 请求课程数据
- **URL**: `/jwglxt/xsxk/zzxkyzb_cxZzxkYzbPartDisplay.html`
- **Method**: `POST`
- **Query Parameters**:
  - `gnmkdm`: `N253512`
- **Body Parameters** (示例):
  - `rwlx`: `1` (任务类型)
  - `xkly`: `0` (选课来源)
  - `bklx_id`: `0`
  - `sfkxq`: `0` (是否跨校区)
  - `sfkcf`: `0` (是否跨专业)
  - `xkkz_id`: `[从上一步获取]` (选课控制ID)
  - `cxbj`: `0` (重修标记)
  - `fxbj`: `0` (辅修标记)

**响应处理**:
返回 JSON 格式的课程列表 (`tmpList` 数组)，包含：
- `kch_id`: 课程号
- `kcmc`: 课程名称
- `jxb_id`: 教学班ID (短ID，仅用于显示)
- `do_jxb_id`: **加密教学班ID** (长字符串，用于实际选课，**关键**)
- `jxbrl`: 容量
- `yxzrs`: 已选人数

---

## 3. 抢课/选课 (执行选课)

发送选课请求。

- **URL**: `/jwglxt/xsxk/zzxkyzbjk_xkBcZyZzxkYzb.html`
- **Method**: `POST`
- **Query Parameters**:
  - `gnmkdm`: `N253512`
- **Headers**:
  - `Referer`: `/jwglxt/xsxk/zzxkyzb_cxZzxkYzbIndex.html...`
- **Body Parameters**:
  - `jxb_ids`: `[do_jxb_id]` (**必须使用加密的长ID**，不能使用短ID)
  - `kch_id`: `[课程号]`
  - `kcmc`: `([课程号])[课程名]`
  - `rwlx`: `[从列表页获取]`
  - `rlkz`: `0` (容量控制)
  - `rlzlkz`: `1` (容量增量控制)
  - `sxbj`: `1`
  - `xxkbj`: `0`
  - `qz`: `0` (权重/强制标记)
  - `cxbj`: `0`
  - `xkkz_id`: `[选课控制ID]`
  - `njdm_id`: `[年级代码]`
  - `zyh_id`: `[专业号]`
  - `kklxdm`: `01` (课程类型代码)
  - `xklc`: `2` (选课轮次)
  - `xkxnm`: `2025` (选课学年)
  - `xkxqm`: `12` (选课学期，3=上，12=下)

**响应格式**:
```json
{
  "flag": "1",  // "1" 表示成功，"-1" 或其他表示失败
  "msg": "选课成功"
}
```

---

## 4. 获取已选课程

获取当前学生已经选中的课程。

- **URL**: `/jwglxt/xsxk/zzxkyzb_cxZzxkYzbChoosedDisplay.html`
- **Method**: `POST`
- **Query Parameters**:
  - `gnmkdm`: `N253512`
- **Body Parameters**:
  需要先访问 `/jwglxt/xsxk/zzxkyzb_cxZzxkYzbIndex.html` 动态提取以下参数：
  - `jg_id`, `zyh_id`, `njdm_id`, `zyfx_id`, `bh_id`, `xz`, `ccdm`, `xqh_id`, `xkxnm`, `xkxqm`, `xkly`

**响应处理**:
通常返回 JSON 数组，包含已选课程的详细信息。如果 Cookie 失效，可能返回包含 "用户登录" 的 HTML。

---

## 5. 获取课表

查询学生的学期课表。

- **URL**: `/jwglxt/kbcx/xskbcx_cxXsgrkb.html`
- **Method**: `POST`
- **Query Parameters**:
  - `gnmkdm`: `N2151`
- **Body Parameters**:
  - `xnm`: `2025` (学年)
  - `xqm`: `3` (学期)
  - `kzlx`: `ck` (控制类型：查看)
  - `xsdm`: `` (为空)

**响应格式**:
JSON 对象，核心数据在 `kbList` 数组中。
- `xqjmc`: 星期几 (如 "星期一")
- `jcs`: 节次 (如 "1-2")
- `kcmc`: 课程名称
- `cdmc`: 教室名称

---

## 6. 获取学期成绩

查询特定学期的成绩。

- **URL**: `/jwglxt/cjcx/cjcx_cxXsgrcj.html`
- **Method**: `POST`
- **Query Parameters**:
  - `doType`: `query`
  - `gnmkdm`: `N305005`
- **Body Parameters**:
  - `xnm`: `2024` (学年)
  - `xqm`: `3` (学期)
  - `nd`: `[当前时间戳]`

**响应处理**:
可能返回 JSON 或 HTML 表格。需解析提取课程名、成绩、学分、绩点等。

---

## 7. 获取总体成绩 (入学以来)

查询所有学期的成绩。

- **URL**: `/jwglxt/xsxy/xsxyqk_cxJxzxjhxfyqKcxx.html`
- **Method**: `POST`
- **Query Parameters**:
  - `gnmkdm`: `N105515`
- **Body Parameters**:
  需先访问 `/jwglxt/xsxy/xsxyqk_cxXsxyqkIndex.html` 提取：
  - `jg_id`, `njdm_id`, `zyh_id`, `xh_id`, `xsbj`

**响应处理**:
返回 JSON 数组，包含所有课程的成绩信息。

---

## 关键代码说明 (功能模块代码)

- **`N305005`**: 成绩查询模块代码
- **`N253512`**: 选课/课程查询模块代码
- **`N253508` / `N2151`**: 课表查询模块代码

## 注意事项

1. **Cookie 保活**: 正方系统 Session 容易过期，程序需处理 `901` 或 `910` 状态码，提示用户重新登录。
2. **参数动态获取**: 许多参数（如 `xkkz_id`, `do_jxb_id`）是动态生成的，必须从前序页面的响应中提取，不能硬编码。
3. **加密 ID**: 选课时必须使用 `do_jxb_id` (加密长ID)，使用 `jxb_id` (短ID) 会导致选课失败。
