# 教务识别、湖南工业大学 CAS、选课参数与今日课表组件验证

日期：2026-09-16。基于 v1.0.80（`24d0e40462556d702c59b0c9663a33ef77ebaebe`），工作分支为 `codex/academic-schedule-widget`。

本轮改动保留在工作区，未提交、推送、合并或发布；版本仍为 1.0.80。原始 HAR、登录凭据及本机构建产物不属于待交付源码。

## 实现范围

- 添加学校、编辑学校和自动登录共用结构化网址识别。先探测输入地址，再探测规范根路径；保留协议、端口和自定义路径，区分无效地址、连接失败、未知页面和成功结果。整体超时 20 秒，页面关闭或地址变更时取消旧任务，保留手动选择的系统类型。
- 湖南工业大学原生 CAS 登录使用已确认的认证入口及教务回调。支持动态 execution、RSA PKCS#1 加密、`__RSA__` 前缀、验证码刷新与继续提交；多因素认证转入网页登录。认证会话独立，换票后只导入教务 Cookie 并校验会话，取消或切换账号时清理待提交凭据。
- `xkkz_id`、`xkkz_xh` 独立贯通分类、课程、教学班、队列和缓存。各接口按自己的字段白名单组装；不能确认旧缓存字段含义时重新读取对应分类。分类响应与已知控制值冲突时，不补入另一分类的字段。只对模拟服务验证选课提交。
- 课表增加周／日切换、今日与下一节概览、返回今天、重叠课程选择、周末显示和紧凑密度。按账号保存显示偏好，按账号和学期恢复浏览位置；继续使用既有日历及周分页同步逻辑。
- 新增默认 4×2、可调整大小的 RemoteViews 今日组件。共用离线课表、节次时间和日历，显示当前／下一节、地点、时间和剩余课程数；点击进入课表或课程详情。账号、数据、日期、时区和重启变化触发本地刷新，时间边界使用非精确、不唤醒设备的闹钟，系统周期更新兜底。

## 编译与自动化测试

另一任务同时修改玻璃渲染诊断代码，因此本轮使用固定源码副本验证：`build/academic-verify-20260916/`。副本包含本轮全部源码、资源与测试；三个 `GlassLens*` 文件采用基线版本，排除另一任务的两个性能测试文件。原工作区中的这些改动完整保留。

执行命令：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --no-build-cache --rerun-tasks --console=plain
```

最终结果：`BUILD SUCCESSFUL in 3m 7s`，87 项任务全部重新执行。副本中的本轮源码与工作区逐文件哈希一致。

| 检查 | 结果 |
| --- | --- |
| JVM 测试 | 450 项：446 通过，4 条件跳过，0 失败、0 错误 |
| Debug APK | 编译成功 |
| Debug AndroidTest APK | 编译成功 |
| Lint | 0 错误、392 警告、11 提示 |
| 差异空白检查 | 通过 |
| 本轮改动敏感内容检查 | 未发现所提供凭据、真实 CAS 票据或原始 HAR |

4 项条件跳过均为需要外部环境或凭据的在线测试：`AcademicAuthenticatedSmokeTest`、`AcademicLiveSmokeTest`、`HutReadOnlyIntegrationTest`、`TyustSsoLiveTest`。湖南工业大学测试另有一次实际凭据的只读验证成功记录，见下文。

Lint 相比基线新增 7 条提示性警告：3 条 Android 31+ 组件元数据属性（低版本使用已有的尺寸和初始布局属性）、2 条组件辅助文字为 10sp、2 条 SharedPreferences KTX 写法建议。

关键测试覆盖：

- `AcademicDetectionTest`：三个入口、表单根路径、重定向、自定义路径、GBK、不可达与超时结果。
- `QzCasProtocolTest`：Vue 表单、RSA 解密对照、验证码动态 execution、service 限制、票据交换、Cookie 隔离、多因素认证转交和取消后禁止再次提交。
- `ZfSelectionControlTest`：HAR 字段结构、单字段／双字段、不同分类、响应控制值冲突、参数去重与编码保持、旧队列恢复及精确教学班保留。新增冲突用例在修复前复现失败，修复后通过。
- `ScheduleAgendaTest`、`ScheduleDisplayStoreTest`、`ScheduleWidgetTest`：单双周、跨日跨周、开学前后、时区、未知周次、无课、缓存、账号隔离、退出、删除账号、组件布局与点击路由。组件测试使用 Robolectric，覆盖 API 24／33，尺寸测试使用原生图形模式。
- 既有 `ScheduleWeekPagerSyncTest` 的 6 项周分页回归测试全部通过。

本机证据路径：

- 最终构建日志：`build/academic-isolated-verification-final.log`
- JVM XML：`build/academic-verify-20260916/app/build/test-results/testDebugUnitTest/`
- JVM HTML：`build/academic-verify-20260916/app/build/reports/tests/testDebugUnitTest/index.html`
- Lint HTML：`build/academic-verify-20260916/app/build/reports/lint-results-debug.html`
- 源码清单与哈希：`build/academic-verification-snapshot.json`
- 结果汇总与 APK 哈希：`build/academic-verification-results.json`
- Debug APK：`build/academic-verify-20260916/app/build/outputs/apk/debug/app-debug.apk`
- 测试 APK：`build/academic-verify-20260916/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`

## 在线只读验证

| 入口 | 保存路径与类型 | 本轮在线结果 |
| --- | --- | --- |
| `http://jwxt.hut.edu.cn/jsxsd/framework/xsMainV.htmlx` | `/jsxsd`，新强智 | 匿名页面识别成功；CAS 登录及课表查询成功 |
| `http://jw.hljit.edu.cn/default2.aspx` | 空路径，旧正方 | 站点连接失败；地址解析和模拟页面识别测试通过 |
| `https://jw.educationgroup.cn/gzstzyxy_jsxsd/` | `/gzstzyxy_jsxsd`，旧强智 | 匿名页面识别成功 |

