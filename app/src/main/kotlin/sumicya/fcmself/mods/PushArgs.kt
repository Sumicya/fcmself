package sumicya.fcmself.mods

import android.content.Intent

import java.lang.reflect.Field
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

import sumicya.fcmself.core.Push

/**
 * 从 Hook 点的实参列表里定位「目标明确的推送广播」——签名自适应的另一核心。
 *
 * 旧版按固定下标取 intent（`args[2]`、`args[1]`…），ROM 往拦截点插参数就漂移。
 * 而无论参数怎么插，携带推送 Intent 的载体只有两种形态：
 *
 * 1. 参数本身就是 [Intent]（如 `shouldInterceptService`、`isAllowStartService`）；
 * 2. 参数是广播记录类对象，其 `intent` 字段是 [Intent]（如 BroadcastRecord、
 *    SmartPower 的包装类、Oplus 启动管理器的入参）。
 *
 * 按这两种形态扫描实参即可与参数位置解耦。`intent` 字段按声明类缓存
 * （含「无此字段」的负缓存），单条广播的扫描开销可忽略——且低于旧版
 * 逐层走 [java.lang.reflect.Class.getDeclaredField] 的无缓存反射。
 *
 * 只有命中 [Push.isTargetedPush]（推送族 + 目标明确）才返回，非推送调用零介入。
 */
internal object PushArgs {

    private val intentFields = ConcurrentHashMap<Class<*>, Optional<Field>>()

    /** 实参列表中第一条「目标明确的推送」Intent；找不到返回 null。 */
    fun find(args: Array<Any?>): Intent? {
        for (arg in args) {
            when {
                arg is Intent -> if (Push.isTargetedPush(arg)) return arg
                arg == null || arg is String || arg is Number || arg is Boolean -> Unit
                else -> {
                    val intent = intentFieldOf(arg) ?: continue
                    if (Push.isTargetedPush(intent)) return intent
                }
            }
        }
        return null
    }

    private fun intentFieldOf(holder: Any): Intent? {
        val field = intentFields
            .computeIfAbsent(holder.javaClass) { clazz ->
                try {
                    Optional.of(clazz.getDeclaredField("intent").apply { isAccessible = true })
                } catch (_: NoSuchFieldException) {
                    Optional.empty()
                }
            }
            .orElse(null) ?: return null
        if (!Intent::class.java.isAssignableFrom(field.type)) return null
        return try {
            field.get(holder) as? Intent
        } catch (_: Throwable) {
            null
        }
    }
}
