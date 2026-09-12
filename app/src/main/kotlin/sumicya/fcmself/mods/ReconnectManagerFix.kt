package sumicya.fcmself.mods

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock

import java.lang.reflect.Constructor
import java.lang.reflect.Modifier
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import io.github.libxposed.api.XposedInterface

import sumicya.fcmself.FcmselfModule
import sumicya.fcmself.ProcessEnv
import sumicya.fcmself.core.FcmselfLog
import sumicya.fcmself.hook.Hooks
import sumicya.fcmself.hook.Reflect

/**
 * ReconnectManagerFix —— GMS 长连接重连修复（运行在 com.google.android.gms 进程）。
 *
 * 国内网络环境下 GMS 与 Google 服务器之间的长连接容易断开且重连缓慢。
 * Hook GMS 内部的心跳/重连计时器：倒计时出现异常负值时主动发送 GCM_RECONNECT
 * 广播触发重连，并把 fcmself 诊断日志转发到 GMS 日志（FCM Diagnostics 可见）。
 *
 * hook 点采用「自动发现」策略：从 HeartbeatChimeraAlarm 出发定位 Timer 类、
 * 设置超时的方法、alarm 类型字段。发现结果只保存在内存里，不写任何配置文件——
 * 每次 GMS 进程启动时重新发现。
 *
 * 触发时机（等价于旧版的两次握手，这里用 [onEnvReady] 的 owner 去重实现）：
 * 模块构造与 GcmChimeraService.onCreate 各登记一次，环境就绪后恰好执行一次发现。
 */
