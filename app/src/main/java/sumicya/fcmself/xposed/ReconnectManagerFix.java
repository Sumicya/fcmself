package sumicya.fcmself.xposed;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
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

import sumicya.fcmself.config.FcmselfConfig;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import sumicya.fcmself.util.Hooks;
import sumicya.fcmself.util.Reflect;

import io.github.libxposed.api.XposedInterface;

/**
 * ReconnectManagerFix - GMS 长连接重连修复模块（运行在 com.google.android.gms 进程）
 *
 * 功能说明：
 * 国内网络环境下 GMS 与 Google 服务器之间的长连接容易断开且重连缓慢。
 * 本模块 Hook GMS 内部的心跳/重连计时器：
 * <ul>
 *   <li>心跳/重连倒计时出现异常负值时主动发送 GCM_RECONNECT 广播触发重连；</li>
 *   <li>在 FCM Diagnostics 页面注入 RECONNECT 按钮；</li>
 *   <li>把 fcmself 诊断日志转发到 GMS 日志（便于在 FCM Diagnostics 查看）。</li>
 * </ul>
 *
 * 工作原理：
 * GMS 内部 Timer 相关类名随版本变化，因此 hook 点采用"自动发现 + 持久化"策略：
 * 首次运行或 GMS 更新时从 {@code com.google.android.gms.gcm.connection.HeartbeatChimeraAlarm}
 * 出发定位 Timer 类/设置超时的方法/alarm 类型字段，结果存入 GMS 本地
 * SharedPreferences {@value #PREF_NAME}，之后直接按缓存 hook。
 *
 * 状态项（GMS 本地 SharedPreferences {@value #PREF_NAME}）：
 * {@code enable}（由自动发现流程写入）、缓存的 hook 点、GMS 版本号等，全部由本模块自己维护，
 * 用户无需配置。
 *
 * <p>历史版本还支持固定心跳/重连间隔（heartbeatInterval / reconnInterval），但那两项从来
 * 没有写入入口、始终为 0（{@code >1000} 才生效），已连同相关分支一起移除。
 */
public class ReconnectManagerFix extends XposedModule {

