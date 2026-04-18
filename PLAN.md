# `zf_old` 端到端透传与展示补全计划

## Summary
目标是把 `zf_old` 从公开下单页、探针识别、D1 持久化、Go 派发、challenge 刷新、后台管理台这整条链路补成“不会被降级成 `zf`”的完整实现。

当前计划文档方向是对的，但缺了真正会导致 `zf_old -> zf` 退化的几处关键落点，尤其是：
- `web-app/src/lib/go-client.ts`
- `web-app/src/app/api/public/cloud-orders/[orderNo]/challenge/[challengeId]/refresh/route.ts`
- `web-app/src/lib/cloud-db.ts`
- `web-app/src/app/dashboard/cloud-orders/page.tsx` 中的系统类型编辑与展示分支
- 对应测试

本计划只覆盖 Web 端和云派发链的 `zf_old` 透传与展示，不改 Go 侧 `zf_old` 抢课引擎本身的业务逻辑。

## Scope
### In scope
- Web 端新增并完整透传 `systemType = "zf_old"`
- 老正方探针识别 `default2.aspx`
- `zf_old` 的默认 referer 与 session header 派生
- 公开下单、管理员下单、编辑订单、challenge 刷新链路不降级
- Dashboard 正确展示、编辑、打标 `zf_old`
- 相关测试补齐

### Out of scope
- 不改 `course_grabber_script/cloud_grab_server/engine/zf_old_adapter.go` 的业务执行逻辑
- 不新增新的 `zf_old` system_options
- 不改 D1 schema 结构
- 不改非云订单页面的视觉重构

## Current State
仓库真实现状已经确认：
- `web-app/src/lib/cloud.ts` 仍把系统类型限定为 `'zf' | 'qz'`
- `normalizeSystemType()` 目前只会返回 `qz` 或 `zf`
- `deriveReferer()` 只区分 `qz` 和新版正方
- `web-app/src/lib/go-client.ts` 里两处仍是 `order.system_type === 'qz' ? 'qz' : 'zf'`
- `challenge refresh` 路由里也仍是二元分支
- `PublicOrderSubmit.tsx` 和后台订单台仍是二元系统切换
- `cloud-db.ts` 的 `CreateCloudOrderParams.systemType` 仍只声明 `'zf' | 'qz'`

这些点决定了即使前端提交了 `zf_old`，后续链路仍可能被回退成 `zf`。

## Public APIs / Interfaces / Types
### Shared types
修改 [cloud.ts](/d:/nextjs-course-selector-app/web-app/src/lib/cloud.ts)：
- `export type CloudOrderSystemType = 'zf' | 'qz' | 'zf_old'`

修改 [cloud-db.ts](/d:/nextjs-course-selector-app/web-app/src/lib/cloud-db.ts)：
- `CreateCloudOrderParams.systemType?: 'zf' | 'qz' | 'zf_old'`

修改前端状态类型：
- `PublicOrderSubmit.tsx` 中 `systemType` state 改为 `'zf' | 'qz' | 'zf_old'`
- `dashboard/cloud-orders/page.tsx` 中表单 `systemType` 改为支持 `zf_old`

### Normalization behavior
修改 [cloud.ts](/d:/nextjs-course-selector-app/web-app/src/lib/cloud.ts)：
- `normalizeSystemType(value)` 必须返回三态：
  - `qz`
  - `zf_old`
  - 默认 `zf`

默认规则：
```ts
export function normalizeSystemType(value: unknown): CloudOrderSystemType {
  const v = String(value || '').trim().toLowerCase();
  if (v === 'qz') return 'qz';
  if (v === 'zf_old') return 'zf_old';
  return 'zf';
}
```

### Referer derivation
修改 [cloud.ts](/d:/nextjs-course-selector-app/web-app/src/lib/cloud.ts)：
- `deriveReferer(baseUrl, systemType)` 三态分支：
  - `qz`: 保持现有 `/xsxk/xklc_list`
  - `zf`: 保持现有新版正方 referer
  - `zf_old`: 新增 `${normalizeBaseUrl(baseUrl)}/default2.aspx`

