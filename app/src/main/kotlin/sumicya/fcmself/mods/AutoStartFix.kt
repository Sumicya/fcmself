package sumicya.fcmself.mods

import android.content.Intent

import io.github.libxposed.api.XposedInterface

import sumicya.fcmself.FcmselfModule
import sumicya.fcmself.core.FcmselfLog
import sumicya.fcmself.core.Push
import sumicya.fcmself.hook.Hooks
import sumicya.fcmself.hook.Reflect

/**
 * AutoStartFix —— 自启动限制修复。
 *
 * 放行被 ROM 拦下的推送广播，允许已停止的应用被 FCM 唤醒。覆盖：
 * - MIUI 12：`BroadcastQueueInjector.checkApplicationAutoStart`
 * - MIUI 13：`BroadcastQueueImpl.checkApplicationAutoStart`
 * - HyperOS：`BroadcastQueueModernStubImpl.checkApplicationAutoStart` / `checkReceiverIfRestricted`
 * - MIUI / HyperOS：`AutoStartManagerServiceStubImpl.isAllowStartService`、
 *   `SmartPowerService.shouldInterceptBroadcast`、`SmartPowerPolicyManager.shouldInterceptService`
 * - ColorOS / OxygenOS：`OplusAppStartupManager.shouldPreventSendReceiverReal`
 *
 * 统一介入条件（自由化基线）：被拦截对象必须是「目标明确的推送广播」。
 * 定位 Intent 不再依赖固定下标，而是用 [PushArgs] 按两种载体形态扫描实参。
 * 行为变化（相对旧版）：HyperOS 的 checkApplicationAutoStart 与 isAllowStartService
 * 原本对**任意**定向广播放行，本版收敛到推送族；SmartPowerPolicyManager 原本只认
 * MESSAGING_EVENT，本版放宽到完整推送族。
 */
