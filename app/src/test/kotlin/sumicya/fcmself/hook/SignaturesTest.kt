package sumicya.fcmself.hook

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

import java.lang.reflect.Method

/**
 * [Signatures] 的单元测试：签名自适应解析的候选校验与兜底路径。
 *
 * 用合成的「假签名」方法模拟各版本 ROM 的真实形态（下标以真机核实的数据为准）：
 * - Android 15/16 BroadcastController.broadcastIntentLocked：intent@3 / appOp@13；
 * - Android 13 AMS.broadcastIntentLocked：intent@3 / appOp@12；
 * - Android 16 cancelAllNotificationsInt：pkg@2 / reason@7（9 参形态）；
 * - Android 14 cancelAllNotificationsInt：pkg@2 / reason@8（10 参形态）。
 */
class SignaturesTest {

    /** 模拟 Android 15/16 的 BroadcastController.broadcastIntentLocked：intent@3 / appOp@13。 */
    @Suppress("unused")
    private fun controller16(
        pid: Int, uid: Int, caller: Any, intent: FakeIntent, resolvedType: String,
        resultTo: Any, resultCode: Int, resultData: String, resultExtras: Any,
        permissions: Array<String>, extraParam: Any, options: Any,
        serialized: Boolean, appOp: Int, sticky: Boolean, userId: Int,
    ) {
    }

    /** 模拟 Android 13 的 AMS.broadcastIntentLocked：intent@3 / appOp@12。 */
    @Suppress("unused")
    private fun ams13(
        caller: Any, pid: Int, uid: Int, intent: FakeIntent, resolvedType: String,
        resultTo: Any, resultCode: Int, resultData: String, resultExtras: Any,
        permissions: Array<String>, options: Any,
        serialized: Boolean, appOp: Int, sticky: Boolean, userId: Int,
    ) {
    }

    /** 模拟未知新版本：候选（@12/@13 都是 Boolean）全失效，但参数名兜底可定位 intent@3 / appOp@14。 */
    @Suppress("unused")
    private fun future(
        a: Any, pid: Int, uid: Int, intent: FakeIntent, b: String, c: Any, d: Int, e: String, f: Any,
        g: Array<String>, h: Any, i: Any, j: Boolean, k: Boolean, appOp: Int, l: Int,
    ) {
    }

    /** 模拟 Android 16 的 cancelAllNotificationsInt（9 参）：pkg@2 / reason@7。 */
    @Suppress("unused")
    private fun notifications16(
        callingUid: Int, userId: Int, pkg: String, mustHaveFlags: Boolean, mustBeBlocked: Boolean,
        mustNotPost: Boolean, wasPosted: Boolean, reason: Int, userIdOfCaller: Int,
    ) {
    }

    /** 模拟 Android 14 的 cancelAllNotificationsInt（10 参）：pkg@2 / reason@8。 */
    @Suppress("unused")
    private fun notifications14(
        callingUid: Int, userId: Int, pkg: String, mustHaveFlags: Boolean, mustBeBlocked: Boolean,
        mustNotPost: Boolean, wasPosted: Boolean, reserved: Int, reason: Int, userIdOfCaller: Int,
    ) {
    }

    // ---- broadcastIntentLocked ------------------------------------------

    @Test
    fun broadcastArgs_prefersTheMatchingCandidate() {
        // 34+ 候选先试 (3,13)：正中 Android 15/16 形态
        assertEquals(
            3 to 13,
            Signatures.resolveBroadcastArgs(method("controller16"), FakeIntent::class.java, listOf(3 to 13, 3 to 12)),
        )
        // AMS 33 候选：(3,11) 类型不符被拒，(3,12) 命中
        assertEquals(
            3 to 12,
            Signatures.resolveBroadcastArgs(method("ams13"), FakeIntent::class.java, listOf(3 to 11, 3 to 12)),
        )
    }

    @Test
    fun broadcastArgs_rejectsMismatchedCandidateThenFallsBackToParameterNames() {
        // controller16 的 @12 是 Boolean：候选 (3,12) 必须被类型校验拒绝，
        // 随后参数名兜底找到真实的 intent@3 / appOp@13
        assertEquals(
            3 to 13,
            Signatures.resolveBroadcastArgs(method("controller16"), FakeIntent::class.java, listOf(3 to 12)),
        )
    }

    @Test
    fun broadcastArgs_fallsBackToParameterNamesWhenAllCandidatesFail() {
        // future 的 @12/@13 都是 Boolean，两个候选全失效 → 参数名兜底 intent@3 / appOp@14
        assertEquals(
            3 to 14,
            Signatures.resolveBroadcastArgs(method("future"), FakeIntent::class.java, listOf(3 to 13, 3 to 12)),
        )
    }

    @Test
    fun broadcastArgs_returnsNullAndLogsWhenEverythingFails() {
        val logs = mutableListOf<String>()
        val resolved = Signatures.resolveBroadcastArgs(method("noIntentAtAll"), FakeIntent::class.java, listOf(3 to 13)) {
            logs += it
        }
        assertNull(resolved)
        assertNotNull(logs.singleOrNull())  // 失败时必须给出可用于排查的签名日志
    }

    // ---- cancelAllNotificationsInt --------------------------------------

    @Test
    fun notificationArgs_acceptsTheAndroid16Shape() {
        // API 35+ 候选顺序 [7, 8]
        assertEquals(2 to 7, Signatures.resolveNotificationArgs(method("notifications16"), listOf(7, 8)))
    }

    @Test
    fun notificationArgs_acceptsTheAndroid14Shape() {
        // API 34 候选顺序 [8, 7]：10 参形态 reason@8 先命中（注意 @7 也是 int，顺序即消歧）
        assertEquals(2 to 8, Signatures.resolveNotificationArgs(method("notifications14"), listOf(8, 7)))
    }

    @Test
    fun notificationArgs_returnsNullAndLogsWhenCandidatesFail() {
        val logs = mutableListOf<String>()
        val resolved = Signatures.resolveNotificationArgs(method("notifications16"), emptyList()) { logs += it }
        assertNull(resolved)
        assertNotNull(logs.singleOrNull())
    }

    @Suppress("unused", "UNUSED_PARAMETER")
    private fun noIntentAtAll(a: Int, b: String, c: Boolean) {
    }

    /** 代替 android.content.Intent，让单元测试不依赖 Android。 */
    private class FakeIntent

    private fun method(name: String): Method =
        SignaturesTest::class.java.declaredMethods.firstOrNull { it.name == name }
            ?: throw IllegalStateException("测试用的样例方法不存在: $name")
}
