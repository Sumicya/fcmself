package sumicya.fcmself.mods

import android.os.Build
import android.service.notification.NotificationListenerService

import io.github.libxposed.api.XposedInterface

import sumicya.fcmself.FcmselfModule
import sumicya.fcmself.ProcessEnv
import sumicya.fcmself.core.FcmselfLog
import sumicya.fcmself.core.Push
import sumicya.fcmself.hook.Hooks
import sumicya.fcmself.hook.Reflect
import sumicya.fcmself.hook.Signatures

/**
 * KeepNotification —— 防止系统自动清除推送通知。
 *
 * 部分 ROM 会在应用未运行时因「包变化」（更新/卸载等）自动清掉其通知。原生 AOSP
 * 没有这个行为，本模块 Hook `NotificationManagerService.cancelAllNotificationsInt`，
 * 在取消原因为 REASON_PACKAGE_CHANGED（或 ColorOS / OxygenOS 特有的 10020 / 10021）
 * 时直接忽略这次取消，把行为还原成原生语义——对所有包生效（无白名单）。
 *
 * (pkg, reason) 下标由 [Signatures.resolveNotificationArgs] 自适应解析：
 * 候选顺序按 API 版本给出（34 的 10 参形态 reason@8 与 8/9 参形态 reason@7 两代），
 * 全部候选过类型校验，不符则安全跳过并打出完整签名。
 */
internal class KeepNotification(api: XposedInterface, classLoader: ClassLoader) :
    FcmselfModule(api, classLoader, "KeepNotification") {

    init {
        point("NotificationManagerService#cancelAllNotificationsInt") { startHook() }
    }

    private fun startHook() {
        val className = "com.android.server.notification.NotificationManagerService"
        val clazz = Reflect.findClass(className, classLoader)
        val method = Reflect.findMethodMostParams(clazz, "cancelAllNotificationsInt")
            ?: throw NoSuchMethodError("$className#cancelAllNotificationsInt")

        // reason 候选顺序承载消歧规则：<=33 固定 @8；34 先试 @8（10 参）再试 @7（8 参）；
        // 35+ 先试 @7（9 参）。每个候选都会经 MethodArgs 校验，全部失败则放弃挂载。
        val reasonCandidates = when {
            Build.VERSION.SDK_INT <= 33 -> listOf(8)
            Build.VERSION.SDK_INT == 34 -> listOf(8, 7)
            else -> listOf(7, 8)
        }
        val (pkgIndex, reasonIndex) = Signatures.resolveNotificationArgs(method, reasonCandidates) {
            FcmselfLog.log(it)
        } ?: return
        FcmselfLog.log(
            "cancelAllNotificationsInt hook 参数：pkg@$pkgIndex reason@$reasonIndex（API ${Build.VERSION.SDK_INT}）"
        )

        Hooks.hook(api, method) { chain ->
            // 系统启动完成前不介入
            if (!ProcessEnv.isBootComplete) return@hook chain.proceed()
            val pkg = chain.getArg(pkgIndex) as? String
            if (!Push.hasTarget(pkg)) return@hook chain.proceed()
            val reason = chain.getArg(reasonIndex) as? Int
            // 原因是应用包变化（更新/卸载）：阻止取消，等于「这次取消请求被忽略」
            if (reason == NotificationListenerService.REASON_PACKAGE_CHANGED ||
                reason == REASON_COS_OOS_1 ||
                reason == REASON_COS_OOS_2
            ) {
                return@hook null
            }
            chain.proceed()
        }
    }

    private companion object {
        /** ColorOS 15 / OxygenOS 15 特有的取消原因代码。 */
        const val REASON_COS_OOS_1 = 10020
        const val REASON_COS_OOS_2 = 10021
    }
}
