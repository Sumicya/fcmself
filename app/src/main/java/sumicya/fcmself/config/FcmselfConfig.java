package sumicya.fcmself.config;

import sumicya.fcmself.util.FcmselfLog;

/**
 * fcmself 运行期状态（每个进程一份，system_server 与 GMS 各自独立）。
 *
 * <p>本模块是纯 Hook 模块：没有设置界面，也没有白名单/开关配置——所有修复对所有
 * FCM 目标应用生效。唯一的运行期状态是"系统是否启动完成"：system_server 进程在
 * 用户解锁后延迟 {@link #BOOT_COMPLETE_DELAY_MS} 才置位，避免在系统启动早期介入
 * 广播/通知类 Hook；其它进程（GMS）配置即就绪。
 */
public final class FcmselfConfig {

    /** fcmself 模块自身包名 */
    public static final String SELF_PACKAGE = "sumicya.fcmself";

    /** 诊断日志广播（ReconnectManagerFix 转发到 GMS 日志，便于在 FCM Diagnostics 查看） */
    public static final String ACTION_LOG = "sumicya.fcmself.log";

    /** system_server 启动后延迟介入的时间 */
    private static final long BOOT_COMPLETE_DELAY_MS = 60000L;

    private static volatile boolean isBootComplete = false;

    private FcmselfConfig() {
    }

    /**
     * 系统是否已完成启动。未就绪前广播/通知类 Hook 一律不介入。
     */
    public static boolean isBootComplete() {
        return isBootComplete;
    }

    /** 用户解锁（或已解锁）时由 {@code XposedModule} 调用，启动计时。 */
    public static void onUserUnlocked() {
        if ("android".equals(FcmselfLog.getSelfPackageName())) {
            new Thread(() -> {
                try {
                    Thread.sleep(BOOT_COMPLETE_DELAY_MS);
                    isBootComplete = true;
                    FcmselfLog.log("Boot Complete");
                } catch (Throwable e) {
                    FcmselfLog.log(e.getMessage());
                }
            }).start();
        } else {
            isBootComplete = true;
        }
    }
}
