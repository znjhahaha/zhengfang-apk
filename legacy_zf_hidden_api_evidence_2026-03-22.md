# 老版正方隐藏接口取证报告

## 结论

未发现 `jw.hljit.edu.cn` 当前这套老版正方部署存在可按“新正方 API 方案”接入的独立隐藏业务接口。

当前可确认的选课相关行为均退化为 ASP.NET WebForms 页面回帖，核心特征是：

- 同页 `POST`
- `__VIEWSTATE` / `__EVENTTARGET` / `__EVENTARGUMENT`
- submitter 驱动的表单回放
- `javascript:__doPostBack(...)`

因此，`jw.hljit.edu.cn` 当前部署不适合按新版 `/jwglxt/...` 那种独立后端接口链接入。若坚持接入，只能走 WebForms 表单回放方案；按本次约束，应停止实现并给出否定结论。

## 站点架构

- 登录入口：`http://jw.hljit.edu.cn/default2.aspx`
- 登录依赖：
  - `__VIEWSTATE`
  - `__VIEWSTATEGENERATOR`
  - `txtUserName`
  - `TextBox2`
  - `txtSecretCode`
  - `RadioButtonList1`
  - 页面内公钥字段 `txtKeyExponent` / `txtKeyModulus`
- 登录后框架页：`xs_main.aspx?xh=20250402`
- 业务页在 `zhuti` iframe 中加载

## 页面入口

本次实际打开并取证的页面：

- `xsxk.aspx?xh=20250402&xm=郭宇鹏&gnmkdm=N121101`
- `xf_xsqxxxk.aspx?xh=20250402&xm=郭宇鹏&gnmkdm=N121112`
- `xstyk.aspx?xh=20250402`
- `zylb.aspx?xh=20250402&nj=2025`

页面角色：

- `xsxk.aspx`：主选课页，分类按钮切换
- `xf_xsqxxxk.aspx`：全校性选修课页，支持筛选、排序、分页、立即提交
- `xstyk.aspx`：体育课页，独立新标签页打开
- `zylb.aspx`：页面内辅助 frame，用于专业/条件维度列表，不是独立业务 API

## 脚本证据

### `xsxk.aspx`

- `form action` 即当前页：`xsxk.aspx?...`
- `form method`：`POST`
- hidden 字段只有典型 WebForms 状态：
  - `__EVENTTARGET`
  - `__EVENTARGUMENT`
  - `__LASTFOCUS`
  - `__VIEWSTATE`
  - `__VIEWSTATEGENERATOR`
- 页面脚本来源：
  - `jquery.min.js`
  - `bootstrap.min.js`
  - `OpenDiago.js`
  - `jquery.qrcode.min.js`
  - `fc.js`
  - `WebResource.axd`
  - 若干 inline script
- 关键词扫描结果：
  - 命中 `__doPostBack`
  - 命中 `OpenDiago`
  - 未命中任何选课相关 `.ashx` / `.asmx` / `.svc` / `PageMethods`

### `xf_xsqxxxk.aspx`

- `form action` 即当前页：`xf_xsqxxxk.aspx?...`
- `form method`：`POST`
- hidden 字段除 WebForms 状态外，还包含行级 `kcmcGrid$ctlXX$jcnr`
- 排序列头都是：
  - `javascript:__doPostBack('kcmcGrid$ctl01$ctl00','')`
  - `javascript:__doPostBack('kcmcGrid$ctl01$ctl01','')`
  - 等同类回帖入口
- 页面脚本来源：
  - `OpenDiago.js`
  - `jquery.min.js`
  - `bootstrap.min.js`
  - `WebResource.axd`
  - 若干 inline script
- 关键词扫描结果：
  - 命中 `__doPostBack`
  - 未发现选课相关 `.ashx` / `.asmx` / `.svc` / `PageMethods`

### `xstyk.aspx`

- `form action` 即当前页：`xstyk.aspx?xh=20250402`
- `form method`：`POST`
- hidden 字段仍是典型 WebForms 状态字段
- 脚本仅见：
  - `jquery.min.js`
  - `bootstrap.min.js`
  - inline script
