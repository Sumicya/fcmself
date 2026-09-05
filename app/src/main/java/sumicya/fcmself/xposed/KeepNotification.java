package sumicya.fcmself.xposed;

import android.os.Build;
import android.service.notification.NotificationListenerService;

import java.lang.reflect.Method;

import sumicya.fcmself.config.FcmselfConfig;
import sumicya.fcmself.libxposed.XC_MethodHook;
import sumicya.fcmself.libxposed.XposedBridge;
import sumicya.fcmself.libxposed.XposedHelpers;

/**
 * 通知保持模块 - 防止系统自动清除 FCM 通知
 *
 * 功能说明：
 * 部分 Android 系统会在应用未启动或后台运行时自动清除其通知，导致用户无法看到 FCM 推送。
 * 本模块通过 Hook 系统的通知管理服务，阻止系统自动清除目标应用的通知。
 *
 * 工作原理：
 * 1. 查找 NotificationManagerService 类中的 cancelAllNotificationsInt 方法
 * 2. 根据不同 Android 版本确定参数位置（包名和原因代码）
 * 3. 当检测到目标应用且原因是 PACKAGE_CHANGED（或 ColorOS/OxygenOS 特定原因代码）时，
 *    阻止取消通知
 *
 * 支持的 Android 版本：
 * - Android 10 (API 29) 到 Android 15 (API 35) 及以上
 * - 自动适配不同版本的方法签名变化
 *
 * 配置项：
 * - disableAutoCleanNotification: 启用后阻止系统自动清除通知
 */
public class KeepNotification extends XposedModule {

    /** ColorOS 15 / OxygenOS 15 特有的取消原因代码 */
    private static final int REASON_COS_OOS_1 = 10020;
    private static final int REASON_COS_OOS_2 = 10021;

    public KeepNotification(ClassLoader classLoader) {
        super(classLoader);
        try {
            this.startHook();
        } catch (Throwable e) {
            printLog("No Such Method com.android.server.notification.NotificationManagerService.cancelAllNotificationsInt");
        }
    }

    /**
     * 开始 Hook 操作：Hook 通知取消方法，防止系统自动清除目标应用的通知
     *
     * @throws NoSuchMethodError                        当找不到目标方法时抛出
     * @throws XposedHelpers.ClassNotFoundError         当找不到目标类时抛出
     */
    protected void startHook() throws NoSuchMethodError, XposedHelpers.ClassNotFoundError {
        // 查找通知管理服务类
        Class<?> clazz = XposedHelpers.findClass("com.android.server.notification.NotificationManagerService", classLoader);
        final Method[] declareMethods = clazz.getDeclaredMethods();
        Method targetMethod = null;

        // 查找 cancelAllNotificationsInt 方法，选择参数最多的版本（通常是最新的）
        for (Method method : declareMethods) {
            if ("cancelAllNotificationsInt".equals(method.getName())) {
                if (targetMethod == null || targetMethod.getParameterTypes().length < method.getParameterTypes().length) {
                    targetMethod = method;
                }
            }
        }

        if (targetMethod == null) {
            throw new NoSuchMethodError();
        }

        int pkgArgsIndex;
        int reasonArgsIndex;
        // 各版本参数下标：包名恒为 2，原因代码随版本/签名变化
        if (Build.VERSION.SDK_INT >= 30 && Build.VERSION.SDK_INT <= 33) {
            pkgArgsIndex = 2;
            reasonArgsIndex = 8;
        } else if (Build.VERSION.SDK_INT == 34) {
            if (targetMethod.getParameterTypes().length == 10) {
                pkgArgsIndex = 2;
                reasonArgsIndex = 8;
            } else if (targetMethod.getParameterTypes().length == 8) {
                pkgArgsIndex = 2;
                reasonArgsIndex = 7;
            } else {
                throw new NoSuchMethodError();
            }
        } else if (Build.VERSION.SDK_INT >= 35) {
            pkgArgsIndex = 2;
            reasonArgsIndex = 7;
        } else {
            throw new NoSuchMethodError();
        }

        final int finalPkgArgsIndex = pkgArgsIndex;
        final int finalReasonArgsIndex = reasonArgsIndex;

        // Hook 目标方法
        XposedBridge.hookMethod(targetMethod, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                // 系统启动完成前不介入
                if (!FcmselfConfig.isBootComplete()) {
                    return;
                }
                // 启用了"禁用自动清理通知"且是白名单目标应用
                if (getBooleanConfig(FcmselfConfig.KEY_DISABLE_AUTO_CLEAN_NOTIFICATION, false)
                        && targetIsAllow((String) param.args[finalPkgArgsIndex])) {
                    int reason = (int) param.args[finalReasonArgsIndex];

                    // 原因是应用包变化（如更新/卸载）：阻止取消通知
                    if (reason == NotificationListenerService.REASON_PACKAGE_CHANGED
                            // ColorOS 15 / OxygenOS 15 的特定原因代码
                            || reason == REASON_COS_OOS_1
                            || reason == REASON_COS_OOS_2) {
                        param.setResult(null);
                    }
                }
            }
        });
    }
}
