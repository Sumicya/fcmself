package sumicya.fcmself

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface

import sumicya.fcmself.util.FcmselfLog
import sumicya.fcmself.xposed.AutoStartFix
import sumicya.fcmself.xposed.BroadcastFix
import sumicya.fcmself.xposed.KeepNotification
import sumicya.fcmself.xposed.MiuiLocalNotificationFix
import sumicya.fcmself.xposed.OplusProxyFix
import sumicya.fcmself.xposed.PowerkeeperFix
import sumicya.fcmself.xposed.ReconnectManagerFix
import sumicya.fcmself.xposed.XposedModule

/**
 * fcmself LSPosed 入口。
 *
 * 入口只负责"按进程分发"，具体 Hook 哪些模块由下方的清单声明：
 * - [SYSTEM_SERVER_MODULES]：system_server（进程身份 "android"）内安装的模块；
 * - [PACKAGE_MODULES]：目标应用包名 -> 该进程内安装的模块。
 *
 * 新增一个 Hook 模块只需在对应清单里登记一行，无需改动分发逻辑。
 *
 * 每个模块独立 try/catch：单个模块安装失败只影响自身，不会阻断后续模块。
 */
class XposedMain : io.github.libxposed.api.XposedModule() {

    /** Hook 模块登记项（名字仅用于日志）。 */
    private class ModuleEntry(val name: String, val factory: (XposedInterface, ClassLoader) -> XposedModule)

    private companion object {
        const val PKG_GMS = "com.google.android.gms"

        /** system_server 内安装的模块，顺序即安装顺序。 */
        val SYSTEM_SERVER_MODULES: List<ModuleEntry> = listOf(
            ModuleEntry("BroadcastFix", ::BroadcastFix),
            ModuleEntry("AutoStartFix", ::AutoStartFix),
            ModuleEntry("KeepNotification", ::KeepNotification),
            ModuleEntry("MiuiLocalNotificationFix", ::MiuiLocalNotificationFix),
            ModuleEntry("OplusProxyFix", ::OplusProxyFix),
            ModuleEntry("PowerkeeperFix", ::PowerkeeperFix)
        )

        /** 目标进程包名 -> 该进程内安装的模块。 */
        val PACKAGE_MODULES: Map<String, List<ModuleEntry>> = mapOf(
            PKG_GMS to listOf(ModuleEntry("ReconnectManagerFix", ::ReconnectManagerFix))
        )
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        FcmselfLog.setXposed(this)
        FcmselfLog.selfPackageName = "android"
        installAll(SYSTEM_SERVER_MODULES, param.classLoader)
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        // 只处理清单里登记过的进程；多用户/多进程场景下只在首个包实例上安装一次
        val modules = PACKAGE_MODULES[param.packageName]
        if (modules == null || !param.isFirstPackage) {
            return
        }
        FcmselfLog.setXposed(this)
        FcmselfLog.selfPackageName = param.packageName
        installAll(modules, param.classLoader)
    }

    /** 逐个安装模块；单个模块失败仅记录日志，不影响其它模块。 */
    private fun installAll(modules: List<ModuleEntry>, classLoader: ClassLoader) {
        for (entry in modules) {
            try {
                entry.factory(this, classLoader)
            } catch (t: Throwable) {
                FcmselfLog.log("模块安装失败 " + entry.name + ": " + t)
            }
        }
    }
}
