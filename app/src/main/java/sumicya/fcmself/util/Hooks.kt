package sumicya.fcmself.util

import java.lang.reflect.Executable
import java.lang.reflect.Method

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker

/**
 * 安装 hook 的薄封装：直接对接 libxposed 的 [XposedInterface.hook]，
 * 只补两件 libxposed 本身不提供的事——按名字查找方法（见 [Reflect]），
 * 以及"原方法执行完再跑一段逻辑"的 after 语义。
 *
 * libxposed 的拦截器只有一个入口 [Hooker.intercept]，
 * 拦截器自己决定是否调用 chain.proceed()，因此：
 * - before：在 proceed() 之前写逻辑。要跳过原方法就直接 return 某个值（不调用 proceed()）；
 * - after：用 [hookAfter]，它先 proceed()（异常也会捕获并回传），
 *   跑完 after 逻辑后把原方法的返回值/异常原样还回去。
 *
 * 异常模式沿用 module.prop 里的 exceptionMode=protective：
 * 拦截器自身抛出的异常由框架记录并当作"没有 hook"处理，不会拖垮宿主进程。
 */
object Hooks {

    /**
     * after 回调：原方法（或链上其它拦截器）已经执行完毕。
     *
     * @param error 原方法抛出的异常，没有异常时为 null。after 逻辑正常返回后，
     *              这个异常会被原样重新抛出。
     */
    fun interface AfterHook {
        fun after(chain: Chain, error: Throwable?)
    }

    /** 拦截一个方法/构造器，逻辑完全由 [hooker] 自己决定。 */
    @JvmStatic
    fun hook(api: XposedInterface, member: Executable, hooker: Hooker) {
        api.hook(member).intercept(hooker)
    }

    /** 拦截一个方法/构造器，原方法执行完毕后再跑 [after]。 */
    @JvmStatic
    fun hookAfter(api: XposedInterface, member: Executable, after: AfterHook) {
        api.hook(member).intercept { chain ->
            var result: Any? = null
            var error: Throwable? = null
            try {
                result = chain.proceed()
            } catch (t: Throwable) {
                error = t
            }
            after.after(chain, error)
            if (error != null) throw error
            result
        }
    }

    /** 按参数类型精确查找方法并拦截。方法不存在时抛 [NoSuchMethodError]。 */
    @JvmStatic
    fun hookMethod(api: XposedInterface, clazz: Class<*>, methodName: String,
                   parameterTypes: Array<Class<*>>, hooker: Hooker): Method {
        val method = Reflect.findMethodExact(clazz, methodName, *parameterTypes)
        hook(api, method, hooker)
        return method
    }

    /** 按参数类型精确查找方法，原方法执行完再跑 [after]。 */
    @JvmStatic
    fun hookMethodAfter(api: XposedInterface, clazz: Class<*>, methodName: String,
                        parameterTypes: Array<Class<*>>, after: AfterHook): Method {
        val method = Reflect.findMethodExact(clazz, methodName, *parameterTypes)
        hookAfter(api, method, after)
        return method
    }
}
