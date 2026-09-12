package sumicya.fcmself.xposed

import java.lang.reflect.Method

import io.github.libxposed.api.XposedInterface

import sumicya.fcmself.util.Hooks
import sumicya.fcmself.util.Reflect
import sumicya.fcmself.xposed.XposedModule.Companion.printLog

/**
 * MiuiLocalNotificationFix - MIUI 本地通知限制修复
 *
 * MIUI 会拦截本地通知（isAllowLocalNotification / isDeniedLocalNotification）。
 * 本模块 Hook 这两个方法之一，对 FCM 目标应用放行本地通知。
 */
class MiuiLocalNotificationFix(api: XposedInterface, classLoader: ClassLoader) : XposedModule(api, classLoader) {

    init {
        try {
            startHook()
        } catch (e: Throwable) {
            printLog("hook error MiuiLocalNotificationFix:" + e.message)
        }
    }

    private fun startHook() {
        try {
            val clazz = try {
                Reflect.findClass("com.android.server.notification.NotificationManagerServiceInjector", classLoader)
            } catch (e: Reflect.ClassNotFound) {
                Reflect.findClass("com.android.server.notification.NotificationManagerServiceImpl", classLoader)
            }

            var targetMethod: Method? = null
            for (method in clazz.declaredMethods) {
                if ("isAllowLocalNotification" == method.name || "isDeniedLocalNotification" == method.name) {
                    targetMethod = method
                    break
                }
            }
            if (targetMethod == null) {
                printLog("Not found [isAllowLocalNotification/isDeniedLocalNotification]")
                return
            }

            val isAllow = "isAllowLocalNotification" == targetMethod.name
            Hooks.hook(api, targetMethod) { chain ->
                val result = chain.proceed()
                val args = chain.args.toTypedArray()
                val pkg = if (args.size > 3 && args[3] is String) args[3] as String else null
                if (hasTargetPackage(pkg)) {
                    isAllow
                } else {
                    result
                }
            }
        } catch (e: Reflect.ClassNotFound) {
            printLog("Not found [isAllowLocalNotification/isDeniedLocalNotification] in " +
                "com.android.server.notification.[NotificationManagerServiceInjector/NotificationManagerServiceImpl]")
        }
    }
}
