package sumicya.fcmself.xposed

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.UserManager

import io.github.libxposed.api.XposedInterface

import sumicya.fcmself.config.FcmselfConfig
import sumicya.fcmself.util.FcmselfLog
import sumicya.fcmself.util.Hooks
import sumicya.fcmself.util.Reflect

import android.content.Context.NOTIFICATION_SERVICE

/**
 * 所有 fcmself Hook 模块的基类。
 *
 * 职责：
 * - 捕获本进程的 [Context]（Hook ContextWrapper.attachBaseContext），
 *   并在用户解锁后触发配置加载；
 * - 维护模块实例列表，配置就绪后逐个回调 [onCanReadConfig]；
 * - 提供各 Fix 模块共用的工具：日志（委托 [FcmselfLog]）、FCM Intent 识别。
 *
 * 具体配置与启动时机逻辑见 [FcmselfConfig]。
 */
abstract class XposedModule(
    protected val api: XposedInterface,
    protected val classLoader: ClassLoader
) {

    init {
        synchronized(instances) {
            instances.add(this)
            if (instances.size == 1) {
                initContext(api, classLoader)
            } else if (context != null && isUserUnlocked()) {
                safeOnCanReadConfig(this)
            }
        }
    }

    /** 用户解锁、运行期状态就绪后回调，默认空实现。 */
    protected open fun onCanReadConfig() {}

    // ------------------------------------------------------------------
    // 供 Fix 模块使用的工具方法
    // ------------------------------------------------------------------

    /** 广播/通知是否有明确的目标应用包名。本模块没有白名单：只要目标包名非空就介入。 */
    protected fun hasTargetPackage(packageName: String?): Boolean = !packageName.isNullOrEmpty()

    /** 判断 action 是否为 FCM/GCM 相关：c2dm 接收广播、Firebase 消息事件、Firebase 实例 ID 事件。 */
    protected fun isFCMAction(action: String?): Boolean =
        action != null && (action.endsWith(".android.c2dm.intent.RECEIVE") ||
            "com.google.firebase.MESSAGING_EVENT" == action ||
            "com.google.firebase.INSTANCE_ID_EVENT" == action)

    protected fun isFCMIntent(intent: Intent?): Boolean =
        intent != null && isFCMAction(intent.action)

    companion object {

        /** 旧版本模块通知渠道 id，仅用于卸载时清理历史残留 */
        private const val NOTIFICATION_CHANNEL = "fcmself"

        @SuppressLint("StaticFieldLeak")
        @JvmStatic
        var context: Context? = null

        /** 本进程内已创建的模块实例（构造顺序即安装顺序） */
        private val instances = mutableListOf<XposedModule>()
        private var isInitReceiver = false

        private fun isUserUnlocked(): Boolean =
            try {
                context?.getSystemService(UserManager::class.java)?.isUserUnlocked ?: false
            } catch (e: Throwable) {
                false
            }

        private fun initContext(api: XposedInterface, classLoader: ClassLoader) {
            val contextWrapper = Reflect.findClass("android.content.ContextWrapper", classLoader)
            Hooks.hookMethodAfter(api, contextWrapper, "attachBaseContext", arrayOf(Context::class.java)) { chain, _ ->
                if (context == null) {
                    context = chain.thisObject as Context
                    if (isUserUnlocked()) {
                        callAllOnCanReadConfig()
                    } else {
                        val filter = IntentFilter(Intent.ACTION_USER_UNLOCKED)
                        context?.registerReceiver(unlockBroadcastReceive, filter)
                    }
                }
            }
        }

        private val unlockBroadcastReceive = object : BroadcastReceiver() {
            override fun onReceive(_context: Context, intent: Intent) {
                if (Intent.ACTION_USER_UNLOCKED == intent.action) {
                    try {
                        context?.unregisterReceiver(this)
                    } catch (ignored: Throwable) {
                    }
                    callAllOnCanReadConfig()
                }
            }
        }

        /** 用户解锁后统一入口：初始化广播接收器 -> 配置加载/启动计时 -> 逐个模块 onCanReadConfig。 */
        private fun callAllOnCanReadConfig() {
            initReceiver()
            FcmselfConfig.onUserUnlocked()
            val snapshot: List<XposedModule>
            synchronized(instances) {
                snapshot = instances.toList()
            }
            for (instance in snapshot) {
                safeOnCanReadConfig(instance)
            }
        }

        private fun safeOnCanReadConfig(instance: XposedModule) {
            try {
                instance.onCanReadConfig()
            } catch (e: Throwable) {
                FcmselfLog.log("onCanReadConfig 失败: " + e.message)
            }
        }

        // ------------------------------------------------------------------
        // 卸载监听
        // ------------------------------------------------------------------

        private val uninstallReceiver = object : BroadcastReceiver() {
            override fun onReceive(_context: Context, intent: Intent) {
                if (Intent.ACTION_PACKAGE_REMOVED == intent.action &&
                    FcmselfConfig.SELF_PACKAGE == intent.data?.schemeSpecificPart
                ) {
                    val extras = intent.extras
                    if (extras?.containsKey(Intent.EXTRA_REPLACING) == true &&
                        extras.getBoolean(Intent.EXTRA_REPLACING)
                    ) {
                        return
                    }
                    onUninstallSelf()
                    if ("android" == FcmselfLog.selfPackageName) {
                        FcmselfLog.log("Fcmself已卸载，重启后停止生效。")
                    }
                }
            }
        }

        private fun initReceiver() {
            if (!isInitReceiver && context != null) {
                isInitReceiver = true
                val unInstallIntentFilter = IntentFilter(Intent.ACTION_PACKAGE_REMOVED)
                unInstallIntentFilter.addDataScheme("package")
                context?.registerReceiver(uninstallReceiver, unInstallIntentFilter)
            }
        }

        private fun onUninstallSelf() {
            val notificationManager =
                context?.getSystemService(NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL)
            if (channel != null) {
                notificationManager.deleteNotificationChannel(channel.id)
            }
        }

        // ------------------------------------------------------------------
        // 供 Fix 模块（含 companion 静态方法）使用的工具方法
        // ------------------------------------------------------------------

        @JvmStatic
        fun printLog(text: String) = FcmselfLog.log(text)

        @JvmStatic
        fun printLog(text: String, isDiagnosticsLog: Boolean) = FcmselfLog.log(text, isDiagnosticsLog)

        /** 从携带 intent 字段的广播参数对象中取出 Intent（各 ROM 参数结构不同，按对象字段反射）。 */
        @JvmStatic
        fun intentOfField(holder: Any?): Intent? =
            try {
                holder?.let { Reflect.getObjectField(it, "intent") as? Intent }
            } catch (e: Throwable) {
                null
            }

        /** 从定向 Intent 中解析目标包名（显式 component 优先，其次 package）。 */
        @JvmStatic
        fun targetOf(intent: Intent?): String? {
            if (intent == null) return null
            return intent.component?.packageName ?: intent.getPackage()
        }
    }
}
