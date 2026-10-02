package com.koljs.controller

/**
 * 会话状态判定与「取消确认巡检」的纯逻辑。
 *
 * 本文件刻意**不引用任何 Android SDK**，便于用 JVM 单测覆盖（放在 `app/src/test` 下
 * 即可像 `TimeEstimatorTest` 那样直接跑）。`MainActivity` 只负责把界面文案套在这些
 * 判定结果上，业务判定集中在这里，避免 UI 层散落各处、改一处漏一处。
 */
object SessionState {

    /** `queued`：GitHub 已受理 dispatch，正在排队等待 runner */
    const val STATUS_QUEUED = "queued"

    /** `in_progress`：job 正在执行 */
    const val STATUS_IN_PROGRESS = "in_progress"

    /** `completed`：本次运行已结束，结论见 `conclusion` 字段 */
    const val STATUS_COMPLETED = "completed"

    /**
     * workflow_dispatch 之后、虚拟机真正跑起来之前的过渡态
     * （GitHub 会先后给出其中若干个）。
     */
    val PENDING_STATUSES: Set<String> = setOf("requested", "waiting", "pending", STATUS_QUEUED)

    /** 「会话尚未结束」的全部状态：过渡态 + 真正在跑 */
    val ACTIVE_STATUSES: Set<String> = PENDING_STATUSES + STATUS_IN_PROGRESS

    /** 是否处于过渡态（排队 / 等待分配虚拟机） */
    fun isPending(status: String?): Boolean = status != null && status in PENDING_STATUSES

    /** job 是否真正在执行 */
    fun isRunning(status: String?): Boolean = status == STATUS_IN_PROGRESS

    /** 本次运行是否已结束 */
    fun isCompleted(status: String?): Boolean = status == STATUS_COMPLETED

    /**
     * 会话是否仍然活跃（即尚未结束）。
     *
     * 与之等价的另一面：**活跃即意味着可以被取消**。
     */
    fun isActive(status: String?): Boolean = status != null && status in ACTIVE_STATUSES

    /** 当前是否存在活跃会话 */
    fun hasActiveSession(run: RunInfo?): Boolean = run != null && isActive(run.status)

    /**
     * 该 run 是否可以作为「停止」的目标。
     *
     * 只有尚未结束的 run 才值得发 cancel；对已结束的 run 发取消，GitHub 会回 409，
     * 界面上如果把它当成功，就会出现「停止成功但机器还在跑」的假象。
     */
    fun isCancelable(run: RunInfo?): Boolean = hasActiveSession(run)

    /** 取消确认巡检的轮询间隔 */
    const val CANCEL_WATCH_INTERVAL_MS = 5_000L

    /** 取消确认巡检的最大轮询次数（5s × 12 = 60s） */
    const val CANCEL_WATCH_MAX_ATTEMPTS = 12

    /** 取消确认巡检的总时限 */
    const val CANCEL_WATCH_TIMEOUT_MS = CANCEL_WATCH_INTERVAL_MS * CANCEL_WATCH_MAX_ATTEMPTS

    /**
     * 判定「取消确认巡检」的下一步动作。
     *
     * GitHub 的 `POST /actions/runs/{id}/cancel` 返回 202 只代表**已受理**，job 真正被杀
     * 需要一段时间（本 workflow 最后一个 step 是 PowerShell 长驻保活循环，还带着
     * easytier-core 子进程），所以必须持续轮询到真的确认结束为止，而不是发完就完。
     *
     * @param targetRunId 发出 cancel 时针对的 run id
     * @param latest 本轮拉到的最新一条 run；null 表示这一轮没拉到（网络问题），不因此判定失败
     * @param elapsedMs 自发出 cancel 起已经过去的时间
     * @param timeoutMs 巡检总时限，超过即判定 [CancelWatchVerdict.TIMEOUT]
     *
     * @return 确认成功 / 继续等待 / 超时未确认
     */
    fun evaluateCancelWatch(
        targetRunId: Long,
        latest: RunInfo?,
        elapsedMs: Long,
        timeoutMs: Long = CANCEL_WATCH_TIMEOUT_MS
    ): CancelWatchVerdict {
        if (latest != null) {
            // 用 **严格大于** 而不是「不等于」：GitHub 的 run id 单调递增，更新的 run 必然
            // 更大。若出现 id 变小，说明口径异常（将来若放开并发 run、或端点排序变了都可能如此），
            // 这时**不判成功** —— 它更可能意味着「我们要停的那个还没停掉」，让它继续轮询到
            // TIMEOUT，由用户决定是否再点一次，总好过谎报已停止。
            if (latest.id > targetRunId) return CancelWatchVerdict.CONFIRMED
            // 同一条 run 走到了 completed：回收已确认
            if (isCompleted(latest.status)) return CancelWatchVerdict.CONFIRMED
        }
        if (elapsedMs >= timeoutMs) return CancelWatchVerdict.TIMEOUT
        return CancelWatchVerdict.CONTINUE
    }
}

/**
 * GitHub `POST /actions/runs/{id}/cancel` 的结果分类。
 *
 * 以前的实现把 409/422 也当成成功，于是「取消了一个已经结束的会话」会被界面报成成功 ——
 * 这是「按了好几次停止才生效」的核心原因：用户看到成功、机器没动，只能反复点。
 */
enum class CancelOutcome {
    /** 202：已受理，job 会在稍后被真正杀掉 */
    ACCEPTED,

    /** 409/422：该实例已结束或不接受取消。本次 cancel 并没有停掉任何东西，不许报成功 */
    ALREADY_FINISHED,

    /** 网络层或其他错误导致请求根本没送达（由调用方捕获异常后转换而来） */
    FAILED
}

/** [SessionState.evaluateCancelWatch] 的结论 */
enum class CancelWatchVerdict {
    /** 目标 run 已结束，或已被新的 run 取代 */
    CONFIRMED,

    /** 还没确认出来，继续等下一轮 */
    CONTINUE,

    /** 超出巡检时限仍未确认 */
    TIMEOUT
}