理由：
- `zf_old` 登录和 challenge 刷新都以 `default2.aspx` 为稳定入口
- Go 端 `resolveZFOldLoginURL()` 已兼容根地址或 `default2.aspx`

### System options
`zf_old` 不新增任何 `system_options` 字段。
修改 [cloud.ts](/d:/nextjs-course-selector-app/web-app/src/lib/cloud.ts) 的 `normalizeSystemOptions()`：
- 保持非 `qz` 一律返回 `{}`

修改 [cloud-db.ts](/d:/nextjs-course-selector-app/web-app/src/lib/cloud-db.ts)：
- 存储时继续仅对 `qz` 写入 `system_options_json`
- `zf_old` 存 `null`

## Implementation Plan
### 1. Shared normalization and session-header layer
修改 [cloud.ts](/d:/nextjs-course-selector-app/web-app/src/lib/cloud.ts)：
- 扩充 `CloudOrderSystemType`
- 扩充 `normalizeSystemType`
- 扩充 `deriveReferer`
- `buildSessionHeaders()` 自动支持 `zf_old`
- 所有依赖 `systemType === 'qz' ? ... : ...` 的调用点改为使用 `normalizeSystemType()` 结果，不再手写二元判断

验收要求：
- 任何传入 `zf_old` 的地方都不会被隐式转换成 `zf`
- `zf_old` 的默认 referer 始终是 `default2.aspx`

### 2. Probe API and public submit page
修改 [detect-jwglxt route](/d:/nextjs-course-selector-app/web-app/src/app/api/public/detect-jwglxt/route.ts)：
- 接收 `{ baseUrl, systemType }`
- 当 `systemType === 'zf_old'` 时：
  - 优先探测 `bareUrl`
  - 同时探测 `bareUrl + '/default2.aspx'`
  - 页面特征正则新增：
    - `现代教学管理信息系统`
    - 标题含“正方”
- 对 `zf_old` 的 `suggestedUrl` 默认返回根地址 `bareUrl`
- 不把 `default2.aspx` 直接写回输入框作为唯一建议值

修改 [PublicOrderSubmit.tsx](/d:/nextjs-course-selector-app/web-app/src/components/public/PublicOrderSubmit.tsx)：
- 系统选择 UI 从二元改为三元
- 新增 `zf_old` 卡片或按钮态
- `getClassKeywordLabel/Placeholder/Hint` 支持 `zf_old`
- `urlHint` 分支支持 `zf_old`
  - 若填 `/jwglxt`，提示这更像新版正方
  - 若填根域名或 `default2.aspx`，提示可自动探测
- `handleProbe()` 发出 `systemType`
- 提交 payload 时保留 `systemType: 'zf_old'`

默认 UX 规则：
- `zf_old` 用户看到的辅助文案强调“文本模糊匹配”和“老版入口通常是根地址或 `default2.aspx`”

### 3. Persistence and order creation
修改 [cloud-db.ts](/d:/nextjs-course-selector-app/web-app/src/lib/cloud-db.ts)：
- `CreateCloudOrderParams.systemType` 类型扩容
- `createCloudOrder()` 使用新的三态 `normalizeSystemType()`
- `updateCloudOrderSession()` 使用新的三态 `deriveReferer()`
- `recordCloudOrderEvent()` 里写入的 `systemType` 与 `sessionHeaders` 必须保留 `zf_old`
- `normalizeWebsiteOrderItems()` 不对 `zf_old` 做额外降级，仍按“网站端仅 full_chain”规则处理

默认数据策略：
- D1 不做 schema migration
- `system_type` 继续用 `TEXT`
- `zf_old` 与 `zf` 共用现有订单结构

### 4. Go dispatch bridge and challenge refresh
修改 [go-client.ts](/d:/nextjs-course-selector-app/web-app/src/lib/go-client.ts)：
- `submitOrderToGo()` 的 `buildSessionHeaders(..., systemType)` 改为三态，不再写二元表达式
- `updateGoJobSession()` 同样改为三态
- `system_type` 发往 Go 的 payload 保持原样 `order.system_type || 'zf'`
- 如有共用 helper，抽出 `const systemType = normalizeSystemType(order.system_type)`

