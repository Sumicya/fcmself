# FCM Fix - FCM 推送修复工具

一个基于 Xposed/LSPosed 的 Android FCM(GCM) 推送通知修复模块，特别针对国内定制 ROM 进行优化。

## 功能特性

### 核心功能
- **唤醒未启动应用**: 当收到 FCM 消息时自动唤醒目标应用
- **阻止通知自动移除**: 防止系统自动清除应用停止时的通知
- **解除自启动限制**: 绕过各厂商对应用自启动的限制

### 厂商特定修复

#### OPPO/OnePlus ColorOS
- 绕过 OplusProxyWakeLock 冻结机制
- 阻止 OplusProxyBroadcast 拦截 FCM 广播  
- 禁用 Hans 后台管理系统对 GMS 的限制
- **配合 fcmfix 可实现无代理直接接收 FCM 消息**

#### MIUI/HyperOS
- 修复 PowerKeeper 对 GMS 的限制
- 解除本地通知自动清除

## 系统要求

- Android 10+ (API 29+)
- Root 权限 + LSPosed/Xposed 框架
- Google Play 服务 (GMS) 已安装

## 安装说明

1. 确保设备已 root 并安装 LSPosed 框架
2. 在 LSPosed 中启用本模块
3. 重启设备
4. 打开应用，授予必要权限
5. 在应用列表中选择需要接收 FCM 推送的应用

## 配置选项

- **阻止应用停止时自动清除通知**: 启用后防止系统自动清理通知
- **允许唤醒被冰箱冻结的应用**: 配合 IceBox 等冻结工具使用
- **目标无响应时代发提示通知**: 当应用无法正常接收时发送提醒

## 技术实现

### Hook 的组件

#### 系统服务 (`android`)
- `ActivityManagerService.broadcastIntentLocked`
- `BroadcastController.broadcastIntentLocked`
- `NotificationManagerService.cancelAllNotificationsInt`
- `OplusProxyWakeLock` (ColorOS)
- `OplusProxyBroadcast` (ColorOS)

#### GMS (`com.google.android.gms`)
- `GmsReconnectManager` 重连管理

#### 厂商服务 (`com.miui.powerkeeper` 等)
- MIUI PowerKeeper 相关限制

## 项目结构

```
app/
├── src/main/java/com/kooritea/fcmfix/
│   ├── XposedMain.java          # Xposed 入口
│   ├── MainActivity.java        # 配置界面
│   ├── BootCompletedReceiver.java
│   ├── libxposed/               # Xposed API 兼容层
│   ├── util/                    # 工具类
│   └── xposed/                  # Hook 模块
│       ├── BroadcastFix.java    # 广播修复
│       ├── KeepNotification.java # 通知保持
│       ├── OplusProxyFix.java   # ColorOS 专用
│       └── ...
└── build.gradle
```

## 注意事项

1. 本模块仅用于学习研究目的
2. 不同 ROM 版本可能需要不同的 Hook 点
3. 部分功能需要配合其他工具（如 IceBox）使用
4. ColorOS 15 已测试通过，其他版本请自行验证

## 构建说明

```bash
./gradlew assembleDebug
# APK 输出：app/build/outputs/apk/debug/app-debug.apk
```

## 许可证

本项目仅供学习交流使用

## 鸣谢

- Xposed/LSPosed 团队
- 所有贡献者
