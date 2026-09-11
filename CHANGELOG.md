# 更新日志

> 本仓库由 fcmfix 迁移而来，此前没有维护变更日志。当前版本尚未打 tag（`versionName` 为 `dev`），
> 下面列出的即本次未发布版本相对旧 fcmfix 的完整变化。

## 简化 / 自由化 / 现代化（本次重构）

### 去配置化（简化）

- 移除 GMS 重连修复的 SharedPreferences 配置缓存（`fcmself_config`）与"配置文件"概念：hook 点改为每次 GMS 进程启动时在内存中自动发现，不再写任何文件
- 移除「自动更新配置文件成功/失败」两条通知，以及注入 FCM Diagnostics 页面的 `RECONNECT` 按钮
- 移除 `XposedModule` 里已无引用的通知发送逻辑（仅保留卸载时清理旧渠道）

### 通用化（自由化）

- 恢复 MIUI / HyperOS 自启动修复：`BroadcastQueueInjector` / `BroadcastQueueImpl` / `BroadcastQueueModernStubImpl.checkApplicationAutoStart`、`checkReceiverIfRestricted`、`AutoStartManagerServiceStubImpl.isAllowStartService`、`SmartPowerService.shouldInterceptBroadcast`、`SmartPowerPolicyManager.shouldInterceptService`
- 新增 `MiuiLocalNotificationFix`：放行 MIUI 被拦截的本地通知
- 新增 `PowerkeeperFix`：解除 MIUI PowerKeeper 对 GMS 的黑名单管控
- 上述各点均独立容错，非对应 ROM 上只打日志跳过，不影响其它模块

### 工具

- `Reflect` 新增 `setObjectField` / `setStaticObjectField`（PowerkeeperFix 需要）

## 行为与身份

## 行为与身份

- applicationId 由 `com.kooritea.fcmfix` 改为 `sumicya.fcmself`
- 移除设置界面与白名单：所有修复对所有 FCM 目标应用始终生效，模块无启动图标、无任何配置项
- 厂商特定修复覆盖 ColorOS / OxygenOS 与 MIUI / HyperOS

## 代码质量与文档

- 重构：清理死代码（`Reflect.findConstructorExact` 等）、统一字段与方法命名风格、抽取助手方法，行为不变
- 健壮性小修：`MethodArgs.matches` 对负下标返回 `false`；`BroadcastFix` 挂载前增加下标非负校验
- 新增单元测试 `ReflectTest`（反射封装），`MethodArgsTest` 补充负下标用例
- README 精简为单页；删除与 CI 重复的 `docs/android.yml.suggested`；清理 CI 工作流顶部历史注释