湖南工业大学实际只读验证返回 `SUCCESS`，读取 `2026-2027-1` 学期的 19 条课表记录。凭据只通过临时环境变量传入，完成后清除；未执行真实选课或退课。证据为 `build/hut-readonly-evidence.xml` 和 `build/hut-readonly-widget-check.log`。

HAR 测试夹具仅保留接口路径和字段名，位于 `app/src/test/resources/academic/zf-issue10-request-shapes.json`，不含捕获值、请求头、Cookie 或原始响应。

## 模拟器验证

用户提供的两台模拟器实际为 API 32 和 API 35。本轮使用独立 `uiPreview` 包与演示数据测试，未使用真实账号进行选课写操作。

| 设备 | 结果 |
| --- | --- |
| `127.0.0.1:5555`，API 32 | 6 个目标用例全部通过；最终两个流程用例耗时 178.419 秒 |
| `127.0.0.1:5557`，API 35 | 6 个目标用例全部通过；最终两个流程用例耗时 173.697 秒 |

覆盖 280×180 与 150×110 组件布局、重叠课程逐个打开、320／360／412dp 课表对齐、最大 1.6 字体缩放、长地点文字、开学日期保存、周数恢复、周／日切换、周末隐藏、紧凑偏好、课程详情、浅／深色切换、Activity 重建，以及每台设备后台静置 125 秒后恢复浏览日期并返回当前周。

初轮流程脚本未正确展开 SystemPicker、未滚动到设置项；修正脚本后单独重跑两个流程用例，均通过。组件与适配用例沿用初轮通过结果。证据日志：

- `build/academic-device-5555-clean.log`、`build/academic-device-5557-clean.log`：组件与适配用例。
- `build/academic-flow-5555.log`、`build/academic-flow-5557.log`：修正后的最终流程结果，均为 `OK (2 tests)`。
- `build/academic-preview-clean-build.log`：预览 APK 和测试 APK 全新编译成功，71 项任务执行。

已核查的截图保存在 `build/academic-evidence/5555/` 和 `build/academic-evidence/5557/`：`agenda-day-light.png`、`agenda-day-dark.png`、`agenda-background-restored.png`、`agenda-overlap.png`、`widget-280-180.png`、`widget-150-110.png`。图片为本机验证产物，不加入源码仓库。

黑龙江站的线上可达性尚未验证成功；该限制与本地网址解析修复分开记录。本轮未安排任何发布动作。
