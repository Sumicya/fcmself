package sumicya.fcmself.xposed

import android.os.Build
import android.service.notification.NotificationListenerService

import java.lang.reflect.Method

import io.github.libxposed.api.XposedInterface

import sumicya.fcmself.config.FcmselfConfig
import sumicya.fcmself.util.Hooks
import sumicya.fcmself.util.MethodArgs
import sumicya.fcmself.util.Reflect
import sumicya.fcmself.xposed.XposedModule.Companion.printLog

/**
 * 通知保持模块 - 防止系统自动清除 FCM 通知
 *
 * 部分 Android 系统会在应用未启动或后台运行时自动清除其通知，导致用户无法看到 FCM 推送。
 * 本模块通过 Hook 系统的通知管理服务，阻止系统自动清除目标应用的通知。
 *
 * 工作原理：查找 NotificationManagerService 的 cancelAllNotificationsInt 方法，
 * 按系统版本确定参数位置（包名和原因代码），当检测到目标应用且原因是 PACKAGE_CHANGED
 * （或 ColorOS/OxygenOS 特定原因代码）时，阻止取消通知。
 *
 * 生效条件（无配置项，对所有 FCM 目标应用始终生效）：
 * 取消原因为 REASON_PACKAGE_CHANGED，或 ColorOS 15 / OxygenOS 15 的 10020 / 10021。
 */
class KeepNotification(api: XposedInterface, classLoader: ClassLoader) : XposedModule(api, classLoader) {

    init {
        try {
            startHook()
        } catch (e: Throwable) {
            printLog("No Such Method com.android.server.notification.NotificationManagerService.cancelAllNotificationsInt")
        }
    }

    private fun startHook() {
        val clazz = Reflect.findClass("com.android.server.notification.NotificationManagerService", classLoader)
        val targetMethod = Reflect.findMethodMostParams(clazz, "cancelAllNotificationsInt")
            ?: throw NoSuchMethodError(clazz.name + "#cancelAllNotificationsInt")

        val indices = resolveIndices(targetMethod)
        val pkgIndex = indices[0]
        val reasonIndex = indices[1]

        // 下标是按系统版本硬编码的猜测值，ROM 或新版本系统可能改变签名。挂错下标会在
        // system_server 里抛 ClassCastException，或把无关的通知取消一并拦下，因此先按真实
        // 签名校验；不符就放弃这个 Hook（其它模块不受影响），并打出可供排查的签名信息。
        val paramTypes = targetMethod.parameterTypes
        if (!MethodArgs.matches(paramTypes, pkgIndex, reasonIndex)) {
            printLog("cancelAllNotificationsInt 签名与预期不符，已跳过该 Hook 以免误拦截通知："
                + "API " + Build.VERSION.SDK_INT + "，参数=" + paramTypes.contentToString()
                + "，预期 pkg@" + pkgIndex + "(String) reason@" + reasonIndex + "(int)")
            return
        }
        printLog("cancelAllNotificationsInt hook 参数：pkg@" + pkgIndex
            + " reason@" + reasonIndex + "（API " + Build.VERSION.SDK_INT + "）")

        Hooks.hook(api, targetMethod) { chain ->
            // 系统启动完成前不介入
            if (!FcmselfConfig.isBootComplete()) return@hook chain.proceed()
            // 目标包名可解析即介入：阻止系统因应用包变化自动清理其通知
            if (hasTargetPackage(chain.getArg(pkgIndex) as String?)) {
                val reason = chain.getArg(reasonIndex) as Int
                // 原因是应用包变化（如更新/卸载）：阻止取消通知
                if (reason == NotificationListenerService.REASON_PACKAGE_CHANGED
                    || reason == REASON_COS_OOS_1
                    || reason == REASON_COS_OOS_2
                ) {
                    // 不调用 chain.proceed()：直接返回，等于这次取消请求被忽略
                    return@hook null
                }
            }
            chain.proceed()
        }
    }

    private companion object {
        /** ColorOS 15 / OxygenOS 15 特有的取消原因代码 */
        const val REASON_COS_OOS_1 = 10020
        const val REASON_COS_OOS_2 = 10021

        /** 按系统版本解析 cancelAllNotificationsInt 的 (pkg, reason) 参数下标。 */
        fun resolveIndices(targetMethod: Method): IntArray {
            val pkgIndex = 2
            val parameterCount = targetMethod.parameterTypes.size
            val reasonIndex = when {
                Build.VERSION.SDK_INT in 30..33 -> 8
                Build.VERSION.SDK_INT == 34 -> when (parameterCount) {
                    10 -> 8
                    8 -> 7
                    else -> throw NoSuchMethodError()
                }
                Build.VERSION.SDK_INT >= 35 -> 7
                else -> throw NoSuchMethodError()
            }
            return intArrayOf(pkgIndex, reasonIndex)
        }
    }
}
