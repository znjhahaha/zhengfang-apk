# 校园服务 API v2 与网站验收

日期：2026-09-21。用户要求将 VPS 工作接回 Windows，继续在 `D:\zhengfang-vps-resume` 完成。App 分支为 `codex/academic-plugins-v1`；插件和网站在同工作区的独立 `plugins` 仓库，分支为 `main`，无远端。

## 同步与交付范围

VPS Git 历史、未提交文件、交接及验收证据已导出。同步时逐文件校验 713 个源码文件的 SHA-256，其中 App 635 个、插件 78 个。后续本地修改在此独立工作区进行，未覆盖原有三个开发目录。VPS 开发与卡住的 Gradle 已停止，Paseo 接口已恢复可达；没有验证用户手机的实际连接。

本次交付包含 API v2 独立校园服务登录、会话与权限、七类原生页面模块、模块排序/隐藏/恢复、校园服务入口、正式目录公钥与下载、三亚学院 #24 登录协议修复、SDK/CLI 和五类模板，以及网站反馈、回执与源码审核。页面沿用原玻璃组件。

## Android

环境：Windows、JDK 21.0.12、Android SDK `android-37.0`。APK 使用独立包名 `com.tyust.course.uipreview` 和现有 debug 签名，不替换正式 App，也不作为公开 APK 发布。

全新执行（PowerShell 参数带点时需引号）：

```powershell
.\gradlew.bat :app:testUiPreviewUnitTest :app:assembleUiPreview :app:assembleUiPreviewAndroidTest `
  '-PuiTestBuildType=uiPreview' '-Pkotlin.compiler.execution.strategy=in-process' `
  --max-workers=1 --no-daemon --no-configuration-cache --no-build-cache --rerun-tasks `
  '-Dorg.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8' --console=plain
```

结果：`BUILD SUCCESSFUL in 6m 2s`，79 个任务执行。88 个测试套件、500 项测试，496 通过、4 跳过、0 失败/错误。跳过项均为需要显式启用及私有账号配置的真实学校测试：AcademicAuthenticatedSmokeTest、AcademicLiveSmokeTest、HutReadOnlyIntegrationTest、TyustSsoLiveTest。

MuMu Android 15/API 35（`127.0.0.1:16416`）安装主 APK 与测试 APK，执行 synthetic/MockWebServer 验收：

| 测试类 | 通过项数 |
| --- | ---: |
| CampusServiceDeviceTest | 2 |
| CampusServiceUiDeviceTest | 1 |
| PluginSandboxDeviceTest | 7 |
| PluginFlowDeviceTest | 3 |
| PluginUpdateDeviceTest | 1 |
| PluginCenterDeviceTest | 2 |

共 16 项通过。覆盖独立登录、验证码、会话隔离、匿名会话重建、账号范围撤销、查询、写入确认、模块隐藏与恢复、页面导航、浅深主题、沙箱、导入、更新与目录安装。截图检查保留原玻璃界面。

目录 UI 测试首次截图遇到绑定弹窗晚于包激活的竞态，已改为等待弹窗出现、取消并等待关闭后截图。仅重建测试 APK后，六项旧插件回归全部通过；主 APK 未因该测试修正而改变。

## 插件、工具包与网站

- SDK 2.0.0 已 build/export，synthetic 服务资产已同步导出。
- 插件 Node 测试：20 项，16 通过、4 跳过、0 失败。Windows 普通权限无法创建文件符号链接，四项文件链接测试明确跳过；目录 junction 拒绝测试实际通过。生产归档逻辑仍拒绝所有链接，没有降低校验要求；VPS Linux 上保留此前链接测试通过证据。
- 最终 starter ZIP 已独立解压后执行 `npm ci`、`npm test`，五个模板分别执行 create/check/test/pack/source-zip，共 27 步通过。
- 网站 build、TypeScript typecheck 通过，Worker 测试 7/7 通过，含访客回复设为私密后作者仍可见的回归。
- VPS 浏览器验收证据已同步：14 路由 × 2 主题 × 2 桌面宽度，共 56 组合；反馈、回执导入导出、补充/撤回、公开/私密审核、源码上传下载、发布门槛均通过，无意外浏览器错误。全部使用本地 D1 和虚构数据。

starter ZIP SHA-256：`55ef259f5b1920abb11eb7bea53e65d5fb9ceaa58e393431d2e1674154e1e3be`。

## 生产检查

网站：[校园插件站](https://plugins.hidisiwa.xyz)。Worker `academic-plugin-community` 已部署，版本 `cee0c49d-6e56-4f2e-bdb7-9043aa610f66`，D1 首次迁移成功，管理密钥已配置并有 Windows DPAPI 私有备份。

部署后 19 项公开 GET 检查和 1 项管理员认证 GET 检查通过：页面、health、目录、三包、starter 和三份文档均可读；下载摘要与本地已验收产物一致。目录、三包清单签名与可执行文件摘要均通过校验，信任锚使用 App 内置 `campus-production-2026-09` 公钥。匿名管理员请求返回 401，私有密钥认证返回 200。生产没有写入测试反馈或源码。

生产浏览器能正常显示目录。Cloudflare 自动注入的统计脚本被 `script-src 'self'` 拦截，控制台有一条 CSP 报错；统计脚本未执行，页面和接口功能不受影响。没有为统计放宽安全策略。

## 学校验收边界

山东农业大学、广州松田职业学院、三亚学院保留此前真实账号的只读验收证据，并有正式签名下载包；这次没有重跑真实账号。三亚学院此前真实登录、身份、会话和 12 条课表/已选记录通过，其余部分查询成功返回空列表。

黑龙江工程学院此前 HTTP/HTTPS 连接关闭或重置，仍不标记 verified、不开放下载。#13 华南师范大学成绩明细与第二课堂仍是样本模板，未做真实平台验收。没有执行真实选课、退课或活动报名。

## 私有验收材料

工作区 `.local/` 保存同步校验、构建/设备日志、JVM XML、starter/生产检查报告和 synthetic 截图。私有验收包包括主 APK、测试 APK、安装与重跑说明、提交 SHA、结果报告及 SHA256SUMS，不包含账号、管理密钥或真实学生数据，不加入 Git。

App 提交使用 `[skip ci]`，推送对应分支时使用 `--no-follow-tags` 并核对远端 SHA；插件仓库仅本地提交。未创建发布标签、未发布 APK、未改签名、未主动触发 CI/CD。
