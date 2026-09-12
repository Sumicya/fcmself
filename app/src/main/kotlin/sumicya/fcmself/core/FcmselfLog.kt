package sumicya.fcmself.core

import android.util.Log

/**
 * fcmself 统一日志。
 *
 * 职责只有一件事：把一行日志写进 logcat（tag 固定为 [TAG]）。
 * 其它去向由宿主进程启动时注入的 [sink] 决定（见 [LogSink]）：
 *
 * - 普通日志额外写入 LSPosed 框架日志（`XposedInterface.log`）；
 * - 诊断日志（diagnostics=true）额外广播转发到 GMS 日志，在 FCM Diagnostics 页面可查。
 *
 * 本类只依赖 android.util.Log，是 core 包「零模块内依赖」的一部分：
 * 框架接口、Context 等能力全部通过 [LogSink] 抽象注入，杜绝旧版 util→xposed 的反向依赖。
 */
object FcmselfLog {

    const val TAG = "FcmSelf"

    /** 当前进程身份：system_server 为 "android"，GMS 为包名；入口初始化前为 UNKNOWN。 */
    @Volatile
    var processName: String = "UNKNOWN"

    /** 日志去向（框架日志 / 诊断广播），由入口在进程初始化时安装；未安装时只写 logcat。 */
    @Volatile
    var sink: LogSink? = null

    fun log(text: String, diagnostics: Boolean = false) {
        Log.d(TAG, text)
        val line = "[fcmself] [$processName]$text"
        try {
            sink?.log(line, diagnostics)
        } catch (_: Throwable) {
            // sink 不可用时忽略：logcat 里已经有一份
        }
    }
}

/** 每条日志的额外去向（框架日志 / 诊断广播），由宿主进程实现并注入。 */
fun interface LogSink {
    fun log(line: String, diagnostics: Boolean)
}
