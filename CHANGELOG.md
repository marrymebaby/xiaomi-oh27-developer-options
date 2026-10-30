# Changelog

## v1.4 — 2026-10-30

- 新增首个按钮 `Open developer dashboard (OH27 firmware route)`，通过已导出的设置容器打开系统保留的开发者选项面板。
- 在小米智能家庭屏 Max 27 / OH27、Android 14、构建增量 177 上实测：用户手动开启开发者选项及 USB 调试后，Mac USB ADB shell 成功。
- 启动前检查容器导出状态、启用状态及固定 Fragment 元数据；启动失败时显示错误，输出 `[FIRMWARE ROUTE]` 日志。
- 保留旧版诊断、APK 导出和可选无障碍连击辅助功能；主流程无需使用它们。

这是当前已实机验证的发布版本，不代表所有固件兼容，不提供 root 或自动系统优化。

## Earlier diagnostic versions

- v1.3：增加 FactoryMode APK 和组件信息导出。
- v1.2：增加 Settings APK 和组件信息导出。
- 早期版本：设置页面探测、状态读取及可选版本号连击辅助。
