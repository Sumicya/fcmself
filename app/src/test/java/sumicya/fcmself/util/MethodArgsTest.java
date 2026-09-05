package sumicya.fcmself.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

/**
 * {@link MethodArgs} 的单元测试。
 *
 * <p>参数下标解析是整个模块里最容易随 ROM / 系统版本出错的部分（Android 16 上就重新核实过
 * 两处签名），而它恰好不依赖 Android，可以在 JVM 上直接验证。真机日志已确认 Android 16 的
 * cancelAllNotificationsInt 为 pkg@2 / reason@7、BroadcastController.broadcastIntentLocked
 * 为 intent@3 / appOp@13，下面的用例就以这两个真实签名为基准。
 */
public class MethodArgsTest {

    /** 模拟 Android 15/16 的 cancelAllNotificationsInt：pkg@2(String) / reason@7(int)。 */
    private static Class<?>[] notificationSignature() {
        return new Class<?>[]{int.class, int.class, String.class, int.class, boolean.class,
                int.class, int.class, int.class, int.class};
    }

    @Test
    public void matches_acceptsTheAndroid16NotificationSignature() {
        assertTrue(MethodArgs.matches(notificationSignature(), 2, 7));
    }

    @Test
    public void matches_rejectsWhenReasonSlotIsNotInt() {
        Class<?>[] types = notificationSignature();
        types[7] = String.class;
        assertFalse(MethodArgs.matches(types, 2, 7));
    }

    @Test
    public void matches_rejectsWhenPkgSlotIsNotString() {
        Class<?>[] types = notificationSignature();
        types[2] = CharSequence.class;
        assertFalse(MethodArgs.matches(types, 2, 7));
    }

    @Test
    public void matches_rejectsWhenIndexIsOutOfRange() {
        Class<?>[] types = {int.class, String.class};
        assertFalse(MethodArgs.matches(types, 2, 1));
        assertFalse(MethodArgs.matches(types, 1, 5));
    }

    @Test
    public void matches_acceptsIntentAndAppOpPair() {
        Class<?>[] types = new Class<?>[20];
        for (int i = 0; i < types.length; i++) {
            types[i] = Object.class;
        }
        types[3] = FakeIntent.class;
        types[13] = int.class;
        assertTrue(MethodArgs.matches(types, 3, FakeIntent.class, 13));
    }

    @Test
    public void matches_rejectsWhenAppOpSlotIsNotInt() {
        // 这正是原先只检查"第 13 个参数是 int"时可能漏掉的情况：签名一变就命中错误的参数
        Class<?>[] types = new Class<?>[20];
        for (int i = 0; i < types.length; i++) {
            types[i] = Object.class;
        }
        types[3] = FakeIntent.class;
        types[13] = long.class;
        assertFalse(MethodArgs.matches(types, 3, FakeIntent.class, 13));
    }

    @Test
    public void firstIntIndex_returnsFirstCandidateThatIsInt() {
        Parameter[] params = method("sample").getParameters();
        assertEquals(2, MethodArgs.firstIntIndex(params, 1, 2, 3));
        assertEquals(3, MethodArgs.firstIntIndex(params, 3, 9));
    }

    @Test
    public void firstIntIndex_returnsMinusOneWhenNothingMatches() {
        Parameter[] params = method("sample").getParameters();
        assertEquals(-1, MethodArgs.firstIntIndex(params, 0));      // 第 0 个是 String
        assertEquals(-1, MethodArgs.firstIntIndex(params, 99));     // 越界
        assertEquals(-1, MethodArgs.firstIntIndex(new Parameter[0], 0));
    }

    @Test
    public void byName_findsIntentAndAppOpWhenParameterNamesAreRetained() {
        // 本模块以 -parameters 编译（见 build.gradle），所以这里能拿到真实参数名。
        // 注意 Android framework 通常没有保留参数名，真机上这条兜底路径多数会返回 null。
        Parameter[] params = method("broadcastIntentLocked").getParameters();
        assertArrayEquals(new int[]{1, 4}, MethodArgs.byName(params, String.class));
    }

    @Test
    public void byName_returnsNullWhenEitherNameIsMissing() {
        assertNull(MethodArgs.byName(method("sample").getParameters(), String.class));
    }

    /** 代替 android.content.Intent，让单元测试不依赖 Android。 */
    private static final class FakeIntent {
    }

    @SuppressWarnings("unused")
    private static void sample(String a, String b, int c, int d) {
    }

    @SuppressWarnings("unused")
    private static void broadcastIntentLocked(Object caller, String intent, Object x, Object y, int appOp) {
    }

    private static Method method(String name) {
        for (Method m : MethodArgsTest.class.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        throw new IllegalStateException("测试用的样例方法不存在: " + name);
    }
}
