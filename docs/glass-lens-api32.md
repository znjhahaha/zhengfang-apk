# API 31/32 折射（自实现 AGSL 替代）

> 面向：想知道「33 以下的液态玻璃是怎么做出来的」的人。
> 结论先说：**着色器是 kyant AGSL 的逐行移植，不是我写的算法；把它跑起来的那套离屏管线是这个项目自己写的。**

## 1. 问题

`RuntimeShader`（AGSL）是 API 33 的平台类，**没有兼容库**、没有 AndroidX 封装。
所以 API 31/32 上库的 `lens()` 内部第一行就是：

```kotlin
if (!isRuntimeShaderSupported()) return   // Lens.kt:22
```

静默 return —— 折射直接不存在。这两个版本有 `RenderEffect`（所以模糊、
描边、阴影都正常），唯独缺逐像素着色。

目标：在 31/32 上复现 33+ 的观感，且**不改变 33+ 的任何行为**。

## 2. 为什么是 OpenGL ES 2.0

| 方案 | 结论 |
| --- | --- |
| `RuntimeShader` | API 33+ 才有，无兼容库 |
| `RenderScript` | API 31 起废弃，且不适合逐帧 |
| `Canvas.drawBitmapMesh` | **试过，走不通**：网格只能位移顶点坐标，玻璃需要的是逐像素位移 + 逐通道色散，顶点位移表达不了 |
| **GLSL ES 2.0** | **自 API 8 就有**，能逐像素、能多次采样 |

所以走离屏 GL：EGL pbuffer 上下文 + FBO 渲染 + `glReadPixels` 回读成 Bitmap，
再由 Compose 的 `DrawModifierNode` 画到屏幕上。

## 3. 哪些是移植的，哪些是自己写的

这是本文档最该说清楚的一节。

### 3.1 移植（算法不是我的）

`GlassLensShader.kt` 的片元着色器是
`kyant/AndroidLiquidGlass` 里 `RoundedRectRefractionWithDispersionShaderString`
（`backdrop/.../internal/Shaders.kt:87`）的**逐行同式翻译**，AGSL → GLSL ES 1.00。
一一对应关系：

| 库 AGSL | 本项目 GLSL | 说明 |
| --- | --- | --- |
| `sdRoundedRect` | `sdRoundedRect` | 圆角矩形 SDF，同式 |
| `gradSdRoundedRect` | `gradSdRoundedRect` | SDF 解析梯度，同式 |
| `circleMap(x)` | `circleMap(x)` | `1 - sqrt(1 - x²)`，同式 |
| `d = circleMap(1 - -sd/h) * amount` | 同 | 位移量沿斜坡的分布曲线 |
| `gradRadius = min(radius*1.5, min(halfSize))` | 同 | 让胶囊两端方向不突变 |
| `dispersionIntensity = (cx*cy)/(hx*hy)` | `dispScale` | 色散的位置调制：四角最大、两中轴为 0 |
| 七次 `content.eval()` + 权重 | 七次 `texture2D()` + 同权重 | 红/橙/黄/绿/青/蓝/紫，权重表照抄 |
| `setFloatUniform("refractionAmount", -amount)` | `d` 取负 | 取负 + 朝外梯度 = 朝**内**采样 |

翻译时的必要改动，都是语言/平台差异，不是算法差异：

- `half4`/`float2` → `vec4`/`vec2`；`content.eval(coord)` → `texture2D(u_tex, uv)`。
- AGSL 的 `content` 是平台喂的输入图像，坐标是像素；GL 里得自己传纹理 +
  `u_srcOrigin`/`u_srcScale` 做归一化换算。
- 库支持四个角**不同**半径（`cornerRadii` 是 `float4` + `radiusAt()`）；
  这里只传一个 `u_radius`。App 里所有折射元素都是等角的（胶囊/等圆角矩形），
  四角不等的情况用不到。
- 库的 alpha 通道也参与色散累加（`color.a += red.a / 7.0`）；这里输出恒 `alpha=1`，
  形状外直接 `vec4(0.0)` 由 Compose 侧 clip 负责。
- 变量不能叫 `flat` —— GLSL ES 1.00 保留字，用了整条折射静默降级。

### 3.2 自己写的（库里没有对应物）

| 文件 | 行数 | 做什么 |
| --- | --- | --- |
| `GlassLensEngine.kt` | 190 | 进程级共享 EGL 上下文 / GL 线程 / program 编译 / uniform location 缓存 / 失败一次即整体停用 |
| `GlassLensRenderer.kt` | 433 | `GlassLensSource`（底图纹理，按区域共享）+ `GlassLensTarget`（FBO、回读缓冲、输出位图，按元素独占） |
| `GlassLens.kt` | 671 | `GlassLensAnchor` 锚点、`Modifier.glassLens` / `Modifier.glassLensAnchor`、Backdrop→Picture→Bitmap 快照、上一帧结果的绘制与本帧提交 |
| `GlassLensRegion.kt` | 108 | 「区域」抽象 + 两个 CompositionLocal（普通/模态预模糊） |
| `GlassLensFreshness.kt` | 59 | 底图何时重拍：滚动限频 + 停下补拍 |
| `GlassLensShader.kt` | 228 | 上面那段移植的着色器 + 为什么每一行是这样的说明 |

