# StrongZhi 字段语义重构计划（去除“教学班”心智模型）

## Summary
- 目标是把 StrongZhi 从当前“沿用 ZhengFang 的教学班输入模型”改成“课程名称 + 教师 + 时间 + 可选附加关键词”的真实模型。
- 结论已经明确：
  - StrongZhi 当前 Go 端没有稳定的 `jxbmc`/教学班名称字段。
  - `class_name` 在 StrongZhi 里只是模糊筛选辅助项，不是真教学班。
  - StrongZhi 真正提交依赖的是行级参数：`jx0404id / kcid / cfbs / xqid`。
- 本次改造不改变现有公共提交 API 的字段名，先做“语义重定义 + UI 文案改造 + 后端匹配语义对齐 + 管理页展示改造”，避免一次性 breaking change。
- 兼容策略：
  - 对外 JSON 继续保留 `class_name/className`
  - 但在 StrongZhi 模式下，将其正式定义为“附加筛选关键词”
  - ZhengFang 继续保留“教学班关键词”语义不变

## Goals
- 消除 StrongZhi 用户输入误导。
- 让 StrongZhi 的表单、任务详情、结果展示、文档都与真实能力一致。
- 为后续如果要新增 StrongZhi 专属字段（如 `extra_keyword`）打下兼容基础。
- 不影响 ZhengFang 提交流程和已有订单数据。

## Non-goals
- 本次不做 StrongZhi 验证码自动处理。
- 本次不做 StrongZhi 冲突确认自动续提。
- 本次不修改公共 submit API 的 JSON 字段形状。
- 本次不把 StrongZhi 分类筛选彻底接入执行逻辑，只会把“分类未真正参与筛选”明确展示出来并记录为后续缺口。

## Current state to preserve
- Go 端 StrongZhi 真实链路继续保留：
  - `runTaskQZFullChain()` in [worker.go](/d:/nextjs-course-selector-app/course_grabber_script/cloud_grab_server/engine/worker.go)
  - `submitSelectionQZ()` in [worker.go](/d:/nextjs-course-selector-app/course_grabber_script/cloud_grab_server/engine/worker.go)
- StrongZhi 匹配逻辑继续保留：
  - 课程名
  - 教师关键词
  - 时间关键词
  - `class_name` 作为模糊附加筛选
- ZhengFang 继续保留原本“课程 -> 教学班 -> 提交”的模式。

## Implementation plan

### 1. 明确字段语义，不改 API 名称
在 [models.go](/d:/nextjs-course-selector-app/course_grabber_script/cloud_grab_server/models/models.go) 和 TypeScript 类型中保留：
- `class_name`
- `className`

但补充语义约束：

- `system_type = zf`
  - `class_name` = 教学班关键词
- `system_type = qz`
  - `class_name` = 附加筛选关键词
  - 可匹配范围 = 课程名补充文本 + 教师 + 时间 + 地点 + 分类等组合文本
  - 明确“不代表教学班名称”

实施方式：
- Go 结构体字段不改名，只加注释。
- TS 类型字段不改名，只加注释和辅助 label 逻辑。
- 后端校验与 UI 文案按 `system_type` 分支解释。

### 2. StrongZhi 匹配层正式改名为“附加筛选”
在 [qz_adapter.go](/d:/nextjs-course-selector-app/course_grabber_script/cloud_grab_server/engine/qz_adapter.go) 对这段逻辑做语义清理：

当前：
- `task.ClassName` 被用来对 `row.CourseName + row.Teacher + row.Category + row.Time + row.Location` 做模糊匹配。

改造后：
- 保留当前算法，但增加注释和命名辅助函数：
  - 新增内部 helper：`qzBuildAuxiliaryMatchText(row)`
  - 新增内部 helper：`qzMatchAuxiliaryKeyword(task, row)`
- `qzMatchRowCandidates()` 中不再把这段逻辑称为“class match”
- 失败码仍保持 `qzErrNoClass` 兼容现有前端，但 message 改为：
  - StrongZhi: `course matched by name, but auxiliary keyword/teacher/time filters removed all candidates`
  - ZhengFang 相关逻辑不动

说明：
- 错误码暂不改，避免破坏前端兼容。
- 展示文案必须改，不再提“教学班”。

