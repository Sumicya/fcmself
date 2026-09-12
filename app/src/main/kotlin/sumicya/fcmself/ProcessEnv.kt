package sumicya.fcmself

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserManager

import io.github.libxposed.api.XposedInterface

import sumicya.fcmself.core.FcmselfLog
import sumicya.fcmself.core.LogSink
import sumicya.fcmself.hook.Hooks
import sumicya.fcmself.hook.Reflect

/**
 * 进程环境 —— 每个被 Hook 进程（system_server / GMS）一份的运行期状态与生命周期。
 *
 * - [context]：Hook `ContextWrapper.attachBaseContext` 捕获的本进程第一个 Context；
 * - [isBootComplete]：广播/通知类 Hook 的介入闸门。GMS 等应用进程一就绪即放行；
 *   system_server 在用户解锁后延迟 [BOOT_COMPLETE_DELAY_MS] 再放行，避开开机早期的
 *   广播/通知风暴（沿用旧版行为：60 秒守护线程计时）；
 * - [onReady]：「context 已捕获 + 用户已解锁」后恰好执行一次的回调登记处，
 *   替代旧版的实例表扇出与两次握手。
 */
object ProcessEnv {

    /** system_server 的进程身份（Xposed 语义下的包名）。 */
    const val SYSTEM_SERVER = "android"

    /** fcmself 模块自身包名（自卸载监听用）。 */
    const val SELF_PACKAGE = "sumicya.fcmself"

    /** 诊断日志广播 action（[FcmselfLog] 的 sink 转发，ReconnectManagerFix 接收）。 */
    const val ACTION_LOG = "sumicya.fcmself.log"

    /** 旧版本模块通知渠道 id，仅用于卸载时清理历史残留。 */
    private const val LEGACY_NOTIFICATION_CHANNEL = "fcmself"

    /** system_server 启动后延迟放行广播/通知类介入的时间。 */
    private const val BOOT_COMPLETE_DELAY_MS = 60_000L

    @Volatile
    var processName: String = "UNKNOWN"
        private set

    @Volatile
    var context: Context? = null
        private set

    @Volatile
    var isBootComplete: Boolean = false
        private set

    @Volatile
    private var hookApi: XposedInterface? = null

    // ---- 就绪协调 ------------------------------------------------------

    private val lock = Any()
    private var ready = false
    private var contextCaptured = false
    private var uninstallRegistered = false
    private val pending = mutableListOf<Pair<String, () -> Unit>>()
    private val firedOwners = HashSet<String>()

    /**
     * 进程初始化：由入口在 onSystemServerStarting / onPackageReady 里调用一次。
     * 安装日志 sink 并 Hook ContextWrapper 捕获 Context，随后各模块构造。
     */
    fun bootstrap(api: XposedInterface, processName: String, classLoader: ClassLoader) {
        hookApi = api
        this.processName = processName
        FcmselfLog.processName = processName
        FcmselfLog.sink = LogSink { line, diagnostics -> dispatchLog(line, diagnostics) }
        captureContext(api, classLoader)
    }

    /**
     * 登记就绪回调：环境未就绪时排队，就绪后执行；已就绪则立即在当前线程执行。
     * 以 [owner]（模块名）去重——同一模块从多处登记（如构造器 + Hook 回调）只执行一次。
     */
    fun onReady(owner: String, action: () -> Unit) {
        synchronized(lock) {
            if (!ready) {
                pending.add(owner to action)
                return
            }
            if (!firedOwners.add(owner)) return
        }
        runAction(owner, action)
    }

    /** 日志去向：普通日志写框架日志；诊断日志改为广播转发到 GMS（FCM Diagnostics 可见）。 */
    private fun dispatchLog(line: String, diagnostics: Boolean) {
        fun toFramework() {
            val api = hookApi ?: return
            try {
                api.log(android.util.Log.INFO, FcmselfLog.TAG, line)
            } catch (_: Throwable) {
                // 框架日志不可用时忽略，logcat 里已经有一份
            }
        }
        if (!diagnostics) {
            toFramework()
            return
        }
        val ctx = context
        if (ctx == null) {
            toFramework()
            return
        }
        try {
            ctx.sendBroadcast(Intent(ACTION_LOG).putExtra("text", line))
        } catch (_: Throwable) {
            toFramework()
        }
    }

