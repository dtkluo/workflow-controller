package com.koljs.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/** 5s × 12 = 60s，巡检总时限的期望值（写死以捕捉常量被改动） */
private const val EXPECTED_TIMEOUT_MS = 60_000L

/**
 * [SessionState] 的纯 JVM 单测（无 Android 依赖，`app/src/test` 下直接跑）。
 *
 * 覆盖的是两个线上症状的根因防线：
 *
 * ①「按了好几次停止云桌面才生效」
 *    根因：对**已结束**的 run 调 cancel，GitHub 返回 409
 *    `Cannot cancel a workflow run that is completed.`，旧代码把 409/422 一并当成功，
 *    界面谎报「已发送停止命令」，用户只能反复点。
 *    防线：[SessionState.isCancelable] 对已结束 run 必须 false；
 *    [CancelOutcome.ALREADY_FINISHED] 必须与 [CancelOutcome.ACCEPTED] 严格区分。
 *
 * ②「点击立即刷新不会刷新状态」
 *    根因：刷新被静默吞掉 / 状态判定不可靠。
 *    防线：[SessionState.evaluateCancelWatch] 在「本轮没拉到 run（网络抖动）」时
 *    必须继续等待，绝不能误判成「已停止」。
 *
 * 另外枚举值个数与名称也做了断言：`MainActivity` 里 `when (outcome)` 是**无 else 的
 * 穷举分支**（作为语句使用，Kotlin 只给警告不给错误），一旦有人新增枚举值却忘了改那里，
 * 分支会被静默跳过 —— 这类回归只能靠这里的断言拦住。
 */
class SessionStateTest {

    private val createdAt: Date = Date(1_700_000_000_000L)

    private fun runInfo(
        id: Long = 1L,
        status: String = "in_progress",
        conclusion: String? = null
    ): RunInfo = RunInfo(id = id, status = status, conclusion = conclusion, createdAt = createdAt)

    // ---------- isActive：会话是否尚未结束 ----------

    @Test
    fun `isActive 把全部未结束状态都判为活跃`() {
        listOf("requested", "waiting", "pending", "queued", "in_progress").forEach { status ->
            assertTrue(
                "status=$status 属于过渡态或运行中，应判为活跃",
                SessionState.isActive(status)
            )
        }
    }

    @Test
    fun `isActive 对 completed 判为非活跃`() {
        assertFalse(
            "completed 表示本次运行已结束，不得再判为活跃",
            SessionState.isActive("completed")
        )
    }

    @Test
    fun `isActive 对 null 判为非活跃`() {
        assertFalse(
            "status 为 null（从未拉到过 run）时不得判为活跃",
            SessionState.isActive(null)
        )
    }

    @Test
    fun `isActive 对未知状态判为非活跃`() {
        assertFalse(
            "未知状态一律按非活跃处理，否则会误启用停止按钮",
            SessionState.isActive("who_knows")
        )
    }

    // ---------- isCancelable：取消防线的核心 ----------

    @Test
    fun `isCancelable 对运行中及排队中的 run 返回 true`() {
        assertTrue(SessionState.isCancelable(runInfo(status = "in_progress")))
        assertTrue(SessionState.isCancelable(runInfo(status = "queued")))
        assertTrue(SessionState.isCancelable(runInfo(status = "requested")))
        assertTrue(SessionState.isCancelable(runInfo(status = "waiting")))
    }

    @Test
    fun `isCancelable 对已结束的 run 一律返回 false`() {
        // 这是「按了好几次停止才生效」的核心防线：对已结束的 run 发 cancel，
        // GitHub 只会回 409，而旧代码把它算成成功 —— 界面谎报、机器照跑。
        assertFalse(
            "completed(success) 的 run 绝不能作为取消目标",
            SessionState.isCancelable(runInfo(status = "completed", conclusion = "success"))
        )
        assertFalse(
            "completed(failure) 的 run 绝不能作为取消目标",
            SessionState.isCancelable(runInfo(status = "completed", conclusion = "failure"))
        )
        assertFalse(
            "已被手动停止（cancelled）的 run 不可再取消",
            SessionState.isCancelable(runInfo(status = "completed", conclusion = "cancelled"))
        )
        assertFalse(
            "conclusion 为 null 的 completed 同样不可取消",
            SessionState.isCancelable(runInfo(status = "completed", conclusion = null))
        )
    }

    @Test
    fun `isCancelable 对 null 返回 false`() {
        assertFalse("没有任何 run 信息时不许发取消", SessionState.isCancelable(null))
    }

    @Test
    fun `对已结束会话点停止的完整判定链都不会放行`() {
        val finished = runInfo(id = 9L, status = "completed", conclusion = "success")
        assertFalse("isActive 应挡住", SessionState.isActive(finished.status))
        assertFalse("hasActiveSession 应挡住", SessionState.hasActiveSession(finished))
        assertFalse("isCancelable 应挡住", SessionState.isCancelable(finished))
    }

