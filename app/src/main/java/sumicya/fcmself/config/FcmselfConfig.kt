package sumicya.fcmself.config

import sumicya.fcmself.util.FcmselfLog

/**
 * fcmself 运行期状态（每个进程一份，system_server 与 GMS 各自独立）。
 *
 * 本模块是纯 Hook 模块：没有设置界面，也没有白名单/开关配置——所有修复对所有
 * FCM 目标应用生效。唯一的运行期状态是"系统是否启动完成"：system_server 进程在
 * 用户解锁后延迟 [BOOT_COMPLETE_DELAY_MS] 才置位，避免在系统启动早期介入
 * 广播/通知类 Hook；其它进程（GMS）配置即就绪。
 */
object FcmselfConfig {

    /** fcmself 模块自身包名 */
    const val SELF_PACKAGE = "sumicya.fcmself"

    /** 诊断日志广播（ReconnectManagerFix 转发到 GMS 日志，便于在 FCM Diagnostics 查看） */
    const val ACTION_LOG = "sumicya.fcmself.log"

    /** system_server 启动后延迟介入的时间 */
    private const val BOOT_COMPLETE_DELAY_MS = 60000L

    @Volatile
    private var bootComplete = false

    /** 系统是否已完成启动。未就绪前广播/通知类 Hook 一律不介入。 */
    @JvmStatic
    fun isBootComplete(): Boolean = bootComplete

    /** 用户解锁（或已解锁）时由 XposedModule 调用，启动计时。 */
    @JvmStatic
    fun onUserUnlocked() {
        if ("android" == FcmselfLog.selfPackageName) {
            val bootTimer = Thread({
                try {
                    Thread.sleep(BOOT_COMPLETE_DELAY_MS)
                    bootComplete = true
                    FcmselfLog.log("Boot Complete")
                } catch (e: Throwable) {
                    FcmselfLog.log(e.message ?: "null")
                }
            }, "fcmself-boot-complete")
            bootTimer.isDaemon = true
            bootTimer.start()
        } else {
            bootComplete = true
        }
    }
}
