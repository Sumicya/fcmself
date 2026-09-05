# 真机验证清单

模块没有界面，行为全部体现在日志里。所有日志的 tag 固定为 `FcmSelf`（见 `FcmselfLog.TAG`），
每条日志同时写入 `Log.d` 和 LSPosed 日志（`XposedBridge.log`），行格式为：

```
[fcmself] [<进程身份>]<内容>
```

进程身份：system_server 是 `android`，GMS 是 `com.google.android.gms`，电量管家是 `com.miui.powerkeeper`。

## 0. 准备

1. 安装 CI 产物里的 `fcmself-<版本>-debug-signed.apk`（或本地用 `scripts/sign-apk.sh` 签一个）
2. LSPosed 启用模块，确认作用域已勾选（默认预选）：`system`、`com.google.android.gms`、
   非 MIUI 设备可不勾 `com.miui.powerkeeper`
3. 重启设备
4. 开始抓日志（重启后立刻开始，别错过启动日志）：

```bash
# Termux（设备已 root 并授权 Termux 使用 su）
su -c "logcat -s FcmSelf"

# 或电脑上
adb logcat -s FcmSelf
```

也可以直接在 LSPosed 管理器 → 日志 里看（内容相同）。

## 1. 模块有没有被加载

启动后应看到（`BroadcastFix` 在 system_server 里打印）：

```
[fcmself] [android]Android API: 35
[fcmself] [android]appOp_args_index: ...
[fcmself] [android]intent_args_index: ...
[fcmself] [android]hook target: <类名>
```

**什么都没有** → LSPosed 没加载模块。依次检查：模块是否"已启用"、作用域是否勾了 `system`、
是否重启过（改作用域后必须重启）。

## 2. 核心功能：唤醒未启动的应用

先确保目标应用是完全停止的：从最近任务划掉，再执行 `adb shell su -c "am force-stop <包名>"`。
然后让服务端推一条 FCM 消息，期望日志：

```
[fcmself] [android]Add FLAG_INCLUDE_STOPPED_PACKAGES: <包名>
```

出现这行且通知正常弹出 = 核心功能生效。（这行是给正在发送的那条广播补上
`FLAG_INCLUDE_STOPPED_PACKAGES`，不是额外发一条广播。）

**必须没有的一行**（出现说明核心 Hook 点没找到，模块整体不工作）：

```
broadcastIntentLocked hook 位置查找失败，fcmself将不会工作。
```

## 3. 自启动限制（按 ROM，命中一个就够）

```
Allow Auto Start: <包名>
[<action>]checkApplicationAutoStart package_name: <包名>
Disable MIUI Intercept: <包名>
SmartPowerService.shouldInterceptBroadcast package_name: <包名>
```

`No Such Method ...` / `No Such Class ...` 表示这台设备的 ROM 没有对应 Hook 点，**属正常**，
不需要处理。

## 4. 通知不被自动清理

Hook 的是 `NotificationManagerService.cancelAllNotificationsInt`，只拦截
`REASON_PACKAGE_CHANGED`(8) 以及 ColorOS 15 / OxygenOS 15 的 10020 / 10021 两种取消原因，
其它原因照常放行。这项现在对所有 FCM 目标应用**始终生效**（原来由界面开关控制）。

失败信号：

```
No Such Method com.android.server.notification.NotificationManagerService.cancelAllNotificationsInt
```

## 5. MIUI / HyperOS 电量管家（需勾选 `com.miui.powerkeeper`）

```
Set com.miui.powerkeeper.millet.MilletConfig.isGlobal to true
Success: PowerKeeper GMS Limitation.
Success: MilletPolicy mSystemBlackList.
Success: MilletPolicy whiteApps.
Success: MilletPolicy mDataWhiteList.
```

`Error: MilletPolicy. Field not found: ...` = 这台设备的 PowerKeeper 改了字段名，请连同
日志和 PowerKeeper 版本一起反馈。

## 6. OPPO / OnePlus（ColorOS / OxygenOS）

```
OplusProxyWakeLock instance captured
shouldProxy bypass: pkg=...
unfreeze: <包名>, uid=...
```

