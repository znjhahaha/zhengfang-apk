# 正方教务助手

把课表、成绩、考试安排和选课放进一个 Android 应用。以液态玻璃界面呈现日常教务信息，通过校园插件连接不同学校。

[下载 APK](https://github.com/znjhahaha/zhengfang-apk/releases/latest) · [插件商店](https://plugins.hidisiwa.xyz) · [反馈问题](https://plugins.hidisiwa.xyz/feedback) · [更新记录](CHANGELOG.md)

## 液态玻璃界面

半透明卡片、悬浮导航和背景在同一层次中衔接，课程详情与设置沿用统一材质。可选择浅色、深色、预设背景或自定义图片；玻璃效果随设备能力调整。

| 悬浮导航 | 课程详情 | 分组设置 |
| :---: | :---: | :---: |
| <img src="docs/images/liquid-glass/navigation.jpg" width="250" alt="课程列表与液态玻璃悬浮导航"> | <img src="docs/images/liquid-glass/course-detail.jpg" width="250" alt="课程时间、地点与课前提醒面板"> | <img src="docs/images/liquid-glass/settings.jpg" width="250" alt="账号和外观分组设置"> |
| 插件中心 | 每日课表 | 背景选择 |
| <img src="docs/images/liquid-glass/plugin-center.jpg" width="250" alt="插件中心的搜索和发现列表"> | <img src="docs/images/liquid-glass/timetable.jpg" width="250" alt="按时间排列的每日课程"> | <img src="docs/images/liquid-glass/wallpaper.jpg" width="250" alt="玻璃材质的背景选择面板"> |

六张图片于 2026-09-25 在本机 MuMu Android 15 实拍，使用 `1.0.87-ui-preview` 与演示数据，仅展示该版本界面。3.2.1 的学校搜索、学期提醒和源码协作以本文及相应版本说明为准，截图不作为这些功能的设备验收。详见 [素材来源](docs/images/liquid-glass/README.md)。

## 开始使用

1. 从 Releases 下载并安装 APK，支持 **Android 7.0 及以上**。
2. 在登录页按名称搜索学校，选择对应教务适配并确认权限；已有自定义学校可直接选择，也可手动添加学校、确认地址与系统类型。
3. 查看课表和成绩，或将课程加入选课队列。学校需要验证码或额外确认时，按页面提示完成。

应用内可以检查更新。默认分支包含后续版本的开发内容，已发布功能以对应 Release 为准。

## 主要功能

| 功能 | 内容 |
| --- | --- |
| 课表 | 日／周视图、课程详情、日期切换、日历导出；一次设置所选学期的课前提醒 |
| 成绩与考试 | 按学期查询成绩、绩点和考试安排；以学校提供的数据为准 |
| 选课 | 课程查询、选退课、批量队列、定时执行和余量检测 |
| 校园插件 | 学校搜索、教务适配、原生页面和网页服务；经授权复用本校登录；按版本下载公开源码参与修改 |
| 个性化 | 浅色／深色主题、自定义背景与随设备能力调整的玻璃效果 |
| 消息与反馈 | 更新提示、公告、问卷提醒，以及无需 GitHub 账号的站内反馈 |

选课窗口、名额、冲突规则和操作结果由学校系统决定。定时与后台任务受通知、闹钟权限及系统后台管理影响；应用不会绕过学校验证码或选课限制。

## 校园插件

学校搜索合并已保存的学校与通过签名验证的插件目录，显示兼容状态和可选提供者。选择适配后按下载校验、权限确认和激活顺序处理，完成后才绑定学校；多个候选由你选择，原有自定义地址会保留。

在插件中心管理本校适配与已安装插件，按学校域名、端口及路径匹配。本校停用、卸载和回滚各有独立入口；离线时仍能使用已有配置及可用缓存。

同一学校可以启用不同作者的功能插件。需要已有教务登录时，由你授权宿主为该插件发送限定范围的请求，插件不会取得密码或 Cookie 原文。插件详情可撤销授权，账号、学校、教务适配或插件版本改变后重新确认；独立认证的服务仍使用各自账号。

插件详情的「本版本源码与参与修改」对应当前安装版本，可查看许可证、源码摘要与仓库入口。下载源码后可以向现维护者提交修复，也可保留原作者与许可证、使用新 ID 独立衍生。历史版本没有公开源码时会明确提示。

[插件商店](https://plugins.hidisiwa.xyz) 提供功能介绍、作者资料、适用学校、兼容要求及已公开的验收记录。正式目录中的插件经过审核、验收和签名，旧版插件继续兼容。

欢迎有能力的同学为自己的学校开发独立适配，或编写校园服务插件。**开发者 QQ 群：1074017033**。SDK、模板、接口文档和上传入口统一放在 [开发者中心](https://plugins.hidisiwa.xyz/developers)。SDK 3.2.1 保持 API 主版本 3；网站源码、使用文档、可复制的 Agent 提示词及两类开发包在 [插件仓库](https://github.com/znjhahaha/zhengfang-plugins) 同步维护。本仓库保留 App 运行时与生成的契约资源。

## 一次设置学期提醒

在课表设置的「课前提醒」中，可一次开启或关闭当前账号、所选学期完整课表的提醒，包含手动课程。已设置的提前时间会保留，新开启的提醒默认提前 15 分钟；之后新加入的课程默认关闭，可以逐门调整。

页面分别显示开启数量和可调度状态。按提示处理通知、精确闹钟权限，以及学期日期和节次时间；缺少时间、已经结束或受到系统限制时，不会把已保存的开关当作通知已经安排。

## 反馈与隐私

使用 [站内反馈](https://plugins.hidisiwa.xyz/feedback) 提交问题并查看回复；选择公开提交后会同步 GitHub Issue。App 的设置、插件详情及运行错误页也有快捷反馈入口。

反馈请说明 App 版本、学校、操作步骤与错误提示，截图先遮住学号等个人信息。不要公开密码、验证码或会话令牌。

已保存的登录凭据使用 Android Keystore 加密保存在本机，登录时提交到所选学校的教务或认证地址。匿名使用统计可以在设置中关闭，统计不包含账号、学校或课程内容。

## 从源码构建

环境：**JDK 17 或以上**、Android SDK Platform **37.0**。使用仓库自带的 Gradle Wrapper；无需 Node.js 或单独生成插件 SDK。

```sh
git clone https://github.com/znjhahaha/zhengfang-apk.git
cd zhengfang-apk
./gradlew testDebugUnitTest assembleDebug
```

Windows 使用 `gradlew.bat`。通过 `ANDROID_HOME` 或本机 `local.properties` 配置 SDK 路径。调试包位于 `app/build/outputs/apk/debug/`。

插件与界面设备测试使用独立的预览包，不覆盖正式 App：

```sh
./gradlew assembleUiPreview assembleUiPreviewAndroidTest -PuiTestBuildType=uiPreview
```

设备回归脚本位于 `scripts/`，可传入设备序列号、API 级别和 ADB 路径。

## 参与开发

欢迎提交 Issue 和 Pull Request。涉及学校差异时优先考虑插件；修改 App 时请说明问题、修改结果及验证方法，界面改动附截图。

仓库主要包含 `app/`、Gradle 构建配置、`scripts/` 和发布记录。发布说明以 `release-notes/` 为准，由 GitHub Actions 完成构建和发布。

项目使用 [GNU GPL v3](LICENSE)。修改和分发时请遵守许可证，保留版权与许可说明，并按要求提供对应源码。
