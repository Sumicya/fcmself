package sumicya.fcmself.xposed;

import java.lang.reflect.Method;
import sumicya.fcmself.libxposed.XC_MethodHook;
import sumicya.fcmself.libxposed.XposedBridge;
import sumicya.fcmself.libxposed.XposedHelpers;

/**
 * MIUI 本地通知修复模块
 * 
 * 功能说明：
 * MIUI 系统会限制某些应用的本地通知显示，导致 FCM 消息即使到达也无法在状态栏显示。
 * 本模块通过 Hook MIUI 的通知管理服务，强制允许指定应用显示本地通知。
 * 
 * 工作原理：
 * 1. 查找 MIUI 特有的通知管理类（NotificationManagerServiceInjector 或 NotificationManagerServiceImpl）
 * 2. Hook 其中的 isAllowLocalNotification 或 isDeniedLocalNotification 方法
 * 3. 当检测到目标应用时，强制返回允许显示通知的结果
 * 
 * 适用场景：
 * - MIUI 系统上应用无法显示本地通知
 * - FCM 消息到达但状态栏无提示
 * - 需要确保重要通知不被系统拦截
 * 
 * @author fcmself
 */
public class MiuiLocalNotificationFix extends XposedModule {

    public MiuiLocalNotificationFix(ClassLoader classLoader) {
        super(classLoader);
        this.startHook();
    }

    /**
     * 开始 Hook 操作
     * 查找并 Hook MIUI 通知管理相关方法
     */
    protected void startHook(){
        try{
            Class<?> clazz;
            // 尝试查找 MIUI 的通知管理类，不同版本类名可能不同
            try{
                clazz = XposedHelpers.findClass("com.android.server.notification.NotificationManagerServiceInjector",classLoader);
            } catch (XposedHelpers.ClassNotFoundError e) {
                // 如果找不到 Injector 类，尝试查找 Impl 类
                clazz = XposedHelpers.findClass("com.android.server.notification.NotificationManagerServiceImpl",classLoader);
            }
            
            final Method[] declareMethods = clazz.getDeclaredMethods();
            Method targetMethod = null;
            
            // 遍历所有方法，查找目标方法
            for(Method method : declareMethods){
                if("isAllowLocalNotification".equals(method.getName()) || "isDeniedLocalNotification".equals(method.getName())){
                    targetMethod = method;
                    break;
                }
            }
            
            if(targetMethod != null){
                Method finalTargetMethod = targetMethod;
                XposedBridge.hookMethod(targetMethod,new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam methodHookParam) {
                        // 检查是否是目标应用，如果是则强制允许/拒绝通知
                        if(hasTargetPackage((String)methodHookParam.args[3])){
                            // 根据原方法名决定返回值：
                            // isAllowLocalNotification -> 返回 true (允许)
                            // isDeniedLocalNotification -> 返回 false (不拒绝)
                            methodHookParam.setResult("isAllowLocalNotification".equals(finalTargetMethod.getName()));
                        }
                    }
                });
            }else{
                printLog("Not found [isAllowLocalNotification/isDeniedLocalNotification] in com.android.server.notification.[NotificationManagerServiceInjector/NotificationManagerServiceImpl]");
            }
        }catch (XposedHelpers.ClassNotFoundError e){
            printLog("Not found [isAllowLocalNotification/isDeniedLocalNotification] in com.android.server.notification.[NotificationManagerServiceInjector/NotificationManagerServiceImpl]");
        }

    }
}
