package sumicya.fcmself.xposed;

import android.content.Intent;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

import sumicya.fcmself.util.Hooks;
import sumicya.fcmself.util.Reflect;

/**
 * AutoStartFix - 自启动限制修复
 *
 * <p>Hook 各 ROM 的自启动拦截点：当被拦截的广播是 FCM 消息时放行，允许已停止的应用被唤醒。
 * 覆盖 ColorOS / OxygenOS（OplusAppStartupManager）与 MIUI / HyperOS（BroadcastQueue*
 * 系列、AutoStartManagerServiceStubImpl、SmartPowerService / SmartPowerPolicyManager）。
 *
 * <p>每个 hook 点独立 try/catch：某台设备没有对应类/方法时只记录日志并跳过，不影响其它点。
 * 与其它 Fix 模块一致，本模块对所有 FCM 目标应用生效（无白名单）。
 */
public class AutoStartFix extends XposedModule {

    public AutoStartFix(XposedInterface api, ClassLoader classLoader) {
        super(api, classLoader);
        try {
            this.startHook();
        } catch (Throwable e) {
            printLog("hook error AutoStartFix:" + e.getMessage());
        }
    }

    protected void startHook() {
        hookMiui12();
        hookMiui13();
        hookHyperos();
        hookIsAllowStartService();
        hookSmartPower();
        hookOplus();
        hookMiuiPowerPolicy();
    }

    /** MIUI 12：BroadcastQueueInjector.checkApplicationAutoStart */
    private void hookMiui12() {
        try {
            Class<?> clazz = Reflect.findClass("com.android.server.am.BroadcastQueueInjector", classLoader);
            Method method = Reflect.findMethodMostParams(clazz, "checkApplicationAutoStart");
            if (method == null) {
                throw new NoSuchMethodError(clazz.getName() + "#checkApplicationAutoStart");
            }
            Hooks.hook(api, method, chain -> {
                Object[] args = chain.getArgs().toArray();
                Intent intent = args.length > 2 ? intentOfField(args[2]) : null;
                if (isFCMIntent(intent)) {
                    String target = targetOf(intent);
                    if (hasTargetPackage(target)) {
                        Reflect.callStaticMethod(clazz, "checkAbnormalBroadcastInQueueLocked", args[1], args[0]);
                        printLog("Allow Auto Start: " + target, true);
                        return true;
                    }
                }
                return chain.proceed();
            });
        } catch (Reflect.ClassNotFound | NoSuchMethodError e) {
            printLog("No Such Method com.android.server.am.BroadcastQueueInjector.checkApplicationAutoStart");
        }
    }

    /** MIUI 13：BroadcastQueueImpl.checkApplicationAutoStart */
    private void hookMiui13() {
        try {
            Class<?> clazz = Reflect.findClass("com.android.server.am.BroadcastQueueImpl", classLoader);
            Method method = Reflect.findMethodMostParams(clazz, "checkApplicationAutoStart");
            if (method == null) {
                throw new NoSuchMethodError(clazz.getName() + "#checkApplicationAutoStart");
            }
            Hooks.hook(api, method, chain -> {
                Object[] args = chain.getArgs().toArray();
                Intent intent = args.length > 1 ? intentOfField(args[1]) : null;
                if (isFCMIntent(intent)) {
                    String target = targetOf(intent);
                    if (hasTargetPackage(target)) {
                        Reflect.callMethod(chain.getThisObject(), "checkAbnormalBroadcastInQueueLocked", args[0]);
                        printLog("Allow Auto Start: " + target, true);
                        return true;
                    }
                }
                return chain.proceed();
            });
        } catch (Reflect.ClassNotFound | NoSuchMethodError e) {
            printLog("No Such Method com.android.server.am.BroadcastQueueImpl.checkApplicationAutoStart");
        }
    }

    /** HyperOS：BroadcastQueueModernStubImpl.checkApplicationAutoStart 与 checkReceiverIfRestricted */
    private void hookHyperos() {
        try {
            Class<?> clazz = Reflect.findClass("com.android.server.am.BroadcastQueueModernStubImpl", classLoader);

            Method autoStart = Reflect.findMethodMostParams(clazz, "checkApplicationAutoStart");
            if (autoStart != null) {
                Hooks.hook(api, autoStart, chain -> {
                    Object[] args = chain.getArgs().toArray();
                    Intent intent = args.length > 1 ? intentOfField(args[1]) : null;
                    if (intent != null && hasTargetPackage(targetOf(intent))) {
                        printLog("checkApplicationAutoStart package_name: " + targetOf(intent), true);
                        return true;
                    }
                    return chain.proceed();
                });
            }

            Method receiverRestricted = Reflect.findMethodMostParams(clazz, "checkReceiverIfRestricted");
            if (receiverRestricted != null) {
                Hooks.hook(api, receiverRestricted, chain -> {
                    Object[] args = chain.getArgs().toArray();
                    Intent intent = args.length > 1 ? intentOfField(args[1]) : null;
                    if (isFCMIntent(intent) && hasTargetPackage(targetOf(intent))) {
                        printLog("BroadcastQueueModernStubImpl.checkReceiverIfRestricted package_name: "
                                + targetOf(intent), true);
                        return false;
                    }
                    return chain.proceed();
                });
            }

            if (autoStart == null && receiverRestricted == null) {
                throw new NoSuchMethodError(clazz.getName());
            }
        } catch (Reflect.ClassNotFound | NoSuchMethodError e) {
            printLog("No Such class com.android.server.am.BroadcastQueueModernStubImpl");
        }
    }

