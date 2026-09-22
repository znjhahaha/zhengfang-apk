# 教务插件 v1 本地验收

> 这是 API v1 阶段的 Windows 历史验收记录，不代表后续 API v2 修改已通过同一轮测试。校园服务、正式网站及迁回本地后的验收另见 [API v2 本地验收记录](2026-09-21-campus-plugins-v2-local.md)。

基线：`17ada56634b4ce89b88c3e8e3978c2ae0a961692`。工作目录：`D:\zfapk-plugins-v1`，分支：`codex/academic-plugins-v1`。原工作区的独立玻璃性能改动未纳入本分支。

## 交付范围

- App：隔离 QuickJS 运行时、宿主 SDK、插件包管理、提供者接入、学期与日历兼容、设置与调试界面。
- 本地插件仓库：`D:\zhengfang-plugins`，独立 Git 仓库；含 SDK、CLI、三个模板和实例、样本、签名及上传脚本、中文文档。本地提交为 `42485c7740bfe662d633e6dd17d3654959b81ad4`，未配置远程仓库。
- 支持本地导入，以及配置目录后的浏览、下载、验签、安装和更新。正式线上目录与公钥未接入，不能声称已支持生产环境在线获取。
- 新输出文件统一为 `.eduplugin`，旧 `.zfplugin` 保持兼容；界面统一使用“教务适配包”。
- 不创建版本标签，不发布 APK，不启用线上分发或工作流模板。

## 底栏

动画保持 480ms，结束姿态和首帧一致，末速度归零。切换时保留当前运动轨迹，重复点击只合并一次待播放反馈；静止后停止帧调度。柱形图改变几何顶边而不缩放描边，标签采用固定布局的常规／加粗渐变。

`NavigationIconMotionTest` 覆盖曲线端点、导数、运动边界与柱形图速度连续性。API 32／35 的 `NavigationIconPlaybackDeviceTest` 覆盖中断、50 次重复点击、减少动态效果与静止状态，并输出浅深色五个图标 90%～100% 的末段画面。已查看联系图，首末帧逐像素一致，未见轮廓跳变或闪色。

## 自动验证

| 验证 | 结果 | 本地证据 |
| --- | --- | --- |
| 全量 JVM | 489 项，0 失败、0 错误、4 项在线测试跳过 | `app/build/test-results/testDebugUnitTest/`，`plugin-final-source-check.log` |
| API 24 QuickJS | 6 项通过 | `plugin-final-api24.log` |
| API 32 运行时、插件流程、目录与导航 | 15 项通过 | `plugin-final-api32.log` |
| API 35 运行时、插件流程、目录与导航 | 15 项通过 | `plugin-final-api35.log` |
| 最终独立 Lint | 0 errors、409 warnings、11 hints，构建成功 | `plugin-final-lint.log`，`app/build/reports/lint-results-debug.html` |
| 最终 Debug / uiPreview / 设备测试 APK | 构建成功 | `plugin-name-build.log` |
| Node / QuickJS WASM | 5 组通过 | 插件仓库 `.local/final-node-tests.log` |
| 三模板全新目录 | 创建、检查、样本、打包及提交检查通过 | `build/plugin-quickstart-20260920/quickstart.log` |
| Git 克隆后的快速开始 | `npm ci` 及全部 quick-start 命令通过，输出 `.eduplugin` | `build/plugin-edu-quickstart.log` |
| 本地签名发布准备 | 生成 `.eduplugin` 签名包、目录、entries、公钥，目录验签通过 | 插件仓库 `dist/release/` |

API 24 的进程强制退出测试因系统没有相应 shell 命令而过滤；该项在 API 32／35 执行。API 24 仍实际验证死循环、内存超限、取消、大响应、网络等待预算、跳转边界和加密向量。

