package com.kooritea.fcmfix.xposed;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;

import com.kooritea.fcmfix.config.FcmfixConfig;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Timer;
import java.util.TimerTask;

import com.kooritea.fcmfix.libxposed.XC_MethodHook;
import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.libxposed.XposedHelpers;
import com.kooritea.fcmfix.util.XposedUtils;

/**
 * ReconnectManagerFix - GMS 长连接重连修复模块（运行在 com.google.android.gms 进程）
 *
 * 功能说明：
 * 国内网络环境下 GMS 与 Google 服务器之间的长连接容易断开且重连缓慢。
 * 本模块 Hook GMS 内部的心跳/重连计时器：
 * <ul>
 *   <li>固定心跳间隔（heartbeatInterval）与重连间隔（reconnInterval，单位 ms，>1000 生效）；</li>
 *   <li>倒计时出现异常负值时主动发送 GCM_RECONNECT 广播触发重连；</li>
 *   <li>在 FCM Diagnostics 页面注入 RECONNECT / 打开 fcmfix 按钮；</li>
 *   <li>把 fcmfix 诊断日志转发到 GMS 日志（便于在 FCM Diagnostics 查看）。</li>
 * </ul>
 *
 * 工作原理：
 * GMS 内部 Timer 相关类名随版本变化，因此 hook 点采用"自动发现 + 持久化"策略：
 * 首次运行或 GMS 更新时从 {@code com.google.android.gms.gcm.connection.HeartbeatChimeraAlarm}
 * 出发定位 Timer 类/设置超时的方法/alarm 类型字段，结果存入 GMS 本地
 * SharedPreferences {@value #PREF_NAME}，之后直接按缓存 hook。
 *
 * 配置项（GMS 本地 SharedPreferences {@value #PREF_NAME}）：
 * heartbeatInterval / reconnInterval / enable 等。
 */
public class ReconnectManagerFix extends XposedModule {

    private static final String PREF_NAME = "fcmfix_config";
    private static final String PREF_IS_INIT = "isInit";
    private static final String PREF_CONFIG_VERSION = "config_version";
    private static final String PREF_ENABLE = "enable";
    private static final String PREF_HEARTBEAT_INTERVAL = "heartbeatInterval";
    private static final String PREF_RECONN_INTERVAL = "reconnInterval";
    private static final String PREF_GMS_VERSION = "gms_version";
    private static final String PREF_GMS_VERSION_CODE = "gms_version_code";
    private static final String PREF_TIMER_CLASS = "timer_class";
    private static final String PREF_TIMER_SETTIMEOUT_METHOD = "timer_settimeout_method";
    private static final String PREF_TIMER_ALARM_TYPE_PROPERTY = "timer_alarm_type_property";

    /** 配置结构版本号，变更 hook 点结构时递增以触发重新发现 */
    public static final String configVersion = "v3";

    /** 低于该 GMS 版本 code 时不启用重连修复（旧版 GCM 架构不同） */
    private static final long MIN_GMS_VERSION_CODE = 213916046L;

    /** GCM 心跳 / 连接重连 alarm 类型常量 */
    private static final String ALARM_TYPE_HEARTBEAT = "GCM_HB_ALARM";
    private static final String ALARM_TYPE_CONNECTION = "GCM_CONN_ALARM";

    /** 负倒计时判定阈值（ms） */
    private static final long NEGATIVE_COUNTDOWN_THRESHOLD_MS = -60000L;

    private final Class<?> GcmChimeraService;
    private String GcmChimeraServiceLogMethodName;
    /**
     * 两次"配置可读"握手标志：
     * 构造时 Hook 的 onCreate 与 onCanReadConfig 各触发一次，
     * 第一次仅置位，第二次才执行 checkVersion（确保 context 与配置都就绪）。
     */
    private boolean startHookFlag = false;

    public ReconnectManagerFix(ClassLoader classLoader) {
        super(classLoader);
        this.GcmChimeraService = XposedHelpers.findClass("com.google.android.gms.gcm.GcmChimeraService", classLoader);
        this.addButton();
        this.startHookGcmServiceStart();
    }

