# UI104：M3E 与 Liquid Glass 设计评估

评估基线：App main `bbfa3d1803baa0a7be7015b43ec0fda719269519`（1.0.103）。
实施分支：`uipreview`，开发版本 1.0.104 / 104。SDK 3.2.8、API 3 不变。
这是源码和局部布局验证，不是 Google/Apple 官方认证或全 App 视觉改版。

## 结论

当前 App 是 Material 3 基础上的自定义玻璃界面。它已采用有意义的层级、连续手势反馈、可打断的弹簧，以及减少动态效果与高对比度回退；不能据此称为完整 Material 3 Expressive（M3E）实现。M3E 不是“全部动效都增加弹性”，Apple 的 Liquid Glass 也不是“所有内容都透明”。本轮保留视觉风格，优先改善内容面积、稳定前景与控件可达性，不给无依据的合规分数。

## 全 App 源码评估

| 范围 / 证据 | 已具备的设计原则 | 偏差或需要注意的点 | 本轮 / 后续 |
|---|---|---|---|
| 主题 `Theme.kt`、`MotionTokens.kt` | MaterialTheme、语义配色和圆角，按主题分前景 | 多套自定义曲线/弹簧；`Standard(0.4,0,0.2,1)` 的注释不能证明它是 M3E。Material3 1.4.0 的 MotionScheme 是 Kotlin internal | 本轮不全局换主题或依赖；局部用公共 Compose spring 分空间/效果。后续依赖升级后再迁移公开 MotionScheme |
| 主导航 `NavigationMotion.kt` | 目标页面优先；打断时保留当前快照，最多保留两页；隐藏页去除语义；减少动态效果有独立路径 | 自定义方向/比例/位移需设备上核对眩晕、触控延迟和返回手势；不是系统 M3E 组件自动提供 | 保留，必要的实际流畅度验收待设备 |
| 初次加载 `InitialPageLoad.kt`、`ModuleMotion.kt` | 骨架与真实内容交接、缓存直接展示、失败退出骨架 | 多个局部入场叠加仍可能形成较长视觉等待；ModuleMotion 最长约 605ms，不能当作网络请求耗时 | 本轮新市场不追加逐行入场；其他页节奏统一为可选后续 |
| 课表/成绩 `MeasuredGradesHeader.kt`、`GradesScreen.kt` | 收起由真实滚动几何驱动，固定内容 inset 防止双倍滚动；文字退出折射层 | 特定 API 的 GPU 采样仍需真机，不由 JVM Canvas 保证 | 用作市场的直接操作原则；不改已有收起参数和学期胶囊 |
| 插件市场 `PluginCenterActivity.kt` | 发现/安装独立状态，授权与安装动作明确 | 基线把多组筛选固定在列表外，360dp 短屏可用区域过少，属于必要易用性修复 | 本轮已移至同一 lazy 列表，留下两行紧凑操作/分段栏；面板复用筛选状态 |
| 通用弹窗 `DialogHost.kt`、`SystemDialogButton` | 正文与光学背景分层、实色主次/危险按钮；长文本滚动和关闭语义 | API32/17 自定义玻璃实际合成仍需验收；高对比度须保持文字/按钮明确 | 保留102弹窗修复；本轮不改变 .78/.84/.96 |
| 下拉选择器 `LiquidSelectionComponents.kt`、`PickerRowReveal` | 有界首屏渐入，后续行直接清晰；最大高度/滚动；保存选中语义 | 其他仍用 refractLabels=true 的分段栏可作为后续检查对象，不能一概宣称存在缺陷 | 保留103下拉 .18/.22/.96 和宽度；市场使用 refractLabels=false |
| 按压反馈 `GlassPressIndication`、`LiquidActionGroup` | 动作组局部融合，按压不改变布局位置；减少动态效果停用融合 | 多重 spring/折射叠加可能过度强调，帧率需设备检查 | 复用动作组，不重新造渲染器；空间 spring 与效果 spring 局部分离 |
| 菜单 `SystemActionMenu`、`AnchoredGlassPortal` | 同窗口源与目标、空间约束、返回键、收起后再执行下一动作 | 新浮动面板必须限制在 IME 安全空间，退出期间不能留下可交互控件 | 复用 portal，不动原菜单材质与动作逻辑 |
| 窗口 `ScreenMetrics.kt`、设置/表单 | 基于真实窗口、600/840dp 档及内容宽度上限；较大触控区 | 不能用固定屏幕快照保证所有字体/IME/多窗口；全 App 双栏尚未实现 | 市场 840dp 上限；新预览支持大字体/短窗口验证；双栏可选后续 |
| 插件网页/原生页面 | 宿主和插件有清楚的界面/能力边界 | 任意第三方网页不受 App 动效系统控制；不能把宿主评估等同全部插件认证 | 插件契约/SDK 不改，不强迫插件采用玻璃或 M3E |

## Apple 值得借鉴的部分

1. **控件与内容分层**：导航/操作可以用玻璃，长列表正文保持清晰；新列表文字、图标不进入采样快照。固定工具栏只采样壁纸，列表在工具栏下方裁剪，避免隐藏筛选仍能点击或采样自身。
2. **滚动边缘与阅读面积**：用标题/筛选随内容上移表达层级。市场采用固定的小型两行工具栏和自然滚动的扩展区，避免布局 padding 随收起二次变化。它不是对 iOS scroll-edge 模糊算法的复制。
3. **共享玻璃容器和形变连续性**：现有 GlassWindowHost、Backdrop 和 LiquidActionGroup 已有共享源和局部融合。新面板复用它们，快速反向从当前 spring 状态继续，不叠加多个独立窗口/光学捕获。
4. **克制的效果**：文字不弹跳、不色散；颜色/透明度用不回弹的效果 spring；位置/尺寸/圆角使用空间 spring。减少动态效果时直接到终态。高对比度与关闭玻璃继续采用已有实色回退。

SwiftUI 的 `GlassEffectContainer` / `glassEffectID` 不是 Android 可用 API。本轮借鉴交互原则，没有引入第三方仿制库、私有 API 或额外 SDK 能力。

## 必要修复与可选改进

- 必要：市场列表面积、操作可达、独立前景、面板状态保持、关闭后清理交互、旋转恢复；本轮实现并进行局部测试。
- 发布前必要设备检查：API32/Android17 实际玻璃、触控延迟、TalkBack 和真实 IME；JVM 布局结果不能替代这些。
- 可选后续：公开 MotionScheme 的依赖迁移、统一全 App 动效命名和节奏、逐页评估其他折射标签、真正的平板双栏与折叠设备支持。本轮不连带重做这些页面。

## 官方参考

查阅日期：2026-10-05。优先以当前锁定依赖的实际编译可用性为准。

- [Material 3 in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3)
- [MotionScheme](https://developer.android.com/reference/kotlin/androidx/compose/material3/MotionScheme)：spatial 改变位置/尺寸/形状，effects 改变 alpha/color。
- [Compose accessibility defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults)：最小触控目标、语义、内容描述。
- [Apple: Adopting Liquid Glass](https://developer.apple.com/documentation/technologyoverviews/adopting-liquid-glass)
- [Apple: Applying Liquid Glass to custom views](https://developer.apple.com/documentation/swiftui/applying-liquid-glass-to-custom-views)
