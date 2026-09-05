package com.kooritea.fcmfix.xposed;

import android.content.pm.PackageManager;
import android.os.WorkSource;

import com.kooritea.fcmfix.libxposed.XC_MethodHook;
import com.kooritea.fcmfix.libxposed.XC_MethodReplacement;
import com.kooritea.fcmfix.libxposed.XposedHelpers;
import com.kooritea.fcmfix.util.XposedUtils;

/**
 * OPPO/OnePlus ColorOS 专用 FCM 修复模块
 * 主要功能：
 * 1. 绕过 OplusProxyWakeLock 冻结机制
 * 2. 阻止 OplusProxyBroadcast 拦截 FCM 广播
 * 3. 禁用 Hans 后台管理系统对 GMS 的限制
 * 
 * 配合 fcmfix 可实现无代理直接接收 FCM 消息
 */
public class OplusProxyFix extends XposedModule {

    private static Object s_oplusProxyWakeLock = null;
    private static volatile boolean s_useFourParams = false;
    private static volatile boolean s_signatureDetected = false;

    public OplusProxyFix(ClassLoader classLoader) {
        super(classLoader);
        
        // Hook OplusProxyWakeLock 和 OplusProxyBroadcast
        try {
            startHookOplusProxyWakeLock();
            startHookOplusProxyBroadcast();
        } catch (Throwable e) {
            printLog("hook error OplusProxy: " + e.getMessage());
        }
        
        // 阻止 Hans 监听 GMS 状态更新
        try {
            startHookRegisterGmsRestrictObserver();
        } catch (Throwable e) {
            printLog("hook error registerGmsRestrictObserver: " + e.getMessage());
        }
        
        // 拦截 Hans 更新 GMS 限制状态
        try {
            startHookUpdateGmsRestrict();
        } catch (Throwable e) {
            printLog("hook error updateGmsRestrict: " + e.getMessage());
        }
        
        // 阻止判断 GMS 限制
        try {
            startHookIsGoogleRestricInfoOn();
        } catch (Throwable e) {
            printLog("hook error isGoogleRestricInfoOn: " + e.getMessage());
        }
        
        // 可选：阻止 Hans 对 GMS 进行特殊处理（默认禁用）
        // try {
        //     startHookIsGmsApp();
        // } catch (Throwable e) {
        //     printLog("hook error isGmsApp: " + e.getMessage());
        // }
    }

