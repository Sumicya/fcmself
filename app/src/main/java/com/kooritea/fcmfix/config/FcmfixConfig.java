package com.kooritea.fcmfix.config;

import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.util.FcmfixLog;

/**
 * fcmfix 运行期配置中心（每个进程一份，system_server / GMS / PowerKeeper 各自独立）。
 *
 * <p>配置来源：LSPosed 远程配置（模块 UI 写入的 SharedPreferences "config" 组）。
 *
 * <p>生命周期：
 * <ul>
 *   <li>首次可读取配置（用户解锁）时触发 {@link #onUserUnlocked()}；</li>
 *   <li>system_server 进程额外延迟 {@link #BOOT_COMPLETE_DELAY_MS} 后才置
 *       {@link #isBootComplete()}，避免系统启动早期介入广播；</li>
 *   <li>模块 UI 通过广播 {@link #ACTION_UPDATE_CONFIG} 触发重新加载。</li>
 * </ul>
 */
public final class FcmfixConfig {

    /** fcmfix 模块自身包名（始终视为允许） */
    public static final String SELF_PACKAGE = "com.kooritea.fcmfix";

    // ---- 配置项键名（与模块 UI 保持一致） ----
    public static final String KEY_ALLOW_LIST = "allowList";
    public static final String KEY_DISABLE_AUTO_CLEAN_NOTIFICATION = "disableAutoCleanNotification";
    public static final String KEY_INCLUDE_ICEBOX_DISABLE_APP = "includeIceBoxDisableApp";
    public static final String KEY_NO_RESPONSE_NOTIFICATION = "noResponseNotification";

    /** 模块 UI 通知钩子侧重新加载配置 */
    public static final String ACTION_UPDATE_CONFIG = "com.kooritea.fcmfix.update.config";
    /** 诊断日志广播（ReconnectManagerFix 转发到 GMS 日志） */
    public static final String ACTION_LOG = "com.kooritea.fcmfix.log";

    /** 远程配置组名 */
    public static final String REMOTE_PREFS_GROUP = "config";
    /** system_server 启动后延迟介入的时间 */
    private static final long BOOT_COMPLETE_DELAY_MS = 60000L;

    private static final Map<String, Object> config = new HashMap<>();

    private static volatile Set<String> allowList = null;
    private static volatile boolean isBootComplete = false;
    private static Thread loadConfigThread = null;

    private FcmfixConfig() {
    }

    /**
     * 系统是否已完成启动（system_server 进程延迟 60 秒，其它进程配置就绪即完成）。
     * 未就绪前广播/通知类 Hook 一律不介入。
     */
    public static boolean isBootComplete() {
        return isBootComplete;
    }

    /**
     * 目标应用是否在白名单内（fcmfix 自身恒为 true）。
     * 配置尚未加载完成时返回 false（fail-safe：宁可不介入）。
     */
    public static boolean isAllowed(String packageName) {
        if (packageName == null) {
            return false;
        }
        ensureConfigLoaded();
        if (SELF_PACKAGE.equals(packageName)) {
            return true;
        }
        Set<String> list = allowList;
        return list != null && list.contains(packageName);
    }

    public static boolean getBoolean(String key, boolean defaultValue) {
        ensureConfigLoaded();
        if (!config.containsKey("init")) {
            return defaultValue;
        }
        Object value = config.get(key);
        return value == null ? defaultValue : (Boolean) value;
    }

    /** 用户解锁（或已解锁）时由 {@code XposedModule} 调用，触发配置加载与启动计时。 */
    public static void onUserUnlocked() {
        if ("android".equals(FcmfixLog.getSelfPackageName())) {
            new Thread(() -> {
                try {
                    Thread.sleep(BOOT_COMPLETE_DELAY_MS);
                    isBootComplete = true;
                    FcmfixLog.log("Boot Complete");
                } catch (Throwable e) {
                    FcmfixLog.log(e.getMessage());
                }
            }).start();
        } else {
            isBootComplete = true;
        }
        load();
    }

    private static void ensureConfigLoaded() {
        if (config.containsKey("init")) {
            return;
        }
        load();
    }

    /**
     * 读取远程配置（异步，避免在 system_server 启动路径上阻塞）。
     * 同一时刻只有一个加载线程；失败后允许下次访问时重试。
     */
    public static synchronized void load() {
        if (loadConfigThread != null) {
            return;
        }
        loadConfigThread = new Thread() {
            @Override
            public void run() {
                try {
                    SharedPreferences remotePreferences = XposedBridge.getRemotePreferences(REMOTE_PREFS_GROUP);
                    if (remotePreferences == null) {
                        throw new IllegalStateException("remotePreferences 不可用");
                    }
                    Set<String> list = remotePreferences.getStringSet(KEY_ALLOW_LIST, null);
                    if (list == null) {
                        list = new HashSet<>();
                    }
                    allowList = list;
                    if ("android".equals(FcmfixLog.getSelfPackageName())) {
                        FcmfixLog.log("[Modern Xposed API]onUpdateConfig allowList size: " + list.size());
                    }
                    config.put(KEY_DISABLE_AUTO_CLEAN_NOTIFICATION,
                            remotePreferences.getBoolean(KEY_DISABLE_AUTO_CLEAN_NOTIFICATION, false));
                    config.put(KEY_INCLUDE_ICEBOX_DISABLE_APP,
                            remotePreferences.getBoolean(KEY_INCLUDE_ICEBOX_DISABLE_APP, false));
                    config.put(KEY_NO_RESPONSE_NOTIFICATION,
                            remotePreferences.getBoolean(KEY_NO_RESPONSE_NOTIFICATION, false));
                    config.put("init", true);
                } catch (Throwable e) {
                    FcmfixLog.log("通过现代Xposed API读取配置失败: " + e.getMessage());
                }
                loadConfigThread = null;
            }
        };
        loadConfigThread.start();
    }
}
