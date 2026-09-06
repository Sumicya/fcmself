package sumicya.fcmself.xposed;

import android.content.Intent;

import java.lang.reflect.Method;

import sumicya.fcmself.util.Hooks;
import sumicya.fcmself.util.Reflect;

import io.github.libxposed.api.XposedInterface;

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

    public AutoStartFix(XposedInterface api, ClassLoader classLoader) {
        super(api, classLoader);
        try {
            this.startHook();
        } catch (Throwable e) {
            printLog("hook error AutoStartFix:" + e.getMessage());
        }
    }

    /** OOS 15 / ColorOS 15: OplusAppStartupManager.shouldPreventSendReceiverReal */
    protected void startHook() {
        try {
            Class<?> clazz = Reflect.findClass("com.android.server.am.OplusAppStartupManager", classLoader);
            Method method = Reflect.findMethodByParamCount(clazz, "shouldPreventSendReceiverReal", 4);
            if (method == null) {
                throw new NoSuchMethodError(clazz.getName() + "#shouldPreventSendReceiverReal");
            }
            Hooks.hook(api, method, chain -> {
                Object holder = chain.getArg(0);
                if (holder != null) {
                    Intent intent = intentOfField(holder);
                    if (isFCMIntent(intent) && hasTargetPackage(intent.getPackage())) {
                        // 不调用 chain.proceed()，直接返回 false = 放行这条广播
                        return false;
                    }
                }
                return chain.proceed();
            });
        } catch (Reflect.ClassNotFound | NoSuchMethodError e) {
            printLog("No Such Method com.android.server.am.OplusAppStartupManager.shouldPreventSendReceiverReal");
        }
    }
}
