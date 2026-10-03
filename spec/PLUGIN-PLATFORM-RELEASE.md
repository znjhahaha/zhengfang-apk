# App 与插件平台协同发布

发布 App 时必须主动同步契约、SDK、网站开发包/文档及插件审核工具链。完整执行规范位于同一工作区的 `../plugins/spec/PLUGIN-PLATFORM-RELEASE.md`（仓库内相对位置为 `../../plugins/spec/PLUGIN-PLATFORM-RELEASE.md`）。规范源码：https://github.com/znjhahaha/zhengfang-plugins/blob/main/spec/PLUGIN-PLATFORM-RELEASE.md 。

`promote-release.yml` 在正式推广前自动运行 `python3 scripts/check_plugin_platform.py`，线上资源和本地宿主契约不一致会阻止创建正式标签/Release。必须完成同步后重试，不能删除门槛、重签原包或手改校验和。

`plugin-audit.yml` 从网站读取工具链元数据、完整下载并校验 SDK/规则。SDK/审核变更后必须运行 `self_test=true` 并核对公开 `audit-toolchain` 收据；真实开发者任务不作为测试样本。已有发布授权覆盖必要同步，不等用户另行提醒 SDK 或工作流。
