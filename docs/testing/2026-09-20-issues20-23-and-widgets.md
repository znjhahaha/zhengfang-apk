# 四个 issue、队列轮询和课表组件验收

本轮在 `codex/academic-schedule-widget` 工作区完成代码修改和本地打包，保留原有改动。首次交付时没有提交、推送、发布版本或关闭 issue。用户后续要求安装到 API 32 测试机，然后提交并推送但不触发 CI/CD；提交前复核见文末。学校适配继续暂缓，没有创建适配分支。

| 项目 | 已实现和验证的行为 | 验收边界 |
| --- | --- | --- |
| #20 网页登录裁切 | 独立紧凑顶栏；WebView 首次有效布局后导航；显式填满可用区域；页面历史随 Activity 重建恢复；键盘出现时收起底部说明。两台设备实测温州理工官网的账号、密码和登录按钮可见。 | MuMu 将输入交给主机，Android 软件键盘没有可见区域；键盘遮挡验收条件跳过。 |
| #21 南京工程可选课程列表 | 补齐 AJAX、Referer、同源 Origin；沿用分类控制参数白名单；区分空列表、选课未开放、登录失效和服务端错误。 | **未标记完全修复。** 此前两次官网登录均返回账号或密码错误，尚未取得该账号的可选、已选和周课表真实响应。 |
| #22 路径和参数混用 | 统一新正方端点拼接，覆盖空路径、`/jwglxt`、自定义根路径和已有前缀；保留控制参数白名单；教学班操作标识按表单编码。提交结果不明时先查已选，不能确认就暂停。 | 请求和写操作使用 MockWebServer 验证，没有对真实账号执行选课或退课。 |
| #23 精确教学班 | 分离课程 ID、教学班身份 ID 和本次提交 ID；分页查找；去重；旧标识只在班号与教师/时间证据唯一时重绑；精确模式不自动选其他班。 | 已覆盖旧队列、第二页目标、重复项、同名课程和刷新后提交标识改变。 |
| 全队列轮询 | 新协议队列仅在实际尝试期间占用并发名额；旧队列重试回到队尾，等待不占工作名额；多关键词单线程也轮询全部。 | 5 门课在 1/2 个工作名额下都获得首轮尝试，并各执行 3 次；验证码阻断后等待项不再发送请求。真实抢课压力测试未执行。 |
| 日期栏与课表 | 去掉日期栏背景滑槽，保留选中日期滑块及动效；静止分散悬浮、滚动后显示有边距和阴影的玻璃面板；保留课程详情与原有交互。 | 验证窄屏、大字号、浅深色、日周切换、学期与账号隔离、Activity 重建及后台 125 秒恢复。 |
| 桌面组件 | 增加简洁单课、双课程、今日时间轴；可从课表菜单选择预览，也可在设置/桌面组件列表分别添加；小尺寸保留名称、教室、时间，大字号按空间降级。 | RemoteViews 原生布局、每张课程卡的跳转、浅深色和 2 倍字号均通过设备检查；时间轴只展示当天课程，空间不足提示查看全部。 |

## 构建与测试

- 新鲜全量 JVM 运行：478 项，474 通过，4 项在线条件测试跳过，0 失败、0 错误。
- Debug APK、Debug 设备测试 APK、UI Preview APK、Preview 设备测试 APK 均已编译。
- 最终 Lint：0 错误、404 警告、11 提示；未把警告说成全部清零。报告位于 `app/build/reports/lint-results-debug.html`。
- 设备为用户指定的 `127.0.0.1:5555`（API 32）和 `127.0.0.1:5557`（API 35），本机没有使用 API 37。
- 按每个测试的最后一次结果去重，两台设备各 27 项通过、1 项软件键盘条件跳过。初期失败记录保留，以最后一次对应测试结果为准。
- 125 秒恢复从原任务返回验证；避免把启动另一个 MainActivity 的冷启动误当作后台恢复。
- 源码差异空白检查通过。测试凭据、Cookie、原始抓包和本地截图未加入源码。

