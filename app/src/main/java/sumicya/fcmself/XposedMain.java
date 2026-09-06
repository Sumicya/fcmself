package sumicya.fcmself;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import sumicya.fcmself.util.FcmselfLog;
import sumicya.fcmself.xposed.AutoStartFix;
import sumicya.fcmself.xposed.BroadcastFix;
import sumicya.fcmself.xposed.KeepNotification;
import sumicya.fcmself.xposed.OplusProxyFix;
import sumicya.fcmself.xposed.ReconnectManagerFix;
import sumicya.fcmself.xposed.XposedModule;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * fcmself LSPosed 入口。
 *
 * <p>入口只负责"按进程分发"，具体 Hook 哪些模块由下方的清单声明：
 * <ul>
 *   <li>{@link #SYSTEM_SERVER_MODULES}：system_server（进程身份 "android"）内安装的模块；</li>
 *   <li>{@link #PACKAGE_MODULES}：目标应用包名 -> 该进程内安装的模块。</li>
 * </ul>
 * 新增一个 Hook 模块只需在对应清单里登记一行，无需改动分发逻辑。
 *
 * <p>每个模块独立 try/catch：单个模块安装失败只影响自身，不会阻断后续模块。
 */
public class XposedMain extends io.github.libxposed.api.XposedModule {

    private static final String PKG_GMS = "com.google.android.gms";

    /** 单个 Hook 模块的构造方式。 */
    private interface ModuleFactory {
        XposedModule create(XposedInterface api, ClassLoader classLoader);
    }

    /** Hook 模块登记项（名字仅用于日志）。 */
    private static final class ModuleEntry {
        final String name;
        final ModuleFactory factory;

        ModuleEntry(String name, ModuleFactory factory) {
            this.name = name;
            this.factory = factory;
        }
    }

    /** system_server 内安装的模块，顺序即安装顺序。 */
    private static final List<ModuleEntry> SYSTEM_SERVER_MODULES = Arrays.asList(
            new ModuleEntry("BroadcastFix", BroadcastFix::new),
            new ModuleEntry("AutoStartFix", AutoStartFix::new),
            new ModuleEntry("KeepNotification", KeepNotification::new),
            new ModuleEntry("OplusProxyFix", OplusProxyFix::new));

    /** 目标进程包名 -> 该进程内安装的模块。 */
    private static final Map<String, List<ModuleEntry>> PACKAGE_MODULES = new HashMap<>();

    static {
        PACKAGE_MODULES.put(PKG_GMS, Collections.singletonList(
                new ModuleEntry("ReconnectManagerFix", ReconnectManagerFix::new)));
    }

    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        FcmselfLog.setXposed(this);
        FcmselfLog.setSelfPackageName("android");
        installAll(SYSTEM_SERVER_MODULES, param.getClassLoader());
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        // 只处理清单里登记过的进程；多用户/多进程场景下只在首个包实例上安装一次
        List<ModuleEntry> modules = PACKAGE_MODULES.get(param.getPackageName());
        if (modules == null || !param.isFirstPackage()) {
            return;
        }
        FcmselfLog.setXposed(this);
        FcmselfLog.setSelfPackageName(param.getPackageName());
        installAll(modules, param.getClassLoader());
    }

    /** 逐个安装模块；单个模块失败仅记录日志，不影响其它模块。 */
    private void installAll(List<ModuleEntry> modules, ClassLoader classLoader) {
        for (ModuleEntry entry : modules) {
            try {
                entry.factory.create(this, classLoader);
            } catch (Throwable t) {
                FcmselfLog.log("模块安装失败 " + entry.name + ": " + t);
            }
        }
    }
}
