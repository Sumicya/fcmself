# fcmself - FCM 推送修复工具

[![Android CI](https://github.com/Sumicya/fcmself/workflows/Android%20CI/badge.svg)](https://github.com/Sumicya/fcmself/actions)

一个基于 Xposed/LSPosed 的 Android FCM(GCM) 推送通知修复模块，特别针对国内定制 ROM 进行优化。

## 功能特性

### 核心功能
- **唤醒未启动应用**: 当收到 FCM 消息时自动唤醒目标应用，解决 `Failed to broadcast to stopped app`
- **阻止通知自动移除**: 防止系统自动清除应用停止时的通知
- **解除自启动限制**: 绕过各厂商对应用自启动的限制
- **重连修复**: 固定 GMS 心跳/重连间隔，负倒计时主动触发重连（运行于 GMS 进程）

### 厂商特定修复

#### OPPO/OnePlus ColorOS
- 绕过 OplusProxyWakeLock 冻结机制
- 阻止 OplusProxyBroadcast 拦截 FCM 广播
- 禁用 Hans 后台管理系统对 GMS 的限制

#### MIUI/HyperOS
- 修复 PowerKeeper 对 GMS 的限制
- 解除本地通知自动清除
- 绕过 BroadcastQueue/SmartPower 的自启动拦截

## 关于 FCM

FCM 是 Android 中由 Google 维护的一条介于 Google 服务器与 GMS 应用之间用于推送通知的长链接。
一般的工作流程为：应用服务器将消息发送到 Google 服务器，Google 服务器将消息推送给 GMS 应用，GMS 应用通过广播传递给应用，应用通过接收到的 FCM 消息决定是否发送通知和通知内容。
其中 GMS 通过 FCM 广播通知应用时，如果应用处于非运行状态，就会出现 `Failed to broadcast to stopped app`，fcmself 主要就是解决这个问题。

## 系统要求

- Android 10+ (API 29+)，Android 10-15 测试
- Root 权限 + LSPosed 框架（Modern Xposed API 100+，target 102）
- Google Play 服务 (GMS) 已安装

## 安装说明

1. 确保设备已 root 并安装 LSPosed 框架
2. 在 LSPosed 中启用本模块（勾选对应作用域，见下）
3. 重启设备
4. 打开应用，授予"读取应用列表"权限，在应用列表中选择需要接收 FCM 推送的应用

### LSPosed 作用域

- 必须勾选 `system`（系统服务，核心 Hook 所在）
- MIUI 设备勾选 `com.miui.powerkeeper`（电量管家限制解除）
- 需要重连修复时勾选 `com.google.android.gms`
- 在 MIUI/HyperOS 上如果推送没有问题，就不需要额外勾选电量和性能相关作用域

## 配置选项

- **阻止应用停止时自动清除通知**: 启用后防止系统自动清理通知
- **允许唤醒被冰箱冻结的应用**: 配合 IceBox 等冻结工具使用（需授予 `com.catchingnow.icebox.SDK` 权限）
- **全选包含 FCM 的应用**: 菜单一键操作
- **打开 FCM Diagnostics**: 跳转 GMS 诊断页（重连修复会在其中注入 RECONNECT 按钮）

## 技术实现

### Hook 的组件

#### 系统服务 (`system`)
- `ActivityManagerService.broadcastIntentLocked` / `BroadcastController.broadcastIntentLocked` (API 29-35)
- `BroadcastQueueInjector/BroadcastQueueImpl/BroadcastQueueModernStubImpl.checkApplicationAutoStart` (MIUI 12/13/HyperOS)
- `AutoStartManagerServiceStubImpl.isAllowStartService`
- `SmartPowerService.shouldInterceptBroadcast`
- `OplusAppStartupManager.shouldPreventSendReceiverReal` (OOS/ColorOS 15)
- `NotificationManagerService.cancelAllNotificationsInt`
- `OplusProxyWakeLock` / `OplusProxyBroadcast` (ColorOS)
- Hans `OplusBgSceneManager`：阻止 GMS 限制状态注册与更新 (ColorOS)

#### GMS (`com.google.android.gms`)
- `GcmChimeraService` 生命周期 + 内部 Timer 重连管理（hook 点自动发现并持久化）
- `GcmChimeraDiagnostics` 注入操作按钮

#### 厂商服务 (`com.miui.powerkeeper` 等)
- `MilletConfig.isGlobal` / `SimpleSettings.Misc.getBoolean`
- `MilletPolicy` 构造时的黑/白名单修改
- `SmartPowerPolicyManager.shouldInterceptService` (MIUI 13)

### 架构说明

- `libxposed/`：传统 Xposed API 风格的封装层，底层桥接到 LSPosed `XposedInterface`。
  同一方法重复挂载多个回调时只安装一个底层拦截器，避免回调重复执行。
- `config/FcmselfConfig`：配置中心（白名单/开关/启动时机），system_server 启动完成后 60 秒才介入广播。
- 各 Fix 模块独立 try/catch，单个 Hook 点缺失/失败不影响其它模块。

## 项目结构

```
app/
├── src/main/java/com/sumicya/fcmself/
│   ├── XposedMain.java              # LSPosed 入口（Hook 模块清单登记处）
│   ├── MainActivity.java            # 设置界面（白名单/开关）
│   ├── BootCompletedReceiver.java
│   ├── config/FcmselfConfig.java    # 配置中心
│   ├── libxposed/                   # Xposed API 兼容层（桥接 LSPosed）
│   ├── util/                        # 工具类（日志、IceBox 等）
│   └── xposed/                      # Hook 模块
│       ├── XposedModule.java        # 模块基类
│       ├── BroadcastFix.java        # 广播修复
│       ├── AutoStartFix.java        # 自启动修复
│       ├── KeepNotification.java    # 通知保持
│       ├── OplusProxyFix.java       # ColorOS 专用
│       ├── PowerkeeperFix.java      # MIUI 电量管家
│       ├── MiuiLocalNotificationFix.java
│       └── ReconnectManagerFix.java # GMS 重连修复
└── build.gradle
```

## 构建说明

```bash
./gradlew assembleRelease
# 输出：app/build/outputs/apk/release/app-release-unsigned.apk
```

CI 在 push 到 master 与 pull_request 上都会构建；构建失败时日志尾部会写进 job summary。
push 到 master 时自动签名并创建 Release（`fcmself-*.apk`），
`app/build.gradle` 有变更时额外推送到 LSPosed 模块仓库。

## 升级说明

本模块的 applicationId 已从 `com.kooritea.fcmfix` 改为 `com.sumicya.fcmself`（LSPosed 模块仓库路径同步改为
`Xposed-Modules-Repo/com.sumicya.fcmself`）。LSPosed 的远程配置按模块包名隔离，因此：

- 旧版本不会自动升级为新版本，需要先在 LSPosed 中停用并卸载旧模块，再安装新模块；
- 旧模块里勾选的白名单**不会迁移**，安装后需要重新选择目标应用；
- GMS 内的重连修复参数存放在 GMS 自己的 `fcmself_config` 里，需要在 FCM Diagnostics 页面重新开启一次。

## 已知问题

- 非 MIUI/HyperOS/OxygenOS15/ColorOS15 系统可能需要给予目标应用类似"允许自启动"的权限，以及电池选项设置为"不优化"
- 各 ROM 的 Hook 点随版本变化，部分机型可能需要自行验证
- GMS 更新后重连修复的 hook 点会自动重新发现；发现失败时会发送通知并禁用该功能（其它功能不受影响）

## 注意事项

1. 本模块仅用于学习研究目的
2. 不同 ROM 版本可能需要不同的 Hook 点
3. 部分功能需要配合其他工具（如 IceBox）使用
4. ColorOS 15 已测试通过，其他版本请自行验证

## 许可证

本项目仅供学习交流使用

## 鸣谢

- Xposed/LSPosed 团队
- 所有贡献者