- 未发现独立选课接口引用

### `zylb.aspx`

- `form action` 即当前页：`zylb.aspx?xh=20250402&nj=2025`
- 页面文本与功能均表现为专业列表/条件面板
- 可见 `__doPostBack`
- 未发现独立业务接口

## 网络证据

### 已观察到的异步请求

- `POST /ajaxRequest/xyjd.ashx`

结论：

- 该请求发生在首页学业进度相关流程中
- 与 `xsxk.aspx`、`xf_xsqxxxk.aspx`、`xstyk.aspx` 的选课列表、筛选、排序、分页、提交没有直接对应关系
- 不应视为选课隐藏接口

### 未发现的请求类型

在以下动作中，未发现独立于页面回帖的选课业务接口：

- 打开 `xsxk.aspx`
- `xsxk.aspx` 点击“选修课程”
- 打开 `xf_xsqxxxk.aspx`
- `xf_xsqxxxk.aspx` 点击“确定”搜索
- `xf_xsqxxxk.aspx` 点击排序列头
- `xf_xsqxxxk.aspx` 点击分页按钮
- 打开 `xstyk.aspx`

## 页面动作证据

### `xsxk.aspx` 分类按钮

对“选修课程”按钮做页面内提交拦截后，记录到：

- `action`: `xsxk.aspx?...`
- `method`: `post`
- `submitterName`: `Button2`
- `submitterValue`: `选修课程`

提交键仅为页面表单字段，例如：

- `__EVENTTARGET`
- `__EVENTARGUMENT`
- `__VIEWSTATE`
- `__VIEWSTATEGENERATOR`
- `zymc`
- `xx`
- `Button2`
- `txtPjUrl`

未出现任何隐藏 XHR / fetch / handler 调用。

### `xf_xsqxxxk.aspx` 搜索

对“确定”按钮做页面内提交拦截后，记录到：

- `action`: `xf_xsqxxxk.aspx?...`
- `method`: `post`
- `submitterName`: `Button2`
- `submitterValue`: `确定`

提交键为页面表单字段，例如：

- `__EVENTTARGET`
- `__EVENTARGUMENT`
- `__VIEWSTATE`
- `__VIEWSTATEGENERATOR`
- `ddl_kcxz`
- `ddl_ywyl`
- `ddl_kcgs`
- `ddl_xqbs`
- `ddl_sksj`
- `TextBox1`
- `Button2`
- `kcmcGrid$ctl02$jcydxz`
- `kcmcGrid$ctl02$jcnr`
- `dpkcmcGrid$txtChoosePage`
- `dpkcmcGrid$txtPageSize`

未出现任何前置 XHR / fetch。

### `xf_xsqxxxk.aspx` 排序

对“课程名称”列头点击后，捕获到程序化提交：

- `action`: `xf_xsqxxxk.aspx?...`
- `method`: `post`
- `eventTarget`: `kcmcGrid$ctl01$ctl00`

这说明排序不是独立接口，而是：

1. 链接执行 `javascript:__doPostBack(...)`
2. 页面设置 `__EVENTTARGET`
3. 调用 `form.submit()`
4. 同页回帖

### `xf_xsqxxxk.aspx` 分页

点击“下一页”后，页面从“第 1 页”变为“第 2 页”，但未观察到独立选课业务 XHR。

该行为与 WebForms 回帖一致：

- 仍是当前页内容刷新
- 没有出现独立列表 API

### `xf_xsqxxxk.aspx` 提交入口

“立即提交”按钮本身也是当前页 submitter，不是独立提交 endpoint。

页面已确认：

- `form action`: `xf_xsqxxxk.aspx?...`
- `submitter`: `Button1`
- 行选择依赖：
  - 行 checkbox，如 `kcmcGrid$ctl02$xk`
  - 行教材单选，如 `kcmcGrid$ctl02$jcydxz`
  - 行 hidden 字段，如 `kcmcGrid$ctl02$jcnr`

没有发现页面外部的“最终提交 API”。

## 可疑接口清单

