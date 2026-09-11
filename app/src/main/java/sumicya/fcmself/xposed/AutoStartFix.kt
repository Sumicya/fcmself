package sumicya.fcmself.xposed

import android.content.Intent

import java.lang.reflect.Method

import io.github.libxposed.api.XposedInterface

import sumicya.fcmself.util.Hooks
import sumicya.fcmself.util.Reflect
import sumicya.fcmself.xposed.XposedModule.Companion.intentOfField
import sumicya.fcmself.xposed.XposedModule.Companion.printLog
import sumicya.fcmself.xposed.XposedModule.Companion.targetOf

/**
 * AutoStartFix - 自启动限制修复
 *
 * Hook 各 ROM 的自启动拦截点：当被拦截的广播是 FCM 消息时放行，允许已停止的应用被唤醒。
 * 覆盖 ColorOS / OxygenOS（OplusAppStartupManager）与 MIUI / HyperOS（BroadcastQueue*
 * 系列、AutoStartManagerServiceStubImpl、SmartPowerService / SmartPowerPolicyManager）。
 *
 * 每个 hook 点独立 try/catch：某台设备没有对应类/方法时只记录日志并跳过，不影响其它点。
 * 与其它 Fix 模块一致，本模块对所有 FCM 目标应用生效（无白名单）。
 */
class AutoStartFix(api: XposedInterface, classLoader: ClassLoader) : XposedModule(api, classLoader) {

    init {
        try {
            startHook()
        } catch (e: Throwable) {
            printLog("hook error AutoStartFix:" + e.message)
        }
    }

    private fun startHook() {
        hookMiui12()
        hookMiui13()
        hookHyperos()
        hookIsAllowStartService()
        hookSmartPower()
        hookOplus()
        hookMiuiPowerPolicy()
    }

    /** MIUI 12：BroadcastQueueInjector.checkApplicationAutoStart */
    private fun hookMiui12() {
        try {
            val clazz = Reflect.findClass("com.android.server.am.BroadcastQueueInjector", classLoader)
            val method = Reflect.findMethodMostParams(clazz, "checkApplicationAutoStart")
                ?: throw NoSuchMethodError(clazz.name + "#checkApplicationAutoStart")
            Hooks.hook(api, method) { chain ->
                val args = chain.args.toTypedArray()
                val intent = if (args.size > 2) intentOfField(args[2]) else null
                if (isFCMIntent(intent)) {
                    val target = targetOf(intent)
                    if (hasTargetPackage(target)) {
                        Reflect.callStaticMethod(clazz, "checkAbnormalBroadcastInQueueLocked", args[1], args[0])
                        printLog("Allow Auto Start: " + target, true)
                        return@hook true
                    }
                }
                chain.proceed()
            }
        } catch (e: Reflect.ClassNotFound) {
            printLog("No Such Method com.android.server.am.BroadcastQueueInjector.checkApplicationAutoStart")
        } catch (e: NoSuchMethodError) {
            printLog("No Such Method com.android.server.am.BroadcastQueueInjector.checkApplicationAutoStart")
        }
    }

    /** MIUI 13：BroadcastQueueImpl.checkApplicationAutoStart */
    private fun hookMiui13() {
        try {
            val clazz = Reflect.findClass("com.android.server.am.BroadcastQueueImpl", classLoader)
            val method = Reflect.findMethodMostParams(clazz, "checkApplicationAutoStart")
                ?: throw NoSuchMethodError(clazz.name + "#checkApplicationAutoStart")
            Hooks.hook(api, method) { chain ->
                val args = chain.args.toTypedArray()
                val intent = if (args.size > 1) intentOfField(args[1]) else null
                if (isFCMIntent(intent)) {
                    val target = targetOf(intent)
                    if (hasTargetPackage(target)) {
                        Reflect.callMethod(chain.thisObject, "checkAbnormalBroadcastInQueueLocked", args[0])
                        printLog("Allow Auto Start: " + target, true)
                        return@hook true
                    }
                }
                chain.proceed()
            }
        } catch (e: Reflect.ClassNotFound) {
            printLog("No Such Method com.android.server.am.BroadcastQueueImpl.checkApplicationAutoStart")
        } catch (e: NoSuchMethodError) {
            printLog("No Such Method com.android.server.am.BroadcastQueueImpl.checkApplicationAutoStart")
        }
    }

    /** HyperOS：BroadcastQueueModernStubImpl.checkApplicationAutoStart 与 checkReceiverIfRestricted */
    private fun hookHyperos() {
        try {
            val clazz = Reflect.findClass("com.android.server.am.BroadcastQueueModernStubImpl", classLoader)
            val autoStart = Reflect.findMethodMostParams(clazz, "checkApplicationAutoStart")
            val receiverRestricted = Reflect.findMethodMostParams(clazz, "checkReceiverIfRestricted")

            if (autoStart != null) {
                Hooks.hook(api, autoStart) { chain ->
                    val args = chain.args.toTypedArray()
                    val intent = if (args.size > 1) intentOfField(args[1]) else null
                    if (intent != null && hasTargetPackage(targetOf(intent))) {
                        printLog("checkApplicationAutoStart package_name: " + targetOf(intent), true)
                        return@hook true
                    }
                    chain.proceed()
                }
            }

            if (receiverRestricted != null) {
                Hooks.hook(api, receiverRestricted) { chain ->
                    val args = chain.args.toTypedArray()
                    val intent = if (args.size > 1) intentOfField(args[1]) else null
                    if (isFCMIntent(intent) && hasTargetPackage(targetOf(intent))) {
                        printLog("BroadcastQueueModernStubImpl.checkReceiverIfRestricted package_name: " +
                            targetOf(intent), true)
                        return@hook false
                    }
                    chain.proceed()
                }
            }

            if (autoStart == null && receiverRestricted == null) {
                throw NoSuchMethodError(clazz.name)
            }
        } catch (e: Reflect.ClassNotFound) {
            printLog("No Such class com.android.server.am.BroadcastQueueModernStubImpl")
        } catch (e: NoSuchMethodError) {
            printLog("No Such class com.android.server.am.BroadcastQueueModernStubImpl")
        }
    }

