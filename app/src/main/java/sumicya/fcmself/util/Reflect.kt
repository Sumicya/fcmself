package sumicya.fcmself.util

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 反射封装。所有 Hook 目标都是各 ROM 的私有类，方法签名无法在编译期确定，
 * 只能按名字 + 参数个数在运行期查找，所以查找方式分三档，按调用点需要选用：
 *
 * - [findMethodExact]：参数类型完全匹配（getDeclaredMethod，不查父类）；
 * - [findMethodByParamCount]：只按名字 + 参数个数（同名重载取最后一个）；
 * - [findMethodMostParams]：只按名字，取参数最多的那个（应对 ROM 逐版本加参数）。
 *
 * 查不到时：findXxxExact 抛 [NoSuchMethodError]，
 * [findMethodByParamCount] / [findMethodMostParams] 返回 null，
 * 由调用点决定是"记录日志后跳过"还是"整个模块安装失败"。
 */
object Reflect {

    /** 目标类不存在。继承自 [Error]：调用点可以只 catch 它而不吞掉别的异常。 */
    class ClassNotFound(className: String, cause: Throwable) : Error(className, cause)

    // ------------------------------------------------------------------
    // 类
    // ------------------------------------------------------------------

    /** 加载类；不存在时抛 [ClassNotFound]。第二个参数 false = 不触发类初始化。 */
    @JvmStatic
    fun findClass(className: String, classLoader: ClassLoader): Class<*> =
        try {
            Class.forName(className, false, classLoader)
        } catch (e: ClassNotFoundException) {
            throw ClassNotFound(className, e)
        }

    /** 加载类；不存在时返回 null。 */
    @JvmStatic
    fun findClassIfExists(className: String, classLoader: ClassLoader): Class<*>? =
        try {
            Class.forName(className, false, classLoader)
        } catch (e: ClassNotFoundException) {
            null
        }

    // ------------------------------------------------------------------
    // 方法 / 构造器查找
    // ------------------------------------------------------------------

    /** 按参数类型精确查找本类声明的方法。 */
    @JvmStatic
    fun findMethodExact(clazz: Class<*>, methodName: String, vararg parameterTypes: Class<*>): Method =
        try {
            val method = clazz.getDeclaredMethod(methodName, *parameterTypes)
            method.isAccessible = true
            method
        } catch (e: NoSuchMethodException) {
            throw NoSuchMethodError(clazz.name + "#" + methodName)
        }

    /** 按名字 + 参数个数查找；没有则返回 null。同名同参数个数的重载取最后一个。 */
    @JvmStatic
    fun findMethodByParamCount(clazz: Class<*>, methodName: String, parameterCount: Int): Method? {
        var found: Method? = null
        for (method in clazz.declaredMethods) {
            if (method.name == methodName && method.parameterTypes.size == parameterCount) {
                found = method
            }
        }
        return found
    }

    /** 只按名字查找参数最多的方法；类不存在或方法不存在时返回 null。 */
    @JvmStatic
    fun findMethodMostParams(classLoader: ClassLoader, className: String, methodName: String): Method? {
        val clazz = findClassIfExists(className, classLoader) ?: return null
        return findMethodMostParams(clazz, methodName)
    }

    /** 只按名字查找参数最多的方法；方法不存在时返回 null。 */
    @JvmStatic
    fun findMethodMostParams(clazz: Class<*>, methodName: String): Method? {
        var best: Method? = null
        for (method in clazz.declaredMethods) {
            if (method.name == methodName &&
                (best == null || method.parameterTypes.size > best.parameterTypes.size)
            ) {
                best = method
            }
        }
        best?.isAccessible = true
        return best
    }

    /**
     * 找出与给定参数类型"前缀匹配最多"的构造器（用于 ROM 会往构造器尾部加参数的场景）。
     * 一个都没有则抛 [NoSuchMethodError]。
     */
    @JvmStatic
    fun findConstructorMostMatch(clazz: Class<*>, vararg parameterTypes: Class<*>): Constructor<*> {
        var best: Constructor<*>? = null
        var bestMatch = 0
        for (constructor in clazz.declaredConstructors) {
            val declared = constructor.parameterTypes
            var matched = 0
            for (i in 0 until minOf(declared.size, parameterTypes.size)) {
                if (parameterTypes[i] === declared[i]) matched++
            }
            if (matched >= bestMatch) {
                bestMatch = matched
                best = constructor
            }
        }
        if (best == null) {
            throw NoSuchMethodError(clazz.name + "#<init>")
        }
        best.isAccessible = true
        return best
    }

    // ------------------------------------------------------------------
    // 字段
    // ------------------------------------------------------------------

    /** 读实例字段（沿继承链向上找）；找不到抛 [NoSuchFieldError]。 */
    @JvmStatic
    fun getObjectField(obj: Any, fieldName: String): Any? =
        try {
            findField(obj.javaClass, fieldName).get(obj)
        } catch (e: IllegalAccessException) {
            throw RuntimeException(e)
        }

