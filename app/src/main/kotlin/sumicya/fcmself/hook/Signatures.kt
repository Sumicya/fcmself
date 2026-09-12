package sumicya.fcmself.hook

import java.lang.reflect.Method

/**
 * 各 Hook 点的「签名自适应」解析 —— 自由化的核心之一。
 *
 * 旧版把参数下标散落在各模块里、按 SDK_INT 硬编码，每升一版系统都要人工核对一遍。
 * 本类把解析收敛成纯函数（不碰 Android 类，JVM 上可直接单测），策略是：
 *
 * 1. **已知数据点优先**：调用方按 API 版本给出经验证的 (参数下标) 候选列表；
 * 2. **候选逐一过类型校验**（[MethodArgs.matches]）：候选失效（ROM 改签名）不会误挂；
 * 3. **参数名兜底**：framework 以 -parameters 编译时直接按名字定位（多数设备无此条件）；
 * 4. **全部失败则放弃该 Hook 并打出完整签名**：宁可安全跳过，也不猜一个下标挂上去。
 *
 * 也就是说：已知版本零漂移，未知新版本自动走 2→3→4，把「适配」变成「看日志补候选」。
 */
object Signatures {

    /**
     * 解析 `broadcastIntentLocked` 的 (intent, appOp) 参数下标。
     *
     * @param candidates 按 API 版本给出的候选，如 `listOf(3 to 13, 3 to 12)`，依次尝试
     * @param log 全部候选失败时的诊断回调（调用方负责写日志）
     * @return (intentIndex, appOpIndex)，无法确定时返回 null
     */
    fun resolveBroadcastArgs(
        method: Method,
        intentType: Class<*>,
        candidates: List<Pair<Int, Int>>,
        log: (String) -> Unit = {},
    ): Pair<Int, Int>? {
        val types = method.parameterTypes
        for ((intentIndex, appOpIndex) in candidates) {
            if (MethodArgs.matches(types, intentIndex, intentType, appOpIndex)) {
                return intentIndex to appOpIndex
            }
        }
        MethodArgs.byName(method.parameters, intentType)?.let { return it[0] to it[1] }
        log(
            "broadcastIntentLocked 参数位置无法确定（参数=${types.contentToString()}，" +
                "候选=$candidates，参数名兜底失败）"
        )
        return null
    }

    /**
     * 解析 `cancelAllNotificationsInt` 的 (pkg, reason) 参数下标。
     * pkg 历来固定 @2；reason 的候选由调用方按 API 版本给出（不同版本的签名经历了
     * 10 参 reason@8 与 8/9 参 reason@7 两代形态，候选顺序本身承载了消歧规则）。
     */
    fun resolveNotificationArgs(
        method: Method,
        reasonCandidates: List<Int>,
        log: (String) -> Unit = {},
    ): Pair<Int, Int>? {
        val types = method.parameterTypes
        for (reasonIndex in reasonCandidates) {
            if (MethodArgs.matches(types, 2, reasonIndex)) {
                return 2 to reasonIndex
            }
        }
        log(
            "cancelAllNotificationsInt 签名与预期不符，已跳过该 Hook 以免误拦截通知：" +
                "参数=${types.contentToString()}，预期 pkg@2(String)，reason 候选=$reasonCandidates(int)"
        )
        return null
    }
}