    /**
     * 负倒计时检测共用的调度器。
     *
     * <p>原先每次 setTimeout 都 {@code new Timer(...)}，即每次心跳/重连都新建一个线程
     * （任务结束才 cancel）。改用一个守护线程的共享调度器后，线程数与消息量解耦。
     */
    private static final ScheduledExecutorService NEGATIVE_COUNTDOWN_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "fcmself-countdown");
                thread.setDaemon(true);
                return thread;
            });

    /** setTimeout 之后多久复查倒计时（ms） */
    private static final long COUNTDOWN_CHECK_DELAY_MS = 5000L;

    private static final String PREF_NAME = "fcmself_config";
    private static final String PREF_IS_INIT = "isInit";
    private static final String PREF_CONFIG_VERSION = "config_version";
    private static final String PREF_ENABLE = "enable";
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

    public ReconnectManagerFix(XposedInterface api, ClassLoader classLoader) {
        super(api, classLoader);
        this.GcmChimeraService = Reflect.findClass("com.google.android.gms.gcm.GcmChimeraService", classLoader);
        this.addButton();
        this.startHookGcmServiceStart();
    }

    @Override
    protected void onCanReadConfig() throws Throwable {
        if (startHookFlag) {
            this.checkVersion();
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
            Hooks.hookMethodAfter(api, this.GcmChimeraService, "onCreate", new Class<?>[0],
                    (chain, error) -> {
                        registerLogReceiver();
                        if (startHookFlag) {
                            checkVersion();
                        } else {
                            startHookFlag = true;
                        }
                    });
            Hooks.hookMethod(api, this.GcmChimeraService, "onDestroy", new Class<?>[0], chain -> {
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

    /**
     * 检查 GMS 版本与本地配置，决定是否需要重新发现 hook 点或直接启用。
     */
    private void checkVersion() throws Throwable {
        final SharedPreferences sharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String versionName = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        long versionCode = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).getLongVersionCode();
        if (versionCode < MIN_GMS_VERSION_CODE) {
            printLog("当前为旧版GMS，请使用0.4.1版本FCMSELF，禁用重连修复功能");
            return;
        }
        if (!sharedPreferences.getBoolean(PREF_IS_INIT, false)
                || !configVersion.equals(sharedPreferences.getString(PREF_CONFIG_VERSION, ""))) {
            printLog("fcmself_config init", true);
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
            printLog("当前配置文件enable标识为false，FCMSELF退出", true);
            return;
        }
        startHook();
    }

    private void initConfig(SharedPreferences sharedPreferences, String versionName, long versionCode) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean(PREF_IS_INIT, true);
        editor.putBoolean(PREF_ENABLE, false);
        editor.putString(PREF_GMS_VERSION, versionName);
        editor.putLong(PREF_GMS_VERSION_CODE, versionCode);
        editor.putString(PREF_CONFIG_VERSION, configVersion);
        editor.putString(PREF_TIMER_CLASS, "");
        editor.putString(PREF_TIMER_SETTIMEOUT_METHOD, "");
        editor.putString(PREF_TIMER_ALARM_TYPE_PROPERTY, "");
        editor.apply();
    }

    /**
     * 按缓存的 hook 点安装重连修复 Hook（setTimeout 负倒计时检测）。
     */
    protected void startHook() {
        final SharedPreferences sharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        printLog("timer_class: " + sharedPreferences.getString(PREF_TIMER_CLASS, ""), true);
        printLog("timer_alarm_type_property: " + sharedPreferences.getString(PREF_TIMER_ALARM_TYPE_PROPERTY, ""), true);
        printLog("timer_settimeout_method: " + sharedPreferences.getString(PREF_TIMER_SETTIMEOUT_METHOD, ""), true);
        final Class<?> timerClazz = Reflect.findClass(sharedPreferences.getString(PREF_TIMER_CLASS, ""), classLoader);

        // 检测心跳/重连倒计时是否出现异常负值
        Hooks.hookMethodAfter(api, timerClazz,
                sharedPreferences.getString(PREF_TIMER_SETTIMEOUT_METHOD, ""), new Class<?>[]{long.class},
                (chain, error) -> {
                    // Chain 不能跨线程或跨调用复用，延时任务要用的值先取出来
                    final Object timer = chain.getThisObject();
                    final long timeout = (long) chain.getArg(0);
                    // 防止计时器出现负数计时（心跳/重连倒计时），异常时主动触发重连
                    String alarmType = (String) Reflect.getObjectFieldByPath(timer,
                            sharedPreferences.getString(PREF_TIMER_ALARM_TYPE_PROPERTY, ""));
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
                    Reflect.callStaticMethod(GcmChimeraService, GcmChimeraServiceLogMethodName,
                            new Class<?>[]{String.class, Object[].class}, "[fcmself] " + intent.getStringExtra("text"), null);
                } catch (Throwable e) {
                    printLog("输出日志到fcm失败：" + intent.getStringExtra("text"));
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
            Class<?> heartbeatChimeraAlarm = Reflect.findClass("com.google.android.gms.gcm.connection.HeartbeatChimeraAlarm", classLoader);
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
                    Hooks.hookAfter(api, alarmClassConstructor, (chain, err) -> {
                        Object alarm = chain.getThisObject();
                        Object alarmTag = chain.getArg(2);
                        if (!isFinish[0]) {
                            for (Field field : alarmClass.getDeclaredFields()) {
                                if (field.getType() == String.class && Modifier.isFinal(field.getModifiers()) && Modifier.isPrivate(field.getModifiers())) {
                                    if (alarmTag != null && Reflect.getObjectField(alarm, field.getName()) == alarmTag) {
                                        // 注意：这里必须是新的 Editor。外层 findAndUpdateHookTarget
                                        // 也有一个同名局部变量 editor，改成 lambda 后两者同处一个
                                        // 作用域（原来在匿名类里是各自的作用域），故换个名字。
                                        SharedPreferences.Editor hookPointEditor = sharedPreferences.edit();
                                        hookPointEditor.putString(PREF_TIMER_ALARM_TYPE_PROPERTY, timerClassField.getName() + "." + field.getName());
                                        hookPointEditor.putBoolean(PREF_ENABLE, true);
                                        hookPointEditor.apply();
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
     * 在 GMS 的 FCM Diagnostics 页面注入 RECONNECT 按钮（发送重连广播）。
     */
    private void addButton() {
        Method onCreate = Reflect.findMethodExact(
                Reflect.findClass("com.google.android.gms.gcm.GcmChimeraDiagnostics", classLoader),
                "onCreate", Bundle.class);
        Hooks.hookAfter(api, onCreate, (chain, error) -> injectReconnectButton(chain.getThisObject()));
    }

    @SuppressLint("SetTextI18n")
    private void injectReconnectButton(Object activity) {
        ViewGroup viewGroup = ((Window) Reflect.callMethod(activity, "getWindow")).getDecorView().findViewById(android.R.id.content);
        LinearLayout linearLayout = (LinearLayout) viewGroup.getChildAt(0);
        LinearLayout linearLayout2 = (LinearLayout) linearLayout.getChildAt(0);

        Button reConnectButton = new Button((ContextWrapper) activity);
        reConnectButton.setText("RECONNECT");
        reConnectButton.setOnClickListener(view -> {
            context.sendBroadcast(new Intent("com.google.android.intent.action.GCM_RECONNECT"));
            printLog("Send broadcast GCM_RECONNECT", true);
        });
        linearLayout2.addView(reConnectButton);
    }
}