`hook error OplusProxy: ...` / `hook error registerGmsRestrictObserver: ...` 表示该 ColorOS
版本没有对应方法，其它 Hook 不受影响。

## 7. GMS 重连修复（需勾选 `com.google.android.gms`）

打开 FCM Diagnostics 页面（模块会往里注入 `RECONNECT` 按钮）：

```bash
adb shell am start -a android.intent.action.VIEW \
  -n com.google.android.gms/com.google.android.gms.gcm.GcmDiagnostics
```

首次运行的正常流程：

```
fcmself_config init
正在更新hook位置
更新hook位置成功
```

并发一条"自动更新配置文件成功"通知。之后重启 GMS 进程会直接按缓存 Hook。

其它可能看到的：

| 日志 / 通知 | 含义 |
| --- | --- |
| `自动寻找hook点失败...` + 通知"自动更新配置文件失败" | 该 GMS 版本找不到 Hook 点，重连修复自动禁用，其它功能不受影响 |
| `当前为旧版GMS，请使用0.4.1版本FCMSELF，禁用重连修复功能` | GMS 版本低于 `MIN_GMS_VERSION_CODE`，重连修复不启用 |
| `当前配置文件enable标识为false，FCMSELF退出` | 上次自动发现失败留下的状态；清掉 GMS 的 `fcmself_config` 或等 GMS 更新后会重新发现 |
| `gms已更新: ...` + `正在更新hook位置` | GMS 升级后自动重新发现 Hook 点（预期行为） |
| `Send broadcast GCM_RECONNECT` | 点了 RECONNECT 按钮，或检测到倒计时出现异常负值后主动重连 |

页面里还能看到以 `[fcmself] [com.google.android.gms]` 开头的行，那是本模块转发到 GMS 日志的
诊断信息。

## 7.1 不重启也能验证的部分

模块在**进程启动时**注入。因此只有 `system` 作用域（system_server）必须重启设备，
其余作用域只要把对应进程杀掉重启即可生效：

```bash
# GMS 重连修复（ReconnectManagerFix）
adb shell su -c "am force-stop com.google.android.gms"

# MIUI 电量管家（PowerkeeperFix）
adb shell su -c "am force-stop com.miui.powerkeeper"
```

进程重启后按第 5、7 节看日志。**注意**：核心的"唤醒未启动应用"跑在 system_server 里，
不重启无法验证；第 2、3、4 节必须等设备重启后再看。

## 7.2 Android 16（API 36）需要额外确认的两行

代码按 Android 15 的签名假设硬编码了参数下标，挂 Hook 前会自校验。日志里应该看到：

```
[fcmself] [android]Android API: 36
[fcmself] [android]hook target: com.android.server.am.BroadcastController   ← 或 ActivityManagerService
[fcmself] [android]cancelAllNotificationsInt hook 参数：pkg@2 reason@7（API 36）
```

如果看到的是下面这些，说明 Android 16 改了签名，请把整段日志发回来（这属于需要适配的情况，
不是崩溃——对应的 Hook 会被安全跳过，其它模块照常工作）：

```
broadcastIntentLocked 参数位置无法确定（API 36，参数个数 NN）
broadcastIntentLocked hook 位置查找失败，fcmself将不会工作。
cancelAllNotificationsInt 签名与预期不符，已跳过该 Hook 以免误拦截通知：API 36，参数=[...]
broadcastIntentLocked 硬编码下标失效，改用参数名定位：intent@N appOp@N   ← 兜底成功，功能仍可用
```

## 8. 出问题时怎么排除

1. LSPosed 里停用模块 → 重启 → 现象还在 = 与本模块无关（ROM 或 GMS 本身）
2. 卸载模块后，模块会发一条 `Fcmself已卸载，重启后停止生效。` 通知，重启后彻底停用
3. 需要回滚代码时，本分支每个 commit 都是独立可回退的（`git log --oneline`）

## 9. 反馈问题时请附上

- 从重启开始的完整 `FcmSelf` 日志
- ROM 名称与版本、GMS 版本号、LSPosed 版本、Android 版本
- 目标应用包名，以及"杀掉应用 → 推送"的复现步骤
