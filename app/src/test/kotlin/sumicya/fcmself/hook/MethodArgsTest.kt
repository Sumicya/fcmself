package sumicya.fcmself.hook

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

import java.lang.reflect.Method
import java.lang.reflect.Parameter

// javaPrimitiveType 对原始类型返回非空 Class，但签名上可空；这里统一收窄为非空的 Class<*>
private val INT_TYPE: Class<*> = Int::class.javaPrimitiveType!!
private val LONG_TYPE: Class<*> = Long::class.javaPrimitiveType!!
private val BOOLEAN_TYPE: Class<*> = Boolean::class.javaPrimitiveType!!
private val STRING_TYPE: Class<*> = String::class.java

/**
 * [MethodArgs] 的单元测试。
 *
 * 参数下标解析是整个模块里最容易随 ROM / 系统版本出错的部分（Android 16 上就重新核实过
 * 两处签名），而它恰好不依赖 Android，可以在 JVM 上直接验证。真机日志已确认 Android 16 的
 * cancelAllNotificationsInt 为 pkg@2 / reason@7、BroadcastController.broadcastIntentLocked
 * 为 intent@3 / appOp@13，下面的用例就以这两个真实签名为基准。
 */
class MethodArgsTest {

    /** 模拟 Android 15/16 的 cancelAllNotificationsInt：pkg@2(String) / reason@7(int)。 */
    private fun notificationSignature(): Array<Class<*>> = arrayOf(
        INT_TYPE, INT_TYPE, STRING_TYPE, INT_TYPE, BOOLEAN_TYPE,
        INT_TYPE, INT_TYPE, INT_TYPE, INT_TYPE,
    )

    @Test
    fun matches_acceptsTheAndroid16NotificationSignature() {
        assertTrue(MethodArgs.matches(notificationSignature(), 2, 7))
    }

    @Test
    fun matches_rejectsWhenReasonSlotIsNotInt() {
        val types = notificationSignature()
        types[7] = STRING_TYPE
        assertFalse(MethodArgs.matches(types, 2, 7))
    }

    @Test
    fun matches_rejectsWhenPkgSlotIsNotString() {
        val types = notificationSignature()
        types[2] = CharSequence::class.java
        assertFalse(MethodArgs.matches(types, 2, 7))
    }

    @Test
    fun matches_rejectsWhenIndexIsOutOfRange() {
        val types = arrayOf<Class<*>>(INT_TYPE, STRING_TYPE)
        assertFalse(MethodArgs.matches(types, 2, 1))
        assertFalse(MethodArgs.matches(types, 1, 5))
    }

    @Test
    fun matches_returnsFalseForNegativeIndicesInsteadOfThrowing() {
        val types = notificationSignature()
        assertFalse(MethodArgs.matches(types, -1, 7))
        assertFalse(MethodArgs.matches(types, 2, -1))
        assertFalse(MethodArgs.matches(types, -1, FakeIntent::class.java, 13))
        assertFalse(MethodArgs.matches(types, 3, FakeIntent::class.java, -1))
    }

    @Test
    fun matches_acceptsIntentAndAppOpPair() {
        val types = Array<Class<*>>(20) { Any::class.java }
        types[3] = FakeIntent::class.java
        types[13] = INT_TYPE
        assertTrue(MethodArgs.matches(types, 3, FakeIntent::class.java, 13))
    }

    @Test
    fun matches_rejectsWhenAppOpSlotIsNotInt() {
        // 这正是原先只检查「第 13 个参数是 int」时可能漏掉的情况：签名一变就命中错误的参数
        val types = Array<Class<*>>(20) { Any::class.java }
        types[3] = FakeIntent::class.java
        types[13] = LONG_TYPE
        assertFalse(MethodArgs.matches(types, 3, FakeIntent::class.java, 13))
    }

    @Test
    fun firstIntIndex_returnsFirstCandidateThatIsInt() {
        val params = method("sample").parameters
        assertEquals(2, MethodArgs.firstIntIndex(params, 1, 2, 3))
        assertEquals(3, MethodArgs.firstIntIndex(params, 3, 9))
    }

    @Test
    fun firstIntIndex_returnsMinusOneWhenNothingMatches() {
        val params = method("sample").parameters
        assertEquals(-1, MethodArgs.firstIntIndex(params, 0))      // 第 0 个是 String
        assertEquals(-1, MethodArgs.firstIntIndex(params, 99))     // 越界
        assertEquals(-1, MethodArgs.firstIntIndex(arrayOf<Parameter>(), 0))
    }

    @Test
    fun byName_findsIntentAndAppOpWhenParameterNamesAreRetained() {
        // 本模块以 -parameters / javaParameters 编译（见 build 脚本），所以这里能拿到真实参数名。
        // 注意 Android framework 通常没有保留参数名，真机上这条兜底路径多数会返回 null。
        val params = method("broadcastIntentLocked").parameters
        assertArrayEquals(intArrayOf(1, 4), MethodArgs.byName(params, String::class.java))
    }

    @Test
    fun byName_returnsNullWhenEitherNameIsMissing() {
        assertNull(MethodArgs.byName(method("sample").parameters, String::class.java))
    }

    /** 代替 android.content.Intent，让单元测试不依赖 Android。 */
    private class FakeIntent

    @Suppress("unused")
    private fun sample(a: String, b: String, c: Int, d: Int) {
    }

    @Suppress("unused")
    private fun broadcastIntentLocked(caller: Any, intent: String, x: Any, y: Any, appOp: Int) {
    }

    private fun method(name: String): Method =
        MethodArgsTest::class.java.declaredMethods.firstOrNull { it.name == name }
            ?: throw IllegalStateException("测试用的样例方法不存在: $name")
}
