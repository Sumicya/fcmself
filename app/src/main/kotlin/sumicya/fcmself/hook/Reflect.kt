package sumicya.fcmself.hook

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 反射封装。所有 Hook 目标都是各 ROM 的私有类，方法签名无法在编译期确定，
 * 只能按名字在运行期查找。查找方式分三档，按调用点需要选用：
 *
 * - [findMethodExact]：参数类型完全匹配（getDeclaredMethod，不查父类）；
 * - [findMethodByParamCount]：只按名字 + 参数个数（同名重载取最后一个）；
 * - [findMethodMostParams]：只按名字，取参数最多的那个（应对 ROM 逐版本加参数）。
 *
 * 查不到时：findMethodExact 抛 [NoSuchMethodError]，
 * findMethodByParamCount / findMethodMostParams 返回 null，由调用点决定跳过或失败。
 */
object Reflect {

    /** 目标类不存在。继承自 Error：调用点可以只 catch 它而不吞掉别的异常。 */
    class ClassNotFound(className: String, cause: Throwable) : Error(className, cause)

    // ------------------------------------------------------------------
    // 类
    // ------------------------------------------------------------------

    /** 加载类；不存在时抛 [ClassNotFound]。第二个参数 false = 不触发类初始化。 */
    fun findClass(className: String, classLoader: ClassLoader): Class<*> =
        try {
            Class.forName(className, false, classLoader)
        } catch (e: ClassNotFoundException) {
            throw ClassNotFound(className, e)
        }

    /** 加载类；不存在时返回 null。 */
    fun findClassIfExists(className: String, classLoader: ClassLoader): Class<*>? =
        try {
            Class.forName(className, false, classLoader)
        } catch (_: ClassNotFoundException) {
            null
        }

    // ------------------------------------------------------------------
    // 方法 / 构造器查找
    // ------------------------------------------------------------------