    /** MIUI / HyperOS：AutoStartManagerServiceStubImpl.isAllowStartService（3 参或 4 参） */
    private fun hookIsAllowStartService() {
        try {
            val clazz = Reflect.findClass("com.android.server.am.AutoStartManagerServiceStubImpl", classLoader)
            var method = Reflect.findMethodByParamCount(clazz, "isAllowStartService", 4)
            if (method == null) method = Reflect.findMethodByParamCount(clazz, "isAllowStartService", 3)
            if (method == null) throw NoSuchMethodError(clazz.name + "#isAllowStartService")
            Hooks.hook(api, method) { chain ->
                val args = chain.args.toTypedArray()
                val intent = if (args.size > 1 && args[1] is Intent) args[1] as Intent else null
                if (intent != null && hasTargetPackage(targetOf(intent))) {
                    printLog("AutoStartManagerServiceStubImpl.isAllowStartService package_name: " +
                        targetOf(intent), true)
                    return@hook true
                }
                chain.proceed()
            }
        } catch (e: Reflect.ClassNotFound) {
            printLog("No Such Class com.android.server.am.AutoStartManagerServiceStubImpl.isAllowStartService")
        } catch (e: NoSuchMethodError) {
            printLog("No Such Class com.android.server.am.AutoStartManagerServiceStubImpl.isAllowStartService")
        }
    }

    /** MIUI / HyperOS：SmartPowerService.shouldInterceptBroadcast */
    private fun hookSmartPower() {
        try {
            val clazz = Reflect.findClass("com.android.server.am.SmartPowerService", classLoader)
            val method = Reflect.findMethodMostParams(clazz, "shouldInterceptBroadcast")
                ?: throw NoSuchMethodError(clazz.name + "#shouldInterceptBroadcast")
            Hooks.hook(api, method) { chain ->
                val args = chain.args.toTypedArray()
                val intent = if (args.size > 1) intentOfField(args[1]) else null
                if (isFCMIntent(intent) && hasTargetPackage(targetOf(intent))) {
                    printLog("SmartPowerService.shouldInterceptBroadcast package_name: " + targetOf(intent), true)
                    return@hook false
                }
                chain.proceed()
            }
        } catch (e: Reflect.ClassNotFound) {
            printLog("No Such Class com.android.server.am.SmartPowerService")
        } catch (e: NoSuchMethodError) {
            printLog("No Such Class com.android.server.am.SmartPowerService")
        }
    }

    /** ColorOS 15 / OxygenOS 15：OplusAppStartupManager.shouldPreventSendReceiverReal */
    private fun hookOplus() {
        try {
            val clazz = Reflect.findClass("com.android.server.am.OplusAppStartupManager", classLoader)
            val method = Reflect.findMethodByParamCount(clazz, "shouldPreventSendReceiverReal", 4)
                ?: throw NoSuchMethodError(clazz.name + "#shouldPreventSendReceiverReal")
            Hooks.hook(api, method) { chain ->
                val holder = chain.getArg(0)
                if (holder != null) {
                    val intent = intentOfField(holder)
                    if (isFCMIntent(intent) && hasTargetPackage(intent?.getPackage())) {
                        // 不调用 chain.proceed()，直接返回 false = 放行这条广播
                        return@hook false
                    }
                }
                chain.proceed()
            }
        } catch (e: Reflect.ClassNotFound) {
            printLog("No Such Method com.android.server.am.OplusAppStartupManager.shouldPreventSendReceiverReal")
        } catch (e: NoSuchMethodError) {
            printLog("No Such Method com.android.server.am.OplusAppStartupManager.shouldPreventSendReceiverReal")
        }
    }

    /** MIUI 13：SmartPowerPolicyManager.shouldInterceptService */
    private fun hookMiuiPowerPolicy() {
        try {
            val clazz = Reflect.findClass("com.miui.server.smartpower.SmartPowerPolicyManager", classLoader)
            val method = Reflect.findMethodMostParams(clazz, "shouldInterceptService")
                ?: throw NoSuchMethodError(clazz.name + "#shouldInterceptService")
            Hooks.hook(api, method) { chain ->
                val result = chain.proceed()
                val arg0 = chain.getArg(0)
                if (arg0 is Intent && "com.google.firebase.MESSAGING_EVENT" == arg0.action) {
                    val target = targetOf(arg0)
                    if (hasTargetPackage(target)) {
                        printLog("Disable MIUI Intercept: " + target, true)
                        return@hook false
                    }
                }
                result
            }
        } catch (e: Reflect.ClassNotFound) {
            printLog("No Such Method com.miui.server.smartpower.SmartPowerPolicyManager.shouldInterceptService")
        } catch (e: NoSuchMethodError) {
            printLog("No Such Method com.miui.server.smartpower.SmartPowerPolicyManager.shouldInterceptService")
        }
    }
}
