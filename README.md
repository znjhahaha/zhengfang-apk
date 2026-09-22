# 正方教务助手

把课表、成绩、考试安排和选课放进一个 Android 应用。支持新正方、旧正方、新强智和旧强智，并通过校园插件适配不同学校。

[下载 APK](https://github.com/znjhahaha/zhengfang-apk/releases/latest) · [插件商店](https://plugins.hidisiwa.xyz) · [反馈问题](https://plugins.hidisiwa.xyz/feedback) · [更新记录](CHANGELOG.md)

## 开始使用

1. 从 Releases 下载并安装 APK，支持 **Android 7.0 及以上**。
2. 选择或添加学校，确认教务地址与系统类型，按学校要求登录。
3. 查看课表和成绩，或将课程加入选课队列。学校需要验证码或额外确认时，按页面提示完成。

应用内可以检查更新。默认分支包含后续版本的开发内容，已发布功能以对应 Release 为准。

## 主要功能

| 功能 | 内容 |
| --- | --- |
| 课表 | 日／周视图、课程详情、日期切换、日历导出 |
| 成绩与考试 | 按学期查询成绩、绩点和考试安排；以学校提供的数据为准 |
| 选课 | 课程查询、选退课、批量队列、定时执行和余量检测 |
| 校园插件 | 按学校匹配教务适配，安装校园服务和原生页面扩展 |
| 个性化 | 浅色／深色主题、自定义背景与随设备能力调整的玻璃效果 |
| 消息与反馈 | 更新提示、公告、问卷提醒，以及无需 GitHub 账号的站内反馈 |

选课窗口、名额、冲突规则和操作结果由学校系统决定。定时与后台任务受通知、闹钟权限及系统后台管理影响；应用不会绕过学校验证码或选课限制。

## 校园插件

在 App 的插件中心查看「本校」和「已安装」，安装适配后按学校域名、端口及路径匹配；多个教务适配冲突时可手动选择。本校停用、卸载和回滚各有独立入口。

[插件商店](https://plugins.hidisiwa.xyz) 提供功能介绍、作者资料、适用学校、兼容要求及已公开的验收记录。正式目录中的插件经过审核、验收和签名，旧版插件继续兼容。

欢迎有能力的同学为自己的学校开发独立适配，或编写校园服务插件。**开发者 QQ 群：1074017033**。SDK、模板、接口文档和上传入口统一放在 [开发者中心](https://plugins.hidisiwa.xyz/developers)。本仓库保留 App 使用的运行时与协议文件，SDK 生成源码和网站独立维护。

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
