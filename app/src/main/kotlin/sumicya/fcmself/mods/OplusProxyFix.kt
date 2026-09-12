package sumicya.fcmself.mods

import android.content.pm.PackageManager
import android.os.SystemClock
import android.os.WorkSource

import io.github.libxposed.api.XposedInterface

import java.util.concurrent.ConcurrentHashMap

import sumicya.fcmself.FcmselfModule
import sumicya.fcmself.ProcessEnv
import sumicya.fcmself.core.FcmselfLog
import sumicya.fcmself.core.Push
import sumicya.fcmself.hook.Hooks
import sumicya.fcmself.hook.Reflect

/**
 * OplusProxyFix —— OPPO / OnePlus（ColorOS / OxygenOS）专用修复。
 *
 * 1. 捕获 `OplusProxyWakeLock` 实例，供 [unfreeze] 解冻目标应用（BroadcastFix 调用）；
 * 2. `OplusProxyBroadcast.shouldProxy`：推送广播返回 NOT_INCLUDE，不走代理检查；
 * 3. Hans 后台管理：阻止注册/更新 GMS 限制（no-op 替换）；
 * 4. `OplusStartupStrategy.isGoogleRestricInfoOn` 恒返回 false。
 *
 * 状态全部收进 [OplusProxy] 单例：跨 hook 线程访问，volatile / 同步保证可见性
 * （旧版的裸 companion 字段没有任何同步措施）。
 */
internal class OplusProxyFix(api: XposedInterface, classLoader: ClassLoader) :
    FcmselfModule(api, classLoader, "OplusProxyFix") {

    init {
        point("OplusProxyWakeLock 构造捕获") { hookWakelockConstructor() }
        point("OplusProxyBroadcast#shouldProxy") { hookShouldProxy() }
        point("Hans GMS 限制") {
            noOp("com.android.server.hans.scene.OplusBgSceneManager", "registerGmsRestrictObserver")
            noOp("com.android.server.hans.scene.OplusBgSceneManager", "updateGmsRestrict")
        }
        point("OplusStartupStrategy#isGoogleRestricInfoOn") { hookGoogleRestrict() }
    }

    /** Hook OplusProxyWakeLock 构造函数，保存实例引用供 [unfreeze] 使用。 */
    private fun hookWakelockConstructor() {
        val className = "com.android.server.power.OplusProxyWakeLock"
        val clazz = Reflect.findClass(className, classLoader)
        // 旧版对「参数最多的构造器」的意图被 >= 比较扭曲成了「最后一个构造器」；
        // 这里改为明确的「参数最多」（ROM 常在尾部加参数）。
        val constructor = Reflect.findConstructorMostParams(clazz)
        Hooks.hookAfter(api, constructor) { chain, _ ->
            if (OplusProxy.capture(chain.thisObject)) {
                FcmselfLog.log("OplusProxyWakeLock instance captured")
            } else {
                FcmselfLog.log("warn: OplusProxyWakeLock constructed multiple times!")
            }
        }
    }

    /** Hook OplusProxyBroadcast.shouldProxy：推送广播返回 NOT_INCLUDE 以绕过代理检查。 */
    private fun hookShouldProxy() {
        val className = "com.android.server.am.OplusProxyBroadcast"
        val clazz = Reflect.findClass(className, classLoader)
        val resultEnum = Reflect.findClass("$className\$RESULT", classLoader)
        val notInclude = Reflect.getStaticObjectField(resultEnum, "NOT_INCLUDE")
        val method = Reflect.findMethodByParamCount(clazz, "shouldProxy", 8)
            ?: throw NoSuchMethodError("$className#shouldProxy(8 参)")
        Hooks.hook(api, method) { chain ->
            // 形如 caller=com.google.android.gms, pkg=<目标应用>, action=com.google.android.c2dm.intent.RECEIVE
            val callingPkg = chain.getArg(3) as? String
            val pkgName = chain.getArg(5) as? String
            val action = chain.getArg(6) as? String
            if (Push.isPushAction(action) && Push.hasTarget(pkgName)) {
                OplusProxy.logBypassThrottled(pkgName!!, callingPkg, action)
                // 不调用 chain.proceed()：这条广播不走代理
                return@hook notInclude
            }
            chain.proceed()
        }
    }

    /** 把 className#methodName（无参）替换成空实现：原方法完全不执行，返回 null。 */
    private fun noOp(className: String, methodName: String) {
        val method = Reflect.findMethodExact(Reflect.findClass(className, classLoader), methodName)
        Hooks.hook(api, method) { null }
        FcmselfLog.log("$className#$methodName hooked")
    }

    /** 阻止 GMS 限制检查：isGoogleRestricInfoOn 恒返回 false。 */
    private fun hookGoogleRestrict() {
        val className = "com.android.server.am.OplusAppStartupManager\$OplusStartupStrategy"
        val method = Reflect.findMethodExact(
            Reflect.findClass(className, classLoader),
            "isGoogleRestricInfoOn",
            Int::class.javaPrimitiveType!!,
        )
        Hooks.hook(api, method) { false }
        FcmselfLog.log("isGoogleRestricInfoOn hooked")
    }
}

