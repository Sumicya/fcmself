package sumicya.fcmself.xposed

import android.content.pm.PackageManager
import android.os.SystemClock
import android.os.WorkSource

import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

import io.github.libxposed.api.XposedInterface

import sumicya.fcmself.util.Hooks
import sumicya.fcmself.util.Reflect
import sumicya.fcmself.xposed.XposedModule.Companion.printLog

/**
 * OPPO/OnePlus ColorOS 专用 FCM 修复模块
 * 主要功能：
 * 1. 绕过 OplusProxyWakeLock 冻结机制
 * 2. 阻止 OplusProxyBroadcast 拦截 FCM 广播
 * 3. 禁用 Hans 后台管理系统对 GMS 的限制
 */
class OplusProxyFix(api: XposedInterface, classLoader: ClassLoader) : XposedModule(api, classLoader) {

    init {
        try {
            startHookOplusProxyWakeLock()
            startHookOplusProxyBroadcast()
        } catch (e: Throwable) {
            printLog("hook error OplusProxy: " + e.message)
        }
        try {
            startHookRegisterGmsRestrictObserver()
        } catch (e: Throwable) {
            printLog("hook error registerGmsRestrictObserver: " + e.message)
        }
        try {
            startHookUpdateGmsRestrict()
        } catch (e: Throwable) {
            printLog("hook error updateGmsRestrict: " + e.message)
        }
        try {
            startHookIsGoogleRestricInfoOn()
        } catch (e: Throwable) {
            printLog("hook error isGoogleRestricInfoOn: " + e.message)
        }
    }

    /** Hook OplusProxyBroadcast.shouldProxy：FCM 广播返回 NOT_INCLUDE 以绕过代理检查。 */
    private fun startHookOplusProxyBroadcast() {
        val oplusProxyBroadcastClass = Reflect.findClass("com.android.server.am.OplusProxyBroadcast", classLoader)
        val resultEnum = Reflect.findClass("com.android.server.am.OplusProxyBroadcast\$RESULT", classLoader)
        val notIncludeValue = Reflect.getStaticObjectField(resultEnum, "NOT_INCLUDE")
        val shouldProxy = Reflect.findMethodByParamCount(oplusProxyBroadcastClass, "shouldProxy", 8)
            ?: throw NoSuchMethodError(oplusProxyBroadcastClass.name + "#shouldProxy")
        Hooks.hook(api, shouldProxy) { chain ->
            val callingPkg = chain.getArg(3) as String?
            val pkgName = chain.getArg(5) as String?
            val action = chain.getArg(6) as String?
            // 示例：caller=com.google.android.gms, action=com.google.android.c2dm.intent.RECEIVE
            if (isFCMAction(action) && hasTargetPackage(pkgName)) {
                logBypassThrottled(pkgName!!, callingPkg, action)
                // 不调用 chain.proceed()，直接返回 NOT_INCLUDE = 这条广播不走代理
                return@hook notIncludeValue
            }
            chain.proceed()
        }
    }

    /** Hook OplusProxyWakeLock 构造函数，保存实例引用供后续使用。 */
    private fun startHookOplusProxyWakeLock() {
        val oplusWakelockClass = Reflect.findClass("com.android.server.power.OplusProxyWakeLock", classLoader)
        val constructor: Constructor<*> = Reflect.findConstructorMostMatch(oplusWakelockClass)
        Hooks.hookAfter(api, constructor) { chain, _ ->
            if (oplusProxyWakeLock != null) {
                printLog("warn: OplusProxyWakeLock constructed multiple times!")
                return@hookAfter
            }
            oplusProxyWakeLock = chain.thisObject
            printLog("OplusProxyWakeLock instance captured")
        }
    }

    /** 阻止 Hans 注册 GMS 限制观察者 */
    private fun startHookRegisterGmsRestrictObserver() {
        hookNoOp("com.android.server.hans.scene.OplusBgSceneManager", "registerGmsRestrictObserver")
        printLog("registerGmsRestrictObserver hooked")
    }

    /** 把 className#methodName()（无参）替换成空实现：原方法完全不执行，返回 null。 */
    private fun hookNoOp(className: String, methodName: String) {
        val method = Reflect.findMethodExact(Reflect.findClass(className, classLoader), methodName)
        Hooks.hook(api, method) { null }
    }