    /** 按参数类型精确查找本类声明的方法。 */
    fun findMethodExact(clazz: Class<*>, methodName: String, vararg parameterTypes: Class<*>): Method =
        try {
            clazz.getDeclaredMethod(methodName, *parameterTypes).apply { isAccessible = true }
        } catch (_: NoSuchMethodException) {
            val params = parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name }
            throw NoSuchMethodError("${clazz.name}#$methodName$params")
        }

    /** 按名字 + 参数个数查找；没有则返回 null。同名同参数个数的重载取最后一个。 */
    fun findMethodByParamCount(clazz: Class<*>, methodName: String, parameterCount: Int): Method? =
        clazz.declaredMethods
            .filter { it.name == methodName && it.parameterTypes.size == parameterCount }
            .lastOrNull()

    /** 只按名字查找参数最多的方法；类不存在或方法不存在时返回 null。 */
    fun findMethodMostParams(classLoader: ClassLoader, className: String, methodName: String): Method? =
        findClassIfExists(className, classLoader)?.let { findMethodMostParams(it, methodName) }

    /** 只按名字查找参数最多的方法；方法不存在时返回 null。 */
    fun findMethodMostParams(clazz: Class<*>, methodName: String): Method? =
        clazz.declaredMethods
            .filter { it.name == methodName }
            .maxByOrNull { it.parameterTypes.size }
            ?.apply { isAccessible = true }

    /**
     * 找出与给定参数类型「前缀匹配」的构造器（用于 ROM 往构造器尾部加参数的场景）。
     * 至少要匹配上第一个给定类型，否则一律视为签名不符抛 [NoSuchMethodError]——
     * 旧实现用 >= 比较，零匹配时静默返回最后一个构造器，存在 hook 错对象的风险。
     */
    fun findConstructorMostMatch(clazz: Class<*>, vararg parameterTypes: Class<*>): Constructor<*> {
        check(parameterTypes.isNotEmpty()) { "至少给出一个参数类型；表达「参数最多」请用 findConstructorMostParams" }
        var best: Constructor<*>? = null
        var bestMatch = 0
        for (constructor in clazz.declaredConstructors) {
            val declared = constructor.parameterTypes
            var matched = 0
            for (i in declared.indices) {
                if (i >= parameterTypes.size || parameterTypes[i] !== declared[i]) break
                matched++
            }
            if (matched > bestMatch) {
                bestMatch = matched
                best = constructor
            }
        }
        return best?.apply { isAccessible = true }
            ?: throw NoSuchMethodError("${clazz.name}#<init>${parameterTypes.joinToString(",") { it.name }}（无前缀匹配的构造器）")
    }

    /** 取参数最多的构造器（ROM 常在尾部加参数）；类没有构造器时抛 [NoSuchMethodError]。 */
    fun findConstructorMostParams(clazz: Class<*>): Constructor<*> =
        clazz.declaredConstructors.maxByOrNull { it.parameterTypes.size }
            ?.apply { isAccessible = true }
            ?: throw NoSuchMethodError("${clazz.name}#<init>（无构造器）")

    // ------------------------------------------------------------------
    // 字段
    // ------------------------------------------------------------------

    /** 读实例字段（沿继承链向上找）；找不到抛 [NoSuchFieldError]。 */
    fun getObjectField(obj: Any, fieldName: String): Any? = readField(obj.javaClass, fieldName) { it.get(obj) }

    /** 读 long 型实例字段。 */
    fun getLongField(obj: Any, fieldName: String): Long = readField(obj.javaClass, fieldName) { it.getLong(obj) }

    /** 读静态字段。 */
    fun getStaticObjectField(clazz: Class<*>, fieldName: String): Any? = readField(clazz, fieldName) { it.get(null) }

    /** 按 a.b.c 形式逐层读字段；任何一层失败都抛带完整路径的 [NoSuchFieldError]。 */
    fun getObjectFieldByPath(obj: Any, pathFieldName: String): Any? {
        var current: Any? = obj
        try {
            for (fieldName in pathFieldName.split(".")) {
                current = getObjectField(current!!, fieldName)
            }
        } catch (_: Throwable) {
            throw NoSuchFieldError("${obj.javaClass.name}#$pathFieldName")
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
    fun callStaticMethod(clazz: Class<*>, methodName: String, vararg args: Any?): Any? {
        val first = args.firstOrNull()
        val declaredTypes = if (first is Array<*> && first.isArrayOf<Class<*>>()) {
            @Suppress("UNCHECKED_CAST")
            first as Array<Class<*>>
        } else {
            null
        }
        val invokeArgs = if (declaredTypes != null) args.copyOfRange(1, args.size) else args
        val method = if (declaredTypes != null) {
            findMethodExact(clazz, methodName, *declaredTypes)
        } else {
            findBestMethod(clazz, methodName, args)
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

    private inline fun <T> readField(clazz: Class<*>, fieldName: String, read: (Field) -> T): T =
        try {
            read(findField(clazz, fieldName))
        } catch (e: IllegalAccessException) {
            throw RuntimeException(e)
        }

    private fun findField(clazz: Class<*>, fieldName: String): Field {
        var current: Class<*>? = clazz
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                // 继续往父类找
            }
            current = current.superclass
        }
        throw NoSuchFieldError("${clazz.name}#$fieldName")
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
        return best?.apply { isAccessible = true }
            ?: throw NoSuchMethodError("${clazz.name}#$methodName（无与 ${args.size} 个实参匹配的重载）")
    }

    private fun boxPrimitive(type: Class<*>): Class<*> = when (type) {
        Int::class.javaPrimitiveType -> Integer::class.java
        Long::class.javaPrimitiveType -> java.lang.Long::class.java
        Boolean::class.javaPrimitiveType -> java.lang.Boolean::class.java
        Float::class.javaPrimitiveType -> java.lang.Float::class.java
        Double::class.javaPrimitiveType -> java.lang.Double::class.java
        Byte::class.javaPrimitiveType -> java.lang.Byte::class.java
        Short::class.javaPrimitiveType -> java.lang.Short::class.java
        Char::class.javaPrimitiveType -> Character::class.java
        else -> type
    }
}
