# Native Android Course Selector

这个文件夹包含了一个标准的 Android Studio 项目。

## 如何运行

1. 打开 **Android Studio**。
2. 选择 **Open** (打开项目)。
3. 导航到此文件夹 `nextjs-course-selector-app` 并点击 **OK**。
4. 等待 Gradle 同步完成。
5. 连接 Android 手机或启动模拟器。
6. 点击绿色的 **Run** 按钮 (Shift+F10)。

## 项目结构

- `app/src/main/java`: Java 源代码
- `app/src/main/res/layout`: 界面布局 XML
- `app/build.gradle`: 模块构建配置

## 开发进度

目前已完成：
- [x] 项目基础骨架
- [x] Gradle 配置
- [x] 登录页面布局 (`activity_login.xml`)
- [x] 主页入口 (`MainActivity.java`)

接下来的工作：
- 实现网络请求工具类 (OkHttp)
- 对接教务系统登录逻辑
- 解析课程表 HTML