    /** MIUI / HyperOS：AutoStartManagerServiceStubImpl.isAllowStartService（3 参或 4 参） */
    private void hookIsAllowStartService() {
        try {
            Class<?> clazz = Reflect.findClass("com.android.server.am.AutoStartManagerServiceStubImpl", classLoader);
            Method method = Reflect.findMethodByParamCount(clazz, "isAllowStartService", 4);
            if (method == null) {
                method = Reflect.findMethodByParamCount(clazz, "isAllowStartService", 3);
            }
            if (method == null) {
                throw new NoSuchMethodError(clazz.getName() + "#isAllowStartService");
            }
            Hooks.hook(api, method, chain -> {
                Object[] args = chain.getArgs().toArray();
                Intent intent = args.length > 1 && args[1] instanceof Intent ? (Intent) args[1] : null;
                if (intent != null && hasTargetPackage(targetOf(intent))) {
                    printLog("AutoStartManagerServiceStubImpl.isAllowStartService package_name: "
                            + targetOf(intent), true);
                    return true;
                }
                return chain.proceed();
            });
        } catch (Reflect.ClassNotFound | NoSuchMethodError e) {
            printLog("No Such Class com.android.server.am.AutoStartManagerServiceStubImpl.isAllowStartService");
        }
    }

    /** MIUI / HyperOS：SmartPowerService.shouldInterceptBroadcast */
    private void hookSmartPower() {
        try {
            Class<?> clazz = Reflect.findClass("com.android.server.am.SmartPowerService", classLoader);
            Method method = Reflect.findMethodMostParams(clazz, "shouldInterceptBroadcast");
            if (method == null) {
                throw new NoSuchMethodError(clazz.getName() + "#shouldInterceptBroadcast");
            }
            Hooks.hook(api, method, chain -> {
                Object[] args = chain.getArgs().toArray();
                Intent intent = args.length > 1 ? intentOfField(args[1]) : null;
                if (isFCMIntent(intent) && hasTargetPackage(targetOf(intent))) {
                    printLog("SmartPowerService.shouldInterceptBroadcast package_name: " + targetOf(intent), true);
                    return false;
                }
                return chain.proceed();
            });
        } catch (Reflect.ClassNotFound | NoSuchMethodError e) {
            printLog("No Such Class com.android.server.am.SmartPowerService");
        }
    }

    /** ColorOS 15 / OxygenOS 15：OplusAppStartupManager.shouldPreventSendReceiverReal */
    private void hookOplus() {
        try {
            Class<?> clazz = Reflect.findClass("com.android.server.am.OplusAppStartupManager", classLoader);
            Method method = Reflect.findMethodByParamCount(clazz, "shouldPreventSendReceiverReal", 4);
            if (method == null) {
                throw new NoSuchMethodError(clazz.getName() + "#shouldPreventSendReceiverReal");
            }
            Hooks.hook(api, method, chain -> {
                Object holder = chain.getArg(0);
                if (holder != null) {
                    Intent intent = intentOfField(holder);
                    if (isFCMIntent(intent) && hasTargetPackage(intent.getPackage())) {
                        // 不调用 chain.proceed()，直接返回 false = 放行这条广播
                        return false;
                    }
                }
                return chain.proceed();
            });
        } catch (Reflect.ClassNotFound | NoSuchMethodError e) {
            printLog("No Such Method com.android.server.am.OplusAppStartupManager.shouldPreventSendReceiverReal");
        }
    }

    /** MIUI 13：SmartPowerPolicyManager.shouldInterceptService */
    private void hookMiuiPowerPolicy() {
        try {
            Class<?> clazz = Reflect.findClass("com.miui.server.smartpower.SmartPowerPolicyManager", classLoader);
            Method method = Reflect.findMethodMostParams(clazz, "shouldInterceptService");
            if (method == null) {
                throw new NoSuchMethodError(clazz.getName() + "#shouldInterceptService");
            }
            Hooks.hook(api, method, chain -> {
                Object result = chain.proceed();
                Object arg0 = chain.getArg(0);
                if (arg0 instanceof Intent && "com.google.firebase.MESSAGING_EVENT".equals(((Intent) arg0).getAction())) {
                    String target = targetOf((Intent) arg0);
                    if (hasTargetPackage(target)) {
                        printLog("Disable MIUI Intercept: " + target, true);
                        return false;
                    }
                }
                return result;
            });
        } catch (Reflect.ClassNotFound | NoSuchMethodError e) {
            printLog("No Such Method com.miui.server.smartpower.SmartPowerPolicyManager.shouldInterceptService");
        }
    }
}
