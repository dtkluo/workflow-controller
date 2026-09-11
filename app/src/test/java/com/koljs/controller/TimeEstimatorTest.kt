package com.koljs.controller

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeEstimatorTest {

    @Test
    fun `剩余时间为计划时长减去已过时间`() {
        val start = 1_000_000L
        val now = start + 3600_000L // 已过 1 小时
        assertEquals(5 * 3600L, TimeEstimator.remainingSeconds(start, 6, now))
    }

    @Test
    fun `超时后剩余时间为零`() {
        val start = 0L
        val now = 7 * 3600_000L // 已过 7 小时
        assertEquals(0L, TimeEstimator.remainingSeconds(start, 6, now))
    }

    @Test
    fun `格式化包含小时和分钟`() {
        assertEquals("1 小时 30 分", TimeEstimator.format(5400L))
    }

    @Test
    fun `格式化不足一小时只显示分钟`() {
        assertEquals("45 分钟", TimeEstimator.format(2700L))
    }
}
