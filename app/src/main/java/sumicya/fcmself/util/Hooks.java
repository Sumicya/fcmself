package sumicya.fcmself.util;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * 安装 hook 的薄封装：直接对接 libxposed 的 {@link XposedInterface#hook}，
 * 只补两件 libxposed 本身不提供的事——按名字查找方法（见 {@link Reflect}），
 * 以及"原方法执行完再跑一段逻辑"的 after 语义。
 *
 * <p>libxposed 的拦截器只有一个入口 {@link Hooker#intercept}，
 * 拦截器自己决定是否调用 {@code chain.proceed()}，因此：
 * <ul>
 *   <li><b>before</b>：在 {@code proceed()} 之前写逻辑。要跳过原方法就直接
 *       {@code return 某个值}（不调用 {@code proceed()}）；</li>
 *   <li><b>after</b>：用 {@link #hookAfter}，它先 {@code proceed()}（异常也会捕获并回传），
 *       跑完 after 逻辑后把原方法的返回值/异常原样还回去。</li>
 * </ul>
 *
 * <p>异常模式沿用 {@code module.prop} 里的 {@code exceptionMode=protective}：
 * 拦截器自身抛出的异常由框架记录并当作"没有 hook"处理，不会拖垮宿主进程。
 */
public final class Hooks {

    /**
     * after 回调：原方法（或链上其它拦截器）已经执行完毕。
     *
     * @param error 原方法抛出的异常，没有异常时为 {@code null}。after 逻辑正常返回后，
     *              这个异常会被原样重新抛出。
     */
    public interface AfterHook {
        void after(Chain chain, Throwable error) throws Throwable;
    }

    private Hooks() {
    }

    /** 拦截一个方法/构造器，逻辑完全由 {@code hooker} 自己决定。 */
    public static void hook(XposedInterface api, Executable member, Hooker hooker) {
        api.hook(member).intercept(hooker);
    }

    /** 拦截一个方法/构造器，原方法执行完毕后再跑 {@code after}。 */
    public static void hookAfter(XposedInterface api, Executable member, AfterHook after) {
        api.hook(member).intercept(chain -> {
            Object result = null;
            Throwable error = null;
            try {
                result = chain.proceed();
            } catch (Throwable t) {
                error = t;
            }
            after.after(chain, error);
            if (error != null) {
                throw error;
            }
            return result;
        });
    }

    /** 按参数类型精确查找方法并拦截。方法不存在时抛 {@link NoSuchMethodError}。 */
    public static Method hookMethod(XposedInterface api, Class<?> clazz, String methodName,
                                    Class<?>[] parameterTypes, Hooker hooker) {
        Method method = Reflect.findMethodExact(clazz, methodName, parameterTypes);
        hook(api, method, hooker);
        return method;
    }

    /** 按参数类型精确查找方法，原方法执行完再跑 {@code after}。 */
    public static Method hookMethodAfter(XposedInterface api, Class<?> clazz, String methodName,
                                         Class<?>[] parameterTypes, AfterHook after) {
        Method method = Reflect.findMethodExact(clazz, methodName, parameterTypes);
        hookAfter(api, method, after);
        return method;
    }
}
