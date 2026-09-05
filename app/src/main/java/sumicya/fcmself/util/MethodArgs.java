package sumicya.fcmself.util;

import java.lang.reflect.Parameter;

/**
 * 按方法签名解析参数下标的纯 Java 工具（不依赖 Android，便于单元测试）。
 *
 * <p>本模块多个 Hook 点的参数下标是按系统版本硬编码的，而 ROM 或新版本系统可能改变签名，
 * 因此挂载前一律先用这里的方法按真实签名校验：不符就跳过该 Hook，绝不在宿主进程里
 * 抛 ClassCastException 或误改参数。
 */
public final class MethodArgs {

    private MethodArgs() {
    }

    /**
     * 校验 (stringIndex, intIndex) 是否与真实签名相符。
     * KeepNotification 用它确认 cancelAllNotificationsInt 的 (pkg, reason) 两个下标。
     */
    public static boolean matches(Class<?>[] paramTypes, int stringIndex, int intIndex) {
        int max = Math.max(stringIndex, intIndex);
        return paramTypes.length > max
                && paramTypes[stringIndex] == String.class
                && paramTypes[intIndex] == int.class;
    }

    /** 校验 (intentIndex, appOpIndex) 是否与真实签名相符。 */
    public static boolean matches(Class<?>[] paramTypes, int intentIndex, Class<?> intentType, int appOpIndex) {
        int max = Math.max(intentIndex, appOpIndex);
        return paramTypes.length > max
                && paramTypes[intentIndex] == intentType
                && paramTypes[appOpIndex] == int.class;
    }

    /** 返回 candidates 中第一个 int 类型参数的下标，都没有（或越界）则返回 -1。 */
    public static int firstIntIndex(Parameter[] parameters, int... candidates) {
        for (int i : candidates) {
            if (i >= 0 && i < parameters.length && parameters[i].getType() == int.class) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 按参数名查找 (intent, appOp) 下标。
     *
     * <p>注意：只有以 {@code -parameters} 编译的字节码才保留真实参数名，Android framework
     * 通常没有保留（{@code Parameter.getName()} 会返回 {@code arg0}），因此这条兜底路径
     * 在多数设备上会失败——调用方必须处理 null 返回值。
     *
     * @return {@code int[]{intentIndex, appOpIndex}}，任一个找不到就返回 null
     */
    public static int[] byName(Parameter[] parameters, Class<?> intentType) {
        int intentIndex = -1;
        int appOpIndex = -1;
        for (int i = 0; i < parameters.length; i++) {
            if ("intent".equals(parameters[i].getName()) && parameters[i].getType() == intentType) {
                intentIndex = i;
            }
            if ("appOp".equals(parameters[i].getName()) && parameters[i].getType() == int.class) {
                appOpIndex = i;
            }
        }
        return (intentIndex < 0 || appOpIndex < 0) ? null : new int[]{intentIndex, appOpIndex};
    }
}
