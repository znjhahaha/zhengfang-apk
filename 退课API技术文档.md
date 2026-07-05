# 退课 API 技术文档

> 基于正方教务系统选课模块逆向分析，适用于「正方教务助手」Android 客户端。
> 本文档覆盖两个关联 API：**获取已选课程列表** 和 **退课（退选）**。

---

# Part A — 获取已选课程列表

---

## A1. 接口概述

获取当前登录学生已选的全部课程，对应 Web 端「已选课程」页面的数据加载。

| 属性 | 值 |
|------|-----|
| 请求方法 | `POST` |
| Content-Type | `application/x-www-form-urlencoded;charset=UTF-8` |
| X-Requested-With | `XMLHttpRequest` |
| 认证方式 | Cookie（ASP.NET_SessionId / JSESSIONID） |
| 功能代码 | `gnmkdm=N253512` |

---

## A2. 接口 URL

```
POST {baseUrl}{basePath}/xsxk/zzxkyzb_cxZzxkYzbChoosedDisplay.html?gnmkdm={courseGnmkdm}
```

| 段 | 来源 | 默认值 |
|----|------|--------|
| `{baseUrl}` | `SchoolConfig.getBaseUrl()` | — |
| `{basePath}` | `SchoolConfig.basePath` | `/jwglxt` |
| 路径 | `SchoolConfig.selectedCoursesPath` | `/xsxk/zzxkyzb_cxZzxkYzbChoosedDisplay.html` |
| `{courseGnmkdm}` | `SchoolConfig.courseGnmkdm` | `N253512` |

**示例：**

```
POST https://jwxt.example.edu.cn/jwglxt/xsxk/zzxkyzb_cxZzxkYzbChoosedDisplay.html?gnmkdm=N253512
```

---

## A3. 请求参数

参数通过 `application/x-www-form-urlencoded` 格式放在 POST Body 中。所有参数均从选课首页的隐藏参数中提取（参见 B6.1 节）。

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| `jg_id` | String | 是 | 学院/机构 ID |
| `zyh_id` | String | 是 | 专业号 |
| `njdm_id` | String | 是 | 年级代码，如 `"2024"` |
| `zyfx_id` | String | 否 | 专业方向 ID |
| `bh_id` | String | 否 | 班号 ID |
| `xz` | String | 否 | 学制 |
| `ccdm` | String | 否 | 层次代码 |
| `xqh_id` | String | 否 | 校区号 |
| `xkxnm` | String | 是 | 选课学年，如 `"2025"` |
| `xkxqm` | String | 是 | 选课学期，如 `"12"`（秋）/ `"3"`（春） |
| `xkly` | String | 否 | 选课来源，默认 `"0"` |

**POST Body 示例：**

```
jg_id=05&zyh_id=0801&njdm_id=2024&zyfx_id=wfx&bh_id=&xz=4&ccdm=3&xqh_id=1&xkxnm=2025&xkxqm=12&xkly=0
```

---

## A4. 请求头

```http
POST /jwglxt/xsxk/zzxkyzb_cxZzxkYzbChoosedDisplay.html?gnmkdm=N253512 HTTP/1.1
Host: jwxt.example.edu.cn
Content-Type: application/x-www-form-urlencoded;charset=UTF-8
X-Requested-With: XMLHttpRequest
Accept: application/json, text/javascript, */*; q=0.01
Accept-Language: zh-CN,zh;q=0.9
User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 ...
Origin: https://jwxt.example.edu.cn
Referer: https://jwxt.example.edu.cn/jwglxt/xsxk/zzxkyzb_cxZzxkYzbIndex.html?gnmkdm=N253512&layout=default&su=jwxt.example.edu.cn
Cookie: ASP.NET_SessionId=xxx; JSESSIONID=xxx
```

---

## A5. 响应格式

### 响应结构

响应体为 JSON 数组（`JSONArray`），每个元素代表一门已选课程。

```json
[
  {
    "kch_id": "01010001",
    "do_jxb_id": "A1B2C3D4E5F6...",
    "jxb_id": "2025010001001",
    "kcmc": "高等数学A",
    "jxbmc": "高等数学A-教学班01",
    "jsxm": "张三",
    "sksj": "星期一第1-2节<br>星期三第3-4节",
    "jxdd": "教学楼A101<br>教学楼A201",
    "xf": "4.0",
    "jxbrl": 120,
    "yxzrs": 85,
    "sfxkbj": "1",
    "kklxdm": "01"
  }
]
```