修改 [challenge refresh route](/d:/nextjs-course-selector-app/web-app/src/app/api/public/cloud-orders/[orderNo]/challenge/[challengeId]/refresh/route.ts)：
- `buildSessionHeaders(..., normalizeSystemType(order.system_type))`
- 不再使用 `(order.system_type === 'qz' ? 'qz' : 'zf')`

这是这份旧计划最关键的漏项之一。
如果这里不改，`zf_old` challenge 图片刷新仍会带错 referer/header 分支。

### 5. Admin dashboard rendering and editing
修改 [dashboard cloud-orders page](/d:/nextjs-course-selector-app/web-app/src/app/dashboard/cloud-orders/page.tsx)：
- 系统类型选择器新增 `zf_old`
- 默认表单 state 支持 `zf_old`
- 编辑订单时正确回填 `zf_old`
- 列表徽章改成三态：
  - `qz`: 强智
  - `zf`: 正方
  - `zf_old`: 老正方
- 列表徽章样式：
  - `zf_old`: `bg-amber-50 text-amber-700`
- 详情页的 class-name 说明改成三态：
  - `qz`: 附加筛选关键词
  - `zf`: 教学班关键词
  - `zf_old`: 建议显示为“课程/教师/时间辅助关键词”或“教学班关键词（老正方按文本匹配）”
- `qz` 专属轮次区域保持仅在 `systemType === 'qz'` 时显示
- `zf_old` 不显示 qz 轮次配置

还需修正的硬编码点：
- 当前默认 `systemType: 'zf'`
- 系统切换只支持 `zf/qz`
- 列表徽章只判断 `qz` 与“其他=正方”
这些都要改成真正的三态分支。

### 6. Tests
必须补以下测试。

#### Shared / lib
新增或修改 [cloud.ts](/d:/nextjs-course-selector-app/web-app/src/lib/cloud.ts) 相关测试：
- `normalizeSystemType('zf_old') === 'zf_old'`
- `deriveReferer(baseUrl, 'zf_old')` 返回 `.../default2.aspx`
- `buildSessionHeaders(..., 'zf_old')` 的 `Referer` 为 `default2.aspx`

#### Public probe API
为 [detect-jwglxt route](/d:/nextjs-course-selector-app/web-app/src/app/api/public/detect-jwglxt/route.ts) 增加测试：
- `zf_old + default2.aspx + 现代教学管理信息系统` 能识别成功
- `zf_old` 返回的 `suggestedUrl` 是根地址，不是强制 `default2.aspx`
- `zf` 旧逻辑不回归
- `qz` 旧逻辑不回归

#### Public submit route
修改 [public submit test](/d:/nextjs-course-selector-app/web-app/src/app/api/public/cloud-orders/submit.route.test.ts)：
- 新增 `systemType: 'zf_old'` 用例
- 断言 `createCloudOrder()` 收到的是 `zf_old`
- 断言 `systemOptions` 对 `zf_old` 仍为 `{}` 或 `undefined`
- 不影响现有 `qz` 用例

#### Admin submit/edit route
修改 [admin cloud-orders route test](/d:/nextjs-course-selector-app/web-app/src/app/api/cloud-orders/route.test.ts)：
- 新增 `zf_old` 创建订单用例
- 新增 `zf_old` 编辑订单保持系统类型不变用例

#### Go bridge
为 [go-client.ts](/d:/nextjs-course-selector-app/web-app/src/lib/go-client.ts) 增加测试：
- `submitOrderToGo()` 对 `zf_old` 生成 `default2.aspx` referer
- `updateGoJobSession()` 对 `zf_old` 生成 `default2.aspx` referer
- 发给 Go 的 `system_type` 仍是 `zf_old`

