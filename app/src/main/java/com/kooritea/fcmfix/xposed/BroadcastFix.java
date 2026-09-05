package com.kooritea.fcmfix.xposed;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import com.kooritea.fcmfix.config.FcmfixConfig;
import com.kooritea.fcmfix.libxposed.XC_MethodHook;
import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.libxposed.XposedHelpers;
import com.kooritea.fcmfix.util.IceboxUtils;
import com.kooritea.fcmfix.util.XposedUtils;

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
        // 实验性 Hook（依赖 BroadcastQueueModernImpl.scheduleResultTo 内部结构），默认禁用：
        // try {
        //     startHookScheduleResultTo();
        // } catch (Throwable e) {
        //     printLog("hook error com.android.server.am.BroadcastQueueModernImpl.scheduleResultTo:" + e.getMessage());
        // }
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
                argsIndex = new int[]{3, 13};
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
                && targetMethod.getParameters()[argsIndex[0]].getType() == Intent.class
                && targetMethod.getParameters()[argsIndex[1]].getType() == int.class) {
            createBroadcastIntentLockedHooker(argsIndex[0], argsIndex[1], targetMethod);
        } else {
            printLog("broadcastIntentLocked hook 位置查找失败，fcmfix将不会工作。");
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
                if (!FcmfixConfig.isBootComplete()) {
                    return;
                }
                if (methodHookParam.args[finalIntent_args_index] == null) {
                    return;
                }
                Intent intent = (Intent) methodHookParam.args[finalIntent_args_index];
                // 介入条件：Intent未包含唤醒停止的pkg 且 Intent是FCM
                if ((intent.getFlags() & Intent.FLAG_INCLUDE_STOPPED_PACKAGES) == 0 && isFCMIntent(intent)) {
                    String target = targetOf(intent);
                    if (targetIsAllow(target)) {
                        int appOp = (Integer) methodHookParam.args[finalAppOp_args_index];
                        if (appOp == -1) {
                            methodHookParam.args[finalAppOp_args_index] = 11;
                        }
                        intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                        if (getBooleanConfig("includeIceBoxDisableApp", false) && !IceboxUtils.isAppEnabled(context, target)) {
                            // 目标被 IceBox 冻结：先解冻等待，再重新走原方法补发广播
                            printLog("Waiting for IceBox to activate the app: " + target, true);
                            methodHookParam.setResult(false);
                            new Thread(() -> resumeAfterIceboxActivated(methodHookParam, method, target)).start();
                        } else {
                            printLog("Send Forced Start Broadcast: " + target, true);
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
                printLog("Send Forced Start Broadcast Error: " + target + " " + e.getMessage(), true);
            }
        }
        try {
            if (IceboxUtils.isAppEnabled(context, target)) {
                printLog("Send Forced Start Broadcast: " + target, true);
            } else {
                printLog("Waiting for IceBox to activate the app timed out: " + target, true);
            }
            XposedBridge.invokeOriginalMethod(methodHookParam.method, methodHookParam.thisObject, methodHookParam.args);
        } catch (Throwable e) {
            printLog("Send Forced Start Broadcast Error: " + target + " " + e.getMessage(), true);
        }
    }

    /**
     * 实验性 Hook：监听广播结果回送，目标无响应且开启 noResponseNotification 时代发通知。
     * 依赖 BroadcastQueueModernImpl 内部结构，ROM 升级后易失效，默认未启用。
     */
    protected void startHookScheduleResultTo() {
        Method method = XposedUtils.findMethod(
                XposedHelpers.findClass("com.android.server.am.BroadcastQueueModernImpl", classLoader),
                "scheduleResultTo", 1);
        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                if (!FcmfixConfig.isBootComplete()) {
                    return;
                }
                if (methodHookParam.args[0] == null || XposedHelpers.getObjectField(methodHookParam.args[0], "resultTo") == null
                        || XposedHelpers.getObjectField(methodHookParam.args[0], "intent") == null
                        || XposedHelpers.getObjectField(methodHookParam.args[0], "resultCode") == null) {
                    return;
                }
                Intent intent = (Intent) XposedHelpers.getObjectField(methodHookParam.args[0], "intent");
                int resultCode = (int) XposedHelpers.getObjectField(methodHookParam.args[0], "resultCode");
                String packageName = intent.getPackage();
                if (resultCode != -1 && getBooleanConfig("noResponseNotification", false) && targetIsAllow(packageName)) {
                    sendNoResponseNotification(packageName);
                }
            }
        });
    }

    private void sendNoResponseNotification(String packageName) {
        try {
            Intent notifyIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);
            if (notifyIntent != null) {
                notifyIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                PendingIntent pendingIntent = PendingIntent.getActivity(
                        context, 0, notifyIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
                createFcmfixChannel(notificationManager);
                NotificationCompat.Builder notification = new NotificationCompat.Builder(context, "fcmfix")
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle("FCM Message")
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT);
                Bitmap icon = getAppIcon(packageName);
                if (icon != null) {
                    notification.setLargeIcon(icon);
                }
                notification.setContentIntent(pendingIntent).setAutoCancel(true);
                notificationManager.notify((int) System.currentTimeMillis(), notification.build());
            } else {
                printLog("无法获取目标应用active: " + packageName, false);
            }
        } catch (Throwable e) {
            printLog(e.getMessage(), false);
        }
    }

    private static Bitmap getAppIcon(String packageName) {
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            Drawable drawable = pm.getApplicationIcon(appInfo);
            if (drawable instanceof BitmapDrawable) {
                return ((BitmapDrawable) drawable).getBitmap();
            }
            Bitmap bitmap = Bitmap.createBitmap(
                    drawable.getIntrinsicWidth(),
                    drawable.getIntrinsicHeight(),
                    Bitmap.Config.ARGB_8888);
            drawable.setBounds(0, 0, bitmap.getWidth(), bitmap.getHeight());
            drawable.draw(new Canvas(bitmap));
            return bitmap;
        } catch (Throwable e) {
            return null;
        }
    }
}
