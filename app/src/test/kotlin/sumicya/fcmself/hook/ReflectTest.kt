package sumicya.fcmself.hook

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

import java.lang.reflect.Constructor
import java.lang.reflect.Method

/**
 * [Reflect] 的单元测试。
 *
 * [Reflect] 只依赖 java.lang.reflect，不涉及 Android，可以在 JVM 上直接
 * 验证类 / 方法 / 构造器查找、字段读写与方法调用这几类反射封装。
 */
class ReflectTest {

    @Test
    fun findClass_returnsLoadedClass() {
        assertSame(String::class.java, Reflect.findClass("java.lang.String", javaClass.classLoader))
    }

    @Test
    fun findClass_throwsClassNotFoundWhenMissing() {
        try {
            Reflect.findClass("sumicya.fcmself.DoesNotExist", javaClass.classLoader)
            fail("应当抛出 ClassNotFound")
        } catch (e: Reflect.ClassNotFound) {
            assertEquals("sumicya.fcmself.DoesNotExist", e.message)
        }
    }

    @Test
    fun findClassIfExists_returnsNullWhenMissing() {
        assertNull(Reflect.findClassIfExists("sumicya.fcmself.DoesNotExist", javaClass.classLoader))
    }

    @Test
    fun findMethodExact_findsDeclaredMethod() {
        val method = Reflect.findMethodExact(Sample::class.java, "greet", String::class.java)
        assertEquals("greet", method.name)
        assertEquals(String::class.java, method.parameterTypes[0])
    }

    @Test
    fun findMethodExact_throwsWhenMissing() {
        try {
            Reflect.findMethodExact(Sample::class.java, "nope")
            fail("应当抛出 NoSuchMethodError")
        } catch (expected: NoSuchMethodError) {
            // 预期
        }
    }

    @Test
    fun findMethodByParamCount_returnsNullWhenNoMatch() {
        assertNull(Reflect.findMethodByParamCount(Sample::class.java, "overloaded", 9))
    }

    @Test
    fun findMethodByParamCount_returnsMethodWithMatchingCount() {
        val method = Reflect.findMethodByParamCount(Sample::class.java, "dup", 1)
        assertNotNull(method)
        assertEquals("dup", method!!.name)
        assertEquals(1, method.parameterTypes.size)
    }

    @Test
    fun findMethodMostParams_returnsMethodWithMostParameters() {
        val method = Reflect.findMethodMostParams(Sample::class.java, "overloaded")
        assertNotNull(method)
        assertEquals(2, method!!.parameterTypes.size)
    }

    @Test
    fun findMethodMostParams_byClassLoaderAndName_returnsNullWhenMissing() {
        assertNull(Reflect.findMethodMostParams(javaClass.classLoader, Sample::class.java.name, "nope"))
    }

    @Test
    fun findMethodMostParams_byClassLoaderAndName_returnsNullWhenClassMissing() {
        assertNull(Reflect.findMethodMostParams(javaClass.classLoader, "sumicya.fcmself.Nope", "nope"))
    }

    // ---- 构造器 ----------------------------------------------------------

    @Test
    fun findConstructorMostMatch_returnsConstructorWithMostMatchedPrefix() {
        val constructor: Constructor<*> = Reflect.findConstructorMostMatch(
            Sample::class.java, String::class.java,
            Int::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!,
        )
        assertEquals(3, constructor.parameterTypes.size)
    }

    @Test
    fun findConstructorMostMatch_allowsTailGrownSignature() {
        // ROM 往构造器尾部加参数的场景：给定的前缀同时命中 2 参与加参后的 3 参构造器，取匹配更长的那个
        val constructor = Reflect.findConstructorMostMatch(Sample::class.java, String::class.java, Int::class.javaPrimitiveType!!)
        assertEquals(3, constructor.parameterTypes.size)
    }

    @Test
    fun findConstructorMostMatch_throwsWhenNoPrefixMatches() {
        // 旧实现在零匹配时静默返回最后一个构造器；现在必须显式失败
        try {
            Reflect.findConstructorMostMatch(Sample::class.java, Boolean::class.javaPrimitiveType!!)
            fail("应当抛出 NoSuchMethodError")
        } catch (expected: NoSuchMethodError) {
            // 预期
        }
    }

