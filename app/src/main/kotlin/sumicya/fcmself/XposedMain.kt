package sumicya.fcmself

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface

import sumicya.fcmself.core.FcmselfLog
import sumicya.fcmself.mods.AutoStartFix
import sumicya.fcmself.mods.BroadcastFix
import sumicya.fcmself.mods.KeepNotification
import sumicya.fcmself.mods.MiuiLocalNotificationFix
import sumicya.fcmself.mods.OplusProxyFix
import sumicya.fcmself.mods.PowerkeeperFix
import sumicya.fcmself.mods.ReconnectManagerFix

/**
 * fcmself LSPosed 入口。
 *
 * 入口只负责「按进程分发」，具体 Hook 哪些模块由下方的清单声明：
 * - [SYSTEM_SERVER_MODULES]：system_server（进程身份 "android"）内安装的模块；
 * - [PACKAGE_MODULES]：目标应用包名 -> 该进程内安装的模块。
 *
 * 新增一个 Hook 模块只需在对应清单里登记一个构造器引用，无需改动分发逻辑。
 * 每个模块独立 try/catch：单个模块构造失败只影响自身，不会阻断后续模块
 * （模块内部各 Hook 点还有一层 [FcmselfModule.point] 的独立容错）。
 */
class XposedMain : io.github.libxposed.api.XposedModule() {

    private companion object {
        /** system_server 内安装的模块，顺序即安装顺序。 */
        val SYSTEM_SERVER_MODULES: List<(XposedInterface, ClassLoader) -> FcmselfModule> = listOf(
            ::BroadcastFix,
            ::AutoStartFix,
            ::KeepNotification,
            ::MiuiLocalNotificationFix,
            ::OplusProxyFix,
            ::PowerkeeperFix,
        )

        /** 目标进程包名 -> 该进程内安装的模块。 */
        val PACKAGE_MODULES: Map<String, List<(XposedInterface, ClassLoader) -> FcmselfModule>> = mapOf(
            "com.google.android.gms" to listOf(::ReconnectManagerFix),
        )
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        ProcessEnv.bootstrap(this, ProcessEnv.SYSTEM_SERVER, param.classLoader)
        installAll(SYSTEM_SERVER_MODULES, param.classLoader)
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        // 只处理清单里登记过的进程；多用户/多进程场景下只在首个包实例上安装一次
        val modules = PACKAGE_MODULES[param.packageName] ?: return
        if (!param.isFirstPackage) return
        ProcessEnv.bootstrap(this, param.packageName, param.classLoader)
        installAll(modules, param.classLoader)
    }

    /** 逐个安装模块；单个模块失败仅记录日志，不影响其它模块。 */
    private fun installAll(
        modules: List<(XposedInterface, ClassLoader) -> FcmselfModule>,
        classLoader: ClassLoader,
    ) {
        for (factory in modules) {
            try {
                factory(this, classLoader)
            } catch (t: Throwable) {
                FcmselfLog.log("模块安装失败: $t")
            }
        }
    }
}
