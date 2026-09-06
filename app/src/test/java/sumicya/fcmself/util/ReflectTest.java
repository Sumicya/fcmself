package sumicya.fcmself.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * {@link Reflect} 的单元测试。
 *
 * <p>{@link Reflect} 只依赖 {@code java.lang.reflect}，不涉及 Android，可以在 JVM 上直接
 * 验证类 / 方法 / 构造器查找、字段读写与方法调用这几类反射封装。
 */
public class ReflectTest {

    @Test
    public void findClass_returnsLoadedClass() {
        assertSame(String.class, Reflect.findClass("java.lang.String", getClass().getClassLoader()));
    }

    @Test
    public void findClass_throwsClassNotFoundWhenMissing() {
        try {
            Reflect.findClass("sumicya.fcmself.DoesNotExist", getClass().getClassLoader());
            fail("应当抛出 ClassNotFound");
        } catch (Reflect.ClassNotFound e) {
            assertEquals("sumicya.fcmself.DoesNotExist", e.getMessage());
        }
    }

    @Test
    public void findClassIfExists_returnsNullWhenMissing() {
        assertNull(Reflect.findClassIfExists("sumicya.fcmself.DoesNotExist", getClass().getClassLoader()));
    }

    @Test
    public void findMethodExact_findsDeclaredMethod() {
        Method method = Reflect.findMethodExact(Sample.class, "greet", String.class);
        assertEquals("greet", method.getName());
        assertEquals(String.class, method.getParameterTypes()[0]);
    }

    @Test
    public void findMethodExact_throwsWhenMissing() {
        try {
            Reflect.findMethodExact(Sample.class, "nope");
            fail("应当抛出 NoSuchMethodError");
        } catch (NoSuchMethodError expected) {
            // 预期
        }
    }

    @Test
    public void findMethodByParamCount_returnsNullWhenNoMatch() {
        assertNull(Reflect.findMethodByParamCount(Sample.class, "overloaded", 9));
    }

    @Test
    public void findMethodByParamCount_returnsMethodWithMatchingCount() {
        Method method = Reflect.findMethodByParamCount(Sample.class, "dup", 1);
        assertNotNull(method);
        assertEquals("dup", method.getName());
        assertEquals(1, method.getParameterTypes().length);
    }

    @Test
    public void findMethodMostParams_returnsMethodWithMostParameters() {
        Method method = Reflect.findMethodMostParams(Sample.class, "overloaded");
        assertNotNull(method);
        assertEquals(2, method.getParameterTypes().length);
    }

    @Test
    public void findMethodMostParams_byClassLoaderAndName_returnsNullWhenMissing() {
        assertNull(Reflect.findMethodMostParams(getClass().getClassLoader(), Sample.class.getName(), "nope"));
    }

    @Test
    public void findMethodMostParams_byClassLoaderAndName_returnsNullWhenClassMissing() {
        assertNull(Reflect.findMethodMostParams(getClass().getClassLoader(), "sumicya.fcmself.Nope", "nope"));
    }

    @Test
    public void findConstructorMostMatch_returnsConstructorWithMostMatchedPrefix() {
        Constructor<?> constructor = Reflect.findConstructorMostMatch(Sample.class, String.class, int.class, long.class);
        assertEquals(3, constructor.getParameterTypes().length);
    }

    @Test
    public void getObjectField_readsPrivateField() {
        Sample sample = new Sample("hello", 123L, null);
        assertEquals("hello", Reflect.getObjectField(sample, "name"));
    }

    @Test
    public void getObjectField_throwsWhenMissing() {
        try {
            Reflect.getObjectField(new Sample(), "missing");
            fail("应当抛出 NoSuchFieldError");
        } catch (NoSuchFieldError expected) {
            // 预期
        }
    }

    @Test
    public void getLongField_readsPrivateLong() {
        assertEquals(42L, Reflect.getLongField(new Sample("x", 42L, null), "ts"));
    }

    @Test
    public void getStaticObjectField_readsPrivateStatic() {
        assertEquals("tag", Reflect.getStaticObjectField(Sample.class, "STATIC_TAG"));
    }

    @Test
    public void getObjectFieldByPath_walksNestedFields() {
        Sample sample = new Sample("x", 0L, new Sample.Inner("deep"));
        assertEquals("deep", Reflect.getObjectFieldByPath(sample, "inner.value"));
    }

    @Test
    public void getObjectFieldByPath_throwsWhenPathBroken() {
        try {
            Reflect.getObjectFieldByPath(new Sample(), "inner.missing");
            fail("应当抛出 NoSuchFieldError");
        } catch (NoSuchFieldError expected) {
            // 预期
        }
    }

    @Test
    public void callMethod_invokesWithPrimitiveArgs() {
        assertEquals(5, Reflect.callMethod(new Sample(), "add", 2, 3));
    }

    @Test
    public void callMethod_skipsTypeCheckForNullArgs() {
        assertEquals("hi null", Reflect.callMethod(new Sample(), "greet", (Object) null));
    }

    @Test
    public void callStaticMethod_invokesWithoutDisambiguation() {
        assertEquals("ab", Reflect.callStaticMethod(Sample.class, "concat", "a", "b"));
    }

    @Test
    public void callStaticMethod_usesExplicitParameterTypes() {
        assertEquals(42, Reflect.callStaticMethod(Sample.class, "twice", new Class<?>[]{int.class}, 21));
    }

    // ------------------------------------------------------------------
    // 测试用的样例类：成员尽量私有，确保测试真正走过 setAccessible 路径
    // ------------------------------------------------------------------

    public static final class Sample {
        private final String name;
        private final long ts;
        private final Inner inner;
        private static final String STATIC_TAG = "tag";

        public Sample() {
            this("", 0L, null);
        }

        public Sample(String name, long ts, Inner inner) {
            this.name = name;
            this.ts = ts;
            this.inner = inner;
        }

        public Sample(String name, int ignored) {
            this(name, 0L, null);
        }

        public Sample(String name, int ignored, long ts) {
            this(name, ts, null);
        }

        private int add(int a, int b) {
            return a + b;
        }

        private String greet(String who) {
            return "hi " + who;
        }

        @SuppressWarnings("unused")
        private void overloaded() {
        }

        @SuppressWarnings("unused")
        private void overloaded(int a) {
        }

        @SuppressWarnings("unused")
        private void overloaded(int a, int b) {
        }

        @SuppressWarnings("unused")
        private void dup(int a) {
        }

        @SuppressWarnings("unused")
        private void dup(String s) {
        }

        @SuppressWarnings("unused")
        private static String concat(String a, String b) {
            return a + b;
        }

        @SuppressWarnings("unused")
        private static int twice(int x) {
            return x * 2;
        }

        public static final class Inner {
            private final String value;

            public Inner(String value) {
                this.value = value;
            }
        }
    }
}
