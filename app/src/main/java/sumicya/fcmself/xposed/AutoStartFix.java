package sumicya.fcmself.xposed;

import android.content.Intent;

import sumicya.fcmself.util.XposedUtils;

import java.lang.reflect.Method;

import sumicya.fcmself.libxposed.XC_MethodHook;
import sumicya.fcmself.libxposed.XposedBridge;
import sumicya.fcmself.libxposed.XposedHelpers;

/**
 * AutoStartFix - 自启动修复模块
 *
 * 功能说明：
 * 解决各 ROM 系统对应用自启动的限制，允许 FCM/GCM 推送消息能够唤醒已停止的应用。
 * 主要针对不同 Android 版本和厂商定制系统（MIUI、HyperOS、ColorOS、OxygenOS）的自启动管理策略进行 Hook。
 *
 * 工作原理：
 * 1. Hook 系统广播发送前的检查方法，绕过自启动限制
 * 2. 当检测到 FCM 相关 Intent（或白名单目标）时，强制允许应用接收广播
 * 3. 支持多个系统版本的不同类和方法名（找不到对应类时静默跳过）
 *
 * 各 ROM 覆盖情况：
 * - MIUI 12：BroadcastQueueInjector.checkApplicationAutoStart
 * - MIUI 13：BroadcastQueueImpl.checkApplicationAutoStart、SmartPowerPolicyManager.shouldInterceptService
 * - HyperOS：BroadcastQueueModernStubImpl.checkApplicationAutoStart / checkReceiverIfRestricted
 * - MIUI(HyperOS?)：AutoStartManagerServiceStubImpl.isAllowStartService
 * - SmartPower：SmartPowerService.shouldInterceptBroadcast
 * - OOS 15 / ColorOS 15：OplusAppStartupManager.shouldPreventSendReceiverReal
 */
public class AutoStartFix extends XposedModule {

    public AutoStartFix(ClassLoader classLoader) {
        super(classLoader);
        try {
            // Hook 各 ROM 的自启动检查方法
            this.startHook();
            // Hook MIUI 电源策略，移除对 FCM 服务的拦截
            this.startHookRemovePowerPolicy();
        } catch (Throwable e) {
            printLog("hook error AutoStartFix:" + e.getMessage());
        }
    }

