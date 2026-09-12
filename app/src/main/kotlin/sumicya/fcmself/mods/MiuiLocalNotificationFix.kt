package sumicya.fcmself.mods

import io.github.libxposed.api.XposedInterface

import sumicya.fcmself.FcmselfModule
import sumicya.fcmself.core.FcmselfLog
import sumicya.fcmself.core.Push
import sumicya.fcmself.hook.Hooks
import sumicya.fcmself.hook.Reflect

/**
 * MiuiLocalNotificationFix —— MIUI 本地通知限制修复。
 *
 * MIUI 会拦截本地通知（isAllowLocalNotification / isDeniedLocalNotification）。
 * Hook 命中的那一个：目标包名可定位时，把结果改写为「允许」（isAllow 变体返回
 * true，isDenied 变体返回 false），其余调用原样放行。
 *
 * 包名参数沿用旧版取 @3 的行为（挂载前校验该位置确为 String，不符则不介入并
 * 打出完整签名，供适配排查——旧版这里没有任何校验与日志）。
 */
internal class MiuiLocalNotificationFix(api: XposedInterface, classLoader: ClassLoader) :
    FcmselfModule(api, classLoader, "MiuiLocalNotificationFix") {

    init {
        point("MIUI 本地通知限制") { startHook() }
    }

    private fun startHook() {
        val injectorName = "com.android.server.notification.NotificationManagerServiceInjector"
        val implName = "com.android.server.notification.NotificationManagerServiceImpl"
        val clazz = Reflect.findClassIfExists(injectorName, classLoader)
            ?: Reflect.findClassIfExists(implName, classLoader)
            ?: run {
                skip("MIUI 本地通知限制", "$injectorName / $implName 均不存在")
                return
            }

        val method = clazz.declaredMethods.firstOrNull {
            it.name == "isAllowLocalNotification" || it.name == "isDeniedLocalNotification"
        } ?: run {
            skip("MIUI 本地通知限制", "${clazz.name} 无 isAllowLocalNotification / isDeniedLocalNotification")
            return
        }

        val isAllow = method.name == "isAllowLocalNotification"
        val pkgIndex = if (method.parameterTypes.size > 3 && method.parameterTypes[3] == String::class.java) 3 else -1
        FcmselfLog.log(
            "MIUI 本地通知 hook: ${clazz.name}#${method.name} pkg@$pkgIndex " +
                "参数=${method.parameterTypes.contentToString()}"
        )

        Hooks.hook(api, method) { chain ->
            val result = chain.proceed()
            val pkg = if (pkgIndex >= 0) chain.getArg(pkgIndex) as? String else null
            if (Push.hasTarget(pkg)) isAllow else result
        }
    }
}