    @Test
    fun findConstructorMostParams_returnsTheWidestConstructor() {
        val constructor = Reflect.findConstructorMostParams(Sample::class.java)
        assertEquals(3, constructor.parameterTypes.size)
    }

    // ---- 字段 ------------------------------------------------------------

    @Test
    fun getObjectField_readsPrivateField() {
        val sample = Sample("hello", 123L, null)
        assertEquals("hello", Reflect.getObjectField(sample, "name"))
    }

    @Test
    fun getObjectField_throwsWhenMissing() {
        try {
            Reflect.getObjectField(Sample(), "missing")
            fail("应当抛出 NoSuchFieldError")
        } catch (expected: NoSuchFieldError) {
            // 预期
        }
    }

    @Test
    fun getLongField_readsPrivateLong() {
        assertEquals(42L, Reflect.getLongField(Sample("x", 42L, null), "ts"))
    }

    @Test
    fun getStaticObjectField_readsPrivateStatic() {
        assertEquals("tag", Reflect.getStaticObjectField(Sample::class.java, "STATIC_TAG"))
    }

    @Test
    fun getObjectFieldByPath_walksNestedFields() {
        val sample = Sample("x", 0L, Sample.Inner("deep"))
        assertEquals("deep", Reflect.getObjectFieldByPath(sample, "inner.value"))
    }

    @Test
    fun getObjectFieldByPath_throwsWhenPathBroken() {
        try {
            Reflect.getObjectFieldByPath(Sample(), "inner.missing")
            fail("应当抛出 NoSuchFieldError")
        } catch (expected: NoSuchFieldError) {
            // 预期
        }
    }

    // ---- 调用 ------------------------------------------------------------

    @Test
    fun callMethod_invokesWithPrimitiveArgs() {
        assertEquals(5, Reflect.callMethod(Sample(), "add", 2, 3))
    }

    @Test
    fun callMethod_skipsTypeCheckForNullArgs() {
        assertEquals("hi null", Reflect.callMethod(Sample(), "greet", null))
    }

    @Test
    fun callStaticMethod_invokesWithoutDisambiguation() {
        assertEquals("ab", Reflect.callStaticMethod(Sample::class.java, "concat", "a", "b"))
    }

    @Test
    fun callStaticMethod_usesExplicitParameterTypes() {
        assertEquals(
            42,
            Reflect.callStaticMethod(Sample::class.java, "twice", arrayOf(Int::class.javaPrimitiveType), 21),
        )
    }

    // ------------------------------------------------------------------
    // 测试用的样例类：成员尽量私有，确保测试真正走过 setAccessible 路径
    // ------------------------------------------------------------------

    class Sample {
        private val name: String
        private val ts: Long
        private val inner: Inner?

        constructor() : this("", 0L, null)

        constructor(name: String, ts: Long, inner: Inner?) {
            this.name = name
            this.ts = ts
            this.inner = inner
        }

        @Suppress("unused")
        constructor(name: String, ignored: Int) : this(name, 0L, null)

        @Suppress("unused")
        constructor(name: String, ignored: Int, ts: Long) : this(name, ts, null)

        private fun add(a: Int, b: Int): Int = a + b

        private fun greet(who: String?): String = "hi $who"

        @Suppress("unused")
        private fun overloaded() {
        }

        @Suppress("unused")
        private fun overloaded(a: Int) {
        }

        @Suppress("unused")
        private fun overloaded(a: Int, b: Int) {
        }

        @Suppress("unused")
        private fun dup(a: Int) {
        }

        @Suppress("unused")
        private fun dup(s: String) {
        }

        class Inner(private val value: String)

        companion object {
            // @JvmField / @JvmStatic 让这些成员以真正 static 的形式落在 Sample 上，
            // 供 Reflect 通过 getStaticObjectField / callStaticMethod 反射访问
            @JvmField
            val STATIC_TAG = "tag"

            @Suppress("unused")
            @JvmStatic
            fun concat(a: String, b: String): String = a + b

            @Suppress("unused")
            @JvmStatic
            fun twice(x: Int): Int = x * 2
        }
    }
}