### 字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| `kch_id` | String | 课程代码（唯一标识一门课） |
| `do_jxb_id` | String | **加密教学班 ID**，退课时优先使用此字段 |
| `jxb_id` | String | 教学班 ID，`do_jxb_id` 为空时作为退课回退参数 |
| `kcmc` | String | 课程名称 |
| `jxbmc` | String | 教学班名称 |
| `jsxm` | String | 教师姓名 |
| `sksj` | String | 上课时间（HTML，含 `<br>` 标签，需替换为 `, ` 或换行） |
| `jxdd` | String | 上课地点（HTML，含 `<br>` 标签，需替换为 `, ` 或换行） |
| `xf` / `jxbxf` | String | 学分 |
| `jxbrl` | Int | 教学班容量上限 |
| `yxzrs` | Int | 已选人数 |
| `sfxkbj` | String | 是否已选标记，`"1"` = 已选 |
| `kklxdm` | String | 课程类型代码（如 `"01"` = 必修） |

> **注意**：`sksj` 和 `jxdd` 字段内嵌 HTML `<br>` / `<br/>` 标签，客户端需做清洗后才能显示。

---

## A6. 客户端实现

### A6.1 调用方法

- **异步版**：`CourseApiClient.fetchSelectedCourses(school, postBody, callback)`
- **同步版**：`CourseApiClient.fetchSelectedCoursesSync(school, postBody)` — 返回原始 JSON 字符串

### A6.2 UI 层流程（`SelectedCoursesRoute.kt`）

```
loadSelectedCourses()
  │
  ├── ① fetchPageHiddenParamsSync(school)  // GET 选课首页，提取隐藏参数
  ├── ② parseCourseParams(html)             // Jsoup 解析 <input type="hidden">
  ├── ③ 构建 POST Body                      // 取 jg_id / zyh_id / njdm_id 等 11 个 key
  ├── ④ fetchSelectedCoursesSync(school, body) // POST 获取 JSON
  └── ⑤ CourseParser.parseCourseListFromJson(json) // 解析为 List<Course>
```

### A6.3 解析逻辑（`CourseParser.parseCourseListFromJson`）

JSON 响应可能有两种结构：
- 直接为数组：`[{...}, {...}]`
- 包裹在对象中：`{"tmpList": [{...}]}` 或 `{"courses": [{...}]}`

解析器自动识别并提取数组，逐条映射为 `Course` 对象。

---

# Part B — 退课（退选）

---

## B1. 接口概述

| 属性 | 值 |
|------|-----|
| 请求方法 | `POST` |
| Content-Type | `application/x-www-form-urlencoded;charset=UTF-8` |
| X-Requested-With | `XMLHttpRequest` |
| 认证方式 | Cookie（ASP.NET_SessionId / JSESSIONID） |
| 功能代码 | `gnmkdm=N253512`（与选课模块共用） |

---

## B2. 接口 URL

```
POST {baseUrl}{basePath}/xsxk/zzxkyzb_tuikBcZzxkYzb.html?gnmkdm={courseGnmkdm}
```

**URL 拼接规则：**

| 段 | 来源 | 默认值 |
|----|------|--------|
| `{baseUrl}` | `SchoolConfig.getBaseUrl()` = `{protocol}://{domain}` | — |
| `{basePath}` | `SchoolConfig.basePath` | `/jwglxt` |
| 路径 | 硬编码 `/xsxk/zzxkyzb_tuikBcZzxkYzb.html` | — |
| `{courseGnmkdm}` | `SchoolConfig.courseGnmkdm` | `N253512` |

**示例：**

```
https://jwxt.example.edu.cn/jwglxt/xsxk/zzxkyzb_tuikBcZzxkYzb.html?gnmkdm=N253512
```

---

## B3. 请求参数

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| `kch_id` | String | 是 | 课程代码（即 Course.courseId），来自已选课程列表 JSON 中的 `kch_id` 字段 |
| `jxb_ids` | String | 是 | 教学班加密 ID，优先使用 `doJxbId`（`do_jxb_id`），回退到 `classId`（`jxb_id`） |
| `xkxnm` | String | 是 | 选课学年码，如 `"2025"`，从选课首页隐藏参数提取 |
| `xkxqm` | String | 是 | 选课学期码，如 `"12"`（秋季）或 `"3"`（春季），从选课首页隐藏参数提取 |
| `txbsfrl` | String | 否 | 退选是否释放容量，固定为 `"0"` |

