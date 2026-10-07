# UI109 本地验收报告

日期：2026-10-07。开发版本1.0.109/code109，SDK3.2.9、API3，`uipreview` 分支。原因与实现见 [UI109-STABILITY.md](UI109-STABILITY.md)。

## 复现与修复

`ui109-reproduce` 在未修改的生产代码上执行4项：原有2项通过，新增颜色动画回归在API32/33各失败1项。断言确认段落身份未变，但折射字形版本发生了错误增长。失败结果保留，未计入最终通过数。

修复后 `ui109-stability` 全部38项通过，0失败、0错误、0跳过：

| 范围 | 测试类 | 数量 |
| --- | --- | ---: |
| 颜色、坐标噪声、移动、字体与布局 | SegmentLabelSourceTest | 6 |
| 实际玻璃分支、触摸拖动保持、松手与等待100帧 | SegmentLensPlacementTest | 2 |
| 背景更新时的帧保留、字形及几何变动失效 | GlassLensFrameIdentityTest | 4 |
| 渲染队列合并、完成后静止、关闭和迟到回调 | GlassFrameQueueTest | 5 |
| 局部字形与材质的完整合成输入 | SegmentRefractionSourceTest | 2 |
| 局部材质采样 | SegmentMaterialSourceTest | 1 |
| 前景文字与选中状态 | SegmentedForegroundTest | 1 |
| 采样几何 | GlassLensCaptureGeometryTest | 3 |
| 缺失帧回退 | GlassLensFallbackTest | 1 |
| 既有按压、速度和色散参数 | GlassLensOpticsTest | 13 |

生产Kotlin与AndroidTest Kotlin编译通过。完整任务耗时9分钟；使用受限Gradle runner、单worker、1GiB堆及384MiB单测试进程，stdout/stderr/退出码与XML保存在交付包内。

## 交付状态

本轮App内部修复不改变插件契约、SDK、审核或网站，无需重新发布SDK。提交包含 `[skip ci]`，推送 `uipreview` 并核对远端SHA；最终提交、源码与校验和见交付 `verification.json` / `SHA256SUMS`。

本轮未触发新CI/CD，未构建APK、合并main、部署网站、同步Gitee或更新正式提示。108测试APK保持原文件。109开发版本及发布说明已准备好。

## 待设备验收

真实华为API31、API32与Android17：轻微拖动保持、松手回弹、快速反向、切换后静止、滚动收起、字体改变、玻璃开关和复杂壁纸下的文字及色散连续性。未运行设备/模拟器，局部字形、软件Canvas和帧调度通过不代表最终GPU闪烁已经得到设备确认。
