package sumicya.fcmself.xposed

import android.content.Context

import java.lang.reflect.Constructor
import java.lang.reflect.Method

import io.github.libxposed.api.XposedInterface

import sumicya.fcmself.util.Hooks
import sumicya.fcmself.util.Reflect
import sumicya.fcmself.xposed.XposedModule.Companion.printLog

/**
 * PowerkeeperFix - MIUI PowerKeeper 对 GMS 的限制修复
 *
 * MIUI 的 PowerKeeper 会把 GMS 加入后台限制黑名单。本模块：
 * - 把 MilletConfig.isGlobal 置为 true；
 * - 让 SimpleSettings.Misc.getBoolean("gms_control", ...) 恒返回 false；
 * - 在 MilletPolicy 构造时把 GMS 从黑名单移除、加入数据白名单。
 *
 * 非 MIUI 设备上这些类不存在，各点独立跳过。
 */
class PowerkeeperFix(api: XposedInterface, classLoader: ClassLoader) : XposedModule(api, classLoader) {

    init {
        try {
            startHook()
        } catch (e: Throwable) {
            printLog("hook error PowerkeeperFix:" + e.message)
        }
    }

    private fun startHook() {
        hookMilletConfig()
        hookMiscGetBoolean()
        hookMilletPolicyConstructor()
    }

    /** MilletConfig.isGlobal = true */
    private fun hookMilletConfig() {
        try {
            val clazz = Reflect.findClassIfExists("com.miui.powerkeeper.millet.MilletConfig", classLoader) ?: return
            Reflect.setStaticObjectField(clazz, "isGlobal", true)
            printLog("Set com.miui.powerkeeper.millet.MilletConfig.isGlobal to true")
        } catch (e: Throwable) {
            printLog("No Such Class com.miui.powerkeeper.millet.MilletConfig")
        }
    }

    /** SimpleSettings.Misc.getBoolean("gms_control", ...) 恒返回 false */
    private fun hookMiscGetBoolean() {
        try {
            val clazz = Reflect.findClassIfExists(
                "com.miui.powerkeeper.provider.SimpleSettings\$Misc", classLoader) ?: return
            val method = Reflect.findMethodByParamCount(clazz, "getBoolean", 3) ?: return
            Hooks.hook(api, method) { chain ->
                val result = chain.proceed()
                val arg1 = chain.getArg(1)
                if ("gms_control" == arg1) {
                    printLog("Success: PowerKeeper GMS Limitation.", true)
                    false
                } else {
                    result
                }
            }
        } catch (e: Throwable) {
            printLog("No Such Method com.miui.powerkeeper.provider.SimpleSettings.Misc.getBoolean")
        }
    }

    /** MilletPolicy 构造时移除 GMS 的黑名单项，加入数据白名单 */
    private fun hookMilletPolicyConstructor() {
        try {
            val clazz = Reflect.findClassIfExists("com.miui.powerkeeper.millet.MilletPolicy", classLoader) ?: return
            val constructor: Constructor<*> = Reflect.findConstructorMostMatch(clazz, Context::class.java)
            Hooks.hookAfter(api, constructor) { chain, _ ->
                val policy = chain.thisObject ?: return@hookAfter
                for (field in clazz.declaredFields) {
                    val name = field.name
                    when (name) {
                        "mSystemBlackList" -> {
                            @Suppress("UNCHECKED_CAST")
                            val list = Reflect.getObjectField(policy, name) as MutableList<String>
                            list.remove("com.google.android.gms")
                            Reflect.setObjectField(policy, name, list)
                            printLog("Success: MilletPolicy mSystemBlackList.")
                        }
                        "whiteApps" -> {
                            @Suppress("UNCHECKED_CAST")
                            val list = Reflect.getObjectField(policy, name) as MutableList<String>
                            list.remove("com.google.android.gms")
                            list.remove("com.google.android.ext.services")
                            Reflect.setObjectField(policy, name, list)
                            printLog("Success: MilletPolicy whiteApps.")
                        }
                        "mDataWhiteList" -> {
                            @Suppress("UNCHECKED_CAST")
                            val list = Reflect.getObjectField(policy, name) as MutableList<String>
                            if (!list.contains("com.google.android.gms")) list.add("com.google.android.gms")
                            Reflect.setObjectField(policy, name, list)
                            printLog("Success: MilletPolicy mDataWhiteList.")
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            printLog("No Such Method com.miui.powerkeeper.millet.MilletPolicy")
        }
    }
}
