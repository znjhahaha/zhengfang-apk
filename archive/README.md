# 归档区

本目录收录**历史上有效、但已不再描述当前版本**的材料。归档不等于作废：内容一律保留，只标注其适用版本与已被什么取代。

**当前版本以 `app/version.properties` 为准**（现为 1.0.116 / code116），宿主 SDK 3.6.0、插件 API 主版本 3。`main` 上的 `README.md`、`CHANGELOG.md`、`docs/` 与 `release-notes/` 描述现状；本文与同目录文件只作历史留存。

## 为什么有归档而不是删除

1. 旧版本记录里的失败、取舍与验证边界是可追溯的工程证据，删掉就再也查不到了。
2. 1.0.103 时期及更早的文档与当前实现已有实质差异（例如 1.0.96 移除了插件的隔离 UID），直接留在 `docs/` 会被误读为现状，所以集中到这里并逐条标注。
3. 归档材料**不作为当前版本的行为依据**；引用请以对应 `release-notes/vX.Y.Z.md` 和代码为准。

## 归档清单

### 变更日志历史

| 文件 | 覆盖版本 | 说明 |
| --- | --- | --- |
| [CHANGELOG-1.0.31-1.0.35.md](CHANGELOG-1.0.31-1.0.35.md) | 1.0.31 – 1.0.35（2026-05-26） | 从 `CHANGELOG.md` 拆出的早期条目。1.0.36 – 1.0.66 期间 `CHANGELOG.md` 未曾随版本更新（当时被 `.gitignore` 屏蔽），那一段只能查 Git 提交历史；1.0.67 未发布。 |

`CHANGELOG.md` 自 1.0.68 起的完整条目仍在该文件内，其中缺失版本由 2026-10 补充，见文首「维护方式」说明。

### 历史验收与合规记录（位于 `docs/`，未移动）

| 文件 | 适用版本 / SDK | 被什么取代 |
| --- | --- | --- |
| [docs/ISSUES-41-49-ACCEPTANCE.md](../docs/ISSUES-41-49-ACCEPTANCE.md) | 1.0.92 – 1.0.96 / SDK 3.2.5 | 后续版本继续沿用其授权模型，但版本号与验证结果均为当时状态 |
| [docs/ISSUES-50-51.md](../docs/ISSUES-50-51.md) | 1.0.96 / SDK 3.2.5 | 同上；含移除隔离 UID 的安全取舍说明 |
| [docs/security/ISSUE-41-API32.md](../docs/security/ISSUE-41-API32.md) | 基线 1.0.91 / SDK 3.2.4 | 文内「隔离 Android UID」结论已于 1.0.96 被推翻，文首已加过时标注 |
| [docs/plugins/SITE-CONSENT-3.2.5.md](../docs/plugins/SITE-CONSENT-3.2.5.md) | SDK 3.2.5 | 授权行为已被后续契约扩展取代；隔离 UID 一项已订正 |
| [docs/TEST97-ACCEPTANCE.md](../docs/TEST97-ACCEPTANCE.md) | 1.0.97 / SDK 3.2.7 | 1.0.98 起的更新检查与下载流程 |
| [docs/ui/UI102-ACCEPTANCE.md](../docs/ui/UI102-ACCEPTANCE.md) | 1.0.102 / SDK 3.2.8 | UI104 起的插件中心与筛选浮层 |
| [docs/ui/UI104-ACCEPTANCE.md](../docs/ui/UI104-ACCEPTANCE.md) | 1.0.104（uipreview）/ SDK 3.2.8 | UI105 → UI106 → UI107 的界面结构 |
| [docs/ui/UI104-DESIGN-REVIEW.md](../docs/ui/UI104-DESIGN-REVIEW.md) | 1.0.103 – 1.0.104 / SDK 3.2.8 | UI105 → UI106 → UI107 的界面结构 |
| [docs/ui/UI105-ACCEPTANCE.md](../docs/ui/UI105-ACCEPTANCE.md) | 1.0.105（uipreview）/ SDK 3.2.8 | UI106、UI107 |
| [docs/ui/UI106-ACCEPTANCE.md](../docs/ui/UI106-ACCEPTANCE.md) | 1.0.106（uipreview）/ SDK 3.2.8 | UI107 |
| [docs/ui/MATERIAL-HEADER-MASK.md](../docs/ui/MATERIAL-HEADER-MASK.md) | 1.0.103 前后 / SDK 3.2.8 | 后续顶栏与分段栏实现 |
| [docs/superpowers/plans/2026-08-17-zhengfang-app-promo-video.md](../docs/superpowers/plans/2026-08-17-zhengfang-app-promo-video.md) 与 [specs 同名设计稿](../docs/superpowers/specs/2026-08-17-zhengfang-app-promo-video-design.md) | 2026-08-17 规划 | 宣传片工程不在本仓库内，两份文档属规划留档，文内引用的路径与产物均已不存在 |

### 仍然有效、请勿当作历史材料的文档

- `CHANGELOG.md`（1.0.68 起）、`release-notes/v*.md`：逐版本发布说明，是本项目最完整的一手记录。
- `docs/ui/UI107-COMPONENTS.md`、`UI108-REFRACTION.md`、`UI109-STABILITY.md`、`docs/ui/TOP-BAR-VISIBILITY.md`：描述当前仍在使用或最近一轮的实现。
- `spec/PREVIEW112-RESTORATION.md`、`spec/PLUGIN-PLATFORM-RELEASE.md`：当前有效的规范与恢复记录。
- `distribution/README.md`、`docs/ui/UI104-BRANCHES.json`：当前分发说明与分支对照数据。

## 已知的已移除材料

以下路径曾被 `CHANGELOG.md` 或发布说明引用，但当前仓库内不存在，历史原文仍保留在对应的 `release-notes/v*.md` 中：

- `docs/glass-lens-api32.md`（1.0.70 新增的 API 31/32 折射设计说明）
- `docs/testing/2026-09-13-api32-fixes.md`（1.0.76 的对照结果）
- `verification.json`、`ACCEPTANCE.md` 等交付目录产物（见 `docs/ISSUES-50-51.md`、`docs/ISSUES-41-49-ACCEPTANCE.md`）
- `spec/PLUGIN-PLATFORM-RELEASE.md` 原先指向的 `../plugins/spec/PLUGIN-PLATFORM-RELEASE.md` 及对应线上地址（已 404）

## 维护约定

- 新增归档：在本文件对应表格追加一行，写明「适用版本 / SDK」与「被什么取代」。
- 旧文档不删除。需要在原地保留时，在文首加一行 `> **历史文档。** …` 标注适用版本与后继文档。
- 拆出 `CHANGELOG.md` 早期条目时，保持标题行与原文一字不改，只移动位置。
