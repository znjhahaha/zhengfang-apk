# UI106 验收报告

日期：2026-10-06。开发版本 **1.0.106 / code 106**，分支 **uipreview**，基线 `07f1399a65697ea0fb8afa6973e547d30eb027fb`。SDK **3.2.8**、API **3** 不变。

## 代码已修复

- 分段滑块移除新增蓝色叠层，前景文字保持单份绘制。API31/32 与 API33+ 共同采样局部磨砂材质；位置、尺寸及来源版本改变后拒绝旧帧，等待和失败保留中性选中底色与轮廓。
- 搜索、筛选、更多使用单一浮层。分别测量真实按钮锚点；正文最终宽高与外层开合动画分离；覆盖层内部直接观察当前面板状态，修复交互状态已切换而正文仍保留旧筛选内容的问题。
- 顶栏空白区拦截采用兄弟节点，避免取消按钮触摸。关闭期间阻止穿透点击；菜单共用课表样式，接受的操作关闭后执行一次。后继学校选择不会在用户已切换面板后突然打开。
- 课表加载问题采用明确类型。已有缓存（包括有效空课表）时，自动发现登录过期静默；手动同步复用统一重新登录提示。已知过期不反复查询；无缓存显示原因与入口；普通失败提示可关闭且避开底栏。缓存、自建课程和浏览位置保留。
- 插件首页保留条件摘要，分类、学校和搜索移入面板。分类使用轻强调玻璃胶囊；卡片说明最多两行、能力先显示两项与剩余数量，未安装项也可查看完整介绍。保留版本、范围、安装状态和详情中的审核说明。
- 开发者组件预览同步更新；103 下拉材质、自定义字体和成绩学期记忆保持。无契约、数据库或 SDK 迁移。

## 本地已验证

最终作业 **ui106-final-2：84 项，84 通过，0 失败，0 错误，0 跳过**；实际耗时 0:09:31.079232。包含最终生产代码和测试代码的必要编译。

| 测试类 | 实际测试数 | 失败 | 跳过 |
| --- | ---: | ---: | ---: |
| ScheduleCacheStoreTest | 10 | 0 | 0 |
| ScheduleLoadIssueTest | 3 | 0 | 0 |
| ScheduleRefreshCoordinatorTest | 4 | 0 | 0 |
| GradeBrowseStateTest | 7 | 0 | 0 |
| BrowserFirstTapTest | 4 | 0 | 0 |
| GlassBrowserTest | 10 | 0 | 0 |
| GlassFilterCapsuleTest | 2 | 0 | 0 |
| GlassOverlayLifecycleTest | 7 | 0 | 0 |
| PickerRowRevealTest | 3 | 0 | 0 |
| PickerVisibilityTest | 2 | 0 | 0 |
| ScheduleFeedbackUiTest | 4 | 0 | 0 |
| SegmentedForegroundTest | 1 | 0 | 0 |
| SessionNoticeStateTest | 6 | 0 | 0 |
| SystemDialogButtonTest | 6 | 0 | 0 |
| GlassLensCaptureGeometryTest | 3 | 0 | 0 |
| GlassLensFallbackTest | 1 | 0 | 0 |
| SegmentMaterialSourceTest | 1 | 0 | 0 |
| SessionRecoveryCoordinatorTest | 10 | 0 | 0 |

新增回归使用生产浏览器/按钮结构和真实触摸序列。动画逐帧推进后等待 Android 布局，不通过第二次点击或额外业务状态修改帮助面板展开。测试包含真实 LayerBackdrop 归属，验证浮层正文不在被采样页面内。

局部底图测试在绿色区域与窗口下部红色标记之间移动并返回，校验位置和材质像素。该测试使用软件 Canvas；它不证明设备 GPU 的 blur/refraction 效果。原有 API31 锚点回退测试只使用惰性 EGL 哨兵，不运行设备 EGL。

窗口/状态回归包含 360、411、600、840 dp，短窗口、180% 字体、主题/玻璃开关、反向滚动、详情返回和重建。课表覆盖缓存、有效空表、过期、普通错误、恢复入口、会话替换和迟到结果；成绩偏好及旧选择器回归通过。

## 过程记录与资源约束

全部重任务串行运行于受限 Gradle runner，stdout、stderr、退出状态及 JUnit XML 已保存。最终使用 1 GiB Gradle 堆、单 worker、Kotlin in-process；测试每类回收进程，实际子进程参数为 384 MiB 堆、256 MiB 元空间。

早期发现 Android unitTests.all 会覆盖初始小堆设置，最终附加 init 在 projectsEvaluated 后强制小堆。首次合并测试还出现多 SDK 元空间不足，改为每类一个进程解决。未停止其他服务。

调试过程中发现正文宽度随无界测量缩窄，以及覆盖层持有旧面板/动画值；新增测试捕获问题后修正。临时语义树输出保留在调试证据，最终测试移除输出并保留操作序列断言。首次编译的缺少导入、失败测试和后续结果全部保留，没有以跳过测试掩盖问题。

## 尚未发布与待设备确认

- 本轮只在 uipreview 提交并推送；最终本地/远端 SHA 见交付包 verification.json。正式 main 保持 `bbfa3d1803baa0a7be7015b43ec0fda719269519`。
- 未触发 CI/CD、合并 main、生成 APK、覆盖105测试包、同步 Gitee、部署网站或发布正式版本。
- 插件/SDK/网站仓库保持 `95d2bdf5707879bbedb589df06228addf4b87f05`，本轮无需重建 SDK 开发套件。
- 华为 API31、API32 和 Android17 的真实玻璃、GPU 性能、实际键盘和 TalkBack 仍待设备验收；没有设备/模拟器测试，不宣称已验证此前原生崩溃的所有原因。
- 没有访问真实学校接口。交付目录为工作区 `delivery/ui106-20261006/`，包含源码、基线补丁、组件说明、完整本地证据和 SHA-256。