**POST Body 示例：**

```
kch_id=01010001&jxb_ids=A1B2C3D4E5F6...&xkxnm=2025&xkxqm=12&txbsfrl=0
```

---

## B4. 请求头要求

```http
POST /jwglxt/xsxk/zzxkyzb_tuikBcZzxkYzb.html?gnmkdm=N253512 HTTP/1.1
Host: jwxt.example.edu.cn
Content-Type: application/x-www-form-urlencoded;charset=UTF-8
X-Requested-With: XMLHttpRequest
Accept: application/json, text/javascript, */*; q=0.01
Accept-Language: zh-CN,zh;q=0.9
User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 ...
Origin: https://jwxt.example.edu.cn
Referer: https://jwxt.example.edu.cn/jwglxt/xsxk/zzxkyzb_cxZzxkYzbIndex.html?gnmkdm=N253512&layout=default&su=jwxt.example.edu.cn
Cookie: ASP.NET_SessionId=xxx; JSESSIONID=xxx
```

---

## B5. 响应格式

### 成功响应

服务器返回 JSON 格式，退课成功时有两种等价表示：

```json
"1"
```

或

```json
{"flag": "1"}
```

客户端判断逻辑：响应体 trim 后等于 `"\"1\""` 或包含 `"\"flag\":\"1\""` 即视为成功。

### 失败响应

退课失败时返回包含 `msg` 字段的 JSON：

```json
{"flag": "-1", "msg": "该课程不在退课时间范围内"}
```

常见错误消息：
- 该课程不在退课时间范围内
- 退课失败（通用错误）

---

## B6. 前置数据获取流程

退课所需的 `xkxnm` 和 `xkxqm` 参数不直接来自课程数据，需从选课首页页面动态提取（B6.1 节），**已选课程列表通过 A Part 所述接口获取**：

### B6.1 获取页面隐藏参数

```
GET {fullBasePath}/xsxk/zzxkyzb_cxZzxkYzbIndex.html?gnmkdm={courseGnmkdm}&layout=default&su={domain}
```

从返回的 HTML 中解析所有 `<input type="hidden">` 元素，提取以下关键参数：

| 参数 | 说明 |
|------|------|
| `xkxnm` | 选课学年 |
| `xkxqm` | 选课学期 |
| `jg_id` | 学院/机构 ID |
| `zyh_id` | 专业号 |
| `njdm_id` | 年级代码 |
| `xqh_id` | 校区号 |
| `kklxdm` | 课程类型代码 |
| `xkkz_id` | 选课控制 ID |

解析方式：Jsoup 选择器 `input[type=hidden]` 提取 name/value 对。

### B6.2 获取已选课程列表

```
POST {fullBasePath}/xsxk/zzxkyzb_cxZzxkYzbChoosedDisplay.html?gnmkdm={courseGnmkdm}
```

POST Body（从 B6.1 解析的参数中取）：

```
jg_id={jg_id}&zyh_id={zyh_id}&njdm_id={njdm_id}&zyfx_id={zyfx_id}&bh_id={bh_id}&xz={xz}&ccdm={ccdm}&xqh_id={xqh_id}&xkxnm={xkxnm}&xkxqm={xkxqm}&xkly={xkly}
```

响应为 JSON 数组，每个元素的关键字段：

| 字段 | 说明 | 用途 |
|------|------|------|
| `kch_id` | 课程代码 | → 退课参数 `kch_id` |
| `do_jxb_id` | 加密教学班 ID | → 退课参数 `jxb_ids`（优先） |
| `jxb_id` | 教学班 ID | → 退课参数 `jxb_ids`（回退） |
| `kcmc` | 课程名称 | 显示用 |
| `jsxm` | 教师姓名 | 显示用 |
| `jxbmc` | 教学班名称 | 显示用 |
| `sksj` | 上课时间 | 显示用（含 `<br>` 标签需清洗） |
| `jxdd` | 上课地点 | 显示用（含 `<br>` 标签需清洗） |
| `xf` / `jxbxf` | 学分 | 显示用 |
| `sfxkbj` | 是否已选标记 | `"1"` 表示已选 |

