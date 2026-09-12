package sumicya.fcmself.xposed

import android.content.Intent
import android.os.Build

import java.lang.reflect.Method

import io.github.libxposed.api.XposedInterface

import sumicya.fcmself.config.FcmselfConfig
import sumicya.fcmself.util.Hooks
import sumicya.fcmself.util.MethodArgs
import sumicya.fcmself.util.Reflect
import sumicya.fcmself.xposed.XposedModule.Companion.printLog
import sumicya.fcmself.xposed.XposedModule.Companion.targetOf

/**
 * BroadcastFix - 广播修复模块
 *
 * Hook 系统广播发送流程，确保 FCM/GCM 消息能够正确送达目标应用，
 * 主要解决系统阻止后台应用接收广播的问题（"Failed to broadcast to stopped app"）。
 *
 * 工作原理：
 * 1. Hook broadcastIntentLocked 方法（系统广播发送核心入口，API 29-35 各版本签名不同，
 *    自动定位 intent 与 appOp 参数下标）；
 * 2. 检测到 FCM Intent 且目标包名可解析时，强制添加 FLAG_INCLUDE_STOPPED_PACKAGES
 *    并把 appOp 从 -1 改为 11（正常）；
 * 3. ColorOS：调用 OplusProxyFix.unfreeze 解除 OplusProxy 冻结。
 */
class BroadcastFix(api: XposedInterface, classLoader: ClassLoader) : XposedModule(api, classLoader) {

    init {
        try {
            startHookBroadcastIntentLocked()
        } catch (e: Throwable) {
            printLog("hook error broadcastIntentLocked:" + e.message)
        }
    }

    private fun startHookBroadcastIntentLocked() {
        var targetMethod: Method? = null
        var argsIndex: IntArray? = null

        // Android 15+：广播逻辑移到 BroadcastController
        if (Build.VERSION.SDK_INT >= 35) {
            val m = Reflect.findMethodMostParams(classLoader, "com.android.server.am.BroadcastController", "broadcastIntentLocked")
            if (m != null) {
                targetMethod = m
                argsIndex = resolveBroadcastArgs(m, 3, 13)
            }
        }
        // Android 10-14：仍在 ActivityManagerService
        if (targetMethod == null) {
            targetMethod = Reflect.findMethodMostParams(classLoader, "com.android.server.am.ActivityManagerService", "broadcastIntentLocked")
            if (targetMethod != null) {
                argsIndex = resolveAmsBroadcastArgs(targetMethod)
            }
        }

        if (targetMethod != null && argsIndex != null &&
            argsIndex[0] >= 0 && argsIndex[1] >= 0 &&
            argsIndex[0] < targetMethod.parameters.size &&
            argsIndex[1] < targetMethod.parameters.size &&
            targetMethod.parameters[argsIndex[0]].type == Intent::class.java &&
            targetMethod.parameters[argsIndex[1]].type == Int::class.javaPrimitiveType
        ) {
            createBroadcastIntentLockedHooker(argsIndex[0], argsIndex[1], targetMethod)
        } else {
            printLog("broadcastIntentLocked hook 位置查找失败，fcmself将不会工作。")
        }
    }

    private fun createBroadcastIntentLockedHooker(intentArgsIndex: Int, appOpArgsIndex: Int, method: Method) {
        printLog("Android API: " + Build.VERSION.SDK_INT)
        printLog("appOp_args_index: " + appOpArgsIndex)
        printLog("intent_args_index: " + intentArgsIndex)
        printLog("hook target: " + method.declaringClass.name)

        Hooks.hook(api, method) { chain ->
            if (!FcmselfConfig.isBootComplete()) return@hook chain.proceed()
            // Chain.getArgs() 返回的是不可变列表，要改参数必须整份传回 proceed(args)
            val args = chain.args.toTypedArray()
            if (args[intentArgsIndex] == null) return@hook chain.proceed()
            val intent = args[intentArgsIndex] as Intent
            // 介入条件：Intent 未包含唤醒停止的 pkg 且 Intent 是 FCM
            if ((intent.flags and Intent.FLAG_INCLUDE_STOPPED_PACKAGES) == 0 && isFCMIntent(intent)) {
                val target = targetOf(intent)
                if (hasTargetPackage(target)) {
                    val appOp = args[appOpArgsIndex] as Int
                    if (appOp == APP_OP_NONE) args[appOpArgsIndex] = APP_OP_POST_NOTIFICATION
                    intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    printLog("Add FLAG_INCLUDE_STOPPED_PACKAGES: " + target, true)
                    // cos15 解冻 OplusProxy
                    OplusProxyFix.unfreeze(target!!)
                }
            }
            chain.proceed(args)
        }
    }

    private companion object {
        /** AOSP AppOpsManager.OP_NONE（-1），@hide 常量，只能自己定义。 */
        private const val APP_OP_NONE = -1

        /** AOSP AppOpsManager.OP_POST_NOTIFICATION（11，@hide 常量）。 */
        private const val APP_OP_POST_NOTIFICATION = 11

        /** 按系统版本解析 broadcastIntentLocked 的 (intent, appOp) 参数下标；无法确定时返回 null。 */
        fun resolveAmsBroadcastArgs(targetMethod: Method): IntArray? {
            val parameters = targetMethod.parameters
            var intentIndex = 0
            var appOpIndex = 0
            when {
                Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> {
                    intentIndex = 2; appOpIndex = 9
                }
                Build.VERSION.SDK_INT == Build.VERSION_CODES.R -> {
                    intentIndex = 3; appOpIndex = 10
                }
                Build.VERSION.SDK_INT == 31 || Build.VERSION.SDK_INT == 32 -> {
                    intentIndex = 3; appOpIndex = MethodArgs.firstIntIndex(parameters, 11, 12)
                }
                Build.VERSION.SDK_INT == 33 -> {
                    intentIndex = 3; appOpIndex = 12
                }
                Build.VERSION.SDK_INT >= 34 -> {
                    intentIndex = 3; appOpIndex = MethodArgs.firstIntIndex(parameters, 12, 13)
                }
            }
            // 版本硬编码失败（未知版本 / 候选下标不是 int）时按参数名兜底；
            // Android framework 通常不保留参数名，兜底路径在多数设备上会返回 null
            return if (intentIndex <= 0 || appOpIndex <= 0) {
                MethodArgs.byName(parameters, Intent::class.java)
            } else {
                intArrayOf(intentIndex, appOpIndex)
            }
        }

        /** 校验 BroadcastController.broadcastIntentLocked 的 (intent, appOp) 下标是否与签名相符。 */
        fun resolveBroadcastArgs(targetMethod: Method, intentIndex: Int, appOpIndex: Int): IntArray? {
            val paramTypes = targetMethod.parameterTypes
            if (MethodArgs.matches(paramTypes, intentIndex, Intent::class.java, appOpIndex)) {
                return intArrayOf(intentIndex, appOpIndex)
            }
            val byName = MethodArgs.byName(targetMethod.parameters, Intent::class.java)
            if (byName == null) {
                printLog("broadcastIntentLocked 参数位置无法确定（API " + Build.VERSION.SDK_INT +
                    "，参数个数 " + paramTypes.size + "）")
                return null
            }
            printLog("broadcastIntentLocked 硬编码下标失效，改用参数名定位：intent@" + byName[0] +
                " appOp@" + byName[1])
            return byName
        }
    }
}
