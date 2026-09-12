package sumicya.fcmself.mods

import android.content.Intent
import android.os.Build

import java.lang.reflect.Method

import io.github.libxposed.api.XposedInterface

import sumicya.fcmself.FcmselfModule
import sumicya.fcmself.ProcessEnv
import sumicya.fcmself.core.FcmselfLog
import sumicya.fcmself.core.Push
import sumicya.fcmself.hook.Hooks
import sumicya.fcmself.hook.Reflect
import sumicya.fcmself.hook.Signatures

/**
 * BroadcastFix —— 发送核心入口修复（唤醒未启动的应用）。
 *
 * 检测到 FCM 推送广播时：
 * 1. 强制补上 `FLAG_INCLUDE_STOPPED_PACKAGES`（原生 AOSP 发往特定应用的广播
 *    本就默认携带此标志，这里只是把 OEM 丢掉的语义补回来）；
 * 2. 把 appOp 从 OP_NONE 改为 OP_POST_NOTIFICATION；
 * 3. ColorOS 上顺带调用 [OplusProxy.unfreeze] 解冻目标应用。
 *
 * 挂载点 `broadcastIntentLocked`：Android 15+ 在 `BroadcastController`，
 * Android 10-14 在 `ActivityManagerService`。(intent, appOp) 下标由
 * [Signatures.resolveBroadcastArgs] 按「版本候选 + 类型校验 + 参数名兜底」自适应解析。
 */
internal class BroadcastFix(api: XposedInterface, classLoader: ClassLoader) :
    FcmselfModule(api, classLoader, "BroadcastFix") {

    init {
        point("broadcastIntentLocked") { startHook() }
    }

    private fun startHook() {
        val located = locateTarget()
        if (located == null) {
            FcmselfLog.log("broadcastIntentLocked hook 位置查找失败，fcmself将不会工作。")
            return
        }
        val (method, indices) = located
        val (intentIndex, appOpIndex) = indices
        FcmselfLog.log("Android API: ${Build.VERSION.SDK_INT}")
        FcmselfLog.log("appOp_args_index: $appOpIndex")
        FcmselfLog.log("intent_args_index: $intentIndex")
        FcmselfLog.log("hook target: ${method.declaringClass.name}")

        Hooks.hook(api, method) { chain ->
            // 启动闸门未放行前不介入
            if (!ProcessEnv.isBootComplete) return@hook chain.proceed()
            // Chain.getArgs() 返回不可变列表，要改参数必须整份传回 proceed(args)
            val args = chain.args.toTypedArray()
            val intent = args.getOrNull(intentIndex) as? Intent ?: return@hook chain.proceed()
            // 介入条件：广播未携带「唤醒已停止应用」标志且是推送族
            if ((intent.flags and Intent.FLAG_INCLUDE_STOPPED_PACKAGES) == 0 && Push.isTargetedPush(intent)) {
                val appOp = args.getOrNull(appOpIndex) as? Int
                if (appOp == OP_NONE) args[appOpIndex] = OP_POST_NOTIFICATION
                intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                FcmselfLog.log("Add FLAG_INCLUDE_STOPPED_PACKAGES: ${Push.targetOf(intent)}", true)
                // ColorOS 15 解冻 OplusProxy
                Push.targetOf(intent)?.let { OplusProxy.unfreeze(it) }
            }
            chain.proceed(args)
        }
    }

    /** 依次尝试 BroadcastController（Android 15+）与 ActivityManagerService（Android 10-14）。 */
    private fun locateTarget(): Pair<Method, Pair<Int, Int>>? {
        if (Build.VERSION.SDK_INT >= 35) {
            locate("com.android.server.am.BroadcastController", listOf(3 to 13, 3 to 12))?.let { return it }
        }
        return locate("com.android.server.am.ActivityManagerService", amsCandidates())
    }

    /** AMS.broadcastIntentLocked 的 (intent, appOp) 版本候选；未知新版本落到 34+ 的候选并依赖类型校验。 */
    private fun amsCandidates(): List<Pair<Int, Int>> = when {
        Build.VERSION.SDK_INT <= 29 -> listOf(2 to 9)
        Build.VERSION.SDK_INT == 30 -> listOf(3 to 10)
        Build.VERSION.SDK_INT <= 32 -> listOf(3 to 11, 3 to 12)
        else -> listOf(3 to 12, 3 to 13)
    }

    private fun locate(className: String, candidates: List<Pair<Int, Int>>): Pair<Method, Pair<Int, Int>>? {
        val clazz = Reflect.findClassIfExists(className, classLoader) ?: return null
        val method = Reflect.findMethodMostParams(clazz, "broadcastIntentLocked") ?: return null
        val indices = Signatures.resolveBroadcastArgs(method, Intent::class.java, candidates) {
            FcmselfLog.log(it)
        } ?: return null
        return method to indices
    }

    private companion object {
        /** AOSP AppOpsManager.OP_NONE（-1），@hide 常量，只能自己定义。 */
        const val OP_NONE = -1

        /** AOSP AppOpsManager.OP_POST_NOTIFICATION（11，@hide 常量）。 */
        const val OP_POST_NOTIFICATION = 11
    }
}
