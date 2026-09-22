# 教务插件 v1

> 本文记录 API v1 阶段设计。API v2 校园服务和正式签名目录的后续接入见 [校园服务 API v2](campus-services-v2.md)。

## 提供者和兼容

`AcademicGatewayFactory` 为登录、学习数据、选课和队列提供统一的创建入口。学校的 `academicProvider` 可绑定已安装插件或内置实现；未绑定的学校保留 `academicSystem` 和已有学校、账号 ID。原有兼容界面继续服务未启用新适配器的旧配置，四类原生协议通过 `BuiltinAcademicProvider` 接入。学校专用密码登录保留原实现，插件覆盖认证组时优先采用插件。

三类包分别是配置型、继承内置适配器的扩展型和完全独立型。认证与选课组必须完整覆盖；学习数据可按方法覆盖。插件不能继承其他插件。页面根据能力隐藏不可用操作，空结果、未开放和未适配使用不同状态。

原生提交令牌由内置提供者私有保存，通用桥接只持有不透明快照引用。课程、教学班、已选记录保持各自稳定 ID 和教学班身份已知标记。插件通过受限 `state` 保存临时令牌，公开模型不传递协议参数。旧原生并发策略保持不变，JS 提供者按账号串行调用，继续使用公平轮询与未知结果核查。

学期 `id` 不再从年份和序号推导；年份、序号、排序、起止日期和 `nextId` 独立保存。缓存保存完整元数据并兼容旧键。课表仍限制为 25 周。插件日历仅更新由插件管理的日期和节次，用户保存后转为手动值，之后不再覆盖。

## 运行边界

QuickJS 位于 `exported=false`、`isolatedProcess=true` 的服务中。应用在账号、主题和提醒初始化前判断隔离 UID。JS 没有 Java、文件、网络和 Android 模块；SDK 通过 AIDL 代理，载荷走有界文件描述符管道。

每次调用绑定学校、账号、会话代次、提供者版本及操作 ID。默认上限：64 MiB JS 内存、1 MiB 栈、5 秒执行预算、120 秒墙钟预算、5 MiB HTTP 响应。网络等待不占 JS 执行预算。取消和进程退出使旧结果失效，宿主取消相关请求。已发送写入后发生中断返回结果未知，禁止自动重放。

网络规则精确校验 origin、端口、路径边界、方法、用途和必要参数，并逐跳重新校验。查询不能借认证或 mutation 权限写入；单次已确认选退课最多发出一个 mutation 请求。Cookie 只由宿主会话管理。state/storage 按学校、账号、提供者与开发环境隔离，各上限 256 KiB；日志只保留 origin、方法、用途、状态等元数据。

网页登录由 App 打开插件声明的认证地址，用户手工完成。返回地址必须匹配声明的完成页，随后通过 `auth.resume` 续接。登录及执行中的队列固定原包版本，更新只影响以后创建的适配器。

## 包与目录

App 契约源为 `academic-plugin-api/`。运行 `npm ci && npm run build` 后，通过 `node tools/export-plugin-api.mjs D:\zhengfang-plugins` 导出 SDK、Schema、共享 HTML 实现、加密测试向量和摘要锁。贡献者的 CLI 不依赖应用源码位置。

`.eduplugin` 是受限 ZIP（旧 `.zfplugin` 文件保持可导入）：仅允许清单、JS 入口和签名文件；压缩上限 2 MiB，展开上限 8 MiB。导入验证清单、全部文件摘要、签名和真实能力导出，加载检查不能发请求或写存储。官方包和目录使用 ECDSA P-256/SHA-256，JSON 按递归键排序及统一字符串转义生成签名输入。

安装后按包摘要不可变保存，再原子切换 active/previous 索引。写入在宿主进程统一串行化。下载中断、取消、坏签名或能力检查失败都不切换版本。回滚只更换以后使用的包；已持有的版本继续存在。

本轮没有配置正式目录和正式公钥。Debug 的开发模式允许回环目录和测试公钥；普通包仍需验签，未签名包必须显式启用开发导入。测试签名私钥只在本地 `.local/`，不进源码或 APK。`crypto-vectors.json` 中的 RSA 私钥是公开的随机测试向量，与包签名无关，只进入测试资产。

## 本地验证

- `:app:testDebugUnitTest --rerun-tasks`：原生回归、插件边界、包校验、学期和日历。
- `:app:assembleDebug :app:assembleUiPreview :app:assembleUiPreviewAndroidTest -PuiTestBuildType=uiPreview`：应用与设备测试。
- `:app:assembleBenchmark :app:assembleBenchmarkAndroidTest -PuiTestBuildType=benchmark`：R8 后的运行时测试。
- `:app:lintDebug`：静态分析。Kotlin 2.4 需要单独更新 R8 与 Lint；版本固定在 Gradle 配置中。
- 插件仓库 `npm test` 和 `submit-check`：QuickJS WASM、三类模板、共享加密与 HTML 向量。

设备测试使用虚构学校和本地服务，不执行真实学校选退课。过程和结果见 `docs/testing/2026-09-20-academic-plugins-v1.md`。
