package sumicya.fcmself.xposed

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

import io.github.libxposed.api.XposedInterface

import sumicya.fcmself.config.FcmselfConfig
import sumicya.fcmself.util.Hooks
import sumicya.fcmself.util.Reflect
import sumicya.fcmself.xposed.XposedModule.Companion.printLog

/**
 * ReconnectManagerFix - GMS 长连接重连修复模块（运行在 com.google.android.gms 进程）
 *
 * 国内网络环境下 GMS 与 Google 服务器之间的长连接容易断开且重连缓慢。
 * 本模块 Hook GMS 内部的心跳/重连计时器：倒计时出现异常负值时主动发送
 * GCM_RECONNECT 广播触发重连，并把 fcmself 诊断日志转发到 GMS 日志（便于在 FCM Diagnostics 查看）。
 *
 * hook 点采用"自动发现"策略：从 HeartbeatChimeraAlarm 出发定位 Timer 类、设置超时的方法、
 * alarm 类型字段。发现结果只保存在内存里，不写任何配置文件——每次 GMS 进程启动时重新发现。
 * 本模块没有设置界面、没有配置项、也不发通知。
 */
class ReconnectManagerFix(api: XposedInterface, classLoader: ClassLoader) : XposedModule(api, classLoader) {

    private val gcmChimeraService: Class<*> =
        Reflect.findClass("com.google.android.gms.gcm.GcmChimeraService", classLoader)
    private var gcmChimeraServiceLogMethodName: String? = null

    /** 自动发现到的 Timer 类与超时设置方法（alarm 类型字段路径在构造器回调里补齐）。 */
    private var timerClass: Class<*>? = null
    private var setTimeoutMethod: String? = null
    @Volatile
    private var hookStarted = false

    /** 两次"配置可读"握手标志：构造时 Hook 的 onCreate 与 onCanReadConfig 各触发一次。 */
    private var startHookFlag = false

    init {
        startHookGcmServiceStart()
    }

    override fun onCanReadConfig() {
        if (startHookFlag) {
            checkVersionAndDiscover()
        } else {
            startHookFlag = true
        }
    }

