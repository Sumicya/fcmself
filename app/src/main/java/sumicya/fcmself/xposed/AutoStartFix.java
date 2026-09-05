package sumicya.fcmself.xposed;

import android.content.Intent;

import java.lang.reflect.Method;

import sumicya.fcmself.libxposed.XC_MethodHook;
import sumicya.fcmself.libxposed.XposedBridge;
import sumicya.fcmself.libxposed.XposedHelpers;
import sumicya.fcmself.util.XposedUtils;

/**
 * AutoStartFix - ColorOS / OxygenOS 自启动限制修复
 *
 * <p>Hook {@code OplusAppStartupManager.shouldPreventSendReceiverReal}：当被拦截的广播是
 * FCM 消息时返回 false，允许已停止的应用被唤醒接收。
 *
 * <p>本模块现在只面向 ColorOS / OxygenOS。历史上这里还覆盖了 MIUI 12/13、HyperOS、
 * SmartPower 的自启动管理点（{@code BroadcastQueueInjector} / {@code BroadcastQueueImpl} /
 * {@code BroadcastQueueModernStubImpl} / {@code AutoStartManagerServiceStubImpl} /
 * {@code SmartPowerService} / {@code SmartPowerPolicyManager}），已按需求移除，
 * 需要时可从 git 历史恢复（见 commit "refactor: 移除 MIUI/HyperOS 支持"）。
 */
public class AutoStartFix extends XposedModule {

    public AutoStartFix(ClassLoader classLoader) {
        super(classLoader);
        try {
            this.startHook();
        } catch (Throwable e) {
            printLog("hook error AutoStartFix:" + e.getMessage());
        }
    }

    /** OOS 15 / ColorOS 15: OplusAppStartupManager.shouldPreventSendReceiverReal */
    protected void startHook() {
        try {
            Method method = XposedUtils.findMethod(
                    XposedHelpers.findClass("com.android.server.am.OplusAppStartupManager", classLoader),
                    "shouldPreventSendReceiverReal", 4);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                    if (methodHookParam.args[0] != null) {
                        Intent intent = intentOfField(methodHookParam.args[0]);
                        if (isFCMIntent(intent) && hasTargetPackage(intent.getPackage())) {
                            methodHookParam.setResult(false);
                        }
                    }
                }
            });
        } catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError e) {
            printLog("No Such Method com.android.server.am.OplusAppStartupManager.shouldPreventSendReceiverReal");
        }
    }
}
