package com.kooritea.fcmfix;

import java.util.function.Supplier;

import com.kooritea.fcmfix.config.FcmfixConfig;
import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.util.FcmfixLog;
import com.kooritea.fcmfix.xposed.AutoStartFix;
import com.kooritea.fcmfix.xposed.BroadcastFix;
import com.kooritea.fcmfix.xposed.KeepNotification;
import com.kooritea.fcmfix.xposed.MiuiLocalNotificationFix;
import com.kooritea.fcmfix.xposed.OplusProxyFix;
import com.kooritea.fcmfix.xposed.PowerkeeperFix;
import com.kooritea.fcmfix.xposed.ReconnectManagerFix;
import com.kooritea.fcmfix.xposed.XposedModule;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * fcmfix LSPosed 入口。
 *
 * <p>模块安装分布：
 * <ul>
 *   <li>system_server（"android"）：广播/自启动/通知/ColorOS 代理等系统侧 Hook；</li>
 *   <li>com.google.android.gms：重连修复（ReconnectManagerFix）；</li>
 *   <li>com.miui.powerkeeper：MIUI 电量管家限制解除（PowerkeeperFix）。</li>
 * </ul>
 * 每个模块独立 try/catch：单个模块安装失败只影响自身，不会阻断后续模块。
 */
public class XposedMain extends io.github.libxposed.api.XposedModule {

    @Override
    public void onSystemServerStarting(io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam param) {
        XposedBridge.init(this);
        FcmfixLog.setSelfPackageName("android");
        FcmfixConfig.setLiteBuild(BuildConfig.LITE);

        ClassLoader classLoader = param.getClassLoader();

        XposedBridge.log("[fcmfix] start hook com.android.server.am.ActivityManagerService/com.android.server.am.BroadcastController");
        install("BroadcastFix", () -> new BroadcastFix(classLoader));

        XposedBridge.log("[fcmfix] start hook com.android.server.notification.NotificationManagerServiceInjector");
        install("MiuiLocalNotificationFix", () -> new MiuiLocalNotificationFix(classLoader));

        XposedBridge.log("[fcmfix] com.android.server.am.BroadcastQueueInjector.checkApplicationAutoStart");
        install("AutoStartFix", () -> new AutoStartFix(classLoader));

        XposedBridge.log("[fcmfix] com.android.server.notification.NotificationManagerService");
        install("KeepNotification", () -> new KeepNotification(classLoader));

        XposedBridge.log("[fcmfix] start hook com.android.server.power.OplusProxyWakeLock");
        install("OplusProxyFix", () -> new OplusProxyFix(classLoader));
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        XposedBridge.init(this);
        FcmfixConfig.setLiteBuild(BuildConfig.LITE);

        if ("com.google.android.gms".equals(param.getPackageName()) && param.isFirstPackage()) {
            FcmfixLog.setSelfPackageName("com.google.android.gms");
            XposedBridge.log("[fcmfix] start hook com.google.android.gms");
            install("ReconnectManagerFix", () -> new ReconnectManagerFix(param.getClassLoader()));
        }

        if ("com.miui.powerkeeper".equals(param.getPackageName()) && param.isFirstPackage()) {
            FcmfixLog.setSelfPackageName("com.miui.powerkeeper");
            XposedBridge.log("[fcmfix] start hook com.miui.powerkeeper");
            install("PowerkeeperFix", () -> new PowerkeeperFix(param.getClassLoader()));
        }
    }

    /** 安装单个 Hook 模块；失败仅记录日志，不影响其它模块。 */
    private static XposedModule install(String name, Supplier<XposedModule> factory) {
        try {
            return factory.get();
        } catch (Throwable t) {
            XposedBridge.log("[fcmfix] 模块安装失败 " + name + ": " + t);
            return null;
        }
    }
}