### 3. Web 公共下单页按系统切换字段文案
修改 [PublicOrderSubmit.tsx](/d:/nextjs-course-selector-app/web-app/src/components/public/PublicOrderSubmit.tsx)：

当前：
- 所有系统都显示“教学班关键词”

改造后：
- `systemType === 'zf'`
  - placeholder: `教学班关键词`
  - hint: `用于筛选具体教学班`
- `systemType === 'qz'`
  - label/placeholder: `附加筛选关键词`
  - hint: `可填教师、地点、备注片段等，不是教学班名称`
  - 示例文案：
    - `例如：南校区主楼N201`
    - `例如：法学2309`
    - `例如：实验楼A203`

同时更新 StrongZhi 区块说明文案：
- 当前文案里强调“自动探测轮次与抢课冲突保护”可保留
- 追加一句：
  - `StrongZhi 主要按课程名称、教师、时间匹配；附加筛选关键词仅作辅助过滤`

### 4. 管理后台订单编辑页同步改名
修改 [page.tsx](/d:/nextjs-course-selector-app/web-app/src/app/dashboard/cloud-orders/page.tsx) 中订单编辑表单：

当前：
- 输入框 placeholder 固定是 `教学班关键词`

改造后：
- `form.systemType === 'zf'`
  - placeholder: `教学班关键词`
- `form.systemType === 'qz'`
  - placeholder: `附加筛选关键词（非教学班）`

并在任务卡片上方增加一条系统级提示：
- `StrongZhi 当前不按教学班名称筛选，主要使用课程名称 / 教师 / 时间`

### 5. 任务详情展示区按系统解释字段
修改 [page.tsx](/d:/nextjs-course-selector-app/web-app/src/app/dashboard/cloud-orders/page.tsx) 的详情展示：

当前：
- 任务列表里 `class_name` 视觉上仍像“教学班”

改造后：
- `system_type = zf`
  - 展示标签：`教学班关键词`
- `system_type = qz`
  - 展示标签：`附加筛选关键词`
  - 若为空显示：`未设置附加筛选`
- 在 StrongZhi 结果区增加说明：
  - `StrongZhi 当前匹配维度：课程名称 / 教师 / 时间 / 附加筛选关键词`
  - `附加筛选关键词不代表教学班名称`

### 6. 本地 GUI 同步文案
修改 [course_gui_fluent.py](/d:/nextjs-course-selector-app/course_grabber_script/course_gui_fluent.py) 中涉及 StrongZhi 表单或任务编辑的位置。

要求：
- 如果 GUI 同时支持 ZhengFang 和 StrongZhi：
  - ZF 仍显示“教学班”
  - QZ 显示“附加筛选关键词”
- 如果当前 GUI 还没有清晰的 `system_type` 分支 UI：
  - 最低要求是在 StrongZhi 模式下 tooltip/说明文案改掉
- 若 GUI 当前只展示通用“教学班”字段：
  - 改成动态 label
  - 同时保留原数据绑定字段，避免破坏保存格式

### 7. 文档与帮助文案同步
更新以下文档：
- [qz_plan.md](/d:/nextjs-course-selector-app/qz_plan.md)
- [qz_live_evidence_2026-03-19.md](/d:/nextjs-course-selector-app/qz_live_evidence_2026-03-19.md)

新增说明：
- StrongZhi 没有可靠的教学班名称输入模型
- 当前实际匹配维度为：
  - 课程名称
  - 教师
  - 时间
  - 附加筛选关键词
- `class_name` 在 StrongZhi 中只是兼容字段名
- 分类字段当前仍未真正参与分类页筛选决策，只用于熔断和记录

建议新增一份明确说明文档：
- [strongzhi-field-semantics.md](/d:/nextjs-course-selector-app/strongzhi-field-semantics.md)

文档内容必须包含：
- 为什么 StrongZhi 不再称“教学班”
- 与 ZhengFang 的核心差异
- 用户应如何填写 StrongZhi 任务

### 8. 为后续 breaking change 做兼容铺垫
虽然本次不改 API，但要在代码里预留未来迁移路径。