---

## B7. 完整调用时序

```
┌────────────┐         ┌──────────────────┐         ┌────────────────┐
│   客户端   │         │  选课参数页面     │         │  已选课程接口   │
└─────┬──────┘         └────────┬─────────┘         └───────┬────────┘
      │                        │                            │
      │ ① GET 选课首页         │                            │
      │  (提取隐藏参数)         │                            │
      │──────────────────────>│                            │
      │     HTML (hidden params)│                            │
      │<──────────────────────│                            │
      │                        │                            │
      │ ② 解析 xkxnm, xkxqm  │                            │
      │    等隐藏参数           │                            │
      │                        │                            │
      │ ③ POST 已选课程列表    │                            │
      │──────────────────────────────────────────────────>│
      │                   JSON Array (kch_id, do_jxb_id)  │
      │<──────────────────────────────────────────────────│
      │                        │                            │
      │ ④ POST 退课请求        │                            │
      │   kch_id + jxb_ids +   │                            │
      │   xkxnm + xkxqm        │                            │
      │──────────────────────────────────────────────────>│
      │                   "1" 或 {"flag":"1"}              │
      │<──────────────────────────────────────────────────│
```

---

## B8. 客户端实现细节

### B8.1 核心调用方法

`CourseApiClient.dropCourseSync()` — 同步退课方法，参数如下：

```java
public String dropCourseSync(
    SchoolConfig school,  // 学校配置（含 baseUrl/basePath/gnmkdm）
    String kchId,         // 课程代码 (kch_id)
    String jxbIds,        // 教学班加密ID (do_jxb_id 或 jxb_id)
    String xkxnm,         // 选课学年
    String xkxqm          // 选课学期
)
```

### B8.2 UI 层调用入口

`SelectedCoursesRoute.kt` 中的 `performDropCourse()` 方法：

1. 从缓存的 `xkxnm`/`xkxqm` 获取学年学期参数
2. 从 `Course` 对象取 `kchId`（优先 `courseId`）和 `jxbIds`（优先 `doJxbId`，回退 `classId`）
3. 调用 `CourseApiClient.dropCourseSync()`
4. 解析响应：`"1"` 或 `{"flag":"1"}` 为成功
5. 成功后更新本地缓存中该课程的 `isSelected = false` 并刷新列表

### B8.3 退课确认交互

UI 在 `SelectedCoursesScreen.kt` 中实现：
- 每门已选课程右侧显示红色圆形删除按钮
- 点击后弹出确认对话框（带动画），提示「确定要退选「xxx」吗？退课后可能无法再次选上此课程」
- 确认后调用 `onDropCourse` 回调
- 退课过程中显示 `LinearProgressIndicator` 进度条，按钮显示加载动画

---

## B9. 错误处理

| 场景 | 处理方式 |
|------|----------|
| 学年学期参数缺失 | Toast 提示「学年学期参数缺失，请刷新后重试」，阻断请求 |
| `kchId` 或 `jxbIds` 为空 | 函数提前 return，不发送请求 |
| 网络异常 | Toast 提示「退课异常: {message}」 |
| 服务器返回非 "1" | 解析 `msg` 字段显示错误消息，无 msg 时显示「退课失败」 |
| Cookie 过期 | 拦截器检测到重定向登录页，触发 `ACTION_COOKIE_EXPIRED` 广播通知用户重新登录 |

---

## B10. Cookie/会话要求

- 所有请求依赖有效的 Cookie 会话（`ASP.NET_SessionId` + `JSESSIONID`）
- Cookie 通过内置浏览器 WebView 登录获取，存入 OkHttp CookieJar（按账号隔离）
- 客户端拦截器自动检测 Cookie 失效：通过检查响应 URL 是否被重定向到登录页，或响应体中包含登录表单特征
- 检测到失效后发送 `ACTION_COOKIE_EXPIRED` 广播，UI 层弹出高优先级通知引导重新登录

---

## B11. 与「捡漏模式」的关系

退课事件是捡漏（模糊匹配）模式的数据来源。`GrabService` 的模糊匹配模式通过高频轮询已选课程列表，检测 `selected`（已选人数）下降来判断是否有人退课，从而自动发起选课请求填补空位。

检测逻辑：`currentSelected < lastSelected` 时触发选课。