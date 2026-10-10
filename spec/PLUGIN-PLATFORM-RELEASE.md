# App 与插件平台协同发布

> **文件位置说明（2026-10 补记）：** 本文是 App 仓库内的摘要版，规则以本文为准。文中原先指向的 `../plugins/spec/PLUGIN-PLATFORM-RELEASE.md`、`../../plugins/spec/PLUGIN-PLATFORM-RELEASE.md` 与 GitHub 上的 `znjhahaha/zhengfang-plugins/blob/main/spec/PLUGIN-PLATFORM-RELEASE.md` **均已不存在**（本地无该路径、线上返回 404），因此不再作为链接指向；平台侧若之后补回完整规范，请在此处更新链接。

发布 App 时必须主动同步契约、SDK、网站开发包/文档及插件审核工具链。

`promote-release.yml` 在正式推广前自动运行 `python3 scripts/check_plugin_platform.py`，线上资源和本地宿主契约不一致会阻止创建正式标签/Release。必须完成同步后重试，不能删除门槛、重签原包或手改校验和。

`plugin-audit.yml` 从网站读取工具链元数据、完整下载并校验 SDK/规则。SDK/审核变更后必须运行 `self_test=true` 并核对公开 `audit-toolchain` 收据；真实开发者任务不作为测试样本。已有发布授权覆盖必要同步，不等用户另行提醒 SDK 或工作流。
