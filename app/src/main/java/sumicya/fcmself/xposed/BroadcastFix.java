package sumicya.fcmself.xposed;

import android.content.Intent;
import android.os.Build;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import sumicya.fcmself.config.FcmselfConfig;
import sumicya.fcmself.libxposed.XC_MethodHook;
import sumicya.fcmself.util.MethodArgs;
import sumicya.fcmself.libxposed.XposedBridge;
import sumicya.fcmself.util.XposedUtils;

/**
 * BroadcastFix - 广播修复模块
 *
 * 功能说明：
 * Hook 系统广播发送流程，确保 FCM/GCM 消息能够正确送达目标应用。
 * 主要解决系统阻止后台应用接收广播的问题（"Failed to broadcast to stopped app"）。
 *
 * 工作原理：
 * 1. Hook broadcastIntentLocked 方法（系统广播发送核心入口，API 29-35 各版本签名不同，
 *    自动定位 intent 与 appOp 参数下标）；
 * 2. 检测到 FCM Intent 且目标在白名单时，强制添加 FLAG_INCLUDE_STOPPED_PACKAGES
 *    并把 appOp 从 -1 改为 11（正常）；
 * 4. ColorOS：调用 OplusProxyFix.unfreeze 解除 OplusProxy 冻结。
 */
public class BroadcastFix extends XposedModule {

    /**
     * AOSP {@code AppOpsManager.OP_NONE}（值 -1）。该常量是 @hide，公开 SDK 里没有，
     * 只能自己定义。表示 broadcastIntentLocked 的调用方未指定 appOp。
     */
    private static final int APP_OP_NONE = -1;

    /**
     * AOSP {@code AppOpsManager.OP_POST_NOTIFICATION}（值 11，同为 @hide 常量；
     * 已对照 AOSP android-9.0.0_r34 与 prebuilts android-29 核实）。
     *
     * <p>调用方未指定 appOp（{@link #APP_OP_NONE}）时补成这个值，让这条广播按
     * "投递通知"来对待，从而允许送达已停止的应用。
     */
    private static final int APP_OP_POST_NOTIFICATION = 11;

    public BroadcastFix(ClassLoader classLoader) {
        super(classLoader);
        try {
            startHookBroadcastIntentLocked();
        } catch (Throwable e) {
            printLog("hook error broadcastIntentLocked:" + e.getMessage());
        }
    }

    /**
     * Hook broadcastIntentLocked 方法
     * 该方法是 Android 系统发送广播的核心入口点
     */
    protected void startHookBroadcastIntentLocked() {
        Method targetMethod = null;
        int[] argsIndex = null;

        // Android 15+：广播逻辑移到 BroadcastController
        if (Build.VERSION.SDK_INT >= 35) {
            Method m = XposedUtils.tryFindMethodMostParam(classLoader, "com.android.server.am.BroadcastController", "broadcastIntentLocked");
            if (m != null) {
                targetMethod = m;
                argsIndex = resolveBroadcastArgs(m, 3, 13);
            }
        }
        // Android 10-14：仍在 ActivityManagerService
        if (targetMethod == null) {
            targetMethod = XposedUtils.tryFindMethodMostParam(classLoader, "com.android.server.am.ActivityManagerService", "broadcastIntentLocked");
            if (targetMethod != null) {
                argsIndex = resolveAmsBroadcastArgs(targetMethod);
            }
        }

        if (targetMethod != null && argsIndex != null
                && argsIndex[0] < targetMethod.getParameters().length
                && argsIndex[1] < targetMethod.getParameters().length
                && targetMethod.getParameters()[argsIndex[0]].getType() == Intent.class
                && targetMethod.getParameters()[argsIndex[1]].getType() == int.class) {
            createBroadcastIntentLockedHooker(argsIndex[0], argsIndex[1], targetMethod);
        } else {
            printLog("broadcastIntentLocked hook 位置查找失败，fcmself将不会工作。");
        }
    }

