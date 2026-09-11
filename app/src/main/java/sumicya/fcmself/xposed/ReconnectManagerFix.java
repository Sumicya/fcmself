package sumicya.fcmself.xposed;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.SystemClock;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.github.libxposed.api.XposedInterface;

import sumicya.fcmself.config.FcmselfConfig;
import sumicya.fcmself.util.Hooks;
import sumicya.fcmself.util.Reflect;

/**
 * ReconnectManagerFix - GMS 长连接重连修复模块（运行在 com.google.android.gms 进程）
 *
 * <p>国内网络环境下 GMS 与 Google 服务器之间的长连接容易断开且重连缓慢。
 * 本模块 Hook GMS 内部的心跳/重连计时器：倒计时出现异常负值时主动发送
 * GCM_RECONNECT 广播触发重连，并把 fcmself 诊断日志转发到 GMS 日志
 * （便于在 FCM Diagnostics 查看）。
 *
 * <p>hook 点采用"自动发现"策略：从 {@code HeartbeatChimeraAlarm} 出发定位 Timer 类、
 * 设置超时的方法、alarm 类型字段。发现结果只保存在内存里，<b>不写任何配置文件</b>——
 * 每次 GMS 进程启动时重新发现一次，GMS 升级后也不会用到过期的缓存。本模块没有
 * 设置界面、没有配置项、也不发通知。
 */
public class ReconnectManagerFix extends XposedModule {