    // ---------- isPending / isRunning / isCompleted / hasActiveSession ----------

    @Test
    fun `isPending 只认过渡态`() {
        listOf("requested", "waiting", "pending", "queued").forEach { status ->
            assertTrue("status=$status 应属于过渡态", SessionState.isPending(status))
        }
        assertFalse("in_progress 不属于过渡态", SessionState.isPending("in_progress"))
        assertFalse("completed 不属于过渡态", SessionState.isPending("completed"))
        assertFalse("null 不属于过渡态", SessionState.isPending(null))
    }

    @Test
    fun `isRunning 只认 in_progress`() {
        assertTrue(SessionState.isRunning("in_progress"))
        assertFalse(SessionState.isRunning("queued"))
        assertFalse(SessionState.isRunning("completed"))
        assertFalse(SessionState.isRunning(null))
    }

    @Test
    fun `isCompleted 只认 completed`() {
        assertTrue(SessionState.isCompleted("completed"))
        assertFalse(SessionState.isCompleted("in_progress"))
        assertFalse(SessionState.isCompleted("queued"))
        assertFalse(SessionState.isCompleted(null))
    }

    @Test
    fun `hasActiveSession 综合 run 整体判定`() {
        assertFalse("没有 run 时不算活跃会话", SessionState.hasActiveSession(null))
        assertTrue(SessionState.hasActiveSession(runInfo(status = "in_progress")))
        assertTrue(SessionState.hasActiveSession(runInfo(status = "queued")))
        assertFalse(SessionState.hasActiveSession(runInfo(status = "completed")))
        assertFalse(SessionState.hasActiveSession(runInfo(status = "who_knows")))
    }

    @Test
    fun `过渡态集合与活跃态集合保持一致`() {
        assertEquals(
            setOf("requested", "waiting", "pending", "queued"),
            SessionState.PENDING_STATUSES
        )
        assertEquals(
            "活跃态 = 过渡态 + in_progress，两者必须同步维护",
            SessionState.PENDING_STATUSES + "in_progress",
            SessionState.ACTIVE_STATUSES
        )
        assertEquals(5, SessionState.ACTIVE_STATUSES.size)
    }

    // ---------- evaluateCancelWatch：取消确认巡检 ----------

    @Test
    fun `巡检中目标 run 仍在跑且未超时则继续等待`() {
        assertEquals(
            CancelWatchVerdict.CONTINUE,
            SessionState.evaluateCancelWatch(
                targetRunId = 1L,
                latest = runInfo(id = 1L, status = "in_progress"),
                elapsedMs = 0L
            )
        )
    }

    @Test
    fun `巡检中目标 run 变成 completed 即确认已停止`() {
        assertEquals(
            CancelWatchVerdict.CONFIRMED,
            SessionState.evaluateCancelWatch(
                targetRunId = 1L,
                latest = runInfo(id = 1L, status = "completed", conclusion = "cancelled"),
                elapsedMs = 5_000L
            )
        )
    }

    @Test
    fun `巡检中出现新的 run id 说明目标已出栈，判为确认`() {
        assertEquals(
            "最新一条已经不是目标 run，说明它已结束并被新 run 取代",
            CancelWatchVerdict.CONFIRMED,
            SessionState.evaluateCancelWatch(
                targetRunId = 1L,
                latest = runInfo(id = 2L, status = "in_progress"),
                elapsedMs = 0L
            )
        )
    }

    @Test
    fun `巡检中本轮没拉到 run 且未超时不得误判为成功`() {
        assertEquals(
            "latest 为 null 只是这一轮网络没拉到，绝不能当成『已停止』",
            CancelWatchVerdict.CONTINUE,
            SessionState.evaluateCancelWatch(
                targetRunId = 1L,
                latest = null,
                elapsedMs = 0L
            )
        )
        assertEquals(
            CancelWatchVerdict.CONTINUE,
            SessionState.evaluateCancelWatch(
                targetRunId = 1L,
                latest = null,
                elapsedMs = EXPECTED_TIMEOUT_MS - 1
            )
        )
    }

    @Test
    fun `巡检中本轮没拉到 run 且已超时判为超时`() {
        assertEquals(
            CancelWatchVerdict.TIMEOUT,
            SessionState.evaluateCancelWatch(
                targetRunId = 1L,
                latest = null,
                elapsedMs = EXPECTED_TIMEOUT_MS
            )
        )
    }

    @Test
    fun `巡检超时边界——elapsed 恰好等于 timeout 即判超时`() {
        assertEquals(
            ">= 而非 >：正好到时限就应放弃等待",
            CancelWatchVerdict.TIMEOUT,
            SessionState.evaluateCancelWatch(
                targetRunId = 1L,
                latest = runInfo(id = 1L, status = "in_progress"),
                elapsedMs = EXPECTED_TIMEOUT_MS
            )
        )
        assertEquals(
            "差 1 毫秒仍在时限内，应继续等",
            CancelWatchVerdict.CONTINUE,
            SessionState.evaluateCancelWatch(
                targetRunId = 1L,
                latest = runInfo(id = 1L, status = "in_progress"),
                elapsedMs = EXPECTED_TIMEOUT_MS - 1
            )
        )
    }

