package sumicya.fcmself.libxposed;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


import io.github.libxposed.api.XposedInterface;

/**
 * 传统 Xposed API（XposedBridge/XposedHelpers）风格的封装，
 * 底层桥接到 LSPosed 的 {@link XposedInterface}。
 *
 * <p>实现要点：
 * <ul>
 *   <li>同一 {@link Member} 只安装一个 LSPosed 拦截器，回调保存在共享列表中。
 *       若对同一方法重复调用 {@link #hookMethod}，不会出现"每个拦截器都遍历完整回调列表"
 *       导致的重复执行问题。</li>
 *   <li>before 回调中调用 {@link XC_MethodHook.MethodHookParam#setResult} 后跳过
 *       {@code chain.proceed()}，after 回调按 before 执行的逆序执行。</li>
 * </ul>
 */
public final class XposedBridge {

    private static final String LOG_TAG = "fcmself";

    private static XposedInterface xposedInterface;

    /** member -> 该成员上挂载的所有回调（按挂载顺序） */
    private static final Map<Member, List<XC_MethodHook>> HOOKS = new ConcurrentHashMap<>();
    /** member -> 已安装的 LSPosed 拦截句柄（每个成员至多一个） */
    private static final Map<Member, XposedInterface.HookHandle> HANDLES = new ConcurrentHashMap<>();

    private XposedBridge() {
    }

    public static void init(XposedInterface xposed) {
        xposedInterface = xposed;
    }

    /**
     * 日志同时写入 logcat 与 LSPosed 框架日志（框架尚未初始化时只写 logcat）。
     *
     * <p>框架侧签名是 {@code XposedInterface.log(int priority, String tag, String msg)}
     * （priority 取 android.util.Log 的常量），没有单参数的 log(String)。
     */
    public static void log(String text) {
        Log.i(LOG_TAG, text);
        XposedInterface xposed = xposedInterface;
        if (xposed != null) {
            try {
                xposed.log(Log.INFO, LOG_TAG, text);
            } catch (Throwable ignored) {
                // 框架日志不可用时忽略，logcat 里已经有一份
            }
        }
    }

    public static XC_MethodHook.Unhook hookMethod(Member member, XC_MethodHook callback) {
        ensureInit();
        if (!(member instanceof Method) && !(member instanceof Constructor<?>)) {
            throw new IllegalArgumentException("Only Method/Constructor can be hooked");
        }

        XposedInterface.HookHandle handle;
        List<XC_MethodHook> callbacks;
        synchronized (HOOKS) {
            handle = HANDLES.get(member);
            if (handle == null) {
                handle = xposedInterface.hook((Executable) member)
                        .intercept(chain -> invokeChain(member, chain));
                HANDLES.put(member, handle);
            }
            List<XC_MethodHook> list = HOOKS.get(member);
            if (list == null) {
                list = new ArrayList<>();
                HOOKS.put(member, list);
            }
            list.add(callback);
            callbacks = list;
        }

        return callback.new Unhook(member, callback);
    }

    /**
     * 单次调用链上执行所有已挂载回调。
     * 回调列表是共享的：读取时做一次快照，避免执行期间被 unhook 修改。
     */
    private static Object invokeChain(Member member, XposedInterface.Chain chain) throws Throwable {
        XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
        param.method = (Member) chain.getExecutable();
        param.thisObject = chain.getThisObject();
        param.args = chain.getArgs().toArray(new Object[0]);

        List<XC_MethodHook> callbacks;
        synchronized (HOOKS) {
            List<XC_MethodHook> list = HOOKS.get(member);
            callbacks = (list == null || list.isEmpty()) ? null : new ArrayList<>(list);
        }
        if (callbacks == null) {
            return chain.proceed(param.args);
        }

        int beforeCount = 0;
        for (XC_MethodHook hook : callbacks) {
            hook.beforeHookedMethod(param);
            beforeCount++;
            if (param.isReturnEarly()) {
                break;
            }
        }

        if (!param.isReturnEarly()) {
            try {
                param.setResult(chain.proceed(param.args));
            } catch (Throwable t) {
                param.setThrowable(t);
            }
            param.resetReturnEarly();
        }

        for (int i = beforeCount - 1; i >= 0; i--) {
            callbacks.get(i).afterHookedMethod(param);
        }

        if (param.hasThrowable()) {
            throw param.getThrowable();
        }
        return param.getResult();
    }

    /**
     * 移除单个回调；当该成员上不再有回调时，卸载底层的 LSPosed 拦截器。
     */
    static void unhook(Member member, XC_MethodHook callback) {
        XposedInterface.HookHandle handle;
        synchronized (HOOKS) {
            List<XC_MethodHook> list = HOOKS.get(member);
            handle = null;
            if (list != null) {
                list.remove(callback);
                if (list.isEmpty()) {
                    HOOKS.remove(member);
                    handle = HANDLES.remove(member);
                }
            }
        }
        if (handle != null) {
            handle.unhook();
        }
    }

    private static void ensureInit() {
        if (xposedInterface == null) {
            throw new IllegalStateException("XposedBridge not initialized");
        }
    }
}
