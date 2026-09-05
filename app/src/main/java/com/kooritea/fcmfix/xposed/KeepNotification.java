package com.kooritea.fcmfix.xposed;

import android.os.Build;
import android.service.notification.NotificationListenerService;

import java.lang.reflect.Method;
import com.kooritea.fcmfix.libxposed.XC_MethodHook;
import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.libxposed.XposedHelpers;

/**
 * 通知保持模块 - 防止系统自动清除 FCM 通知
 * 
 * 功能说明：
 * 部分 Android 系统会在应用未启动或后台运行时自动清除其通知，导致用户无法看到 FCM 推送。
 * 本模块通过 Hook 系统的通知管理服务，阻止系统自动清除目标应用的通知。
 * 
 * 工作原理：
 * 1. 查找 NotificationManagerService 类中的 cancelAllNotificationsInt 方法
 * 2. 根据不同 Android 版本确定参数位置（包名和原因代码）
 * 3. 当检测到目标应用且原因是 PACKAGE_CHANGED 或特定代码时，阻止取消通知
 * 
 * 支持的 Android 版本：
 * - Android 10 (API 30) 到 Android 15 (API 35) 及以上
 * - 自动适配不同版本的方法签名变化
 * 
 * 配置项：
 * - disableAutoCleanNotification: 启用后阻止系统自动清除通知
 * 
 * @author fcmfix
 */
public class KeepNotification extends XposedModule{

    public KeepNotification(ClassLoader classLoader) {
        super(classLoader);
        try {
            this.startHook();
        } catch (Throwable e) {
            printLog("No Such Method com.android.server.notification.NotificationManagerService.cancelAllNotificationsInt");
        }
    }
    
    /**
     * 开始 Hook 操作
     * Hook 通知取消方法，防止系统自动清除目标应用的通知
     * 
     * @throws NoSuchMethodError 当找不到目标方法时抛出
     * @throws XposedHelpers.ClassNotFoundError 当找不到目标类时抛出
     */
    protected void startHook() throws NoSuchMethodError, XposedHelpers.ClassNotFoundError {
        // 查找通知管理服务类
        Class<?> clazz = XposedHelpers.findClass("com.android.server.notification.NotificationManagerService",classLoader);
        final Method[] declareMethods = clazz.getDeclaredMethods();
        Method targetMethod = null;
        
        // 查找 cancelAllNotificationsInt 方法，选择参数最多的版本（通常是最新的）
        for(Method method : declareMethods){
            if("cancelAllNotificationsInt".equals(method.getName())){
                if(targetMethod == null || targetMethod.getParameterTypes().length < method.getParameterTypes().length){
                    targetMethod = method;
                }
            }
        }
        
        if(targetMethod != null){
            int pkg_args_index = 0;  // 包名参数索引
            int reason_args_index = 0;  // 原因代码参数索引
            
            // 根据不同 Android 版本设置参数索引
            if(Build.VERSION.SDK_INT == 30){
                pkg_args_index = 2;
                reason_args_index = 8;
            }
            if(Build.VERSION.SDK_INT == 31){
                pkg_args_index = 2;
                reason_args_index = 8;
            }
            if(Build.VERSION.SDK_INT == 32){
                pkg_args_index = 2;
                reason_args_index = 8;
            }
            if(Build.VERSION.SDK_INT == 33){
                pkg_args_index = 2;
                reason_args_index = 8;
            }
            if(Build.VERSION.SDK_INT == 34){
                if(targetMethod.getParameterTypes().length == 10){
                    pkg_args_index = 2;
                    reason_args_index = 8;
                }else if(targetMethod.getParameterTypes().length == 8){
                    pkg_args_index = 2;
                    reason_args_index = 7;
                }
            }
            if(Build.VERSION.SDK_INT == 35){
                pkg_args_index = 2;
                reason_args_index = 7;
            }
            if(Build.VERSION.SDK_INT > 35){
                pkg_args_index = 2;
                reason_args_index = 7;
            }
            
            // 如果参数索引未正确设置，抛出异常
            if(pkg_args_index == 0 || reason_args_index == 0){
                throw new NoSuchMethodError();
            }
            
            int finalPkg_args_index = pkg_args_index;
            int finalReason_args_index = reason_args_index;
            
            // Hook 目标方法
            XposedBridge.hookMethod(targetMethod,new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    // 检查系统是否已完成启动
                    if(!isBootComplete){
                        return;
                    }
                    
                    // 如果启用了禁用自动清理通知功能且是目标应用
                    if(getBooleanConfig("disableAutoCleanNotification",false) && targetIsAllow((String) param.args[finalPkg_args_index])){
                        int reason = (int)param.args[finalReason_args_index];
                        
                        // 如果原因是应用包变化（如更新），阻止取消通知
                        if(reason == NotificationListenerService.REASON_PACKAGE_CHANGED){
                            param.setResult(null);
                        }
                        
                        // 处理 ColorOS 15 / OxygenOS 15 的特定原因代码
                        if(reason == 10020 || reason == 10021){ // cos15/oos15
                            param.setResult(null);
                        }
                    }
                }
            });
        }else{
            throw new NoSuchMethodError();
        }
    }
}