    @Override
    protected void onCanReadConfig() throws Throwable {
        if (startHookFlag) {
            this.checkVersion();
            FcmfixConfig.load();
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
            for (Method method : this.GcmChimeraService.getMethods()) {
                if (method.getParameterTypes().length == 2) {
                    if (method.getParameterTypes()[0] == String.class && method.getParameterTypes()[1] == Object[].class) {
                        this.GcmChimeraServiceLogMethodName = method.getName();
                        break;
                    }
                }
            }
            XposedHelpers.findAndHookMethod(this.GcmChimeraService, "onCreate", new XC_MethodHook() {
                @SuppressLint("UnspecifiedRegisterReceiverFlag")
                @Override
                protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                    IntentFilter intentFilter = new IntentFilter(FcmfixConfig.ACTION_LOG);
                    if (Build.VERSION.SDK_INT >= 34) {
                        context.registerReceiver(logBroadcastReceive, intentFilter, Context.RECEIVER_EXPORTED);
                    } else {
                        context.registerReceiver(logBroadcastReceive, intentFilter);
                    }
                    if (startHookFlag) {
                        checkVersion();
                    } else {
                        startHookFlag = true;
                    }
                }
            });
            XposedHelpers.findAndHookMethod(this.GcmChimeraService, "onDestroy", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(final MethodHookParam param) {
                    context.unregisterReceiver(logBroadcastReceive);
                }
            });
        } catch (Throwable e) {
            XposedBridge.log("[fcmfix] GcmChimeraService hook 失败: " + e.getMessage());
        }
    }

    /**
     * 检查 GMS 版本与本地配置，决定是否需要重新发现 hook 点或直接启用。
     */
    private void checkVersion() throws Throwable {
        final SharedPreferences sharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String versionName = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        long versionCode = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).getLongVersionCode();
        if (versionCode < MIN_GMS_VERSION_CODE) {
            printLog("当前为旧版GMS，请使用0.4.1版本FCMFIX，禁用重连修复功能");
            return;
        }
        if (!sharedPreferences.getBoolean(PREF_IS_INIT, false)
                || !configVersion.equals(sharedPreferences.getString(PREF_CONFIG_VERSION, ""))) {
            printLog("fcmfix_config init", true);
            initConfig(sharedPreferences, versionName, versionCode);
            printLog("正在更新hook位置", true);
            findAndUpdateHookTarget(sharedPreferences);
            return;
        }
        if (!sharedPreferences.getString(PREF_GMS_VERSION, "").equals(versionName)) {
            printLog("gms已更新: " + sharedPreferences.getString(PREF_GMS_VERSION, "") + "(" + sharedPreferences.getLong(PREF_GMS_VERSION_CODE, 0) + ")"
                    + "->" + versionName + "(" + versionCode + ")", true);
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putString(PREF_GMS_VERSION, versionName);
            editor.putLong(PREF_GMS_VERSION_CODE, versionCode);
            editor.putBoolean(PREF_ENABLE, false);
            editor.apply();
            printLog("正在更新hook位置", true);
            findAndUpdateHookTarget(sharedPreferences);
            return;
        }
        if (!sharedPreferences.getBoolean(PREF_ENABLE, false)) {
            printLog("当前配置文件enable标识为false，FCMFIX退出", true);
            return;
        }
        startHook();
    }

    private void initConfig(SharedPreferences sharedPreferences, String versionName, long versionCode) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean(PREF_IS_INIT, true);
        editor.putBoolean(PREF_ENABLE, false);
        editor.putLong(PREF_HEARTBEAT_INTERVAL, 0L);
        editor.putLong(PREF_RECONN_INTERVAL, 0L);
        editor.putString(PREF_GMS_VERSION, versionName);
        editor.putLong(PREF_GMS_VERSION_CODE, versionCode);
        editor.putString(PREF_CONFIG_VERSION, configVersion);
        editor.putString(PREF_TIMER_CLASS, "");
        editor.putString(PREF_TIMER_SETTIMEOUT_METHOD, "");
        editor.putString(PREF_TIMER_ALARM_TYPE_PROPERTY, "");
        editor.apply();
    }

    /**
     * 按缓存的 hook 点安装重连修复 Hook（Timer.toString 打标 + setTimeout 间隔改写/负倒计时检测）。
     */
    protected void startHook() {
        final SharedPreferences sharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        printLog("timer_class: " + sharedPreferences.getString(PREF_TIMER_CLASS, ""), true);
        printLog("timer_alarm_type_property: " + sharedPreferences.getString(PREF_TIMER_ALARM_TYPE_PROPERTY, ""), true);
        printLog("timer_settimeout_method: " + sharedPreferences.getString(PREF_TIMER_SETTIMEOUT_METHOD, ""), true);
        final Class<?> timerClazz = XposedHelpers.findClass(sharedPreferences.getString(PREF_TIMER_CLASS, ""), classLoader);

        // 心跳/重连 alarm 生效时，在 Timer.toString 输出中追加标记
        XposedHelpers.findAndHookMethod(timerClazz, "toString", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(final MethodHookParam param) {
                String alarmType = (String) XposedUtils.getObjectFieldByPath(param.thisObject,
                        sharedPreferences.getString(PREF_TIMER_ALARM_TYPE_PROPERTY, ""));
                if (ALARM_TYPE_HEARTBEAT.equals(alarmType) || ALARM_TYPE_CONNECTION.equals(alarmType)) {
                    long hinterval = sharedPreferences.getLong(PREF_HEARTBEAT_INTERVAL, 0L);
                    long cinterval = sharedPreferences.getLong(PREF_RECONN_INTERVAL, 0L);
                    if ((hinterval > 1000) || (cinterval > 1000)) {
                        param.setResult(param.getResult() + "[fcmfix locked]");
                    }
                }
            }
        });

        // 改写心跳/重连超时，并检测异常负倒计时
        XposedHelpers.findAndHookMethod(timerClazz, sharedPreferences.getString(PREF_TIMER_SETTIMEOUT_METHOD, ""), long.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(final MethodHookParam param) {
                // 修改心跳/重连间隔
                String alarmType = (String) XposedUtils.getObjectFieldByPath(param.thisObject,
                        sharedPreferences.getString(PREF_TIMER_ALARM_TYPE_PROPERTY, ""));
                if (ALARM_TYPE_HEARTBEAT.equals(alarmType)) {
                    long interval = sharedPreferences.getLong(PREF_HEARTBEAT_INTERVAL, 0L);
                    if (interval > 1000) {
                        param.args[0] = interval;
                    }
                }
                if (ALARM_TYPE_CONNECTION.equals(alarmType)) {
                    long interval = sharedPreferences.getLong(PREF_RECONN_INTERVAL, 0L);
                    if (interval > 1000) {
                        param.args[0] = interval;
                    }
                }
            }

            @Override
            protected void afterHookedMethod(final MethodHookParam param) {
                // 防止计时器出现负数计时（心跳/重连倒计时），异常时主动触发重连
                String alarmType = (String) XposedUtils.getObjectFieldByPath(param.thisObject,
                        sharedPreferences.getString(PREF_TIMER_ALARM_TYPE_PROPERTY, ""));
                if (ALARM_TYPE_HEARTBEAT.equals(alarmType) || ALARM_TYPE_CONNECTION.equals(alarmType)) {
                    Field maxField = null;
                    long maxFieldValue = 0L;
                    for (Field field : timerClazz.getDeclaredFields()) {
                        if (field.getType() == long.class) {
                            long fieldValue = (long) XposedHelpers.getObjectField(param.thisObject, field.getName());
                            if (maxField == null || fieldValue > maxFieldValue) {
                                maxField = field;
                                maxFieldValue = fieldValue;
                            }
                        }
                    }
                    final Field finalMaxField = maxField;
                    Timer timer = new Timer("ReconnectManagerFix");
                    timer.schedule(new TimerTask() {
                        @Override
                        public void run() {
                            long nextConnectionTime = XposedHelpers.getLongField(param.thisObject, finalMaxField.getName());
                            if (nextConnectionTime != 0 && nextConnectionTime - SystemClock.elapsedRealtime() < NEGATIVE_COUNTDOWN_THRESHOLD_MS) {
                                context.sendBroadcast(new Intent("com.google.android.intent.action.GCM_RECONNECT"));
                                printLog("Send broadcast GCM_RECONNECT", true);
                            }
                            timer.cancel();
                        }
                    }, (long) param.args[0] + 5000);
                }
            }
        });
    }

    /** 诊断日志广播接收器：把 fcmfix 日志写入 GMS 日志（FCM Diagnostics 可见）。 */
    private final BroadcastReceiver logBroadcastReceive = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (FcmfixConfig.ACTION_LOG.equals(intent.getAction())) {
                try {
                    XposedHelpers.callStaticMethod(GcmChimeraService, GcmChimeraServiceLogMethodName,
                            new Class<?>[]{String.class, Object[].class}, "[fcmfix] " + intent.getStringExtra("text"), null);
                } catch (Throwable e) {
                    XposedBridge.log("[fcmfix] 输出日志到fcm失败： [fcmfix] " + intent.getStringExtra("text"));
                }
            }
        }
    };

    /**
     * 自动发现并持久化 hook 点：
     * HeartbeatChimeraAlarm 构造器第 4 参 -> Timer 类 -> 超时设置方法 -> alarm 类型字段。
     * 成功写入 {@code timer_alarm_type_property} 并 enable=true 后立即 startHook。
     */
    private void findAndUpdateHookTarget(final SharedPreferences sharedPreferences) {
        final SharedPreferences.Editor editor = sharedPreferences.edit();
        try {
            Class<?> heartbeatChimeraAlarm = XposedHelpers.findClass("com.google.android.gms.gcm.connection.HeartbeatChimeraAlarm", classLoader);
            Class<?> timerClass = heartbeatChimeraAlarm.getConstructors()[0].getParameterTypes()[3];
            if (timerClass.getDeclaredMethods().length == 0) {
                timerClass = timerClass.getSuperclass();
            }
            editor.putString(PREF_TIMER_CLASS, timerClass.getName());
            for (Method method : timerClass.getDeclaredMethods()) {
                if (method.getParameterTypes().length == 1 && method.getParameterTypes()[0] == long.class
                        && Modifier.isFinal(method.getModifiers()) && Modifier.isPublic(method.getModifiers())) {
                    editor.putString(PREF_TIMER_SETTIMEOUT_METHOD, method.getName());
                    break;
                }
            }
            for (final Field timerClassField : timerClass.getDeclaredFields()) {
                if (Modifier.isFinal(timerClassField.getModifiers()) && Modifier.isPublic(timerClassField.getModifiers())) {
                    final Class<?> alarmClass = timerClassField.getType();
                    final Boolean[] isFinish = {false};
                    Constructor alarmClassConstructor = null;
                    for (Constructor constructor : alarmClass.getConstructors()) {
                        Class[] pts = constructor.getParameterTypes();
                        if (alarmClassConstructor == null || pts.length > alarmClassConstructor.getParameterCount()) {
                            if (pts.length == 3 && pts[0] == Context.class && pts[1] == int.class && pts[2] == String.class) {
                                alarmClassConstructor = constructor;
                            }
                        }
                    }
                    if (alarmClassConstructor == null) {
                        throw new Throwable("未找到构造函数");
                    }
                    XposedBridge.hookMethod(alarmClassConstructor, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(final MethodHookParam param) {
                            if (!isFinish[0]) {
                                for (Field field : alarmClass.getDeclaredFields()) {
                                    if (field.getType() == String.class && Modifier.isFinal(field.getModifiers()) && Modifier.isPrivate(field.getModifiers())) {
                                        if (param.args[2] != null && XposedHelpers.getObjectField(param.thisObject, field.getName()) == param.args[2]) {
                                            SharedPreferences.Editor editor = sharedPreferences.edit();
                                            editor.putString(PREF_TIMER_ALARM_TYPE_PROPERTY, timerClassField.getName() + "." + field.getName());
                                            editor.putBoolean(PREF_ENABLE, true);
                                            editor.apply();
                                            isFinish[0] = true;
                                            printLog("更新hook位置成功", true);
                                            sendNotification("自动更新配置文件成功");
                                            startHook();
                                            return;
                                        }
                                    }
                                }
                                printLog("自动寻找hook点失败: 未找到目标方法", true);
                            }
                        }
                    });
                    break;
                }
            }
        } catch (Throwable e) {
            editor.putBoolean(PREF_ENABLE, false);
            printLog("自动寻找hook点失败" + e.getMessage(), true);
            this.sendNotification("自动更新配置文件失败", "未能找到hook点，已禁用重连修复和固定心跳功能。");
        }
        editor.apply();
    }

    /**
     * 在 GMS 的 FCM Diagnostics 页面注入两个按钮：
     * RECONNECT（发送重连广播）、打开FCMFIX（启动本模块设置界面）。
     */
    private void addButton() {
        XposedHelpers.findAndHookMethod("com.google.android.gms.gcm.GcmChimeraDiagnostics", classLoader, "onCreate", Bundle.class, new XC_MethodHook() {
            @SuppressLint("SetTextI18n")
            @Override
            protected void afterHookedMethod(final MethodHookParam param) {
                ViewGroup viewGroup = ((Window) XposedHelpers.callMethod(param.thisObject, "getWindow")).getDecorView().findViewById(android.R.id.content);
                LinearLayout linearLayout = (LinearLayout) viewGroup.getChildAt(0);
                LinearLayout linearLayout2 = (LinearLayout) linearLayout.getChildAt(0);

                Button reConnectButton = new Button((ContextWrapper) param.thisObject);
                reConnectButton.setText("RECONNECT");
                reConnectButton.setOnClickListener(view -> {
                    context.sendBroadcast(new Intent("com.google.android.intent.action.GCM_RECONNECT"));
                    printLog("Send broadcast GCM_RECONNECT", true);
                });
                linearLayout2.addView(reConnectButton);

                Button openFcmFixButton = new Button((ContextWrapper) param.thisObject);
                openFcmFixButton.setText("打开FCMFIX");
                openFcmFixButton.setOnClickListener(view -> {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    intent.setPackage(FcmfixConfig.SELF_PACKAGE);
                    intent.setComponent(new ComponentName(FcmfixConfig.SELF_PACKAGE, FcmfixConfig.SELF_PACKAGE + ".MainActivity"));
                    context.startActivity(intent);
                });
                linearLayout2.addView(openFcmFixButton);
            }
        });
    }
}
