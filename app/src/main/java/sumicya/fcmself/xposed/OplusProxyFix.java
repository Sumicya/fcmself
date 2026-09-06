package sumicya.fcmself.xposed;

import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.os.WorkSource;

import sumicya.fcmself.util.Hooks;
import sumicya.fcmself.util.Reflect;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;

/**
 * OPPO/OnePlus ColorOS 专用 FCM 修复模块
 * 主要功能：
 * 1. 绕过 OplusProxyWakeLock 冻结机制
 * 2. 阻止 OplusProxyBroadcast 拦截 FCM 广播
 * 3. 禁用 Hans 后台管理系统对 GMS 的限制
 */
public class OplusProxyFix extends XposedModule {

    /**
     * 同一目标包的 shouldProxy bypass 日志最小间隔。
     *
     * <p>聊天类应用的 FCM 消息非常密集（实测 5 分钟 200+ 条），逐条打日志只会把 logcat 刷满。
     * 这里按包名节流，并在下一条日志里报告被抑制的条数——命中信息一条不丢，噪音大幅下降。
     */
    private static final long BYPASS_LOG_INTERVAL_MS = 60_000L;

    /** 包名 -> {上次打印时刻, 期间被抑制条数} */
    private static final Map<String, long[]> BYPASS_LOG_STATE = new ConcurrentHashMap<>();

    /**
     * "从未打印过"的时刻哨兵。
     *
     * <p>{@link SystemClock#elapsedRealtime()} 返回的是开机以来的毫秒数，所以初值不能用 0：
     * 开机后前 60 秒内到达的第一条 bypass 会满足 {@code now - 0 < 60000} 而被当成
     * "刚刚打印过"吞掉，只留在抑制计数里。
     */
    private static final long NEVER_PRINTED = -BYPASS_LOG_INTERVAL_MS * 2;

    private static Object s_oplusProxyWakeLock = null;
    private static volatile boolean s_useFourParams = false;
    private static volatile boolean s_signatureDetected = false;

    public OplusProxyFix(XposedInterface api, ClassLoader classLoader) {
        super(api, classLoader);
        
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
    }

    /**
     * Hook OplusProxyBroadcast.shouldProxy 方法
     * 当检测到 FCM 相关广播时，返回 NOT_INCLUDE 以绕过代理检查
     */
    private void startHookOplusProxyBroadcast() throws Exception {
        Class<?> oplusProxyBroadcastClass = Reflect.findClass(
            "com.android.server.am.OplusProxyBroadcast", classLoader);
        Class<?> resultEnum = Reflect.findClass(
            "com.android.server.am.OplusProxyBroadcast$RESULT", classLoader);
        Object notIncludeValue = Reflect.getStaticObjectField(resultEnum, "NOT_INCLUDE");

        // ColorOS 15 测试：shouldProxy 有 8 个参数
        Method shouldProxy = Reflect.findMethodByParamCount(oplusProxyBroadcastClass, "shouldProxy", 8);
        if (shouldProxy == null) {
            throw new NoSuchMethodError(oplusProxyBroadcastClass.getName() + "#shouldProxy");
        }
        Hooks.hook(api, shouldProxy, chain -> {
            String callingPkg = (String) chain.getArg(3);
            String pkgName = (String) chain.getArg(5);
            String action = (String) chain.getArg(6);

            // 示例：caller=com.google.android.gms, action=com.google.android.c2dm.intent.RECEIVE
            if (isFCMAction(action) && hasTargetPackage(pkgName)) {
                logBypassThrottled(pkgName, callingPkg, action);
                // 不调用 chain.proceed()，直接返回 NOT_INCLUDE = 这条广播不走代理
                return notIncludeValue;
            }
            return chain.proceed();
        });
    }