    /** Hook 第一个 ContextWrapper.attachBaseContext：应用进程是 Application，system_server 是系统 Context。 */
    private fun captureContext(api: XposedInterface, classLoader: ClassLoader) {
        val contextWrapper = Reflect.findClass("android.content.ContextWrapper", classLoader)
        Hooks.hookMethodAfter(api, contextWrapper, "attachBaseContext", arrayOf(Context::class.java)) { chain, _ ->
            val first: Context = synchronized(lock) {
                if (contextCaptured) null else {
                    contextCaptured = true
                    chain.thisObject as Context
                }
            } ?: return@hookMethodAfter
            context = first
            if (isUserUnlocked(first)) {
                markReady()
            } else {
                try {
                    first.registerReceiver(
                        object : BroadcastReceiver() {
                            override fun onReceive(c: Context, intent: Intent) {
                                if (intent.action != Intent.ACTION_USER_UNLOCKED) return
                                try {
                                    c.unregisterReceiver(this)
                                } catch (_: Throwable) {
                                }
                                markReady()
                            }
                        },
                        IntentFilter(Intent.ACTION_USER_UNLOCKED),
                    )
                } catch (t: Throwable) {
                    FcmselfLog.log("注册 USER_UNLOCKED 失败，直接视为就绪: $t")
                    markReady()
                }
            }
        }
    }

    private fun isUserUnlocked(ctx: Context?): Boolean =
        try {
            ctx?.getSystemService(UserManager::class.java)?.isUserUnlocked ?: false
        } catch (_: Throwable) {
            false
        }

    /** 环境就绪：启动闸门 + 注册自卸载监听 + 执行排队的回调。 */
    private fun markReady() {
        val todo: List<Pair<String, () -> Unit>>
        synchronized(lock) {
            if (ready) return
            ready = true
            todo = pending.toList()
            pending.clear()
            todo.forEach { firedOwners.add(it.first) }
        }
        FcmselfLog.log("进程环境就绪（$processName）")
        registerUninstallListener()
        if (processName == SYSTEM_SERVER) {
            // system_server：解锁后再延迟一段时间才放行广播/通知类介入（守门闸门）
            val bootTimer = Thread({
                try {
                    Thread.sleep(BOOT_COMPLETE_DELAY_MS)
                    isBootComplete = true
                    FcmselfLog.log("Boot Complete")
                } catch (t: Throwable) {
                    FcmselfLog.log("boot timer: ${t.message}")
                }
            }, "fcmself-boot-complete")
            bootTimer.isDaemon = true
            bootTimer.start()
        } else {
            isBootComplete = true
        }
        for ((owner, action) in todo) {
            runAction(owner, action)
        }
    }

    private fun runAction(owner: String, action: () -> Unit) {
        try {
            action()
        } catch (t: Throwable) {
            FcmselfLog.log("onReady[$owner] 失败: $t")
        }
    }

    // ---- 自卸载监听 ----------------------------------------------------

    /** 模块被卸载（非覆盖安装）时清理旧版残留的通知渠道，并在 system_server 里留一条日志。 */
    private fun registerUninstallListener() {
        val ctx = context ?: return
        val already = synchronized(lock) {
            if (uninstallRegistered) true else {
                uninstallRegistered = true
                false
            }
        }
        if (already) return
        val filter = IntentFilter(Intent.ACTION_PACKAGE_REMOVED).apply { addDataScheme("package") }
        ctx.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (intent.action != Intent.ACTION_PACKAGE_REMOVED) return
                    if (intent.data?.schemeSpecificPart != SELF_PACKAGE) return
                    if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
                    deleteLegacyChannel(c)
                    if (processName == SYSTEM_SERVER) {
                        FcmselfLog.log("Fcmself已卸载，重启后停止生效。")
                    }
                }
            },
            filter,
        )
    }

    private fun deleteLegacyChannel(ctx: Context) {
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.getNotificationChannel(LEGACY_NOTIFICATION_CHANNEL)?.let {
            manager.deleteNotificationChannel(it.id)
        }
    }
}