/**
 * OplusProxyWakeLock 实例与 unfreezeIfNeed 签名探测结果的持有者。
 *
 * 首次调用时自动探测 unfreezeIfNeed 的参数个数（3 参或 4 参），之后复用缓存签名；
 * 探测与调用全程同步，避免旧版「并发首调可能双双走探测路径」的竞态。
 */
internal object OplusProxy {

    private const val WAKELOCK_TAG = "FCMXX"
    private const val WAKELOCK_OWNER = "FcmSelf"

    /** 同一目标包的 bypass 日志最小间隔（聊天类应用推送密集，逐条打印会刷满 logcat）。 */
    private const val BYPASS_LOG_INTERVAL_MS = 60_000L

    /** 「从未打印过」的时刻哨兵（elapsedRealtime 从开机算起，初值不能用 0）。 */
    private const val NEVER_PRINTED = -BYPASS_LOG_INTERVAL_MS * 2

    @Volatile
    private var wakelock: Any? = null

    private val detectMonitor = Any()

    /** unfreezeIfNeed 是否为 4 参签名（首次调用时探测，之后复用）。 */
    private var fourParams = false

    /** 签名是否已探测完成（配合 [detectMonitor] 使用）。 */
    private var detected = false

    /** 包名 -> {上次打印时刻, 期间被抑制条数}。 */
    private val bypassLogState = ConcurrentHashMap<String, LongArray>()

    /** 捕获实例；已有实例时返回 false（重复构造告警用）。 */
    @Synchronized
    fun capture(instance: Any?): Boolean {
        if (wakelock != null || instance == null) return false
        wakelock = instance
        return true
    }

    /** 解冻指定包名的应用（由 BroadcastFix 在放行推送广播时调用）。 */
    fun unfreeze(target: String?) {
        val lock = wakelock ?: return
        val uid = uidOf(target) ?: return
        val workSource = WorkSource()
        synchronized(detectMonitor) {
            if (!detected) {
                // 首次调用：先试 4 参签名（ROM 加参后的新形态），失败回落 3 参
                detected = true
                fourParams = try {
                    invoke(lock, uid, workSource, true)
                    FcmselfLog.log("unfreezeIfNeed using 4 params: uid=$uid, pkg=$target")
                    true
                } catch (t: Throwable) {
                    invoke(lock, uid, workSource, false)
                    FcmselfLog.log("unfreezeIfNeed using 3 params: uid=$uid, pkg=$target")
                    false
                }
            } else {
                try {
                    invoke(lock, uid, workSource, fourParams)
                    FcmselfLog.log("unfreeze: $target, uid=$uid")
                } catch (_: Throwable) {
                    // unfreeze 失败不影响广播放行，静默
                }
            }
        }
    }

    /** 按包名节流打印 bypass 日志（见 [BYPASS_LOG_INTERVAL_MS]）。 */
    fun logBypassThrottled(pkgName: String, callingPkg: String?, action: String?) {
        val now = SystemClock.elapsedRealtime()
        val state = bypassLogState.computeIfAbsent(pkgName) { longArrayOf(NEVER_PRINTED, 0) }
        synchronized(state) {
            val suppressed = state[1]
            if (now - state[0] < BYPASS_LOG_INTERVAL_MS) {
                state[1] = suppressed + 1
                return
            }
            state[0] = now
            state[1] = 0
            FcmselfLog.log(
                "shouldProxy bypass: pkg=$pkgName, caller=$callingPkg, action=$action" +
                    if (suppressed > 0) "（期间另有 $suppressed 条同类日志已抑制）" else ""
            )
        }
    }

    private fun invoke(wakelock: Any, uid: Int, workSource: WorkSource, fourParams: Boolean) {
        if (fourParams) {
            Reflect.callMethod(wakelock, "unfreezeIfNeed", uid, workSource, WAKELOCK_TAG, WAKELOCK_OWNER)
        } else {
            Reflect.callMethod(wakelock, "unfreezeIfNeed", uid, workSource, WAKELOCK_TAG)
        }
    }

    private fun uidOf(target: String?): Int? {
        if (target == null) return null
        val context = ProcessEnv.context ?: return null
        val uid = try {
            context.packageManager.getPackageUid(target, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            FcmselfLog.log("error: Package not found: $target")
            -1
        }
        return uid.takeIf { it >= 0 }
    }
}
