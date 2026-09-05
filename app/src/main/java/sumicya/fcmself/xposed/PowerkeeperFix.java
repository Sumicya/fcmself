package sumicya.fcmself.xposed;

import android.content.Context;

import java.lang.reflect.Field;
import java.util.List;

import sumicya.fcmself.libxposed.XC_MethodHook;
import sumicya.fcmself.libxposed.XposedHelpers;
import sumicya.fcmself.util.XposedUtils;

/**
 * MIUI PowerKeeper 电源管理修复模块
 *
 * 功能说明：
 * MIUI 的 PowerKeeper（电量管家）会限制 GMS 后台活动，导致 FCM 推送无法正常工作。
 * 本模块通过修改 PowerKeeper 的内部配置，解除对 GMS 的限制，确保 FCM 消息正常接收。
 *
 * 工作原理：
 * 1. 设置 MilletConfig.isGlobal = true，启用全局模式
 * 2. Hook SimpleSettings.Misc.getBoolean()，当查询 "gms_control" 时返回 false（不限制 GMS）
 * 3. Hook MilletPolicy 构造函数，修改三个关键列表：
 *    - mSystemBlackList: 从系统黑名单中移除 GMS
 *    - whiteApps: 从白名单应用中移除 GMS 和相关服务（避免特殊处理）
 *    - mDataWhiteList: 将 GMS 添加到数据网络白名单
 *
 * 适用场景：
 * - MIUI 系统上 GMS 被电源管理限制
 * - FCM 消息延迟或无法接收
 * - 应用后台被 MIUI 强制杀死
 */
public class PowerkeeperFix extends XposedModule {

    private static final String GMS_PACKAGE = "com.google.android.gms";
    private static final String GMS_CONTROL_KEY = "gms_control";

    public PowerkeeperFix(ClassLoader classLoader) {
        super(classLoader);
        this.startHook();
    }

    /**
     * 开始 Hook 操作
     * 修改 MIUI PowerKeeper 的电源管理策略
     */
    protected void startHook() {
        try {
            // 1. 设置 MilletConfig.isGlobal = true，启用全局管理模式
            Class<?> MilletConfig = XposedHelpers.findClassIfExists("com.miui.powerkeeper.millet.MilletConfig", classLoader);
            XposedHelpers.setStaticBooleanField(MilletConfig, "isGlobal", true);
            printLog("Set com.miui.powerkeeper.millet.MilletConfig.isGlobal to true");

            // 2. Hook SimpleSettings.Misc.getBoolean()，绕过 GMS 控制限制
            Class<?> Misc = XposedHelpers.findClassIfExists("com.miui.powerkeeper.provider.SimpleSettings.Misc", classLoader);
            printLog("[fcmself] start hook com.miui.powerkeeper.provider.SimpleSettings.Misc.getBoolean");
            XposedUtils.findAndHookMethod(Misc, "getBoolean", 3, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                    // 当查询 "gms_control" 时，返回 false 表示不限制 GMS
                    if (GMS_CONTROL_KEY.equals((String) methodHookParam.args[1])) {
                        printLog("Success: PowerKeeper GMS Limitation. ", true);
                        methodHookParam.setResult(false);
                    }
                }
            });

            // 3. Hook MilletPolicy(Context) 构造函数，在构造前修改电源管理策略列表。
            //    注意：必须写回字段（setObjectField），部分 MIUI 版本的列表字段为 final 引用。
            Class<?> MilletPolicy = XposedHelpers.findClassIfExists("com.miui.powerkeeper.millet.MilletPolicy", classLoader);

            XC_MethodHook methodHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                    boolean mSystemBlackList = false;
                    boolean whiteApps = false;
                    boolean mDataWhiteList = false;

                    // 检查 MilletPolicy 类中实际存在的字段（不同 MIUI 版本字段不同）
                    for (Field field : MilletPolicy.getDeclaredFields()) {
                        if (field.getName().equals("mSystemBlackList")) {
                            mSystemBlackList = true;
                        } else if (field.getName().equals("whiteApps")) {
                            whiteApps = true;
                        } else if (field.getName().equals("mDataWhiteList")) {
                            mDataWhiteList = true;
                        }
                    }

                    // 从系统黑名单中移除 GMS，允许其后台运行
                    if (mSystemBlackList) {
                        List blackList = (List) XposedHelpers.getObjectField(methodHookParam.thisObject, "mSystemBlackList");
                        blackList.remove(GMS_PACKAGE);
                        XposedHelpers.setObjectField(methodHookParam.thisObject, "mSystemBlackList", blackList);
                        printLog("Success: MilletPolicy mSystemBlackList.");
                    } else {
                        printLog("Error: MilletPolicy. Field not found: com.miui.powerkeeper.millet.MilletPolicy.mSystemBlackList");
                    }

                    // 从白名单应用中移除 GMS 和相关服务，避免特殊处理导致的问题
                    if (whiteApps) {
                        List whiteAppList = (List) XposedHelpers.getObjectField(methodHookParam.thisObject, "whiteApps");
                        whiteAppList.remove(GMS_PACKAGE);
                        whiteAppList.remove("com.google.android.ext.services");
                        XposedHelpers.setObjectField(methodHookParam.thisObject, "whiteApps", whiteAppList);
                        printLog("Success: MilletPolicy whiteApps.");
                    } else {
                        printLog("Error: MilletPolicy. Field not found: com.miui.powerkeeper.millet.MilletPolicy.whiteApps");
                    }

                    // 将 GMS 添加到数据网络白名单，确保后台联网权限
                    if (mDataWhiteList) {
                        List dataWhiteList = (List) XposedHelpers.getObjectField(methodHookParam.thisObject, "mDataWhiteList");
                        dataWhiteList.add(GMS_PACKAGE);
                        XposedHelpers.setObjectField(methodHookParam.thisObject, "mDataWhiteList", dataWhiteList);
                        printLog("Success: MilletPolicy mDataWhiteList.");
                    }
                }
            };
            printLog("[fcmself] start hook com.miui.powerkeeper.millet.MilletPolicy constructor");
            XposedHelpers.findAndHookConstructor(MilletPolicy, Context.class, methodHook);
        } catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError e) {
            printLog("No Such Method com.miui.powerkeeper.millet.MilletPolicy constructor");
        }
    }
}
