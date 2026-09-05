package com.kooritea.fcmfix.util;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.kooritea.fcmfix.config.FcmfixConfig;
import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.xposed.XposedModule;

/**
 * fcmfix 统一日志。
 *
 * <p>每条日志都会：
 * <ul>
 *   <li>写入 {@code Log.d(FcmfixLog.TAG, ...)}；</li>
 *   <li>写入 LSPosed 日志（{@link XposedBridge#log}）；</li>
 *   <li>诊断类日志（{@code diagnostics=true}）额外通过广播 {@link FcmfixConfig#ACTION_LOG}
 *       转发到 GMS 日志（由 ReconnectManagerFix 接收），便于在 FCM Diagnostics 中查看。</li>
 * </ul>
 */
public final class FcmfixLog {

    public static final String TAG = "FcmFix";

    /** 当前模块运行的进程身份（system_server 为 "android"，否则为包名） */
    private static String selfPackageName = "UNKNOWN";

    private FcmfixLog() {
    }

    public static void setSelfPackageName(String packageName) {
        selfPackageName = packageName;
    }

    public static String getSelfPackageName() {
        return selfPackageName;
    }

    public static void log(String text) {
        log(text, false);
    }

    public static void log(String text, boolean diagnostics) {
        Log.d(TAG, text);
        String line = "[fcmfix] [" + selfPackageName + "]" + text;
        if (diagnostics) {
            Intent logIntent = new Intent(FcmfixConfig.ACTION_LOG);
            logIntent.putExtra("text", line);
            try {
                Context context = XposedModule.getContext();
                if (context != null) {
                    context.sendBroadcast(logIntent);
                } else {
                    XposedBridge.log(line);
                }
            } catch (Throwable e) {
                XposedBridge.log(line);
            }
        } else {
            XposedBridge.log(line);
        }
    }
}