仪器测试在 `app/src/androidTest/.../glass/`：`GlassLensRendererTest` 验上线代码真的出
折射（含区域共享语义）；另三个是探路阶段的证据，分别记录 `Bitmap.createBitmap(Picture)`
可用、ES 2.0 离屏可跑复杂着色器、以及零回读通路**更慢所以放弃**。
| `PhysicalLensSurface.kt` 的 `glassLensOpticsFrom` | — | 把同一份 dp 配方换算成着色器要的像素量，与 33+ 共用 `lensScales` 曲线 |

这部分是本项目原创，因为库根本不需要它：AGSL 由平台喂输入、由平台合成输出，
不存在「上下文」「底图」「重拍」这些概念。

### 3.3 谁做的

代码是我（Claude）在会话里写的，方向、判据和几乎每一次纠错都来自使用者：
「不要影响 API33+」「外层有一个壳」「静止无折射」等等，都是他截图指出来的。
其中「静止无折射」提了三次我才去翻库源码，发现自己凭观感加的 `0.42` 折射下限
根本不是库的行为 —— 详见第 7 节。

## 4. 管线

```
Backdrop（壁纸 / 页面 / tint 文字）
  │  ① 只在内容变化时：Picture → 硬件位图 → ARGB_8888（约 5ms）
  ▼
GlassLensSource   一块底图纹理，同区域内所有元素共享
  │  ② 逐帧只改 uniform，不重传纹理
  ▼
GlassLensTarget   每元素独占 FBO；渲染 + glReadPixels（0.27ms 中位 @ Adreno 640）
  │
  ▼
Modifier.glassLens.draw()   画**上一帧**的结果，同时提交本帧
```

三个关键决定：

**画上一帧的结果。** 在 `draw()` 里同步等 GPU 会把 UI 线程钉住；折射差一帧看不出来。

**底图按区域共享，FBO 按元素独占。** 早期两者在同一个类里，一个锚点只服务一个元素，
所以没暴露。改成 App 级共享锚点后立刻炸：所有元素往同一个 FBO 提交、又都读同一张
`latest`，每个元素画出来的是**最后渲染完的那个元素**的输出拉伸到自己尺寸 ——
屏幕上是选择器胶囊里一条灰带，140 行像素逐行几乎相同（实测 163.7±0.3）。

**锚点必须挂在不动的祖先上。** 快照 5ms，不能跟着控件移动。所以：祖先调
`rememberGlassLensRegion` 并挂 `Modifier.glassLensAnchor`，控件从
`LocalGlassLensAnchor` 读，滑动时只改采样窗口 `u_srcOrigin`。
另外底图必须**比元素大**，否则边缘采样会被 CLAMP 成一圈涂抹。

## 5. 底图要复现「元素实际压着的东西」

AGSL 的输入由平台自动提供；GL 这边必须**手工重建同一张图**。这是整个移植里最容易错的地方。

规则：读调用点，看它的 `drawBackdrop(backdrop = X)` 里 X 是什么，以及 lens 之前
有没有别的 effect。

- 底栏指示器压着 `模糊壁纸(10dp) + 轨道容器色 + 锐利 tint 文字`，顺序与屏幕一致。
- 成绩页芯片压着 `combined(壁纸, 顶栏玻璃层)`，**没有** blur、**没有**轨道色。
- 弹窗/下拉面板走 `vibrancy → blur → lens`，输入是**模糊过的**背景，
  所以模糊必须**烤进底图**（`LocalGlassLensModalAnchor`）——
  屏幕上那层 blur 加在 `drawBackdrop` 建的图层里，而折射在它上游就画完了，
  那层 blur 没有输入可吃。

抄邻居的配方就会得到一张错的底图：分段控件那次抄了底栏的（blur + 轨道色），
滑块因此发灰发褐。

另有一条反直觉的：**不能 replay 轨道层**。轨道那层是 `shape = { Capsule() }`，
它的裁边正好落在指示器边缘上，底图里于是有一条沿指示器轮廓的半透明边界，
斜坡再把它放大 —— 那圈「灰罩」就是它，跟折射本身无关（已量过：罩在底图里就有）。

## 6. 底图何时重拍

底图里有会动的东西（页面内容随滚动移动），但重拍一次 5.1ms，不能每帧拍。

- 内容标识（选中项、主题、壁纸）变化 → 立刻重拍。
- 滚动期间 → 限频 100ms 一次（≈5% 开销）。
- 滚动停下 / 换页动画结束 → 补拍一次（用户真正盯着看的是静止态）。
- 静止 → 零开销。