设备覆盖：验证码获取/刷新/错误重试、网页完成地址限制、更新期间登录版本锁定、不透明学期、完整分页、成绩明细、校历、模拟选退课、账号隔离、失效会话拒绝。包与更新覆盖 Node→Android 跨端签名、坏签名、ZIP 路径及大小限制、摘要校验、下载中断/取消和回滚。强制退出隔离进程后，查询返回进程退出，已发出的写入返回结果未知，服务可再次调用且没有重放请求。

JVM 新增日历用例验证插件更新可刷新自动值，用户保存（包括保存相同日期）后不再覆盖，并保留旧设置迁移及账号隔离。加密测试使用 Android/WASM 同一份 SHA-256、MD5、HMAC、AES、RSA 与中文编码向量。

## 界面验收

插件管理使用现有 `GlassPageScaffold`、`InsetGroupedSection/Row`、`LiquidButton` 与 `LiquidSwitch`。当前学校、获取入口、已安装列表、详情和开发者工具分组呈现；接口标识仅在开发调试中显示。正式目录未启用的状态明确显示。

目录界面已验证全新安装：点击获取、下载验签、安装并显示已安装状态。玻璃布局修改后，API 32／35 各重新通过 5 项插件流程与界面测试，见 `plugin-glass-api32.log`、`plugin-glass-api35.log`。最后统一“教务适配包”文案后，API 35 再次通过 2 项 UI 测试，见 `plugin-edu-ui-api35.log`。

最新 API 35 截图位于 `build/plugin-glass-api35/plugin-ui/`；API 32 玻璃布局截图位于 `build/plugin-glass-api32/`。对比图 `build/plugin-glass-preview.png` 使用最新 API 35 的浅色普通模式与深色开发者模式，分别展示关闭和开启的开关状态。App 按包内容导入，不按后缀筛选；设备测试仍使用 `.zfplugin` 资产验证旧格式兼容。

## 构建及兼容说明

Kotlin／Compose 插件 2.4.10、协程 1.11.0、QuickJS 1.0.15。现有 AGP 8.13.2 的内置 R8/Lint 不支持新的 Kotlin 元数据，因此固定 R8 9.4.24、Lint 32.4.1（AGP 属性使用 9.4.1）。Windows AIDL 命令注释中的反斜杠-u 会被 javac 当作 Unicode 转义，仅规范化该生成注释。

Debug、uiPreview 与 benchmark 包已确认同时包含 `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64` 的 `libquickjs.so`。设备运行使用 x86_64；其他 ABI 只验打包内容。最终 APK 的 SHA-256、大小、ABI 和测试计数保存在 `build/plugin-final-evidence.json`。

多变体初次编译遇到 Kotlin 2 GiB 堆不足，已调整编译内存并顺序构建。后续组合构建在 Lint 阶段发生 Gradle daemon 消失，这些轮次未计为整轮成功。最终 APK 构建成功，独立 Lint 使用全新 JVM、4 GiB 堆和单 worker，5 分 14 秒完成；未发现 Lint error，仍有上述 warnings 和 hints。R8 仪器测试需保留跨 APK 调用的公开教务接口与 MockWebServer 共享依赖，相关规则仅用于 benchmark，不进入 release。

R8 运行复核：API 35 的 11 项运行时、插件流程与更新测试通过（`plugin-minified-api35.log`）；API 24 的 6 项运行时测试通过（`plugin-minified-api24.log`）。benchmark 产物已包含全部逻辑与玻璃布局改动，仅未包含最后一处隐藏文件后缀的说明文案调整；最新 Debug、uiPreview 和 API 35 UI 测试已覆盖该文案。

## 保留限制

南京工程学院账号在线验收仍受此前凭据拒绝限制，本轮不重试该账号。真实学校选退课未执行；使用虚构学校和本地服务验收。原有旧配置兼容界面保留，四种原生协议通过新提供者包装接入。正式 Gitee 上传脚本与工作流模板未执行，学校适配申请仍保持暂停。
