# UI108 色散合成本地验收

日期：2026-10-07。App 1.0.108 / 108，SDK 3.2.9、API 3。分支 `uipreview`。

## 代码修复

按课程页的采样顺序，成绩与插件中心将局部材质和强调色字形合成后进行单次折射、色散，删除独立透明文字透镜。保留实际文字排版、自定义字体、6%放大、按压/拖动色散、原有位置与尺寸。普通文字在光学帧和同步回退的同一胶囊区域内避让。说明见 [UI108-REFRACTION.md](UI108-REFRACTION.md)。

## 本地验证

| 测试类 | 实际通过数 |
| --- | ---: |
| SegmentRefractionSourceTest（API32/33 独立透明位图中的生产采样输入） | 2 |
| SegmentLensPlacementTest（API33 实际玻璃分支/触摸/尺寸变化） | 1 |
| SegmentLabelSourceTest（API32/33 实际文字排版与像素） | 2 |
| SegmentedForegroundTest（选中背景、标签位置与唯一语义） | 1 |
| SegmentMaterialSourceTest（当前位置材质，不采窗口底部） | 1 |
| GlassLensOpticsTest（静止/按压/速度及尺寸配方） | 13 |
| GlassLensCaptureGeometryTest（采样变换与失效） | 3 |
| GlassLensFallbackTest（API31 缺少有效帧的回退） | 1 |
| 合计 | **24** |

最终测试轮次 `ui108-refraction`：24通过，0失败、0错误、0跳过。生产 Kotlin 与 AndroidTest Kotlin 编译通过。遵守单 worker、1GiB Gradle 堆、384MiB 单测试进程配置；stdout、stderr 和退出码由独立持久任务保存。

初次定位轮次 `ui108-reproduce` 为1通过、1失败：API33位置断言通过；API32真实玻璃初始化因 Robolectric 没有 EGL handle（`EGL_NO_DISPLAY` 为null）失败，未运行到位置断言。该失败收据保留；最终不声称软件环境运行了 API32 的真实 EGL。API31回退测试使用惯有惰性 sentinel，仅验证未启动渲染器的回退路径。

最终原始 XML 与任务记录交付于 `local-verification.zip`。没有运行完整 APK 构建、设备或模拟器测试、真实学校接口。本次不涉及插件契约和工具链，没有重复执行 SDK/网站发布测试。

## 交付与待确认

App 提交 `[skip ci]`，推送 `uipreview` 并核对远端 SHA；准确提交及校验和见交付包 `verification.json` 和 `SHA256SUMS`。main、正式版本信息、107测试产物、SDK和网站保持原状；本轮没有触发新 CI/CD。

代码及采样输入回归完成。实际黑色碎片是否完全消除、色散过渡与清晰度，仍需华为API31、API32、Android17设备对照课程页确认，覆盖静止、按压、拖动反向、收起、浅深主题、复杂壁纸与自定义字体。软件 Canvas 和采样输入通过不能替代最终 GPU 验收。
