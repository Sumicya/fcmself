package sumicya.fcmself.xposed;

import android.content.Context;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

import sumicya.fcmself.util.Hooks;
import sumicya.fcmself.util.Reflect;

/**
 * PowerkeeperFix - MIUI PowerKeeper 对 GMS 的限制修复
 *
 * <p>MIUI 的 PowerKeeper 会把 GMS 加入后台限制黑名单。本模块：
 * <ul>
 *   <li>把 {@code MilletConfig.isGlobal} 置为 true；</li>
 *   <li>让 {@code SimpleSettings.Misc.getBoolean("gms_control", ...)} 恒返回 false；</li>
 *   <li>在 {@code MilletPolicy} 构造时把 GMS 从黑名单移除、加入数据白名单。</li>
 * </ul>
 * 非 MIUI 设备上这些类不存在，各点独立跳过。
 */
public class PowerkeeperFix extends XposedModule {

    public PowerkeeperFix(XposedInterface api, ClassLoader classLoader) {
        super(api, classLoader);
        try {
            this.startHook();
        } catch (Throwable e) {
            printLog("hook error PowerkeeperFix:" + e.getMessage());
        }
    }

    protected void startHook() {
        hookMilletConfig();
        hookMiscGetBoolean();
        hookMilletPolicyConstructor();
    }

    /** MilletConfig.isGlobal = true */
    private void hookMilletConfig() {
        try {
            Class<?> clazz = Reflect.findClassIfExists("com.miui.powerkeeper.millet.MilletConfig", classLoader);
            if (clazz == null) {
                return;
            }
            Reflect.setStaticObjectField(clazz, "isGlobal", true);
            printLog("Set com.miui.powerkeeper.millet.MilletConfig.isGlobal to true");
        } catch (Throwable e) {
            printLog("No Such Class com.miui.powerkeeper.millet.MilletConfig");
        }
    }

    /** SimpleSettings.Misc.getBoolean("gms_control", ...) 恒返回 false */
    private void hookMiscGetBoolean() {
        try {
            Class<?> clazz = Reflect.findClassIfExists(
                    "com.miui.powerkeeper.provider.SimpleSettings$Misc", classLoader);
            if (clazz == null) {
                return;
            }
            Method method = Reflect.findMethodByParamCount(clazz, "getBoolean", 3);
            if (method == null) {
                return;
            }
            Hooks.hook(api, method, chain -> {
                Object result = chain.proceed();
                Object arg1 = chain.getArg(1);
                if ("gms_control".equals(arg1)) {
                    printLog("Success: PowerKeeper GMS Limitation.", true);
                    return false;
                }
                return result;
            });
        } catch (Throwable e) {
            printLog("No Such Method com.miui.powerkeeper.provider.SimpleSettings.Misc.getBoolean");
        }
    }

    /** MilletPolicy 构造时移除 GMS 的黑名单项，加入数据白名单 */
    private void hookMilletPolicyConstructor() {
        try {
            Class<?> clazz = Reflect.findClassIfExists("com.miui.powerkeeper.millet.MilletPolicy", classLoader);
            if (clazz == null) {
                return;
            }
            Constructor<?> constructor = Reflect.findConstructorMostMatch(clazz, Context.class);
            Hooks.hookAfter(api, constructor, (chain, error) -> {
                Object policy = chain.getThisObject();
                for (Field field : clazz.getDeclaredFields()) {
                    String name = field.getName();
                    if ("mSystemBlackList".equals(name)) {
                        List<String> list = (List<String>) Reflect.getObjectField(policy, name);
                        list.remove("com.google.android.gms");
                        Reflect.setObjectField(policy, name, list);
                        printLog("Success: MilletPolicy mSystemBlackList.");
                    } else if ("whiteApps".equals(name)) {
                        List<String> list = (List<String>) Reflect.getObjectField(policy, name);
                        list.remove("com.google.android.gms");
                        list.remove("com.google.android.ext.services");
                        Reflect.setObjectField(policy, name, list);
                        printLog("Success: MilletPolicy whiteApps.");
                    } else if ("mDataWhiteList".equals(name)) {
                        List<String> list = (List<String>) Reflect.getObjectField(policy, name);
                        if (!list.contains("com.google.android.gms")) {
                            list.add("com.google.android.gms");
                        }
                        Reflect.setObjectField(policy, name, list);
                        printLog("Success: MilletPolicy mDataWhiteList.");
                    }
                }
            });
        } catch (Throwable e) {
            printLog("No Such Method com.miui.powerkeeper.millet.MilletPolicy");
        }
    }
}
