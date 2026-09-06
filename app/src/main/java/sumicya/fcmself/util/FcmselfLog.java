package sumicya.fcmself.util;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import sumicya.fcmself.config.FcmselfConfig;
import sumicya.fcmself.xposed.XposedModule;

import io.github.libxposed.api.XposedInterface;

/**
 * fcmself 统一日志。
 *
 * <p>每条日志都会：
 * <ul>
 *   <li>写入 {@code Log.d(FcmselfLog.TAG, ...)}；</li>
 *   <li>写入 LSPosed 框架日志（{@link XposedInterface#log}，实例由入口通过
 *       {@link #setXposed} 注入，未注入时只写 logcat）；</li>
 *   <li>诊断类日志（{@code diagnostics=true}）额外通过广播 {@link FcmselfConfig#ACTION_LOG}
 *       转发到 GMS 日志（由 ReconnectManagerFix 接收），便于在 FCM Diagnostics 中查看。</li>
 * </ul>
 */
public final class FcmselfLog {

    public static final String TAG = "FcmSelf";

    /** 当前模块运行的进程身份（system_server 为 "android"，否则为包名） */
    private static String selfPackageName = "UNKNOWN";

    /** 框架接口实例，用于把日志同时写进 LSPosed 日志；入口初始化前为 null */
    private static volatile XposedInterface xposed;

    private FcmselfLog() {
    }

    public static void setSelfPackageName(String packageName) {
        selfPackageName = packageName;
    }

    /** 由模块入口注入框架接口，之后每条日志都会同步写入 LSPosed 框架日志。 */
    public static void setXposed(XposedInterface xposedInterface) {
        xposed = xposedInterface;
    }

    /**
     * 写一条框架日志。框架侧签名是 {@code log(int priority, String tag, String msg)}
     * （priority 取 android.util.Log 的常量），没有单参数的 log(String)。
     */
    private static void logToFramework(String line) {
        XposedInterface instance = xposed;
        if (instance == null) {
            return;
        }
        try {
            instance.log(Log.INFO, TAG, line);
        } catch (Throwable ignored) {
            // 框架日志不可用时忽略，logcat 里已经有一份
        }
    }

    public static String getSelfPackageName() {
        return selfPackageName;
    }

    public static void log(String text) {
        log(text, false);
    }

    public static void log(String text, boolean diagnostics) {
        Log.d(TAG, text);
        String line = "[fcmself] [" + selfPackageName + "]" + text;
        if (diagnostics) {
            Intent logIntent = new Intent(FcmselfConfig.ACTION_LOG);
            logIntent.putExtra("text", line);
            try {
                Context context = XposedModule.getContext();
                if (context != null) {
                    context.sendBroadcast(logIntent);
                } else {
                    logToFramework(line);
                }
            } catch (Throwable e) {
                logToFramework(line);
            }
        } else {
            logToFramework(line);
        }
    }
}