    /**
     * Hook OplusProxyBroadcast.shouldProxy 方法
     * 当检测到 FCM 相关广播时，返回 NOT_INCLUDE 以绕过代理检查
     */
    private void startHookOplusProxyBroadcast() throws Exception {
        Class<?> oplusProxyBroadcastClass = XposedHelpers.findClass(
            "com.android.server.am.OplusProxyBroadcast", classLoader);
        Class<?> resultEnum = XposedHelpers.findClass(
            "com.android.server.am.OplusProxyBroadcast$RESULT", classLoader);
        Object notIncludeValue = XposedHelpers.getStaticObjectField(resultEnum, "NOT_INCLUDE");

        // ColorOS 15 测试：shouldProxy 有 8 个参数
        XposedUtils.findAndHookMethod(oplusProxyBroadcastClass, "shouldProxy", 8, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                String callingPkg = (String) param.args[3];
                String pkgName = (String) param.args[5];
                String action = (String) param.args[6];
                
                // 示例：caller=com.google.android.gms, action=com.google.android.c2dm.intent.RECEIVE
                if (isFCMAction(action) && targetIsAllow(pkgName)) {
                    printLog("shouldProxy bypass: pkg=" + pkgName + 
                             ", caller=" + callingPkg + 
                             ", action=" + action);
                    param.setResult(notIncludeValue);
                }
            }
        });
    }

    /**
     * Hook OplusProxyWakeLock 构造函数，保存实例引用供后续使用
     */
    private void startHookOplusProxyWakeLock() throws Exception {
        Class<?> oplusWakelockClass = XposedHelpers.findClass(
            "com.android.server.power.OplusProxyWakeLock", classLoader);

        XposedUtils.findAndHookConstructorAnyParam(oplusWakelockClass, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (s_oplusProxyWakeLock != null) {
                    printLog("warn: OplusProxyWakeLock constructed multiple times!");
                    return;
                }
                s_oplusProxyWakeLock = param.thisObject;
                printLog("OplusProxyWakeLock instance captured");
            }
        });
    }

    /**
     * 根据包名获取对应的 UID
     */
    private static int getTargetUidFromPackageName(String packageName) {
        if (packageName == null) {
            return -1;
        }
        try {
            PackageManager pm = context.getPackageManager();
            return pm.getPackageUid(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            printLog("error: Package not found: " + packageName);
            return -1;
        }
    }

    /**
     * 解冻指定包名的应用
     * 自动检测并使用正确的参数数量（3 参或 4 参）
     * 
     * @param target 目标应用包名
     */
    public static void unfreeze(String target) {
        if (s_oplusProxyWakeLock == null) {
            return;
        }

        int uid = getTargetUidFromPackageName(target);
        if (uid < 0) {
            return;
        }

        WorkSource ws = new WorkSource();
        String tag = "FCMXX";

        // 首次调用时检测签名
        if (!s_signatureDetected) {
            try {
                // 尝试 4 参数版本
                XposedHelpers.callMethod(s_oplusProxyWakeLock, "unfreezeIfNeed", 
                    uid, ws, tag, "FCMFix");
                s_useFourParams = true;
                printLog("unfreezeIfNeed using 4 params: uid=" + uid + ", pkg=" + target);
            } catch (Throwable e) {
                // 降级到 3 参数版本
                XposedHelpers.callMethod(s_oplusProxyWakeLock, "unfreezeIfNeed", 
                    uid, ws, tag);
                s_useFourParams = false;
                printLog("unfreezeIfNeed using 3 params: uid=" + uid + ", pkg=" + target);
            }
            s_signatureDetected = true;
        } else {
            // 使用已缓存的参数配置
            try {
                if (s_useFourParams) {
                    XposedHelpers.callMethod(s_oplusProxyWakeLock, "unfreezeIfNeed", 
                        uid, ws, tag, "FCMFix");
                } else {
                    XposedHelpers.callMethod(s_oplusProxyWakeLock, "unfreezeIfNeed", 
                        uid, ws, tag);
                }
                printLog("unfreeze: " + target + ", uid=" + uid);
            } catch (Throwable ignored) {
                // 静默失败
            }
        }
    }

    /**
     * 阻止 Hans 注册 GMS 限制观察者
     */
    private void startHookRegisterGmsRestrictObserver() {
        XposedHelpers.findAndHookMethod(
            "com.android.server.hans.scene.OplusBgSceneManager", 
            classLoader, 
            "registerGmsRestrictObserver", 
            XC_MethodReplacement.DO_NOTHING);
        printLog("registerGmsRestrictObserver hooked");
    }

    /**
     * 阻止 Hans 更新 GMS 限制状态
     */
    private void startHookUpdateGmsRestrict() {
        XposedHelpers.findAndHookMethod(
            "com.android.server.hans.scene.OplusBgSceneManager", 
            classLoader, 
            "updateGmsRestrict", 
            XC_MethodReplacement.DO_NOTHING);
        printLog("updateGmsRestrict hooked");
    }

    /**
     * 阻止 GMS 限制检查
     */
    private void startHookIsGoogleRestricInfoOn() {
        XposedHelpers.findAndHookMethod(
            "com.android.server.am.OplusAppStartupManager$OplusStartupStrategy", 
            classLoader, 
            "isGoogleRestricInfoOn", 
            int.class, 
            XC_MethodReplacement.returnConstant(false));
        printLog("isGoogleRestricInfoOn hooked");
    }

    /**
     * 阻止 Hans 将 GMS 识别为特殊应用（可选功能）
     */
    @SuppressWarnings("unused")
    private void startHookIsGmsApp() {
        XposedHelpers.findAndHookMethod(
            "com.android.server.hans.OplusHansDBConfig", 
            classLoader, 
            "isGmsApp", 
            int.class, 
            XC_MethodReplacement.returnConstant(false));
        printLog("isGmsApp hooked");
    }
}