    /**
     * Hook GcmChimeraService：
     * - onCreate：注册诊断日志接收器，参与两次握手；
     * - onDestroy：注销接收器；
     * - 同时探测 GcmChimeraService 的静态日志方法（String, Object[]）供日志转发使用。
     */
    private fun startHookGcmServiceStart() {
        try {
            for (method in gcmChimeraService.methods) {
                if (method.parameterTypes.size == 2 &&
                    method.parameterTypes[0] == String::class.java &&
                    method.parameterTypes[1] == Array<Any>::class.java
                ) {
                    gcmChimeraServiceLogMethodName = method.name
                    break
                }
            }
            Hooks.hookMethodAfter(api, gcmChimeraService, "onCreate", arrayOf<Class<*>>()) { _, _ ->
                registerLogReceiver()
                if (startHookFlag) {
                    checkVersionAndDiscover()
                } else {
                    startHookFlag = true
                }
            }
            Hooks.hookMethod(api, gcmChimeraService, "onDestroy", arrayOf<Class<*>>()) { chain ->
                try {
                    XposedModule.context?.unregisterReceiver(logBroadcastReceive)
                } catch (ignored: Throwable) {
                    // 接收器可能已经注销过
                }
                chain.proceed()
            }
        } catch (e: Throwable) {
            printLog("GcmChimeraService hook 失败: " + e.message)
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerLogReceiver() {
        val intentFilter = IntentFilter(FcmselfConfig.ACTION_LOG)
        if (Build.VERSION.SDK_INT >= 34) {
            XposedModule.context?.registerReceiver(logBroadcastReceive, intentFilter, Context.RECEIVER_EXPORTED)
        } else {
            XposedModule.context?.registerReceiver(logBroadcastReceive, intentFilter)
        }
    }

    /** 检查 GMS 版本，满足要求后自动发现 hook 点并安装。 */
    private fun checkVersionAndDiscover() {
        try {
            val ctx = XposedModule.context ?: return
            val versionCode = ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
            if (versionCode < MIN_GMS_VERSION_CODE) {
                printLog("当前为旧版GMS，重连修复不启用")
                return
            }
            discoverAndStartHook()
        } catch (e: Throwable) {
            printLog("重连修复初始化失败: " + e.message)
        }
    }

    /** 自动发现 hook 点并安装重连修复 Hook。发现结果只保留在内存字段中，不持久化。 */
    private fun discoverAndStartHook() {
        if (hookStarted) return
        try {
            val heartbeatChimeraAlarm = Reflect.findClass(
                "com.google.android.gms.gcm.connection.HeartbeatChimeraAlarm", classLoader)
            var timer: Class<*> = heartbeatChimeraAlarm.constructors[0].parameterTypes[3]
            if (timer.declaredMethods.isEmpty()) {
                timer = timer.superclass ?: timer
            }
            timerClass = timer

            for (method in timer.declaredMethods) {
                if (method.parameterTypes.size == 1 &&
                    method.parameterTypes[0] == Long::class.javaPrimitiveType &&
                    Modifier.isFinal(method.modifiers) && Modifier.isPublic(method.modifiers)
                ) {
                    setTimeoutMethod = method.name
                    break
                }
            }
            if (setTimeoutMethod == null) throw Throwable("未找到 setTimeout 方法")

            for (field in timer.declaredFields) {
                if (Modifier.isFinal(field.modifiers) && Modifier.isPublic(field.modifiers)) {
                    val alarmClass = field.type
                    val alarmConstructor = findAlarmConstructor(alarmClass)
                        ?: throw Throwable("未找到 alarm 构造函数")
                    val timerFieldName = field.name
                    Hooks.hookAfter(api, alarmConstructor) { chain, _ ->
                        onAlarmConstructed(alarmClass, timerFieldName, chain.thisObject, chain.getArg(2))
                    }
                    break
                }
            }
        } catch (e: Throwable) {
            printLog("自动寻找hook点失败: " + e.message, true)
        }
    }

    /** 查找 alarm 类的 (Context, int, String) 三参构造器。 */
    private fun findAlarmConstructor(alarmClass: Class<*>): Constructor<*>? {
        for (constructor in alarmClass.constructors) {
            val pts = constructor.parameterTypes
            if (pts.size == 3 && pts[0] == Context::class.java &&
                pts[1] == Int::class.javaPrimitiveType && pts[2] == String::class.java
            ) {
                return constructor
            }
        }
        return null
    }

    /** alarm 构造器被调用：匹配 alarmTag 定位 alarm 类型属性路径，然后安装倒计时检测 Hook。 */
    private fun onAlarmConstructed(alarmClass: Class<*>, timerFieldName: String, alarm: Any?, alarmTag: Any?) {
        if (hookStarted) return
        for (field in alarmClass.declaredFields) {
            if (field.type == String::class.java &&
                Modifier.isFinal(field.modifiers) && Modifier.isPrivate(field.modifiers)
            ) {
                if (alarmTag != null && alarm != null && Reflect.getObjectField(alarm, field.name) === alarmTag) {
                    val alarmTypeProperty = timerFieldName + "." + field.name
                    hookStarted = true
                    printLog("重连修复 hook 点已定位", true)
                    startHook(timerClass!!, setTimeoutMethod!!, alarmTypeProperty)
                    return
                }
            }
        }
        printLog("自动寻找hook点失败: 未找到目标方法", true)
    }

    /** 安装重连修复 Hook：检测心跳/重连倒计时是否出现异常负值，异常时主动触发重连。 */
    private fun startHook(timerClazz: Class<*>, setTimeoutMethodName: String, alarmTypeProperty: String) {
        printLog("timer_class: " + timerClazz.name, true)
        printLog("timer_alarm_type_property: " + alarmTypeProperty, true)
        printLog("timer_settimeout_method: " + setTimeoutMethodName, true)

        Hooks.hookMethodAfter(api, timerClazz, setTimeoutMethodName,
            arrayOf(Long::class.javaPrimitiveType!!)) { chain, _ ->
            // Chain 不能跨线程或跨调用复用，延时任务要用的值先取出来
            val timer = chain.thisObject ?: return@hookMethodAfter
            val timeout = chain.getArg(0) as Long
            val alarmType = Reflect.getObjectFieldByPath(timer, alarmTypeProperty) as? String
            if (ALARM_TYPE_HEARTBEAT != alarmType && ALARM_TYPE_CONNECTION != alarmType) {
                return@hookMethodAfter
            }
            var maxField: Field? = null
            var maxFieldValue = 0L
            for (field in timerClazz.declaredFields) {
                if (field.type == Long::class.javaPrimitiveType) {
                    val fieldValue = Reflect.getLongField(timer, field.name)
                    if (maxField == null || fieldValue > maxFieldValue) {
                        maxField = field
                        maxFieldValue = fieldValue
                    }
                }
            }
            if (maxField == null) return@hookMethodAfter
            val maxFieldName = maxField.name
            NEGATIVE_COUNTDOWN_SCHEDULER.schedule({
                val nextConnectionTime = Reflect.getLongField(timer, maxFieldName)
                if (nextConnectionTime != 0L &&
                    nextConnectionTime - SystemClock.elapsedRealtime() < NEGATIVE_COUNTDOWN_THRESHOLD_MS
                ) {
                    XposedModule.context?.sendBroadcast(Intent("com.google.android.intent.action.GCM_RECONNECT"))
                    printLog("Send broadcast GCM_RECONNECT", true)
                }
            }, timeout + COUNTDOWN_CHECK_DELAY_MS, TimeUnit.MILLISECONDS)
        }
    }

    /** 诊断日志广播接收器：把 fcmself 日志写入 GMS 日志（FCM Diagnostics 可见）。 */
    private val logBroadcastReceive = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (FcmselfConfig.ACTION_LOG == intent.action) {
                val methodName = gcmChimeraServiceLogMethodName ?: return
                try {
                    Reflect.callStaticMethod(gcmChimeraService, methodName,
                        arrayOf<Class<*>>(String::class.java, Array<Any>::class.java),
                        "[fcmself] " + intent.getStringExtra("text"), null)
                } catch (e: Throwable) {
                    printLog("输出日志到fcm失败：" + intent.getStringExtra("text"))
                }
            }
        }
    }

    private companion object {
        /** 负倒计时检测共用的调度器（守护线程，避免每次 setTimeout 都新建线程）。 */
        private val NEGATIVE_COUNTDOWN_SCHEDULER: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "fcmself-countdown").apply { isDaemon = true }
            }

        /** setTimeout 之后多久复查倒计时（ms） */
        private const val COUNTDOWN_CHECK_DELAY_MS = 5000L

        /** 低于该 GMS 版本 code 时不启用重连修复（旧版 GCM 架构不同） */
        private const val MIN_GMS_VERSION_CODE = 213916046L

        /** GCM 心跳 / 连接重连 alarm 类型常量 */
        private const val ALARM_TYPE_HEARTBEAT = "GCM_HB_ALARM"
        private const val ALARM_TYPE_CONNECTION = "GCM_CONN_ALARM"

        /** 负倒计时判定阈值（ms） */
        private const val NEGATIVE_COUNTDOWN_THRESHOLD_MS = -60000L
    }
}