试过并放弃：常驻 `HardwareRenderer` + `SurfaceTexture` 的零回读通路。
中位 4.69ms、p90 15.99ms，比现在**更差** —— `HardwareRenderer` 按 vsync 节拍走，
`syncAndDraw` 持续返回 status 8。不要再试。

## 7. 三个查了很久的坑

**位移被自己 clamp 到库的 1/3。** 曾有 `amount ≤ height × 0.5`，理由是「锐利采样源
经不起大位移」。整条推理都是错的：库的 `LiquidBottomTabs` 是 `lens(10dp, 14dp)`，
比值 1.4，位移**大于**斜坡是常态；而且库采的也是 `blur(8dp)` 过的 backdrop，
并没有更锐利的源。当时误判成「模糊底图没有高频内容可弯」，还在着色器里加了一层
伪造微纹理 —— 位移够大时，模糊底图里剩下的大尺度结构本身就足以显形。已删。

**着色器里多画了一份轮廓光。** 加过 `u_specular`/`u_rimStrength`/`u_ior` 等一整套
Snell + Fresnel + rim。库的折射着色器**只做位移 + 色散**，轮廓光交给 Compose 侧的
`Highlight`/`Shadow`/`InnerShadow`，而调用方已经挂了这三个 —— 着色器里再加一份就是
画两遍，表现为一圈过曝白壳，把色散的蓝黄边整个盖掉（使用者报的「外层有一个壳」）。
实测 Snell 双界面的残量约 1px，而位移项是 11px，它对观感的贡献本来就被完全盖住。

**自己发明的「静止折射下限」。** `NavIndicatorRefractionFloor` 曾是 `0.42`，
注释写着「静止保留轻折射，凸起镜片感」，并当成 33+ 的既有基线。翻库源码才发现
指示器是 `lens(10dp * pressProgress, 14dp * pressProgress)` —— 静止**恰好为 0**，
没有任何下限。这个错在 31/32 上后果更重：那条路采模糊底图，页面的彩色内容被
blur 摊成大色斑，静止折射把色斑沿胶囊边缘挤一圈，屏幕上就是指示器边上一条青蓝/粉
彩边。改成 `0f` 后实测 `thick=0.0 amount=0.0`（原 15.1 / 22.7），按下仍到
`amount=54.35 thick=36.17 ratio=1.503`。
注意库的**轨道**是无条件 `lens(24dp, 24dp)`，静止**是**折射的；只有指示器不折射。

## 8. 覆盖范围与开关

`glassLens` 的调用点：`CapsuleNavigationBar`（轨道 + 指示器）、`GlassChipSurface`、
`LiquidComponents`（按钮/圆钮）、`LiquidSelectionComponents`（分段控件、选择器）、
`SystemUi`（弹窗面板）。

门控：

```kotlin
fun isGlassLensApplicable(): Boolean   // 只在 31/32 为 true
```

33+ 走平台 AGSL，≤30 连 `RenderEffect` 都没有、只剩模糊。所有编辑都挂在
`isGlassLensApplicable()` 或 `lensAnchor != null` 上，因此 33+ 的路径不受影响
（验证方式：拿 HEAD 构建的 APK 与当前 APK 在 API 35 上逐像素比，期望零差异）。

**一个雷**：走折射路径时调用方的 `onDrawBackdrop` 会**让掉**正常的背景绘制
（背景由折射负责画）。所以折射一旦失效而组合期读不到这个变化，元素就永远没有背景 ——
屏幕上是整块面板消失（弹窗上实拍到过）。因此 `failed` 用 snapshot state 存，
失败会触发重组、连 blur 兜底一起切回去；锚点没挂到节点上时 `warnUnanchored()` 会报错。

## 9. 已知未完成

- API 31 没有真机验证过（只有 32 和 35）。
- 底栏指示器亮度比按算法预测值低约 6%（预测 222.7，实测 213）；
  疑点在底图重建与库自己的 `vibrancy()` 之间，以及着色器里 `vibrancy = 1.28`。
- 开关滑块（`LiquidSelectionComponents.kt`）仍是内联 `lens()` + 硬编码 5dp/10dp，
  没走 `resolvePhysicalLens`。
- 真机上的实际帧开销还没量（探针已随本版删除，量的时候要重新加回临时插桩）。

## 10. 参考

- 库源码：`AndroidLiquidGlass/backdrop/.../internal/Shaders.kt:87`（着色器）、
  `effects/Lens.kt`（参数与形状约束）、
  `app/.../components/LiquidBottomTabs.kt`（底栏的参数取值基准）。
- 本项目：`app/src/main/java/com/tyust/course/ui/system/glass/`。
- 单元测试：`app/src/test/.../glass/`（光学参数、圆角解析等 8 个文件）。
