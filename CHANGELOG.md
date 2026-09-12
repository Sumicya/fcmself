# 更新日志

> 本仓库由 fcmfix 迁移而来，此前没有维护变更日志。

## 0.9.0（未发布）—— 自由化 / 原生化 / 现代化

> 本轮重构的三条主线：**自由化**（Hook 点摆脱版本硬编码、介入条件统一）、
> **原生化**（地道 Kotlin + 以「还原原生 AOSP 推送行为」为设计基准）、
> **现代化**（构建体系 / 代码 / 测试 / CI 全面对齐当前最佳实践）。
> 按约定，本轮允许行为调整，不保证与 0.8.x 逐点一致。

### 自由化

- **签名自适应解析**：新增 `hook/Signatures`，`broadcastIntentLocked` 的 (intent, appOp)、
  `cancelAllNotificationsInt` 的 (pkg, reason) 下标改为「版本候选表 + 类型校验 + 参数名兜底」，
  候选全部失效时安全放弃并打出完整签名——适配新系统从「改代码」变成「看日志补候选」
- **实参探测替代固定下标**：新增 `mods/PushArgs`，各自启动/拦截 Hook 点不再按 `args[N]` 取
  intent，而是按「参数本身是 Intent / 参数带 `intent` 字段」两种形态扫描实参（字段按类缓存），
  ROM 插参不再导致 Hook 失效
- **统一推送族判定**：新增 `core/Push`，全部 Hook 点共用一套介入判据
  （c2dm RECEIVE/**REGISTRATION** + Firebase MESSAGING_EVENT / INSTANCE_ID_EVENT / **NEW_TOKEN**）：
  - 收紧：HyperOS `checkApplicationAutoStart`、`isAllowStartService` 旧版对**任意**定向广播放行，
    本版只放行推送族
  - 放宽：`SmartPowerPolicyManager.shouldInterceptService` 旧版只认 MESSAGING_EVENT，
    本版覆盖完整推送族

### 原生化

- **包结构分层**，破除 util→xposed 循环依赖：
  `core`（日志 / 推送判定，零模块内依赖）、`hook`（反射 / Hook 工具 / 签名解析）、
  `mods`（各 Fix 模块）、根包（入口 / 模块基类 / 进程环境）
- **进程状态收敛**：`XposedModule` 基类的 companion 巨静态（context、实例表、广播接收器）、
  `FcmselfConfig` 的 boot 计时、两次握手，全部收进 `ProcessEnv` 单例；
  模块生命周期从「onCanReadConfig 扇出」改为 `ProcessEnv.onReady(owner)` 按 owner 去重
- **Kotlin 惯用法**：移除全部 `@JvmStatic` / Java 式拼接，标准库收敛
  （`firstOrNull` / `maxByOrNull` / `apply` / 字符串模板）；并发原语保持 stdlib（不加协程依赖，
  system_server 内越少依赖越好）
- **行为设计基准显式化**：各 Hook 的语义注释统一对齐「原生 AOSP 本就没有这些限制」——
  模块做的事是**把 OEM 丢掉的原生语义补回来**，而不是绕过安全

### 现代化

- **构建脚本 Groovy → Kotlin DSL**：`settings.gradle.kts`（`pluginManagement` +
  `dependencyResolutionManagement`，删除废弃的 `buildscript classpath` / `allprojects` / 手写 clean）、
  根/模块 `build.gradle.kts`、**version catalog**（`gradle/libs.versions.toml`）
- **版本号注入现代化**：CI 不再用 `sed` 改构建脚本，改为 `-Pfcmself.versionName=...` Gradle 属性
  （`providers.gradleProperty` 读取，本地构建回落 `0.9.0`）
- **Gradle**：开启 parallel / caching / configuration-cache；wrapper 属性补 `networkTimeout` 与
  distribution 校验，`-all` 换 `-bin` 发行包
- **测试 JUnit 4 → JUnit 5**（jupiter + platform-launcher，`useJUnitPlatform()`）；
  新增 `SignaturesTest`（合成假签名表驱动验证候选/兜底/失败日志）、`PushTest`（推送族分类），
  `ReflectTest` 补构造器新语义用例（前缀零匹配必须显式失败、最宽构造器）
- 源码目录 `src/main/java` → `src/main/kotlin`（Kotlin 工程惯例）

### 修复 / 加固（真机行为疑点）

- `Reflect.findConstructorMostMatch`：旧实现的 `>=` 比较在**零匹配**时静默返回最后一个构造器，
  可能 hook 错对象；现要求至少匹配第一个给定类型，否则抛 `NoSuchMethodError`。
  OplusProxyWakeLock 的「任意构造器」改用语义明确的 `findConstructorMostParams`（参数最多者）
- `OplusProxy` 状态（wakelock 引用、3/4 参签名探测）加 volatile / 同步：旧版裸 companion 字段
  跨 hook 线程无可见性保证，且并发首调可能双双走探测路径
- `MiuiLocalNotificationFix`：包名取 `args[3]` 前先校验该位置确为 String 并打出完整签名
  （全项目唯一没做签名校验的点）
- 日志修复：`KeepNotification` 不再抛无消息的 `NoSuchMethodError()`，
  跳过原因统一为 `hook skip <点>: <原因>` 可排查格式；`PowerkeeperFix` 的类缺失不再误报为方法缺失
- `PowerkeeperFix.whiteApps` 的 GMS 移除**保留旧版行为**，但注释明确标注该列表语义未证实
  （若实为「允许后台的白名单」，此操作反而收紧）——待真机核实后再定

### 环境升级

- JUnit `4.13.2` → JUnit Jupiter `5.11.4`
- AGP / Gradle / libxposed / compileSdk 保持上一轮升级后的版本（AGP 9.4 / Gradle 9.6 / libxposed 102 / SDK 36）
- `versionCode` 55 → 56，`versionName` 0.8.0 → 0.9.0

## 0.8.0（历史）

> 以下为上一轮「简化 / 自由化 / 现代化」的记录。

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

- applicationId 由 `com.kooritea.fcmfix` 改为 `sumicya.fcmself`
- 移除设置界面与白名单：所有修复对所有 FCM 目标应用始终生效，模块无启动图标、无任何配置项
- 厂商特定修复覆盖 ColorOS / OxygenOS 与 MIUI / HyperOS

## 代码质量与文档

- 重构：清理死代码、统一字段与方法命名风格、抽取助手方法，行为不变
- 健壮性小修：`MethodArgs.matches` 对负下标返回 `false`；`BroadcastFix` 挂载前增加下标非负校验
- 新增单元测试 `ReflectTest`（反射封装），`MethodArgsTest` 补充负下标用例
- README 精简为单页；清理 CI 工作流顶部历史注释

## 环境升级

- Android Gradle Plugin `8.13.0` → `9.4.0`，Gradle wrapper `8.13` → `9.6.0`
- 改用 AGP 9 的 **built-in Kotlin**：删除 `apply plugin: 'kotlin-android'` 与顶层 `kotlin-gradle-plugin` classpath
- `android.kotlinOptions{}` 迁移为顶层 `kotlin { compilerOptions{} }`；`jvmTarget` 对齐 17，`javaParameters` 保留
- libxposed `api:101.0.1` → `102.0.0`（`targetApiVersion=102` 对齐）
- `targetSdkVersion` `34` → `36`
- 顺带把误入库的 `app/FcmFuck.apk` 从 Git 索引移除
