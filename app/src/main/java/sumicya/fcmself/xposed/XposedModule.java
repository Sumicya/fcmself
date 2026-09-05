package sumicya.fcmself.xposed;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.UserManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import sumicya.fcmself.config.FcmselfConfig;
import sumicya.fcmself.libxposed.XC_MethodHook;
import sumicya.fcmself.libxposed.XposedHelpers;
import sumicya.fcmself.util.FcmselfLog;

import java.util.ArrayList;
import java.util.List;

import static android.content.Context.NOTIFICATION_SERVICE;

/**
 * 所有 fcmself Hook 模块的基类。
 *
 * <p>职责：
 * <ul>
 *   <li>捕获本进程的 {@link Context}（Hook {@code ContextWrapper.attachBaseContext}），
 *       并在用户解锁后触发配置加载；</li>
 *   <li>维护模块实例列表，配置就绪后逐个回调 {@link #onCanReadConfig()}；</li>
 *   <li>提供各 Fix 模块共用的工具：日志（委托 {@link FcmselfLog}）、白名单/开关判断
 *       （委托 {@link FcmselfConfig}）、FCM Intent 识别、通知发送。</li>
 * </ul>
 *
 * <p>具体配置与启动时机逻辑见 {@link FcmselfConfig}。
 */
public abstract class XposedModule {

    @SuppressLint("StaticFieldLeak")
    protected static Context context = null;

    /** 本进程内已创建的模块实例（构造顺序即安装顺序） */
    private static final List<XposedModule> instances = new ArrayList<>();
    private static boolean isInitReceiver = false;

    protected final ClassLoader classLoader;

    protected XposedModule(final ClassLoader classLoader) {
        this.classLoader = classLoader;
        synchronized (instances) {
            instances.add(this);
            if (instances.size() == 1) {
                initContext(classLoader);
            } else if (context != null && isUserUnlocked()) {
                safeOnCanReadConfig(this);
            }
        }
    }

    // ------------------------------------------------------------------
    // 进程身份 / 上下文
    // ------------------------------------------------------------------

    public static Context getContext() {
        return context;
    }

    private static boolean isUserUnlocked() {
        try {
            return context.getSystemService(UserManager.class).isUserUnlocked();
        } catch (Throwable e) {
            return false;
        }
    }