全量命令：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --no-build-cache --rerun-tasks --console=plain
.\gradlew.bat :app:assembleUiPreview :app:assembleUiPreviewAndroidTest -PuiTestBuildType=uiPreview --console=plain
```

测试代码后续仅修正模拟器显示屏/后台操作和页面加载等待，再次编译设备测试 APK 并执行 Lint。业务代码对应的全量测试证据为 `build/issues-20-23-20260919/full-final.log`。

## 本地交付

目录：`D:/zfapk/build/deliverables/issues-20-23-20260920/`。

| 文件 | 用途 | SHA-256 |
| --- | --- | --- |
| `zhengfang-issues20-23-debug.apk` | 正式功能的 Debug 安装包 | `124168d812c31721a340cace03f44868f548d96b45debaacabadf1937e4c9627` |
| `zhengfang-ui-preview.apk` | 独立包名的演示/界面预览 | `4fa177ebaf404d9ca5affa1ae6d5fe8326a6d3583ee61d9ff40a66f1b1fff365` |
| `zhengfang-debug-tests.apk` | Debug instrumentation 包 | `1bfcc9699b344d855f6dcedc3407bca60dd7cb5a2e6a6d436f5e8c177d3bf677` |
| `zhengfang-preview-tests.apk` | Preview instrumentation 包 | `16a9afd2f3f4b2a1b572657fcb1de5ac0db38a781990b4137225092c5794af3f` |

关键截图保存在 `D:/zfapk/build/issues-20-23-20260919/verified-5557/`：`date-header-no-track.png`、`widget-styles-contact.png`、`wzut-login-live.png`、`web-login-large-font-restored.png`、`verified-course-detail-light.png`、`verified-course-detail-dark.png`。

原始测试日志在 `build/issues-20-23-20260919/`。课表全面测试见 `schedule-5555.log` / `schedule-5557.log`；本轮界面、组件和导航见 `acceptance-5555.log` / `acceptance-5557.log`；登录最终结果见 `final-web-5555.log` / `final-restoration-5557.log`；窄屏和日期操作复验见 `final-layout-5555.log` / `final-layout-5557.log`。

当前版本课程详情的浅深色截图与断言另见 `final-detail-5555.log` / `final-detail-5557.log`。最后一次测试包编译和 Lint 见 `delivery-lint.log`，结果通过。

待补验收仅包括南京工程真实登录后的查询链，以及有可见软件键盘设备上的键盘遮挡检查。学校适配后台仍未恢复处理。

## 提交前复核（2026-09-20）

- API 32 测试机 `127.0.0.1:5555` 已安装首次交付的 Debug APK，应用数据保留。读取设备上的 `base.apk` 计算 SHA-256，结果与上表的 `124168d8…c9627` 完全一致。
- 当前完整工作区重新执行全量 JVM 测试、Debug APK、设备测试 APK 编译和 Lint，87 项任务全部执行，结果成功。JVM 共 478 项，474 通过、4 条件跳过；Lint 为 0 错误、404 警告、11 提示。日志：`build/resume-20260920-full.log`。
- 本任务提交范围为 104 个源码、资源、测试及验证文档。另一个任务的玻璃捕获和渲染性能改动、性能测试、基准规则及调研文档继续保留在本地；`GrabProScreen.kt` 只提交两处全队列轮询说明。APK、截图、原始日志和本机配置不纳入提交。
- 为验证提交范围可独立构建，从暂存区导出完整源码到 `build/resume-commit-20260920/`。这个副本不含上述玻璃性能改动，与首次交付安装包的源码范围有所不同。复核日志：`build/resume-commit-20260920-full.log`。
- 待提交副本完整验证成功，87 项任务全部执行：JVM 共 474 项，470 通过、4 条件跳过、0 失败；Debug APK 和设备测试 APK 编译成功；Lint 为 0 错误、403 警告、11 提示。与工作区统计的差异来自未提交的玻璃性能测试和诊断代码。
- 推送目标为现有工作分支 `codex/academic-schedule-widget`，提交信息包含 `[skip ci]`。仓库仅有的发布工作流由 `v*` 标签或手动操作触发；本次仅推送分支，不创建版本标签，也不执行发布或关闭 issue。