internal class ReconnectManagerFix(api: XposedInterface, classLoader: ClassLoader) :
    FcmselfModule(api, classLoader, "ReconnectManagerFix") {

    private val gcmService: Class<*>? = Reflect.findClassIfExists(GCM_SERVICE, classLoader)

    /** 自动发现到的 Timer 类与超时设置方法（alarm 类型字段路径在构造器回调里补齐）。 */
    private var timerClass: Class<*>? = null
    private var setTimeoutName: String? = null

    /** 诊断日志转发用的 GcmChimeraService 静态方法名（String, Object[]）。 */
    private var gcmLogMethodName: String? = null

    /** 发现流程只跑一次（环境就绪回调 + onCreate 回调双入口）。 */
    private val discovered = AtomicBoolean(false)

    /** alarm 构造回调定位到 hook 点后置位，阻止后续 alarm 构造重复挂载。 */
    @Volatile
    private var hookStarted = false

    init {
        point("GcmChimeraService 生命周期") { hookGcmService() }
        onEnvReady { discoverOnce() }
    }

    // ------------------------------------------------------------------
    // GcmChimeraService：日志方法探测 + onCreate/onDestroy Hook
    // ------------------------------------------------------------------

    private fun hookGcmService() {
        val service = gcmService ?: run {
            skip(GCM_SERVICE, "类不存在")
            return
        }
        gcmLogMethodName = service.methods.firstOrNull {
            it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == Array<Any>::class.java
        }?.name

        Hooks.hookMethodAfter(api, service, "onCreate", arrayOf<Class<*>>()) { _, _ ->
            registerLogReceiver()
            onEnvReady { discoverOnce() }
        }
        Hooks.hookMethod(api, service, "onDestroy", arrayOf<Class<*>>()) { chain ->
            try {
                ProcessEnv.context?.unregisterReceiver(logReceiver)
            } catch (_: Throwable) {
                // 接收器可能已经注销过
            }
            chain.proceed()
        }
    }

    /** 发现入口（只跑一次）：校验 GMS 版本后自动发现 hook 点。 */
    private fun discoverOnce() {
        if (!discovered.compareAndSet(false, true)) return
        try {
            checkVersionAndDiscover()
        } catch (t: Throwable) {
            FcmselfLog.log("重连修复初始化失败: ${t.message}")
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerLogReceiver() {
        val context = ProcessEnv.context ?: return
        val filter = IntentFilter(ProcessEnv.ACTION_LOG)
        if (Build.VERSION.SDK_INT >= 34) {
            context.registerReceiver(logReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(logReceiver, filter)
        }
    }

    /** 诊断日志广播接收器：把 fcmself 日志写入 GMS 日志（FCM Diagnostics 可见）。 */
    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ProcessEnv.ACTION_LOG) return
            val service = gcmService ?: return
            val methodName = gcmLogMethodName ?: return
            val text = intent.getStringExtra("text") ?: return
            try {
                Reflect.callStaticMethod(
                    service,
                    methodName,
                    arrayOf<Class<*>>(String::class.java, Array<Any>::class.java),
                    "[fcmself] $text",
                    null,
                )
            } catch (_: Throwable) {
                FcmselfLog.log("输出日志到fcm失败：$text")
            }
        }
    }

    // ------------------------------------------------------------------
    // hook 点自动发现
    // ------------------------------------------------------------------

    private fun checkVersionAndDiscover() {
        val context = ProcessEnv.context ?: return
        val versionCode = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        if (versionCode < MIN_GMS_VERSION_CODE) {
            FcmselfLog.log("当前为旧版GMS，重连修复不启用")
            return
        }
        discoverAndStartHook()
    }

    /** 自动发现 hook 点并安装重连修复 Hook。发现结果只保留在内存字段中，不持久化。 */
    private fun discoverAndStartHook() {
        if (hookStarted) return
        try {
            val heartbeat = Reflect.findClass("com.google.android.gms.gcm.connection.HeartbeatChimeraAlarm", classLoader)
            var timer: Class<*> = heartbeat.constructors[0].parameterTypes[3]
            if (timer.declaredMethods.isEmpty()) {
                timer = timer.superclass ?: timer
            }
            timerClass = timer

            setTimeoutName = timer.declaredMethods.firstOrNull {
                it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Long::class.javaPrimitiveType &&
                    Modifier.isFinal(it.modifiers) &&
                    Modifier.isPublic(it.modifiers)
            }?.name ?: throw IllegalStateException("未找到 setTimeout 方法")

            val timerField = timer.declaredFields.firstOrNull {
                Modifier.isFinal(it.modifiers) && Modifier.isPublic(it.modifiers)
            } ?: throw IllegalStateException("未找到 Timer 的 alarm 字段")

            val alarmClass = timerField.type
            val alarmConstructor = findAlarmConstructor(alarmClass)
                ?: throw IllegalStateException("未找到 alarm 构造函数")
            val timerFieldName = timerField.name
            Hooks.hookAfter(api, alarmConstructor) { chain, _ ->
                onAlarmConstructed(alarmClass, timerFieldName, chain.thisObject, chain.getArg(2))
            }
        } catch (t: Throwable) {
            FcmselfLog.log("自动寻找hook点失败: ${t.message}", true)
        }
    }

    /** 查找 alarm 类的 (Context, int, String) 三参构造器。 */
    private fun findAlarmConstructor(alarmClass: Class<*>): Constructor<*>? =
        alarmClass.constructors.firstOrNull {
            val types = it.parameterTypes
            types.size == 3 &&
                types[0] == Context::class.java &&
                types[1] == Int::class.javaPrimitiveType &&
                types[2] == String::class.java
        }

    /** alarm 构造器被调用：匹配 alarmTag 定位 alarm 类型属性路径，然后安装倒计时检测 Hook。 */
    private fun onAlarmConstructed(alarmClass: Class<*>, timerFieldName: String, alarm: Any?, alarmTag: Any?) {
        if (hookStarted || alarm == null || alarmTag == null) return
        for (field in alarmClass.declaredFields) {
            if (field.type != String::class.java) continue
            if (!Modifier.isFinal(field.modifiers) || !Modifier.isPrivate(field.modifiers)) continue
            if (Reflect.getObjectField(alarm, field.name) !== alarmTag) continue
            // 命中：这个字段就是 alarm 类型标识
            hookStarted = true
            FcmselfLog.log("重连修复 hook 点已定位", true)
            val timer = timerClass
            val setTimeout = setTimeoutName
            if (timer != null && setTimeout != null) {
                startHook(timer, setTimeout, "$timerFieldName.${field.name}")
            }
            return
        }
        FcmselfLog.log("自动寻找hook点失败: 未找到目标方法", true)
    }

    // ------------------------------------------------------------------
    // 倒计时检测 Hook
    // ------------------------------------------------------------------

    /** 检测心跳/重连倒计时是否出现异常负值，异常时延时主动触发重连。 */
    private fun startHook(timerClazz: Class<*>, setTimeoutMethodName: String, alarmTypeProperty: String) {
        FcmselfLog.log("timer_class: ${timerClazz.name}", true)
        FcmselfLog.log("timer_alarm_type_property: $alarmTypeProperty", true)
        FcmselfLog.log("timer_settimeout_method: $setTimeoutMethodName", true)

        Hooks.hookMethodAfter(api, timerClazz, setTimeoutMethodName, arrayOf(Long::class.javaPrimitiveType!!)) { chain, _ ->
            // Chain 不能跨线程或跨调用复用，延时任务要用的值先取出来
            val timer = chain.thisObject ?: return@hookMethodAfter
            val timeout = chain.getArg(0) as Long
            val alarmType = Reflect.getObjectFieldByPath(timer, alarmTypeProperty) as? String
            if (alarmType != ALARM_TYPE_HEARTBEAT && alarmType != ALARM_TYPE_CONNECTION) {
                return@hookMethodAfter
            }
            // 「下一次连接时刻」= long 字段里值最大的那个（并列取先声明者，与旧版一致）
            val maxField = timerClazz.declaredFields
                .filter { it.type == Long::class.javaPrimitiveType }
                .map { it to Reflect.getLongField(timer, it.name) }
                .maxByOrNull { it.second }
                ?: return@hookMethodAfter
            val maxFieldName = maxField.first.name
            SCHEDULER.schedule(
                {
                    val nextConnectionTime = Reflect.getLongField(timer, maxFieldName)
                    if (nextConnectionTime != 0L &&
                        nextConnectionTime - SystemClock.elapsedRealtime() < NEGATIVE_COUNTDOWN_THRESHOLD_MS
                    ) {
                        ProcessEnv.context?.sendBroadcast(Intent("com.google.android.intent.action.GCM_RECONNECT"))
                        FcmselfLog.log("Send broadcast GCM_RECONNECT", true)
                    }
                },
                timeout + COUNTDOWN_CHECK_DELAY_MS,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    private companion object {
        /** 负倒计时检测共用的调度器（守护线程，避免每次 setTimeout 都新建线程）。 */
        val SCHEDULER: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "fcmself-countdown").apply { isDaemon = true }
        }

        /** setTimeout 之后多久复查倒计时（ms）。 */
        const val COUNTDOWN_CHECK_DELAY_MS = 5000L

        /** 低于该 GMS 版本 code 时不启用重连修复（旧版 GCM 架构不同）。 */
        const val MIN_GMS_VERSION_CODE = 213916046L

        /** GCM 心跳 / 连接重连 alarm 类型常量。 */
        const val ALARM_TYPE_HEARTBEAT = "GCM_HB_ALARM"
        const val ALARM_TYPE_CONNECTION = "GCM_CONN_ALARM"

        /** 负倒计时判定阈值（ms）。 */
        const val NEGATIVE_COUNTDOWN_THRESHOLD_MS = -60000L

        const val GCM_SERVICE = "com.google.android.gms.gcm.GcmChimeraService"
    }
}
