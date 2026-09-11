package com.koljs.controller

import java.util.concurrent.TimeUnit

/**
 * 会话时间估算工具
 */
object TimeEstimator {

    /** 预计剩余秒数（不小于 0） */
    fun remainingSeconds(startMillis: Long, plannedHours: Int, nowMillis: Long = System.currentTimeMillis()): Long {
        val total = TimeUnit.HOURS.toSeconds(plannedHours.toLong().coerceAtLeast(1))
        val elapsed = (nowMillis - startMillis) / 1000
        return (total - elapsed).coerceAtLeast(0)
    }

    /** 秒数格式化为 "X 小时 Y 分" / "Y 分钟" */
    fun format(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return if (h > 0) "${h} 小时 ${m} 分" else "${m} 分钟"
    }
}
