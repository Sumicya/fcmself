package sumicya.fcmself.core

import android.content.Intent

/**
 * 推送族 Intent 识别 —— 所有 Fix 模块共用的唯一介入判据（自由化基线）。
 *
 * 覆盖 GMS 推送链路上的全部 action：
 * - c2dm：`*.android.c2dm.intent.RECEIVE`（消息投递）/ `.REGISTRATION`（注册回执）；
 * - Firebase：MESSAGING_EVENT / INSTANCE_ID_EVENT / NEW_TOKEN。
 *
 * 设计基准是「还原原生 AOSP 行为」：原生系统对这些推送广播不加任何自启动/通知限制。
 * 因此各 Hook 点的介入面统一为「推送族 action + 目标包名可解析」——旧版里
 * HyperOS 两处对任意定向广播放行（过宽）、`SmartPowerPolicyManager` 只认
 * MESSAGING_EVENT（过窄）的各自为政，在本版全部收敛。
 */
object Push {

    /** c2dm 家族用后缀匹配（历史上有 com.google.android.c2dm 等前缀变体）。 */
    private val PUSH_ACTION_SUFFIXES =
        arrayOf(".android.c2dm.intent.RECEIVE", ".android.c2dm.intent.REGISTRATION")

    private val PUSH_ACTIONS = setOf(
        "com.google.firebase.MESSAGING_EVENT",
        "com.google.firebase.INSTANCE_ID_EVENT",
        "com.google.firebase.NEW_TOKEN",
    )

    /** action 是否属于推送族。 */
    fun isPushAction(action: String?): Boolean =
        action != null && (action in PUSH_ACTIONS || PUSH_ACTION_SUFFIXES.any { action.endsWith(it) })

    fun isPushIntent(intent: Intent?): Boolean = intent != null && isPushAction(intent.action)

    /** 从定向 Intent 中解析目标包名（显式 component 优先，其次 package）。 */
    fun targetOf(intent: Intent?): String? = intent?.let { it.component?.packageName ?: it.getPackage() }

    /** 目标包名是否可定位（本模块无白名单：只要能定位目标应用就介入）。 */
    fun hasTarget(packageName: String?): Boolean = !packageName.isNullOrEmpty()

    /** 一条广播是否为「发往明确目标的推送」——绝大多数 Hook 点的统一介入条件。 */
    fun isTargetedPush(intent: Intent?): Boolean = isPushIntent(intent) && hasTarget(targetOf(intent))
}