    private static void initContext(final ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod("android.content.ContextWrapper", classLoader, "attachBaseContext", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam methodHookParam) {
                if (context == null) {
                    context = (Context) methodHookParam.thisObject;
                    if (isUserUnlocked()) {
                        callAllOnCanReadConfig();
                    } else {
                        IntentFilter filter = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
                        context.registerReceiver(unlockBroadcastReceive, filter);
                    }
                }
            }
        });
    }

    private static final BroadcastReceiver unlockBroadcastReceive = new BroadcastReceiver() {
        @Override
        public void onReceive(Context _context, Intent intent) {
            if (Intent.ACTION_USER_UNLOCKED.equals(intent.getAction())) {
                try {
                    context.unregisterReceiver(unlockBroadcastReceive);
                } catch (Throwable ignored) {
                }
                callAllOnCanReadConfig();
            }
        }
    };

    /**
     * 用户解锁后统一入口：初始化广播接收器 -> 配置加载/启动计时 -> 逐个模块 onCanReadConfig。
     */
    private static void callAllOnCanReadConfig() {
        initReceiver();
        FcmselfConfig.onUserUnlocked();
        List<XposedModule> snapshot;
        synchronized (instances) {
            snapshot = new ArrayList<>(instances);
        }
        for (XposedModule instance : snapshot) {
            safeOnCanReadConfig(instance);
        }
    }

    private static void safeOnCanReadConfig(XposedModule instance) {
        try {
            instance.onCanReadConfig();
        } catch (Throwable e) {
            FcmselfLog.log("onCanReadConfig 失败: " + e.getMessage());
        }
    }

    /** 配置（远程配置 + 白名单）就绪后回调，默认空实现。 */
    protected void onCanReadConfig() throws Throwable {
    }

    // ------------------------------------------------------------------
    // 配置广播 / 卸载监听
    // ------------------------------------------------------------------

    /** 模块 UI 保存配置后发送该广播，通知各进程重新加载配置。 */
    private static final BroadcastReceiver configUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context _context, Intent intent) {
            if (FcmselfConfig.ACTION_UPDATE_CONFIG.equals(intent.getAction())) {
                FcmselfConfig.load();
            }
        }
    };

    private static final BroadcastReceiver uninstallReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context _context, Intent intent) {
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                    && FcmselfConfig.SELF_PACKAGE.equals(intent.getData().getSchemeSpecificPart())) {
                Bundle extras = intent.getExtras();
                if (extras.containsKey(Intent.EXTRA_REPLACING) && extras.getBoolean(Intent.EXTRA_REPLACING)) {
                    return;
                }
                onUninstallSelf();
                if ("android".equals(FcmselfLog.getSelfPackageName())) {
                    FcmselfLog.log("Fcmself已卸载，重启后停止生效。");
                }
            }
        }
    };

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private static synchronized void initReceiver() {
        if (!isInitReceiver && context != null) {
            isInitReceiver = true;

            IntentFilter updateConfigIntentFilter = new IntentFilter(FcmselfConfig.ACTION_UPDATE_CONFIG);
            if (Build.VERSION.SDK_INT >= 34) {
                context.registerReceiver(configUpdateReceiver, updateConfigIntentFilter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(configUpdateReceiver, updateConfigIntentFilter);
            }

            IntentFilter unInstallIntentFilter = new IntentFilter(Intent.ACTION_PACKAGE_REMOVED);
            unInstallIntentFilter.addDataScheme("package");
            context.registerReceiver(uninstallReceiver, unInstallIntentFilter);
        }
    }

    private static void onUninstallSelf() {
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = notificationManager.getNotificationChannel("fcmself");
        if (channel != null) {
            notificationManager.deleteNotificationChannel(channel.getId());
        }
    }

    // ------------------------------------------------------------------
    // 供 Fix 模块使用的工具方法（保持原有调用签名）
    // ------------------------------------------------------------------

    // 注意：保持 static（部分模块如 OplusProxyFix 从静态方法中调用）
    protected static void printLog(String text) {
        FcmselfLog.log(text);
    }

    protected static void printLog(String text, Boolean isDiagnosticsLog) {
        FcmselfLog.log(text, isDiagnosticsLog);
    }

    /**
     * 目标应用是否在白名单内（fcmself 自身恒为 true）。
     */
    protected boolean targetIsAllow(String packageName) {
        return FcmselfConfig.isAllowed(packageName);
    }

    protected boolean getBooleanConfig(String key, boolean defaultValue) {
        return FcmselfConfig.getBoolean(key, defaultValue);
    }

    protected void sendNotification(String title) {
        sendNotification(title, null, null);
    }

    protected void sendNotification(String title, String content) {
        sendNotification(title, content, null);
    }

    @SuppressLint("MissingPermission")
    protected void sendNotification(String title, String content, PendingIntent pendingIntent) {
        printLog(title, false);
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
        createNotificationChannel(notificationManager);
        NotificationCompat.Builder notification = new NotificationCompat.Builder(context, "fcmself")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("[fcmself]" + title)
                .setContentText(content)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        if (pendingIntent != null) {
            notification.setContentIntent(pendingIntent).setAutoCancel(true);
        }
        notificationManager.notify((int) System.currentTimeMillis(), notification.build());
    }

    protected void createNotificationChannel(NotificationManagerCompat notificationManager) {
        if (notificationManager.getNotificationChannel("fcmself") == null) {
            NotificationChannel channel = new NotificationChannel("fcmself", "fcmself", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("[xposed] fcmself");
            notificationManager.createNotificationChannel(channel);
        }
    }

    /**
     * 判断 action 是否为 FCM/GCM 相关：
     * c2dm 接收广播、Firebase 消息事件、Firebase 实例 ID 事件。
     */
    protected boolean isFCMAction(String action) {
        return action != null && (action.endsWith(".android.c2dm.intent.RECEIVE") ||
                "com.google.firebase.MESSAGING_EVENT".equals(action) ||
                "com.google.firebase.INSTANCE_ID_EVENT".equals(action));
    }

    protected boolean isFCMIntent(Intent intent) {
        return intent != null && isFCMAction(intent.getAction());
    }

    /** 从携带 intent 字段的广播参数对象中取出 Intent（各 ROM 参数结构不同，按对象字段反射）。 */
    protected static Intent intentOfField(Object holder) {
        try {
            return (Intent) XposedHelpers.getObjectField(holder, "intent");
        } catch (Throwable e) {
            return null;
        }
    }

    /** 从定向 Intent 中解析目标包名（显式 component 优先，其次 package）。 */
    protected static String targetOf(Intent intent) {
        if (intent == null) {
            return null;
        }
        return intent.getComponent() == null ? intent.getPackage() : intent.getComponent().getPackageName();
    }
}