internal class AutoStartFix(api: XposedInterface, classLoader: ClassLoader) :
    FcmselfModule(api, classLoader, "AutoStartFix") {

    init {
        point("MIUI12 BroadcastQueueInjector") {
            hookQueueAutoStart("com.android.server.am.BroadcastQueueInjector", instanceCall = false)
        }
        point("MIUI13 BroadcastQueueImpl") {
            hookQueueAutoStart("com.android.server.am.BroadcastQueueImpl", instanceCall = true)
        }
        point("HyperOS BroadcastQueueModernStubImpl") { hookHyperos() }
        point("AutoStartManagerServiceStubImpl#isAllowStartService") { hookIsAllowStartService() }
        point("SmartPowerService#shouldInterceptBroadcast") { hookSmartPower() }
        point("OplusAppStartupManager#shouldPreventSendReceiverReal") { hookOplus() }
        point("SmartPowerPolicyManager#shouldInterceptService") { hookMiuiPowerPolicy() }
    }

    /**
     * MIUI 12/13 的 checkApplicationAutoStart：推送广播放行（返回 true），
     * 并按旧版行为补调 ROM 的「异常广播检查」留下等价的安全记录。
     * 两者仅在被拦截记录的取参与检查调用的形态上不同（实例/静态）。
     */
    private fun hookQueueAutoStart(className: String, instanceCall: Boolean) {
        val clazz = Reflect.findClass(className, classLoader)
        val method = Reflect.findMethodMostParams(clazz, "checkApplicationAutoStart")
            ?: throw NoSuchMethodError("$className#checkApplicationAutoStart")
        Hooks.hook(api, method) { chain ->
            val args = chain.args.toTypedArray()
            val intent = PushArgs.find(args) ?: return@hook chain.proceed()
            if (instanceCall) {
                Reflect.callMethod(chain.thisObject!!, "checkAbnormalBroadcastInQueueLocked", args.getOrNull(0))
            } else {
                Reflect.callStaticMethod(clazz, "checkAbnormalBroadcastInQueueLocked", args.getOrNull(1), args.getOrNull(0))
            }
            FcmselfLog.log("Allow Auto Start: ${Push.targetOf(intent)}", true)
            true
        }
    }

    /** HyperOS：自启动检查恒放行（true）、接收器限制检查恒放行（false），仅对推送广播生效。 */
    private fun hookHyperos() {
        val className = "com.android.server.am.BroadcastQueueModernStubImpl"
        val clazz = Reflect.findClass(className, classLoader)
        val autoStart = Reflect.findMethodMostParams(clazz, "checkApplicationAutoStart")
        val receiverRestricted = Reflect.findMethodMostParams(clazz, "checkReceiverIfRestricted")

        autoStart?.let { method ->
            Hooks.hook(api, method) { chain ->
                val intent = PushArgs.find(chain.args.toTypedArray()) ?: return@hook chain.proceed()
                FcmselfLog.log("checkApplicationAutoStart package_name: ${Push.targetOf(intent)}", true)
                true
            }
        }
        receiverRestricted?.let { method ->
            Hooks.hook(api, method) { chain ->
                val intent = PushArgs.find(chain.args.toTypedArray()) ?: return@hook chain.proceed()
                FcmselfLog.log("checkReceiverIfRestricted package_name: ${Push.targetOf(intent)}", true)
                false
            }
        }
        if (autoStart == null && receiverRestricted == null) {
            throw NoSuchMethodError("$className#checkApplicationAutoStart / checkReceiverIfRestricted")
        }
    }

    /** MIUI / HyperOS：isAllowStartService（3 参或 4 参）——推送 Intent 即放行。 */
    private fun hookIsAllowStartService() {
        val className = "com.android.server.am.AutoStartManagerServiceStubImpl"
        val clazz = Reflect.findClass(className, classLoader)
        val method = Reflect.findMethodByParamCount(clazz, "isAllowStartService", 4)
            ?: Reflect.findMethodByParamCount(clazz, "isAllowStartService", 3)
            ?: throw NoSuchMethodError("$className#isAllowStartService")
        Hooks.hook(api, method) { chain ->
            val intent = chain.args.filterIsInstance<Intent>().firstOrNull { Push.isTargetedPush(it) }
            if (intent != null) {
                FcmselfLog.log("isAllowStartService package_name: ${Push.targetOf(intent)}", true)
                return@hook true
            }
            chain.proceed()
        }
    }

    /** MIUI / HyperOS：SmartPowerService.shouldInterceptBroadcast —— 推送广播不拦截。 */
    private fun hookSmartPower() {
        val className = "com.android.server.am.SmartPowerService"
        val clazz = Reflect.findClass(className, classLoader)
        val method = Reflect.findMethodMostParams(clazz, "shouldInterceptBroadcast")
            ?: throw NoSuchMethodError("$className#shouldInterceptBroadcast")
        Hooks.hook(api, method) { chain ->
            val intent = PushArgs.find(chain.args.toTypedArray()) ?: return@hook chain.proceed()
            FcmselfLog.log("SmartPowerService.shouldInterceptBroadcast package_name: ${Push.targetOf(intent)}", true)
            false
        }
    }

    /** ColorOS / OxygenOS：shouldPreventSendReceiverReal —— 推送广播放行（false = 不阻止）。 */
    private fun hookOplus() {
        val className = "com.android.server.am.OplusAppStartupManager"
        val clazz = Reflect.findClass(className, classLoader)
        val method = Reflect.findMethodByParamCount(clazz, "shouldPreventSendReceiverReal", 4)
            ?: throw NoSuchMethodError("$className#shouldPreventSendReceiverReal")
        Hooks.hook(api, method) { chain ->
            if (PushArgs.find(chain.args.toTypedArray()) != null) false else chain.proceed()
        }
    }

    /** MIUI 13：SmartPowerPolicyManager.shouldInterceptService —— 推送不拦截（after 改写结果）。 */
    private fun hookMiuiPowerPolicy() {
        val className = "com.miui.server.smartpower.SmartPowerPolicyManager"
        val clazz = Reflect.findClass(className, classLoader)
        val method = Reflect.findMethodMostParams(clazz, "shouldInterceptService")
            ?: throw NoSuchMethodError("$className#shouldInterceptService")
        Hooks.hook(api, method) { chain ->
            val result = chain.proceed()
            val intent = chain.args.filterIsInstance<Intent>().firstOrNull { Push.isTargetedPush(it) }
            if (intent != null) {
                FcmselfLog.log("Disable MIUI Intercept: ${Push.targetOf(intent)}", true)
                false
            } else {
                result
            }
        }
    }
}
