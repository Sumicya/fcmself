package sumicya.fcmself.mods

import android.content.Context

import io.github.libxposed.api.XposedInterface

import sumicya.fcmself.FcmselfModule
import sumicya.fcmself.core.FcmselfLog
import sumicya.fcmself.hook.Hooks
import sumicya.fcmself.hook.Reflect

/**
 * PowerkeeperFix —— MIUI PowerKeeper 对 GMS 的限制修复。
 *
 * - 把 MilletConfig.isGlobal 置为 true；
 * - 让 SimpleSettings.Misc.getBoolean("gms_control", ...) 恒返回 false；
 * - MilletPolicy 构造时：从 mSystemBlackList 与 whiteApps 移除 GMS（及 ext.services）、
 *   把 GMS 加入 mDataWhiteList。
 *
 * 行为说明：whiteApps 的移除沿用旧版（fcmfix 时代实测保留）逻辑，该列表的语义
 * 未被证实——若实为「允许后台的白名单」，此操作反而收紧。真机验证前不改。
 *
 * 非 MIUI 设备上这些类不存在，各点独立跳过。
 */
internal class PowerkeeperFix(api: XposedInterface, classLoader: ClassLoader) :
    FcmselfModule(api, classLoader, "PowerkeeperFix") {

    init {
        point("MilletConfig.isGlobal") { hookMilletConfig() }
        point("SimpleSettings.Misc#gms_control") { hookMiscGetBoolean() }
        point("MilletPolicy 构造") { hookMilletPolicyConstructor() }
    }

    /** MilletConfig.isGlobal = true */
    private fun hookMilletConfig() {
        val className = "com.miui.powerkeeper.millet.MilletConfig"
        val clazz = Reflect.findClassIfExists(className, classLoader) ?: run {
            skip(className, "类不存在（非 MIUI / HyperOS）")
            return
        }
        Reflect.setStaticObjectField(clazz, "isGlobal", true)
        FcmselfLog.log("Set $className.isGlobal to true")
    }

    /** SimpleSettings.Misc.getBoolean("gms_control", ...) 恒返回 false */
    private fun hookMiscGetBoolean() {
        val className = "com.miui.powerkeeper.provider.SimpleSettings\$Misc"
        val clazz = Reflect.findClassIfExists(className, classLoader) ?: run {
            skip(className, "类不存在（非 MIUI / HyperOS）")
            return
        }
        val method = Reflect.findMethodByParamCount(clazz, "getBoolean", 3)
            ?: throw NoSuchMethodError("$className#getBoolean(3 参)")
        Hooks.hook(api, method) { chain ->
            val result = chain.proceed()
            if (chain.getArg(1) == "gms_control") {
                FcmselfLog.log("Success: PowerKeeper GMS Limitation.", true)
                false
            } else {
                result
            }
        }
    }

    /** MilletPolicy 构造后按字段名调整 GMS 的黑名单 / 白名单归属。 */
    private fun hookMilletPolicyConstructor() {
        val className = "com.miui.powerkeeper.millet.MilletPolicy"
        val clazz = Reflect.findClassIfExists(className, classLoader) ?: run {
            skip(className, "类不存在（非 MIUI / HyperOS）")
            return
        }
        val constructor = Reflect.findConstructorMostMatch(clazz, Context::class.java)
        Hooks.hookAfter(api, constructor) { chain, _ ->
            val policy = chain.thisObject ?: return@hookAfter
            for (field in clazz.declaredFields) {
                when (field.name) {
                    "mSystemBlackList" -> stringList(policy, field.name)?.let { list ->
                        list.remove(GMS)
                        FcmselfLog.log("Success: MilletPolicy mSystemBlackList.")
                    }
                    "whiteApps" -> stringList(policy, field.name)?.let { list ->
                        list.remove(GMS)
                        list.remove(EXT_SERVICES)
                        FcmselfLog.log("Success: MilletPolicy whiteApps.")
                    }
                    "mDataWhiteList" -> stringList(policy, field.name)?.let { list ->
                        if (GMS !in list) list.add(GMS)
                        FcmselfLog.log("Success: MilletPolicy mDataWhiteList.")
                    }
                }
            }
        }
    }

    /** 读一个 List<String> 字段（沿继承链）；类型不符或读取失败返回 null。 */
    private fun stringList(holder: Any, fieldName: String): MutableList<String>? =
        try {
            Reflect.getObjectField(holder, fieldName) as? MutableList<String>
        } catch (_: Throwable) {
            null
        }

    private companion object {
        const val GMS = "com.google.android.gms"
        const val EXT_SERVICES = "com.google.android.ext.services"
    }
}
