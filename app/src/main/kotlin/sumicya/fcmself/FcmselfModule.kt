package sumicya.fcmself

import io.github.libxposed.api.XposedInterface

import sumicya.fcmself.core.FcmselfLog

/**
 * 所有 Fix 模块的基类。
 *
 * 只做三件小事：
 * - 登记 [api] / [classLoader]，并要求子类给出 [name]（日志与就绪回调的归属标识）；
 * - [point]：单个 Hook 点的安装包装——失败只记日志并跳过该点，不影响其它点；
 * - [onEnvReady]：进程环境（context 捕获 + 用户解锁）就绪后执行回调。
 *
 * 旧版基类把进程级状态全放在 companion（静态实例表 + onCanReadConfig 扇出 +
 * 模块内两次握手），本版收敛进 [ProcessEnv.onReady]，基类不再持有任何静态状态。
 *
 * 注意：Hook 一律在构造器（或 [point]）里安装，**不要**等 [onEnvReady]——
 * system_server 模块必须赶在系统广播风暴之前挂上。
 */
abstract class FcmselfModule(
    protected val api: XposedInterface,
    protected val classLoader: ClassLoader,
    val name: String,
) {

    /** 进程环境就绪后执行 [action]；同一模块多次登记只执行一次（见 [ProcessEnv.onReady]）。 */
    protected fun onEnvReady(action: () -> Unit) = ProcessEnv.onReady(name, action)

    /** 安装一个 Hook 点：任何异常都降级为一条日志，绝不阻断同模块的其它点。 */
    protected fun point(what: String, body: () -> Unit) {
        try {
            body()
        } catch (t: Throwable) {
            FcmselfLog.log("hook skip $what: $t")
        }
    }

    /** 记录一个 Hook 点的跳过（类/方法在当前 ROM 上不存在等预期情况）。 */
    protected fun skip(what: String, reason: String) = FcmselfLog.log("hook skip $what: $reason")
}
