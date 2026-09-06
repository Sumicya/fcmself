package sumicya.fcmself.xposed;

import android.os.Build;
import android.service.notification.NotificationListenerService;

import java.lang.reflect.Method;
import java.util.Arrays;

import sumicya.fcmself.config.FcmselfConfig;
import sumicya.fcmself.util.Hooks;
import sumicya.fcmself.util.MethodArgs;
import sumicya.fcmself.util.Reflect;

import io.github.libxposed.api.XposedInterface;

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
 * 生效条件（无配置项，对所有 FCM 目标应用始终生效）：
 * 取消原因为 {@code REASON_PACKAGE_CHANGED}，或 ColorOS 15 / OxygenOS 15 的
 * {@value #REASON_COS_OOS_1} / {@value #REASON_COS_OOS_2}。其它取消原因照常放行。
 */
public class KeepNotification extends XposedModule {

    /** ColorOS 15 / OxygenOS 15 特有的取消原因代码 */
    private static final int REASON_COS_OOS_1 = 10020;
    private static final int REASON_COS_OOS_2 = 10021;

    public KeepNotification(XposedInterface api, ClassLoader classLoader) {
        super(api, classLoader);
        try {
            this.startHook();
        } catch (Throwable e) {
            printLog("No Such Method com.android.server.notification.NotificationManagerService.cancelAllNotificationsInt");
        }
    }

    /**
     * 开始 Hook 操作：Hook 通知取消方法，防止系统自动清除目标应用的通知
     *
     * @throws NoSuchMethodError      当找不到目标方法时抛出
     * @throws Reflect.ClassNotFound  当找不到目标类时抛出
     */
    protected void startHook() throws NoSuchMethodError, Reflect.ClassNotFound {
        // 查找通知管理服务类
        Class<?> clazz = Reflect.findClass("com.android.server.notification.NotificationManagerService", classLoader);

        // 查找 cancelAllNotificationsInt 方法，选择参数最多的版本（通常是最新的）
        Method targetMethod = Reflect.findMethodMostParams(clazz, "cancelAllNotificationsInt");
        if (targetMethod == null) {
            throw new NoSuchMethodError(clazz.getName() + "#cancelAllNotificationsInt");
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

        // 下标是按系统版本硬编码的猜测值，ROM 或新版本系统可能改变签名。挂错下标会在
        // system_server 里抛 ClassCastException，或把无关的通知取消一并拦下，因此先按真实
        // 签名校验；不符就放弃这个 Hook（其它模块不受影响），并打出可供排查的签名信息。
        Class<?>[] paramTypes = targetMethod.getParameterTypes();
        if (!MethodArgs.matches(paramTypes, pkgArgsIndex, reasonArgsIndex)) {
            printLog("cancelAllNotificationsInt 签名与预期不符，已跳过该 Hook 以免误拦截通知："
                    + "API " + Build.VERSION.SDK_INT + "，参数=" + Arrays.toString(paramTypes)
                    + "，预期 pkg@" + pkgArgsIndex + "(String) reason@" + reasonArgsIndex + "(int)");
            return;
        }
        printLog("cancelAllNotificationsInt hook 参数：pkg@" + pkgArgsIndex
                + " reason@" + reasonArgsIndex + "（API " + Build.VERSION.SDK_INT + "）");

        // lambda 里只能引用 effectively final 的局部变量，两个下标在上面的分支里赋值过，
        // 这里复制成 final 再捕获
        final int pkgIndex = pkgArgsIndex;
        final int reasonIndex = reasonArgsIndex;

        // Hook 目标方法
        Hooks.hook(api, targetMethod, chain -> {
            // 系统启动完成前不介入
            if (!FcmselfConfig.isBootComplete()) {
                return chain.proceed();
            }
            // 目标包名可解析即介入：阻止系统因应用包变化自动清理其通知
            if (hasTargetPackage((String) chain.getArg(pkgIndex))) {
                int reason = (int) chain.getArg(reasonIndex);

                // 原因是应用包变化（如更新/卸载）：阻止取消通知
                if (reason == NotificationListenerService.REASON_PACKAGE_CHANGED
                        // ColorOS 15 / OxygenOS 15 的特定原因代码
                        || reason == REASON_COS_OOS_1
                        || reason == REASON_COS_OOS_2) {
                    // 不调用 chain.proceed()：直接返回，等于这次取消请求被忽略
                    return null;
                }
            }
            return chain.proceed();
        });
    }
}