### `ajaxRequest/xyjd.ashx`

保留为可疑点，但已排除为选课接口：

- 只在首页学业进度相关流程中出现
- 与选课页动作没有对应关系

### `zylb.aspx`

保留为可疑点，但已排除为隐藏业务接口：

- 它本身仍是 `.aspx` 页面
- 作用是页面内专业列表/条件辅助
- 不是课程列表 JSON / 班级明细 / 提交接口

### `WebResource.axd`

保留为平台资源点，但已排除：

- 提供 ASP.NET WebForms 客户端脚本
- 不是业务数据接口

## 源码仓库参照

补充参照仓库：[`undefinedv/ZF-system`](https://github.com/undefinedv/ZF-system)

从仓库首页文件树可以确认，这套老版正方产品线本身确实包含一些 webservice / service 组件，例如：

- `Service.asmx`
- `BI_LoginCheck.asmx`
- `zf_webservice/`
- 多个老版选课页面，如 `XKJS.aspx`、`xstyk.aspx` 等

这说明两件事：

1. 老版正方这个产品族并不是“绝对没有服务端接口组件”。
2. 但这些组件是否在某个学校部署中实际启用、是否用于选课主流程、以及是否可脱离页面独立调用，必须以具体部署站点取证为准。

本次 live 取证仍然支持原结论：

- 在 `jw.hljit.edu.cn` 的实际选课路径上，没有观测到这些独立 service/webservice 组件参与主流程。
- 当前观测到的主流程仍然是 WebForms 页面回帖，不是独立隐藏业务接口链。

### 基于源码名称的部署探测

根据源码仓库首页可见的服务组件命名，本次额外对学校站点做了同名端点探测：

- `/Service.asmx`
- `/BI_LoginCheck.asmx`
- `/zf_webservice/`
- `/zf_webservice/Service.asmx`
- `/zf_webservice/BI_LoginCheck.asmx`

探测结果：

- 全部返回 `404`
- 没有任何一个端点暴露为可访问的 ASMX / webservice 服务描述页
- 没有发现这些源码同名服务组件在当前学校部署中被直接发布

这进一步加强了部署层结论：

- 即使老版正方源码产品线包含 service / webservice 组件
- `jw.hljit.edu.cn` 当前部署也没有把这些最直观的服务端点公开出来供选课流程使用

因此，报告结论应理解为：

- 不是“老版正方源码里完全没有接口组件”
- 而是“`jw.hljit.edu.cn` 当前部署的选课流程，没有暴露出可按新正方方式接入的独立接口链”

## 排除理由

以下标准全部满足，因此给出“无新正方式隐藏接口”结论：

- 课程列表获取未发现独立业务接口
- 分类切换未发现独立业务接口
- 搜索/筛选未发现独立业务接口
- 排序未发现独立业务接口
- 分页未发现独立业务接口
- 提交前未发现独立业务接口
- 源码同名 `asmx` / `zf_webservice` 端点在当前部署中均未暴露
- 已确认存在且主导流程的是：
  - 同页 `POST`
  - `__doPostBack`
  - WebForms hidden state

## 是否存在可接入隐藏接口

结论：不存在足够证据支持的、可按“新正方 API 方案”接入的隐藏业务接口。

当前站点选课相关页面整体更像：

- 页面即接口
- 提交即回帖
- 状态由 `__VIEWSTATE` 维护

而不是：

- 独立课程列表接口
- 独立课程详情接口
- 独立选课提交接口

## 如果存在应当长什么样

本次未发现。但若真存在，至少应满足以下其中之一：

- 页面动作触发稳定的 `XHR/fetch`
- 页面 JS 中存在明确业务 handler，如课程列表或提交 `.ashx/.asmx/.svc`
- 排序/分页/筛选通过异步接口请求数据而不是 `__doPostBack`
- 提交按钮指向页面外部 endpoint

本次证据均不满足。

## 停止理由

按本次约束：

- 只接受“像新正方一样的隐藏业务接口”
- 不接受 WebForms 页面回帖方案

因此应在此停止实现，不进入 `zf_old` 接入阶段。