    /** 读 long 型实例字段。 */
    @JvmStatic
    fun getLongField(obj: Any, fieldName: String): Long =
        try {
            findField(obj.javaClass, fieldName).getLong(obj)
        } catch (e: IllegalAccessException) {
            throw RuntimeException(e)
        }

    /** 读静态字段。 */
    @JvmStatic
    fun getStaticObjectField(clazz: Class<*>, fieldName: String): Any? =
        try {
            findField(clazz, fieldName).get(null)
        } catch (e: IllegalAccessException) {
            throw RuntimeException(e)
        }

    /** 写实例字段（沿继承链向上找）；找不到抛 [NoSuchFieldError]。 */
    @JvmStatic
    fun setObjectField(obj: Any, fieldName: String, value: Any?) {
        try {
            findField(obj.javaClass, fieldName).set(obj, value)
        } catch (e: IllegalAccessException) {
            throw RuntimeException(e)
        }
    }

    /** 写静态字段；找不到抛 [NoSuchFieldError]。 */
    @JvmStatic
    fun setStaticObjectField(clazz: Class<*>, fieldName: String, value: Any?) {
        try {
            findField(clazz, fieldName).set(null, value)
        } catch (e: IllegalAccessException) {
            throw RuntimeException(e)
        }
    }

    /** 按 a.b.c 形式逐层读字段；任何一层失败都抛 [NoSuchFieldError]。 */
    @JvmStatic
    fun getObjectFieldByPath(obj: Any, pathFieldName: String): Any? {
        var current: Any? = obj
        try {
            for (fieldName in pathFieldName.split(".")) {
                current = getObjectField(current!!, fieldName)
            }
        } catch (e: Throwable) {
            throw NoSuchFieldError(obj.javaClass.name + "#" + pathFieldName)
        }
        return current
    }

    // ------------------------------------------------------------------
    // 调用
    // ------------------------------------------------------------------

    /**
     * 按名字 + 实参个数/类型挑一个最匹配的方法并调用。
     * 实参为 null 的位置不参与类型比较。
     */
    @JvmStatic
    fun callMethod(obj: Any, methodName: String, vararg args: Any?): Any? {
        val method = findBestMethod(obj.javaClass, methodName, args)
        return try {
            method.invoke(obj, *args)
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    /**
     * 调用静态方法。第一个参数若是 Class<*>[]，则视为显式声明的参数类型
     * （用于消歧），其余参数为实参。
     */
    @JvmStatic
    fun callStaticMethod(clazz: Class<*>, methodName: String, vararg args: Any?): Any? {
        var declaredParamTypes: Array<Class<*>>? = null
        var invokeArgs: Array<out Any?> = args
        if (args.isNotEmpty() && args[0] is Array<*> && (args[0] as Array<*>).isArrayOf<Class<*>>()) {
            @Suppress("UNCHECKED_CAST")
            declaredParamTypes = args[0] as Array<Class<*>>
            invokeArgs = args.copyOfRange(1, args.size)
        }
        val method = if (declaredParamTypes != null) {
            findMethodExact(clazz, methodName, *declaredParamTypes)
        } else {
            findBestMethod(clazz, methodName, invokeArgs)
        }
        return try {
            method.invoke(null, *invokeArgs)
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private fun findField(clazz: Class<*>, fieldName: String): Field {
        var current: Class<*>? = clazz
        while (current != null) {
            try {
                val field = current.getDeclaredField(fieldName)
                field.isAccessible = true
                return field
            } catch (e: NoSuchFieldException) {
                // 继续往父类找
            }
            current = current.superclass
        }
        throw NoSuchFieldError(clazz.name + "#" + fieldName)
    }

    private fun findBestMethod(clazz: Class<*>, methodName: String, args: Array<out Any?>): Method {
        var best: Method? = null
        var bestScore = -1
        for (method in clazz.declaredMethods) {
            if (method.name != methodName) continue
            val parameterTypes = method.parameterTypes
            if (parameterTypes.size != args.size) continue
            var score = 0
            var compatible = true
            for (i in parameterTypes.indices) {
                val arg = args[i] ?: continue
                val boxed = boxPrimitive(parameterTypes[i])
                if (!boxed.isAssignableFrom(arg.javaClass)) {
                    compatible = false
                    break
                }
                if (boxed === arg.javaClass) score++
            }
            if (compatible && score >= bestScore) {
                best = method
                bestScore = score
            }
        }
        if (best == null) {
            throw NoSuchMethodError(clazz.name + "#" + methodName)
        }
        best.isAccessible = true
        return best
    }

    private fun boxPrimitive(type: Class<*>): Class<*> = when (type) {
        Int::class.javaPrimitiveType -> Integer::class.java
        Long::class.javaPrimitiveType -> Long::class.java
        Boolean::class.javaPrimitiveType -> Boolean::class.java
        Float::class.javaPrimitiveType -> Float::class.java
        Double::class.javaPrimitiveType -> Double::class.java
        Byte::class.javaPrimitiveType -> Byte::class.java
        Short::class.javaPrimitiveType -> Short::class.java
        Char::class.javaPrimitiveType -> Character::class.java
        else -> type
    }
}