    /** 负倒计时检测共用的调度器（守护线程，避免每次 setTimeout 都新建线程）。 */
    private static final ScheduledExecutorService NEGATIVE_COUNTDOWN_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "fcmself-countdown");
                thread.setDaemon(true);
                return thread;
            });

    /** setTimeout 之后多久复查倒计时（ms） */
    private static final long COUNTDOWN_CHECK_DELAY_MS = 5000L;

    /** 低于该 GMS 版本 code 时不启用重连修复（旧版 GCM 架构不同） */
    private static final long MIN_GMS_VERSION_CODE = 213916046L;

    /** GCM 心跳 / 连接重连 alarm 类型常量 */
    private static final String ALARM_TYPE_HEARTBEAT = "GCM_HB_ALARM";
    private static final String ALARM_TYPE_CONNECTION = "GCM_CONN_ALARM";

    /** 负倒计时判定阈值（ms） */
    private static final long NEGATIVE_COUNTDOWN_THRESHOLD_MS = -60000L;

    private final Class<?> gcmChimeraService;
    private String gcmChimeraServiceLogMethodName;

    /** 自动发现到的 Timer 类与超时设置方法（alarm 类型字段路径在构造器回调里补齐）。 */
    private Class<?> timerClass;
    private String setTimeoutMethod;
    private volatile boolean hookStarted = false;

    /**
     * 两次"配置可读"握手标志：构造时 Hook 的 onCreate 与 onCanReadConfig 各触发一次，
     * 第一次仅置位，第二次才执行版本检查与 hook 点发现（确保 context 就绪）。
     */
    private boolean startHookFlag = false;

    public ReconnectManagerFix(XposedInterface api, ClassLoader classLoader) {
        super(api, classLoader);
        this.gcmChimeraService = Reflect.findClass("com.google.android.gms.gcm.GcmChimeraService", classLoader);
        this.startHookGcmServiceStart();
    }

    @Override
    protected void onCanReadConfig() throws Throwable {
        if (startHookFlag) {
            this.checkVersionAndDiscover();
        } else {
            startHookFlag = true;
        }
    }

    /**
     * Hook GcmChimeraService：
     * - onCreate：注册诊断日志接收器，参与两次握手；
     * - onDestroy：注销接收器；
     * - 同时探测 GcmChimeraService 的静态日志方法（String, Object[]）供日志转发使用。
     */
    private void startHookGcmServiceStart() {
        try {
            for (Method method : this.gcmChimeraService.getMethods()) {
                if (method.getParameterTypes().length == 2
                        && method.getParameterTypes()[0] == String.class
                        && method.getParameterTypes()[1] == Object[].class) {
                    this.gcmChimeraServiceLogMethodName = method.getName();
                    break;
                }
            }
            Hooks.hookMethodAfter(api, this.gcmChimeraService, "onCreate", new Class<?>[0],
                    (chain, error) -> {
                        registerLogReceiver();
                        if (startHookFlag) {
                            checkVersionAndDiscover();
                        } else {
                            startHookFlag = true;
                        }
                    });
            Hooks.hookMethod(api, this.gcmChimeraService, "onDestroy", new Class<?>[0], chain -> {
                try {
                    context.unregisterReceiver(logBroadcastReceive);
                } catch (Throwable ignored) {
                    // 接收器可能已经注销过
                }
                return chain.proceed();
            });
        } catch (Throwable e) {
            printLog("GcmChimeraService hook 失败: " + e.getMessage());
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerLogReceiver() {
        IntentFilter intentFilter = new IntentFilter(FcmselfConfig.ACTION_LOG);
        if (Build.VERSION.SDK_INT >= 34) {
            context.registerReceiver(logBroadcastReceive, intentFilter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(logBroadcastReceive, intentFilter);
        }
    }

    /** 检查 GMS 版本，满足要求后自动发现 hook 点并安装。 */
    private void checkVersionAndDiscover() {
        try {
            long versionCode = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).getLongVersionCode();
            if (versionCode < MIN_GMS_VERSION_CODE) {
                printLog("当前为旧版GMS，重连修复不启用");
                return;
            }
            discoverAndStartHook();
        } catch (Throwable e) {
            printLog("重连修复初始化失败: " + e.getMessage());
        }
    }

    /**
     * 自动发现 hook 点并安装重连修复 Hook（setTimeout 负倒计时检测）。
     * 发现结果只保留在内存字段中，不持久化。
     */
    private void discoverAndStartHook() {
        if (hookStarted) {
            return;
        }
        try {
            Class<?> heartbeatChimeraAlarm = Reflect.findClass(
                    "com.google.android.gms.gcm.connection.HeartbeatChimeraAlarm", classLoader);
            timerClass = heartbeatChimeraAlarm.getConstructors()[0].getParameterTypes()[3];
            if (timerClass.getDeclaredMethods().length == 0) {
                timerClass = timerClass.getSuperclass();
            }

            for (Method method : timerClass.getDeclaredMethods()) {
                if (method.getParameterTypes().length == 1 && method.getParameterTypes()[0] == long.class
                        && Modifier.isFinal(method.getModifiers()) && Modifier.isPublic(method.getModifiers())) {
                    setTimeoutMethod = method.getName();
                    break;
                }
            }
            if (setTimeoutMethod == null) {
                throw new Throwable("未找到 setTimeout 方法");
            }

            // 找到 alarm 类型字段后，hook 其构造器，等构造器被调用时匹配 alarmTag 以定位属性路径
            for (final Field timerClassField : timerClass.getDeclaredFields()) {
                if (Modifier.isFinal(timerClassField.getModifiers())
                        && Modifier.isPublic(timerClassField.getModifiers())) {
                    final Class<?> alarmClass = timerClassField.getType();
                    Constructor<?> alarmConstructor = findAlarmConstructor(alarmClass);
                    if (alarmConstructor == null) {
                        throw new Throwable("未找到 alarm 构造函数");
                    }
                    Hooks.hookAfter(api, alarmConstructor, (chain, err) -> onAlarmConstructed(
                            alarmClass, timerClassField.getName(), chain.getThisObject(), chain.getArg(2)));
                    break;
                }
            }
        } catch (Throwable e) {
            printLog("自动寻找hook点失败: " + e.getMessage(), true);
        }
    }

    /** 查找 alarm 类的 (Context, int, String) 三参构造器。 */
    private static Constructor<?> findAlarmConstructor(Class<?> alarmClass) {
        for (Constructor<?> constructor : alarmClass.getConstructors()) {
            Class<?>[] pts = constructor.getParameterTypes();
            if (pts.length == 3 && pts[0] == Context.class && pts[1] == int.class && pts[2] == String.class) {
                return constructor;
            }
        }
        return null;
    }

    /** alarm 构造器被调用：匹配 alarmTag 定位 alarm 类型属性路径，然后安装倒计时检测 Hook。 */
    private void onAlarmConstructed(Class<?> alarmClass, String timerFieldName, Object alarm, Object alarmTag) {
        if (hookStarted) {
            return;
        }
        for (Field field : alarmClass.getDeclaredFields()) {
            if (field.getType() == String.class && Modifier.isFinal(field.getModifiers())
                    && Modifier.isPrivate(field.getModifiers())) {
                if (alarmTag != null && Reflect.getObjectField(alarm, field.getName()) == alarmTag) {
                    String alarmTypeProperty = timerFieldName + "." + field.getName();
                    hookStarted = true;
                    printLog("重连修复 hook 点已定位", true);
                    startHook(timerClass, setTimeoutMethod, alarmTypeProperty);
                    return;
                }
            }
        }
        printLog("自动寻找hook点失败: 未找到目标方法", true);
    }

    /**
     * 安装重连修复 Hook：检测心跳/重连倒计时是否出现异常负值，异常时主动触发重连。
     */
    private void startHook(Class<?> timerClazz, String setTimeoutMethodName, String alarmTypeProperty) {
        printLog("timer_class: " + timerClazz.getName(), true);
        printLog("timer_alarm_type_property: " + alarmTypeProperty, true);
        printLog("timer_settimeout_method: " + setTimeoutMethodName, true);

        Hooks.hookMethodAfter(api, timerClazz, setTimeoutMethodName, new Class<?>[]{long.class},
                (chain, error) -> {
                    // Chain 不能跨线程或跨调用复用，延时任务要用的值先取出来
                    final Object timer = chain.getThisObject();
                    final long timeout = (long) chain.getArg(0);
                    String alarmType = (String) Reflect.getObjectFieldByPath(timer, alarmTypeProperty);
                    if (!ALARM_TYPE_HEARTBEAT.equals(alarmType) && !ALARM_TYPE_CONNECTION.equals(alarmType)) {
                        return;
                    }
                    Field maxField = null;
                    long maxFieldValue = 0L;
                    for (Field field : timerClazz.getDeclaredFields()) {
                        if (field.getType() == long.class) {
                            long fieldValue = (long) Reflect.getObjectField(timer, field.getName());
                            if (maxField == null || fieldValue > maxFieldValue) {
                                maxField = field;
                                maxFieldValue = fieldValue;
                            }
                        }
                    }
                    if (maxField == null) {
                        return;
                    }
                    final String maxFieldName = maxField.getName();
                    NEGATIVE_COUNTDOWN_SCHEDULER.schedule(() -> {
                        long nextConnectionTime = Reflect.getLongField(timer, maxFieldName);
                        if (nextConnectionTime != 0
                                && nextConnectionTime - SystemClock.elapsedRealtime() < NEGATIVE_COUNTDOWN_THRESHOLD_MS) {
                            context.sendBroadcast(new Intent("com.google.android.intent.action.GCM_RECONNECT"));
                            printLog("Send broadcast GCM_RECONNECT", true);
                        }
                    }, timeout + COUNTDOWN_CHECK_DELAY_MS, TimeUnit.MILLISECONDS);
                });
    }

    /** 诊断日志广播接收器：把 fcmself 日志写入 GMS 日志（FCM Diagnostics 可见）。 */
    private final BroadcastReceiver logBroadcastReceive = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (FcmselfConfig.ACTION_LOG.equals(intent.getAction())) {
                try {
                    Reflect.callStaticMethod(gcmChimeraService, gcmChimeraServiceLogMethodName,
                            new Class<?>[]{String.class, Object[].class},
                            "[fcmself] " + intent.getStringExtra("text"), null);
                } catch (Throwable e) {
                    printLog("输出日志到fcm失败：" + intent.getStringExtra("text"));
                }
            }
        }
    };
}
