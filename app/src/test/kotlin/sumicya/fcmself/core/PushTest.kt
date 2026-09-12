package sumicya.fcmself.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [Push] 的单元测试。
 *
 * 只测不依赖 android.content.Intent 实例的部分（action 分类与包名判据）；
 * 涉及 Intent 的路径（isPushIntent / targetOf / isTargetedPush）在真机验证清单里覆盖。
 */
class PushTest {

    @Test
    fun isPushAction_coversTheWholePushFamily() {
        assertTrue(Push.isPushAction("com.google.android.c2dm.intent.RECEIVE"))
        assertTrue(Push.isPushAction("com.google.android.c2dm.intent.REGISTRATION"))
        assertTrue(Push.isPushAction("com.google.firebase.MESSAGING_EVENT"))
        assertTrue(Push.isPushAction("com.google.firebase.INSTANCE_ID_EVENT"))
        assertTrue(Push.isPushAction("com.google.firebase.NEW_TOKEN"))
    }

    @Test
    fun isPushAction_rejectsEverythingElse() {
        assertFalse(Push.isPushAction(null))
        assertFalse(Push.isPushAction(""))
        assertFalse(Push.isPushAction("android.intent.action.BOOT_COMPLETED"))
        assertFalse(Push.isPushAction("com.google.android.c2dm.intent.RECEIVE.extra"))  // 后缀不完整
        assertFalse(Push.isPushAction("com.example.intent.RECEIVE"))
    }

    @Test
    fun hasTarget_requiresNonBlankPackageName() {
        assertTrue(Push.hasTarget("com.example.app"))
        assertFalse(Push.hasTarget(null))
        assertFalse(Push.hasTarget(""))
    }
}
