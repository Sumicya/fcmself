package sumicya.fcmself.xposed;

import android.content.Intent;
import android.os.Build;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import sumicya.fcmself.config.FcmselfConfig;
import sumicya.fcmself.libxposed.XC_MethodHook;
import sumicya.fcmself.libxposed.XposedBridge;
import sumicya.fcmself.util.IceboxUtils;
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
 * 3. 目标被 IceBox 冻结且开启对应开关时：先解冻、再补发广播；
 * 4. ColorOS：调用 OplusProxyFix.unfreeze 解除 OplusProxy 冻结。
 */
public class BroadcastFix extends XposedModule {

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
            appOpIndex = findIntParamIndex(parameters, 11, 12);
        } else if (Build.VERSION.SDK_INT == 33) {
            intentIndex = 3;
            appOpIndex = 12;
        } else if (Build.VERSION.SDK_INT == 34) {
            intentIndex = 3;
            appOpIndex = findIntParamIndex(parameters, 12, 13);
        } else if (Build.VERSION.SDK_INT >= 35) {
            intentIndex = 3;
            appOpIndex = findIntParamIndex(parameters, 12, 13);
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
        Parameter[] parameters = targetMethod.getParameters();
        if (intentIndex < parameters.length && appOpIndex < parameters.length
                && parameters[intentIndex].getType() == Intent.class
                && parameters[appOpIndex].getType() == int.class) {
            return new int[]{intentIndex, appOpIndex};
        }
        int byNameIntent = -1;
        int byNameAppOp = -1;
        for (int i = 0; i < parameters.length; i++) {
            if ("intent".equals(parameters[i].getName()) && parameters[i].getType() == Intent.class) {
                byNameIntent = i;
            }
            if ("appOp".equals(parameters[i].getName()) && parameters[i].getType() == int.class) {
                byNameAppOp = i;
            }
        }
        if (byNameIntent < 0 || byNameAppOp < 0) {
            printLog("broadcastIntentLocked 参数位置无法确定（API " + Build.VERSION.SDK_INT
                    + "，参数个数 " + parameters.length + "）");
            return null;
        }
        printLog("broadcastIntentLocked 硬编码下标失效，改用参数名定位：intent@" + byNameIntent
                + " appOp@" + byNameAppOp);
        return new int[]{byNameIntent, byNameAppOp};
    }

    /** 返回 candidates 中第一个 int 类型参数下标，都没有则返回 -1。 */
    private static int findIntParamIndex(Parameter[] parameters, int... candidates) {
        for (int i : candidates) {
            if (i < parameters.length && parameters[i].getType() == int.class) {
                return i;
            }
        }
        return -1;
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
                        if (appOp == -1) {
                            methodHookParam.args[finalAppOp_args_index] = 11;
                        }
                        intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                        if (IceboxUtils.isInstalled(context) && !IceboxUtils.isAppEnabled(context, target)) {
                            // 目标被 IceBox 冻结：先解冻等待，再重新走原方法补发广播
                            printLog("Waiting for IceBox to activate the app: " + target, true);
                            methodHookParam.setResult(false);
                            new Thread(() -> resumeAfterIceboxActivated(methodHookParam, method, target)).start();
                        } else {
                            printLog("Add FLAG_INCLUDE_STOPPED_PACKAGES: " + target, true);
                        }
                        // cos15 解冻 OplusProxy
                        OplusProxyFix.unfreeze(target);
                    }
                }
            }
        });
    }

    /**
     * IceBox 冻结目标的补发逻辑：
     * 激活应用（最长 30s）后，用当前参数重新调用一次原 broadcastIntentLocked。
     */
    private void resumeAfterIceboxActivated(XC_MethodHook.MethodHookParam methodHookParam, Method method, String target) {
        IceboxUtils.activeApp(context, target);
        for (int i = 0; i < 300; i++) {
            if (IceboxUtils.isAppEnabled(context, target)) {
                break;
            }
            try {
                Thread.sleep(100);
            } catch (Throwable e) {
                printLog("Waiting for IceBox interrupted: " + target + " " + e.getMessage(), true);
            }
        }
        try {
            if (IceboxUtils.isAppEnabled(context, target)) {
                printLog("Resend broadcast after IceBox activated: " + target, true);
            } else {
                printLog("Waiting for IceBox to activate the app timed out: " + target, true);
            }
            XposedBridge.invokeOriginalMethod(methodHookParam.method, methodHookParam.thisObject, methodHookParam.args);
        } catch (Throwable e) {
            printLog("Resend broadcast failed: " + target + " " + e.getMessage(), true);
        }
    }
}
