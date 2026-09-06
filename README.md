# fcmself - FCM 推送修复工具

[![Android CI](https://github.com/Sumicya/fcmself/workflows/Android%20CI/badge.svg)](https://github.com/Sumicya/fcmself/actions)

一个基于 Xposed/LSPosed 的 Android FCM(GCM) 推送通知修复模块，特别针对国内定制 ROM 进行优化。

**纯 Hook 模块**：没有设置界面、没有白名单、没有任何配置项——装上、在 LSPosed 里勾选作用域、重启，所有修复对所有 FCM 目标应用生效。APK 里除图标与 manifest 外只有模块自身的类（CI 产出的 release + debug 两个包合计约 217 KB）。

## 功能特性

### 核心功能
- **唤醒未启动应用**: 当收到 FCM 消息时自动唤醒目标应用，解决 `Failed to broadcast to stopped app`
- **阻止通知自动移除**: 防止系统自动清除应用停止时的通知
- **解除自启动限制**: 绕过各厂商对应用自启动的限制
- **重连修复**: GMS 心跳/重连倒计时出现异常负值时主动触发重连（运行于 GMS 进程）

### 厂商特定修复

#### OPPO/OnePlus ColorOS
- 绕过 OplusProxyWakeLock 冻结机制
- 阻止 OplusProxyBroadcast 拦截 FCM 广播
- 禁用 Hans 后台管理系统对 GMS 的限制

## 关于 FCM

FCM 是 Android 中由 Google 维护的一条介于 Google 服务器与 GMS 应用之间用于推送通知的长链接。
一般的工作流程为：应用服务器将消息发送到 Google 服务器，Google 服务器将消息推送给 GMS 应用，GMS 应用通过广播传递给应用，应用通过接收到的 FCM 消息决定是否发送通知和通知内容。
其中 GMS 通过 FCM 广播通知应用时，如果应用处于非运行状态，就会出现 `Failed to broadcast to stopped app`，fcmself 主要就是解决这个问题。

## 系统要求

- Android 10+（API 29+）。Android 10-15 有过测试记录；**Android 16（API 36）已于 2026-09-05
  在 ColorOS + LSPosed 2.2.0 上真机验证通过**
- Root 权限 + LSPosed 框架，需支持 libxposed API 101+（LSPosed 2.1.0 / 2.2.0，本模块 target API 102）
- 厂商定制路径只覆盖 OPPO（ColorOS / OxygenOS 的 `Oplus*`）。MIUI/HyperOS 支持已按需移除
  （需要时可从 git 历史恢复），其它 ROM 只有通用修复（唤醒停止的应用、阻止通知被自动清理、
  GMS 重连修复）
- Google Play 服务 (GMS) 已安装（重连修复需要）

两处按版本硬编码的参数下标在挂 Hook 前会按真实签名自校验，不符就打日志并跳过该 Hook
（不会误拦截广播或通知）。Android 16（API 36）上实测两者与 Android 15 的假设一致：
`BroadcastController.broadcastIntentLocked` 为 `intent@3 / appOp@13`，
`NotificationManagerService.cancelAllNotificationsInt` 为 `pkg@2 / reason@7`。

Android 16 + ColorOS + LSPosed 2.2.0 的实测结果：核心唤醒、ColorOS 代理绕过与解冻
（`unfreezeIfNeed` 4 参数签名）、GMS 重连修复的 hook 点自动发现（定位到混淆后的
`timer_class` / `timer_settimeout_method` / `timer_alarm_type_property`）均生效；

## 安装说明

1. 确保设备已 root 并安装 LSPosed 框架
2. 在 LSPosed 中启用本模块（勾选对应作用域，见下）
3. 重启设备

模块没有启动图标，也不需要任何配置。

### LSPosed 作用域

模块自带默认预选（`META-INF/xposed/scope.list`，`staticScope=false` 所以仍可手动调整）：

- `system`：系统服务，核心 Hook 所在，**必须**
- `com.google.android.gms`：重连修复与 FCM Diagnostics 注入

## 行为说明（无配置项）

原先由设置界面控制的开关现在都固定生效：

- **阻止应用停止时自动清除通知**：始终生效
- **重连修复**：在 GMS 的 FCM Diagnostics 页面注入 `RECONNECT` 按钮（原先还有一个"打开 fcmself"
  按钮，随设置界面一起移除）

## 技术实现

### Hook 的组件

#### 系统服务 (`system`)
- `ActivityManagerService.broadcastIntentLocked` / `BroadcastController.broadcastIntentLocked` (API 29-35)
- `OplusAppStartupManager.shouldPreventSendReceiverReal` (OOS/ColorOS 15)
- `NotificationManagerService.cancelAllNotificationsInt`
- `OplusProxyWakeLock` / `OplusProxyBroadcast` (ColorOS)
- Hans `OplusBgSceneManager`：阻止 GMS 限制状态注册与更新 (ColorOS)

#### GMS (`com.google.android.gms`)
- `GcmChimeraService` 生命周期 + 内部 Timer 重连管理（hook 点自动发现并持久化）
- `GcmChimeraDiagnostics` 注入操作按钮

### 架构说明

- `util/Hooks`：libxposed `hook(Executable).intercept(...)` 的薄封装，只额外提供
  "原方法执行完再跑一段逻辑"的 after 语义。hook 回调就是 `chain -> {...}`：
  要跳过原方法就不调用 `chain.proceed()`，直接返回想要的值。
- `util/Reflect`：类/方法/构造器查找、字段读写、方法调用（各 ROM 私有类的签名
  只能运行期按名字 + 参数个数找）。
- `config/FcmselfConfig`：运行期状态，只保留"系统是否启动完成"（system_server 用户解锁后延迟 60 秒才介入广播）。
- 各 Fix 模块独立 try/catch，单个 Hook 点缺失/失败不影响其它模块。

## 项目结构

```
app/
├── src/main/java/sumicya/fcmself/
│   ├── XposedMain.java              # LSPosed 入口（Hook 模块清单登记处）
│   ├── config/FcmselfConfig.java    # 运行期状态（仅"系统是否启动完成"）
│   ├── util/                        # 工具类（日志、hook 安装、反射查找、参数下标）
│   └── xposed/                      # Hook 模块
│       ├── XposedModule.java        # 模块基类
│       ├── BroadcastFix.java        # 广播修复
│       ├── AutoStartFix.java        # 自启动修复
│       ├── KeepNotification.java    # 通知保持
│       ├── OplusProxyFix.java       # ColorOS 专用
│       └── ReconnectManagerFix.java # GMS 重连修复
└── build.gradle
```

## 构建说明

```bash
./gradlew assembleRelease
# 输出：app/build/outputs/apk/release/app-release-unsigned.apk
```

CI（`.github/workflows/android.yml`）**不使用任何 secrets**，只做编译并上传两个产物：

| 产物 | 说明 |
| --- | --- |
| `fcmself-<版本>-debug-signed.apk` | debug 签名，可直接安装测试 |
| `fcmself-<版本>-unsigned.apk` | release 未签名，本地签完再装 |

签名在本地完成（Termux 一条命令）：

```bash
pkg install -y openjdk-21 apksigner
./scripts/sign-apk.sh <未签名.apk>
```

详见 [`docs/build-and-sign-termux.md`](docs/build-and-sign-termux.md)。

> **CI 配置需要同步更新**：改成单变体后产物名是 `app-release-unsigned.apk`，
> 现有 workflow 里硬编码的 `mv app-full-release-unsigned-signed.apk ...` / `mv app-lite-...` 会直接失败。
> 改好的完整配置见 [`docs/android.yml.suggested`](docs/android.yml.suggested)，
> 执行 `cp docs/android.yml.suggested .github/workflows/android.yml` 即可
> （该文件改动无法从本分支推送：Arena 侧的 GitHub App 缺少 `workflows` 权限）。

## 升级说明

本模块的 applicationId 已从 `com.kooritea.fcmfix` 改为 `sumicya.fcmself`（LSPosed 模块仓库路径同步改为
`Xposed-Modules-Repo/sumicya.fcmself`）。LSPosed 的远程配置按模块包名隔离，因此：

- 旧版本不会自动升级为新版本，需要先在 LSPosed 中停用并卸载旧模块，再安装新模块；
- **设置界面与白名单已彻底移除**：模块不再有启动图标，也不再读取任何配置。原先"阻止应用停止时
  自动清除通知"这类开关改为始终生效，作用范围从白名单应用扩大到所有 FCM 目标应用；
- GMS 内的重连修复参数存放在 GMS 自己的 `fcmself_config`（原 `fcmfix_config`）里。包名变更后 GMS 视为
  全新配置，模块会**自动**重新寻找 hook 点并把 `enable` 置真，不需要手动开启；寻找失败时会发通知
  并只禁用重连修复，其它功能不受影响。

## 已知问题

- 非 OxygenOS15/ColorOS15 系统可能需要给予目标应用类似"允许自启动"的权限，以及电池选项设置为"不优化"
- 各 ROM 的 Hook 点随版本变化，部分机型可能需要自行验证
- GMS 更新后重连修复的 hook 点会自动重新发现；发现失败时会发送通知并禁用该功能（其它功能不受影响）

## 注意事项

1. 本模块仅用于学习研究目的
2. 不同 ROM 版本可能需要不同的 Hook 点
4. ColorOS 15 已测试通过，其他版本请自行验证

## 许可证

本项目仅供学习交流使用

## 鸣谢

- Xposed/LSPosed 团队
- 所有贡献者