    /**
     * 按系统版本解析 broadcastIntentLocked 的 (intent, appOp) 参数下标。
     * 优先按版本硬编码；版本未知时按参数名/类型兜底（部分混淆系统参数名可能失效）。
     *
     * @return int[]{intentIndex, appOpIndex}，无法确定时返回 null
     */
    private static int[] resolveAmsBroadcastArgs(Method targetMethod) {
        Parameter[] parameters = targetMethod.getParameters();
        int intentIndex = 0;
        int appOpIndex = 0;

        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            intentIndex = 2;
            appOpIndex = 9;
        } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
            intentIndex = 3;
            appOpIndex = 10;
        } else if (Build.VERSION.SDK_INT == 31 || Build.VERSION.SDK_INT == 32) {
            intentIndex = 3;
            appOpIndex = MethodArgs.firstIntIndex(parameters, 11, 12);
        } else if (Build.VERSION.SDK_INT == 33) {
            intentIndex = 3;
            appOpIndex = 12;
        } else if (Build.VERSION.SDK_INT == 34) {
            intentIndex = 3;
            appOpIndex = MethodArgs.firstIntIndex(parameters, 12, 13);
        } else if (Build.VERSION.SDK_INT >= 35) {
            intentIndex = 3;
            appOpIndex = MethodArgs.firstIntIndex(parameters, 12, 13);
        }

        if (intentIndex == 0 || appOpIndex == 0) {
            // 根据参数名称查找，部分经过混淆的系统无效
            intentIndex = 0;
            appOpIndex = 0;
            for (int i = 0; i < parameters.length; i++) {
                if ("appOp".equals(parameters[i].getName()) && parameters[i].getType() == int.class) {
                    appOpIndex = i;
                }
                if ("intent".equals(parameters[i].getName()) && parameters[i].getType() == Intent.class) {
                    intentIndex = i;
                }
            }
        }

        return (intentIndex == 0 || appOpIndex == 0) ? null : new int[]{intentIndex, appOpIndex};
    }

    /**
     * 校验 BroadcastController.broadcastIntentLocked 的 (intent, appOp) 下标是否与签名相符。
     * Android 15+ 走硬编码下标；下标失效（新系统改了签名）时按参数名兜底，都不行则返回 null，
     * 由调用方打出"hook 位置查找失败"并跳过。
     */
    private static int[] resolveBroadcastArgs(Method targetMethod, int intentIndex, int appOpIndex) {
        Class<?>[] paramTypes = targetMethod.getParameterTypes();
        if (MethodArgs.matches(paramTypes, intentIndex, Intent.class, appOpIndex)) {
            return new int[]{intentIndex, appOpIndex};
        }
        int[] byName = MethodArgs.byName(targetMethod.getParameters(), Intent.class);
        if (byName == null) {
            printLog("broadcastIntentLocked 参数位置无法确定（API " + Build.VERSION.SDK_INT
                    + "，参数个数 " + paramTypes.length + "）");
            return null;
        }
        printLog("broadcastIntentLocked 硬编码下标失效，改用参数名定位：intent@" + byName[0]
                + " appOp@" + byName[1]);
        return byName;
    }

    protected void createBroadcastIntentLockedHooker(int intent_args_index, int appOp_args_index, Method method) {
        printLog("Android API: " + Build.VERSION.SDK_INT);
        printLog("appOp_args_index: " + appOp_args_index);
        printLog("intent_args_index: " + intent_args_index);
        printLog("hook target: " + method.getDeclaringClass().getName());
        final int finalIntent_args_index = intent_args_index;
        final int finalAppOp_args_index = appOp_args_index;

        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                if (!FcmselfConfig.isBootComplete()) {
                    return;
                }
                if (methodHookParam.args[finalIntent_args_index] == null) {
                    return;
                }
                Intent intent = (Intent) methodHookParam.args[finalIntent_args_index];
                // 介入条件：Intent未包含唤醒停止的pkg 且 Intent是FCM
                if ((intent.getFlags() & Intent.FLAG_INCLUDE_STOPPED_PACKAGES) == 0 && isFCMIntent(intent)) {
                    String target = targetOf(intent);
                    if (hasTargetPackage(target)) {
                        int appOp = (Integer) methodHookParam.args[finalAppOp_args_index];
                        if (appOp == APP_OP_NONE) {
                            methodHookParam.args[finalAppOp_args_index] = APP_OP_POST_NOTIFICATION;
                        }
                        intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                        printLog("Add FLAG_INCLUDE_STOPPED_PACKAGES: " + target, true);
                        // cos15 解冻 OplusProxy
                        OplusProxyFix.unfreeze(target);
                    }
                }
            }
        });
    }

}
