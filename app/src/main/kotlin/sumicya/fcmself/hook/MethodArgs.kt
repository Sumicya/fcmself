package sumicya.fcmself.hook

import java.lang.reflect.Parameter

/**
 * 按方法签名解析参数下标的纯 Kotlin 工具（不依赖 Android，便于单元测试）。
 *
 * Hook 点的参数下标随 ROM / 系统版本漂移，因此挂载前一律先用这里的方法按真实
 * 签名校验：不符就跳过该 Hook，绝不在宿主进程里抛 ClassCastException 或误改参数。
 */
object MethodArgs {

    /** 校验 (stringIndex, intIndex) 是否与真实签名相符。 */
    @JvmStatic
    fun matches(paramTypes: Array<Class<*>>, stringIndex: Int, intIndex: Int): Boolean {
        if (stringIndex < 0 || intIndex < 0) return false
        val max = maxOf(stringIndex, intIndex)
        return paramTypes.size > max &&
            paramTypes[stringIndex] === String::class.java &&
            paramTypes[intIndex] === Int::class.javaPrimitiveType
    }

    /** 校验 (intentIndex, appOpIndex) 是否与真实签名相符。 */
    @JvmStatic
    fun matches(paramTypes: Array<Class<*>>, intentIndex: Int, intentType: Class<*>, appOpIndex: Int): Boolean {
        if (intentIndex < 0 || appOpIndex < 0) return false
        val max = maxOf(intentIndex, appOpIndex)
        return paramTypes.size > max &&
            paramTypes[intentIndex] === intentType &&
            paramTypes[appOpIndex] === Int::class.javaPrimitiveType
    }

    /** 返回 candidates 中第一个 int 类型参数的下标，都没有（或越界）则返回 -1。 */
    @JvmStatic
    fun firstIntIndex(parameters: Array<Parameter>, vararg candidates: Int): Int =
        candidates.firstOrNull { i ->
            i in parameters.indices && parameters[i].type === Int::class.javaPrimitiveType
        } ?: -1

    /**
     * 按参数名查找 (intent, appOp) 下标。
     *
     * 注意：只有以 -parameters 编译的字节码才保留真实参数名，Android framework
     * 通常没有保留（[Parameter.getName] 会返回 arg0），因此这条兜底路径
     * 在多数设备上会失败——调用方必须处理 null 返回值。
     *
     * @return IntArray(intentIndex, appOpIndex)，任一个找不到就返回 null
     */
    @JvmStatic
    fun byName(parameters: Array<Parameter>, intentType: Class<*>): IntArray? {
        val intentIndex = parameters.indexOfFirst {
            it.name == "intent" && it.type === intentType
        }
        val appOpIndex = parameters.indexOfFirst {
            it.name == "appOp" && it.type === Int::class.javaPrimitiveType
        }
        return if (intentIndex < 0 || appOpIndex < 0) null else intArrayOf(intentIndex, appOpIndex)
    }
}