#### Challenge refresh
新增 [refresh route](/d:/nextjs-course-selector-app/web-app/src/app/api/public/cloud-orders/[orderNo]/challenge/[challengeId]/refresh/route.ts) 测试：
- `order.system_type = 'zf_old'` 时调用 `buildSessionHeaders(..., 'zf_old')`
- 不再被回退成 `zf`

### 7. Manual verification
手工验证分三组。

#### Public page
- 在 [PublicOrderSubmit.tsx](/d:/nextjs-course-selector-app/web-app/src/components/public/PublicOrderSubmit.tsx) 选择 `老版正方`
- 输入根域名，探针识别成功
- 输入带 `/jwglxt` 的 URL，会收到针对老正方的提醒
- 提交后订单详情页显示 `system_type = zf_old`

#### Admin dashboard
- 后台新建一单 `zf_old`
- 列表徽章显示“老正方”
- 编辑弹窗保留 `zf_old`
- 详情页 hint 文案是老正方分支，而不是新版正方或强智

#### Go dispatch chain
- 从 D1 读取 `system_type = zf_old` 的订单
- 通过 [go-client.ts](/d:/nextjs-course-selector-app/web-app/src/lib/go-client.ts) 派发
- Go 侧收到 `system_type = zf_old`
- challenge 刷新时使用 `default2.aspx` referer

## Acceptance Criteria
满足以下全部条件才算完成：
- 任意 `zf_old` 订单从公开页或后台创建后，数据库里 `system_type` 为 `zf_old`
- 再次读取、编辑、重新派发后，`system_type` 仍为 `zf_old`
- `go-client.ts` 不再把 `zf_old` 降成 `zf`
- `challenge refresh` 不再把 `zf_old` 降成 `zf`
- 探针 API 能识别老正方 `default2.aspx`
- Dashboard 列表、详情、编辑都能正确显示 `zf_old`
- 相关测试全部通过
- 现有 `zf` 和 `qz` 行为不回归

## Important File List
必须覆盖这些文件：
- [web-app/src/lib/cloud.ts](/d:/nextjs-course-selector-app/web-app/src/lib/cloud.ts)
- [web-app/src/lib/cloud-db.ts](/d:/nextjs-course-selector-app/web-app/src/lib/cloud-db.ts)
- [web-app/src/lib/go-client.ts](/d:/nextjs-course-selector-app/web-app/src/lib/go-client.ts)
- [web-app/src/app/api/public/detect-jwglxt/route.ts](/d:/nextjs-course-selector-app/web-app/src/app/api/public/detect-jwglxt/route.ts)
- [web-app/src/components/public/PublicOrderSubmit.tsx](/d:/nextjs-course-selector-app/web-app/src/components/public/PublicOrderSubmit.tsx)
- [web-app/src/app/dashboard/cloud-orders/page.tsx](/d:/nextjs-course-selector-app/web-app/src/app/dashboard/cloud-orders/page.tsx)
- [web-app/src/app/api/public/cloud-orders/submit/route.ts](/d:/nextjs-course-selector-app/web-app/src/app/api/public/cloud-orders/submit/route.ts)
- [web-app/src/app/api/public/cloud-orders/[orderNo]/challenge/[challengeId]/refresh/route.ts](/d:/nextjs-course-selector-app/web-app/src/app/api/public/cloud-orders/[orderNo]/challenge/[challengeId]/refresh/route.ts)
- [web-app/src/app/api/cloud-orders/route.test.ts](/d:/nextjs-course-selector-app/web-app/src/app/api/cloud-orders/route.test.ts)
- [web-app/src/app/api/public/cloud-orders/submit.route.test.ts](/d:/nextjs-course-selector-app/web-app/src/app/api/public/cloud-orders/submit.route.test.ts)

## Assumptions and Defaults
- `zf_old` 当前不引入独立的 `system_options`
- `zf_old` 默认 referer 取 `default2.aspx`
- 探针成功后建议保存根地址，不强制把 `default2.aspx` 写进 `baseUrl`
- D1 schema 不需要变更
- Go 端 `zf_old` 引擎已存在且继续作为既有事实使用
- 本计划默认保留现有公开页与后台页的整体 UI 结构，只做三态扩容，不做界面重构