在 TS 和 Go 中增加内部注释：
- 未来可新增 `aux_keyword` / `extra_keyword`
- 当前 `class_name` 为兼容字段
- 若未来要升级 API：
  - 新字段优先
  - 旧字段保留映射一段时间

但这次不实际新增对外字段，不做 DB migration。

## Important changes or additions to public APIs/interfaces/types
不新增 public API 字段，不改现有请求结构。

保留：
- Go `TaskPayload.ClassName`
- Go `TaskState.ClassName`
- TS `CloudOrderInputItem.className`
- DB `cloud_order_items.class_name`

新增的是“语义约束和展示分支”：
- `zf`: 教学班关键词
- `qz`: 附加筛选关键词

建议在代码注释中明确：
- [models.go](/d:/nextjs-course-selector-app/course_grabber_script/cloud_grab_server/models/models.go)
- [cloud.ts](/d:/nextjs-course-selector-app/web-app/src/lib/cloud.ts)

## Data flow after change
1. 用户在 Web 或 GUI 选择 `system_type=qz`
2. 输入：
   - 课程名称
   - 教师关键词
   - 时间关键词
   - 附加筛选关键词
3. 前端仍把附加筛选写入 `class_name`
4. Go 端 `qzMatchRowCandidates()`：
   - 先课程名
   - 再教师
   - 再时间
   - 最后附加筛选关键词
5. 展示层统一解释为“附加筛选关键词”，不再说“教学班”

## Edge cases and defaults
- `system_type=qz` 且 `class_name` 为空：
  - 合法，不报错
- `system_type=qz` 且用户把班级名填到 `class_name`：
  - 允许，当作普通辅助关键词使用
  - 不承诺它一定等价于教学班筛选
- `system_type=zf`：
  - 完全保持现有行为，不改任何语义
- 历史 StrongZhi 订单：
  - 旧数据里 `class_name` 原样保留
  - 详情页按 `system_type=qz` 解释成“附加筛选关键词”

## Test cases and scenarios

### Go tests
更新或新增 [qz_adapter_test.go](/d:/nextjs-course-selector-app/course_grabber_script/cloud_grab_server/engine/qz_adapter_test.go)：
- `qzMatchRowCandidates()` 课程名匹配成功
- `teacher_keyword` 过滤成功
- `time_keyword` 过滤成功
- `class_name` 作为附加筛选关键词匹配 `location/category/time/teacher` 组合文本成功
- `class_name` 不再在测试命名或断言文案里被称为“教学班”

### Frontend tests
更新或新增：
- [cloud-orders/route.test.ts](/d:/nextjs-course-selector-app/web-app/src/app/api/cloud-orders/route.test.ts)
- [public/cloud-orders/submit.route.test.ts](/d:/nextjs-course-selector-app/web-app/src/app/api/public/cloud-orders/submit.route.test.ts)

覆盖：
- `systemType=qz` 时表单文案显示“附加筛选关键词”
- `systemType=zf` 时文案仍显示“教学班关键词”
- StrongZhi 详情页展示标签正确
- 发送给 Go 的 payload 字段仍然是 `class_name/className`

### Manual verification
- 公共下单页切换到 StrongZhi：
  - 看到“附加筛选关键词（非教学班）”
- 管理后台新建/编辑 StrongZhi 订单：
  - 看到同样的文案
- StrongZhi 历史任务详情：
  - `class_name` 显示为“附加筛选关键词”
- ZhengFang 下单页和管理页：
  - 文案保持“教学班关键词”

## Acceptance criteria
- StrongZhi 用户界面中不再把该字段称为“教学班关键词”
- StrongZhi 详情页明确说明其实际匹配维度
- Go 端匹配逻辑语义与文案一致
- ZhengFang 现有输入和执行逻辑不受影响
- 无 DB migration
- 无 public API breaking change

## Assumptions and defaults
- 默认选择“兼容优先”方案，不改 `class_name` 字段名。
- 默认认为 StrongZhi 的“教学班”概念在现有实现里不可可靠依赖。
- 默认把 StrongZhi 的第四筛选维度命名为“附加筛选关键词”。
- 默认不在本次引入新的 `aux_keyword` 外部字段。
- 默认把“分类字段尚未真正参与分类页筛选”视为后续独立缺口，不在本次一并修复。
