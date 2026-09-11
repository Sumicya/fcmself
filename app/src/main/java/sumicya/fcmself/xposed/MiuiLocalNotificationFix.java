package sumicya.fcmself.xposed;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

import sumicya.fcmself.util.Hooks;
import sumicya.fcmself.util.Reflect;

/**
 * MiuiLocalNotificationFix - MIUI 本地通知限制修复
 *
 * <p>MIUI 会拦截本地通知（isAllowLocalNotification / isDeniedLocalNotification）。
 * 本模块 Hook 这两个方法之一，对 FCM 目标应用放行本地通知。
 */
public class MiuiLocalNotificationFix extends XposedModule {

    public MiuiLocalNotificationFix(XposedInterface api, ClassLoader classLoader) {
        super(api, classLoader);
        try {
            this.startHook();
        } catch (Throwable e) {
            printLog("hook error MiuiLocalNotificationFix:" + e.getMessage());
        }
    }

    protected void startHook() {
        try {
            Class<?> clazz;
            try {
                clazz = Reflect.findClass("com.android.server.notification.NotificationManagerServiceInjector", classLoader);
            } catch (Reflect.ClassNotFound e) {
                clazz = Reflect.findClass("com.android.server.notification.NotificationManagerServiceImpl", classLoader);
            }

            Method targetMethod = null;
            for (Method method : clazz.getDeclaredMethods()) {
                if ("isAllowLocalNotification".equals(method.getName())
                        || "isDeniedLocalNotification".equals(method.getName())) {
                    targetMethod = method;
                    break;
                }
            }
            if (targetMethod == null) {
                printLog("Not found [isAllowLocalNotification/isDeniedLocalNotification]");
                return;
            }

            final boolean isAllow = "isAllowLocalNotification".equals(targetMethod.getName());
            Hooks.hook(api, targetMethod, chain -> {
                Object result = chain.proceed();
                Object[] args = chain.getArgs().toArray();
                String pkg = args.length > 3 && args[3] instanceof String ? (String) args[3] : null;
                if (hasTargetPackage(pkg)) {
                    return isAllow;
                }
                return result;
            });
        } catch (Reflect.ClassNotFound e) {
            printLog("Not found [isAllowLocalNotification/isDeniedLocalNotification] in "
                    + "com.android.server.notification.[NotificationManagerServiceInjector/NotificationManagerServiceImpl]");
        }
    }
}
