# 更新日志

> 本仓库由 fcmfix 迁移而来，此前没有维护变更日志。当前版本尚未打 tag（`versionName` 为 `dev`），
> 下面列出的即本次未发布版本相对旧 fcmfix 的完整变化。

## 行为与身份

- applicationId 由 `com.kooritea.fcmfix` 改为 `sumicya.fcmself`
- 移除设置界面与白名单：所有修复对所有 FCM 目标应用始终生效，模块无启动图标、无任何配置项
- 移除 MIUI / HyperOS 支持，厂商特定修复聚焦 ColorOS / OxygenOS

## 代码质量与文档

- 重构：清理死代码（`Reflect.findConstructorExact` 等）、统一字段与方法命名风格、抽取助手方法，行为不变
- 健壮性小修：`MethodArgs.matches` 对负下标返回 `false`；`BroadcastFix` 挂载前增加下标非负校验
- 新增单元测试 `ReflectTest`（反射封装），`MethodArgsTest` 补充负下标用例
- README 精简为单页；删除与 CI 重复的 `docs/android.yml.suggested`；清理 CI 工作流顶部历史注释
