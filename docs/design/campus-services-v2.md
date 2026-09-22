# 校园服务 API v2

API v2 保留 API v1 的 configuration、extension、independent 教务适配，新增 `kind: service`。学校适配仍负责原有教务能力；校园服务可提供第二课堂、活动或其他独立平台的登录和原生页面。现有玻璃组件与 App 导航继续由宿主维护。

## 会话与权限

`ServicePluginSession` 使用独立的 `AcademicSession`、Cookie 和状态空间。服务账号范围由当前学校账号、插件 ID、服务用户名共同确定，不接收当前教务密码或 Cookie。password 模式完整实现 auth 四方法并支持验证码；none 模式不声明 auth。校园服务暂不接入 webLogin，教务插件原有网页登录仍可用。

每次宿主调用检查会话代次、当前账号范围和插件启用状态。退出会退休旧会话；免登录服务可重新建立匿名会话。更新后正在使用的包版本保持固定，停用或切换账号则撤销后续调用。

网络请求仍按 origin、端口、路径、方法、用途和必要参数逐跳校验。服务允许 Bearer 认证头；一旦重定向改变 scheme、host 或 port，即使目标也在网络白名单中也会拒绝，目标服务器不会收到请求。查询不能借用认证或写入权限。

服务 action 分 query 与 mutation。mutation 必须在清单中声明确认文案，由 App 展示确认后执行，单次操作最多发送一个写入请求。已发送请求后遇到网络中断、进程退出或不确定响应，返回结果未知，禁止自动重放。

## 原生页面

`service.page` 返回声明页面的 `pageId`、标题和 blocks；`service.action` 返回操作结果，可附带下一份页面。入口、页面、操作和外部网址都需提前声明。链接参数最多 20 项，每项只接受有界字符串。

支持 profile、metrics、progress、list、notice、actions、form 七类模块。`ServicePageRenderer` 使用原生组件渲染，插件不能提供任意 HTML、CSS、Android 代码或改写全局导航。表单字段与模块 ID 必须唯一，选择项和默认值必须匹配；未声明的页面、操作和网址会被拒绝。

`ServicePageLayout` 按学校账号、插件和页面保存模块顺序与隐藏 ID，不保存页面数据。用户可移动、隐藏和恢复模块，插件新增模块默认显示。`CampusServiceCenterActivity` 根据精确 schoolIds 或 academicHosts 列出当前学校适用服务，开发预览单独进入。

## SDK 与正式目录

契约真源仍是 `academic-plugin-api/`，版本 2.0.0。构建并导出 TypeScript 类型、Schema、共享宿主 SDK、测试向量与摘要锁到插件仓库 `sdk/`，再导出虚构校园服务设备测试资产。CLI 在无真实网络的 QuickJS WASM 中校验能力、权限和 fixtures。

五个模板分别为 configuration、extend-zf、mock-school、campus-service、grade-details。校园服务模板覆盖七类页面和演示写入；成绩明细模板针对 #13 的公开结构，私有协议参数只保留于会话 state。模板测试通过不代表真实学校验收。

正式目录为 `https://plugins.hidisiwa.xyz/academic-plugins/catalog.json`，App 内置 `campus-production-2026-09` 公钥。目录及包均需 ECDSA P-256/SHA-256 验签；正式目录仅纳入已验收学校，不从远端临时信任新公钥。网站管理密钥与离线签名私钥互相独立。

网站提供使用说明、API/AI 开发文档、工具包、反馈回执和源码审核。源码上传不会自动执行或发布；审核状态变为发布前，正式签名目录必须包含对应 ID 与版本。源码归档只收清单、src、fixtures、README、LICENSE，拒绝顶层和子项符号链接及特殊文件，压缩包不超过 512 KiB。

## 验收边界

保留山东农业大学、广州松田职业学院、三亚学院此前真实账号查询的证据。三亚学院 #24 按实际按钮绑定选择 Base64 登录协议，残留的 login()/flag=sess 脚本不再误导协议判断。黑龙江工程学院连接关闭/重置，未开放下载。华南师范大学成绩明细和第二课堂仍为样本模板，未声称真实平台可用。

用户于 2026-09-21 要求迁回 Windows；VPS 源码和验收证据已同步至独立工作区，远端开发已停止。Android 构建、JVM 与 MuMu 虚构数据验收在本地完成，网站保留 VPS 浏览器验收并补充生产只读检查。实际计数和未执行项见 [本地验收记录](../testing/2026-09-21-campus-plugins-v2-local.md)；真实学校写入不在此次验收范围。