    @Test
    fun `巡检中确认到结束优先于超时`() {
        assertEquals(
            "最后一刻才拿到 completed，也应报成功而不是超时",
            CancelWatchVerdict.CONFIRMED,
            SessionState.evaluateCancelWatch(
                targetRunId = 1L,
                latest = runInfo(id = 1L, status = "completed"),
                elapsedMs = EXPECTED_TIMEOUT_MS + 1
            )
        )
        assertEquals(
            "目标已出栈同样优先于超时",
            CancelWatchVerdict.CONFIRMED,
            SessionState.evaluateCancelWatch(
                targetRunId = 1L,
                latest = runInfo(id = 2L, status = "in_progress"),
                elapsedMs = EXPECTED_TIMEOUT_MS + 1
            )
        )
    }

    @Test
    fun `evaluateCancelWatch 支持自定义时限`() {
        assertEquals(
            CancelWatchVerdict.TIMEOUT,
            SessionState.evaluateCancelWatch(
                targetRunId = 1L,
                latest = runInfo(id = 1L, status = "in_progress"),
                elapsedMs = 30_000L,
                timeoutMs = 10_000L
            )
        )
        assertEquals(
            "同一个 elapsed 在更大的自定义时限下应继续等待，证明 timeoutMs 真的生效",
            CancelWatchVerdict.CONTINUE,
            SessionState.evaluateCancelWatch(
                targetRunId = 1L,
                latest = runInfo(id = 1L, status = "in_progress"),
                elapsedMs = 30_000L,
                timeoutMs = 60_000L
            )
        )
    }

    // ---------- 常量自洽 ----------

    @Test
    fun `巡检常量自洽——总时限等于间隔乘以最大次数`() {
        assertEquals(5_000L, SessionState.CANCEL_WATCH_INTERVAL_MS)
        assertEquals(12, SessionState.CANCEL_WATCH_MAX_ATTEMPTS)
        assertEquals(EXPECTED_TIMEOUT_MS, SessionState.CANCEL_WATCH_TIMEOUT_MS)
        assertTrue(
            "改了间隔或次数必须同步改总时限，否则巡检会提前放弃或空转",
            SessionState.CANCEL_WATCH_TIMEOUT_MS ==
                SessionState.CANCEL_WATCH_INTERVAL_MS * SessionState.CANCEL_WATCH_MAX_ATTEMPTS
        )
    }

    // ---------- 枚举契约（防止 when 分支静默漏处理） ----------

    @Test
    fun `CancelOutcome 枚举值的个数与名称不得被悄悄改动`() {
        val names = CancelOutcome.values().map { it.name }
        assertEquals(
            "MainActivity 的 when(outcome) 无 else，新增/改名必须同步改那里",
            listOf("ACCEPTED", "ALREADY_FINISHED", "FAILED"),
            names
        )
        assertEquals(3, names.size)
    }

    @Test
    fun `ALREADY_FINISHED 与 ACCEPTED 必须是两个不同结果`() {
        // 若有人把 409 的 ALREADY_FINISHED 合并回 ACCEPTED，
        // 「取消了一个已经结束的会话」又会变成界面上的谎报成功。
        assertFalse(
            "409/422 绝不能等同于 202 受理成功",
            CancelOutcome.ALREADY_FINISHED == CancelOutcome.ACCEPTED
        )
        assertEquals(CancelOutcome.ALREADY_FINISHED, CancelOutcome.valueOf("ALREADY_FINISHED"))
        assertEquals(CancelOutcome.ACCEPTED, CancelOutcome.valueOf("ACCEPTED"))
        assertEquals(CancelOutcome.FAILED, CancelOutcome.valueOf("FAILED"))
    }

    @Test
    fun `CancelWatchVerdict 枚举值的个数与名称不得被悄悄改动`() {
        val names = CancelWatchVerdict.values().map { it.name }
        assertEquals(
            "MainActivity 按 verdict 分支处理，新增/改名必须同步改那里",
            listOf("CONFIRMED", "CONTINUE", "TIMEOUT"),
            names
        )
        assertEquals(3, names.size)
        assertEquals(CancelWatchVerdict.CONFIRMED, CancelWatchVerdict.valueOf("CONFIRMED"))
        assertEquals(CancelWatchVerdict.CONTINUE, CancelWatchVerdict.valueOf("CONTINUE"))
        assertEquals(CancelWatchVerdict.TIMEOUT, CancelWatchVerdict.valueOf("TIMEOUT"))
    }
}