    /**
     * 开始 Hook 各个系统的自启动检查方法。
     * 每个 ROM 的 Hook 点独立 try/catch，单个点缺失不影响其它点。
     */
    protected void startHook() {
        // MIUI 12: BroadcastQueueInjector.checkApplicationAutoStart
        try {
            Class<?> clazz = XposedHelpers.findClass("com.android.server.am.BroadcastQueueInjector", classLoader);
            XposedUtils.findAndHookMethodAnyParam(clazz, "checkApplicationAutoStart", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                    Intent intent = intentOfField(methodHookParam.args[2]);
                    if (isFCMIntent(intent)) {
                        String target = targetOf(intent);
                        if (hasTargetPackage(target)) {
                            XposedHelpers.callStaticMethod(clazz, "checkAbnormalBroadcastInQueueLocked", methodHookParam.args[1], methodHookParam.args[0]);
                            printLog("Allow Auto Start: " + target, true);
                            methodHookParam.setResult(true);
                        }
                    }
                }
            });
        } catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError e) {
            printLog("No Such Method com.android.server.am.BroadcastQueueInjector.checkApplicationAutoStart");
        }

        // MIUI 13: BroadcastQueueImpl.checkApplicationAutoStart
        try {
            Class<?> clazz = XposedHelpers.findClass("com.android.server.am.BroadcastQueueImpl", classLoader);
            XposedUtils.findAndHookMethodAnyParam(clazz, "checkApplicationAutoStart", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                    Intent intent = intentOfField(methodHookParam.args[1]);
                    if (isFCMIntent(intent)) {
                        String target = targetOf(intent);
                        if (hasTargetPackage(target)) {
                            XposedHelpers.callMethod(methodHookParam.thisObject, "checkAbnormalBroadcastInQueueLocked", methodHookParam.args[0]);
                            printLog("Allow Auto Start: " + target, true);
                            methodHookParam.setResult(true);
                        }
                    }
                }
            });
        } catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError e) {
            printLog("No Such Method com.android.server.am.BroadcastQueueImpl.checkApplicationAutoStart");
        }

        // HyperOS: BroadcastQueueModernStubImpl 的两个方法
        try {
            Class<?> clazz = XposedHelpers.findClass("com.android.server.am.BroadcastQueueModernStubImpl", classLoader);
            printLog("[fcmself] start hook com.android.server.am.BroadcastQueueModernStubImpl.checkApplicationAutoStart");
            XposedUtils.findAndHookMethodAnyParam(clazz, "checkApplicationAutoStart", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                    Intent intent = intentOfField(methodHookParam.args[1]);
                    String target = targetOf(intent);
                    if (hasTargetPackage(target)) {
                        // 该方法拿不到 action 过滤信息，按白名单放行
                        printLog("[" + intent.getAction() + "]checkApplicationAutoStart package_name: " + target, true);
                        methodHookParam.setResult(true);
                    }
                }
            });

            printLog("[fcmself] start hook com.android.server.am.BroadcastQueueModernStubImpl.checkReceiverIfRestricted");
            XposedUtils.findAndHookMethodAnyParam(clazz, "checkReceiverIfRestricted", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                    Intent intent = intentOfField(methodHookParam.args[1]);
                    String target = targetOf(intent);
                    if (hasTargetPackage(target)) {
                        if (isFCMIntent(intent)) {
                            printLog("BroadcastQueueModernStubImpl.checkReceiverIfRestricted package_name: " + target, true);
                            methodHookParam.setResult(false);
                        }
                    }
                }
            });
        } catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError e) {
            printLog("No Such class com.android.server.am.BroadcastQueueModernStubImpl");
        }

        // AutoStartManagerServiceStubImpl.isAllowStartService（3 参 / 4 参签名均尝试）
        try {
            Class<?> clazz = XposedHelpers.findClass("com.android.server.am.AutoStartManagerServiceStubImpl", classLoader);
            XC_MethodHook methodHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                    Intent intent = (Intent) methodHookParam.args[1];
                    String target = targetOf(intent);
                    if (hasTargetPackage(target)) {
                        // 拿不到action，按白名单放行
                        printLog("[" + intent.getAction() + "]AutoStartManagerServiceStubImpl.isAllowStartService package_name: " + target, true);
                        methodHookParam.setResult(true);
                    }
                }
            };

            printLog("[fcmself] start hook com.android.server.am.AutoStartManagerServiceStubImpl.isAllowStartService");
            XC_MethodHook.Unhook unhook1 = XposedUtils.tryFindAndHookMethod(clazz, "isAllowStartService", 3, methodHook);
            XC_MethodHook.Unhook unhook2 = XposedUtils.tryFindAndHookMethod(clazz, "isAllowStartService", 4, methodHook);
            if (unhook1 == null && unhook2 == null) {
                throw new NoSuchMethodError();
            }
        } catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError e) {
            printLog("No Such Class com.android.server.am.AutoStartManagerServiceStubImpl.isAllowStartService");
        }

        // SmartPowerService.shouldInterceptBroadcast
        try {
            Class<?> clazz = XposedHelpers.findClass("com.android.server.am.SmartPowerService", classLoader);
            printLog("[fcmself] start hook com.android.server.am.SmartPowerService.shouldInterceptBroadcast");
            XposedUtils.findAndHookMethodAnyParam(clazz, "shouldInterceptBroadcast", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                    Intent intent = intentOfField(methodHookParam.args[1]);
                    String target = targetOf(intent);
                    if (hasTargetPackage(target)) {
                        if (isFCMIntent(intent)) {
                            printLog("SmartPowerService.shouldInterceptBroadcast package_name: " + target, true);
                            methodHookParam.setResult(false);
                        }
                    }
                }
            });
        } catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError e) {
            printLog("No Such Class com.android.server.am.SmartPowerService");
        }

        // OOS 15 / ColorOS 15: OplusAppStartupManager.shouldPreventSendReceiverReal
        try {
            Method method = XposedUtils.findMethod(
                    XposedHelpers.findClass("com.android.server.am.OplusAppStartupManager", classLoader),
                    "shouldPreventSendReceiverReal", 4);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                    if (methodHookParam.args[0] != null) {
                        Intent intent = intentOfField(methodHookParam.args[0]);
                        if (isFCMIntent(intent) && hasTargetPackage(intent.getPackage())) {
                            methodHookParam.setResult(false);
                        }
                    }
                }
            });
        } catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError e) {
            printLog("No Such Method com.android.server.am.OplusAppStartupManager.shouldPreventSendReceiverReal");
        }
    }

    /**
     * Hook MIUI 的电源策略管理器，移除对 FCM 服务的拦截（MIUI 13 SmartPowerPolicyManager）
     */
    protected void startHookRemovePowerPolicy() {
        try {
            Class<?> clazz = XposedHelpers.findClass("com.miui.server.smartpower.SmartPowerPolicyManager", classLoader);
            XposedUtils.findAndHookMethodAnyParam(clazz, "shouldInterceptService", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Intent intent = (Intent) param.args[0];
                    if ("com.google.firebase.MESSAGING_EVENT".equals(intent.getAction())) {
                        String target = targetOf(intent);
                        if (hasTargetPackage(target)) {
                            printLog("Disable MIUI Intercept: " + target, true);
                            param.setResult(false);
                        }
                    }
                }
            });
        } catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError e) {
            printLog("No Such Method com.miui.server.smartpower.SmartPowerPolicyManager.shouldInterceptService");
        }
    }
}
