package sumicya.fcmself.xposed;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.UserManager;

import sumicya.fcmself.config.FcmselfConfig;
import sumicya.fcmself.util.FcmselfLog;
import sumicya.fcmself.util.Hooks;
import sumicya.fcmself.util.Reflect;

import io.github.libxposed.api.XposedInterface;

import java.lang.ref.WeakReference;
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

    /**
     * 使用 {@link WeakReference} 持有 Context，避免内存泄漏。
     * <p>注意：从弱引用获取 Context 后应尽快使用，不要长期持有强引用。
     */
    @SuppressLint("StaticFieldLeak")
    private static WeakReference<Context> contextRef = null;
    
    /**
     * 获取 Context 实例。
     * <p>如果弱引用已被回收，返回 null（调用方需自行判空处理）。
     */
    public static Context getContext() {
        return contextRef != null ? contextRef.get() : null;
    }
    
    /**
     * 设置 Context 引用（内部使用）。
     */
    private static void setContext(Context context) {
        contextRef = new WeakReference<>(context);
    }

    /** 模块自身通知的渠道 id */
    private static final String NOTIFICATION_CHANNEL = "fcmself";

    /** 本进程内已创建的模块实例（构造顺序即安装顺序） */
    private static final List<XposedModule> instances = new ArrayList<>();
    private static boolean isInitReceiver = false;

    /** libxposed 框架接口，安装 hook 时用 */
    protected final XposedInterface api;

    protected final ClassLoader classLoader;

    protected XposedModule(final XposedInterface api, final ClassLoader classLoader) {
        this.api = api;
        this.classLoader = classLoader;
        synchronized (instances) {
            instances.add(this);
            if (instances.size() == 1) {
                initContext(api, classLoader);
            } else if (getContext() != null && isUserUnlocked()) {
                safeOnCanReadConfig(this);
            }
        }
    }

    // ------------------------------------------------------------------
    // 进程身份 / 上下文
    // ------------------------------------------------------------------

    public static Context getContext() {
        return contextRef != null ? contextRef.get() : null;
    }

    private static boolean isUserUnlocked() {
        Context ctx = getContext();
        if (ctx == null) {
            return false;
        }
        try {
            return ctx.getSystemService(UserManager.class).isUserUnlocked();
        } catch (Throwable e) {
            return false;
        }
    }

    private static void initContext(final XposedInterface api, final ClassLoader classLoader) {
        Class<?> contextWrapper = Reflect.findClass("android.content.ContextWrapper", classLoader);
        Hooks.hookMethodAfter(api, contextWrapper, "attachBaseContext", new Class<?>[]{Context.class},
                (chain, error) -> {
                    if (contextRef == null || contextRef.get() == null) {
                        Context newContext = (Context) chain.getThisObject();
                        setContext(newContext);
                        if (isUserUnlocked()) {
                            callAllOnCanReadConfig();
                        } else {
                            IntentFilter filter = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
                            newContext.registerReceiver(unlockBroadcastReceive, filter);
                        }
                    }
                });
    }

    private static final BroadcastReceiver unlockBroadcastReceive = new BroadcastReceiver() {
        @Override
        public void onReceive(Context _context, Intent intent) {
            if (Intent.ACTION_USER_UNLOCKED.equals(intent.getAction())) {
                Context ctx = getContext();
                if (ctx != null) {
                    try {
                        ctx.unregisterReceiver(unlockBroadcastReceive);
                    } catch (Throwable ignored) {
                    }
                    callAllOnCanReadConfig();
                }
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

    /** 用户解锁、运行期状态就绪后回调，默认空实现。 */
    protected void onCanReadConfig() throws Throwable {
    }

    // ------------------------------------------------------------------
    // 卸载监听
    // ------------------------------------------------------------------

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

    private static synchronized void initReceiver() {
        Context ctx = getContext();
        if (!isInitReceiver && ctx != null) {
            isInitReceiver = true;

            IntentFilter unInstallIntentFilter = new IntentFilter(Intent.ACTION_PACKAGE_REMOVED);
            unInstallIntentFilter.addDataScheme("package");
            ctx.registerReceiver(uninstallReceiver, unInstallIntentFilter);
        }
    }

    private static void onUninstallSelf() {
        Context ctx = getContext();
        if (ctx == null) {
            return;
        }
        NotificationManager notificationManager = (NotificationManager) ctx.getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL);
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
     * 广播/通知是否有明确的目标应用包名。
     *
     * <p>本模块没有白名单：只要目标包名可解析就介入，避免对无目标的隐式广播做处理。
     */
    protected boolean hasTargetPackage(String packageName) {
        return packageName != null && !packageName.isEmpty();
    }

    protected void sendNotification(String title) {
        sendNotification(title, null);
    }

    /**
     * 发一条模块自身的通知（Hook 失败提示、重连诊断等）。
     *
     * <p>直接用框架 {@link Notification.Builder}（渠道构造器需 API 26，本模块 minSdk 29），
     * 不为一个通知把整个 androidx.core 打进 APK。模块没有界面，通知不带点击意图。
     */
    // 通知是从被 Hook 的宿主进程（system_server / GMS）的 context 发的，归属宿主 UID，
    // 权限由宿主自己持有——本模块声明 POST_NOTIFICATIONS 不起作用，故一并抑制这两条 lint
    @SuppressLint({"MissingPermission", "NotificationPermission"})
    protected void sendNotification(String title, String content) {
        Context ctx = getContext();
        if (ctx == null) {
            return;
        }
        printLog(title, false);
        NotificationManager notificationManager = (NotificationManager) ctx.getSystemService(NOTIFICATION_SERVICE);
        createNotificationChannel(notificationManager);
        Notification notification = new Notification.Builder(ctx, NOTIFICATION_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("[fcmself]" + title)
                .setContentText(content)
                .build();
        notificationManager.notify((int) System.currentTimeMillis(), notification);
    }

    private void createNotificationChannel(NotificationManager notificationManager) {
        if (notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL) == null) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL, "fcmself", NotificationManager.IMPORTANCE_HIGH);
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
            return (Intent) Reflect.getObjectField(holder, "intent");
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
