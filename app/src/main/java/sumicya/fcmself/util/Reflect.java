package sumicya.fcmself.util;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * 反射工具：类 / 方法 / 构造器查找、字段读写、方法调用。
 *
 * <p>本模块 hook 的都是各 ROM 的私有类，方法签名无法在编译期确定，只能按名字 + 参数个数
 * 在运行期查找，所以查找方式分三档，按调用点需要选用：
 * <ul>
 *   <li>{@link #findMethodExact}：参数类型完全匹配（{@code getDeclaredMethod}，不查父类）；</li>
 *   <li>{@link #findMethodByParamCount}：只按名字 + 参数个数（同名重载取最后一个）；</li>
 *   <li>{@link #findMethodMostParams}：只按名字，取参数最多的那个（应对 ROM 逐版本加参数）。</li>
 * </ul>
 *
 * <p>查不到时：{@code findXxxExact} 抛 {@link NoSuchMethodError}，
 * {@code findMethodByParamCount} / {@code findMethodMostParams} 返回 {@code null}，
 * 由调用点决定是"记录日志后跳过"还是"整个模块安装失败"。
 */
public final class Reflect {

    /** 目标类不存在。继承自 {@link Error}：调用点可以只 catch 它而不吞掉别的异常。 */
    public static class ClassNotFound extends Error {
        public ClassNotFound(String className, Throwable cause) {
            super(className, cause);
        }
    }

    private Reflect() {
    }

    // ------------------------------------------------------------------
    // 类
    // ------------------------------------------------------------------

    /** 加载类；不存在时抛 {@link ClassNotFound}。第二个参数 false = 不触发类初始化。 */
    public static Class<?> findClass(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException e) {
            throw new ClassNotFound(className, e);
        }
    }

    /** 加载类；不存在时返回 {@code null}。 */
    public static Class<?> findClassIfExists(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 方法 / 构造器查找
    // ------------------------------------------------------------------

    /** 按参数类型精确查找本类声明的方法。 */
    public static Method findMethodExact(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        try {
            Method method = clazz.getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            throw new NoSuchMethodError(clazz.getName() + "#" + methodName);
        }
    }

    /** 按参数类型精确查找构造器。 */
    public static Constructor<?> findConstructorExact(Class<?> clazz, Class<?>... parameterTypes) {
        try {
            Constructor<?> constructor = clazz.getDeclaredConstructor(parameterTypes);
            constructor.setAccessible(true);
            return constructor;
        } catch (NoSuchMethodException e) {
            throw new NoSuchMethodError(clazz.getName() + "#<init>");
        }
    }

    /** 按名字 + 参数个数查找；没有则返回 {@code null}。同名同参数个数的重载取最后一个。 */
    public static Method findMethodByParamCount(Class<?> clazz, String methodName, int parameterCount) {
        Method found = null;
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getName().equals(methodName)
                    && method.getParameterTypes().length == parameterCount) {
                found = method;
            }
        }
        return found;
    }

    /** 只按名字查找参数最多的方法；类不存在或方法不存在时返回 {@code null}。 */
    public static Method findMethodMostParams(ClassLoader classLoader, String className, String methodName) {
        Class<?> clazz = findClassIfExists(className, classLoader);
        return clazz == null ? null : findMethodMostParams(clazz, methodName);
    }

    /** 只按名字查找参数最多的方法；方法不存在时返回 {@code null}。 */
    public static Method findMethodMostParams(Class<?> clazz, String methodName) {
        Method best = null;
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getName().equals(methodName)
                    && (best == null || method.getParameterTypes().length > best.getParameterTypes().length)) {
                best = method;
            }
        }
        if (best != null) {
            best.setAccessible(true);
        }
        return best;
    }

    /**
     * 找出与给定参数类型"前缀匹配最多"的构造器（用于 ROM 会往构造器尾部加参数的场景）。
     * 一个都没有则抛 {@link NoSuchMethodError}。
     */
    public static Constructor<?> findConstructorMostMatch(Class<?> clazz, Class<?>... parameterTypes) {
        Constructor<?> best = null;
        int bestMatch = 0;
        for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
            Class<?>[] declared = constructor.getParameterTypes();
            int matched = 0;
            for (int i = 0; i < Math.min(declared.length, parameterTypes.length); i++) {
                if (parameterTypes[i] == declared[i]) {
                    matched++;
                }
            }
            if (matched >= bestMatch) {
                bestMatch = matched;
                best = constructor;
            }
        }
        if (best == null) {
            throw new NoSuchMethodError(clazz.getName() + "#<init>");
        }
        best.setAccessible(true);
        return best;
    }

    // ------------------------------------------------------------------
    // 字段
    // ------------------------------------------------------------------

    /** 读实例字段（沿继承链向上找）；找不到抛 {@link NoSuchFieldError}。 */
    public static Object getObjectField(Object obj, String fieldName) {
        try {
            return findField(obj.getClass(), fieldName).get(obj);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /** 读 long 型实例字段。 */
    public static long getLongField(Object obj, String fieldName) {
        try {
            return findField(obj.getClass(), fieldName).getLong(obj);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /** 读静态字段。 */
    public static Object getStaticObjectField(Class<?> clazz, String fieldName) {
        try {
            return findField(clazz, fieldName).get(null);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /** 按 {@code a.b.c} 形式逐层读字段；任何一层失败都抛 {@link NoSuchFieldError}。 */
    public static Object getObjectFieldByPath(Object obj, String pathFieldName) {
        Object current = obj;
        try {
            for (String fieldName : pathFieldName.split("\\.")) {
                current = getObjectField(current, fieldName);
            }
        } catch (Throwable e) {
            throw new NoSuchFieldError(obj.getClass().getName() + "#" + pathFieldName);
        }
        return current;
    }

    // ------------------------------------------------------------------
    // 调用
    // ------------------------------------------------------------------

    /**
     * 按名字 + 实参个数/类型挑一个最匹配的方法并调用。
     * 实参为 {@code null} 的位置不参与类型比较。
     */
    public static Object callMethod(Object obj, String methodName, Object... args) {
        Method method = findBestMethod(obj.getClass(), methodName, args);
        try {
            return method.invoke(obj, args);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 调用静态方法。第一个参数若是 {@code Class<?>[]}，则视为显式声明的参数类型
     * （用于消歧），其余参数为实参。
     */
    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        Class<?>[] declaredParamTypes = null;
        Object[] invokeArgs = args;
        if (args.length > 0 && args[0] instanceof Class<?>[]) {
            declaredParamTypes = (Class<?>[]) args[0];
            invokeArgs = Arrays.copyOfRange(args, 1, args.length);
        }
        Method method = (declaredParamTypes != null)
                ? findMethodExact(clazz, methodName, declaredParamTypes)
                : findBestMethod(clazz, methodName, invokeArgs);
        try {
            return method.invoke(null, invokeArgs);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private static Field findField(Class<?> clazz, String fieldName) {
        for (Class<?> current = clazz; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // 继续往父类找
            }
        }
        throw new NoSuchFieldError(clazz.getName() + "#" + fieldName);
    }

    private static Method findBestMethod(Class<?> clazz, String methodName, Object[] args) {
        Method best = null;
        int bestScore = -1;
        for (Method method : clazz.getDeclaredMethods()) {
            if (!method.getName().equals(methodName)) {
                continue;
            }
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes.length != args.length) {
                continue;
            }
            int score = 0;
            boolean compatible = true;
            for (int i = 0; i < parameterTypes.length; i++) {
                Object arg = args[i];
                if (arg == null) {
                    continue;
                }
                Class<?> boxed = boxPrimitive(parameterTypes[i]);
                if (!boxed.isAssignableFrom(arg.getClass())) {
                    compatible = false;
                    break;
                }
                if (boxed == arg.getClass()) {
                    score++;
                }
            }
            if (compatible && score >= bestScore) {
                best = method;
                bestScore = score;
            }
        }
        if (best == null) {
            throw new NoSuchMethodError(clazz.getName() + "#" + methodName);
        }
        best.setAccessible(true);
        return best;
    }

    private static Class<?> boxPrimitive(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == boolean.class) return Boolean.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == char.class) return Character.class;
        return type;
    }
}
