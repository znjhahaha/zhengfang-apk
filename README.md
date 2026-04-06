<p align="center">
  <img src="pic/07_登录页.jpg" width="200"/>
  <img src="pic/01_课程列表.jpg" width="200"/>
  <img src="pic/03_课表.jpg" width="200"/>
</p>

<h1 align="center">📚 正方教务助手</h1>

<p align="center">
  <strong>开源 · 免费 · 安全</strong><br/>
  一款面向正方教务系统的 Android 智能选课客户端
</p>

<p align="center">
  <a href="https://github.com/znjhahaha/zhengfang-apk/releases/latest"><img src="https://img.shields.io/github/v/release/znjhahaha/zhengfang-apk?style=flat-square&color=blueviolet&label=最新版本" alt="Release"/></a>
  <a href="https://github.com/znjhahaha/zhengfang-apk/actions"><img src="https://img.shields.io/github/actions/workflow/status/znjhahaha/zhengfang-apk/release.yml?style=flat-square&label=CI/CD" alt="CI"/></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache%202.0-blue?style=flat-square" alt="License"/></a>
</p>

---

## ✨ 功能亮点

| 功能 | 说明 |
|------|------|
| 🔐 **安全登录** | 采用 Cookie 认证，**无需在 App 内输入密码**，杜绝账号泄露风险 |
| 📋 **课程浏览** | 按关键词搜索、按类别筛选，实时显示课程余量与教师信息 |
| ⚡ **智能抢课** | 定时抢课 · 立即抢课 · 捡漏模式，毫秒级发包，三种策略覆盖所有选课场景 |
| 📅 **课表查看** | 清晰周视图，不同课程自动配色，支持导出 `.ics` 同步至系统日历 |
| 📊 **成绩查询** | 按学期筛选，自动计算 GPA，一键查看成绩详情 |
| 🏫 **多校适配** | 内置多校配置，也可自行添加任意正方教务系统的学校 |

---

## 📸 功能预览

<table>
  <tr>
    <td align="center"><img src="pic/01_课程列表.jpg" width="180"/><br/><sub>课程列表</sub></td>
    <td align="center"><img src="pic/02_定时抢课.jpg" width="180"/><br/><sub>定时抢课</sub></td>
    <td align="center"><img src="pic/10_立即抢课.jpg" width="180"/><br/><sub>立即抢课</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="pic/03_课表.jpg" width="180"/><br/><sub>课表查看</sub></td>
    <td align="center"><img src="pic/06_成绩查询.jpg" width="180"/><br/><sub>成绩查询</sub></td>
    <td align="center"><img src="pic/04_已选课程.jpg" width="180"/><br/><sub>已选课程</sub></td>
  </tr>
</table>

---

## 🚀 快速开始

### 方式一：直接下载（推荐）

前往 [**Releases 页面**](https://github.com/znjhahaha/zhengfang-apk/releases/latest) 下载最新版 APK，安装即用。

### 方式二：从源码构建

```bash
# 1. 克隆项目
git clone https://github.com/znjhahaha/zhengfang-apk.git
cd zhengfang-apk

# 2. 构建 Debug 版本
./gradlew assembleDebug
```

> **环境要求**：Android Studio Hedgehog+、JDK 17、Android SDK 34

---

## 🔧 使用指南

### 第一步：获取 Cookie

1. 在手机浏览器中打开学校的教务系统并登录
2. 复制浏览器地址栏中的 Cookie（详见 App 内教程）
3. 将 Cookie 粘贴到 App 的登录页面

<p align="center">
  <img src="pic/08_浏览器获取Cookie.jpg" width="280"/>
</p>

### 第二步：开始选课

- **定时抢课**：设置开抢时间 → 配置课程队列 → 坐等自动发包
- **立即抢课**：选中目标课程 → 点击抢课 → 实时监控余量变化
- **捡漏模式**：针对满员课程，挂机等待退课名额

---

## 🏗️ 技术架构

| 模块 | 技术选型 |
|------|----------|
| 开发语言 | Kotlin + Java |
| UI 框架 | Jetpack Compose + 传统 View 混合迁移 |
| 网络请求 | OkHttp 4 |
| 异步处理 | Kotlin Coroutines |
| HTML 解析 | Jsoup |
| 数据存储 | SharedPreferences |
| CI/CD | GitHub Actions（自动构建 + 发布） |

<details>
<summary>📁 项目结构</summary>

```
app/src/main/java/com/tyust/course/
├── activation/        # 设备激活与配额管理
├── fragment/          # Fragment 页面容器
├── manager/           # 业务管理器（选课逻辑、用户状态）
├── model/             # 数据模型（Course、SchoolConfig）
├── network/           # 网络层（CourseApiClient、Cookie 管理）
├── service/           # 后台抢课服务（GrabService）
├── ui/
│   ├── route/         # 页面路由与业务逻辑
│   ├── screen/        # Compose UI 组件
│   └── theme/         # Material 3 主题配置
├── update/            # 应用内更新模块
└── utils/             # 工具类（解析、导出、设备识别）
```
</details>

---

## 🤝 贡献指南

欢迎提交 Pull Request！

1. Fork 本仓库
2. 创建特性分支：`git checkout -b feature/amazing-feature`
3. 提交修改：`git commit -m 'feat: 添加新功能'`
4. 推送分支：`git push origin feature/amazing-feature`
5. 提交 Pull Request

> **注意**：Debug 构建版会显示开源水印，这是正常行为，不影响开发调试。

---

## ⚠️ 免责声明

- 本项目**完全开源免费**，仅供学习交流使用
- 严禁任何个人或组织将本项目用于商业用途或二次售卖
- 使用本软件所产生的一切后果由用户自行承担
- 请遵守所在学校的相关规定

---

## 📄 许可证

本项目基于 [Apache License 2.0](LICENSE) 协议开源。

