# 文档索引

本目录是 `zhengfang-apk` 的内部工程文档。**面向使用者的内容在根目录 [README.md](../README.md)（中文）与 [README.en.md](../README.en.md)（English）**；逐版本发布说明在 [release-notes/](../release-notes/)；变更日志在 [CHANGELOG.md](../CHANGELOG.md)。

当前版本：**App 1.0.116 / code116**，宿主 **SDK 3.6.0**，插件 **API 主版本 3**。版本号以 [app/version.properties](../app/version.properties) 为准。

## 状态标记

| 标记 | 含义 |
| --- | --- |
| **当前** | 描述当前实现或最近一轮，可直接引用 |
| **历史** | 记录某一版本的当时状态，文首一般有 `> **历史文档。** …` 标注，说明适用版本与后继文档 |
| **规范** | 约束性文件，改对应区域前必须先读 |

历史材料一律保留、不删除；汇总清单另见 [archive/README.md](../archive/README.md)。

## 建议阅读顺序

1. [../AGENTS.md](../AGENTS.md) — 仓库内的协作与安全约定（规范）
2. [../spec/PLUGIN-PLATFORM-RELEASE.md](../spec/PLUGIN-PLATFORM-RELEASE.md) — 改插件契约或发版前必读（规范）
3. 本索引 → 按主题进入下列文档
4. 需要精确行为时以代码与 [release-notes/](../release-notes/) 为准，文档只作背景

## 插件与协议

| 文档 | 状态 | 内容 |
| --- | --- | --- |
| [plugins/SITE-CONSENT-3.2.5.md](plugins/SITE-CONSENT-3.2.5.md) | 历史 | 学校站点授权、查询诊断与插件卸载（SDK 3.2.5 时期；隔离 UID 一项已订正） |
| [../spec/PLUGIN-PLATFORM-RELEASE.md](../spec/PLUGIN-PLATFORM-RELEASE.md) | 规范 | App 与插件平台协同发布的门槛与同步要求 |
| [../spec/PREVIEW112-RESTORATION.md](../spec/PREVIEW112-RESTORATION.md) | 当前 | 1.0.112-ui-preview 功能的恢复范围与比对方式 |
| [../distribution/README.md](../distribution/README.md) | 当前 | 下载分发、镜像、chunk-worker 与凭据说明 |

## 界面与材质

界面文档按「验收报告」（某个版本做了什么、验证到什么程度）与「组件说明」（结构如何）两类组织。

| 文档 | 状态 | 内容 |
| --- | --- | --- |
| [ui/UI109-STABILITY.md](ui/UI109-STABILITY.md) | 当前 | 拖动分段滑块后文字闪烁的修复（最新一轮材质实现） |
| [ui/UI109-ACCEPTANCE.md](ui/UI109-ACCEPTANCE.md) | 当前 | UI109 本地验收报告 |
| [ui/UI108-REFRACTION.md](ui/UI108-REFRACTION.md) | 当前 | 文字与局部玻璃底图先合成再统一折射的实现 |
| [ui/UI108-ACCEPTANCE.md](ui/UI108-ACCEPTANCE.md) | 当前 | UI108 色散合成本地验收 |
| [ui/UI107-COMPONENTS.md](ui/UI107-COMPONENTS.md) | 当前 | 文字透镜与通用网页容器 |
| [ui/UI107-ACCEPTANCE.md](ui/UI107-ACCEPTANCE.md) | 当前 | UI107 本地验收报告 |
| [ui/TOP-BAR-VISIBILITY.md](ui/TOP-BAR-VISIBILITY.md) | 当前 | 顶栏文字随玻璃透明度消失的排查与修复 |
| [ui/NOTIFICATION-SPLASH.md](ui/NOTIFICATION-SPLASH.md) | 当前 | 通知启动时启动图标错位 |
| [ui/UI106-COMPONENTS.md](ui/UI106-COMPONENTS.md) | 历史 | UI106 内部组件与状态说明 |
| [ui/UI106-ACCEPTANCE.md](ui/UI106-ACCEPTANCE.md) | 历史 | UI106 验收报告 |
| [ui/UI105-COMPONENTS.md](ui/UI105-COMPONENTS.md) | 历史 | UI105 浮层与插件浏览玻璃 |
| [ui/UI105-ACCEPTANCE.md](ui/UI105-ACCEPTANCE.md) | 历史 | UI105 验收报告 |
| [ui/UI104-COMPONENTS.md](ui/UI104-COMPONENTS.md) | 历史 | 收起浏览页面与搜索/筛选面板 |
| [ui/UI104-ACCEPTANCE.md](ui/UI104-ACCEPTANCE.md) | 历史 | UI104 验收与交付记录 |
| [ui/UI104-DESIGN-REVIEW.md](ui/UI104-DESIGN-REVIEW.md) | 历史 | M3E 与 Liquid Glass 设计评估 |
| [ui/UI104-BRANCHES.json](ui/UI104-BRANCHES.json) | 历史 | UI104 分支对照数据 |
| [ui/MATERIAL-HEADER-MASK.md](ui/MATERIAL-HEADER-MASK.md) | 历史 | 非玻璃顶栏遮罩修复（1.0.96 开发分支） |
| [ui/UI102-ACCEPTANCE.md](ui/UI102-ACCEPTANCE.md) | 历史 | 1.0.102 页面过渡、弹窗与成绩状态 |

## Issue 验收记录

| 文档 | 状态 | 内容 |
| --- | --- | --- |
| [ISSUES-41-49-ACCEPTANCE.md](ISSUES-41-49-ACCEPTANCE.md) | 历史 | #41–#49 的实施与验收（1.0.92 – 1.0.96 / SDK 3.2.5） |
| [ISSUES-50-51.md](ISSUES-50-51.md) | 历史 | #50–#51 启动兼容性与课表节数；含移除隔离 UID 的安全取舍 |
| [security/ISSUE-41-API32.md](security/ISSUE-41-API32.md) | 历史 | Issue #41 与 API 32 回归跟进（基线 1.0.91 / SDK 3.2.4） |
| [TEST97-ACCEPTANCE.md](TEST97-ACCEPTANCE.md) | 历史 | 1.0.97 测试版修复与验收 |

## 其它

| 文档 | 状态 | 内容 |
| --- | --- | --- |
| [images/liquid-glass/README.md](images/liquid-glass/README.md) | 当前 | README 界面素材的来源、设备与版本说明 |
| [superpowers/plans/2026-08-17-zhengfang-app-promo-video.md](superpowers/plans/2026-08-17-zhengfang-app-promo-video.md) | 历史 | 宣传片实施计划（工程不在本仓库内） |
| [superpowers/specs/2026-08-17-zhengfang-app-promo-video-design.md](superpowers/specs/2026-08-17-zhengfang-app-promo-video-design.md) | 历史 | 宣传片设计规格 |

## 相关目录

```text
../README.md            面向使用者的中文说明（English: ../README.en.md）
../CHANGELOG.md         1.0.68 起的完整变更记录
../release-notes/       逐版本发布说明（唯一数据源）
../archive/             归档材料索引
../spec/                规范与恢复记录
../distribution/        下载分发与镜像
```

## 维护约定

- 新增文档：放进对应子目录（`ui/`、`plugins/`、`security/`），并**在本索引补一行**，写明状态与内容。
- 文档被取代时：不要删除。在文首加 `> **历史文档。** …`，写明适用版本与后继文档，并把本索引中的状态改为「历史」。
- 新增或修改链接后，请确认相对路径从本文件所在目录出发仍然有效。
