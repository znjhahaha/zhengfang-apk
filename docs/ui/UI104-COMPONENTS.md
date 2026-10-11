# 收起浏览页面与搜索/筛选面板

> **历史文档。** 本文描述 1.0.104（uipreview / SDK 3.2.8）的插件中心收起浏览页面与搜索筛选面板，后续结构已被 [UI105 组件说明](UI105-COMPONENTS.md)、[UI106 组件说明](UI106-COMPONENTS.md)、[UI107 文字透镜与通用网页](UI107-COMPONENTS.md) 取代。当前正式版为 1.0.116、宿主 SDK 为 3.6.0。原文保留。

App 内部组件试点，入口：插件中心 → 更多 → 导入、回滚与开发工具 → 玻璃与收起页面预览。也可在开发构建中启动非导出 Activity `GlassBrowserPreviewActivity`。预览只使用本地模拟数据；不创建插件会话、不安装包、不查询学校。

## CollapsingGlassBrowser

调用方提供独立的 `LazyListState`、标题、分段列表、操作及扩展控件；业务内容通过 `LazyListScope` 追加。

```kotlin
val discovery = rememberLazyListState()
val installed = rememberLazyListState()
var tab by rememberSaveable { mutableIntStateOf(0) }
CollapsingGlassBrowser(
    title = "插件中心", subtitle = "学校教务与校园服务",
    state = if (tab == 0) discovery else installed,
    onBack = onBack, tabs = listOf("发现", "已安装"),
    selectedTab = tab, onTabChange = { tab = it },
    actions = { /* 搜索/筛选/更多；至少 48dp 点击目标 */ },
    expandedControls = { /* 共用状态的分类、学校与搜索 */ }
) {
    items(entries, key = { it.id }) { entry -> /* 业务卡片 */ }
}
```

- 大标题和扩展控件属于第0个 lazy item，随列表直接滚动；上滚收起，回到顶部展开。
- 两行工具栏固定：52dp 动作区、最小48dp 分段区、8dp 内边距；大字体增加分段高度。列表从工具栏下方开始，避免隐藏输入框穿过工具栏接收点击或焦点。
- 360×640dp 默认字体的收起列表应达到可用窗口高度的70%以上；宽度最多840dp，其他窗口按真实约束布局。不要给列表再加展开态的固定空白。
- 收起比例由首项偏移/首项高度派生，不建立第二份手势状态或 `animateFloatAsState` 跟踪手指。紧凑标题和背景随比例变化。
- `panelActive` 隐藏基础页面的辅助功能语义；overlay 接管触摸。面板关闭后恢复列表语义。
- 调用方保留目录加载、安装、刷新、筛选重置逻辑；组件没有网络/业务回调。旋转状态使用 Compose saveable，账号/提供者失效仍由业务层处理。
- 保留 `browser-expanded-controls` 内部 item key；业务 item key 不得与它相同。

## GlassSearchFilterPanel

`panel: BrowserPanel?` 由调用方保存，`Search`/`Filters` 共用同一 portal；关闭设为 null。搜索和筛选是两个纯内容 slot，直接读写同一业务状态，不能另建条件副本。学校选择等后继弹窗在 `onClosed` 中打开，避免两个模态层竞争。

- 沿用 `AnchoredGlassPortal` 的安全区、键盘避让、返回/外部点击关闭及同窗口锚点。
- 面板最大宽度400dp，并为末尾“更多”动作预留空间；按实际父窗口收窄，展开时触发按钮不横移。内容独立滚动。空间不足时压缩可见高度，保留内容滚动。
- 动作使用现有 `TopBarActionRail` 的48dp控件模式，避免普通动作组重定位时的位移反馈干扰锚点。
- 复用 `glassSheet` 的面板配方；工具栏使用 `HeaderGlassSlab`。既有下拉与通用对话框配方没有改动。
- 只采样兄弟背景层，文字与图标正常前景绘制一次；市场分段控件明确 `refractLabels=false`。
- 局部空间 spring（0.9 / 700）驱动尺寸、宽度形变与圆角；效果 spring（1 / 1600）驱动 alpha。它们是本项目参数，**不冒充官方 M3E token**。当前 Material3 1.4.0 MotionScheme 为 internal，故使用公共 Compose animation primitive；不抑制可见性检查、不反射。
- 收起期间移除面板语义并阻止输入；清除文本焦点。减少动态效果直接终态。搜索↔筛选和快速反向复用同一 spring 状态。

## 预览与验收

预览提供长列表（发现100项/安装25项）、空列表、加载中、错误重试、模拟详情返回；可切深浅主题、玻璃背景、减少面板动效、大字体180%。宽度由窗口决定，可旋转/调整窗口查看。预览选项仅在当前预览保存，不写全局外观设置。

JVM 的 `GlassBrowserTest` 验证尺寸、可见性、恢复、列表/面板行为；光学源故意为空，避免把软件Canvas当作GPU。真实键盘、TalkBack与设备渲染仍需验收。不要在模拟卡片上加入真实安装/网络调用作为“方便测试”。

## 插件现在可以使用什么

SDK 3.2.8 的原生插件通过 `UiNode` 使用宿主组件。已有的 `button` 映射到 App 的 `LiquidButton`；布局节点可使用 `surface: 'glass'`。例如下列视图声明合法，事件仍由插件处理：

```typescript
const view: UiNode = {
  id: 'tools', type: 'column', surface: 'glass', padding: 16,
  children: [
    { id: 'title', type: 'text', style: 'title', text: '我的工具' },
    { id: 'refresh', type: 'button', label: '刷新', event: 'refresh' }
  ]
};
```

`CollapsingGlassBrowser` 与 `GlassSearchFilterPanel` 是此次新增的 **App 内部 Kotlin 组件**，尚未变成 `UiNode` 类型；声明不存在的 `type: 'collapsingBrowser'` 会被协议校验拒绝。已有插件升级宿主后，也不会自动获得整页收起布局。网页插件的页面内容仍由自己的 HTML/CSS 控制。

如后续开放，需要一起扩展 host-api 节点/能力、宿主渲染器、SDK 类型和校验、最低 App 版本/能力协商、审核与 Wiki 示例，并提供旧宿主回退。本轮 SDK/API 均未变化，不发布尚未实现的能力声明。
