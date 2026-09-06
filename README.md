# fcmself

[![Android CI](https://github.com/Sumicya/fcmself/workflows/Android%20CI/badge.svg)](https://github.com/Sumicya/fcmself/actions)

一个基于 LSPosed 的 FCM/GCM 推送通知修复模块，针对国内定制 ROM 优化。纯 Hook：没有界面、没有白名单、没有任何配置项——装上、勾选作用域、重启，即可对全部 FCM 目标应用生效。

## 功能

- **唤醒未启动的应用**：收到 FCM 消息时自动唤醒目标应用，解决 `Failed to broadcast to stopped app`
- **阻止通知自动清除**：防止系统因应用停止而清掉其通知
- **解除自启动限制**：绕过厂商对应用自启动的限制
- **GMS 重连修复**：心跳/重连倒计时异常时主动重连，并在 FCM Diagnostics 注入 RECONNECT 按钮

> 厂商特定修复目前只覆盖 OPPO / OnePlus（ColorOS / OxygenOS 的 `Oplus*` 与 Hans 后台限制）。

## 要求

- Android 10+（API 29+），已 root 并安装 LSPosed（libxposed API 101+）
- 已安装 Google Play 服务（GMS，重连修复需要）

## 安装

1. 确保设备已 root 并安装 LSPosed
2. 启用本模块，勾选作用域：`system`（必须）、`com.google.android.gms`
3. 重启设备

模块没有启动图标，也不需要任何配置。

## 构建

```bash
./gradlew assembleRelease   # 产物：app/build/outputs/apk/release/app-release-unsigned.apk
```

CI 只编译并上传 debug / unsigned 两个产物（无 secrets）。本地签名见 [`docs/build-and-sign-termux.md`](docs/build-and-sign-termux.md)，真机验证清单见 [`docs/verify-on-device.md`](docs/verify-on-device.md)。

## 许可证

仅供学习交流使用。

## 鸣谢

Xposed / LSPosed 团队与所有贡献者。