    /** 阻止 Hans 更新 GMS 限制状态 */
    private fun startHookUpdateGmsRestrict() {
        hookNoOp("com.android.server.hans.scene.OplusBgSceneManager", "updateGmsRestrict")
        printLog("updateGmsRestrict hooked")
    }

    /** 阻止 GMS 限制检查 */
    private fun startHookIsGoogleRestricInfoOn() {
        val method = Reflect.findMethodExact(
            Reflect.findClass("com.android.server.am.OplusAppStartupManager\$OplusStartupStrategy", classLoader),
            "isGoogleRestricInfoOn", Int::class.javaPrimitiveType!!)
        // 不调用 chain.proceed()，恒定返回 false
        Hooks.hook(api, method) { false }
        printLog("isGoogleRestricInfoOn hooked")
    }

    companion object {
        /** 同一目标包的 shouldProxy bypass 日志最小间隔（聊天类应用 FCM 消息密集，逐条打印会刷满 logcat）。 */
        private const val BYPASS_LOG_INTERVAL_MS = 60_000L

        /** 包名 -> {上次打印时刻, 期间被抑制条数} */
        private val BYPASS_LOG_STATE = ConcurrentHashMap<String, LongArray>()

        /** "从未打印过"的时刻哨兵（elapsedRealtime 从开机算起，初值不能用 0）。 */
        private const val NEVER_PRINTED = -BYPASS_LOG_INTERVAL_MS * 2

        private var oplusProxyWakeLock: Any? = null
        @Volatile
        private var useFourParams = false
        @Volatile
        private var signatureDetected = false

        /** 根据包名获取对应的 UID */
        private fun getTargetUidFromPackageName(packageName: String?): Int {
            if (packageName == null) return -1
            return try {
                XposedModule.context?.packageManager?.getPackageUid(packageName, 0) ?: -1
            } catch (e: PackageManager.NameNotFoundException) {
                printLog("error: Package not found: " + packageName)
                -1
            }
        }

        /** 按包名节流的 bypass 日志（见 [BYPASS_LOG_INTERVAL_MS]）。 */
        private fun logBypassThrottled(pkgName: String, callingPkg: String?, action: String?) {
            val now = SystemClock.elapsedRealtime()
            val state = BYPASS_LOG_STATE.computeIfAbsent(pkgName) { longArrayOf(NEVER_PRINTED, 0) }
            synchronized(state) {
                if (now - state[0] < BYPASS_LOG_INTERVAL_MS) {
                    state[1]++
                    return
                }
                val suppressed = state[1]
                state[0] = now
                state[1] = 0
                printLog("shouldProxy bypass: pkg=" + pkgName + ", caller=" + callingPkg +
                    ", action=" + action +
                    if (suppressed > 0) "（期间另有 " + suppressed + " 条同类日志已抑制）" else "")
            }
        }

        /**
         * 解冻指定包名的应用：首次调用时自动探测 unfreezeIfNeed 的参数个数（3 参或 4 参），
         * 之后复用缓存的签名。
         */
        @JvmStatic
        fun unfreeze(target: String?) {
            val wakelock = oplusProxyWakeLock ?: return
            val uid = getTargetUidFromPackageName(target)
            if (uid < 0) return

            val workSource = WorkSource()
            val tag = "FCMXX"

            if (!signatureDetected) {
                try {
                    invokeUnfreeze(wakelock, uid, workSource, tag, true)
                    useFourParams = true
                    printLog("unfreezeIfNeed using 4 params: uid=" + uid + ", pkg=" + target)
                } catch (e: Throwable) {
                    invokeUnfreeze(wakelock, uid, workSource, tag, false)
                    useFourParams = false
                    printLog("unfreezeIfNeed using 3 params: uid=" + uid + ", pkg=" + target)
                }
                signatureDetected = true
            } else {
                try {
                    invokeUnfreeze(wakelock, uid, workSource, tag, useFourParams)
                    printLog("unfreeze: " + target + ", uid=" + uid)
                } catch (ignored: Throwable) {
                    // 静默失败
                }
            }
        }

        /** 按当前已知的参数个数调用 unfreezeIfNeed。 */
        private fun invokeUnfreeze(wakelock: Any, uid: Int, workSource: WorkSource, tag: String, fourParams: Boolean) {
            if (fourParams) {
                Reflect.callMethod(wakelock, "unfreezeIfNeed", uid, workSource, tag, "FcmSelf")
            } else {
                Reflect.callMethod(wakelock, "unfreezeIfNeed", uid, workSource, tag)
            }
        }
    }
}