    /**
     * Hook OplusProxyWakeLock 构造函数，保存实例引用供后续使用
     */
    private void startHookOplusProxyWakeLock() throws Exception {
        Class<?> oplusWakelockClass = Reflect.findClass(
            "com.android.server.power.OplusProxyWakeLock", classLoader);
        // 不指定参数类型：所有构造器同等匹配，取声明顺序最后一个（与移除兼容层前的行为一致）
        Constructor<?> constructor = Reflect.findConstructorMostMatch(oplusWakelockClass);

        Hooks.hookAfter(api, constructor, (chain, error) -> {
            if (s_oplusProxyWakeLock != null) {
                printLog("warn: OplusProxyWakeLock constructed multiple times!");
                return;
            }
            s_oplusProxyWakeLock = chain.getThisObject();
            printLog("OplusProxyWakeLock instance captured");
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
    /** 按包名节流的 bypass 日志（见 {@link #BYPASS_LOG_INTERVAL_MS}）。 */
    private static void logBypassThrottled(String pkgName, String callingPkg, String action) {
        long now = SystemClock.elapsedRealtime();
        long[] state = BYPASS_LOG_STATE.computeIfAbsent(pkgName, k -> new long[]{NEVER_PRINTED, 0});
        synchronized (state) {
            if (now - state[0] < BYPASS_LOG_INTERVAL_MS) {
                state[1]++;
                return;
            }
            long suppressed = state[1];
            state[0] = now;
            state[1] = 0;
            printLog("shouldProxy bypass: pkg=" + pkgName + ", caller=" + callingPkg
                    + ", action=" + action
                    + (suppressed > 0 ? "（期间另有 " + suppressed + " 条同类日志已抑制）" : ""));
        }
    }

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
                Reflect.callMethod(s_oplusProxyWakeLock, "unfreezeIfNeed", 
                    uid, ws, tag, "FcmSelf");
                s_useFourParams = true;
                printLog("unfreezeIfNeed using 4 params: uid=" + uid + ", pkg=" + target);
            } catch (Throwable e) {
                // 降级到 3 参数版本
                Reflect.callMethod(s_oplusProxyWakeLock, "unfreezeIfNeed", 
                    uid, ws, tag);
                s_useFourParams = false;
                printLog("unfreezeIfNeed using 3 params: uid=" + uid + ", pkg=" + target);
            }
            s_signatureDetected = true;
        } else {
            // 使用已缓存的参数配置
            try {
                if (s_useFourParams) {
                    Reflect.callMethod(s_oplusProxyWakeLock, "unfreezeIfNeed", 
                        uid, ws, tag, "FcmSelf");
                } else {
                    Reflect.callMethod(s_oplusProxyWakeLock, "unfreezeIfNeed", 
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
        hookNoOp("com.android.server.hans.scene.OplusBgSceneManager", "registerGmsRestrictObserver");
        printLog("registerGmsRestrictObserver hooked");
    }

    /** 把 {@code className#methodName()}（无参）替换成空实现：原方法完全不执行，返回 null。 */
    private void hookNoOp(String className, String methodName) {
        Method method = Reflect.findMethodExact(Reflect.findClass(className, classLoader), methodName);
        Hooks.hook(api, method, chain -> null);
    }

    /**
     * 阻止 Hans 更新 GMS 限制状态
     */
    private void startHookUpdateGmsRestrict() {
        hookNoOp("com.android.server.hans.scene.OplusBgSceneManager", "updateGmsRestrict");
        printLog("updateGmsRestrict hooked");
    }

    /**
     * 阻止 GMS 限制检查
     */
    private void startHookIsGoogleRestricInfoOn() {
        Method method = Reflect.findMethodExact(Reflect.findClass(
                "com.android.server.am.OplusAppStartupManager$OplusStartupStrategy", classLoader),
                "isGoogleRestricInfoOn", int.class);
        // 不调用 chain.proceed()，恒定返回 false
        Hooks.hook(api, method, chain -> false);
        printLog("isGoogleRestricInfoOn hooked");
    }
}
